package io.github.hokanosekai.tensai.runtime

import android.content.Context
import io.github.hokanosekai.tensai.compat.DeviceProfiler
import io.github.hokanosekai.tensai.data.chat.ChatRepository
import io.github.hokanosekai.tensai.data.models.ModelRepository
import io.github.hokanosekai.tensai.data.models.TensaiDb
import io.github.hokanosekai.tensai.data.session.SessionRepository
import io.github.hokanosekai.tensai.engine.LlamaKtEngineImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Pragmatic, framework-free composition root for V1.
 *
 * A single lazily-constructed [InferenceQueue] shared by every client in
 * the app (Chat UI in V1, the API server in V2 — see [InferenceQueue]'s
 * KDoc for why that sharing is safe). Replace this with a real DI solution
 * (Hilt/Koin) if the object graph grows beyond this one dependency.
 */
object RuntimeGraph {

    private lateinit var appContext: Context

    /** Must be called once, before any other member is touched — see `TensaiApp.onCreate`. */
    fun init(context: Context) {
        appContext = context.applicationContext

        // TEN-34's save-before-swap: [ActiveSessionTracker.current] is whatever conversation last
        // reported itself active against the model about to be replaced/unloaded. Cleared *before*
        // saving, not after: this hook runs once per swap, so clearing first means a load that
        // then completes without any `ChatViewModel` reporting against the *new* model (nobody
        // opened a conversation yet) leaves the tracker empty rather than still pointing at the
        // just-freed model's key — see `ActiveSessionTracker`'s KDoc for why that matters (a stale
        // key would otherwise mislabel whatever's actually in the new model's KV cache).
        //
        // Calls `llamaEngine.saveSession` directly rather than going through
        // `InferenceQueue.saveSession` — this hook already runs *inside* the queue's own mutex (see
        // `InferenceQueue.onBeforeModelSwap`'s KDoc), so routing through the queue's own
        // mutex-protected entry point would deadlock. Any failure is swallowed: this hook's
        // exceptions propagate up through `loadModel`/`unloadModel` and would abort the swap
        // (leaving the *old* model still resident) — a session save failing must never block
        // loading the next model.
        inferenceQueue.onBeforeModelSwap = {
            val snapshot = ActiveSessionTracker.current.value
            ActiveSessionTracker.clear()
            if (snapshot != null) {
                val (conversationId, key) = snapshot
                runCatching { sessionRepository.save(conversationId, key, saveOnEngine = llamaEngine::saveSession) }
            }
        }

        // TEN-63: this app now keeps at most one persisted session, table-wide — one-time cleanup
        // for whatever a device already had on disk under TEN-34's old per-conversation caps.
        // Fire-and-forget on [applicationScope] rather than awaited here: nothing in `init` depends
        // on it finishing, and `TensaiApp.onCreate` must not block on file I/O.
        applicationScope.launch { runCatching { sessionRepository.pruneToSingleSession() } }
    }

    /**
     * Backs both [inferenceQueue] and [ModelRepository]'s GGUF metadata reads (see
     * `LlamaKtEngine.readMetadata`'s KDoc — it's a header-only read of the *file*, not of whatever
     * this instance currently has loaded, so sharing it here is safe). It does mean a metadata
     * read made while [inferenceQueue] is mid-load or mid-generate is serialized behind that work
     * on the engine's single-thread dispatcher rather than running immediately — acceptable for
     * V1's import flow (no generation is expected to be active while importing a new model), but
     * worth revisiting if that stops holding.
     */
    private val llamaEngine: LlamaKtEngineImpl by lazy { LlamaKtEngineImpl() }

    val inferenceQueue: InferenceQueue by lazy { InferenceQueue(llamaEngine) }

    /**
     * Process-wide scope for observers that must outlive any single
     * `Activity` — currently `TensaiApp`'s start/stop observer for
     * [InferenceService] and that service's own notification-rendering
     * observer of [inferenceQueue]'s state. [SupervisorJob] so a failure in
     * one observer can't cancel the others.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** See [DeviceProfiler]'s KDoc for what's cached vs. re-read on each [DeviceProfiler.profile] call. */
    val deviceProfiler: DeviceProfiler by lazy { DeviceProfiler(appContext) }

    private val database: TensaiDb by lazy { TensaiDb.build(appContext) }

    val modelRepository: ModelRepository by lazy {
        ModelRepository(
            context = appContext,
            modelDao = database.modelDao(),
            readMetadata = llamaEngine::readMetadata,
            currentLoadedModelName = {
                (inferenceQueue.state.value as? QueueState.ModelReady)?.info?.name
            },
        )
    }

    /** Chat history — Room-backed, see [ChatRepository]. */
    val chatRepository: ChatRepository by lazy { ChatRepository(database.chatDao()) }

    /**
     * Persisted KV-cache/prompt state per conversation (TEN-34) — see [SessionRepository]. Its
     * [SessionRepository.restore] is wired to [InferenceQueue.restoreSession] rather than the raw
     * engine, since restoring always happens from outside any lock this queue holds (unlike the
     * save-before-swap path wired in [init], which runs already inside the queue's mutex).
     */
    val sessionRepository: SessionRepository by lazy {
        SessionRepository(
            dao = database.sessionDao(),
            storageRoot = appContext.filesDir,
            restoreOnEngine = { path -> inferenceQueue.restoreSession(path) },
            currentTokenCount = { inferenceQueue.currentKvTokenCount() },
        )
    }
}
