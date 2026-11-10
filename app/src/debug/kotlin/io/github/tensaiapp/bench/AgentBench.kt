package io.github.tensaiapp.bench

import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Debug builds only (this source set, the release one has a stub that ignores the intent): runs the prompts of
 * `assets/bench/agent-prompts.json` (a debug asset, absent from a release) through the chat's agent loop on the model
 * already loaded, and logs one JSON line per prompt and a summary on [TAG], so how often the model calls a tool it
 * should not, or misses one, is measured rather than guessed.
 *
 * `adb shell am start -n io.github.tensaiapp/.MainActivity --activity-single-top --es bench agent [--ez thinking false]
 * [--ez base_prompt false] [--ez examples true] [--ez tool_grammar false] [--ez greedy true] [--ei reasoning_budget 256]
 * [--ez adaptive_reasoning true] [--es tool_policy current|always|always-strict] [--es prompt_list web]`,
 * "Run agent bench" in Settings > This device, which shows [status] (the summary included) on screen, or Settings >
 * Bench (TEN-187), which shows [progress]. Wherever it starts, a run that graded a prompt is saved as a [BenchRun] in
 * [BenchRunStore], a run stopped early ([stop], the model gone) as a partial one.
 *
 * The requests carry the system message a chat would, built by [SystemPrompt] from the toggles of [BenchTrigger]
 * (base prompt, few-shot examples, the stricter tool line of [BenchTrigger.toolPolicy]) and the tools the turn offers,
 * without the user's instructions. The runtime runs that same policy.
 *
 * No chat is stored: each prompt is a one-message conversation in memory, after the tool prefix is warmed so the
 * first one does not pay for it. The conversation the KV cache held is saved first and the cache no longer named
 * after it, so the bench's tokens are never saved as its session; its next send prefills again. So is a conversation
 * the user sends to during the run, before the next prompt ([runBench]). Calls with an effect outside the reply are
 * refused, see [benchRuns]. The run stops when the model goes or the queue refuses a turn.
 *
 * `prompt_list web` (or the list picked on the bench screen) reads `assets/bench/agent-web-prompts.json` instead
 * (TEN-183): fact questions whose `web_search`, `web_fetch` and `wikipedia` calls run, unasked, so each result says
 * what the model searched, read and cited ([BenchWeb]). Nothing that reads the phone runs then, see [benchRuns].
 */
object AgentBench {
    private const val TAG = "tensai.bench"
    private const val PROMPTS = "bench/agent-prompts.json"
    private const val WEB_PROMPTS = "bench/agent-web-prompts.json"
    const val EXTRA_BENCH = "bench"
    const val EXTRA_THINKING = "thinking"
    const val EXTRA_BASE_PROMPT = "base_prompt"
    const val EXTRA_EXAMPLES = "examples"
    const val EXTRA_TOOL_GRAMMAR = "tool_grammar"
    const val EXTRA_GREEDY = "greedy"
    const val EXTRA_REASONING_BUDGET = "reasoning_budget"
    const val EXTRA_ADAPTIVE_REASONING = "adaptive_reasoning"
    const val EXTRA_TOOL_POLICY = "tool_policy"
    const val EXTRA_PROMPT_LIST = "prompt_list"

    private val _status = MutableStateFlow<BenchStatus>(BenchStatus.Idle)

    /** The last run, for the debug panel: whatever started it. */
    val status: StateFlow<BenchStatus> = _status.asStateFlow()

    private val _progress = MutableStateFlow<AgentBenchProgress?>(null)

    /** The run going on, for the bench screen's running card, `null` when none is. */
    val progress: StateFlow<AgentBenchProgress?> = _progress.asStateFlow()

    private val _preparing = MutableStateFlow<AgentBenchPreparing?>(null)

    /**
     * A run accepted and not grading yet (the cache handed over, the tool prefix warmed: up to a minute), so the screen
     * shows it from the tap. `null` once [progress] takes over, or when none is.
     */
    val preparing: StateFlow<AgentBenchPreparing?> = _preparing.asStateFlow()

    @Volatile
    private var job: Job? = null

    @Volatile
    private var stopRequested = false

    /** Reads [intent] from `onCreate` / `onNewIntent`; anything but a debug build's `bench=agent` is ignored. */
    fun offer(intent: Intent?, context: Context) {
        fun flag(name: String) = intent?.takeIf { it.hasExtra(name) }?.getBooleanExtra(name, false)
        val trigger = benchTrigger(
            BuildConfig.DEBUG,
            intent?.getStringExtra(EXTRA_BENCH),
            thinking = flag(EXTRA_THINKING),
            defaultThinking = DefaultEnableThinking,
            basePrompt = flag(EXTRA_BASE_PROMPT),
            examples = flag(EXTRA_EXAMPLES),
            toolGrammar = flag(EXTRA_TOOL_GRAMMAR),
            greedy = flag(EXTRA_GREEDY),
            reasoningBudget = intent?.takeIf { it.hasExtra(EXTRA_REASONING_BUDGET) }?.getIntExtra(EXTRA_REASONING_BUDGET, 0),
            adaptiveReasoning = flag(EXTRA_ADAPTIVE_REASONING),
            toolPolicy = intent?.getStringExtra(EXTRA_TOOL_POLICY),
            promptList = intent?.getStringExtra(EXTRA_PROMPT_LIST),
        ) ?: return
        // Consumed once: a recreation must not run it again.
        intent?.removeExtra(EXTRA_BENCH)
        start(trigger, context)
    }

    /**
     * Runs [trigger] on the application scope, so leaving the screen does not stop it. One run at a time: `false` when
     * another bench holds the gate.
     */
    fun start(trigger: BenchTrigger, context: Context): Boolean {
        if (!BenchGate.tryEnter()) {
            // Logged only: the panel keeps showing the run that is going.
            Log.e(TAG, benchErrorLine("a bench is already running"))
            return false
        }
        val app = context.applicationContext
        stopRequested = false
        _status.value = BenchStatus.Running(done = 0, total = null)
        _preparing.value = AgentBenchPreparing(
            model = RuntimeGraph.inferenceQueue.state.value.residentModel?.let { "${it.name} · ${backendName(it.backend)}" },
            startedAtMs = System.currentTimeMillis(),
        )
        job = RuntimeGraph.applicationScope.launch {
            try {
                run(trigger, app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("the run threw ${e::class.simpleName}: ${e.message}")
            } finally {
                // Never left on Running: the panel would wait for good. Stopped before grading anything: nothing to say.
                if (_status.value is BenchStatus.Running) {
                    _status.value = if (stopRequested) BenchStatus.Idle else BenchStatus.Failed("the run ended without a summary")
                }
                _preparing.value = null
                _progress.value = null
                job = null
                BenchGate.leave()
            }
        }
        return true
    }

    /**
     * Stops the run going on, cutting short the prompt it is on: what it graded before is saved as a partial run. Still
     * [preparing], nothing is graded and nothing saved.
     */
    fun stop() {
        stopRequested = true
        job?.cancel()
    }

    /** The asset of [list]. */
    private fun assetOf(list: BenchPromptList): String = when (list) {
        BenchPromptList.Agent -> PROMPTS
        BenchPromptList.Web -> WEB_PROMPTS
    }

    /** The bench list [list]. Throws on a malformed one, see [parseBenchPrompts]. */
    fun prompts(context: Context, list: BenchPromptList = BenchPromptList.Agent): List<BenchPrompt> =
        parseBenchPrompts(context.assets.open(assetOf(list)).bufferedReader().use { it.readText() })

    private suspend fun run(trigger: BenchTrigger, context: Context) {
        val prompts = runCatching { prompts(context, trigger.promptList) }
            .getOrElse { return fail("cannot read ${assetOf(trigger.promptList)}: ${it.message}") }
        val planned = prompts.size * trigger.repetitions
        _status.value = BenchStatus.Running(done = 0, total = planned)
        val queue = RuntimeGraph.inferenceQueue
        val state = queue.state.value
        val info = state.residentModel ?: return fail("no model loaded, load one first")
        if (state !is QueueState.ModelReady) return fail("the model is busy ($state), run the bench once it is idle")
        val entity = RuntimeGraph.modelRepository.models.first().firstOrNull { it.id == info.modelId }
        if (entity?.toolsAvailable != true) return fail("the loaded model's template takes no tools")

        val gate = RefusingGate()
        val web = trigger.promptList.runsWeb
        val registry = ToolRegistry(RuntimeGraph.toolRegistry.registered().map { BenchTool(it, web) }, RuntimeGraph.toolSettings)
        val runtime = RuntimeGraph.agentRuntimeOn(registry, gate, trigger.toolPolicy)
        if (!runtime.hasTools()) return fail("no tool is granted")
        if (web) {
            val provider = RuntimeGraph.toolSettings.searchProvider.first()
            val searchReady = !provider.needsKey || !RuntimeGraph.searchKeys.of(provider)?.get().isNullOrBlank()
            webBenchBlocker(RuntimeGraph.toolSettings.grants.first(), searchReady)?.let { return fail(it) }
        }
        val systemPrompt = trigger.systemPrompt()
        val system = { tools: Set<String> -> systemPrompt.build(tools = tools, startedAt = null, global = null, chat = null) }
        val sampling = trigger.sampling(replySampling(null, trigger.thinking, entity.thinkingAvailable))
        // The budget the decodes get, as logged and saved: none when the chat's sampling has no reasoning cap to lower.
        val ran = trigger.copy(
            reasoningBudget = trigger.reasoningBudget.takeIf { sampling.reasoningBudget != null },
            adaptiveReasoning = trigger.adaptiveReasoning && sampling.reasoningBudget != null,
        )
        // Adaptive: each prompt as a chat would send it, its budget read off the prompt by the chat's own rule.
        val samplingFor = if (ran.adaptiveReasoning) {
            { text: String -> trigger.sampling(replySampling(null, trigger.thinking, entity.thinkingAvailable, lastUserMessage = text)) }
        } else {
            null
        }
        val runner = BenchRunner(
            runtime = runtime,
            gate = gate,
            generator = ReplyGenerator { queue.generate(it) },
            sampling = sampling,
            countTokens = { text -> queue.countTokens(listOf(text))?.firstOrNull() },
            system = system,
            samplingFor = samplingFor,
            web = web,
        )

        // The cache's owner is saved, then forgotten: from here the cache holds the bench, whatever is saved next.
        // Again before each prompt: a chat send in between has made its conversation the owner.
        // Never cut short by Stop: a session save that is cancelled loses its temp file and the conversation re-prefills.
        val handOver = suspend {
            withContext(NonCancellable) {
                val owner = ActiveSessionTracker.current.value
                handOverCache(
                    owner?.let { it.conversationId to it.key },
                    save = { id, key ->
                        RuntimeGraph.sessionRepository.save(
                            id,
                            key,
                            ownsCache = { ActiveSessionTracker.current.value == owner },
                            saveOnEngine = queue::saveSession,
                        )
                    },
                    forget = ActiveSessionTracker::clearIf,
                )
            }
        }
        if (!handOver()) return fail("could not save the open conversation's session, bench not run")
        var warmed = queue.warmToolPrefix(runner.prefixRequest(), expectedModel = info, replaceSaved = queue.currentKvTokenCount())
        if (warmed != WarmUpOutcome.Warmed) {
            // Most likely the chat's own warm-up holding the engine: stopped (and waited for), then once more.
            queue.cancelWarmUp()
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
                put("tool_policy", trigger.toolPolicy.storageName)
                if (web) put("prompt_list", trigger.promptList.storageName)
                if (trigger.greedy) put("greedy", true)
                put("reasoning_budget", ran.reasoningBudget)
                put("reasoning_budget_mode", ran.reasoningBudgetMode)
                put("reasoning_max_tokens", sampling.reasoningBudget?.reasoningMaxTokens)
                if (trigger.repetitions > 1) put("repetitions", trigger.repetitions)
                put("thinking_supported", entity.thinkingAvailable)
                put("tools", JsonArray(runner.availableTools().map { JsonPrimitive(it) }))
                put("prompts", prompts.size)
                put("prefix", warmed.name)
            }.toString(),
        )
        val config = BenchRunConfig(
            model = entity.shownName,
            quant = entity.quantLabel.takeIf { it.isNotBlank() },
            backend = backendName(info.backend),
            kvType = info.kvCacheType.name.lowercase(),
            thinking = trigger.thinking,
            thinkingSupported = entity.thinkingAvailable,
            basePrompt = trigger.basePrompt,
            basePromptVersion = BasePrompt.VERSION,
            examples = trigger.examples,
            toolGrammar = trigger.toolGrammar,
            toolPolicy = trigger.toolPolicy.storageName,
            promptList = trigger.promptList.storageName,
            repetitions = trigger.repetitions,
            reasoningBudget = ran.reasoningBudget,
            reasoningBudgetMode = ran.reasoningBudgetMode,
            sampling = BenchSampling.of(sampling, greedy = trigger.greedy),
            prompts = prompts.size,
            tools = runner.availableTools().toList(),
            prefixWarmed = warmed == WarmUpOutcome.Warmed,
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            llamaKtCommit = BuildConfig.LLAMA_KT_COMMIT.takeIf { it.isNotBlank() },
        )
        val startedAt = System.currentTimeMillis()
        // Kept here rather than returned: a stopped run never returns, and what it graded is still saved.
        val results = mutableListOf<BenchResult>()
        var stopReason: String? = null
        _progress.value = AgentBenchProgress(config, startedAt, planned, shownFromMs = _preparing.value?.startedAtMs ?: startedAt)
        _preparing.value = null
        runThenSave(
            results,
            stopRequested = { stopRequested },
            run = {
                runBench(
                    prompts,
                    runner,
                    firstCold = warmed != WarmUpOutcome.Warmed,
                    beforeEach = {
                        when {
                            queue.state.value.residentModel != info -> "the model was unloaded or replaced"
                            !handOver() -> "could not save the session of a conversation sent to meanwhile"
                            else -> null
                        }?.also { stopReason = it }
                    },
                    log = { Log.i(TAG, it) },
                    onResult = { result ->
                        results += result
                        if (result.stopsRun) stopReason = "the queue refused '${result.prompt.id}' (${result.error})"
                        _status.value = BenchStatus.Running(done = results.size, total = planned)
                        _progress.value = _progress.value?.plus(result)
                    },
                    repetitions = trigger.repetitions,
                    onStart = { prompt, rep -> _progress.value = _progress.value?.copy(currentId = prompt.id, currentRep = rep) },
                )
                stopReason
            },
            finish = { graded, why ->
                when {
                    why == BenchRun.StoppedByUser -> Log.e(TAG, benchErrorLine("stopped from the bench screen, ${graded.size} of $planned graded"))
                    why?.startsWith(BenchRun.ThrewPrefix) == true -> Log.e(TAG, benchErrorLine("the run $why, ${graded.size} of $planned graded"))
                }
                val summary = BenchSummary.of(graded)
                Log.i(TAG, summary.toLogLine(ran))
                save(context, config, startedAt, planned, graded, why)
                _status.value = BenchStatus.Finished(summary.toDisplayLines(ran, info.name, graded), stoppedEarly = graded.size < planned)
            },
        )
    }

    /** Saves the run in [BenchRunStore] if it graded anything. A failed write is only logged: the log lines have it all. */
    private fun save(context: Context, config: BenchRunConfig, startedAt: Long, planned: Int, results: List<BenchResult>, stopReason: String?) {
        if (results.isEmpty()) return
        val run = BenchRun.of(startedAt, System.currentTimeMillis(), config, planned, results, stopReason)
        runCatching { BenchRunStore.of(context).save(run) }
            .onSuccess { Log.i(TAG, "saved ${run.id}, ${results.size} of $planned graded") }
            .onFailure { Log.e(TAG, benchErrorLine("could not save ${run.id}: ${it.message}")) }
    }

    /** Logs why the bench does not run, as an `error` line, and shows it. */
    private fun fail(message: String) {
        Log.e(TAG, benchErrorLine(message))
        _status.value = BenchStatus.Failed(message)
    }
}

/** A run accepted and getting ready, for the running card: the [model] line (`null` with none loaded) and when the tap was. */
data class AgentBenchPreparing(val model: String?, val startedAtMs: Long)

/**
 * The agent bench run going on, for the bench screen: what it runs with, when it started, how far it is.
 *
 * @param planned trials to grade, prompts times repetitions.
 * @param currentId the prompt being run and [currentRep] its repetition, `null` before the first.
 * @param counts the verdicts graded so far.
 * @param shownFromMs when the screen counts the elapsed time from: the tap, before the preparation, where [startedAtMs]
 *   (the saved run's) is when grading began.
 */
data class AgentBenchProgress(
    val config: BenchRunConfig,
    val startedAtMs: Long,
    val planned: Int,
    val shownFromMs: Long = startedAtMs,
    val done: Int = 0,
    val currentId: String? = null,
    val currentRep: Int = 0,
    val counts: Map<BenchVerdict, Int> = emptyMap(),
) {
    operator fun plus(result: BenchResult): AgentBenchProgress =
        copy(done = done + 1, counts = counts + (result.verdict to (counts[result.verdict] ?: 0) + 1))
}
