package io.github.hokanosekai.tensai.agent

import io.github.hokanosekai.websearch.FallbackSearch
import io.github.hokanosekai.websearch.PageReader
import io.github.hokanosekai.websearch.SearchError
import io.github.hokanosekai.websearch.SearchProvider
import io.github.hokanosekai.websearch.SearchResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `web_search` and `web_fetch` (TEN-181), against fake providers and a fake HTTP client. */
class WebToolsTest {

    private fun search(provider: SearchProvider?, chosen: String = "DuckDuckGo") =
        WebSearchTool { WebSearchTool.Setup(provider?.let { FallbackSearch(it, emptyList()) }, chosen) }

    private fun run(tool: Tool, args: String) = runBlocking { tool.execute(args) }

    @Test fun searchListsTitleLinkSnippetAndSource() {
        val result = run(search(FakeProvider(results = listOf(hit(1), hit(2)))), """{"query":"kotlin"}""")

        assertFalse(result.isError)
        assertEquals(
            "1. Title 1\n   https://example.com/1\n   snippet 1\n2. Title 2\n   https://example.com/2\n   snippet 2\nsource: DuckDuckGo",
            result.text,
        )
        assertEquals("DuckDuckGo", result.destination)
    }

    @Test fun destinationListsEveryProviderTheQueryWentTo() {
        val chain = FallbackSearch(
            FakeProvider("duckduckgo", error = SearchError.Blocked("anomaly")),
            listOf(FakeProvider("marginalia"), FakeProvider("wikipedia", results = listOf(hit(1, source = "wikipedia")))),
        )
        val result = run(WebSearchTool { WebSearchTool.Setup(chain, "DuckDuckGo") }, """{"query":"q"}""")

        assertEquals("DuckDuckGo, Marginalia, Wikipedia", result.destination)
        assertTrue(result.text.endsWith("source: Wikipedia"))
    }

    @Test fun destinationOfAFailedChainStillListsWhatWasTried() {
        val chain = FallbackSearch(
            FakeProvider("duckduckgo", error = SearchError.Blocked("anomaly")),
            listOf(FakeProvider("wikipedia", error = SearchError.Network("down"))),
        )
        val result = run(WebSearchTool { WebSearchTool.Setup(chain, "DuckDuckGo") }, """{"query":"q"}""")

        assertTrue(result.isError)
        assertEquals("DuckDuckGo, Wikipedia", result.destination)
    }

    @Test fun clipNeverSplitsASurrogatePair() {
        for (max in 5..12) {
            val clipped = WebToolLimits.clip("😀".repeat(30), max)
            val body = clipped.dropLast(1)
            assertFalse("max=$max", body.isNotEmpty() && Character.isHighSurrogate(body.last()))
            assertTrue(clipped.length <= max)
        }
        assertEquals("short", WebToolLimits.clip("  short \n", 50))
    }

    @Test fun urlsAreClippedAndTheListStopsAtTheFirstBlockThatDoesNotFit() {
        val long = "https://example.com/" + "a".repeat(600)
        val results = (1..8).map { SearchResult("T$it", if (it == 1) long else "https://e.example/$it", "word ".repeat(100), "duckduckgo") }
        val text = run(search(FakeProvider(results = results)), """{"query":"q","limit":8}""").text

        assertTrue(text.lines().all { it.length <= WebToolLimits.UrlChars + 3 + 1 })
        val numbers = Regex("(?m)^(\\d+)\\. ").findAll(text).map { it.groupValues[1].toInt() }.toList()
        assertEquals((1..numbers.size).toList(), numbers)
        assertTrue(numbers.size < 8)
        assertTrue(text.length <= WebToolLimits.ResultChars)
    }

    @Test fun limitDefaultsAndIsClamped() {
        val provider = FakeProvider(results = listOf(hit(1)))
        run(search(provider), """{"query":"q"}""")
        assertEquals(WebToolLimits.DefaultLimit, provider.lastLimit)
        run(search(provider), """{"query":"q","limit":99}""")
        assertEquals(WebToolLimits.MaxLimit, provider.lastLimit)
        run(search(provider), """{"query":"q","limit":0}""")
        assertEquals(1, provider.lastLimit)
    }

    @Test fun anAnswerStaysUnderTheResultBudget() {
        val long = "word ".repeat(200)
        val results = (1..8).map { hit(it, snippet = long) }
        val text = run(search(FakeProvider(results = results)), """{"query":"q","limit":8}""").text

        assertTrue("was ${text.length}", text.length <= WebToolLimits.ResultChars)
        assertTrue(text.startsWith("1. Title 1"))
        assertTrue(text.endsWith("source: DuckDuckGo"))
    }

    @Test fun noResultsIsNotAnError() {
        val result = run(search(FakeProvider()), """{"query":"zzz"}""")
        assertFalse(result.isError)
        assertEquals("no results", result.text)
        assertEquals("DuckDuckGo", result.destination)
    }

    @Test fun providerErrorsBecomeClearMessagesWithTheChosenProviderAsDestination() {
        val cases = mapOf(
            SearchError.Blocked("anomaly") to "blocked",
            SearchError.RateLimited(30) to "rate limiting",
            SearchError.QuotaExceeded("plan") to "quota",
            SearchError.FormatChanged("x") to "format",
            SearchError.Network("down") to "network error",
        )
        for ((error, fragment) in cases) {
            val result = run(search(FakeProvider(error = error)), """{"query":"q"}""")
            assertTrue(result.isError)
            assertTrue("${result.text} should mention $fragment", result.text.contains(fragment))
            assertEquals("DuckDuckGo", result.destination)
        }
    }

    @Test fun aRejectedKeySendsTheUserToSettings() {
        val result = run(search(FakeProvider(error = SearchError.Unauthorized("401")), chosen = "Tavily"), """{"query":"q"}""")
        assertTrue(result.isError)
        assertTrue(result.text.contains("Tavily rejected the API key"))
        assertTrue(result.text.contains("Settings"))
    }

    @Test fun aMissingKeyIsAnErrorNotASilentSwitch() {
        val result = run(search(null, chosen = "Brave"), """{"query":"q"}""")
        assertTrue(result.isError)
        assertTrue(result.text.contains("Brave needs an API key"))
        assertNull("nothing is sent without a key", result.destination)
    }

    @Test fun badArgumentsAreRefusedWithoutCallingTheProvider() {
        val provider = FakeProvider(results = listOf(hit(1)))
        assertTrue(run(search(provider), "not json").isError)
        assertTrue(run(search(provider), """{"query":"  "}""").isError)
        assertNull(provider.lastQuery)
    }

    // web_fetch

    private val article = "<html><head><title>Hello</title></head><body><article><p>Some readable text.</p></article></body></html>"

    private fun fetchTool(vararg responses: Any, maxChars: Int = WebToolLimits.PageChars) =
        WebFetchTool(PageReader(FakeHttp(*responses), PublicGuard, httpsOnly = true), maxChars)

    @Test fun fetchReturnsTheTextAndTheHostAsDestination() {
        val result = run(fetchTool(html(article)), """{"url":"https://www.example.com/post"}""")

        assertFalse(result.isError)
        assertEquals("url: https://www.example.com/post\n\nHello\n\nSome readable text.", result.text)
        assertEquals("example.com", result.destination)
    }

    @Test fun fetchTruncatesToTheBudgetAndSaysSo() {
        val big = "<html><body><p>" + "lorem ipsum ".repeat(500) + "</p></body></html>"
        val result = run(fetchTool(html(big)), """{"url":"https://example.com/big"}""")

        assertTrue(result.text.length <= WebToolLimits.ResultChars)
        assertTrue(result.text.endsWith("[page cut]"))
    }

    @Test fun fetchRefusesUnsafeUrlsWithoutADestination() {
        for (url in listOf("http://127.0.0.1/", "http://169.254.169.254/latest", "file:///etc/passwd", "http://192.168.1.1/admin")) {
            val result = run(fetchTool(), """{"url":"$url"}""")
            assertTrue(url, result.isError)
            assertTrue(result.text.contains("not allowed"))
            assertNull("nothing left the phone for $url", result.destination)
        }
    }

    @Test fun fetchReportsABotWallAsBlocked() {
        val wall = "<html><head><title>Just a moment...</title></head><body>Checking</body></html>"
        val result = run(fetchTool(html(wall)), """{"url":"https://example.com/"}""")

        assertTrue(result.isError)
        assertTrue(result.text.contains("blocked"))
        assertEquals("example.com", result.destination)
    }

    @Test fun fetchReportsUnreadableContent() {
        val pdf = io.github.hokanosekai.websearch.HttpResponse(200, "%PDF-1.7".toByteArray(), mapOf("content-type" to "application/pdf"))
        val result = run(fetchTool(pdf), """{"url":"https://example.com/a.pdf"}""")
        assertTrue(result.isError)
        assertTrue(result.text.contains("cannot be read as text"))
    }

    // registry

    @Test fun theInternetToolsAreAbsentWhileTheTierIsOff() {
        val tools = listOf(
            search(FakeProvider()),
            fetchTool(),
            WeatherTool(FakeHttp()),
            WikipediaTool(FakeHttp(), "ua"),
        )
        assertEquals(setOf(ToolTier.Network), tools.map { it.tier }.toSet())

        val off = ToolRegistry(tools) { DefaultTierGrants.granted() }
        assertTrue(off.active().isEmpty())
        assertEquals(listOf("web_search", "web_fetch", "weather", "wikipedia"), off.registered().map { it.name })

        val on = ToolRegistry(tools) { DefaultTierGrants.granted() + ToolTier.Network }
        assertEquals(listOf("web_search", "web_fetch", "weather", "wikipedia"), on.active().map { it.name })
    }

    @Test fun everyToolDeclaresItsRequiredArguments() {
        val tools = listOf(search(FakeProvider()), fetchTool(), WeatherTool(FakeHttp()), WikipediaTool(FakeHttp(), "ua"))
        val required = tools.associate { it.name to org.json.JSONObject(it.parametersSchema).getJSONArray("required").getString(0) }
        assertEquals(mapOf("web_search" to "query", "web_fetch" to "url", "weather" to "place", "wikipedia" to "query"), required)
    }

    // web_fetch over https only

    private fun fetchWith(http: FakeHttp) = WebFetchTool(PageReader(http, PublicGuard, httpsOnly = true))

    @Test fun anHttpUrlIsUpgradedToHttpsBeforeAnything() {
        val http = FakeHttp(html(article))
        val result = run(fetchWith(http), """{"url":"HTTP://example.com/a"}""")

        assertFalse(result.isError)
        assertEquals(listOf("https://example.com/a"), http.urls)
        assertEquals("example.com", result.destination)
    }

    @Test fun anHttpOnlySiteGetsAClearMessage() {
        val result = run(fetchWith(FakeHttp(java.io.IOException("connect failed"))), """{"url":"http://old.example/page"}""")

        assertTrue(result.isError)
        assertTrue(result.text, result.text.contains("only be served over http, which is not supported"))
        assertEquals("old.example", result.destination)
        assertTrue(WebFetchTool(PageReader(FakeHttp(), PublicGuard)).description.contains("https URL"))
    }

    @Test fun anHttpsFailureIsAnOrdinaryNetworkError() {
        val result = run(fetchWith(FakeHttp(java.io.IOException("timeout"))), """{"url":"https://example.com/"}""")
        assertTrue(result.text.contains("network error"))
        assertFalse(result.text.contains("only be served over http"))
    }

    @Test fun aRedirectToPlainHttpIsRefusedAndTheStartHostIsShown() {
        val http = FakeHttp(status(301, "location" to "http://example.com/plain"))
        val result = run(fetchWith(http), """{"url":"https://example.com/a"}""")

        assertTrue(result.isError)
        assertTrue(result.text.contains("redirects to plain http"))
        assertEquals("example.com", result.destination)
        assertEquals(1, http.urls.size)
    }

    @Test fun aRefusedRedirectHopKeepsTheDestinationBecauseTheFirstRequestWentOut() {
        val result = run(fetchWith(FakeHttp(status(302, "location" to "https://192.168.1.1/"))), """{"url":"https://example.com/a"}""")

        assertTrue(result.isError)
        assertEquals("example.com", result.destination)
    }

    @Test fun theDestinationIsTheHostTheRequestEndedAt() {
        val http = FakeHttp(status(302, "location" to "https://docs.example.org/b"), html(article))
        assertEquals("docs.example.org", run(fetchWith(http), """{"url":"https://example.com/a"}""").destination)
    }

    @Test fun internationalizedHostsUseTheirPunycodeForm() {
        val result = run(fetchWith(FakeHttp(html(article))), "{\"url\":\"https://bücher.example/x\"}")
        assertEquals("xn--bcher-kva.example", result.destination)
    }

    @Test fun hostOfFallsBackToTheRawAuthority() {
        val tool = fetchWith(FakeHttp())
        assertEquals("my_host.example", tool.hostOf("https://my_host.example:8443/x?y=1"))
        assertEquals("example.com", tool.hostOf("https://user@www.example.com/"))
        assertNull(tool.hostOf("not a url"))
    }
}
