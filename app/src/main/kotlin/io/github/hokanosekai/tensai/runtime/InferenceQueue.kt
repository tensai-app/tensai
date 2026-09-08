package io.github.hokanosekai.tensai.runtime

import io.github.hokanosekai.tensai.engine.DecodeEvent
import io.github.hokanosekai.tensai.engine.DecodeRequest
import io.github.hokanosekai.tensai.engine.InferenceEngine
import io.github.hokanosekai.tensai.engine.LlamaKtEngine
import io.github.hokanosekai.tensai.engine.LoadedModel
import io.github.hokanosekai.tensai.engine.LoadedModelInfo
import io.github.hokanosekai.tensai.engine.ModelLoadConfig
import kotlinx.coroutines.CancellationException
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
 *   cancelled — a model is normally still loaded after [Generating].
 * - [Generating] → [Idle] : the same cleanup, on a queue whose model went
 *   away underneath it. Not a transition anything aims for, but the one
 *   [InferenceQueue.generate] must still be able to reach: refusing to
 *   settle because the state does not fit the diagram is what left the
 *   queue on [Generating] for good (TEN-74).
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
 * The display name of the model this queue currently holds in memory — [residentModel] read off
 * [InferenceQueue.state], as one named thing rather than an expression each caller writes out.
 *
 * It exists because of the one caller that must never get this wrong:
 * [io.github.hokanosekai.tensai.data.models.ModelRepository]'s `currentLoadedModelName`, whose
 * in-use guard is the only thing standing between a mid-generation delete and an `unlink` of a
 * `.gguf` llama.cpp still has mmapped (TEN-76). Wiring that guard to an expression matching
 * `ModelReady` alone left it blind for the whole duration of a reply. Wire it to this instead, so
 * the guard and the tests that pin it read the same derivation.
 */
val InferenceQueue.residentModelName: String?
    get() = state.value.residentModel?.name

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
 *
 * **A queue borrows its [engine]; it does not own it and never closes it**
 * (TEN-66). Closing an engine ends the thread it runs everything on, and in
 * this app the very instance handed here is shared with two other consumers:
 * `RuntimeGraph` gives the same one to `ModelRepository` for its GGUF metadata
 * reads and to the save-before-swap hook wired into [onBeforeModelSwap]. A
 * queue tearing that down would break both, neither of which asked for
 * anything. Releasing the *model* is a different matter and is this class's
 * job — see [unloadModel]. Whoever constructed the engine closes it, once,
 * when nobody is using it any more (see `LlamaKtEngineImpl.close`).
 */
class InferenceQueue(private val engine: InferenceEngine) {

    private val mutex = Mutex()

    private val _state = MutableStateFlow<QueueState>(QueueState.Idle)

    /** Current queue state — see [QueueState] for the transition diagram. */
    val state: StateFlow<QueueState> = _state.asStateFlow()

    /**
     * Guarded by [mutex], with no exception — [cancelActive] reads [activeGenerationJob], not this.
     * Non-null exactly when [state] is [QueueState.ModelReady] or [QueueState.Generating]; the
     * invariant is what [generate] trusts when it hands out [loadedModel] on the strength of
     * [state] alone, and restoring it on every path out of a release is TEN-74.
     */
    private var loadedModel: LoadedModel? = null

    /** Guarded by [mutex]. Moves in lockstep with [loadedModel] — same invariant, same reason. */
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
     * deadlock on the same mutex).
     *
     * Any exception it throws propagates to the caller of
     * [loadModel]/[unloadModel] and aborts the operation — but it does *not*
     * abort the release itself: the previous model is freed and [state] is
     * settled to [QueueState.Idle] first, so the caller sees a failed load or
     * unload against a queue with nothing loaded, never a queue still
     * advertising a model whose only reference this hook just leaked
     * (TEN-74). A hook cannot veto a release it is merely notified of.
     */
    var onBeforeModelSwap: (suspend () -> Unit)? = null

    /**
     * The [Job] of the [generate] coroutine currently producing events, if
     * any. Written only under [mutex] (in [generate]'s begin/end steps);
     * read without the lock in [cancelActive] since cancelling a job is
     * safe to call redundantly or on an already-completed job.
     *
     * Set *before* [state] goes to [QueueState.Generating] and cleared *after* it leaves it, so
     * anyone who sees `Generating` sees a job to cancel (see [generate]). The reverse slack (a job
     * still set while [state] already reads [QueueState.ModelReady]) is harmless: cancelling a
     * generation that has already left its decode loop changes nothing. (Not "already produced its
     * terminal event" — a *cancelled* decode produces none, by design; see [LlamaKtEngineImpl]'s
     * `trySend(DecodeEvent.Done)`.)
     *
     * That ordering only means anything because this is `@Volatile` and `MutableStateFlow.value` is
     * itself a volatile write: two volatile writes are not reordered, so a reader that sees
     * `Generating` is guaranteed to see the job. Dropping `@Volatile` would silently undo the whole
     * fix, and no test in this repo would fail (see [InferenceQueueTest] — the ordering window is
     * not reachable from a single-threaded `runBlocking`, so it is unpinned by construction).
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
                        pendingJob = liveGenerationJobLocked()
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
     * The live [Job] behind the [QueueState.Generating] the caller just read, for it to cancel and
     * join before retrying its load/unload. Must only be called while holding [mutex], and only
     * with [state] actually on [QueueState.Generating].
     *
     * Throws [IllegalStateException] instead of returning a job that cannot make the state move.
     * Only a generation's own cleanup leaves `Generating`, and that cleanup takes [mutex] before
     * its job completes — so a missing or already-completed job, observed under that same lock,
     * means nothing will ever leave `Generating` again. Returning it would have
     * [loadModel]/[unloadModel] cancel a finished job, join it (returning at once, without ever
     * suspending), re-read `Generating` and go round again: a tight loop pinning a core until the
     * process dies, which is what turned TEN-74's broken invariant into a hang. Checking under the
     * lock is what makes this exact rather than a heuristic — a generation that ends *while* the
     * caller waits is not this case, it is the normal one, and its job is still live here.
     */
    private fun liveGenerationJobLocked(): Job {
        val job = activeGenerationJob
        if (job == null || job.isCompleted) {
            error(
                "Queue state is ${_state.value} but no generation is running: " +
                    "the queue can no longer load or unload",
            )
        }
        return job
    }

    /**
     * Must only be called while holding [mutex]. Frees any currently loaded
     * model, then loads [config] via [engine]. On failure, [state] is reset
     * to [QueueState.Idle] and the exception is rethrown.
     */
    private suspend fun performLoadLocked(config: ModelLoadConfig): LoadedModelInfo {
        _state.value = QueueState.Loading(0f)

        try {
            releaseLoadedLocked()
        } catch (e: Exception) {
            // The hook threw: nothing is loaded any more (see [releaseLoadedLocked]), so settle
            // where a failed load settles rather than leave [state] stuck on `Loading` forever.
            _state.value = QueueState.Idle
            throw e
        }

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
     * Must only be called while holding [mutex]. Clears the loaded-model fields, runs
     * [onBeforeModelSwap] if there was actually something loaded, and releases it. Leaves [state]
     * alone — settling it is the caller's job, on both the normal and the throwing path.
     *
     * The hook's exception propagates, but only after `previous` has been freed. Skipping the
     * free (as this used to) dropped the last reference to several GB of native model with no way
     * left to release it, and — because [unloadModel] then also left [state] on
     * [QueueState.ModelReady] — handed the next [generate] a state that says "ready" over a
     * `null` model. That is TEN-74's first link.
     *
     * The free is [NonCancellable] for the same reason, against a trigger that is easier to reach
     * than a throwing hook. [LoadedModel.free] suspends (`LlamaKtEngineImpl` routes it through its
     * own dispatcher), so on a cancelled caller — a `viewModelScope` cleared while a swap is in
     * flight — plain `free()` would throw at its first suspension point *without freeing*, and the
     * `runCatching` would file that away as just another failed release. Cancelling a release has
     * to still release; the caller's coroutine is already ending either way, and the alternative is
     * the same leak by a different door.
     */
    private suspend fun releaseLoadedLocked() {
        val previous = loadedModel
        loadedModel = null
        loadedInfo = null
        loadedConfig = null
        try {
            if (previous != null) onBeforeModelSwap?.invoke()
        } finally {
            previous?.let { withContext(NonCancellable) { runCatching { it.free() } } }
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
                    pendingJob = liveGenerationJobLocked()
                    false
                } else {
                    try {
                        releaseLoadedLocked()
                    } finally {
                        // Even if the hook threw: the model is gone either way, and leaving
                        // `ModelReady` up over a `null` model is exactly what TEN-74 turns into a
                        // wedged queue.
                        _state.value = QueueState.Idle
                    }
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
        // Whether this call took the queue over — i.e. published the two writes below and so owes
        // the matching cleanup. Not `model != null`: if [state] ever says `ModelReady` over a
        // `null` [loadedModel], this call has already claimed the queue and must still hand it
        // back (TEN-74's second link). Not unconditional either: a call rejected because someone
        // *else* is generating must not run a cleanup that would clear that generation's job and
        // drop [state] out of `Generating` behind its back.
        var acquired = false
        val model = mutex.withLock {
            val current = _state.value
            if (current is QueueState.ModelReady) {
                acquired = true
                // Job first, state second, and the order matters: everything that offers a "stop"
                // (`ChatScreen`'s button, `InferenceService`'s notification action) decides
                // whether to offer it from [state] alone, then calls [cancelActive], which reads
                // this field without the lock. Publishing `Generating` first would leave a window,
                // however narrow, where a stop is offered against a job that isn't there yet, and
                // [cancelActive] would silently do nothing.
                activeGenerationJob = coroutineContext[Job]
                _state.value = QueueState.Generating(current.info)
                loadedModel
            } else {
                null
            }
        }

        try {
            if (model == null) {
                // Inside the `try`, so this exit runs the cleanup below like any other. Outside
                // it, a rejection issued *after* `acquired` left the queue on `Generating` with
                // no generation behind it, and every later load/unload wedged on it (TEN-74).
                send(
                    DecodeEvent.Error(
                        QueueBusyException(
                            "Cannot start generation: queue is not ready (state=${_state.value})",
                        ),
                    ),
                )
                return@channelFlow
            }
            model.decode(request).collect { event -> send(event) }
        } finally {
            // Cleanup must run even if this coroutine was cancelled (by cancelActive() or by
            // the collector), so it can't itself be interrupted mid-cleanup.
            if (acquired) {
                withContext(NonCancellable) {
                    mutex.withLock {
                        // Mirror of the begin step's ordering: leave `Generating` first, drop the
                        // job second, so [state] is never `Generating` with nothing behind it. A
                        // [cancelActive] landing in between cancels a job that is already inside
                        // this very (NonCancellable) cleanup: a no-op, not a torn teardown.
                        _state.value = loadedInfo?.let { QueueState.ModelReady(it) } ?: QueueState.Idle
                        activeGenerationJob = null
                    }
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

    /**
     * Number of tokens currently resident in whatever is loaded's KV cache, or `null` under the
     * same conditions as [kvUsedPercent] (no model loaded, [engine] isn't a [LlamaKtEngine]).
     *
     * Exists so [io.github.hokanosekai.tensai.data.session.SessionRepository.save] can cheaply
     * check "would this save write anything new" *before* paying for an actual
     * [LlamaKtEngine.saveSession] call (TEN-63) — this is a plain atomic read, unlike
     * [saveSession], which serializes a multi-hundred-KB-per-token file write behind [mutex].
     */
    fun currentKvTokenCount(): Int? {
        _state.value.residentModel ?: return null
        val llamaEngine = engine as? LlamaKtEngine ?: return null
        return llamaEngine.kvCacheUsedCells()
    }

    /**
     * Persists the currently loaded model's KV-cache/prompt state to [path] — TEN-34's
     * app-triggered save (e.g. the app going to background), as opposed to [onBeforeModelSwap]'s
     * save-before-swap. Returns the number of tokens saved (`>= 0`) on success, `-1` if no model is
     * loaded, [state] is not [QueueState.ModelReady] (in particular: mid-generation, so a save
     * can't stall behind an in-flight decode holding [engine]'s own dispatcher — see
     * `LlamaKtEngineImpl`'s KDoc), [engine] isn't a [LlamaKtEngine], or the underlying save itself
     * failed.
     *
     * No *failure* throws, then — but a [CancellationException] does (TEN-73), because it is not
     * one. See the guard in the body.
     *
     * Serialized on [mutex] like every other queue operation, so it can never race a concurrent
     * [loadModel]/[unloadModel]/[generate] — unlike [onBeforeModelSwap], which runs already inside
     * [mutex], this is meant for callers *outside* the queue and must take the lock itself.
     */
    suspend fun saveSession(path: String): Int = mutex.withLock {
        if (_state.value !is QueueState.ModelReady) return@withLock -1
        val llama = engine as? LlamaKtEngine ?: return@withLock -1
        try {
            llama.saveSession(path)
        } catch (e: CancellationException) {
            // Ahead of the catch below, which reported a cancelled dump as `-1` — indistinguishable
            // from an engine that tried and failed, and swallowed on the way (TEN-73). This is the
            // layer that matters for it: `SessionRepository.save` takes this method as its
            // `saveOnEngine`, so a `-1` here is what it turns into "Session save reported failure",
            // and its own cancellation guard would never have seen a thing.
            throw e
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Restores a previously-saved KV-cache/prompt state from [path] onto whatever model is
     * currently loaded — the app-side counterpart of prefill, letting a resumed conversation skip
     * re-feeding a prompt prefix already present in [path]. Callers are responsible for validating
     * that [path] actually matches the currently loaded model/context/KV-cache-type *before*
     * calling this (see `io.github.hokanosekai.tensai.data.session.SessionRepository`) — this only
     * forwards to [LlamaKtEngine.loadSession] and does not itself re-check compatibility.
     *
     * Returns `false` if no model is loaded, [state] is not [QueueState.ModelReady], [engine]
     * isn't a [LlamaKtEngine], or the underlying restore failed — every case is a clean signal to
     * fall back to a normal prefill. A [CancellationException] is the one thing that throws instead
     * (TEN-73): it is not a verdict on the session, and callers treat `false` as one.
     *
     * Serialized on [mutex] like every other queue operation, so it can't race a concurrent
     * [loadModel]/[unloadModel]/[generate] — must be called before [generate].
     */
    suspend fun restoreSession(path: String): Boolean = mutex.withLock {
        if (_state.value !is QueueState.ModelReady) return@withLock false
        val llama = engine as? LlamaKtEngine ?: return@withLock false
        try {
            llama.loadSession(path)
        } catch (e: CancellationException) {
            // As in [saveSession], and worse downstream: `SessionRepository.restore` reads a
            // `false` from its `restoreOnEngine` as "this session is unusable" and deletes the
            // state file and its row to stop it being retried forever. A cancelled caller would
            // have taken a good session with it (TEN-73).
            throw e
        } catch (e: Exception) {
            false
        }
    }
}
