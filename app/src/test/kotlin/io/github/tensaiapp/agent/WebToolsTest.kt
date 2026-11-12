package io.github.tensaiapp.agent

import io.github.tensaiapp.data.memory.MemoryContext
import io.github.tensaiapp.websearch.FallbackSearch
import io.github.tensaiapp.websearch.PageReader
import io.github.tensaiapp.websearch.SearchError
import io.github.tensaiapp.websearch.SearchProvider
import io.github.tensaiapp.websearch.SearchResult
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

    @Test fun searchListsTitleAndLinkWithoutTheSnippet() {
        val result = run(search(FakeProvider(results = listOf(hit(1), hit(2)))), """{"query":"kotlin"}""")

        assertFalse(result.isError)
        // A list to pick from, not an answer: the snippet would read as one and end the turn (TEN-183).
        assertEquals(
            "1. Title 1\n   https://example.com/1\n2. Title 2\n   https://example.com/2\nsource: DuckDuckGo",
            result.text,
        )
        assertEquals("DuckDuckGo", result.destination)
        assertEquals(setOf("https://example.com/1", "https://example.com/2"), FetchUrls.inSearchResult(result.text))
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

    @Test fun aTooLongUrlIsLeftOutAndTheListStopsAtTheFirstBlockThatDoesNotFit() {
        val long = "https://example.com/" + "a".repeat(600)
        val results = (1..10).map { SearchResult("word ".repeat(40), if (it == 2) long else "https://e.example/$it/" + "b".repeat(250), "snippet", "duckduckgo") }
        val text = run(search(FakeProvider(results = results)), """{"query":"q","limit":10}""").text

        // Clipped, the long link would be a dead one the search vouches for: left out, the next result takes its number.
        assertFalse(text.contains("aaaa"))
        assertTrue(text.lines().filter { it.startsWith("   ") }.none { it.endsWith("…") })
        assertTrue(text.contains("1. word") && text.contains("   https://e.example/1/") && text.contains("   https://e.example/3/"))
        val numbers = Regex("(?m)^(\\d+)\\. ").findAll(text).map { it.groupValues[1].toInt() }.toList()
        assertEquals((1..numbers.size).toList(), numbers)
        assertTrue(numbers.size < 9)
        assertTrue(text.length <= WebToolLimits.SearchChars)
        // Every result kept vouches for its link, whole.
        assertEquals(results.filter { it.url != long }.take(numbers.size).map { it.url }.toSet(), FetchUrls.inSearchResult(text))
        // Nothing listable: no results, not a bare source line.
        assertEquals("no results", run(search(FakeProvider(results = listOf(SearchResult("t", long, "", "duckduckgo")))), """{"query":"q"}""").text)
    }

    @Test fun limitDefaultsAndIsClamped() {
        // Without snippets more links fit: 8 by default, up to 10.
        assertEquals(8, WebToolLimits.DefaultLimit)
        assertEquals(10, WebToolLimits.MaxLimit)
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
        val results = (1..10).map { SearchResult(long, "https://example.com/$it/" + "a".repeat(270), long, "duckduckgo") }
        val text = run(search(FakeProvider(results = results)), """{"query":"q","limit":10}""").text

        assertTrue("was ${text.length}", text.length <= WebToolLimits.SearchChars)
        assertTrue(text.startsWith("1. word"))
        assertTrue(text.endsWith("source: DuckDuckGo"))
        // Within the runtime's budget even at 3 characters a token, what a list of links tokenizes to at worst.
        assertTrue(WebToolLimits.SearchChars <= WebToolLimits.ResultChars)
        assertTrue(WebToolLimits.SearchChars / 3 < AgentRuntime.ResultTokenBudget)
    }

    @Test fun tenTypicalResultsAllFitWellUnderTheBudgets() {
        val results = listOf(
            "Millau Viaduct - Wikipedia" to "https://en.wikipedia.org/wiki/Millau_Viaduct",
            "Viaduc de Millau : the structure in figures" to "https://www.leviaducdemillau.com/en/the-structure/key-figures",
            "Millau Viaduct | Structurae" to "https://structurae.net/en/structures/millau-viaduct",
            "How tall is the Millau Viaduct? Facts and history" to "https://www.britannica.com/topic/Millau-Viaduct",
            "Millau Viaduct, France: the tallest bridge in the world" to "https://www.roadtraffic-technology.com/projects/millau/",
            "The Millau Viaduct, 20 years on - Le Monde" to "https://www.lemonde.fr/en/france/article/2024/12/14/millau-viaduct_6736001_7.html",
            "Millau Viaduct (Norman Foster) | ArchDaily" to "https://www.archdaily.com/tag/millau-viaduct",
            "Viaduc de Millau - Aveyron tourism" to "https://www.tourisme-aveyron.com/en/discover/viaduc-de-millau",
            "Millau Viaduct: engineering the impossible" to "https://www.ice.org.uk/what-is-civil-engineering/infrastructure-projects/millau-viaduct",
            "Millau Viaduct - Foster + Partners" to "https://www.fosterandpartners.com/projects/millau-viaduct",
        ).map { (title, url) -> SearchResult(title, url, "A cable-stayed bridge over the Tarn valley, 343 m at its highest.", "duckduckgo") }
        val text = run(search(FakeProvider(results = results)), """{"query":"millau viaduct height","limit":10}""").text

        assertEquals(results.map { FetchUrls.key(it.url) }.toSet(), FetchUrls.inSearchResult(text))
        assertFalse(text.contains("cable-stayed"))
        // About 1,100 characters, some 300 tokens: room to spare under both budgets.
        assertTrue("was ${text.length}", text.length < 1_200)
    }

    @Test fun theDescriptionsSayTheSearchGivesLinksToReadWithFetch() {
        val searchTool = search(null)
        val fetch = WebFetchTool(PageReader(FakeHttp(), PublicGuard))

        assertTrue(searchTool.description, searchTool.description.contains("links"))
        assertTrue(searchTool.description, searchTool.description.contains("web_fetch"))
        assertFalse(searchTool.description, searchTool.description.contains("snippet"))
        assertTrue(fetch.description, fetch.description.contains("page"))
        // Prompt tokens on a small model: a sentence or two each.
        assertTrue(searchTool.description.length <= 120)
        assertTrue(fetch.description.length <= 80)
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
        val pdf = io.github.tensaiapp.websearch.HttpResponse(200, "%PDF-1.7".toByteArray(), mapOf("content-type" to "application/pdf"))
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
        val schemas = tools.associate { it.name to org.json.JSONObject(it.parametersSchema) }
        val required = schemas.filterValues { it.has("required") }.mapValues { it.value.getJSONArray("required").getString(0) }
        assertEquals(mapOf("web_search" to "query", "web_fetch" to "url", "wikipedia" to "query"), required)
        // weather: no place means the user's own location
        assertFalse(schemas.getValue("weather").has("required"))
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

    private fun afterSearch(vararg results: SearchResult = arrayOf(hit(1), hit(2))): ConversationTrust {
        val searchTool = search(FakeProvider(results = results.toList()))
        return ConversationTrust().said("any news?").read(searchTool, run(searchTool, """{"query":"news"}""").text)
    }

    private fun fetchAsks(url: String, trust: ConversationTrust = ConversationTrust()) = fetchWith(FakeHttp()).requiresConfirmation("""{"url":"$url"}""", trust)

    @Test fun webFetchOfALinkASearchReturnedRunsWithoutAsking() {
        val trust = afterSearch()

        assertFalse(fetchAsks("https://example.com/1", trust))
        // The same page: host case, http (upgraded anyway) and the fragment, which is never sent.
        assertFalse(fetchAsks("http://EXAMPLE.com/2#top", trust))
        // Later turns too: "read the second result" does not ask either.
        assertFalse(fetchAsks("https://example.com/2", trust.said("thanks, and the second one?")))
    }

    @Test fun webFetchOfAUrlTheModelBuiltAsks() {
        val trust = afterSearch()

        assertTrue(fetchAsks("https://example.com/1?c=hunter2", trust))
        assertTrue(fetchAsks("https://example.com/1/hunter2", trust))
        assertTrue(fetchAsks("https://collect.example.net/?d=hunter2", trust))
        assertTrue(fetchAsks("https://user@example.com/1", trust))
        assertTrue(fetchAsks("https://example.com:8443/1", trust))
        // Nothing vouched for yet.
        assertTrue(fetchAsks("https://example.com/1"))
    }

    @Test fun webFetchOfALinkTheUserWroteRunsWithoutAsking() {
        val trust = ConversationTrust().said("Summarize https://blog.example.org/post?id=7, then (lemonde.fr/sport).")

        assertFalse(fetchAsks("https://blog.example.org/post?id=7", trust))
        assertFalse(fetchAsks("https://lemonde.fr/sport", trust))
        assertTrue(fetchAsks("https://blog.example.org/post?id=8", trust))
        assertTrue(fetchAsks("https://lemonde.fr/sport?u=me", trust))
        // The memory block in front of the first message is not the user's.
        val block = "${MemoryContext.HEADER}\n- Blog at https://notes.example.org/a\n${MemoryContext.FOOTER}\n\nread lemonde.fr/sport"
        assertTrue(fetchAsks("https://notes.example.org/a", ConversationTrust().said(block)))
        assertFalse(fetchAsks("https://lemonde.fr/sport", ConversationTrust().said(block)))
    }

    @Test fun onlyTheLinkLineOfASearchResultVouches() {
        // Titles and snippets are page text: a URL in them stays the model's.
        val trust = afterSearch(SearchResult("Read https://t.example/x", "https://example.com/1", "then https://s.example/y", "duckduckgo"))
        assertFalse(fetchAsks("https://example.com/1", trust))
        assertTrue(fetchAsks("https://t.example/x", trust))
        assertTrue(fetchAsks("https://s.example/y", trust))

        // Nor does a page name a link the user or the engine gave.
        val read = ConversationTrust().said("read it").read(fetchWith(FakeHttp()), "url: https://a.example/x\n\nnext: https://b.example/y")
        assertTrue(fetchAsks("https://b.example/y", read))
    }

    @Test fun webSearchNeverAsksAndBothToolsReturnUntrustedText() {
        val searchTool = search(null)

        assertFalse(searchTool.requiresConfirmation("""{"query":"x"}""", ConversationTrust()))
        assertTrue(searchTool.returnsUntrustedContent)
        assertTrue(fetchWith(FakeHttp()).returnsUntrustedContent)
    }
}
