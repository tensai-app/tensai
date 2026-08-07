package io.github.hokanosekai.tensai.runtime

import io.github.hokanosekai.tensai.engine.DecodeEvent
import io.github.hokanosekai.tensai.engine.DecodeRequest
import io.github.hokanosekai.tensai.engine.InferenceEngine
import io.github.hokanosekai.tensai.engine.LlamaKtEngine
import io.github.hokanosekai.tensai.engine.LoadedModel
import io.github.hokanosekai.tensai.engine.LoadedModelInfo
import io.github.hokanosekai.tensai.engine.ModelLoadConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * State of an [InferenceQueue], as observed through [InferenceQueue.state].
 *
 * Valid transitions:
 * - [Idle] → [Loading] : [InferenceQueue.loadModel] called.
 * - [Loading] → [ModelReady] : load succeeded.
 * - [Loading] → [Idle] : load failed (any previously loaded model was
 *   already released as part of the implicit unload — see
 *   [InferenceQueue.loadModel]).
 * - [ModelReady] → [Generating] : [InferenceQueue.generate] accepted.
 * - [Generating] → [ModelReady] : generation finished, failed, or was
 *   cancelled — a model is always still loaded after [Generating].
 * - [ModelReady] → [Idle] : [InferenceQueue.unloadModel] called.
 * - [ModelReady] → [Loading] : [InferenceQueue.loadModel] called again
 *   (implicit unload of the previous model).
 */
sealed class QueueState {
    /** No model loaded, nothing running. */
    data object Idle : QueueState()

    /** A model load is in progress. [progress] is in 0.0–1.0. */
    data class Loading(val progress: Float) : QueueState()

    /** A model is loaded and idle, ready to [InferenceQueue.generate]. */
    data class ModelReady(val info: LoadedModelInfo) : QueueState()

    /** A generation is in progress against the currently loaded model [info]. */
    data class Generating(val info: LoadedModelInfo) : QueueState()
}

/**
 * The model currently resident in memory, or `null` if none is. A model stays loaded across a
 * generation, so both [QueueState.ModelReady] and [QueueState.Generating] carry one — matching only
 * `ModelReady` reads as "no model" for the whole duration of a reply, which is the bug behind both
 * TEN-46 and TEN-58. Anything showing which model is loaded should go through this rather than
 * write the `when` out again.
 */
val QueueState.residentModel: LoadedModelInfo?
    get() = when (this) {
        is QueueState.ModelReady -> info
        is QueueState.Generating -> info
        QueueState.Idle, is QueueState.Loading -> null
    }

/**
 * Thrown (as [DecodeEvent.Error]) when [InferenceQueue.generate] is called
 * while the queue cannot start a new generation — either another
 * generation is already active, or no model is currently loaded.
 */
class QueueBusyException(message: String) : Exception(message)

/**
 * The load-relevant part of a [ModelLoadConfig] — everything except
 * [ModelLoadConfig.onProgress], which is per-call and never identifies a model.
 * Two configs with equal dedup keys produce an identical loaded model, which is what lets
 * [InferenceQueue.loadModel] short-circuit a repeat call (see TEN-50).
 */
private fun ModelLoadConfig.dedupKey(): ModelLoadConfig = copy(onProgress = null)

/**
 * Single, app-wide entry point for every inference in Tensai.
 *
 * Contracts (see spec V1 "Contrats transverses"):
 * - Exactly one model is loaded at a time, and exactly one generation runs
 *   at a time, for the whole app.
 * - Multiple independent callers may share the same instance — the Chat UI
 *   is the only client in V1, but the V2 API server will become a second
 *   client of this same queue. Nothing here assumes a particular caller or
 *   its dispatcher.
 * - Every generation is cancellable, either by cancelling collection of the
 *   [Flow] returned by [generate], or by calling [cancelActive].
 *
 * Serialization of state transitions is done with a [Mutex] — never
 * `synchronized` — so callers on any dispatcher, including suspending
 * callers, compose correctly. This class does not introduce a dispatcher of
 * its own: it purely orchestrates calls into [engine], which is responsible
 * for keeping its own blocking work off the caller's thread (see
 * `LlamaKtEngineImpl`, which routes every call through its own
 * single-thread dispatcher).
 */
class InferenceQueue(private val engine: InferenceEngine) {

    private val mutex = Mutex()

    private val _state = MutableStateFlow<QueueState>(QueueState.Idle)

    /** Current queue state — see [QueueState] for the transition diagram. */
    val state: StateFlow<QueueState> = _state.asStateFlow()

    /** Guarded by [mutex] everywhere except the read in [cancelActive]. */
    private var loadedModel: LoadedModel? = null

    /** Guarded by [mutex] everywhere except the read in [cancelActive]. */
    private var loadedInfo: LoadedModelInfo? = null

    /**
     * The [ModelLoadConfig] that produced [loadedModel]/[loadedInfo], stripped of its
     * [ModelLoadConfig.onProgress] callback, or `null` if nothing is loaded. Guarded by [mutex].
     * Lets [loadModel] recognize a repeat call for the model it just finished loading (two
     * `LoadModelViewModel` instances across a config change both requesting the same model — see
     * TEN-50) and return the cached result instead of loading it all over again.
     *
     * Keyed on the whole config, not just the path: reloading the same file with a different
     * [ModelLoadConfig.backend], [ModelLoadConfig.nCtx] or [ModelLoadConfig.kvCacheType] (a CPU
     * retry after a Vulkan failure, a new [LoadPolicy] plan on a device now under memory pressure)
     * is a genuine reload, not a repeat call.
     */
    private var loadedConfig: ModelLoadConfig? = null

    /**
     * Called just before a currently-loaded model is released — either
     * because [loadModel] is swapping it for a new one, or because
     * [unloadModel] is releasing it outright. `null` (the default) means no
     * hook is installed and nothing happens.
     *
     * This is the anchor point for TEN-27's save-before-load: persisting the
     * outgoing model's session is TEN-34's job, not this one — this field is
     * just where that future hook attaches.
     *
     * Runs under [mutex] in both call sites, so it must be short and must
     * never itself call back into this [InferenceQueue] (that would
     * deadlock on the same mutex). Any exception it throws propagates to the
     * caller of [loadModel]/[unloadModel] and aborts the swap/unload before
     * the previous model is freed.
     */
    var onBeforeModelSwap: (suspend () -> Unit)? = null

    /**
     * The [Job] of the [generate] coroutine currently producing events, if
     * any. Written only under [mutex] (in [generate]'s begin/end steps);
     * read without the lock in [cancelActive] since cancelling a job is
     * safe to call redundantly or on an already-completed job.
     */
    @Volatile
    private var activeGenerationJob: Job? = null

    /**
     * Loads the model described by [config], implicitly unloading and
     * releasing whatever model is currently loaded first (there is never
     * more than one model resident at a time).
     *
     * If [config] matches [loadedConfig] and [state] is
     * [QueueState.ModelReady], this is a no-op returning the already-loaded
     * [LoadedModelInfo] — no second [engine] load is issued. That covers a
     * model already loaded *and* one still loading, since the check runs
     * after acquiring [mutex] and [performLoadLocked] holds it for the whole
     * load. Without it, a second caller racing the first (see TEN-50) tears
     * the model down and reloads several GB from scratch.
     *
     * If a generation is active, it is cancelled first (per spec: stopping
     * generation takes priority over starting a load) and awaited before the
     * load proceeds, so the previous model is never released while a decode
     * may still be touching it. [onBeforeModelSwap], if installed, runs
     * right before the previous model is freed.
     *
     * On failure, the previous model has already been released and [state]
     * falls back cleanly to [QueueState.Idle] — it does not attempt to
     * restore the previous model.
     */
    suspend fun loadModel(config: ModelLoadConfig): LoadedModelInfo {
        while (true) {
            var pendingJob: Job? = null
            val info = mutex.withLock {
                val current = _state.value
                when {
                    loadedConfig == config.dedupKey() && current is QueueState.ModelReady -> current.info
                    current is QueueState.Generating -> {
                        // Someone is generating right now — stop them first (see class KDoc),
                        // then retry once they've fully wound down.
                        pendingJob = activeGenerationJob
                        null
                    }
                    else -> performLoadLocked(config)
                }
            }
            if (info != null) return info
            pendingJob?.cancel()
            pendingJob?.join()
        }
    }

    /**
     * Must only be called while holding [mutex]. Frees any currently loaded
     * model, then loads [config] via [engine]. On failure, [state] is reset
     * to [QueueState.Idle] and the exception is rethrown.
     */
    private suspend fun performLoadLocked(config: ModelLoadConfig): LoadedModelInfo {
        _state.value = QueueState.Loading(0f)

        val previous = loadedModel
        loadedModel = null
        loadedInfo = null
        loadedConfig = null
        if (previous != null) onBeforeModelSwap?.invoke()
        previous?.let { runCatching { it.free() } }

        return try {
            val effectiveConfig = config.copy(
                onProgress = { progress ->
                    _state.value = QueueState.Loading(progress)
                    config.onProgress?.invoke(progress)
                },
            )
            val model = engine.loadModel(effectiveConfig)
            val info = model.metadata
            loadedModel = model
            loadedInfo = info
            loadedConfig = config.dedupKey()
            _state.value = QueueState.ModelReady(info)
            info
        } catch (e: Exception) {
            _state.value = QueueState.Idle
            throw e
        }
    }

    /**
     * Unloads and releases the currently loaded model, if any. If a
     * generation is active it is cancelled first and awaited, same as
     * [loadModel]. A no-op (besides settling to [QueueState.Idle]) if no
     * model is loaded. [onBeforeModelSwap], if installed, runs right before
     * the model is freed.
     */
    suspend fun unloadModel() {
        while (true) {
            var pendingJob: Job? = null
            val done = mutex.withLock {
                if (_state.value is QueueState.Generating) {
                    pendingJob = activeGenerationJob
                    false
                } else {
                    val previous = loadedModel
                    loadedModel = null
                    loadedInfo = null
                    loadedConfig = null
                    if (previous != null) onBeforeModelSwap?.invoke()
                    previous?.let { runCatching { it.free() } }
                    _state.value = QueueState.Idle
                    true
                }
            }
            if (done) return
            pendingJob?.cancel()
            pendingJob?.join()
        }
    }

    /**
     * Streams a completion for [request] against the currently loaded
     * model.
     *
     * The returned [Flow] is cold: nothing happens until it is collected.
     * Cancelling collection stops the underlying generation (same
     * mechanism as [cancelActive]). Like [io.github.hokanosekai.tensai.engine.LoadedModel.decode],
     * the flow always terminates with exactly one terminal event —
     * [DecodeEvent.Done] or [DecodeEvent.Error] — after zero or more
     * [DecodeEvent.Token]s.
     *
     * Rejected (with a single [DecodeEvent.Error] wrapping a
     * [QueueBusyException]) if [state] is not [QueueState.ModelReady] when
     * collection starts — i.e. no model is loaded, a load is in progress,
     * or another generation is already active. This queue never queues up
     * a second generation behind the first; the caller must retry.
     */
    fun generate(request: DecodeRequest): Flow<DecodeEvent> = channelFlow {
        val model = mutex.withLock {
            val current = _state.value
            if (current is QueueState.ModelReady) {
                _state.value = QueueState.Generating(current.info)
                activeGenerationJob = coroutineContext[Job]
                loadedModel
            } else {
                null
            }
        }

        if (model == null) {
            send(
                DecodeEvent.Error(
                    QueueBusyException(
                        "Cannot start generation: queue is not ready (state=${_state.value})",
                    ),
                ),
            )
            return@channelFlow
        }

        try {
            model.decode(request).collect { event -> send(event) }
        } finally {
            // Cleanup must run even if this coroutine was cancelled (by cancelActive() or by
            // the collector), so it can't itself be interrupted mid-cleanup.
            withContext(NonCancellable) {
                mutex.withLock {
                    activeGenerationJob = null
                    _state.value = loadedInfo?.let { QueueState.ModelReady(it) } ?: QueueState.Idle
                }
            }
        }
    }

    /**
     * Stops the currently active generation, if any (e.g. for a "stop"
     * button or a notification action). No-op if no generation is active.
     * Does not wait for the stop to complete — collect [generate]'s flow to
     * completion (or observe [state] return to [QueueState.ModelReady]) for
     * that.
     */
    fun cancelActive() {
        activeGenerationJob?.cancel()
    }

    /**
     * Best-effort KV-cache fill ratio (0f..1f) for whatever is currently loaded, or `null` if no
     * model is loaded or [engine] isn't a [LlamaKtEngine] (the only kind that exposes cache
     * introspection — see its KDoc). Reads [LlamaKtEngine.kvCacheUsedCells] live, so calling this
     * mid-generation reflects the cache as it fills token by token; there's no push notification
     * for it, callers that want a live value must poll.
     *
     * Reads [state] rather than the mutex-guarded [loadedInfo] field — same rationale as
     * [cancelActive]: a plain [StateFlow] read is safe from any thread without taking [mutex].
     */
    fun kvUsedPercent(): Float? {
        // residentModel, not ModelReady alone: matching only the idle state would return null for
        // the whole of a generation, which is exactly when the KDoc above promises a live figure.
        val info = _state.value.residentModel ?: return null
        val llamaEngine = engine as? LlamaKtEngine ?: return null
        if (info.nCtxEffective <= 0) return null
        return llamaEngine.kvCacheUsedCells().toFloat() / info.nCtxEffective
    }
}
