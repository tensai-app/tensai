package io.github.tensaiapp.websearch

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidersTest {

    // DuckDuckGo

    @Test fun ddgParsesResultsDropsAdsAndUnwrapsRedirects() {
        val fetcher = FakeFetcher(ok(fixture("ddg-results.html")))
        val results = runBlocking { DuckDuckGoHtml(fetcher).search("kotlin coroutines", 10) }

        assertEquals(
            listOf(
                "https://kotlinlang.org/docs/coroutines-guide.html",
                "https://github.com/Kotlin/kotlinx.coroutines",
                "https://en.wikipedia.org/wiki/Coroutine?q=a&b=1",
            ),
            results.map { it.url },
        )
        assertTrue(results.none { it.url.contains("y.js") || it.title.contains("Buy") })
        assertEquals("Coroutines guide | Kotlin Documentation", results[0].title)
        assertTrue(results[0].snippet.endsWith("utilize coroutines."))
        assertEquals("duckduckgo", results[0].source)
    }

    @Test fun ddgPostsFormWithMobileUserAgent() {
        val fetcher = FakeFetcher(ok(fixture("ddg-results.html")))
        runBlocking { DuckDuckGoHtml(fetcher).search("a b&c", 3) }

        val request = fetcher.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://html.duckduckgo.com/html/", request.url)
        assertTrue(request.body!!.startsWith("q=a+b%26c&"))
        assertEquals("application/x-www-form-urlencoded", request.headers["content-type"])
        assertTrue(request.headers.getValue("User-Agent").contains("Mobile"))
    }

    @Test fun ddgHonorsLimit() {
        val results = runBlocking { DuckDuckGoHtml(FakeFetcher(ok(fixture("ddg-results.html")))).search("q", 2) }
        assertEquals(2, results.size)
    }

    @Test fun ddgAnomalyPageIsBlocked() {
        assertFails<SearchError.Blocked> {
            runBlocking { DuckDuckGoHtml(FakeFetcher(ok(fixture("ddg-anomaly.html")))).search("q", 5) }
        }
    }

    @Test fun ddg202IsRateLimited() {
        assertFails<SearchError.RateLimited> {
            runBlocking { DuckDuckGoHtml(FakeFetcher(status(202))).search("q", 5) }
        }
    }

    @Test fun ddgNoResultsPageIsEmptyNotAnError() {
        val results = runBlocking { DuckDuckGoHtml(FakeFetcher(ok(fixture("ddg-no-results.html")))).search("zzqxv", 5) }
        assertTrue(results.isEmpty())
    }

    @Test fun ddgUnknownLayoutIsFormatChanged() {
        assertFails<SearchError.FormatChanged> {
            runBlocking { DuckDuckGoHtml(FakeFetcher(ok("<html><body><p>hello</p></body></html>"))).search("q", 5) }
        }
    }

    @Test fun ioFailureIsNetworkError() {
        assertFails<SearchError.Network> {
            runBlocking { DuckDuckGoHtml(FakeFetcher(IOException("timeout"))).search("q", 5) }
        }
    }

    // Wikipedia

    @Test fun wikipediaParsesPagesAndBuildsUrls() {
        val fetcher = FakeFetcher(ok(fixture("wikipedia.json")))
        val results = runBlocking { Wikipedia(fetcher, "tensai/0.3 (repo; me)", "fr").search("kotlin", 5) }

        assertEquals("https://fr.wikipedia.org/w/rest.php/v1/search/page?q=kotlin&limit=5", fetcher.requests.single().url)
        assertEquals("tensai/0.3 (repo; me)", fetcher.requests.single().headers["User-Agent"])
        assertEquals("https://fr.wikipedia.org/wiki/Kotlin_%28programming_language%29", results[0].url)
        // searchmatch markup stripped
        assertTrue(results[0].snippet.startsWith("Kotlin is a cross-platform"))
        // empty excerpt falls back to description
        assertEquals("Island in the Gulf of Finland", results[1].snippet)
        assertEquals("wikipedia", results[0].source)
    }

    @Test fun wikipedia403IsBlocked() {
        assertFails<SearchError.Blocked> { runBlocking { Wikipedia(FakeFetcher(status(403)), "ua").search("q", 3) } }
    }

    @Test fun wikipediaGarbageIsFormatChanged() {
        assertFails<SearchError.FormatChanged> {
            runBlocking { Wikipedia(FakeFetcher(ok("<html>not json</html>")), "ua").search("q", 3) }
        }
        assertFails<SearchError.FormatChanged> {
            runBlocking { Wikipedia(FakeFetcher(ok("""{"other": []}""")), "ua").search("q", 3) }
        }
    }

    // Tavily

    @Test fun tavilyParsesAndSendsBasicDepth() {
        val fetcher = FakeFetcher(ok(fixture("tavily.json")))
        val results = runBlocking { Tavily(fetcher, "tvly-secret").search("kotlin coroutines", 2) }

        val request = fetcher.requests.single()
        assertEquals("https://api.tavily.com/search", request.url)
        assertEquals("Bearer tvly-secret", request.headers["Authorization"])
        assertTrue(request.body!!.contains("\"search_depth\":\"basic\""))
        assertTrue(request.body!!.contains("\"max_results\":2"))
        assertEquals(2, results.size)
        assertEquals("https://kotlinlang.org/docs/coroutines-guide.html", results[0].url)
        assertEquals("tavily", results[0].source)
    }

    @Test fun tavilyStatusMapping() {
        assertFails<SearchError.Unauthorized> { runBlocking { Tavily(FakeFetcher(status(401)), "k").search("q", 1) } }
        val limited = assertFails<SearchError.RateLimited> {
            runBlocking { Tavily(FakeFetcher(status(429, "", "retry-after" to "30")), "k").search("q", 1) }
        }
        assertEquals(30L, limited.retryAfterSeconds)
        assertFails<SearchError.QuotaExceeded> { runBlocking { Tavily(FakeFetcher(status(432)), "k").search("q", 1) } }
        assertFails<SearchError.QuotaExceeded> { runBlocking { Tavily(FakeFetcher(status(433)), "k").search("q", 1) } }
        assertFails<SearchError.Network> { runBlocking { Tavily(FakeFetcher(status(500)), "k").search("q", 1) } }
    }

    // Brave

    @Test fun braveParsesAndStripsMarkup() {
        val fetcher = FakeFetcher(ok(fixture("brave.json")))
        val results = runBlocking { Brave(fetcher, "brave-key").search("kotlin coroutines", 5) }

        val request = fetcher.requests.single()
        assertEquals("GET", request.method)
        assertEquals(
            "https://api.search.brave.com/res/v1/web/search?q=kotlin+coroutines&count=5",
            request.url,
        )
        assertEquals("brave-key", request.headers["X-Subscription-Token"])
        assertEquals("Kotlin provides only minimal low-level APIs to enable coroutines & more.", results[0].snippet)
        assertEquals("brave", results[1].source)
    }

    @Test fun braveCountIsCappedAndEmptyWebIsEmpty() {
        val fetcher = FakeFetcher(ok("""{"type": "search", "query": {"original": "x"}}"""))
        val results = runBlocking { Brave(fetcher, "k").search("x", 50) }
        assertTrue(results.isEmpty())
        assertTrue(fetcher.requests.single().url.endsWith("count=20"))
    }

    @Test fun braveStatusMapping() {
        assertFails<SearchError.Unauthorized> { runBlocking { Brave(FakeFetcher(status(401)), "k").search("q", 1) } }
        assertFails<SearchError.RateLimited> { runBlocking { Brave(FakeFetcher(status(429)), "k").search("q", 1) } }
        assertFails<SearchError.FormatChanged> { runBlocking { Brave(FakeFetcher(ok("{}")), "k").search("q", 1) } }
    }

    // Marginalia

    @Test fun marginaliaParsesWithPublicKeyByDefault() {
        val fetcher = FakeFetcher(ok(fixture("marginalia.json")))
        val results = runBlocking { Marginalia(fetcher).search("kotlin coroutines", 2) }

        val request = fetcher.requests.single()
        assertEquals(
            "https://api2.marginalia-search.com/search?query=kotlin+coroutines&count=2",
            request.url,
        )
        assertEquals("public", request.headers["API-Key"])
        assertEquals("Coroutines from scratch", results[0].title)
        assertEquals("marginalia", results[0].source)
    }

    @Test fun marginalia503IsRateLimited() {
        assertFails<SearchError.RateLimited> { runBlocking { Marginalia(FakeFetcher(status(503))).search("q", 1) } }
        assertFails<SearchError.Unauthorized> { runBlocking { Marginalia(FakeFetcher(status(401))).search("q", 1) } }
    }

    // Review additions

    @Test fun ddgResultMentioningAnomalyIsNotBlocked() {
        val html = fixture("ddg-results.html")
            .replace("Library support for Kotlin coroutines with multiplatform support.", "Anomaly detection with coroutines.")
        val results = runBlocking { DuckDuckGoHtml(FakeFetcher(ok(html))).search("q", 5) }
        assertEquals(3, results.size)
        assertTrue(results.any { it.snippet.startsWith("Anomaly detection") })
    }

    @Test fun ddgMalformedRedirectEscapeSkipsOnlyThatResult() {
        val html = fixture("ddg-results.html").replace("https%3A%2F%2Fkotlinlang.org%2Fdocs%2Fcoroutines-guide.html", "https%3A%2F%2Fbad%ZZ")
        val results = runBlocking { DuckDuckGoHtml(FakeFetcher(ok(html))).search("q", 10) }
        assertEquals(2, results.size)
        assertTrue(results.none { it.url.contains("bad") })
    }

    @Test fun wikipediaLanguageIsValidated() {
        for (bad in listOf("", "e", "en.evil.com", "EN", "en/x", "a".repeat(13))) {
            try {
                Wikipedia(FakeFetcher(), "ua", bad)
                throw AssertionError("accepted $bad")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        Wikipedia(FakeFetcher(), "ua", "zh-min-nan")
    }

    @Test fun tavilyMaxResultsIsClamped() {
        val low = FakeFetcher(ok(fixture("tavily.json")))
        runBlocking { Tavily(low, "k").search("q", 0) }
        assertTrue(low.requests.single().body!!.contains("\"max_results\":1"))
        val high = FakeFetcher(ok(fixture("tavily.json")))
        runBlocking { Tavily(high, "k").search("q", 99) }
        assertTrue(high.requests.single().body!!.contains("\"max_results\":20"))
    }

    @Test fun braveExtraStatusMapping() {
        assertFails<SearchError.Unauthorized> { runBlocking { Brave(FakeFetcher(status(422)), "k").search("q", 1) } }
        assertFails<SearchError.QuotaExceeded> { runBlocking { Brave(FakeFetcher(status(402)), "k").search("q", 1) } }
    }
}
