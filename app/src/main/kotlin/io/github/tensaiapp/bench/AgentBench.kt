package io.github.tensaiapp.bench

import android.content.Intent
import android.content.res.AssetManager
import android.util.Log
import io.github.tensaiapp.BuildConfig
import io.github.tensaiapp.agent.ReplyGenerator
import io.github.tensaiapp.agent.ToolRegistry
import io.github.tensaiapp.runtime.ActiveSessionTracker
import io.github.tensaiapp.runtime.QueueState
import io.github.tensaiapp.runtime.RuntimeGraph
import io.github.tensaiapp.runtime.WarmUpOutcome
import io.github.tensaiapp.runtime.residentModel
import io.github.tensaiapp.ui.screens.chat.DefaultEnableThinking
import io.github.tensaiapp.ui.screens.chat.replySampling
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * `adb shell am start -n io.github.tensaiapp/.MainActivity --activity-single-top --es bench agent [--ez thinking false]`
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
    private const val WarmRetryMs = 500L

    private var running: Job? = null

    /** Reads [intent] from `onCreate` / `onNewIntent`; anything but a debug build's `bench=agent` is ignored. */
    fun offer(intent: Intent?, assets: AssetManager) {
        val thinking = intent?.takeIf { it.hasExtra(EXTRA_THINKING) }?.getBooleanExtra(EXTRA_THINKING, DefaultEnableThinking)
        val trigger = benchTrigger(BuildConfig.DEBUG, intent?.getStringExtra(EXTRA_BENCH), thinking, DefaultEnableThinking) ?: return
        // Consumed once: a recreation must not run it again.
        intent?.removeExtra(EXTRA_BENCH)
        if (running?.isActive == true) return fail("a bench is already running")
        running = RuntimeGraph.applicationScope.launch { run(trigger, assets) }
    }

    private suspend fun run(trigger: BenchTrigger, assets: AssetManager) {
        val prompts = runCatching { parseBenchPrompts(assets.open(PROMPTS).bufferedReader().use { it.readText() }) }
            .getOrElse { return fail("cannot read $PROMPTS: ${it.message}") }
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
        val runner = BenchRunner(
            runtime = runtime,
            gate = gate,
            generator = ReplyGenerator { queue.generate(it) },
            sampling = replySampling(null, trigger.thinking, entity.thinkingAvailable),
            countTokens = { text -> queue.countTokens(listOf(text))?.firstOrNull() },
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
                put("thinking_supported", entity.thinkingAvailable)
                put("tools", JsonArray(runner.availableTools().map { JsonPrimitive(it) }))
                put("prompts", prompts.size)
                put("prefix", warmed.name)
            }.toString(),
        )
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
        )
        Log.i(TAG, BenchSummary.of(results).toLogLine(trigger.thinking))
    }

    /** Logs why the bench does not run, as an `error` line. */
    private fun fail(message: String) {
        Log.e(TAG, benchErrorLine(message))
    }
}
