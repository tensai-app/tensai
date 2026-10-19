package io.github.tensaiapp.agent

import io.github.tensaiapp.data.SearchProviderChoice
import io.github.tensaiapp.ui.screens.settings.SearchTestState
import io.github.tensaiapp.websearch.SearchError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The provider chain built from Settings, and the Test button's run (TEN-181). */
class SearchProvidersTest {

    private val wikipediaJson = """{"pages":[{"key":"Kotlin","title":"Kotlin","excerpt":"a language"}]}"""
    private val tavilyJson = """{"results":[{"title":"T","url":"https://t.example/","content":"c"}]}"""

    private fun providers(http: FakeHttp) = SearchProviders(http, "tensai/test")

    private fun chain(http: FakeHttp, choice: SearchProviderChoice, key: String? = null) =
        providers(http).chain(SearchSelection(choice, key))!!

    @Test fun duckDuckGoFallsToWikipediaThenMarginalia() {
        val http = FakeHttp(status(202), status(403), json("""{"results":[{"url":"https://m.example/","title":"M","description":"d"}]}"""))
        val results = runBlocking { chain(http, SearchProviderChoice.DuckDuckGo).search("kotlin", 3) }

        assertEquals("marginalia", results.single().source)
        assertEquals(3, http.urls.size)
        assertTrue(http.urls[0].startsWith("https://html.duckduckgo.com/"))
        assertTrue(http.urls[1].contains("wikipedia.org"))
        assertTrue(http.urls[2].startsWith("https://api2.marginalia-search.com/"))
    }

    @Test fun duckDuckGoNeverSwitchesToAKeyedProvider() {
        // Everything free is blocked: the error comes back, no Tavily or Brave request is made.
        val http = FakeHttp(status(202), status(403), status(503))
        try {
            runBlocking { chain(http, SearchProviderChoice.DuckDuckGo, key = "tvly-unused").search("kotlin", 3) }
            fail("expected an error")
        } catch (e: SearchError) {
            assertTrue(e is SearchError.RateLimited)
        }
        assertTrue(http.urls.none { it.contains("tavily") || it.contains("brave") })
    }

    @Test fun aKeyedProviderOutOfQuotaFallsToTheFreeOnes() {
        val http = FakeHttp(status(432), status(202), json(wikipediaJson))
        val results = runBlocking { chain(http, SearchProviderChoice.Tavily, "tvly-key").search("kotlin", 3) }

        assertEquals("wikipedia", results.single().source)
        assertTrue(http.urls[0].startsWith("https://api.tavily.com/"))
        assertTrue(http.urls[1].startsWith("https://html.duckduckgo.com/"))
    }

    @Test fun aRejectedKeyStopsTheChain() {
        val http = FakeHttp(status(401), json(wikipediaJson))
        try {
            runBlocking { chain(http, SearchProviderChoice.Tavily, "tvly-bad").search("kotlin", 3) }
            fail("expected Unauthorized")
        } catch (e: SearchError) {
            assertTrue(e is SearchError.Unauthorized)
        }
        assertEquals(1, http.urls.size)
    }

    @Test fun aKeyedChoiceWithoutAKeyBuildsNothing() {
        assertNull(providers(FakeHttp()).chain(SearchSelection(SearchProviderChoice.Tavily, null)))
        assertNull(providers(FakeHttp()).chain(SearchSelection(SearchProviderChoice.Brave, "  ")))
        assertNotNull(providers(FakeHttp()).chain(SearchSelection(SearchProviderChoice.DuckDuckGo, null)))
    }

    @Test fun theKeyGoesToTheChosenProviderOnly() {
        val http = FakeHttp(json(tavilyJson))
        runBlocking { chain(http, SearchProviderChoice.Tavily, "tvly-key").search("q", 1) }
        assertEquals(1, http.urls.size)
        assertTrue(http.posts.single().contains("\"search_depth\":\"basic\""))
    }

    // Test button

    private fun tester(http: FakeHttp, key: String? = "k", clock: () -> Long = System::nanoTime) =
        SearchTester(providers(http), { SearchSelection(it, key) }, clock)

    @Test fun theTestRunsTheChosenProviderAloneAndReportsTheTime() {
        val ticks = longArrayOf(0L, 120_000_000L).iterator()
        val http = FakeHttp(json(tavilyJson))
        val outcome = runBlocking { tester(http, clock = { ticks.next() }).run(SearchProviderChoice.Tavily) }

        assertEquals(SearchTester.Outcome.Answered(120), outcome)
        assertEquals(1, http.urls.size)
        assertEquals(SearchTestState.Answered("Tavily", 120), SearchTestState.from(SearchProviderChoice.Tavily, outcome))
    }

    @Test fun theTestDoesNotFallBack() {
        val http = FakeHttp(status(202), json(wikipediaJson))
        val outcome = runBlocking { tester(http).run(SearchProviderChoice.DuckDuckGo) }

        assertTrue(outcome is SearchTester.Outcome.Failed)
        assertEquals(1, http.urls.size)
    }

    @Test fun theTestNeedsAKey() {
        val http = FakeHttp()
        assertEquals(SearchTester.Outcome.NoKey, runBlocking { tester(http, key = null).run(SearchProviderChoice.Brave) })
        assertTrue(http.urls.isEmpty())
    }

    @Test fun failuresMapToReadableStates() {
        fun state(error: SearchError) =
            SearchTestState.from(SearchProviderChoice.Brave, SearchTester.Outcome.Failed(error)) as SearchTestState.Failed
        assertEquals(io.github.tensaiapp.R.string.tools_test_key_rejected, (state(SearchError.Unauthorized("x")).message as io.github.tensaiapp.ui.tools.UiText.Res).res)
        assertEquals(io.github.tensaiapp.R.string.tools_test_blocked, (state(SearchError.Blocked("x")).message as io.github.tensaiapp.ui.tools.UiText.Res).res)
        assertEquals(io.github.tensaiapp.R.string.tools_test_quota, (state(SearchError.QuotaExceeded("x")).message as io.github.tensaiapp.ui.tools.UiText.Res).res)
        assertEquals(io.github.tensaiapp.R.string.tools_test_network, (state(SearchError.Network("x")).message as io.github.tensaiapp.ui.tools.UiText.Res).res)
    }
}
