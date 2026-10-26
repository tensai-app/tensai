package io.github.tensaiapp.bench

import android.content.Context
import android.os.Build
import android.util.Log
import com.tensai.llamakt.ThreadPolicy
import io.github.tensaiapp.BuildConfig
import io.github.tensaiapp.engine.ChatMessage
import io.github.tensaiapp.engine.CpuLoadKnobs
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
import io.github.tensaiapp.ui.screens.loading.LoadFailure
import io.github.tensaiapp.ui.screens.loading.LoadModelViewModel
import io.github.tensaiapp.ui.screens.loading.LoadUiState
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug builds only (this source set): the decode curve of Settings > Bench > Decode (TEN-190, the runner of TEN-188).
 * On the model already loaded, decodes [tokens][start] tokens after each picked length of [DecodeCurve.Lengths] tokens of
 * filler (no tools, no system message, thinking off, greedy), and saves the run as a [DecodeRun] in [DecodeRunStore],
 * a cancelled one as a partial run. Speeds are llama.cpp's own timings. Logs a `decode_start` line, one `decode` line per
 * point and a `decode_summary` on [TAG].
 *
 * Each point's prompt opens with its own nonce: llama.cpp keeps the longest common prefix of the cache and the new
 * prompt and drops the rest, so a point only shares the template's first tokens with the one before and prefills
 * from an almost empty cache. One short decode warms up first, unlogged. Before it, the run waits for a prefix
 * warm-up still going ([io.github.tensaiapp.runtime.InferenceQueue.isWarmingUp]) rather than cancel it. As in
 * [AgentBench], the open conversation's session is saved and the cache no longer named after it before the run and
 * before each point.
 *
 * [reload] loads the resident model again so the [CpuLoadOverride] knobs apply: unload, then the Models screen's load.
 */
object DecodeBench {
    private const val TAG = "tensai.bench"
    private const val WarmPollMs = 500L

    private val _progress = MutableStateFlow<DecodeProgress?>(null)

    /** The run going on, for the Decode tab, `null` when none is. */
    val progress: StateFlow<DecodeProgress?> = _progress.asStateFlow()

    private val _lastSaved = MutableStateFlow<String?>(null)

    /** The id of the last run saved, so the tab opens its result once it ends. */
    val lastSaved: StateFlow<String?> = _lastSaved.asStateFlow()

    private val _failure = MutableStateFlow<String?>(null)

    /** Why the last run did not run, in English from the log, `null` once one does. */
    val failure: StateFlow<String?> = _failure.asStateFlow()

    private val _reload = MutableStateFlow<ReloadState?>(null)

    /** The reload going on or how the last one failed, `null` otherwise. */
    val reload: StateFlow<ReloadState?> = _reload.asStateFlow()

    @Volatile
    private var job: Job? = null

    @Volatile
    private var stopRequested = false

    private val sampling = SamplingConfig(
        temperature = 0f,
        repeatPenalty = 1f,
        maxTokens = DecodeCurve.EvalTokens,
        enableThinking = false,
        constrainToolCalls = false,
    )

    /**
     * Runs the curve over [points] with [tokens] decoded at each, on the application scope, so leaving the screen does
     * not stop it. One bench at a time: `false` when another holds the gate.
     */
    fun start(context: Context, points: List<Int>, tokens: Int): Boolean {
        if (!BenchGate.tryEnter()) {
            Log.e(TAG, benchErrorLine("a bench is already running"))
            return false
        }
        val app = context.applicationContext
        stopRequested = false
        _failure.value = null
        job = RuntimeGraph.applicationScope.launch {
            try {
                run(app, points.sorted(), tokens)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("the run threw ${e::class.simpleName}: ${e.message}")
            } finally {
                _progress.value = null
                job = null
                BenchGate.leave()
            }
        }
        return true
    }

    /** Cancels the run going on, cutting short the point it is on: the points before it are saved as a partial run. */
    fun cancel() {
        stopRequested = true
        job?.cancel()
    }

    /**
     * Unloads the resident model and loads it again the way the Models screen does ([LoadModelViewModel]), so the
     * knobs set since apply. Holds the bench gate meanwhile. `false` when nothing is loaded, the model is busy (a
     * reply streaming: the unload would cancel it) or a bench is running.
     *
     * The load keeps what the first one settled: past a "won't fit" block (it was loaded, so it was confirmed), and on
     * the CPU when it ran there ([CpuLoadOverride.applied] set), whatever the backend preference says, as a "Retry on
     * CPU" does. The context is the load policy's again: [LoadModelViewModel] takes no context, and steps down on an
     * out of memory the same way as the first time.
     */
    fun reload(): Boolean {
        val queue = RuntimeGraph.inferenceQueue
        val state = queue.state.value
        if (state !is QueueState.ModelReady) return false
        val modelId = state.info.modelId ?: return false
        val cpu = CpuLoadOverride.applied != null
        if (!BenchGate.tryEnter()) return false
        _reload.value = ReloadState.Loading(progress = null)
        RuntimeGraph.applicationScope.launch {
            try {
                queue.unloadModel()
                val load = LoadModelViewModel(modelId)
                if (cpu) load.retryOnCpu() else load.start(confirmedOverride = true)
                val end = load.state
                    .onEach { if (it is LoadUiState.InProgress) _reload.value = ReloadState.Loading(it.progress) }
                    .first { it !is LoadUiState.Preparing && it !is LoadUiState.InProgress }
                Log.i(TAG, "decode reload of $modelId (cpu=$cpu): ${end::class.simpleName}")
                _reload.value = reloadFailure(end)?.let(ReloadState::Failed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, benchErrorLine("decode reload threw ${e::class.simpleName}"))
                _reload.value = ReloadState.Failed(ReloadFailure.Unknown)
            } finally {
                BenchGate.leave()
            }
        }
        return true
    }

    /** Why a load ended that way, `null` for a success. */
    private fun reloadFailure(end: LoadUiState): ReloadFailure? = when (end) {
        LoadUiState.Success -> null
        is LoadUiState.OomFailed -> ReloadFailure.OutOfMemory
        is LoadUiState.Blocked -> ReloadFailure.WontFit
        is LoadUiState.FileMissing -> ReloadFailure.FileMissing
        LoadUiState.ModelNotFound -> ReloadFailure.ModelNotFound
        is LoadUiState.GenericFailed -> when (end.failure) {
            LoadFailure.UnreadableModel -> ReloadFailure.Unreadable
            LoadFailure.UnsupportedArchitecture, LoadFailure.IncompleteModel -> ReloadFailure.Unsupported
            LoadFailure.EngineBusy -> ReloadFailure.EngineBusy
            LoadFailure.Unknown -> ReloadFailure.Unknown
        }
        LoadUiState.Preparing, is LoadUiState.InProgress -> ReloadFailure.Unknown
    }

    /**
     * What the model in memory was loaded with: the decode threads (the knobs' or the big cores, `null` when llama.cpp
     * picked), flash attention, and the cache type it reports.
     */
    fun loadedKnobs(info: LoadedModelInfo): DecodeKnobs {
        val applied = CpuLoadOverride.applied
        return DecodeKnobs(
            nThreads = applied?.decodeThreads ?: ThreadPolicy.resolve(ThreadPolicy.readCoreSpeeds()).decode.takeIf { it > 0 },
            // A CPU load ([applied] set) with a quantized cache has flash attention on (VulkanTuning.kvForLoad).
            flashAttn = if (applied != null && info.kvCacheType != KvCacheType.F16) "on" else (applied?.effectiveFlashAttn ?: FlashAttnMode.Auto).name.lowercase(),
            kv = info.kvCacheType.name.lowercase(),
        )
    }

    /** The knobs the model in memory took, the defaults when it took none. */
    fun appliedKnobs(): CpuLoadKnobs = CpuLoadOverride.applied ?: CpuLoadKnobs()

    private suspend fun run(context: Context, picked: List<Int>, tokens: Int) {
        val queue = RuntimeGraph.inferenceQueue
        val state = queue.state.value
        val info = state.residentModel ?: return fail("no model loaded, load one first")
        if (state !is QueueState.ModelReady) return fail("the model is busy ($state), run the curve once it is idle")
        val entity = RuntimeGraph.modelRepository.models.first().firstOrNull { it.id == info.modelId }
        val backend = backendName(info.backend)
        val lengths = DecodeCurve.lengthsFor(info.nCtxEffective, picked, tokens)
        if (lengths.isEmpty()) return fail("no picked point fits n_ctx ${info.nCtxEffective}")
        val config = DecodeRunConfig(
            model = entity?.shownName ?: info.name,
            quant = entity?.quantLabel?.takeIf { it.isNotBlank() },
            backend = backend,
            nCtx = info.nCtxEffective,
            requested = CpuLoadOverride.applied?.let(DecodeKnobs::of),
            loaded = loadedKnobs(info),
            tokensPerPoint = tokens,
            points = lengths,
            skipped = picked - lengths.toSet(),
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            llamaKtCommit = BuildConfig.LLAMA_KT_COMMIT.takeIf { it.isNotBlank() },
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            soc = runCatching { RuntimeGraph.deviceProfiler.profile().socModel }.getOrNull(),
        )
        _progress.value = DecodeProgress(config, startedAtMs = System.currentTimeMillis(), phase = DecodePhase.WarmUp)

        // A prefix warm-up after a load is worth more than the seconds it takes: let it write its file. Idle at two
        // reads in a row, so one about to start on the load that just ended is not missed.
        var idleReads = 0
        while (idleReads < 2) {
            if (queue.isWarmingUp()) {
                idleReads = 0
                _progress.value = _progress.value?.copy(phase = DecodePhase.Waiting)
            } else {
                idleReads++
            }
            if (idleReads < 2) delay(WarmPollMs)
        }

        val handOver = suspend {
            withContext(NonCancellable) {
                handOverCache(
                    ActiveSessionTracker.current.value,
                    save = { id, key -> RuntimeGraph.sessionRepository.save(id, key, saveOnEngine = queue::saveSession) },
                    forget = ActiveSessionTracker::clearIf,
                )
            }
        }
        if (!handOver()) return fail("could not save the open conversation's session, curve not run")
        val paragraphTokens = queue.countTokens(listOf(DecodeCurve.Paragraph))?.firstOrNull()?.takeIf { it > 0 }
            ?: return fail("cannot count tokens on the loaded model")
        if (queue.state.value.residentModel != info) return fail("the model was unloaded or replaced")

        Log.i(TAG, DecodeRunJson.startLine(config))
        val startedAt = System.currentTimeMillis()
        _progress.value = DecodeProgress(config, startedAt, phase = DecodePhase.WarmUp, phaseStartedAtMs = startedAt)
        // A warm-up the engine refuses says the points would fail too: no run of error points is saved.
        decode(DecodeCurve.prompt(nonce(), 0), DecodeEstimate.WarmUpTokens).second?.let { error ->
            return fail("the warm-up decode failed (${error.message ?: error::class.simpleName}), curve not run")
        }

        // Kept here rather than returned: a cancelled run never returns, and what it measured is still saved.
        val points = mutableListOf<DecodeRunPoint>()
        runThenSave(
            points,
            stopRequested = { stopRequested },
            run = run@{
                for (ctx in lengths) {
                    val why = when {
                        queue.state.value.residentModel != info -> "the model was unloaded or replaced"
                        !handOver() -> "could not save the session of a conversation sent to meanwhile"
                        else -> null
                    }
                    if (why != null) {
                        Log.e(TAG, benchErrorLine("$why, curve stopped before ctx $ctx"))
                        return@run why
                    }
                    val pointStart = System.currentTimeMillis()
                    _progress.value = _progress.value?.copy(current = ctx, phase = DecodePhase.Prefill, phaseStartedAtMs = pointStart, decoded = 0, ttftMs = null)
                    val (stats, error) = decode(DecodeCurve.prompt(nonce(), DecodeCurve.paragraphsFor(ctx, paragraphTokens)), tokens)
                    val point = DecodeRunPoint.of(
                        ctx,
                        stats,
                        error?.let { it.message ?: it::class.simpleName },
                        kvTokens = queue.currentKvTokenCount(),
                        ms = System.currentTimeMillis() - pointStart,
                    )
                    points += point
                    Log.i(TAG, DecodeRunJson.pointLine(point, config))
                    _progress.value = _progress.value?.let { it.copy(done = it.done + point) }
                    if (error is QueueBusyException) {
                        Log.e(TAG, benchErrorLine("the queue refused ctx $ctx (${point.error}), curve stopped"))
                        return@run "the queue refused ctx $ctx (${point.error})"
                    }
                }
                null
            },
            finish = { measured, why ->
                if (why == BenchRun.StoppedByUser) Log.e(TAG, benchErrorLine("cancelled from the bench screen, ${measured.size} of ${lengths.size} points"))
                if (measured.isEmpty()) return@runThenSave
                val run = DecodeRun.of(startedAt, System.currentTimeMillis(), config, measured, why)
                Log.i(TAG, DecodeRunJson.summaryLine(run))
                runCatching { DecodeRunStore.of(context).save(run) }
                    .onSuccess {
                        Log.i(TAG, "saved ${run.id}, ${measured.size} of ${lengths.size} points")
                        _lastSaved.value = run.id
                    }
                    .onFailure { Log.e(TAG, benchErrorLine("could not save ${run.id}: ${it.message}")) }
            },
        )
    }

    /** One decode of [text] as the only user message, capped at [maxTokens]: its stats, or why it failed. Moves [progress] along. */
    private suspend fun decode(text: String, maxTokens: Int): Pair<DecodeStats?, Throwable?> {
        var stats: DecodeStats? = null
        var error: Throwable? = null
        val start = System.currentTimeMillis()
        val request = DecodeRequest(listOf(ChatMessage(MessageRole.User, text)), sampling.copy(maxTokens = maxTokens))
        RuntimeGraph.inferenceQueue.generate(request).collect { event ->
            when (event) {
                // A piece of text is a token or a few: close enough for a live count, the saved one is llama.cpp's.
                is DecodeEvent.Token -> _progress.value = _progress.value?.let { p ->
                    if (p.phase == DecodePhase.Prefill) {
                        val now = System.currentTimeMillis()
                        p.copy(phase = DecodePhase.Decode, ttftMs = now - start, phaseStartedAtMs = now, decoded = 1)
                    } else {
                        p.copy(decoded = p.decoded + 1)
                    }
                }
                is DecodeEvent.Done -> stats = event.stats
                is DecodeEvent.Error -> error = event.cause
                else -> Unit
            }
        }
        return stats to error
    }

    private fun nonce() = UUID.randomUUID().toString().take(8)

    private fun fail(message: String) {
        Log.e(TAG, benchErrorLine(message))
        _failure.value = message
    }
}

/** Where a running curve is. */
enum class DecodePhase {
    /** A prefix warm-up after a load is still going: the curve starts once it is done. */
    Waiting,

    /** The short unlogged decode before the first point. */
    WarmUp,
    Prefill,
    Decode,
}

/**
 * The decode curve going on, for the Decode tab.
 *
 * @param done the points measured so far, in run order.
 * @param current the point being measured, `null` before the first.
 * @param phaseStartedAtMs when [phase] began, for the live rate of a decode.
 * @param decoded pieces of text streamed in this point's decode, about one a token.
 * @param ttftMs this point's time to its first token, once it has one.
 */
data class DecodeProgress(
    val config: DecodeRunConfig,
    val startedAtMs: Long,
    val phase: DecodePhase,
    val done: List<DecodeRunPoint> = emptyList(),
    val current: Int? = null,
    val phaseStartedAtMs: Long = startedAtMs,
    val decoded: Int = 0,
    val ttftMs: Long? = null,
)

/** A [DecodeBench.reload] going on ([progress] `null` until the load reports one), or why it failed. */
sealed interface ReloadState {
    data class Loading(val progress: Float?) : ReloadState

    data class Failed(val reason: ReloadFailure) : ReloadState
}

/** Why a reload failed, from the load screen's outcomes ([LoadUiState], [LoadFailure]), for a readable line. */
enum class ReloadFailure { OutOfMemory, WontFit, FileMissing, ModelNotFound, Unreadable, Unsupported, EngineBusy, Unknown }
