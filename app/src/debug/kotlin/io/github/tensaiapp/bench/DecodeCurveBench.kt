package io.github.tensaiapp.bench

import android.util.Log
import com.tensai.llamakt.ThreadPolicy
import io.github.tensaiapp.engine.ChatMessage
import io.github.tensaiapp.engine.CpuLoadOverride
import io.github.tensaiapp.engine.DecodeEvent
import io.github.tensaiapp.engine.DecodeRequest
import io.github.tensaiapp.engine.DecodeStats
import io.github.tensaiapp.engine.FlashAttnMode
import io.github.tensaiapp.engine.KvCacheType
import io.github.tensaiapp.engine.LoadedModelInfo
import io.github.tensaiapp.engine.MessageRole
import io.github.tensaiapp.engine.SamplingConfig
import io.github.tensaiapp.runtime.ActiveSessionTracker
import io.github.tensaiapp.runtime.QueueBusyException
import io.github.tensaiapp.runtime.QueueState
import io.github.tensaiapp.runtime.RuntimeGraph
import io.github.tensaiapp.runtime.residentModel
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Debug builds only (this source set): "Run decode curve" in Settings > This device (TEN-188). On the model already
 * loaded, decodes [DecodeCurve.EvalTokens] tokens after each of [DecodeCurve.Lengths] tokens of filler (no tools, no
 * system message, thinking off, greedy), and logs one `decode` JSON line per point and a `decode_summary` on [TAG], so
 * decode speed against context length is measured, with the [CpuLoadOverride] knobs the model was loaded with.
 *
 * Each point's prompt opens with its own nonce: llama.cpp keeps the longest common prefix of the cache and the new
 * prompt and drops the rest, so a point only shares the template's first tokens with the one before and prefills
 * from an almost empty cache. One short decode warms up first, unlogged. As in [AgentBench], the open conversation's
 * session is saved and the cache no longer named after it before the run and before each point.
 */
object DecodeCurveBench {
    private const val TAG = "tensai.bench"
    private const val WarmUpTokens = 16

    private val _status = MutableStateFlow<BenchStatus>(BenchStatus.Idle)

    /** The last run, for the debug panel. [BenchStatus.Running] counts points. */
    val status: StateFlow<BenchStatus> = _status.asStateFlow()

    /** Runs the curve on the application scope, so leaving the screen does not stop it. One bench at a time. */
    fun start() {
        if (!BenchGate.tryEnter()) {
            Log.e(TAG, benchErrorLine("a bench is already running"))
            return
        }
        _status.value = BenchStatus.Running(done = 0, total = null)
        RuntimeGraph.applicationScope.launch {
            try {
                run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("the run threw ${e::class.simpleName}: ${e.message}")
            } finally {
                if (_status.value is BenchStatus.Running) _status.value = BenchStatus.Failed("the run ended without a summary")
                BenchGate.leave()
            }
        }
    }

    private val sampling = SamplingConfig(
        temperature = 0f,
        repeatPenalty = 1f,
        maxTokens = DecodeCurve.EvalTokens,
        enableThinking = false,
        constrainToolCalls = false,
    )

    private suspend fun run() {
        val queue = RuntimeGraph.inferenceQueue
        val state = queue.state.value
        val info = state.residentModel ?: return fail("no model loaded, load one first")
        if (state !is QueueState.ModelReady) return fail("the model is busy ($state), run the curve once it is idle")
        val paragraphTokens = queue.countTokens(listOf(DecodeCurve.Paragraph))?.firstOrNull()?.takeIf { it > 0 }
            ?: return fail("cannot count tokens on the loaded model")
        val lengths = DecodeCurve.lengthsFor(info.nCtxEffective)
        val skipped = DecodeCurve.Lengths - lengths.toSet()
        val setup = setupOf(info)
        val handOver = suspend {
            handOverCache(
                ActiveSessionTracker.current.value,
                save = { id, key -> RuntimeGraph.sessionRepository.save(id, key, saveOnEngine = queue::saveSession) },
                forget = ActiveSessionTracker::clearIf,
            )
        }
        if (!handOver()) return fail("could not save the open conversation's session, curve not run")
        _status.value = BenchStatus.Running(done = 0, total = lengths.size)
        Log.i(
            TAG,
            buildJsonObject {
                put("event", "decode_start")
                put("model", setup.model)
                put("backend", setup.backend)
                put("n_ctx", setup.nCtx)
                put("paragraph_tokens", paragraphTokens)
                put("points", lengths.size)
            }.toString(),
        )
        decode(DecodeCurve.prompt(nonce(), 0), maxTokens = WarmUpTokens)

        val points = mutableListOf<DecodePoint>()
        for (ctx in lengths) {
            val why = when {
                queue.state.value.residentModel != info -> "the model was unloaded or replaced"
                !handOver() -> "could not save the session of a conversation sent to meanwhile"
                else -> null
            }
            if (why != null) {
                Log.e(TAG, benchErrorLine("$why, curve stopped before ctx $ctx"))
                break
            }
            val (stats, error) = decode(DecodeCurve.prompt(nonce(), DecodeCurve.paragraphsFor(ctx, paragraphTokens)))
            val eval = stats?.tokensGenerated ?: 0
            val promptTokens = queue.currentKvTokenCount()?.let { (it - eval).coerceAtLeast(0) }
            val point = DecodePoint(
                ctx = ctx,
                promptTokens = promptTokens,
                evalTokens = eval,
                decodeTps = stats?.tokPerSec?.takeIf { it > 0f }?.let { DecodePoint.round2(it.toDouble()) },
                prefillTps = if (stats == null || promptTokens == null) null else DecodePoint.rate(promptTokens, stats.ttftMs),
                ttftMs = stats?.ttftMs,
                stop = stats?.stopReason?.name,
                error = error?.let { it.message ?: it::class.simpleName },
            )
            points += point
            Log.i(TAG, point.toLogLine(setup))
            _status.value = BenchStatus.Running(done = points.size, total = lengths.size)
            if (error is QueueBusyException) {
                Log.e(TAG, benchErrorLine("the queue refused ctx $ctx (${point.error}), curve stopped"))
                break
            }
        }
        Log.i(TAG, decodeSummaryLine(setup, points, skipped))
        _status.value = BenchStatus.Finished(decodeDisplayLines(setup, points, skipped), stoppedEarly = points.size < lengths.size)
    }

    /** One decode of [text] as the only user message: its stats, or why it failed. */
    private suspend fun decode(text: String, maxTokens: Int = DecodeCurve.EvalTokens): Pair<DecodeStats?, Throwable?> {
        var stats: DecodeStats? = null
        var error: Throwable? = null
        val request = DecodeRequest(listOf(ChatMessage(MessageRole.User, text)), sampling.copy(maxTokens = maxTokens))
        RuntimeGraph.inferenceQueue.generate(request).collect { event ->
            when (event) {
                is DecodeEvent.Done -> stats = event.stats
                is DecodeEvent.Error -> error = event.cause
                else -> Unit
            }
        }
        return stats to error
    }

    /** What the model was loaded with: the knobs ([CpuLoadOverride.applied]) and the cache type it reports. */
    private fun setupOf(info: LoadedModelInfo): CurveSetup {
        val applied = CpuLoadOverride.applied
        return CurveSetup(
            model = info.name,
            backend = info.backend,
            nCtx = info.nCtxEffective,
            nThreads = applied?.decodeThreads ?: ThreadPolicy.resolve(ThreadPolicy.readCoreSpeeds()).decode.takeIf { it > 0 },
            // A CPU load ([applied] set) with a quantized cache has flash attention on (VulkanTuning.kvForLoad).
            flashAttn = if (applied != null && info.kvCacheType != KvCacheType.F16) "on" else (applied?.effectiveFlashAttn ?: FlashAttnMode.Auto).name.lowercase(),
            kv = info.kvCacheType.name.lowercase(),
        )
    }

    private fun nonce() = UUID.randomUUID().toString().take(8)

    private fun fail(message: String) {
        Log.e(TAG, benchErrorLine(message))
        _status.value = BenchStatus.Failed(message)
    }
}
