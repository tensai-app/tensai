package io.github.hokanosekai.tensai.ui.screens.settings

import io.github.hokanosekai.tensai.compat.DeviceProfile
import io.github.hokanosekai.tensai.data.AppLanguage
import io.github.hokanosekai.tensai.data.BackendPreference
import io.github.hokanosekai.tensai.data.BackendRepository
import io.github.hokanosekai.tensai.data.LanguageRepository
import io.github.hokanosekai.tensai.data.ThemeRepository
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.data.session.PurgeReport
import io.github.hokanosekai.tensai.data.session.SessionEntity
import io.github.hokanosekai.tensai.data.session.SessionRepository
import io.github.hokanosekai.tensai.runtime.QueueState
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.runtime.residentModel
import io.github.hokanosekai.tensai.ui.theme.ThemeMode
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
 * [io.github.hokanosekai.tensai.ui.screens.loading.LoadModelViewModel] loads from, so this screen
 * and the onboarding's preferences step cannot disagree about what the user picked.
 *
 * Takes flows rather than the repositories behind them wherever a repository would drag in an
 * Android `Context` (the model library, the volume size), so the whole class can be exercised from
 * a JVM test — same reasoning as [io.github.hokanosekai.tensai.ui.screens.HomeViewModel].
 *
 * @param scope where writes run. Deliberately [RuntimeGraph.applicationScope] and **not** a
 *   `rememberCoroutineScope()`: [clearSessions] deletes files for as long as that takes, and a
 *   composition-scoped job would be cancelled by the user navigating away mid-purge — leaving
 *   some sessions deleted and some not. Same reasoning as `OnboardingScreen`'s `persistScope`.
 * @param deviceProfile read once per [inference] emission rather than held, because
 *   [DeviceProfile.availableRamBytes] drifts (see [io.github.hokanosekai.tensai.compat.DeviceProfiler]).
 *   `null` when it can't be read at all.
 * @param fileSize see [SettingsStorage.figures].
 * @param appVersion the installed package's own `versionName`, read from the platform rather than
 *   written here — see [RuntimeGraph.appVersionName].
 */
class SettingsViewModel(
    private val scope: CoroutineScope = RuntimeGraph.applicationScope,
    private val themeRepository: ThemeRepository = RuntimeGraph.themeRepository,
    private val languageRepository: LanguageRepository = RuntimeGraph.languageRepository,
    private val backendRepository: BackendRepository = RuntimeGraph.backendRepository,
    private val sessionRepository: SessionRepository = RuntimeGraph.sessionRepository,
    models: Flow<List<ModelEntity>> = RuntimeGraph.modelRepository.models,
    sessions: Flow<List<SessionEntity>> = RuntimeGraph.sessionRepository.observeSessions(),
    volumeTotalBytes: Flow<Long> = RuntimeGraph.modelRepository.storageUsage().map { it.totalBytes },
    queueState: Flow<QueueState> = RuntimeGraph.inferenceQueue.state,
    private val deviceProfile: () -> DeviceProfile? = {
        runCatching { RuntimeGraph.deviceProfiler.profile() }.getOrNull()
    },
    fileSize: (String) -> Long? = { File(it).takeIf(File::isFile)?.length() },
    val appVersion: String = RuntimeGraph.appVersionName,
) {

    /** What `MainActivity` is themed with right now — see [ThemeRepository]. */
    val themeMode: Flow<ThemeMode> = themeRepository.themeMode

    /** What `MainActivity` resolves its strings against right now — see [LanguageRepository]. */
    val language: Flow<AppLanguage> = languageRepository.language

    /** What the next load will resolve its backend from — see [BackendPreference.resolve]. */
    val backend: Flow<BackendPreference> = backendRepository.preference

    /** Models and sessions as they are *on disk* — see [SettingsStorage.figures]. */
    val storage: Flow<StorageFigures> =
        combine(models, sessions, volumeTotalBytes) { modelList, sessionList, total ->
            SettingsStorage.figures(
                models = modelList,
                sessions = sessionList,
                volumeTotalBytes = total,
                fileSize = fileSize,
            )
        }

    /** Context window and KV-cache precision, in use or planned — see [SettingsInference.facts]. */
    val inference: Flow<InferenceFacts> = combine(queueState, models) { state, modelList ->
        SettingsInference.facts(
            resident = state.residentModel,
            models = modelList,
            profile = deviceProfile(),
            fileExists = { path -> fileSize(path) != null },
        )
    }

    fun setThemeMode(mode: ThemeMode) {
        scope.launch { themeRepository.setThemeMode(mode) }
    }

    fun setLanguage(language: AppLanguage) {
        scope.launch { languageRepository.setLanguage(language) }
    }

    fun setBackend(preference: BackendPreference) {
        scope.launch { backendRepository.setPreference(preference) }
    }

    /**
     * The in-flight [clearSessions], if any — a second tap while one is running is a no-op rather
     * than a second purge, same belt-and-braces as `HomeViewModel.start`.
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
