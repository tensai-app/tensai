package io.github.hokanosekai.tensai.data.session

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
    fun keepsEverythingWhenUnderBothCaps() {
        val sessions = listOf(session("a", lastAccessedAt = 1), session("b", lastAccessedAt = 2))

        val evicted = SessionStoragePolicy.evictionPlan(
            sessions,
            justWritten = "b",
            maxTotalBytes = 1_000L,
            maxSessionCount = 5,
        )

        assertTrue(evicted.isEmpty())
    }

    @Test
    fun evictsTheOldestByLastAccessedAtWhenOverTheCountCap() {
        val sessions = listOf(
            session("oldest", lastAccessedAt = 1),
            session("middle", lastAccessedAt = 2),
            session("newest", lastAccessedAt = 3),
        )

        val evicted = SessionStoragePolicy.evictionPlan(
            sessions,
            justWritten = "newest",
            maxTotalBytes = 1_000L,
            maxSessionCount = 2,
        )

        assertEquals(listOf("oldest"), evicted)
    }

    @Test
    fun evictsTheOldestFirstWhenOverTheTotalSizeCap() {
        val sessions = listOf(
            session("oldest", sizeBytes = 100L, lastAccessedAt = 1),
            session("newer", sizeBytes = 100L, lastAccessedAt = 2),
        )

        val evicted = SessionStoragePolicy.evictionPlan(
            sessions,
            justWritten = "newer",
            maxTotalBytes = 150L,
            maxSessionCount = 10,
        )

        assertEquals(listOf("oldest"), evicted)
    }

    @Test
    fun neverEvictsTheJustWrittenSessionEvenIfItAloneExceedsTheSizeCap() {
        val sessions = listOf(session("just-written", sizeBytes = 10_000L, lastAccessedAt = 1))

        val evicted = SessionStoragePolicy.evictionPlan(
            sessions,
            justWritten = "just-written",
            maxTotalBytes = 100L,
            maxSessionCount = 10,
        )

        assertTrue(evicted.isEmpty())
    }

    @Test
    fun neverEvictsTheJustWrittenSessionEvenWhenItsLastAccessedAtIsStaleCompareToOthers() {
        // Should never happen in practice (save() always bumps lastAccessedAt to "now" right
        // before this runs), but the contract is "never evict what was just written", full stop —
        // not "never evict whatever happens to be most recent".
        val sessions = listOf(
            session("just-written", lastAccessedAt = 1),
            session("actually-newer", lastAccessedAt = 100),
        )

        val evicted = SessionStoragePolicy.evictionPlan(
            sessions,
            justWritten = "just-written",
            maxTotalBytes = 1_000L,
            maxSessionCount = 1,
        )

        assertEquals(listOf("actually-newer"), evicted)
    }

    @Test
    fun evictsMultipleSessionsWhenFarOverCaps() {
        val sessions = (1..5).map { session("c$it", lastAccessedAt = it.toLong()) }

        val evicted = SessionStoragePolicy.evictionPlan(
            sessions,
            justWritten = "c5",
            maxTotalBytes = 1_000L,
            maxSessionCount = 2,
        )

        assertEquals(setOf("c1", "c2", "c3"), evicted.toSet())
    }

    @Test
    fun defaultCapsAreTheDocumentedProvisionalValues() {
        assertEquals(300L * 1024 * 1024, SessionStoragePolicy.DEFAULT_MAX_TOTAL_BYTES)
        assertEquals(3, SessionStoragePolicy.DEFAULT_MAX_SESSION_COUNT)
    }
}
