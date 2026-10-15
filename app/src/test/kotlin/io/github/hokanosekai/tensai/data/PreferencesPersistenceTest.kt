package io.github.hokanosekai.tensai.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.hokanosekai.tensai.engine.BackendHint
import io.github.hokanosekai.tensai.ui.theme.ThemeMode
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The onboarding's choices have to survive the process that made them (TEN-36), and they have to
 * land in the stores the rest of the app already reads: [ThemeRepository] for what `MainActivity`
 * themes with, [BackendRepository] for what
 * [io.github.hokanosekai.tensai.ui.screens.loading.LoadModelViewModel] loads with.
 *
 * "Survives a restart" is simulated the only way a JVM test can: the write goes through one
 * `DataStore` over a real file, that instance is torn down, and a second instance is built over
 * the same file to read it back. Everything above the file is fresh — no shared cache, no shared
 * scope — so a value that comes back came off disk.
 */
class PreferencesPersistenceTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("tensai-preferences-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun theThemePickedInOnboardingComesBackAfterARestart() = runBlocking {
        val file = File(dir, "theme.preferences_pb")

        withStore(file) { ThemeRepository(it).setThemeMode(ThemeMode.Dark) }
        val restored = withStore(file) { ThemeRepository(it).themeMode.first() }

        assertEquals(ThemeMode.Dark, restored)
    }

    @Test
    fun theBackendPickedInOnboardingComesBackAfterARestart() = runBlocking {
        val file = File(dir, "backend.preferences_pb")

        withStore(file) { BackendRepository(it).setPreference(BackendPreference.Cpu) }
        val restored = withStore(file) { BackendRepository(it).preference.first() }

        assertEquals(BackendPreference.Cpu, restored)
    }

    @Test
    fun skippingTheOnboardingIsRememberedAfterARestart() = runBlocking {
        val file = File(dir, "onboarding.preferences_pb")

        withStore(file) { OnboardingRepository(it).setSkipped(true) }
        val restored = withStore(file) { OnboardingRepository(it).skipped.first() }

        assertEquals(true, restored)
    }

    @Test
    fun theLanguagePickedInSettingsComesBackAfterARestart() = runBlocking {
        val file = File(dir, "language.preferences_pb")

        withStore(file) { LanguageRepository(it).setLanguage(AppLanguage.French) }
        val restored = withStore(file) { LanguageRepository(it).language.first() }

        assertEquals(AppLanguage.French, restored)
    }

    @Test
    fun anUntouchedInstallReadsTheDocumentedDefaults() = runBlocking {
        assertEquals(ThemeMode.System, withStore(File(dir, "t.preferences_pb")) { ThemeRepository(it).themeMode.first() })
        assertEquals(
            BackendPreference.Auto,
            withStore(File(dir, "b.preferences_pb")) { BackendRepository(it).preference.first() },
        )
        assertEquals(false, withStore(File(dir, "o.preferences_pb")) { OnboardingRepository(it).skipped.first() })
        assertEquals(
            AppLanguage.System,
            withStore(File(dir, "l.preferences_pb")) { LanguageRepository(it).language.first() },
        )
    }

    @Test
    fun onlySystemDefersToTheDevicesOwnLocaleList() {
        // The one thing [AppLanguage] promises beyond round-tripping: System overrides nothing,
        // rather than quietly meaning English.
        assertNull(AppLanguage.System.localeTag)
        assertEquals("en", AppLanguage.English.localeTag)
        assertEquals("fr", AppLanguage.French.localeTag)
    }

    @Test
    fun theGlobalContextSettingComesBackAfterARestart() = runBlocking {
        val file = File(dir, "context.preferences_pb")

        withStore(file) { ContextRepository(it).setSetting(ContextSetting.K16) }
        val restored = withStore(file) { ContextRepository(it).setting.first() }

        assertEquals(ContextSetting.K16, restored)
    }

    @Test
    fun theGlobalContextSettingDefaultsToAutoAndSurvivesAnUnknownStoredName() = runBlocking {
        val file = File(dir, "context-default.preferences_pb")

        assertEquals(ContextSetting.Auto, withStore(file) { ContextRepository(it).setting.first() })

        // A build downgrade can leave a name this one has never heard of: that reads as the default.
        withStore(file) { store -> store.edit { it[stringPreferencesKey("context_setting")] = "K64" } }
        assertEquals(ContextSetting.Auto, withStore(file) { ContextRepository(it).setting.first() })
    }

    @Test
    fun anUnknownStoredAutoMemoryShareReadsAsTheDefault() = runBlocking {
        val file = File(dir, "share-unknown.preferences_pb")

        withStore(file) { store -> store.edit { it[stringPreferencesKey("auto_memory_share")] = "P99" } }

        assertEquals(AutoMemoryShare.P50, withStore(file) { ContextRepository(it).autoShare.first() })
        assertEquals(AutoMemoryShare.P50, withStore(file) { ContextRepository(it).prefs.first().autoShare })
    }

    @Test
    fun theAutoMemorySharePickedInSettingsComesBackAfterARestartAndDefaultsToFiftyPercent() = runBlocking {
        val file = File(dir, "share.preferences_pb")
        assertEquals(AutoMemoryShare.P50, withStore(file) { ContextRepository(it).autoShare.first() })

        withStore(file) { ContextRepository(it).setAutoShare(AutoMemoryShare.P70) }
        val restored = withStore(file) { ContextRepository(it).prefs.first() }

        assertEquals(ContextPrefs(ContextSetting.Auto, AutoMemoryShare.P70), restored)
    }

    @Test
    fun autoIsTheOnlyPreferenceThatDefersToTheCompatibilityChecker() {
        assertEquals(BackendHint.Gpu, BackendPreference.Auto.resolve(recommended = BackendHint.Gpu))
        assertEquals(BackendHint.Cpu, BackendPreference.Auto.resolve(recommended = BackendHint.Cpu))
        assertEquals(BackendHint.Cpu, BackendPreference.Cpu.resolve(recommended = BackendHint.Gpu))
        assertEquals(BackendHint.Gpu, BackendPreference.Gpu.resolve(recommended = BackendHint.Cpu))
    }

    /**
     * Runs [block] against a `DataStore` over [file], then tears that instance down — DataStore
     * refuses two live instances over one file, and the tear-down is what makes the next call a
     * clean "new process" over the same bytes.
     */
    private suspend fun <T> withStore(file: File, block: suspend (DataStore<Preferences>) -> T): T {
        val job = Job()
        val scope = CoroutineScope(job + Dispatchers.IO)
        return try {
            block(PreferenceDataStoreFactory.create(scope = scope) { file })
        } finally {
            job.cancelAndJoin()
        }
    }
}
