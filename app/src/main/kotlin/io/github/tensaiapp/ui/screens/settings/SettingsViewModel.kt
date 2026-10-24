package io.github.tensaiapp.ui.screens.settings

import io.github.tensaiapp.compat.DeviceProfile
import io.github.tensaiapp.data.AppLanguage
import io.github.tensaiapp.data.BackendPreference
import io.github.tensaiapp.data.BackendRepository
import io.github.tensaiapp.data.ChangelogRepository
import io.github.tensaiapp.data.ContextRepository
import io.github.tensaiapp.data.AutoMemoryShare
import io.github.tensaiapp.data.ContextSetting
import io.github.tensaiapp.data.InstructionsSettings
import io.github.tensaiapp.data.LanguageRepository
import io.github.tensaiapp.data.ThemeRepository
import io.github.tensaiapp.data.models.ModelEntity
import io.github.tensaiapp.data.models.StorageUsage
import io.github.tensaiapp.data.session.PurgeReport
import io.github.tensaiapp.data.session.SessionEntity
import io.github.tensaiapp.data.session.SessionRepository
import io.github.tensaiapp.download.DownloadPreferences
import io.github.tensaiapp.hf.HfTokenCheck
import io.github.tensaiapp.hf.TokenFormat
import io.github.tensaiapp.hf.TokenStore
import io.github.tensaiapp.runtime.QueueState
import io.github.tensaiapp.runtime.RuntimeGraph
import io.github.tensaiapp.runtime.residentModel
import io.github.tensaiapp.ui.theme.ThemeMode
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Drives Settings (TEN-38).
 *
 * Every value on the screen is a projection of a store — DataStore for the three preferences, Room
 * plus the disk for the storage figures, the live queue for what is loaded. Nothing is held in
 * this class: a write goes to the repository and comes back through the same flow the screen is
 * already collecting, which is what makes a theme or language change apply as the user taps rather
 * than on the next launch, and what makes the settings the onboarding wrote (TEN-36) show up here
 * as the current values rather than as defaults.
 *
 * The stores are the app's existing ones, reached through [RuntimeGraph]'s single instances —
 * [ThemeRepository] and [BackendRepository] are the very objects `MainActivity` themes from and
 * [io.github.tensaiapp.ui.screens.loading.LoadModelViewModel] loads from, so this screen
 * and the onboarding's preferences step cannot disagree about what the user picked.
 *
 * Takes flows rather than the repositories behind them wherever a repository would drag in an
 * Android `Context` (the model library, the volume size), so the whole class can be exercised from
 * a JVM test.
 *
 * @param scope where writes run. Deliberately [RuntimeGraph.applicationScope] and **not** a
 *   `rememberCoroutineScope()`: [clearSessions] deletes files for as long as that takes, and a
 *   composition-scoped job would be cancelled by the user navigating away mid-purge — leaving
 *   some sessions deleted and some not. Same reasoning as `OnboardingScreen`'s `persistScope`.
 * @param deviceProfile read once per [inference] emission rather than held, because
 *   [DeviceProfile.availableRamBytes] drifts (see [io.github.tensaiapp.compat.DeviceProfiler]).
 *   `null` when it can't be read at all.
 * @param fileSize see [SettingsStorage.figures].
 * @param tokenStore the Hugging Face token (TEN-144): only its masked form leaves this class.
 * @param checkToken one authenticated call to Hugging Face, see [io.github.tensaiapp.hf.HfClient.checkToken].
 * @param appVersion the installed package's own `versionName`, read from the platform rather than
 *   written here — see [RuntimeGraph.appVersionName].
 * @param changelogSeen the last version whose release notes were opened, see [ChangelogRepository].
 */
class SettingsViewModel(
    private val scope: CoroutineScope = RuntimeGraph.applicationScope,
    private val themeRepository: ThemeRepository = RuntimeGraph.themeRepository,
    private val languageRepository: LanguageRepository = RuntimeGraph.languageRepository,
    private val backendRepository: BackendRepository = RuntimeGraph.backendRepository,
    private val contextRepository: ContextRepository = RuntimeGraph.contextRepository,
    private val sessionRepository: SessionRepository = RuntimeGraph.sessionRepository,
    private val downloadPreferences: DownloadPreferences = RuntimeGraph.downloadPreferences,
    private val instructionsSettings: InstructionsSettings = RuntimeGraph.instructionsSettings,
    private val tokenStore: TokenStore = RuntimeGraph.tokenStore,
    private val checkToken: suspend (String) -> HfTokenCheck = { RuntimeGraph.hfClient.checkToken(it) },
    models: Flow<List<ModelEntity>> = RuntimeGraph.modelRepository.models,
    sessions: Flow<List<SessionEntity>> = RuntimeGraph.sessionRepository.observeSessions(),
    memoryCount: Flow<Int> = RuntimeGraph.memoryDao.observeCount(),
    volume: Flow<StorageUsage> = RuntimeGraph.modelRepository.storageUsage(),
    queueState: Flow<QueueState> = RuntimeGraph.inferenceQueue.state,
    private val deviceProfile: () -> DeviceProfile? = {
        runCatching { RuntimeGraph.deviceProfiler.profile() }.getOrNull()
    },
    fileSize: (String) -> Long? = { File(it).takeIf(File::isFile)?.length() },
    val appVersion: String = RuntimeGraph.appVersionName,
    changelogSeen: Flow<String?> = RuntimeGraph.changelogRepository.seenVersion,
) {

    /** What `MainActivity` is themed with right now — see [ThemeRepository]. */
    val themeMode: Flow<ThemeMode> = themeRepository.themeMode

    /** What `MainActivity` resolves its strings against right now — see [LanguageRepository]. */
    val language: Flow<AppLanguage> = languageRepository.language

    /** What the next load will resolve its backend from — see [BackendPreference.resolve]. */
    val backend: Flow<BackendPreference> = backendRepository.preference

    /** The global context window setting, see [ContextSetting]; a model's own override wins over it. */
    val contextSetting: Flow<ContextSetting> = contextRepository.setting

    /** The share of memory Auto may spend, see [AutoMemoryShare]. */
    val autoShare: Flow<AutoMemoryShare> = contextRepository.autoShare

    /** How many facts the `memory` tool holds (TEN-176), for the Memory row. */
    val memoryCount: Flow<Int> = memoryCount

    /** Whether the notes of the installed version were not opened yet (TEN-193), the dot on the Version row. */
    val changelogDot: Flow<Boolean> = changelogSeen.map { ChangelogRepository.isUnseen(appVersion, it) }

    /** Whether a download may start on mobile data without asking, see [DownloadPreferences]. */
    val allowMobileData: Flow<Boolean> = downloadPreferences.allowMobileData

    /**
     * The Hugging Face token as `hf_••••abcd`, or null when none is set. Re-read when the token is
     * set or cleared; the clear text is never exposed.
     */
    val tokenMask: Flow<String?> = tokenStore.hasToken.distinctUntilChanged().map { has ->
        if (has) tokenStore.get()?.let(TokenStore::mask) else null
    }

    /** Models and sessions as they are *on disk* — see [SettingsStorage.figures]. */
    val storage: Flow<StorageFigures> =
        combine(models, sessions, volume) { modelList, sessionList, usage ->
            SettingsStorage.figures(
                models = modelList,
                sessions = sessionList,
                volumeTotalBytes = usage.totalBytes,
                freeBytes = usage.freeBytes,
                fileSize = fileSize,
            )
        }

    /** Context window and KV-cache precision, in use or planned — see [SettingsInference.facts]. */
    val inference: Flow<InferenceFacts> = combine(queueState, models, contextRepository.prefs) { state, modelList, prefs ->
        SettingsInference.facts(
            resident = state.residentModel,
            models = modelList,
            profile = deviceProfile(),
            global = prefs.setting,
            autoShare = prefs.autoShare,
            fileExists = { path -> fileSize(path) != null },
        )
    }

    /**
     * The model the global context picker shows memory figures for, see [SettingsInference.previewModel].
     */
    val contextPreviewModel: Flow<ModelEntity?> = combine(queueState, models) { state, modelList ->
        SettingsInference.previewModel(
            resident = state.residentModel,
            models = modelList,
            fileExists = { path -> fileSize(path) != null },
        )
    }

    /** The device as it is now, for the picker's figures; `null` when it can't be read. */
    fun currentProfile(): DeviceProfile? = deviceProfile()

    fun setThemeMode(mode: ThemeMode) {
        scope.launch { themeRepository.setThemeMode(mode) }
    }

    fun setLanguage(language: AppLanguage) {
        scope.launch { languageRepository.setLanguage(language) }
    }

    fun setBackend(preference: BackendPreference) {
        scope.launch { backendRepository.setPreference(preference) }
    }

    fun setContextSetting(setting: ContextSetting) {
        scope.launch { contextRepository.setSetting(setting) }
    }

    fun setAutoShare(share: AutoMemoryShare) {
        scope.launch { contextRepository.setAutoShare(share) }
    }

    fun setAllowMobileData(allowed: Boolean) {
        scope.launch { downloadPreferences.setAllowMobileData(allowed) }
    }

    /** "Your instructions", added to every chat's system message, see [InstructionsSettings]. */
    val globalInstructions: Flow<String> = instructionsSettings.global

    fun setGlobalInstructions(text: String) {
        scope.launch { instructionsSettings.setGlobal(text) }
    }

    /**
     * Refuses a [raw] that is not shaped like a token ([TokenFormat]) without any call, else checks it (trimmed) against Hugging Face, then stores it, and reports to [onResult]. Rejected
     * tokens are not stored; one that could not be checked (offline) is, since a typo is the less
     * likely reason and the user can always replace it. Blank does nothing. Runs on [scope] so the
     * write is not cut short by leaving the screen.
     */
    fun saveToken(raw: String, onResult: (TokenSaveResult) -> Unit) {
        val token = raw.trim()
        if (token.isEmpty()) return
        if (!TokenFormat.isValid(token)) {
            onResult(TokenSaveResult.Malformed)
            return
        }
        scope.launch {
            val check = try {
                checkToken(token)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                HfTokenCheck.Unavailable
            }
            onResult(
                when {
                    check == HfTokenCheck.Rejected -> TokenSaveResult.Rejected
                    !tokenStore.set(token) -> TokenSaveResult.StoreFailed
                    check == HfTokenCheck.Accepted -> TokenSaveResult.Saved
                    else -> TokenSaveResult.SavedUnchecked
                },
            )
        }
    }

    fun removeToken() {
        scope.launch { tokenStore.clear() }
    }

    /**
     * The in-flight [clearSessions], if any — a second tap while one is running is a no-op rather
     * than a second purge.
     */
    private var clearJob: Job? = null

    /**
     * Deletes every saved session, then hands the [PurgeReport] to [onCleared] so the screen can
     * say what went. See [SessionRepository.purgeAll] for what this does and does not touch — in
     * particular, it does not interrupt a generation, and it does not stop the app from saving
     * sessions again afterwards.
     */
    fun clearSessions(onCleared: (PurgeReport) -> Unit = {}) {
        if (clearJob?.isActive == true) return
        clearJob = scope.launch {
            val report = sessionRepository.purgeAll()
            onCleared(report)
        }
    }
}

/** What saving the Hugging Face token ended in, for the sheet to say. */
enum class TokenSaveResult { Saved, SavedUnchecked, Rejected, Malformed, StoreFailed }
