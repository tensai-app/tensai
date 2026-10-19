package io.github.tensaiapp.websearch

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackSearchTest {

    private fun hit(source: String) = listOf(SearchResult("t", "https://x.test", "s", source))

    @Test fun primaryAnswersAndFallbacksAreNotCalled() {
        val primary = StubProvider("a", hit("a"))
        val second = StubProvider("b", hit("b"))
        val results = runBlocking { FallbackSearch(primary, listOf(second)).search("q", 5) }

        assertEquals("a", results.single().source)
        assertEquals(0, second.calls)
    }

    @Test fun recoverableErrorsMoveToNextInOrder() {
        val errors = listOf(
            SearchError.Blocked("x"),
            SearchError.RateLimited(5),
            SearchError.QuotaExceeded("plan"),
            SearchError.Network("down"),
            SearchError.FormatChanged("moved"),
        )
        for (error in errors) {
            val primary = StubProvider("a", error = error)
            val second = StubProvider("b", error = SearchError.Network("also down"))
            val third = StubProvider("c", hit("c"))
            val results = runBlocking { FallbackSearch(primary, listOf(second, third)).search("q", 5) }

            assertEquals("c", results.single().source)
            assertEquals(listOf(1, 1, 1), listOf(primary.calls, second.calls, third.calls))
        }
    }

    @Test fun emptyResultMovesOn() {
        val results = runBlocking {
            FallbackSearch(StubProvider("a"), listOf(StubProvider("b", hit("b")))).search("q", 5)
        }
        assertEquals("b", results.single().source)
    }

    @Test fun unauthorizedStopsTheChain() {
        for (error in listOf(SearchError.Unauthorized("bad key"))) {
            val second = StubProvider("b", hit("b"))
            assertFails<SearchError> { runBlocking { FallbackSearch(StubProvider("a", error = error), listOf(second)).search("q", 5) } }
            assertEquals(0, second.calls)
        }
    }

    @Test fun allFailingThrowsPrimaryErrorWithOthersSuppressed() {
        val search = FallbackSearch(
            StubProvider("a", error = SearchError.RateLimited(1)),
            listOf(StubProvider("b", error = SearchError.Network("down"))),
        )
        val thrown = assertFails<SearchError.RateLimited> { runBlocking { search.search("q", 5) } }
        assertTrue(thrown.suppressed.single() is SearchError.Network)
    }

    @Test fun allEmptyWithoutErrorsIsEmpty() {
        val results = runBlocking { FallbackSearch(StubProvider("a"), listOf(StubProvider("b"))).search("q", 5) }
        assertTrue(results.isEmpty())
    }

    @Test fun nameIsThePrimarys() {
        assertEquals("a", FallbackSearch(StubProvider("a"), emptyList()).name)
    }

    @Test fun emptyAnswerBeatsAnEarlierBlock() {
        val search = FallbackSearch(
            StubProvider("a", error = SearchError.Blocked("anomaly")),
            listOf(StubProvider("b")),
        )
        assertTrue(runBlocking { search.search("q", 5) }.isEmpty())
    }

    @Test fun unauthorizedCarriesEarlierErrorsAsSuppressed() {
        val search = FallbackSearch(
            StubProvider("a", error = SearchError.Blocked("anomaly")),
            listOf(StubProvider("b", error = SearchError.Unauthorized("bad key"))),
        )
        val thrown = assertFails<SearchError.Unauthorized> { runBlocking { search.search("q", 5) } }
        assertTrue(thrown.suppressed.single() is SearchError.Blocked)
    }

    @Test fun onTryReportsEveryProviderAskedInOrderIncludingFailures() {
        val tried = mutableListOf<String>()
        val search = FallbackSearch(
            StubProvider("a", error = SearchError.Blocked("x")),
            listOf(StubProvider("b"), StubProvider("c", hit("c")), StubProvider("d", hit("d"))),
        )
        val results = runBlocking { search.search("q", 5) { tried += it } }

        assertEquals("c", results.single().source)
        assertEquals(listOf("a", "b", "c"), tried)
    }

    @Test fun onTryIsCalledEvenWhenEverythingFails() {
        val tried = mutableListOf<String>()
        val search = FallbackSearch(StubProvider("a", error = SearchError.Network("x")), listOf(StubProvider("b", error = SearchError.Network("y"))))
        assertFails<SearchError.Network> { runBlocking { search.search("q", 5) { tried += it } } }
        assertEquals(listOf("a", "b"), tried)
    }
}
