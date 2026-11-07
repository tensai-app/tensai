package io.github.tensaiapp.ui.screens.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.tensaiapp.R
import io.github.tensaiapp.agent.FakeHttp
import io.github.tensaiapp.agent.SearchProviders
import io.github.tensaiapp.agent.SearchSelection
import io.github.tensaiapp.agent.SearchTester
import io.github.tensaiapp.agent.ToolRegistry
import io.github.tensaiapp.agent.ToolTier
import io.github.tensaiapp.agent.json
import io.github.tensaiapp.data.SearchProviderChoice
import io.github.tensaiapp.data.ToolSettingsRepository
import io.github.tensaiapp.ui.tools.UiText
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Test button of Settings > Tools > Internet (TEN-181): states, failures that are not search errors, stale results. */
class ToolsViewModelTest {

    private lateinit var dir: File
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    @Before fun setUp() {
        dir = Files.createTempDirectory("tensai-toolsvm-test").toFile()
    }

    @After fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun viewModel(tester: SearchTester): ToolsViewModel {
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "tools.preferences_pb") }
        return ToolsViewModel(ToolSettingsRepository(store), ToolRegistry(), keys = null, tester = tester, scope = scope)
    }

    private fun tester(http: FakeHttp, selection: suspend (SearchProviderChoice) -> SearchSelection) =
        SearchTester(SearchProviders(http, "ua"), selection)

    private suspend fun ToolsViewModel.await(done: (SearchTestState) -> Boolean): SearchTestState =
        withTimeout(5_000) {
            while (true) {
                val state = testState.value
                if (done(state)) return@withTimeout state
                kotlinx.coroutines.delay(5)
            }
            @Suppress("UNREACHABLE_CODE") testState.value
        }

    /**
     * TEN-211: turning personal data off writes the grant first, then forgets what was caught, so a notification still on
     * its way (the buffer reads the grant on each add) is not kept again. Excluding an app forgets its notifications.
     */
    @Test fun turningPersonalDataOffForgetsAfterTheGrantAndAnExclusionPurges() = runBlocking {
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "tools.preferences_pb") }
        val settings = ToolSettingsRepository(store)
        settings.setGranted(ToolTier.PersonalData, true)
        val grantedAtClear = CompletableDeferred<Boolean>()
        val purged = CompletableDeferred<Set<String>>()
        val vm = ToolsViewModel(
            settings, ToolRegistry(), keys = null, tester = null, scope = scope,
            clearCaught = { grantedAtClear.complete(ToolTier.PersonalData in settings.granted()) },
            purgeCaught = { purged.complete(it) },
        )

        vm.setGroup(ToolGroup.PersonalData, false)
        vm.setExcludedNotificationApps("Signal, com.bank.app ,")

        assertFalse("the grant was written before the clear", withTimeout(5_000) { grantedAtClear.await() })
        assertEquals(setOf("Signal", "com.bank.app"), withTimeout(5_000) { purged.await() })
    }

    @Test fun aRunReportsRunningThenTheProviderAndTime() = runBlocking {
        val vm = viewModel(tester(FakeHttp(json("""{"results":[]}""")), { SearchSelection(it, "tvly-key") }))
        vm.testSearch(SearchProviderChoice.Tavily)
        val state = vm.await { it is SearchTestState.Answered }

        assertEquals("Tavily", (state as SearchTestState.Answered).provider)
    }

    @Test fun anExceptionThatIsNotASearchErrorEndsTheTestInsteadOfHangingOnRunning() = runBlocking {
        val vm = viewModel(tester(FakeHttp(), { throw IllegalStateException("Keystore is gone") }))
        vm.testSearch(SearchProviderChoice.Brave)
        val state = vm.await { it !is SearchTestState.Running }

        val failed = state as SearchTestState.Failed
        assertEquals(UiText.of(R.string.tools_test_network, "Brave"), failed.message)
    }

    @Test fun aResultThatArrivesAfterTheProviderChangedIsIgnored() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(tester(FakeHttp(json("""{"results":[]}""")), { choice -> gate.await(); SearchSelection(choice, "tvly-key") }))

        vm.testSearch(SearchProviderChoice.Tavily)
        assertEquals(SearchTestState.Running(SearchProviderChoice.Tavily), vm.testState.value)
        vm.setSearchProvider(SearchProviderChoice.DuckDuckGo)
        assertEquals(SearchTestState.Idle, vm.testState.value)

        gate.complete(Unit)
        kotlinx.coroutines.delay(200)
        assertEquals("the stale Tavily result is not shown under DuckDuckGo", SearchTestState.Idle, vm.testState.value)
        assertTrue(job.isActive)
    }

    @Test fun aSecondTapWhileRunningIsIgnored() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val http = FakeHttp(json("""{"results":[]}"""))
        val vm = viewModel(tester(http, { choice -> gate.await(); SearchSelection(choice, "tvly-key") }))

        vm.testSearch(SearchProviderChoice.Tavily)
        vm.testSearch(SearchProviderChoice.Tavily)
        gate.complete(Unit)
        vm.await { it is SearchTestState.Answered }

        assertEquals(1, http.urls.size)
    }
}
