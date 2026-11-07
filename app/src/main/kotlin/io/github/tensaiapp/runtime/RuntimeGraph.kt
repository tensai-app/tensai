package io.github.tensaiapp.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import io.github.tensaiapp.BuildConfig
import io.github.tensaiapp.agent.AgentRuntime
import io.github.tensaiapp.agent.AndroidClipboardAccess
import io.github.tensaiapp.agent.AndroidCalendarSource
import io.github.tensaiapp.agent.AndroidContactsSource
import io.github.tensaiapp.agent.AndroidLocationSource
import io.github.tensaiapp.agent.AndroidPhoneActions
import io.github.tensaiapp.agent.AndroidSmsSource
import io.github.tensaiapp.agent.CreateEventTool
import io.github.tensaiapp.agent.DraftEmailTool
import io.github.tensaiapp.agent.DraftSmsTool
import io.github.tensaiapp.agent.FindContactTool
import io.github.tensaiapp.agent.LocationTool
import io.github.tensaiapp.agent.ReadCalendarTool
import io.github.tensaiapp.agent.ReadNotificationsTool
import io.github.tensaiapp.agent.ReadSmsTool
import io.github.tensaiapp.agent.ToolTier
import io.github.tensaiapp.agent.NotificationBuffer
import io.github.tensaiapp.agent.NotificationBufferFile
import io.github.tensaiapp.agent.AndroidNotificationAccess
import io.github.tensaiapp.agent.PermissionGate
import io.github.tensaiapp.agent.MediaControlTool
import io.github.tensaiapp.agent.OpenAppTool
import io.github.tensaiapp.agent.OpenUrlTool
import io.github.tensaiapp.agent.SetAlarmTool
import io.github.tensaiapp.agent.SetTimerTool
import io.github.tensaiapp.agent.Tool
import io.github.tensaiapp.agent.AndroidDeviceInfoSource
import io.github.tensaiapp.agent.AndroidTorchControl
import io.github.tensaiapp.agent.AskUserGate
import io.github.tensaiapp.agent.AskUserTool
import io.github.tensaiapp.agent.ClipboardTool
import io.github.tensaiapp.agent.ConfirmationGate
import io.github.tensaiapp.agent.DeviceInfoTool
import io.github.tensaiapp.agent.MemoryTool
import io.github.tensaiapp.agent.SearchChatsTool
import io.github.tensaiapp.agent.TorchTool
import io.github.tensaiapp.agent.OkHttpFetcher
import io.github.tensaiapp.agent.SearchProviders
import io.github.tensaiapp.agent.SearchSelection
import io.github.tensaiapp.agent.SearchTester
import io.github.tensaiapp.agent.WeatherTool
import io.github.tensaiapp.agent.WebFetchTool
import io.github.tensaiapp.agent.WebSearchTool
import io.github.tensaiapp.agent.WikipediaTool
import io.github.tensaiapp.agent.UiConfirmationGate
import io.github.tensaiapp.agent.ToolRegistry
import io.github.tensaiapp.agent.ToolPolicy
import io.github.tensaiapp.compat.DeviceProfiler
import io.github.tensaiapp.data.BackendPreference
import io.github.tensaiapp.data.BackendRepository
import io.github.tensaiapp.data.ChangelogRepository
import io.github.tensaiapp.data.ContextRepository
import io.github.tensaiapp.data.LanguageRepository
import io.github.tensaiapp.data.InstructionsSettings
import io.github.tensaiapp.data.MemorySettings
import io.github.tensaiapp.data.NotificationPermissionRepository
import io.github.tensaiapp.data.OnboardingRepository
import io.github.tensaiapp.data.SearchKeyStores
import io.github.tensaiapp.data.SearchProviderChoice
import io.github.tensaiapp.data.ThemeRepository
import io.github.tensaiapp.data.ToolSettingsRepository
import io.github.tensaiapp.data.chat.ChatRepository
import io.github.tensaiapp.data.memory.MemoryDao
import io.github.tensaiapp.data.memory.MemoryPrompt
import io.github.tensaiapp.data.models.ModelRepository
import io.github.tensaiapp.data.models.TensaiDb
import io.github.tensaiapp.data.session.SessionRepository
import io.github.tensaiapp.data.session.SessionSaveOutcome
import io.github.tensaiapp.engine.LlamaKtEngineImpl
import io.github.tensaiapp.download.DownloadDrainer
import io.github.tensaiapp.download.DownloadEngine
import io.github.tensaiapp.download.DrainerRegistry
import io.github.tensaiapp.download.DownloadManager
import io.github.tensaiapp.download.DownloadPreferences
import io.github.tensaiapp.download.DownloadRunner
import io.github.tensaiapp.download.WorkManagerScheduler
import io.github.tensaiapp.download.toOrigin
import io.github.tensaiapp.hf.HfClient
import io.github.tensaiapp.hf.TokenStore
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import io.github.tensaiapp.websearch.HttpFetcher
import io.github.tensaiapp.websearch.PageReader
import java.util.Locale

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

    /** False until `TensaiApp.onCreate` ran [init]; a WorkManager worker can start before that. */
    val isInitialized: Boolean get() = ::appContext.isInitialized

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
                        SessionSaveOutcome.Wrote(llamaEngine.saveSession(path), withoutTools = inferenceQueue.holdsNoToolPrefix())
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
                save = { active ->
                    // Swallowed for the same reason the swap hook's is, plus one specific to a
                    // long-lived collector: an escaping exception would end the collection and this
                    // trigger would silently stop existing for the rest of the process.
                    runCatching {
                        sessionRepository.save(
                            active.conversationId,
                            active.key,
                            ownsCache = { ActiveSessionTracker.current.value == active },
                            saveOnEngine = inferenceQueue::saveSession,
                        )
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

        // TEN-176: model files no row references (an import or a registration the process died in),
        // then TEN-141: stuck rows, orphan parts, and a drainer if anything is waiting. The sweep
        // skips files an import or adopt has claimed, so it cannot race one started meanwhile.
        applicationScope.launch {
            runCatching { modelRepository.sweepOrphanFiles() }
            runCatching { downloadManager.reconcile() }
        }

        // TEN-144: decrypt the Hugging Face token once, off Main, so the HTTP clients' provider is a cache hit.
        applicationScope.launch(Dispatchers.IO) { runCatching { tokenStore.get() } }

        // TEN-174: read the granted tiers once, so the first turn finds them in the snapshot.
        applicationScope.launch(Dispatchers.IO) { runCatching { toolSettings.grants.first() } }
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
    private val llamaEngine: LlamaKtEngineImpl by lazy {
        LlamaKtEngineImpl(
            prefixCacheDir = File(appContext.cacheDir, "prefix-kv"),
            log = { message -> Log.i("tensai.prefix", message) },
        )
    }

    val inferenceQueue: InferenceQueue by lazy { InferenceQueue(llamaEngine, modelFileExists = { File(it).exists() }) }

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
     * theme, [io.github.tensaiapp.ui.screens.loading.LoadModelViewModel]'s backend, the
     * onboarding's writes of both — goes through one instance.
     */
    val themeRepository: ThemeRepository by lazy { ThemeRepository(appContext) }

    /** Backend the user picked in onboarding/settings — see [BackendPreference.resolve]. */
    val backendRepository: BackendRepository by lazy { BackendRepository(appContext) }

    /** Global context window setting (TEN-150), see [ContextSetting]. */
    val contextRepository: ContextRepository by lazy { ContextRepository(appContext) }

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

    /** The last version whose release notes were opened, see [ChangelogRepository]. */
    val changelogRepository: ChangelogRepository by lazy { ChangelogRepository(appContext) }

    /**
     * Whether the `POST_NOTIFICATIONS` dialog has already been spent (TEN-75) — see
     * [NotificationPermissionRepository] for why that has to outlive the process, and
     * [NotificationPermissionGate] for the rule it feeds.
     */
    val notificationPermissionRepository: NotificationPermissionRepository by lazy {
        NotificationPermissionRepository(appContext)
    }

    /**
     * Hugging Face Hub client (TEN-136), the app's only network access: huggingface.co and nothing
     * else, see the privacy contract in the `hf` package. Its token provider reads [tokenStore]'s cache.
     */
    val hfClient: HfClient by lazy { HfClient(tokenProvider = tokenStore::peek) }

    /** The Hugging Face token, encrypted with the Android Keystore (TEN-144). */
    val tokenStore: TokenStore by lazy { TokenStore(appContext) }

    private val database: TensaiDb by lazy { TensaiDb.build(appContext) }

    val modelRepository: ModelRepository by lazy {
        ModelRepository(
            context = appContext,
            modelDao = database.modelDao(),
            readMetadata = llamaEngine::readMetadata,
            kvShapeVersion = llamaEngine::kvShapeVersion,
            unlessInUse = inferenceQueue::unlessInUse,
        )
    }

    /** Whether a download may use mobile data without asking, see [DownloadPreferences]. */
    val downloadPreferences: DownloadPreferences by lazy { DownloadPreferences(appContext) }

    private val downloadDao by lazy { database.downloadDao() }

    /**
     * Streams model files for the download queue (TEN-141). Not [HfClient]'s OkHttp: that one has a
     * 30 s call timeout, which no multi-GB download survives. Same host rules, same token as [hfClient].
     */
    private val downloadHttp by lazy {
        HfClient.defaultHttp().newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    /** True while a drainer loop is active in this process: shared by the scheduler, manager and drainer. */
    private val drainerRegistry = DrainerRegistry()

    private val connectivity by lazy { appContext.getSystemService(ConnectivityManager::class.java) }

    /** Keeps the one `tensai-model-downloads` drainer alive, see [WorkManagerScheduler]. */
    val downloadScheduler: WorkManagerScheduler by lazy { WorkManagerScheduler(appContext, drainerRegistry) }

    /** Moves the bytes of one download: resume, one-pass sha256, registration as a model. */
    val downloadRunner: DownloadRunner by lazy {
        DownloadRunner(
            dao = downloadDao,
            engine = DownloadEngine(downloadHttp, freeBytes = modelRepository::freeBytes, tokenProvider = tokenStore::peek),
            urlFor = { repo, sha, file -> hfClient.fileUrl(repo, file, sha ?: "main") },
            partFor = modelRepository::downloadPartFile,
            finalize = { part, row -> modelRepository.registerDownload(part, row.fileName, row.toOrigin(System.currentTimeMillis())) },
            unregister = { registered -> runCatching { modelRepository.unregisterDownload(registered) } },
        )
    }

    /** Runs the queue, see [DownloadDrainer]. Used by `DownloadWorker`. */
    val downloadDrainer: DownloadDrainer by lazy {
        DownloadDrainer(
            dao = downloadDao,
            runOne = { id, progress -> downloadRunner.run(id, progress) },
            isMetered = { connectivity.isActiveNetworkMetered },
            registry = drainerRegistry,
            log = { message, error -> Log.e("tensai.download", message, error) },
        )
    }

    /** The model download queue, see [DownloadManager]. */
    val downloadManager: DownloadManager by lazy {
        DownloadManager(
            dao = downloadDao,
            scheduler = downloadScheduler,
            urlFor = { repo, sha, file -> hfClient.fileUrl(repo, file, sha ?: "main") },
            partFor = modelRepository::downloadPartFile,
            listParts = modelRepository::downloadPartFiles,
            freeBytes = modelRepository::freeBytes,
            isMetered = { connectivity.isActiveNetworkMetered },
            allowMobileData = downloadPreferences::isMobileDataAllowed,
            findInstalled = { name, size ->
                modelRepository.models.first()
                    .firstOrNull { it.fileName == name && it.sizeBytes == size && File(it.filePath).exists() }?.id
            },
            registry = drainerRegistry,
        )
    }

    /** What the `memory` tool remembers (TEN-176), also read by Settings > Memory. */
    val memoryDao: MemoryDao by lazy { database.memoryDao() }

    /** "Use memory in chats" (TEN-189). */
    val memorySettings: MemorySettings by lazy { MemorySettings(appContext) }

    /** The saved facts as each conversation's prompt block (TEN-189), see [MemoryPrompt]. */
    val memoryPrompt: MemoryPrompt by lazy { MemoryPrompt(memoryDao, enabled = memorySettings::useInChatsNow) }

    /** "Your instructions", added to every chat's system message, see [InstructionsSettings]. */
    val instructionsSettings: InstructionsSettings by lazy { InstructionsSettings(appContext) }

    /** Token counts of the context sheet's fixed parts, shared by every chat screen (TEN-190). */
    val partTokenCache: PartTokenCache by lazy { PartTokenCache() }

    /** Chat history — Room-backed, see [ChatRepository]. */
    val chatRepository: ChatRepository by lazy { ChatRepository(database.chatDao()) }

    /** Which tiers the model may use and the search provider (TEN-174), the [io.github.tensaiapp.agent.TierGrants] of [toolRegistry]. */
    val toolSettings: ToolSettingsRepository by lazy { ToolSettingsRepository(appContext) }

    /** The API keys of the search providers that need one, encrypted (TEN-174). */
    val searchKeys: SearchKeyStores by lazy { SearchKeyStores(appContext) }

    /** The chat turns running, by conversation: a chat screen reopened mid-turn stops it from here, see [ChatTurns]. */
    val chatTurns: ChatTurns by lazy { ChatTurns() }

    /** Asks the user before a call that sends or deletes (TEN-174): the chat draws its card, see [UiConfirmationGate]. */
    val confirmationGate: UiConfirmationGate by lazy { UiConfirmationGate() }

    /** Where `ask_user` waits for the user (TEN-187): the chat swaps its input bar for the question, see [AskUserGate]. */
    val askUserGate: AskUserGate by lazy { AskUserGate() }

    /** Where a tool waits for an Android permission dialog (TEN-186), served by [io.github.tensaiapp.MainActivity]. */
    val permissionGate: PermissionGate by lazy { PermissionGate() }

    private fun phoneActionTools(): List<Tool> {
        val phone = AndroidPhoneActions(appContext) { AppForegroundTracker.isForeground.value }
        val contacts = AndroidContactsSource(appContext, permissionGate)
        return listOf(
            SetTimerTool(phone), SetAlarmTool(phone), CreateEventTool(phone), DraftSmsTool(phone, contacts),
            DraftEmailTool(phone, contacts), OpenAppTool(phone), OpenUrlTool(phone), MediaControlTool(phone),
            FindContactTool(contacts) { phone.inForeground },
            ReadCalendarTool(AndroidCalendarSource(appContext, permissionGate), inForeground = { phone.inForeground }),
            LocationTool(AndroidLocationSource(appContext, permissionGate), inForeground = { phone.inForeground }),
            ReadSmsTool(AndroidSmsSource(appContext, permissionGate), contacts, inForeground = { phone.inForeground }),
        )
    }

    /** `tensai/<version> (+<repo>)`: what Wikimedia and Open-Meteo ask a client to say about itself. */
    private val descriptiveUserAgent: String
        get() = "tensai/${BuildConfig.VERSION_NAME} (+https://github.com/tensai-app/tensai)"

    /** The HTTP client of the Internet tools: no redirects, DNS pinned against private addresses, no proxy (see [OkHttpFetcher]). */
    val httpFetcher: HttpFetcher by lazy { OkHttpFetcher(userAgent = descriptiveUserAgent) }

    private val searchLanguage: () -> String = { Locale.getDefault().language.takeIf { it.length in 2..3 } ?: "en" }

    private val searchProviders: SearchProviders by lazy { SearchProviders(httpFetcher, descriptiveUserAgent, searchLanguage) }

    private suspend fun searchSelection(choice: SearchProviderChoice) = SearchSelection(choice, searchKeys.of(choice)?.get())

    /** Runs one query through the provider chosen in Settings, for the Test button. */
    val searchTester: SearchTester by lazy { SearchTester(searchProviders, ::searchSelection) }

    private fun webTools(): List<Tool> = listOf(
        WebSearchTool {
            val choice = toolSettings.searchProvider.first()
            WebSearchTool.Setup(searchProviders.chain(searchSelection(choice)), SearchProviders.displayName(choice))
        },
        WebFetchTool(PageReader(httpFetcher, httpsOnly = true)),
        WeatherTool(
            httpFetcher,
            location = AndroidLocationSource(appContext, permissionGate),
            inForeground = { AppForegroundTracker.isForeground.value },
        ),
        WikipediaTool(httpFetcher, descriptiveUserAgent, searchLanguage),
    )

    /**
     * What `read_notifications` caught since Notification access was turned on, kept a day (TEN-211). In
     * `noBackupFilesDir`, see [NotificationBufferFile].
     */
    val notificationBuffer: NotificationBuffer by lazy {
        NotificationBuffer(
            NotificationBufferFile(File(appContext.noBackupFilesDir, "notifications.json")),
            accepting = { ToolTier.PersonalData in toolSettings.granted() },
            scope = applicationScope,
        )
    }

    /** The built-in tools, filtered by the tiers granted in Settings. Settings lists them per tier. */
    val toolRegistry: ToolRegistry by lazy {
        ToolRegistry.default(
            grants = toolSettings,
            platformTools = listOf(
                DeviceInfoTool(AndroidDeviceInfoSource(appContext)),
                SearchChatsTool(database.chatSearchDao(), conversationId = { ActiveSessionTracker.current.value?.conversationId }),
                ClipboardTool(AndroidClipboardAccess(appContext)),
                TorchTool(AndroidTorchControl(appContext, applicationScope, AppForegroundTracker.isForeground)),
                MemoryTool(
                    memoryDao,
                    conversationId = { ActiveSessionTracker.current.value?.conversationId },
                    onForgotten = { memoryPrompt.invalidate() },
                ),
                AskUserTool(askUserGate),
                ReadNotificationsTool(
                    AndroidNotificationAccess(appContext) { toolSettings.excludedNotificationApps.first() },
                    notificationBuffer,
                    excluded = { toolSettings.excludedNotificationApps.first() },
                ),
            ) + phoneActionTools() + webTools(),
        )
    }

    /**
     * The agent loop (TEN-172), shared by the chat and, later, the API server. Results are counted with the loaded
     * model's tokenizer, which answers nothing while a generation holds the engine.
     */
    val agentRuntime: AgentRuntime by lazy { agentRuntimeOn(toolRegistry, confirmationGate) }

    /**
     * An [AgentRuntime] on [inferenceQueue]: the chat's [agentRuntime], or the debug agent bench's, which refuses what it
     * must not run and may try another [toolPolicy].
     */
    fun agentRuntimeOn(registry: ToolRegistry, gate: ConfirmationGate, toolPolicy: ToolPolicy = ToolPolicy.Current): AgentRuntime =
        AgentRuntime(
            registry = registry,
            gate = gate,
            // One bounded wait for a foreign generation to leave between two iterations, see [AgentRuntime].
            waitForQueue = {
                withTimeoutOrNull(5_000) { inferenceQueue.state.first { it is QueueState.ModelReady } } != null
            },
            countTokens = { text -> inferenceQueue.countTokens(listOf(text))?.firstOrNull() },
            toolPolicy = toolPolicy,
        )

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
