package io.github.hokanosekai.tensai.ui.screens.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.hokanosekai.tensai.compat.DeviceProfile
import io.github.hokanosekai.tensai.data.AppLanguage
import io.github.hokanosekai.tensai.data.BackendPreference
import io.github.hokanosekai.tensai.data.BackendRepository
import io.github.hokanosekai.tensai.data.LanguageRepository
import io.github.hokanosekai.tensai.data.ThemeRepository
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.data.session.PurgeReport
import io.github.hokanosekai.tensai.data.session.SessionDao
import io.github.hokanosekai.tensai.data.session.SessionEntity
import io.github.hokanosekai.tensai.data.session.SessionModelKey
import io.github.hokanosekai.tensai.data.session.SessionRepository
import io.github.hokanosekai.tensai.data.session.SessionSaveOutcome
import io.github.hokanosekai.tensai.engine.KvCacheType
import io.github.hokanosekai.tensai.engine.LoadedModelInfo
import io.github.hokanosekai.tensai.runtime.QueueState
import io.github.hokanosekai.tensai.ui.theme.ThemeMode
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [SettingsViewModel] against real DataStores over temp files and a real [SessionRepository] over a
 * temp directory — no device, no Compose.
 *
 * Two things are being defended, and both are the ticket's own words. **"Every value comes from
 * the store"**: each test writes a non-default value into a store *behind the view model's back*
 * and asserts the screen's flow reports it, which is an assertion no hard-coded default can pass.
 * And **"the settings screen and the onboarding write to the same place"**: a write through this
 * view model is read back through a *separate* repository instance over the same file — the same
 * file `MainActivity` themes from and `LoadModelViewModel` loads from, since
 * `RuntimeGraph` hands all three the same instance.
 */
class SettingsViewModelTest {

    private lateinit var dir: File
    private lateinit var storeJob: Job
    private lateinit var storeScope: CoroutineScope

    private lateinit var themeStore: DataStore<Preferences>
    private lateinit var languageStore: DataStore<Preferences>
    private lateinit var backendStore: DataStore<Preferences>

    private lateinit var sessionDao: FakeSessionDao
    private lateinit var sessionRepository: SessionRepository

    private val key = SessionModelKey(modelId = "m", nCtx = 4096, kvCacheType = KvCacheType.F16, backend = "CPU")

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("tensai-settings-vm-test").toFile()
        storeJob = Job()
        storeScope = CoroutineScope(storeJob + Dispatchers.IO)
        themeStore = PreferenceDataStoreFactory.create(scope = storeScope) { File(dir, "theme.preferences_pb") }
        languageStore = PreferenceDataStoreFactory.create(scope = storeScope) { File(dir, "language.preferences_pb") }
        backendStore = PreferenceDataStoreFactory.create(scope = storeScope) { File(dir, "backend.preferences_pb") }
        sessionDao = FakeSessionDao()
        sessionRepository = SessionRepository(
            dao = sessionDao,
            storageRoot = dir,
        )
    }

    @After
    fun tearDown() = runBlocking {
        storeJob.cancelAndJoin()
        dir.deleteRecursively()
        Unit
    }

    // ---- nothing on this screen is hard-coded ----

    @Test
    fun theThemeShownIsWhateverIsInTheStore() = runBlocking {
        ThemeRepository(themeStore).setThemeMode(ThemeMode.Light)

        assertEquals(ThemeMode.Light, newViewModel().themeMode.first())
    }

    @Test
    fun theBackendShownIsWhateverIsInTheStore() = runBlocking {
        // Written the way the onboarding writes it (TEN-36), read the way Settings reads it.
        BackendRepository(backendStore).setPreference(BackendPreference.Gpu)

        assertEquals(BackendPreference.Gpu, newViewModel().backend.first())
    }

    @Test
    fun theLanguageShownIsWhateverIsInTheStore() = runBlocking {
        LanguageRepository(languageStore).setLanguage(AppLanguage.French)

        assertEquals(AppLanguage.French, newViewModel().language.first())
    }

    @Test
    fun anUntouchedInstallShowsTheDocumentedDefaultsAndNotSomethingElse() = runBlocking {
        val viewModel = newViewModel()

        assertEquals(ThemeMode.System, viewModel.themeMode.first())
        assertEquals(AppLanguage.System, viewModel.language.first())
        assertEquals(BackendPreference.Auto, viewModel.backend.first())
    }

    // ---- a change lands where the rest of the app reads it ----

    @Test
    fun changingTheThemeIsImmediatelyVisibleToWhatMainActivityThemesFrom() = runBlocking {
        val viewModel = newViewModel()

        viewModel.setThemeMode(ThemeMode.Dark)

        // A second, independent repository over the same store — the same instance `MainActivity`
        // collects, since RuntimeGraph hands out one. No restart, no invalidation: the value
        // simply arrives on the next emission of the flow the theme is already collected from,
        // which is what "applied live" means at this level. (Awaited rather than read once: the
        // write runs on the view model's scope, so the tap and the emission are not the same
        // instant — on screen that gap is a frame.)
        assertEquals(
            ThemeMode.Dark,
            withTimeout(10_000) { ThemeRepository(themeStore).themeMode.first { it == ThemeMode.Dark } },
        )
    }

    @Test
    fun changingTheBackendLandsInTheStoreTheOnboardingAndTheLoaderShare() = runBlocking {
        val viewModel = newViewModel()

        viewModel.setBackend(BackendPreference.Cpu)

        assertEquals(
            BackendPreference.Cpu,
            withTimeout(10_000) { BackendRepository(backendStore).preference.first { it == BackendPreference.Cpu } },
        )
        assertEquals(
            "and it comes back out of the view model too, rather than being written and forgotten",
            BackendPreference.Cpu,
            viewModel.backend.first(),
        )
    }

    @Test
    fun changingTheLanguageLandsInTheStoreMainActivityResolvesStringsAgainst() = runBlocking {
        val viewModel = newViewModel()

        viewModel.setLanguage(AppLanguage.French)

        assertEquals(
            AppLanguage.French,
            withTimeout(10_000) { LanguageRepository(languageStore).language.first { it == AppLanguage.French } },
        )
    }

    // ---- storage ----

    @Test
    fun theStorageFiguresComeFromTheLibraryTheSessionTableAndTheDisk() = runBlocking {
        sessionDao.upsert(session("c1", "/sessions/c1.llamastate"))

        val figures = newViewModel(
            models = flowOf(listOf(model("a"), model("gone"))),
            volumeTotalBytes = flowOf(1_000L),
            fileSize = { path -> if (path.contains("gone")) null else 100L },
        ).storage.first()

        assertEquals(100L, figures.modelBytes)
        assertEquals(1, figures.modelCount)
        assertEquals(100L, figures.sessionBytes)
        assertEquals(1, figures.sessionCount)
        assertEquals(0.1f, figures.modelFraction, 0.0001f)
    }

    @Test
    fun theContextRowReportsTheResidentModelWhenThereIsOne() = runBlocking {
        val facts = newViewModel(
            queueState = flowOf(
                QueueState.Generating(LoadedModelInfo("Qwen3-1.7B", 2048, "CPU", KvCacheType.Q8_0)),
            ),
        ).inference.first()

        assertEquals(
            "a model stays resident across a generation, so Settings must not read a reply in " +
                "progress as 'nothing loaded'",
            InferenceFacts.InUse("Qwen3-1.7B", 2048, KvCacheType.Q8_0),
            facts,
        )
    }

    // ---- clear ----

    @Test
    fun clearingSessionsEmptiesTheTableAndReportsWhatItFreed() = runBlocking {
        sessionRepository.save("c1", key) { path -> File(path).writeText("0123456789"); SessionSaveOutcome.Wrote(10) }

        val report = awaitClear(newViewModel())

        assertTrue("expected the sessions table to be empty", sessionDao.rows.isEmpty())
        assertEquals(1, report.sessionCount)
        assertEquals(10L, report.freedBytes)
    }

    @Test
    fun clearingWithNothingSavedIsAHarmlessNoOp() = runBlocking {
        val report = awaitClear(newViewModel())

        assertEquals(0, report.sessionCount)
        assertEquals(0L, report.freedBytes)
        assertNull(sessionDao.getByConversation("nothing"))
    }

    /** [SettingsViewModel.clearSessions] launches on the view model's scope — wait for its result. */
    private suspend fun awaitClear(viewModel: SettingsViewModel): PurgeReport {
        val report = CompletableDeferred<PurgeReport>()
        viewModel.clearSessions { report.complete(it) }
        return withTimeout(10_000) { report.await() }
    }

    private fun newViewModel(
        models: Flow<List<ModelEntity>> = flowOf(emptyList()),
        volumeTotalBytes: Flow<Long> = flowOf(1_000L),
        queueState: Flow<QueueState> = flowOf(QueueState.Idle),
        fileSize: (String) -> Long? = { 100L },
    ) = SettingsViewModel(
        scope = storeScope,
        themeRepository = ThemeRepository(themeStore),
        languageRepository = LanguageRepository(languageStore),
        backendRepository = BackendRepository(backendStore),
        sessionRepository = sessionRepository,
        models = models,
        sessions = sessionDao.observeAll(),
        volumeTotalBytes = volumeTotalBytes,
        queueState = queueState,
        deviceProfile = { profile },
        fileSize = fileSize,
        appVersion = "1.2.3",
    )

    private val profile = DeviceProfile(
        totalRamBytes = 8L * 1024 * 1024 * 1024,
        availableRamBytes = 6L * 1024 * 1024 * 1024,
        socModel = "test-soc",
        bigCoreCount = 4,
        hasVulkan = true,
        hasOpenCl = false,
    )

    private fun model(name: String) = ModelEntity(
        id = name,
        displayName = name,
        fileName = "$name.gguf",
        filePath = "/models/$name.gguf",
        sizeBytes = 1_000_000,
        architecture = "qwen2",
        quantLabel = "Q4_K_M",
        paramCount = 1_500_000_000,
        contextLength = 4096,
        embeddingLength = 1536,
        blockCount = 28,
        supportsThinking = false,
        importedAt = 0L,
    )

    private fun session(conversationId: String, path: String) = SessionEntity(
        conversationId = conversationId,
        modelId = "m",
        filePath = path,
        nCtx = 4096,
        kvCacheType = "F16",
        backend = "CPU",
        tokenCount = 10,
        sizeBytes = 10,
        createdAt = 0L,
        lastAccessedAt = 0L,
    )

    private class FakeSessionDao : SessionDao {
        val rows = mutableMapOf<String, SessionEntity>()
        private val flow = MutableStateFlow<List<SessionEntity>>(emptyList())

        override suspend fun getByConversation(conversationId: String): SessionEntity? = rows[conversationId]

        override fun observeAll(): Flow<List<SessionEntity>> = flow

        override suspend fun allSessions(): List<SessionEntity> = rows.values.toList()

        override suspend fun upsert(session: SessionEntity) {
            rows[session.conversationId] = session
            flow.value = rows.values.toList()
        }

        override suspend fun delete(conversationId: String) {
            rows.remove(conversationId)
            flow.value = rows.values.toList()
        }

        override suspend fun deleteAll() {
            rows.clear()
            flow.value = emptyList()
        }
    }
}
