package io.github.hokanosekai.tensai.data.chat

import io.github.hokanosekai.tensai.agent.ToolCallOutcome
import io.github.hokanosekai.tensai.agent.ToolCallStatus
import io.github.hokanosekai.tensai.agent.ToolTier
import io.github.hokanosekai.tensai.engine.MessageRole
import io.github.hokanosekai.tensai.engine.ToolCall
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TEN-172: tool calls and results are persisted, and replayed into the engine's history. */
class ChatRepositoryToolsTest {

    private val dao = ChatRepositoryTitleTest.FakeChatDao()
    private val repository = ChatRepository(dao)

    private fun message(
        id: String,
        role: String,
        content: String = "",
        toolCallsJson: String? = null,
        toolCallId: String? = null,
        toolName: String? = null,
        createdAt: Long = 0,
        thinking: String? = null,
    ) = MessageEntity(
        id = id, conversationId = "c", role = role, content = content, thinking = thinking,
        toolCallsJson = toolCallsJson, tokensGenerated = null, tokPerSec = null, ttftMs = null,
        stopReason = null, createdAt = createdAt, toolCallId = toolCallId, toolName = toolName,
    )

    private val datetimeCall = ToolCall("call_1", "datetime", "{}")

    @Test
    fun toolCallsAndToolResultsAreReplayedIntoTheEngineHistory() {
        val history = listOf(
            message("1", "user", "what time is it?"),
            message("2", "assistant", "", toolCallsJson = ToolCallsJson.encode(listOf(datetimeCall))),
            message("3", "tool", """{"time":"14:03"}""", toolCallId = "call_1", toolName = "datetime"),
            message("4", "assistant", "It is 14:03."),
        )

        val engine = repository.toEngineMessages(history, systemPrompt = null)

        assertEquals(listOf(MessageRole.User, MessageRole.Assistant, MessageRole.Tool, MessageRole.Assistant), engine.map { it.role })
        // An assistant message that only made calls is not an empty turn: it stays.
        assertEquals(listOf(datetimeCall), engine[1].toolCalls)
        assertEquals("call_1", engine[2].toolCallId)
        assertEquals("""{"time":"14:03"}""", engine[2].content)
        assertNull(engine[3].toolCalls)
        assertNull(engine[0].toolCallId)
    }

    @Test
    fun reasoningIsReplayedOnlyForTheCurrentTurn() {
        val calls = ToolCallsJson.encode(listOf(datetimeCall))
        val history = listOf(
            message("1", "user", "first"),
            message("2", "assistant", "", toolCallsJson = calls, thinking = "old plan"),
            message("3", "tool", "14:03", toolCallId = "call_1"),
            message("4", "assistant", "It is 14:03.", thinking = "old answer"),
            message("5", "user", "second"),
            message("6", "assistant", "", toolCallsJson = calls, thinking = "current plan"),
            message("7", "tool", "14:04", toolCallId = "call_1"),
        )

        val engine = repository.toEngineMessages(history, systemPrompt = null)

        assertEquals(listOf(null, null, null, null, null, "current plan", null), engine.map { it.reasoning })
    }

    @Test
    fun noReasoningIsReplayedWhenThereIsNoThinking() {
        val calls = ToolCallsJson.encode(listOf(datetimeCall))
        for (thinking in listOf(null, "", "  \n")) {
            val history = listOf(
                message("1", "user", "time?"),
                message("2", "assistant", "", toolCallsJson = calls, thinking = thinking),
                message("3", "tool", "14:03", toolCallId = "call_1"),
            )

            assertNull("thinking=$thinking", repository.toEngineMessages(history, null)[1].reasoning)
        }
    }

    @Test
    fun noReasoningWithoutAUserRow() {
        val history = listOf(message("1", "assistant", "hi", thinking = "plan"))

        assertNull(repository.toEngineMessages(history, null).single().reasoning)
    }

    @Test
    fun anEmptyAssistantTurnWithoutCallsIsStillLeftOut() {
        val engine = repository.toEngineMessages(listOf(message("1", "user", "hi"), message("2", "assistant", "")), null)

        assertEquals(listOf(MessageRole.User), engine.map { it.role })
    }

    @Test
    fun argumentsThatAreNotAJsonObjectAreReplayedAsAnEmptyOne() {
        val bad = ToolCall("c", "datetime", "not json")
        val history = listOf(
            message("1", "user", "hi"),
            message("2", "assistant", "", toolCallsJson = ToolCallsJson.encode(listOf(bad))),
            message("3", "tool", "Error: invalid arguments", toolCallId = "c"),
        )

        val replayed = repository.toEngineMessages(history, null)[1].toolCalls!!.single()

        assertEquals("{}", replayed.argumentsJson)
        assertEquals("c", replayed.id)
    }

    private fun callsJson(vararg ids: String) = ToolCallsJson.encode(ids.map { ToolCall(it, "datetime", "{}") })

    @Test
    fun callsWithNoResultAreStrippedFromTheReplayedHistory() {
        // A stop (or a killed process) between the reply and its results.
        val history = listOf(
            message("1", "user", "time?"),
            message("2", "assistant", "let me check", toolCallsJson = callsJson("a")),
            message("3", "user", "hello?"),
        )

        val engine = repository.toEngineMessages(history, null)

        assertEquals(listOf(MessageRole.User, MessageRole.Assistant, MessageRole.User), engine.map { it.role })
        assertNull(engine[1].toolCalls)
        assertEquals("let me check", engine[1].content)
    }

    @Test
    fun anAssistantMessageOfOnlyUnansweredCallsDisappears() {
        val history = listOf(message("1", "user", "time?"), message("2", "assistant", "", toolCallsJson = callsJson("a")))

        assertEquals(listOf(MessageRole.User), repository.toEngineMessages(history, null).map { it.role })
    }

    @Test
    fun onlyTheAnsweredCallsAndTheirResultsStay() {
        val history = listOf(
            message("1", "user", "go"),
            message("2", "assistant", "", toolCallsJson = callsJson("a", "b")),
            message("3", "tool", "r-b", toolCallId = "b", toolName = "datetime"),
        )

        val engine = repository.toEngineMessages(history, null)

        assertEquals(listOf("b"), engine[1].toolCalls!!.map { it.id })
        assertEquals(listOf(MessageRole.User, MessageRole.Assistant, MessageRole.Tool), engine.map { it.role })
    }

    @Test
    fun aToolMessageWithNoMatchingCallIsDropped() {
        val history = listOf(
            message("0", "tool", "orphan at the start", toolCallId = "z"),
            message("1", "user", "hi"),
            message("2", "assistant", "yo"),
            message("3", "tool", "orphan after a plain reply", toolCallId = "y"),
            message("4", "assistant", "", toolCallsJson = callsJson("a")),
            message("5", "tool", "wrong id", toolCallId = "other"),
        )

        val engine = repository.toEngineMessages(history, null)

        assertEquals(listOf(MessageRole.User, MessageRole.Assistant), engine.map { it.role })
    }

    @Test
    fun aCallAlreadyAnsweredIsNotRecordedTwice() = runBlocking {
        val conversation = repository.createConversation(modelId = null)
        val assistant = repository.startAssistantMessage(conversation)
        val outcome = ToolCallOutcome(datetimeCall, ToolTier.Local, ToolCallStatus.Ok, 1L, 1L, "r", 1, null)

        repository.recordToolCall(conversation, assistant, outcome)
        // The cleanup after a stop, for a call whose result had in fact landed.
        repository.recordToolCall(conversation, assistant, ToolCallOutcome.stopped(datetimeCall, ToolTier.Local, 2L))

        assertEquals(1, dao.observeMessages(conversation).first().count { it.role == "tool" })
        assertEquals(listOf("ok"), dao.toolCalls.map { it.status })
    }

    @Test
    fun theSameCallIdInAnotherReplyIsStillRecorded() = runBlocking {
        val conversation = repository.createConversation(modelId = null)
        val first = repository.startAssistantMessage(conversation)
        val second = repository.startAssistantMessage(conversation)
        val outcome = ToolCallOutcome(datetimeCall, ToolTier.Local, ToolCallStatus.Ok, 1L, 1L, "r", 1, null)

        repository.recordToolCall(conversation, first, outcome)
        repository.recordToolCall(conversation, second, outcome)

        assertEquals(2, dao.toolCalls.size)
    }

    @Test
    fun theSystemPromptStillComesFirst() {
        val engine = repository.toEngineMessages(listOf(message("1", "user", "hi")), "be brief")

        assertEquals(MessageRole.System, engine.first().role)
    }

    @Test
    fun toolCallsJsonRoundTripsAndNeverThrowsOnGarbage() {
        val calls = listOf(datetimeCall, ToolCall("b", "echo", """{"text":"a \"quoted\" b"}"""))

        assertEquals(calls, ToolCallsJson.decode(ToolCallsJson.encode(calls)))
        assertTrue(ToolCallsJson.decode(null).isEmpty())
        assertTrue(ToolCallsJson.decode("").isEmpty())
        assertTrue(ToolCallsJson.decode("{oops").isEmpty())
        assertTrue(ToolCallsJson.decode("""[{"id":"x"}]""").isEmpty())
    }

    @Test
    fun recordingACallWritesTheToolMessageAndTheAuditRow() = runBlocking {
        val conversation = repository.createConversation(modelId = null)
        repository.appendUserMessage(conversation, "what time is it?")
        val assistant = repository.startAssistantMessage(conversation)
        repository.setToolCalls(assistant, listOf(datetimeCall))
        val outcome = ToolCallOutcome(
            call = datetimeCall, tier = ToolTier.Local, status = ToolCallStatus.Ok, startedAt = 100L,
            durationMs = 12L, output = """{"time":"14:03"}""", resultTokens = 7, destination = null,
        )

        repository.recordToolCall(conversation, assistant, outcome)

        val stored = dao.observeMessages(conversation).first()
        val tool = stored.single { it.role == "tool" }
        assertEquals("""{"time":"14:03"}""", tool.content)
        assertEquals("call_1", tool.toolCallId)
        assertEquals("datetime", tool.toolName)
        assertEquals(ToolCallsJson.encode(listOf(datetimeCall)), stored.single { it.id == assistant }.toolCallsJson)
        val row = dao.toolCalls.single()
        assertEquals(assistant, row.messageId)
        assertEquals("call_1", row.callId)
        assertEquals("datetime", row.tool)
        assertEquals("Local", row.tier)
        assertNull(row.server)
        assertEquals("{}", row.argsJson)
        assertEquals(100L, row.startedAt)
        assertEquals(12L, row.durationMs)
        assertEquals(7, row.resultTokens)
        assertEquals("ok", row.status)
    }

    @Test
    fun messagesKeepTheirOrderEvenWithinOneMillisecond() = runBlocking {
        val conversation = repository.createConversation(modelId = null)
        repeat(20) { repository.startAssistantMessage(conversation) }

        val times = dao.observeMessages(conversation).first().map { it.createdAt }

        assertEquals(times.sorted().distinct(), times)
    }

    @Test
    fun deletingAReplyDropsItsAuditRows() = runBlocking {
        val conversation = repository.createConversation(modelId = null)
        val assistant = repository.startAssistantMessage(conversation)
        val outcome = ToolCallOutcome.stopped(datetimeCall, ToolTier.Local, now = 1L)
        repository.recordToolCall(conversation, assistant, outcome)

        repository.deleteMessage(assistant)

        assertTrue(dao.toolCalls.isEmpty())
    }

    @Test
    fun deletingAConversationDropsItsAuditRows() = runBlocking {
        val conversation = repository.createConversation(modelId = null)
        val assistant = repository.startAssistantMessage(conversation)
        repository.recordToolCall(conversation, assistant, ToolCallOutcome.stopped(datetimeCall, null, now = 1L))

        repository.deleteConversation(conversation)

        assertTrue(dao.toolCalls.isEmpty())
    }

    @Test
    fun toolsAreOnForANewConversationAndTheSwitchIsStored() = runBlocking {
        val conversation = repository.createConversation(modelId = null)
        assertTrue(repository.getConversation(conversation)!!.toolsEnabled)

        repository.setToolsEnabled(conversation, false)

        assertEquals(false, repository.getConversation(conversation)!!.toolsEnabled)
    }
}
