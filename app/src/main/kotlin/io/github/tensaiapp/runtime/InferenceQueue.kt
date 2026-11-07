package io.github.tensaiapp.runtime

import io.github.tensaiapp.data.session.SessionRestoreOutcome
import io.github.tensaiapp.data.session.SessionSaveOutcome
import io.github.tensaiapp.engine.ChatMessage
import io.github.tensaiapp.engine.DecodeEvent
import io.github.tensaiapp.engine.DecodeRequest
import io.github.tensaiapp.engine.InferenceEngine
import io.github.tensaiapp.engine.LoadStage
import io.github.tensaiapp.engine.LlamaKtEngine
import io.github.tensaiapp.engine.LoadedModel
import io.github.tensaiapp.engine.LoadedModelInfo
import io.github.tensaiapp.engine.MessageRole
import io.github.tensaiapp.engine.ModelLoadConfig
import io.github.tensaiapp.engine.ModelLoadException
import io.github.tensaiapp.engine.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.job
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

    /**
     * A model load is in progress. [progress] is in 0.0–1.0. [modelId] is the
     * [ModelLoadConfig.modelId] being loaded, `null` for a bare-path load: see [modelInUseId].
     */
    data class Loading(val progress: Float, val stage: LoadStage = LoadStage.Weights, val modelId: String? = null) : QueueState()

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
 * The [io.github.tensaiapp.data.models.ModelEntity.id] of the model this queue currently
 * holds in memory — [residentModel] read off [InferenceQueue.state], as one named thing rather
 * than an expression each caller writes out. `null` when nothing is loaded, and also when what is
 * loaded came in without an id (a bare-path load — see [LoadedModelInfo.modelId]).
 *
 * It was written for [io.github.tensaiapp.data.models.ModelRepository]'s in-use guard, the only
 * thing standing between a mid-generation delete and an `unlink` of a `.gguf` llama.cpp still has
 * mmapped (TEN-76): an expression matching `ModelReady` alone left it blind for the whole duration
 * of a reply. The guard now goes through [InferenceQueue.unlessInUse], which also counts a load
 * (TEN-177), see [modelInUseId].
 *
 * Was the display *name* until TEN-80, which is a weaker thing to compare: two quantisations of
 * one model share a GGUF name, so the guard refused to delete either while one was loaded.
 */
val InferenceQueue.residentModelId: String?
    get() = state.value.residentModel?.modelId

/**
 * The [io.github.tensaiapp.data.models.ModelEntity.id] of the model resident *or being loaded*
 * (TEN-177): the one whose file must not be deleted. [residentModelId] alone reads "nothing" for
 * the whole of a load, and a delete then would leave the model loading from an unlinked file, or
 * failing to.
 */
val QueueState.modelInUseId: String?
    get() = residentModel?.modelId ?: (this as? QueueState.Loading)?.modelId

/**
 * Thrown (as [DecodeEvent.Error]) when [InferenceQueue.generate] is called
 * while the queue cannot start a new generation — either another
 * generation is already active, or no model is currently loaded.
 */
class QueueBusyException(message: String) : Exception(message)

/**
 * The load-relevant part of a [ModelLoadConfig] — everything except
 * [ModelLoadConfig.onProgress] and [ModelLoadConfig.onStage], which are per-call and never identifies a model.
 * Two configs with equal dedup keys produce an identical loaded model, which is what lets
 * [InferenceQueue.loadModel] short-circuit a repeat call (see TEN-50).
 */
private fun ModelLoadConfig.dedupKey(): ModelLoadConfig = copy(onProgress = null, onStage = null)

/**
 * "The user asked for this generation to stop" — one instance per [InferenceQueue.generate] call,
 * created by whoever collects it and read back by that same collector once the flow ends (TEN-79).
 *
 * It exists because the two halves of that sentence live in different objects. *Which* generation
 * gets cancelled is decided by [InferenceQueue.cancelActive], which is app-wide and knows nothing
 * about conversations; *what to record about it* is decided by the collector, which knows only its
 * own conversation. A `ChatViewModel` keeping a private "I tapped stop" flag gets the pairing wrong
 * the moment those two disagree: with a reply streaming in conversation A and the user looking at
 * B, B's stop button cancels A — there is one queue and one generation — while the flag that was
 * set belongs to B. A's own collector then sees an unexplained cancellation and persists the reply
 * truncated, unmarked, indistinguishable from a short complete one, and replays it into every later
 * prompt as if the model had finished speaking. That is the defect TEN-39 fixed for one path and
 * this reintroduced through another; carrying the fact on the generation instead of on the caller
 * closes both, and the notification's stop action ([InferenceService]) with them — it goes through
 * the same [InferenceQueue.cancelActive] and so labels what it cancels, where it used to be
 * deliberately left unlabelled for want of anywhere to put the fact.
 *
 * Deliberately a plain "was it asked for", not "why": a cancellation the queue did not initiate
 * (the collector walking away, a model swap) leaves this `false`, which is what lets a caller tell
 * a stop from any other way its collection can end.
 */
class GenerationStopSignal {

    /**
     * `@Volatile`: written from whatever thread called [InferenceQueue.cancelActive] (the UI
     * thread, or a service callback) and read from the collector's.
     */
    @Volatile
    private var requested = false

    /** Whether [InferenceQueue.cancelActive] cancelled *this* generation. */
    val wasRequested: Boolean get() = requested

    /** Set by [InferenceQueue.cancelActive], and by nothing else — see this class's KDoc. */
    internal fun markRequested() {
        requested = true
    }
}

/** How an [InferenceQueue.warmUp] ended. */
enum class WarmUpOutcome {
    /** The cache now holds the whole warm-up prompt. */
    Warmed,

    /**
     * Nothing was done: the queue was not on the expected model, was busy, already had a warm-up
     * running, or the engine declined (no room in the window, no prefill support, nothing to cut
     * the prompt at).
     */
    Skipped,

    /**
     * The queue stopped it to make way for a generation, a load, an unload or a restore. Whatever
     * had been decoded by then stays in the cache, as a valid prefix of the prompt the next turn
     * will send.
     */
    Interrupted,

    /** The engine threw. Swallowed: a warm-up is an optimisation and must never fail a turn. */
    Failed,
}

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
 *
 * @param modelFileExists whether a [ModelLoadConfig.path] is there to load, asked before the
 *   resident model is released (TEN-177). `true` by default for engines whose paths are not files
 *   (the tests' fakes); `RuntimeGraph` checks the file.
 */
class InferenceQueue(
    private val engine: InferenceEngine,
    private val modelFileExists: (path: String) -> Boolean = { true },
) {

    private val mutex = Mutex()

    private val _state = MutableStateFlow<QueueState>(QueueState.Idle)

    /** Current queue state — see [QueueState] for the transition diagram. */
    val state: StateFlow<QueueState> = _state.asStateFlow()

    /**
     * Guarded by [mutex], with no exception — [cancelActive] reads [activeGeneration], not this.
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

    /** The [Job] of the generation currently producing events, and the signal it reports a stop on. */
    private class ActiveGeneration(val job: Job, val stopSignal: GenerationStopSignal?)

    /**
     * The generation currently producing events, if any. Written only under [mutex] (in
     * [generate]'s begin/end steps); read without the lock in [cancelActive] since cancelling a job
     * is safe to call redundantly or on an already-completed job.
     *
     * One field holding both halves, not two fields (TEN-79): [cancelActive] marks a signal and
     * cancels a job, and reading those from two separate references admits a tear where the signal
     * belongs to a generation that has just ended and the job to the one that just started —
     * exactly the mislabelling [GenerationStopSignal] exists to remove. A single volatile read
     * cannot come apart that way.
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
    private var activeGeneration: ActiveGeneration? = null

    /**
     * The [warmUp] currently holding the engine, if any (TEN-88). Written only under [mutex], and
     * only while [state] is [QueueState.ModelReady]: a warm-up is deliberately *not* a state of its
     * own, so the notification and the chat chrome keep saying "ready" and nothing offers a stop for
     * something the user never asked for.
     *
     * What keeps it out of everyone's way is that it does not hold [mutex] while it runs (it can
     * last minutes) and so *cannot* exclude anyone by itself. Every operation that takes the engine
     * for its own purposes looks here under the lock instead, and when it finds one, leaves the
     * lock, cancels it and tries again: [generate],
     * [loadModel], [unloadModel], [saveSession] and [restoreSession]. [countTokens] looks too, and
     * declines rather than cancel it (TEN-181). The warm-up's own cleanup
     * clears this *inside* the job being joined, so a joiner that wakes up sees it cleared; clearing
     * it after the join would have a retry loop spin against a job that is already complete, the
     * shape [liveGenerationJobLocked] documents.
     *
     * `@Volatile` for [cancelWarmUp], which reads it without the lock like [cancelActive] does.
     */
    @Volatile
    private var activeWarmUp: Job? = null

    /** See [holdsOnlyWarmedPrefix]. Written under [mutex], except by a [warmToolPrefix] that has just taken [activeWarmUp]. */
    @Volatile
    private var prefixOnly = false

    /** The last [warmToolPrefix] ran to the end (the prefix is resident and its file written), so [generate] has nothing to finish. */
    @Volatile
    private var prefixWritten = false

    /** What decides the tool prefix of the last request that filled the cache, see [holdsPrefixOf]; `null` when unknown. Written like [prefixOnly]. */
    @Volatile
    private var residentPrefix: PrefixKey? = null

    /** Generations started so far, see [releaseBenchCache]. Written under [mutex]. */
    @Volatile
    private var generationsStarted = 0L

    /** The session [restoreSession] put in the cache was saved without tools, see [holdsNoToolPrefix]. Read while [residentPrefix] is `null`. */
    @Volatile
    private var restoredWithoutTools = false

    /**
     * Loads the model described by [config], implicitly unloading and
     * releasing whatever model is currently loaded first (there is never
     * more than one model resident at a time).
     *
     * If [config] matches [loadedConfig] and a model is actually resident,
     * this is a no-op returning the already-loaded [LoadedModelInfo] — no
     * second [engine] load is issued. That covers a model already loaded
     * *and* one still loading, since the check runs after acquiring [mutex]
     * and [performLoadLocked] holds it for the whole load. Without it, a
     * second caller racing the first (see TEN-50) tears the model down and
     * reloads several GB from scratch.
     *
     * If a generation is active, it is cancelled first (per spec: stopping
     * generation takes priority over starting a load) and awaited before the
     * load proceeds, so the previous model is never released while a decode
     * may still be touching it. [onBeforeModelSwap], if installed, runs
     * right before the previous model is freed.
     *
     * **The dedup is decided before that, and on the resident model rather
     * than on [state]** (TEN-84). Matching [QueueState.ModelReady] alone
     * reads "no model" for the whole duration of a reply (the same blind
     * spot [residentModel] exists for), so a repeat load requested while one
     * was streaming fell through to the `Generating` branch: it cancelled
     * the reply, went round the loop, and only *then* recognized the request
     * as the no-op it had been all along. The user lost a reply to a tap
     * that, by this method's own reckoning, asked for nothing — and the load
     * it appeared to trigger never happened either, so nothing on screen
     * explained the reply stopping. Deduplicating first costs nothing:
     * a no-op load has no reason to touch a generation, and a load that
     * really is a swap still cancels it on the branch below.
     *
     * On failure, the previous model has already been released and [state]
     * falls back cleanly to [QueueState.Idle], it does not attempt to
     * restore the previous model. Except for a missing file, refused before
     * anything is released (TEN-177): the previous model stays loaded.
     */
    /**
     * Whether [loadModel] with [config] would be the no-op dedup (this exact config is already
     * resident). A hint read without the lock: callers use it to skip load-only UI, and a stale
     * answer only costs a brief extra status screen.
     */
    fun isResident(config: ModelLoadConfig): Boolean =
        loadedConfig == config.dedupKey() && _state.value.residentModel != null

    suspend fun loadModel(config: ModelLoadConfig): LoadedModelInfo {
        while (true) {
            var pendingJob: Job? = null
            val info = mutex.withLock {
                val current = _state.value
                val resident = current.residentModel
                when {
                    loadedConfig == config.dedupKey() && resident != null -> resident
                    current is QueueState.Generating -> {
                        // Someone is generating right now — stop them first (see class KDoc),
                        // then retry once they've fully wound down.
                        pendingJob = liveGenerationJobLocked()
                        null
                    }
                    // A warm-up is background work for a conversation the user is leaving by
                    // asking for this load: stopped the same way, and awaited for the same reason
                    // (the model must not be released under a prefill still decoding).
                    activeWarmUp != null -> {
                        pendingJob = activeWarmUp
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
        val job = activeGeneration?.job
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
     *
     * The load and the three fields that record it are [NonCancellable], for the same reason
     * [releaseLoadedLocked]'s free is (TEN-74) and against the same trigger. `LlamaKtEngineImpl`
     * runs the blocking native load inside a `withContext(dispatcher)`, which has no suspension
     * point to interrupt: on a cancelled caller the weights are allocated in full and then the
     * dispatch *return* throws, dropping the only reference to several GB with nothing left to
     * free them. Nothing here would notice — [state] falls back to [QueueState.Idle] and the queue
     * reads as empty, so the user's retry loads a second copy *on top* of the first.
     *
     * The consequence is that a cancelled load still ends on [QueueState.ModelReady] over a
     * genuinely loaded model, and that is the point: it is the only outcome from which the model
     * can still be unloaded. The caller's coroutine is ending either way.
     */
    private suspend fun performLoadLocked(config: ModelLoadConfig): LoadedModelInfo {
        // Before the release: a model deleted while this load waited for the lock would otherwise cost
        // the resident one (its session saved, its weights freed) for a load that cannot succeed.
        if (!modelFileExists(config.path)) throw ModelLoadException("Model file not found: ${config.path}")
        _state.value = QueueState.Loading(0f, LoadStage.Preparing, config.modelId)

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
                    // The first progress value proves the engine is reading weights, even for an engine
                    // that reports no stages.
                    val stage = (_state.value as? QueueState.Loading)?.stage
                        ?.takeIf { it != LoadStage.Preparing } ?: LoadStage.Weights
                    _state.value = QueueState.Loading(progress, stage, config.modelId)
                    config.onProgress?.invoke(progress)
                },
                onStage = { stage ->
                    val progress = (_state.value as? QueueState.Loading)?.progress ?: 0f
                    _state.value = QueueState.Loading(progress, stage, config.modelId)
                    config.onStage?.invoke(stage)
                },
            )
            withContext(NonCancellable) {
                val model = engine.loadModel(effectiveConfig)
                val info = model.metadata
                loadedModel = model
                loadedInfo = info
                loadedConfig = config.dedupKey()
                _state.value = QueueState.ModelReady(info)
                info
            }
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
     *
     * The hook is inside that same [NonCancellable] block rather than outside it, because a
     * half-run hook is worse than a skipped free: what it does here (`RuntimeGraph`) is write the
     * outgoing conversation's KV cache to disk, and it suspends on that I/O. A cancelled caller
     * used to abandon it mid-write and then free the model anyway — the cache destroyed, nothing
     * on disk, and silently: `RuntimeGraph`'s `runCatching` hands back a `Result.failure` nobody
     * reads. Both halves of "save, then free" have to survive the cancellation or neither is worth
     * running.
     */
    private suspend fun releaseLoadedLocked() {
        val previous = loadedModel
        prefixOnly = false
        loadedModel = null
        loadedInfo = null
        loadedConfig = null
        try {
            if (previous == null) return
            withContext(NonCancellable) {
                try {
                    onBeforeModelSwap?.invoke()
                } finally {
                    runCatching { previous.free() }
                }
            }
        } finally {
            // After the hook: its save reads [holdsNoToolPrefix] (TEN-196).
            residentPrefix = null
            restoredWithoutTools = false
        }
    }

    /**
     * Runs [block] unless [modelId] is resident or loading, and answers whether it ran (TEN-177):
     * [io.github.tensaiapp.data.models.ModelRepository.deleteModel]'s check and unlink as one step.
     * Under [mutex], which a load holds from start to end, so a load of [modelId] has either ended
     * (the model is resident, refused) or starts once the file is gone, and then fails before it
     * releases whatever is resident (see [performLoadLocked]). One already loading is refused at once
     * rather than waited for.
     */
    suspend fun unlessInUse(modelId: String, block: suspend () -> Unit): Boolean {
        if (_state.value.modelInUseId == modelId) return false
        return mutex.withLock {
            if (_state.value.modelInUseId == modelId) return@withLock false
            block()
            true
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
                } else if (activeWarmUp != null) {
                    pendingJob = activeWarmUp
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
     * mechanism as [cancelActive]). Like [io.github.tensaiapp.engine.LoadedModel.decode],
     * the flow always terminates with exactly one terminal event —
     * [DecodeEvent.Done] or [DecodeEvent.Error] — after zero or more
     * [DecodeEvent.Token]s.
     *
     * Rejected (with a single [DecodeEvent.Error] wrapping a
     * [QueueBusyException]) if [state] is not [QueueState.ModelReady] when
     * collection starts — i.e. no model is loaded, a load is in progress,
     * or another generation is already active. This queue never queues up
     * a second generation behind the first; the caller must retry.
     *
     * A running [warmUp] is no reason to reject: it is cancelled and awaited before the generation
     * starts (TEN-88), and the prefix it had already decoded is reused by this one.
     *
     * @param stopSignal marked if — and only if — [cancelActive] is what ends *this* generation,
     *   so the collector can tell a stop the user asked for from every other way its collection
     *   can end (TEN-79). Optional: a caller with nothing to record about it passes none.
     * @param onStart runs under [mutex] as this call takes the queue, before anything touches the
     *   cache, and not at all for a rejected call: from here the cache is the caller's, and no
     *   [saveSession] can land between this and the decode. Must not suspend or throw.
     */
    fun generate(
        request: DecodeRequest,
        stopSignal: GenerationStopSignal? = null,
        onStart: (() -> Unit)? = null,
    ): Flow<DecodeEvent> = channelFlow {
        // Whether this call took the queue over — i.e. published the two writes below and so owes
        // the matching cleanup. Not `model != null`: if [state] ever says `ModelReady` over a
        // `null` [loadedModel], this call has already claimed the queue and must still hand it
        // back (TEN-74's second link). Not unconditional either: a call rejected because someone
        // *else* is generating must not run a cleanup that would clear that generation's job and
        // drop [state] out of `Generating` behind its back.
        var acquired = false
        // The cache held a warmed prefix that was not written out when this call took the queue (see [prefixWritten]).
        var wasPrefixOnly = false
        // The cache holds no tool prefix at all, see [holdsNoToolPrefix].
        var heldNoTools = false
        val model = underLockWithoutWarmUp {
            val current = _state.value
            if (current is QueueState.ModelReady) {
                // Ahead of the claim below: a throw here leaves the queue as it was.
                onStart?.invoke()
                acquired = true
                // Job first, state second, and the order matters: everything that offers a "stop"
                // (`ChatScreen`'s button, `InferenceService`'s notification action) decides
                // whether to offer it from [state] alone, then calls [cancelActive], which reads
                // this field without the lock. Publishing `Generating` first would leave a window,
                // however narrow, where a stop is offered against a job that isn't there yet, and
                // [cancelActive] would silently do nothing.
                activeGeneration = coroutineContext[Job]?.let { ActiveGeneration(it, stopSignal) }
                generationsStarted++
                wasPrefixOnly = prefixOnly && !prefixWritten
                prefixOnly = false
                heldNoTools = holdsNoToolPrefix()
                residentPrefix = request.prefixKey()
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
            // A warm-up cut short (a send, a background) left the prefix, whole or partial, in the cache with no
            // file written, and decode skips its own build on a cache that is not empty. Finish it here: the
            // prefill resumes from what is resident, so this costs the file write and nothing else (TEN-205).
            // Same skip after a turn without tools (a creative first turn, the plain path), and the whole prefix would be
            // prefilled again (TEN-195). That prompt shares tokens with a tool prompt only up to where the system
            // messages diverge, inside the system block, before the end of the prefix the file holds: everything the
            // decode would keep is in the file too, so restoring it in place of the cache loses nothing.
            if (request.tools.isNotEmpty() && (wasPrefixOnly || heldNoTools)) model.warmToolPrefix(request, replaceResident = true)
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
                        activeGeneration = null
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
     *
     * Marks that generation's [GenerationStopSignal] first, so it is recorded as stopped *by the
     * user* rather than as one more cancellation of unknown origin — see that class for why the
     * fact belongs on the generation and not on whoever pressed the button. Marked before the
     * cancel, never after: the collector's `finally` can start running the instant the job is
     * cancelled, and a mark landing after it has already read the signal would arrive too late to
     * label anything.
     */
    fun cancelActive() {
        // One read, so the signal and the job are guaranteed to be the same generation's.
        val active = activeGeneration ?: return
        active.stopSignal?.markRequested()
        active.job.cancel()
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
     * Exists so [io.github.tensaiapp.data.session.SessionRepository.save] can cheaply
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
     * Token counts of [texts] under the loaded model's tokenizer (TEN-161), in order, or `null` when
     * there is nothing to count with: no model loaded, or a generation running (the engine thread is
     * busy with it and the answer would wait out the reply). Counting is the model's own, so the
     * figures are only comparable with [LoadedModelInfo.modelId]'s.
     *
     * A running [warmUp] declines it too (TEN-181), and is left running: the count would queue behind
     * its prefill *while holding [mutex]*, and the unload, load or save meant to cancel the warm-up
     * would wait for the lock, so for the whole warm-up, before they could see it.
     */
    suspend fun countTokens(texts: List<String>): List<Int>? = mutex.withLock {
        if (_state.value !is QueueState.ModelReady || activeWarmUp != null) return@withLock null
        val model = loadedModel ?: return@withLock null
        texts.map { model.tokenize(it) }
    }

    /**
     * Persists the currently loaded model's KV-cache/prompt state to [path] — TEN-34's
     * app-triggered save (e.g. the app going to background), as opposed to [onBeforeModelSwap]'s
     * save-before-swap.
     *
     * Answers with a [SessionSaveOutcome] rather than a token count, because the two ways this can
     * decline are not the same thing (TEN-78): [SessionSaveOutcome.EngineFailed] is the engine
     * saying no, [SessionSaveOutcome.NotAttempted] is this queue never asking it — no model
     * loaded, a load in progress, a generation in flight, or an [engine] that isn't a
     * [LlamaKtEngine]. Both used to be `-1`, and `SessionRepository.save` turned both into
     * `SaveResult.Failed`, i.e. "the engine tried and broke": a plain reply streaming in was
     * reported as a save failure, and `ChatViewModel` cancelled the incoming conversation's
     * restore over it.
     *
     * **Mid-generation is still deliberately declined**, and that is the point of naming it
     * [SessionSaveOutcome.NotAttempted] rather than making it work: [LlamaKtEngineImpl] runs every
     * engine call on one thread, so a dump issued during a decode either queues behind the whole
     * generation while holding [mutex] — blocking [unloadModel], and with it the notification's
     * Unload action, for the length of a reply *and* a multi-hundred-MB write — or interleaves
     * between tokens and freezes half an answer. Neither is worth having when the state one moment
     * later is strictly better. What closes TEN-78's third case is the *other* end: see
     * [saveSessionAfterBackgroundedGeneration], which watches for the generation ending and saves
     * then.
     *
     * No failure throws, then — but a [CancellationException] does (TEN-73), because it is not
     * one. See the guard in the body.
     *
     * Serialized on [mutex] like every other queue operation, so it can never race a concurrent
     * [loadModel]/[unloadModel]/[generate] — unlike [onBeforeModelSwap], which runs already inside
     * [mutex], this is meant for callers *outside* the queue and must take the lock itself.
     *
* **Cancels a running [warmUp] and waits for it to stop** (TEN-88), then takes the lock. The * saves that land here are `onStop` and a reply finishing in the background, the ones that * exist so the state is on disk if the process dies, and a save that waited out a warm-up * (minutes) would leave nothing written for exactly that long. Saving mid-warm-up instead would * queue the dump behind the prefill on the engine's single thread *while holding [mutex]*, the * thing the mid-generation decline above exists to avoid. What is saved is whatever the cache * holds when the warm-up stopped: a valid prefix of the next prompt, `embd` already trimmed to * `n_past`, so the token count written is true and a restore resumes from it. The stop is * prompt, the engine interrupts repeatedly (see `interruptUntilDone`).
     */
    suspend fun saveSession(path: String): SessionSaveOutcome = underLockWithoutWarmUp {
        if (_state.value !is QueueState.ModelReady) return@underLockWithoutWarmUp SessionSaveOutcome.NotAttempted
        // No conversation's state in the cache, only a warmed prefix: written under a conversation's id, it
        // would replace that conversation's session with it.
        if (prefixOnly) return@underLockWithoutWarmUp SessionSaveOutcome.NotAttempted
        val llama = engine as? LlamaKtEngine ?: return@underLockWithoutWarmUp SessionSaveOutcome.NotAttempted
        try {
            SessionSaveOutcome.Wrote(llama.saveSession(path), withoutTools = holdsNoToolPrefix())
        } catch (e: CancellationException) {
            // Ahead of the catch below, which reported a cancelled dump as `-1` — indistinguishable
            // from an engine that tried and failed, and swallowed on the way (TEN-73). This is the
            // layer that matters for it: `SessionRepository.save` takes this method as its
            // `saveOnEngine`, so a failure reported here is what it turns into "Session save
            // reported failure", and its own cancellation guard would never have seen a thing.
            throw e
        } catch (e: Exception) {
            SessionSaveOutcome.EngineFailed
        }
    }

    /**
     * Restores a previously-saved KV-cache/prompt state from [path] onto whatever model is
     * currently loaded — the app-side counterpart of prefill, letting a resumed conversation skip
     * re-feeding a prompt prefix already present in [path].
     *
     * Callers validate that [path] matches the loaded model/context/KV-cache-type before calling
     * this (see `io.github.tensaiapp.data.session.SessionRepository.restore`), and that
     * check necessarily runs *outside* [mutex] — so by the time this takes the lock, the model it
     * was made against may already be gone. [expectedModel] is how a caller says which model it
     * checked: pass the [LoadedModelInfo] that was resident then, and this re-reads it under the
     * lock and declines with [SessionRestoreOutcome.NotAttempted] if anything else is loaded now.
     * Without it, a [loadModel] winning the race left [state] back on [QueueState.ModelReady] with
     * a *different* model, and this handed that model another model's KV state (TEN-81). `null`
     * skips the re-check, for callers with no model identity to assert.
     *
     * The three outcomes are the restore twin of [saveSession]'s, and the distinction matters more
     * here: `SessionRepository.restore` deletes the state file and its row on
     * [SessionRestoreOutcome.EngineRefused], to stop a permanently-broken session occupying the
     * conversation's one slot. A queue that was merely busy is not evidence of anything, and
     * answering it with the same `false` as a refusal is what destroyed valid sessions (TEN-81).
     *
     * A [CancellationException] throws instead of being classified (TEN-73): it is not a verdict
     * on the session either.
     *
     * Serialized on [mutex] like every other queue operation, so it can't race a concurrent
     * [loadModel]/[unloadModel]/[generate] — must be called before [generate].
     *
     * Cancels a running [warmUp] first (TEN-88) and waits for it to wind down: a restore replaces
     * the whole cache, so there is nothing of the warm-up left worth finishing.
     *
     * [withoutTools] is the session's `SessionEntity.withoutTools` (TEN-196): the restored cache holds no tool
     * prefix, so the next [generate] with tools puts it back first, as it does after a turn without tools.
     */
    suspend fun restoreSession(
        path: String,
        expectedModel: LoadedModelInfo? = null,
        withoutTools: Boolean = false,
    ): SessionRestoreOutcome = underLockWithoutWarmUp {
        val current = _state.value
        if (current !is QueueState.ModelReady) return@underLockWithoutWarmUp SessionRestoreOutcome.NotAttempted
        // The revalidation TEN-81 asks for, and it has to be here rather than at the caller: this
        // is the only place "the model is X" and "the engine is about to be handed this file" are
        // true at the same instant, because nothing else can take [mutex] in between.
        if (expectedModel != null && current.info != expectedModel) {
            return@underLockWithoutWarmUp SessionRestoreOutcome.NotAttempted
        }
        val llama = engine as? LlamaKtEngine ?: return@underLockWithoutWarmUp SessionRestoreOutcome.NotAttempted
        prefixOnly = false
        residentPrefix = null
        // Set before the call: a cancellation can land after the native load finished, and then the flag must
        // already describe the restored cache. A wrong `true` only re-prefills the tool prefix.
        restoredWithoutTools = withoutTools
        try {
            if (llama.loadSession(path)) {
                SessionRestoreOutcome.Restored
            } else {
                restoredWithoutTools = false
                SessionRestoreOutcome.EngineRefused
            }
        } catch (e: CancellationException) {
            // As in [saveSession], and worse downstream: `SessionRepository.restore` reads a
            // refusal from its `restoreOnEngine` as "this session is unusable" and deletes the
            // state file and its row to stop it being retried forever. A cancelled caller would
            // have taken a good session with it (TEN-73).
            throw e
        } catch (e: Exception) {
            restoredWithoutTools = false
            SessionRestoreOutcome.EngineRefused
        }
    }

    /**
     * Prefills the loaded model's KV cache with the start of the prompt the *next* turn of
     * [messages] will send, without generating anything (TEN-88), see
     * [io.github.tensaiapp.engine.WarmUpPrompt] for what that is and why a reply is worth
     * it: after a long reply the cache and the next prompt disagree from the reply's first token
     * on, and the whole reply would otherwise be re-decoded before the next turn's first token.
     * Meant to be called right after a reply ends, while the user reads, for the conversation whose
     * cache is resident.
     *
     * **Background work that yields to everything.** A warm-up can run for minutes and holds
     * neither [mutex] nor a [QueueState] of its own, so it shows up nowhere: the notification and
     * the chat keep saying "ready", and sending is not disabled. Instead, every operation that
     * wants the engine cancels it (see [activeWarmUp]), and one that cannot start a warm-up never
     * starts one, it is skipped unless [state] is [QueueState.ModelReady] on [expectedModel] at
     * the moment it takes the lock, which is also how it loses a race against a [generate] that
     * got there first. Only one runs at a time. Whatever it had decoded when it was stopped stays
     * in the cache (see [WarmUpOutcome.Interrupted]), which is what makes cancelling cheap: the
     * generation that cancelled it reuses that prefix on its own.
     *
     * Cancelling the *caller* throws as usual. The queue stopping it does not: it ends in
     * [WarmUpOutcome.Interrupted] and the caller is left running, because "something more important
     * came along" is the normal ending of this call, not an error.
     *
     * @param expectedModel the model the reply that just ended was generated on (TEN-81's check,
     *   for the same reason: the caller decided to warm up outside the lock). A different or absent
     *   one means the cache is not the conversation's any more.
     */
    suspend fun warmUp(
        messages: List<ChatMessage>,
        enableThinking: Boolean,
        expectedModel: LoadedModelInfo,
    ): WarmUpOutcome = runWarmUp(expectedModel) { it.warmUp(messages, enableThinking) }

    /**
     * [warmUp]'s twin for a model that has just been loaded (TEN-205): builds or restores the tool
     * prefix of [request] (see [LoadedModel.warmToolPrefix]) so the first message does not pay for it.
     * Same rules, it yields to everything and is skipped unless the queue is ready on [expectedModel].
     *
     * What it leaves in the cache is only that prefix, not a conversation's state, and
     * [holdsOnlyWarmedPrefix] says so until something else takes the cache over: a conversation with a
     * saved session must still restore over it (TEN-34's gate reads "the cache is empty").
     *
     * A warm-up stopped before it wrote the prefix's file is finished by the [generate] that stopped it.
     *
     * A conversation in the cache is left alone, unless the caller has saved it and says so with
     * [replaceSaved]: the token count the cache held before that save. The prefix then takes its place only
     * if the cache still holds that many tokens, nothing generated since the save, and [onReplace] runs
     * first, before the engine touches the cache.
     */
    suspend fun warmToolPrefix(
        request: DecodeRequest,
        expectedModel: LoadedModelInfo,
        replaceSaved: Int? = null,
        onReplace: () -> Unit = {},
    ): WarmUpOutcome =
        runWarmUp(expectedModel) { model ->
            // No prefix to put there, and the engine leaves the cache as it is: so must the state below, or the next
            // [generate] would read a prefix it already has as missing and restore it again (TEN-195).
            if (request.tools.isEmpty()) return@runWarmUp false
            // Before the engine call: a cancel lands inside it, and the partial prefix it keeps counts too.
            // Set over an empty cache too, even if the engine then skips: the flag means "empty or prefix only".
            // Not when someone's conversation is in the cache: the engine leaves that alone, and so must the flag.
            val resident = currentKvTokenCount()
            val replaceConversation = !prefixOnly && replaceSaved != null && resident == replaceSaved
            if (replaceConversation) onReplace()
            val replace = prefixOnly || replaceConversation
            if (replace || resident == 0) {
                prefixOnly = true
                residentPrefix = request.prefixKey()
            }
            prefixWritten = false
            model.warmToolPrefix(request, replaceResident = replace).also { prefixWritten = it }
        }

    /**
     * Whether the last request that filled the cache had the tool prefix [request] has (the system messages,
     * the tools and the thinking flag), so the first message of [request]'s conversation
     * reuses it as it is. `false` when unknown: after a restore, a load, or with nothing in the cache.
     */
    fun holdsPrefixOf(request: DecodeRequest): Boolean = residentPrefix == request.prefixKey()

    /**
     * Whether the cache holds no tool prefix at all: the last request that filled it had no tools, or the session
     * [restoreSession] put there was saved so (TEN-196). What a save records as `SessionEntity.withoutTools`, and
     * what has [generate] put the prefix back before a request with tools. Read under [mutex] (from [generate],
     * [saveSession] or the swap hook): the two fields it reads change together only under the lock.
     */
    fun holdsNoToolPrefix(): Boolean = residentPrefix?.tools?.isEmpty() ?: restoredWithoutTools

    /**
     * Whether the cache is empty or holds at most what [warmToolPrefix] put there, so a restore of a conversation's
     * session may replace it without losing anything that is not on disk. Cleared by a generation, a
     * restore, a load and an unload. A plain read, like [currentKvTokenCount].
     */
    fun holdsOnlyWarmedPrefix(): Boolean = prefixOnly

    /** How many generations have started, for [releaseBenchCache]. */
    fun generationsStarted(): Long = generationsStarted

    /**
     * After a bench (TEN-208): the cache holds filler nobody owns, the open conversation's session already saved. Marked
     * like a warmed prefix ([holdsOnlyWarmedPrefix]), so the conversation opened next restores its session over it, a save
     * never writes it under anyone's id, and a request with tools puts its prefix back first. There is no engine call to
     * empty the cache; this is what the readers of the flag need.
     *
     * [afterGeneration] is [generationsStarted] read right after the bench's last decode: a generation started since (a
     * message sent meanwhile) owns the cache now, and nothing changes. `false` then, or unless the model is idle.
     */
    suspend fun releaseBenchCache(afterGeneration: Long): Boolean = mutex.withLock {
        if (_state.value !is QueueState.ModelReady || generationsStarted != afterGeneration) return@withLock false
        prefixOnly = true
        prefixWritten = false
        residentPrefix = null
        restoredWithoutTools = false
        true
    }

    private suspend fun runWarmUp(
        expectedModel: LoadedModelInfo,
        block: suspend (LoadedModel) -> Boolean,
    ): WarmUpOutcome = coroutineScope {
        // Its own job, not the caller's: cancelling this is the queue's call and must not end
        // whatever the caller is running.
        val work = async {
            val model = mutex.withLock {
                val current = _state.value
                if (current !is QueueState.ModelReady || current.info != expectedModel || activeWarmUp != null) {
                    return@withLock null
                }
                activeWarmUp = currentCoroutineContext().job
                loadedModel
            } ?: return@async WarmUpOutcome.Skipped
            try {
                if (block(model)) WarmUpOutcome.Warmed else WarmUpOutcome.Skipped
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WarmUpOutcome.Failed
            } finally {
                // Inside the job being joined, and uncancellable: see [activeWarmUp].
                withContext(NonCancellable) { mutex.withLock { activeWarmUp = null } }
            }
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            // Either the caller is being cancelled (rethrown), or the queue cancelled `work` to
            // make way for something else.
            coroutineContext.ensureActive()
            WarmUpOutcome.Interrupted
        }
    }

    /**
     * Stops the running [warmUp], if any, and waits for it to stop. For a caller that knows the engine
     * is about to be wanted before the queue finds out: `ChatViewModel` calls it at the start of a send,
     * before the switch sync saves the outgoing conversation. A no-op when nothing is warming up.
     *
     * Waits since TEN-181: [countTokens] declines while [activeWarmUp] is set, and a send counts the
     * prompt's fixed parts right after this. The wait is bounded by the engine's interrupt, not by
     * the warm-up: the prefill checks it between 128-token chunks (and between graph nodes on the CPU
     * backend), and [generate] would wait the same time anyway. The one longer stop is a
     * [warmToolPrefix] inside its file read, which cannot be interrupted.
     */
    suspend fun cancelWarmUp() {
        activeWarmUp?.let {
            it.cancel()
            it.join()
        }
    }

    /**
     * Whether a [warmUp] or [warmToolPrefix] is running, for a caller that would rather wait for it than
     * cancel it (the debug decode bench, TEN-190). A plain read, like [currentKvTokenCount].
     */
    fun isWarmingUp(): Boolean = activeWarmUp != null

    /**
     * Runs [block] under [mutex] once no [warmUp] is running, cancelling one first and waiting for it
     * to stop. The lock is released while waiting: the warm-up's own cleanup takes it,
     * and joining under it would be a deadlock. Looping rather than "cancel, then lock" because a
     * warm-up can register itself between the join and the lock; registration and this check are
     * both under [mutex], which is what settles it.
     */
    private suspend fun <T> underLockWithoutWarmUp(block: suspend () -> T): T {
        while (true) {
            var pending: Job? = null
            val ran = mutex.withLock {
                pending = activeWarmUp
                if (pending == null) Ran(block()) else null
            }
            if (ran != null) return ran.value
            pending?.cancel()
            pending?.join()
        }
    }

    /** Distinguishes "[underLockWithoutWarmUp]'s block ran and returned `null`" from "it did not run". */
    private class Ran<T>(val value: T)

    /**
     * The parts of a [DecodeRequest] its tool prefix is rendered from (see `LlamaKtEngineImpl.restoreOrBuildToolPrefix`).
     * Not the tool choice: llama.cpp never hands it to the template, it only shapes the grammar and the parser,
     * so the forced last step of an agent turn renders the same prefix.
     */
    private data class PrefixKey(val system: List<ChatMessage>, val tools: List<ToolDefinition>, val enableThinking: Boolean)

    private fun DecodeRequest.prefixKey() =
        PrefixKey(messages.takeWhile { it.role == MessageRole.System }, tools, sampling.enableThinking)
}
