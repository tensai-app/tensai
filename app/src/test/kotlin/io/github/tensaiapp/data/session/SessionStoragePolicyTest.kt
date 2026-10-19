package io.github.tensaiapp.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fast, JVM-only unit tests for [SessionStoragePolicy] — plain data, no Room/Android needed. */
class SessionStoragePolicyTest {

    private fun session(
        conversationId: String,
        sizeBytes: Long = 10L,
        lastAccessedAt: Long = 0L,
    ) = SessionEntity(
        conversationId = conversationId,
        modelId = "model-1",
        filePath = "/sessions/$conversationId.state",
        nCtx = 4096,
        kvCacheType = "F16",
        backend = "CPU",
        tokenCount = 100,
        sizeBytes = sizeBytes,
        createdAt = 0L,
        lastAccessedAt = lastAccessedAt,
    )

    @Test
    fun keepsOnlyTheJustWrittenSessionWhenItIsAlone() {
        val evicted = SessionStoragePolicy.evictionPlan(listOf(session("a")), justWritten = "a")

        assertTrue(evicted.isEmpty())
    }

    @Test
    fun returnsEmptyForAnEmptySessionList() {
        val evicted = SessionStoragePolicy.evictionPlan(emptyList(), justWritten = "anything")

        assertTrue(evicted.isEmpty())
    }

    @Test
    fun neverEvictsTheJustWrittenSessionEvenIfItIsTheOnlyOneAndHuge() {
        val sessions = listOf(session("just-written", sizeBytes = 10_000_000_000L, lastAccessedAt = 1))

        val evicted = SessionStoragePolicy.evictionPlan(sessions, justWritten = "just-written")

        assertTrue(evicted.isEmpty())
    }

    // ---- LRU-2 (TEN-62): justWritten plus the single most-recently-accessed other row ----

    @Test
    fun keepsTheOnlyOtherSessionWhenExactlyTwoSessionsExist() {
        val sessions = listOf(
            session("just-written", lastAccessedAt = 2),
            session("other", lastAccessedAt = 1),
        )

        val evicted = SessionStoragePolicy.evictionPlan(sessions, justWritten = "just-written")

        assertTrue("with only two sessions total, the second slot has nothing else to evict", evicted.isEmpty())
    }

    @Test
    fun keepsTheOnlyOtherSessionRegardlessOfItsSizeWhenExactlyTwoSessionsExist() {
        val sessions = listOf(
            session("huge", sizeBytes = 10_000_000_000L, lastAccessedAt = 1),
            session("just-written", sizeBytes = 10L, lastAccessedAt = 2),
        )

        val evicted = SessionStoragePolicy.evictionPlan(sessions, justWritten = "just-written")

        assertTrue("LRU-2 keeps a second session regardless of its size", evicted.isEmpty())
    }

    @Test
    fun evictsEveryOtherSessionButTheMostRecentlyAccessedOne() {
        val sessions = listOf(
            session("oldest", lastAccessedAt = 1),
            session("middle", lastAccessedAt = 2),
            session("newest-other", lastAccessedAt = 3),
        )

        val evicted = SessionStoragePolicy.evictionPlan(sessions, justWritten = "just-written-elsewhere")

        assertEquals(setOf("oldest", "middle"), evicted.toSet())
    }

    @Test
    fun evictsEveryOtherSessionButTheMostRecentlyAccessedOneRegardlessOfSize() {
        val sessions = listOf(
            session("huge-but-stale", sizeBytes = 10_000_000_000L, lastAccessedAt = 1),
            session("tiny-but-fresh", sizeBytes = 1L, lastAccessedAt = 2),
            session("just-written", sizeBytes = 10L, lastAccessedAt = 3),
        )

        val evicted = SessionStoragePolicy.evictionPlan(sessions, justWritten = "just-written")

        assertEquals(listOf("huge-but-stale"), evicted)
    }

    @Test
    fun keepsTheJustWrittenSessionPlusTheMostRecentOtherAmongFourOrMore() {
        val sessions = listOf(
            session("a", lastAccessedAt = 1),
            session("b", lastAccessedAt = 4),
            session("c", lastAccessedAt = 2),
            session("d", lastAccessedAt = 3),
        )

        val evicted = SessionStoragePolicy.evictionPlan(sessions, justWritten = "d")

        // Kept: "d" (justWritten) and "b" (most recently accessed among the rest).
        assertEquals(setOf("a", "c"), evicted.toSet())
    }

    @Test
    fun tiedLastAccessedAtAmongOthersKeepsOnlyOneOfThemDeterministically() {
        val sessions = listOf(
            session("tied-1", lastAccessedAt = 5),
            session("tied-2", lastAccessedAt = 5),
            session("just-written", lastAccessedAt = 5),
        )

        val evicted = SessionStoragePolicy.evictionPlan(sessions, justWritten = "just-written")

        assertEquals(1, evicted.size)
        assertTrue(evicted.single() == "tied-1" || evicted.single() == "tied-2")
    }
}
