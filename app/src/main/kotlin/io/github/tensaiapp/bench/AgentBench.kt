package io.github.tensaiapp.bench

import android.content.Intent
import android.content.res.AssetManager
import android.util.Log
import io.github.tensaiapp.BuildConfig
import io.github.tensaiapp.agent.BasePrompt
import io.github.tensaiapp.agent.ReplyGenerator
import io.github.tensaiapp.agent.SystemPrompt
import io.github.tensaiapp.agent.ToolRegistry
import io.github.tensaiapp.runtime.ActiveSessionTracker
import io.github.tensaiapp.runtime.QueueState
import io.github.tensaiapp.runtime.RuntimeGraph
import io.github.tensaiapp.runtime.WarmUpOutcome
import io.github.tensaiapp.runtime.residentModel
import io.github.tensaiapp.ui.screens.chat.DefaultEnableThinking
import io.github.tensaiapp.ui.screens.chat.replySampling
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Debug builds only: runs the prompts of `assets/bench/agent-prompts.json` (a debug asset, absent from a release)
 * through the chat's agent loop on the model already loaded, and logs one JSON line per prompt and a summary on
 * [TAG], so how often the model calls a tool it should not, or misses one, is measured rather than guessed.
 *
 * `adb shell am start -n io.github.tensaiapp/.MainActivity --activity-single-top --es bench agent [--ez thinking false]
 * [--ez base_prompt false] [--ez examples true] [--ez tool_grammar false]`, or "Run agent bench" in Settings > This device, which shows
 * [status] (the summary included) on screen.
 *
 * The requests carry the system message a chat would, built by [SystemPrompt] from the toggles of [BenchTrigger]
 * (base prompt, few-shot examples), without the user's instructions.
 *
 * Nothing is stored: each prompt is a one-message conversation in memory, after the tool prefix is warmed so the
 * first one does not pay for it. The conversation the KV cache held is saved first and the cache no longer named
 * after it, so the bench's tokens are never saved as its session; its next send prefills again. So is a conversation
 * the user sends to during the run, before the next prompt ([runBench]). Calls with an effect outside the reply are
 * refused, see [benchRuns]. The run stops when the model goes or the queue refuses a turn.
 */
object AgentBench {
    private const val TAG = "tensai.bench"
    private const val PROMPTS = "bench/agent-prompts.json"
    const val EXTRA_BENCH = "bench"
    const val EXTRA_THINKING = "thinking"
    const val EXTRA_BASE_PROMPT = "base_prompt"
    const val EXTRA_EXAMPLES = "examples"
    const val EXTRA_TOOL_GRAMMAR = "tool_grammar"
    private const val WarmRetryMs = 500L

    private val _status = MutableStateFlow<BenchStatus>(BenchStatus.Idle)

    /** The last run, for the debug panel: whatever started it. */
    val status: StateFlow<BenchStatus> = _status.asStateFlow()

    /** Reads [intent] from `onCreate` / `onNewIntent`; anything but a debug build's `bench=agent` is ignored. */
    fun offer(intent: Intent?, assets: AssetManager) {
        fun flag(name: String) = intent?.takeIf { it.hasExtra(name) }?.getBooleanExtra(name, false)
        val trigger = benchTrigger(
            BuildConfig.DEBUG,
            intent?.getStringExtra(EXTRA_BENCH),
            thinking = flag(EXTRA_THINKING),
            defaultThinking = DefaultEnableThinking,
            basePrompt = flag(EXTRA_BASE_PROMPT),
            examples = flag(EXTRA_EXAMPLES),
            toolGrammar = flag(EXTRA_TOOL_GRAMMAR),
        ) ?: return
        // Consumed once: a recreation must not run it again.
        intent?.removeExtra(EXTRA_BENCH)
        start(trigger, assets)
    }

    /** Runs [trigger] on the application scope, so leaving the screen does not stop it. Debug builds only, one run at a time. */
    fun start(trigger: BenchTrigger, assets: AssetManager) {
        if (!BuildConfig.DEBUG) return
        if (!BenchGate.tryEnter()) {
            // Logged only: the panel keeps showing the run that is going.
            Log.e(TAG, benchErrorLine("a bench is already running"))
            return
        }
        _status.value = BenchStatus.Running(done = 0, total = null)
        RuntimeGraph.applicationScope.launch {
            try {
                run(trigger, assets)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("the run threw ${e::class.simpleName}: ${e.message}")
            } finally {
                // Never left on Running: the panel would wait for good.
                if (_status.value is BenchStatus.Running) _status.value = BenchStatus.Failed("the run ended without a summary")
                BenchGate.leave()
            }
        }
    }

    private suspend fun run(trigger: BenchTrigger, assets: AssetManager) {
        val prompts = runCatching { parseBenchPrompts(assets.open(PROMPTS).bufferedReader().use { it.readText() }) }
            .getOrElse { return fail("cannot read $PROMPTS: ${it.message}") }
        _status.value = BenchStatus.Running(done = 0, total = prompts.size)
        val queue = RuntimeGraph.inferenceQueue
        val state = queue.state.value
        val info = state.residentModel ?: return fail("no model loaded, load one first")
        if (state !is QueueState.ModelReady) return fail("the model is busy ($state), run the bench once it is idle")
        val entity = RuntimeGraph.modelRepository.models.first().firstOrNull { it.id == info.modelId }
        if (entity?.toolsAvailable != true) return fail("the loaded model's template takes no tools")

        val gate = RefusingGate()
        val registry = ToolRegistry(RuntimeGraph.toolRegistry.registered().map(::BenchTool), RuntimeGraph.toolSettings)
        val runtime = RuntimeGraph.agentRuntimeOn(registry, gate)
        if (!runtime.hasTools()) return fail("no tool is granted")
        val system = SystemPrompt(base = trigger.basePrompt, examples = trigger.examples)
            .build(tools = runtime.definitions().mapTo(HashSet()) { it.name }, startedAt = null, global = null, chat = null)
        val runner = BenchRunner(
            runtime = runtime,
            gate = gate,
            generator = ReplyGenerator { queue.generate(it) },
            sampling = replySampling(null, trigger.thinking, entity.thinkingAvailable).copy(constrainToolCalls = trigger.toolGrammar),
            countTokens = { text -> queue.countTokens(listOf(text))?.firstOrNull() },
            system = system,
        )

        // The cache's owner is saved, then forgotten: from here the cache holds the bench, whatever is saved next.
        // Again before each prompt: a chat send in between has made its conversation the owner.
        val handOver = suspend {
            handOverCache(
                ActiveSessionTracker.current.value,
                save = { id, key -> RuntimeGraph.sessionRepository.save(id, key, saveOnEngine = queue::saveSession) },
                forget = ActiveSessionTracker::clearIf,
            )
        }
        if (!handOver()) return fail("could not save the open conversation's session, bench not run")
        var warmed = queue.warmToolPrefix(runner.prefixRequest(), expectedModel = info, replaceSaved = queue.currentKvTokenCount())
        if (warmed != WarmUpOutcome.Warmed) {
            // Most likely the chat's own warm-up holding the engine: stopped, then once more.
            queue.cancelWarmUp()
            delay(WarmRetryMs)
            warmed = queue.warmToolPrefix(runner.prefixRequest(), expectedModel = info, replaceSaved = queue.currentKvTokenCount())
        }

        Log.i(
            TAG,
            buildJsonObject {
                put("event", "start")
                put("model", info.name)
                put("thinking", trigger.thinking)
                put("base_prompt", trigger.basePrompt)
                put("base_prompt_version", BasePrompt.VERSION)
                put("examples", trigger.examples)
                put("tool_grammar", trigger.toolGrammar)
                put("thinking_supported", entity.thinkingAvailable)
                put("tools", JsonArray(runner.availableTools().map { JsonPrimitive(it) }))
                put("prompts", prompts.size)
                put("prefix", warmed.name)
            }.toString(),
        )
        var done = 0
        val results = runBench(
            prompts,
            runner,
            firstCold = warmed != WarmUpOutcome.Warmed,
            beforeEach = {
                when {
                    queue.state.value.residentModel != info -> "the model was unloaded or replaced"
                    !handOver() -> "could not save the session of a conversation sent to meanwhile"
                    else -> null
                }
            },
            log = { Log.i(TAG, it) },
            onResult = { _status.value = BenchStatus.Running(done = ++done, total = prompts.size) },
        )
        val summary = BenchSummary.of(results)
        Log.i(TAG, summary.toLogLine(trigger))
        _status.value = BenchStatus.Finished(summary.toDisplayLines(trigger, info.name, results), stoppedEarly = results.size < prompts.size)
    }

    /** Logs why the bench does not run, as an `error` line, and shows it. */
    private fun fail(message: String) {
        Log.e(TAG, benchErrorLine(message))
        _status.value = BenchStatus.Failed(message)
    }
}
