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
    fun keepsOnlyTheJustWrittenSessionWhenItIsAlone() {
        val evicted = SessionStoragePolicy.evictionPlan(listOf(session("a")), justWritten = "a")

        assertTrue(evicted.isEmpty())
    }

    @Test
    fun evictsEveryOtherSessionRegardlessOfRecency() {
        val sessions = listOf(
            session("oldest", lastAccessedAt = 1),
            session("middle", lastAccessedAt = 2),
            session("newest", lastAccessedAt = 3),
        )

        val evicted = SessionStoragePolicy.evictionPlan(sessions, justWritten = "newest")

        assertEquals(setOf("oldest", "middle"), evicted.toSet())
    }

    @Test
    fun evictsEveryOtherSessionRegardlessOfSize() {
        val sessions = listOf(
            session("huge", sizeBytes = 10_000_000L, lastAccessedAt = 1),
            session("just-written", sizeBytes = 10L, lastAccessedAt = 2),
        )

        val evicted = SessionStoragePolicy.evictionPlan(sessions, justWritten = "just-written")

        assertEquals(listOf("huge"), evicted)
    }

    @Test
    fun neverEvictsTheJustWrittenSessionEvenIfItIsTheOnlyOneAndHuge() {
        val sessions = listOf(session("just-written", sizeBytes = 10_000_000_000L, lastAccessedAt = 1))

        val evicted = SessionStoragePolicy.evictionPlan(sessions, justWritten = "just-written")

        assertTrue(evicted.isEmpty())
    }

    @Test
    fun returnsEmptyForAnEmptySessionList() {
        val evicted = SessionStoragePolicy.evictionPlan(emptyList(), justWritten = "anything")

        assertTrue(evicted.isEmpty())
    }
}
