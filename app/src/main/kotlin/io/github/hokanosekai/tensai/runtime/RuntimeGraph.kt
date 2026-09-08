package io.github.hokanosekai.tensai.runtime

import android.content.Context
import io.github.hokanosekai.tensai.compat.DeviceProfiler
import io.github.hokanosekai.tensai.data.BackendPreference
import io.github.hokanosekai.tensai.data.BackendRepository
import io.github.hokanosekai.tensai.data.LanguageRepository
import io.github.hokanosekai.tensai.data.NotificationPermissionRepository
import io.github.hokanosekai.tensai.data.OnboardingRepository
import io.github.hokanosekai.tensai.data.ThemeRepository
import io.github.hokanosekai.tensai.data.chat.ChatRepository
import io.github.hokanosekai.tensai.data.models.ModelRepository
import io.github.hokanosekai.tensai.data.models.TensaiDb
import io.github.hokanosekai.tensai.data.session.SessionRepository
import io.github.hokanosekai.tensai.data.session.SessionSaveOutcome
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
        // exceptions propagate up through `loadModel`/`unloadModel` and would fail the call for
        // the caller — a session save failing must never block loading the next model.
        //
        // Since TEN-74 a throw no longer keeps the old model resident: the release happens either
        // way and only the *call* fails, so the cost of letting one escape is a load the user has
        // to retry, not a wedged queue. The `runCatching` stays anyway, for that reason and
        // because it is the difference between a lost session save and a visible failure.
        //
        // Note it does not cover the whole body: `ActiveSessionTracker.current.value` and
        // `clear()` run outside it. Neither throws today.
        inferenceQueue.onBeforeModelSwap = {
            val snapshot = ActiveSessionTracker.current.value
            ActiveSessionTracker.clear()
            if (snapshot != null) {
                val (conversationId, key) = snapshot
                runCatching {
                    // The raw engine reports a token count and throws on failure, where
                    // `SessionRepository` now speaks in `SessionSaveOutcome` (TEN-78) — the throw is
                    // caught there and lands on `SaveResult.Failed`, exactly as before. There is no
                    // `NotAttempted` to express from here: this hook runs under the queue's mutex
                    // with the model still resident, so the engine is always asked.
                    sessionRepository.save(conversationId, key) { path ->
                        SessionSaveOutcome.Wrote(llamaEngine.saveSession(path))
                    }
                }
            }
        }

        // TEN-78's third save trigger — see [saveSessionAfterBackgroundedGeneration] for why the
        // other two structurally cannot cover a reply that finishes after the user has left. Same
        // fire-and-forget shape as the two below, except this one never returns: it collects
        // [InferenceQueue.state] for the life of the process.
        applicationScope.launch {
            saveSessionAfterBackgroundedGeneration(
                queueState = inferenceQueue.state,
                isForeground = { AppForegroundTracker.isForeground.value },
                activeSession = { ActiveSessionTracker.current.value },
                save = { conversationId, key ->
                    // Swallowed for the same reason the swap hook's is, plus one specific to a
                    // long-lived collector: an escaping exception would end the collection and this
                    // trigger would silently stop existing for the rest of the process.
                    runCatching {
                        sessionRepository.save(conversationId, key, saveOnEngine = inferenceQueue::saveSession)
                    }
                },
            )
        }

        // This app keeps at most two persisted sessions, table-wide (LRU-2 as of TEN-62; TEN-63
        // had it at one) — one-time cleanup for whatever a device already had on disk under an
        // older policy's caps. Fire-and-forget on [applicationScope] rather than awaited here:
        // nothing in `init` depends on it finishing, and `TensaiApp.onCreate` must not block on
        // file I/O.
        applicationScope.launch { runCatching { sessionRepository.pruneToStoragePolicy() } }

        // One-time per model *per revision of the native KV-shape formula* (TEN-72): re-derives
        // the shape of anything whose stored numbers came from a different formula than the one
        // now loaded, so an existing install gets the corrected `n_ctx` without a re-import. Same
        // fire-and-forget shape as the prune above — it reads GGUF headers off disk, nothing in
        // `init` waits on it, and a model whose file has gone missing simply keeps the estimate it
        // already had (see [ModelRepository.backfillKvCacheShape]).
        applicationScope.launch { runCatching { modelRepository.backfillKvCacheShape() } }
    }

    /**
     * Backs both [inferenceQueue] and [ModelRepository]'s GGUF metadata reads (see
     * `LlamaKtEngine.readMetadata`'s KDoc — it's a header-only read of the *file*, not of whatever
     * this instance currently has loaded, so sharing it here is safe).
     *
     * Sharing it used to mean a metadata read issued while [inferenceQueue] was mid-load or
     * mid-generate waited behind that work on the engine's single-thread dispatcher — written down
     * here as acceptable for V1 on the grounds that nobody imports a model mid-reply. That was
     * only ever a guess about the user, and the wait it excused was unbounded: a long generation
     * held an import screen on a spinner for as long as it ran. TEN-84 takes the read off that
     * dispatcher entirely ([LlamaKtEngineImpl.readMetadata] — it touches no engine state, so the
     * dispatcher was protecting nothing), and the two consumers no longer contend at all.
     *
     * Never closed, deliberately (TEN-66): this is a process-lifetime singleton, so there is no
     * point at which closing it would be correct — the process exiting is what reclaims its
     * thread. That is also why closing it is *not* [inferenceQueue]'s business despite the queue
     * being its main consumer: the two other consumers wired up here, [modelRepository]'s
     * metadata reads and the save hook in `init`, hold the same instance. See
     * [LlamaKtEngineImpl.close] for the callers that do have to close one — the instrumented
     * tests, which build an engine per test.
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

    /**
     * User preferences, each backed by its own DataStore file (see [ThemeRepository]).
     * They live here rather than being constructed per-screen so every reader — `MainActivity`'s
     * theme, [io.github.hokanosekai.tensai.ui.screens.loading.LoadModelViewModel]'s backend, the
     * onboarding's writes of both — goes through one instance.
     */
    val themeRepository: ThemeRepository by lazy { ThemeRepository(appContext) }

    /** Backend the user picked in onboarding/settings — see [BackendPreference.resolve]. */
    val backendRepository: BackendRepository by lazy { BackendRepository(appContext) }

    /** Language the app renders in, independently of the system's — see [LanguageRepository]. */
    val languageRepository: LanguageRepository by lazy { LanguageRepository(appContext) }

    /**
     * The installed package's own `versionName`, for Settings > About (TEN-38) — read from the
     * platform rather than written out in Kotlin, so it is the version that is actually installed
     * and it cannot drift from `build.gradle.kts`. Empty if the package manager won't answer,
     * which it always does for one's own package.
     */
    val appVersionName: String by lazy {
        runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    /** First-run bookkeeping — see [OnboardingRepository] and `OnboardingGate`. */
    val onboardingRepository: OnboardingRepository by lazy { OnboardingRepository(appContext) }

    /**
     * Whether the `POST_NOTIFICATIONS` dialog has already been spent (TEN-75) — see
     * [NotificationPermissionRepository] for why that has to outlive the process, and
     * [NotificationPermissionGate] for the rule it feeds.
     */
    val notificationPermissionRepository: NotificationPermissionRepository by lazy {
        NotificationPermissionRepository(appContext)
    }

    private val database: TensaiDb by lazy { TensaiDb.build(appContext) }

    val modelRepository: ModelRepository by lazy {
        ModelRepository(
            context = appContext,
            modelDao = database.modelDao(),
            readMetadata = llamaEngine::readMetadata,
            kvShapeVersion = llamaEngine::kvShapeVersion,
            currentLoadedModelName = { inferenceQueue.residentModelName },
        )
    }

    /** Chat history — Room-backed, see [ChatRepository]. */
    val chatRepository: ChatRepository by lazy { ChatRepository(database.chatDao()) }

    /**
     * Persisted KV-cache/prompt state per conversation (TEN-34) — see [SessionRepository].
     *
     * Both engine entry points are passed per call rather than wired here, and since TEN-81 that
     * includes the restore side: only the caller knows which loaded model it validated the session
     * against, and the queue has to be told so it can re-assert it under its own mutex. `ChatViewModel`
     * is the only caller of [SessionRepository.restore] and does exactly that.
     */
    val sessionRepository: SessionRepository by lazy {
        SessionRepository(
            dao = database.sessionDao(),
            storageRoot = appContext.filesDir,
            currentTokenCount = { inferenceQueue.currentKvTokenCount() },
        )
    }
}
