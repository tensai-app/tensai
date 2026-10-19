package io.github.tensaiapp.ui.screens.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.tensaiapp.compat.DeviceProfile
import io.github.tensaiapp.data.AppLanguage
import io.github.tensaiapp.data.BackendPreference
import io.github.tensaiapp.data.BackendRepository
import io.github.tensaiapp.data.ContextRepository
import io.github.tensaiapp.data.ContextSetting
import io.github.tensaiapp.data.LanguageRepository
import io.github.tensaiapp.data.ThemeRepository
import io.github.tensaiapp.data.models.ModelEntity
import io.github.tensaiapp.data.models.StorageUsage
import io.github.tensaiapp.data.session.PurgeReport
import io.github.tensaiapp.data.session.SessionDao
import io.github.tensaiapp.data.session.SessionEntity
import io.github.tensaiapp.data.session.SessionModelKey
import io.github.tensaiapp.data.session.SessionRepository
import io.github.tensaiapp.data.session.SessionSaveOutcome
import io.github.tensaiapp.download.DownloadPreferences
import io.github.tensaiapp.hf.FakeTokenCipher
import io.github.tensaiapp.hf.HfTokenCheck
import io.github.tensaiapp.hf.TokenStore
import io.github.tensaiapp.engine.KvCacheType
import io.github.tensaiapp.engine.LoadedModelInfo
import io.github.tensaiapp.runtime.QueueState
import io.github.tensaiapp.ui.theme.ThemeMode
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private lateinit var viewModelJob: Job
    private lateinit var viewModelScope: CoroutineScope

    private lateinit var themeStore: DataStore<Preferences>
    private lateinit var languageStore: DataStore<Preferences>
    private lateinit var backendStore: DataStore<Preferences>
    private lateinit var contextStore: DataStore<Preferences>
    private lateinit var downloadStore: DataStore<Preferences>
    private lateinit var tokenDataStore: DataStore<Preferences>
    private lateinit var tokenStore: TokenStore

    private lateinit var sessionDao: FakeSessionDao
    private lateinit var sessionRepository: SessionRepository

    private val key = SessionModelKey(modelId = "m", nCtx = 4096, kvCacheType = KvCacheType.F16, backend = "CPU")

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("tensai-settings-vm-test").toFile()
        storeJob = Job()
        storeScope = CoroutineScope(storeJob + Dispatchers.IO)
        // Deliberately *not* the store scope: this one holds nothing but the coroutines the view
        // model launches, so joining its children is exactly "the writes are done" and nothing
        // else. See [awaitWrites].
        viewModelJob = Job()
        viewModelScope = CoroutineScope(viewModelJob + Dispatchers.IO)
        themeStore = PreferenceDataStoreFactory.create(scope = storeScope) { File(dir, "theme.preferences_pb") }
        languageStore = PreferenceDataStoreFactory.create(scope = storeScope) { File(dir, "language.preferences_pb") }
        backendStore = PreferenceDataStoreFactory.create(scope = storeScope) { File(dir, "backend.preferences_pb") }
        contextStore = PreferenceDataStoreFactory.create(scope = storeScope) { File(dir, "context.preferences_pb") }
        downloadStore = PreferenceDataStoreFactory.create(scope = storeScope) { File(dir, "downloads.preferences_pb") }
        tokenDataStore = PreferenceDataStoreFactory.create(scope = storeScope) { File(dir, "token.preferences_pb") }
        tokenStore = TokenStore(tokenDataStore, FakeTokenCipher(), Dispatchers.IO)
        sessionDao = FakeSessionDao()
        sessionRepository = SessionRepository(
            dao = sessionDao,
            storageRoot = dir,
        )
    }

    @After
    fun tearDown() = runBlocking {
        viewModelJob.cancelAndJoin()
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
    fun theVersionRowShowsADotUntilTheInstalledNotesWereOpened() = runBlocking {
        assertTrue(newViewModel(changelogSeen = flowOf<String?>(null)).changelogDot.first())
        assertTrue(newViewModel(changelogSeen = flowOf<String?>("1.2.0")).changelogDot.first())
        assertFalse(newViewModel(changelogSeen = flowOf<String?>("1.2.3")).changelogDot.first())
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

    // ---- downloads group (TEN-144) ----

    @Test
    fun anUntouchedInstallHasNoTokenAndAsksBeforeUsingMobileData() = runBlocking {
        val viewModel = newViewModel()

        assertNull(viewModel.tokenMask.first())
        assertEquals(false, viewModel.allowMobileData.first())
    }

    @Test
    fun theTokenRowShowsTheMaskAndNeverTheToken() = runBlocking {
        tokenStore.set("hf_x8Tq2LmN3kQzAbCd")

        assertEquals("hf_••••AbCd", newViewModel().tokenMask.first())
    }

    @Test
    fun mobileDataFollowsTheDownloadPreferenceTheDownloadsReadFrom() = runBlocking {
        newViewModel().setAllowMobileData(true)
        awaitWrites()

        assertTrue(DownloadPreferences(downloadStore).isMobileDataAllowed())
        assertEquals(true, newViewModel().allowMobileData.first())
    }

    private suspend fun SettingsViewModel.save(raw: String): TokenSaveResult? {
        var result: TokenSaveResult? = null
        saveToken(raw) { result = it }
        awaitWrites()
        return result
    }

    @Test
    fun anAcceptedTokenIsStoredAndReportedSaved() = runBlocking {
        val viewModel = newViewModel(checkToken = { HfTokenCheck.Accepted })

        assertEquals(TokenSaveResult.Saved, viewModel.save("  hf_x8Tq2LmN3kQzAbCd "))
        assertEquals("hf_x8Tq2LmN3kQzAbCd", tokenStore.get())
    }

    @Test
    fun aRejectedTokenIsNotStored() = runBlocking {
        val viewModel = newViewModel(checkToken = { HfTokenCheck.Rejected })

        assertEquals(TokenSaveResult.Rejected, viewModel.save("hf_badtoken1"))
        assertNull(tokenStore.get())
    }

    @Test
    fun aTokenThatCouldNotBeCheckedIsStoredAndSaidSo() = runBlocking {
        val viewModel = newViewModel(checkToken = { HfTokenCheck.Unavailable })

        assertEquals(TokenSaveResult.SavedUnchecked, viewModel.save("hf_offline"))
        assertEquals("hf_offline", tokenStore.get())
    }

    @Test
    fun aTokenThatDoesNotLookLikeOneIsRefusedWithoutAnyCall() = runBlocking {
        var calls = 0
        val viewModel = newViewModel(checkToken = { calls++; HfTokenCheck.Accepted })

        for (bad in listOf("short", "has a space", "hf_\u00e9\u00e9\u00e9\u00e9\u00e9")) {
            var result: TokenSaveResult? = null
            viewModel.saveToken(bad) { result = it }
            assertEquals(TokenSaveResult.Malformed, result)
        }
        assertEquals(0, calls)
        assertNull(tokenStore.get())
    }

    @Test
    fun aBlankTokenDoesNothingAndRemoveClears() = runBlocking {
        val viewModel = newViewModel()
        assertNull(viewModel.save("   "))

        viewModel.save("hf_keepthis1")
        viewModel.removeToken()
        awaitWrites()
        assertNull(tokenStore.get())
        assertNull(viewModel.tokenMask.first())
    }

    // ---- a change lands where the rest of the app reads it ----

    @Test
    fun changingTheThemeIsImmediatelyVisibleToWhatMainActivityThemesFrom() = runBlocking {
        val viewModel = newViewModel()

        viewModel.setThemeMode(ThemeMode.Dark)
        awaitWrites()

        // A second, independent repository over the same store — the same instance `MainActivity`
        // collects, since RuntimeGraph hands out one. No restart, no invalidation: the value
        // simply arrives on the next emission of the flow the theme is already collected from,
        // which is what "applied live" means at this level. (On screen the tap and the emission
        // are not the same instant — that gap is a frame; here it is [awaitWrites].)
        assertEquals(ThemeMode.Dark, ThemeRepository(themeStore).themeMode.first())
    }

    @Test
    fun changingTheBackendLandsInTheStoreTheOnboardingAndTheLoaderShare() = runBlocking {
        val viewModel = newViewModel()

        viewModel.setBackend(BackendPreference.Cpu)
        awaitWrites()

        assertEquals(BackendPreference.Cpu, BackendRepository(backendStore).preference.first())
        assertEquals(
            "and it comes back out of the view model too, rather than being written and forgotten",
            BackendPreference.Cpu,
            viewModel.backend.first(),
        )
    }

    @Test
    fun changingTheContextSettingLandsInTheStoreTheLoaderReadsFrom() = runBlocking {
        val viewModel = newViewModel()
        assertEquals("Auto until the user says otherwise", ContextSetting.Auto, viewModel.contextSetting.first())

        viewModel.setContextSetting(ContextSetting.K16)
        awaitWrites()

        assertEquals(ContextSetting.K16, ContextRepository(contextStore).setting.first())
        assertEquals(ContextSetting.K16, viewModel.contextSetting.first())
    }

    @Test
    fun changingTheLanguageLandsInTheStoreMainActivityResolvesStringsAgainst() = runBlocking {
        val viewModel = newViewModel()

        viewModel.setLanguage(AppLanguage.French)
        awaitWrites()

        assertEquals(AppLanguage.French, LanguageRepository(languageStore).language.first())
    }

    // ---- storage ----

    @Test
    fun theStorageFiguresComeFromTheLibraryTheSessionTableAndTheDisk() = runBlocking {
        sessionDao.upsert(session("c1", "/sessions/c1.llamastate"))

        val figures = newViewModel(
            models = flowOf(listOf(model("a"), model("gone"))),
            volume = flowOf(StorageUsage(modelsBytes = 0L, freeBytes = 900L, totalBytes = 1_000L)),
            fileSize = { path -> if (path.contains("gone")) null else 100L },
        ).storage.first()

        assertEquals(100L, figures.modelBytes)
        assertEquals(1, figures.modelCount)
        assertEquals(100L, figures.sessionBytes)
        assertEquals(1, figures.sessionCount)
        assertEquals(900L, figures.freeBytes)
        assertEquals(false, figures.lowSpace)
    }

    @Test
    fun freeSpaceBelowTheAverageModelIsLow() = runBlocking {
        val figures = newViewModel(
            models = flowOf(listOf(model("a"))),
            volume = flowOf(StorageUsage(modelsBytes = 0L, freeBytes = 40L, totalBytes = 1_000L)),
            fileSize = { 100L },
        ).storage.first()

        assertEquals(40L, figures.freeBytes)
        assertEquals(true, figures.lowSpace)
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

    /**
     * Waits for the work [SettingsViewModel] launched on [viewModelScope] to finish.
     *
     * The setters are fire-and-forget — `scope.launch { repository.set… }` — so the tap returns
     * long before the store has been written. Without this, an assertion right after the tap is a
     * race between the test thread and the write, and the only thing settling it is which of the
     * two the machine happens to run first. Polling the flow with a timeout does not remove that
     * race, it just gives the write a head start and calls a slow machine a bug.
     *
     * [viewModelJob] has no children but those coroutines (the DataStores run on [storeJob]), and
     * each of them completes only once `DataStore.edit` has returned — which is *after* the value
     * has been written and published to the cache the next read serves from. So joining them is
     * the write being finished, not a guess at how long it takes, and every assertion that follows
     * one can be a single read rather than a wait.
     */
    private suspend fun awaitWrites() {
        viewModelJob.children.toList().forEach { it.join() }
    }

    /** [SettingsViewModel.clearSessions] launches on the view model's scope — wait for its result. */
    private suspend fun awaitClear(viewModel: SettingsViewModel): PurgeReport {
        val report = CompletableDeferred<PurgeReport>()
        viewModel.clearSessions { report.complete(it) }
        awaitWrites()
        // The purge coroutine is over; if it ended without calling back, that is the bug, and it
        // should read as one rather than hang the suite on a `await()` that will never return.
        assertTrue("clearSessions finished without reporting", report.isCompleted)
        return report.await()
    }

    private fun newViewModel(
        models: Flow<List<ModelEntity>> = flowOf(emptyList()),
        volume: Flow<StorageUsage> = flowOf(StorageUsage(modelsBytes = 0L, freeBytes = 900L, totalBytes = 1_000L)),
        queueState: Flow<QueueState> = flowOf(QueueState.Idle),
        fileSize: (String) -> Long? = { 100L },
        changelogSeen: Flow<String?> = flowOf(null),
        checkToken: suspend (String) -> HfTokenCheck = { HfTokenCheck.Accepted },
    ) = SettingsViewModel(
        scope = viewModelScope,
        downloadPreferences = DownloadPreferences(downloadStore),
        tokenStore = tokenStore,
        checkToken = checkToken,
        themeRepository = ThemeRepository(themeStore),
        languageRepository = LanguageRepository(languageStore),
        backendRepository = BackendRepository(backendStore),
        contextRepository = ContextRepository(contextStore),
        sessionRepository = sessionRepository,
        models = models,
        sessions = sessionDao.observeAll(),
        memoryCount = flowOf(0),
        volume = volume,
        queueState = queueState,
        deviceProfile = { profile },
        fileSize = fileSize,
        appVersion = "1.2.3",
        changelogSeen = changelogSeen,
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
