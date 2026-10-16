package io.github.hokanosekai.tensai.agent

import io.github.hokanosekai.tensai.data.memory.MemoryDao
import io.github.hokanosekai.tensai.data.memory.MemoryFactEntity
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `memory` tool (TEN-176) against an in-memory [MemoryDao]. */
class MemoryToolTest {

    /** Same contract as the Room DAO: auto ids from 1, newest first, NOCASE text lookup. */
    private class FakeMemoryDao : MemoryDao {
        val rows = mutableListOf<MemoryFactEntity>()
        private var next = 1L

        override suspend fun insert(fact: MemoryFactEntity): Long {
            val saved = fact.copy(id = next++)
            rows += saved
            return saved.id
        }

        private fun newestFirst() = rows.sortedWith(compareByDescending<MemoryFactEntity> { it.createdAt }.thenByDescending { it.id })

        override suspend fun recent(limit: Int) = newestFirst().take(limit)
        override fun observeAll(): Flow<List<MemoryFactEntity>> = flowOf(newestFirst())
        override suspend fun count() = rows.size
        override fun observeCount(): Flow<Int> = flowOf(rows.size)
        override suspend fun findIdByText(text: String) = rows.firstOrNull { it.text.equals(text, ignoreCase = true) }?.id
        override suspend fun delete(id: Long) = if (rows.removeAll { it.id == id }) 1 else 0
        override suspend fun deleteAll() = rows.clear()
    }

    private val dao = FakeMemoryDao()
    private var clock = 1_760_000_000_000L
    private val tool = MemoryTool(dao, now = { clock++ }, conversationId = { "conv-1" }, zone = { ZoneId.of("UTC") })

    private fun run(args: String) = runBlocking { tool.execute(args) }

    @Test
    fun rememberStoresTheFactWithItsSource() {
        val result = run("""{"action":"remember","text":"  The user   prefers Fish\nshell "}""")

        assertEquals("remembered (id 1)", result.text)
        val row = dao.rows.single()
        assertEquals("The user prefers Fish shell", row.text)
        assertEquals("conv-1", row.sourceConversationId)
    }

    @Test
    fun rememberingTheSameFactTwiceDoesNotStackCopies() {
        run("""{"action":"remember","text":"Likes tea"}""")

        assertEquals("already remembered (id 1)", run("""{"action":"remember","text":"likes TEA"}""").text)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun rememberRefusesEmptyAndTooLongText() {
        assertTrue(run("""{"action":"remember"}""").isError)
        assertTrue(run("""{"action":"remember","text":"   "}""").isError)

        val long = run("""{"action":"remember","text":"${"x".repeat(MemoryTool.MAX_FACT_CHARS + 1)}"}""")
        assertTrue(long.isError)
        assertTrue(long.text.contains("too long"))
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun listAnswersNewestFirstWithIdsAndDates() {
        assertEquals("nothing remembered yet", run("""{"action":"list"}""").text)
        run("""{"action":"remember","text":"first"}""")
        run("""{"action":"remember","text":"second"}""")

        val lines = run("""{"action":"list"}""").text.lines()

        assertEquals(listOf("2 | 2025-10-09 | second", "1 | 2025-10-09 | first"), lines)
    }

    @Test
    fun listIsCutByLengthKeepsEveryLineWholeAndSaysHowManyAreShown() {
        val fact = "x".repeat(MemoryTool.MAX_FACT_CHARS - 8)
        repeat(30) { run("""{"action":"remember","text":"$it $fact"}""") }

        val text = run("""{"action":"list"}""").text
        val lines = text.lines()
        val shown = lines.size - 1

        assertTrue(text.length <= MemoryTool.MAX_LIST_CHARS + 40)
        assertTrue(shown in 1..29)
        assertEquals("($shown of 30 facts shown)", lines.last())
        // Newest first, and each kept line is whole: it still carries its id and its full text.
        assertTrue(lines.first().startsWith("30 | "))
        assertTrue(lines.dropLast(1).all { it.endsWith(fact) })
    }

    @Test
    fun listIsCappedAtTheRowLimitForShortFacts() {
        repeat(MemoryTool.MAX_LISTED + 5) { run("""{"action":"remember","text":"f$it"}""") }

        val lines = run("""{"action":"list"}""").text.lines()

        assertEquals(MemoryTool.MAX_LISTED + 1, lines.size)
        assertEquals("(50 of 55 facts shown)", lines.last())
    }

    @Test
    fun forgetDeletesOneFactOrExplainsTheMiss() {
        run("""{"action":"remember","text":"a"}""")
        run("""{"action":"remember","text":"b"}""")

        assertEquals("forgotten (id 1)", run("""{"action":"forget","id":1}""").text)
        assertEquals(listOf("b"), dao.rows.map { it.text })
        assertTrue(run("""{"action":"forget","id":99}""").isError)
        assertTrue(run("""{"action":"forget"}""").isError)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun anUnknownActionAndBrokenArgumentsAreErrors() {
        assertTrue(run("""{"action":"wipe"}""").isError)
        assertTrue(run("[").isError)
    }

    @Test
    fun onlyForgetGoesThroughTheConfirmationGate() {
        assertEquals(ToolTier.Local, tool.tier)
        assertTrue(tool.sendsOrDeletes)
        assertFalse(tool.requiresConfirmation("""{"action":"remember","text":"x"}"""))
        assertFalse(tool.requiresConfirmation("""{"action":"list"}"""))
        assertTrue(tool.requiresConfirmation("""{"action":"forget","id":1}"""))
        assertFalse(tool.requiresConfirmation("not json"))
        assertEquals("missing required: action", ToolArgs.check(tool.parametersSchema, "{}"))
        assertTrue(tool.parametersSchema.length < 260)
    }

    @Test
    fun theSourceIsNullWhenNoConversationIsActive() {
        val orphan = MemoryTool(dao, now = { 1L })

        runBlocking { orphan.execute("""{"action":"remember","text":"x"}""") }

        assertNull(dao.rows.single().sourceConversationId)
    }
}
