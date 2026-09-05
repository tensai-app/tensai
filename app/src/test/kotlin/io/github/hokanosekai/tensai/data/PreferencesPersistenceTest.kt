package io.github.hokanosekai.tensai.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
