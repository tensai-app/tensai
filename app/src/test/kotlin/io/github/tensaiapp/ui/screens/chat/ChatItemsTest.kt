package io.github.tensaiapp.ui.screens.chat

import io.github.tensaiapp.agent.ConfirmTarget
import io.github.tensaiapp.data.chat.MessageEntity
import io.github.tensaiapp.data.chat.ToolCallEntity
import io.github.tensaiapp.data.chat.ToolCallsJson
import io.github.tensaiapp.engine.ToolCall
import io.github.tensaiapp.ui.tools.ToolCallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [buildChatItems] (TEN-173, TEN-174): how stored messages, tool messages and audit rows come back as list rows. */
class ChatItemsTest {

    private var clock = 0L

    private fun message(
        role: String,
        content: String = "",
        calls: List<ToolCall> = emptyList(),
        toolCallId: String? = null,
        id: String = "m${clock + 1}",
    ) = MessageEntity(
        id = id,
        conversationId = "conv",
        role = role,
        content = content,
        thinking = null,
        toolCallsJson = calls.takeIf { it.isNotEmpty() }?.let(ToolCallsJson::encode),
        tokensGenerated = null,
        tokPerSec = null,
        ttftMs = null,
        stopReason = null,
        createdAt = ++clock,
        toolCallId = toolCallId,
        toolName = null,
    )

    private fun row(messageId: String, callId: String, tool: String, status: String = "ok", args: String = "{}") = ToolCallEntity(
        id = "row-$callId",
        messageId = messageId,
        callId = callId,
        tool = tool,
        tier = "Local",
        server = null,
        argsJson = args,
        startedAt = 0,
        durationMs = 12,
        resultTokens = 1,
        status = status,
        destination = null,
    )

    private fun call(id: String, tool: String, args: String = "{}") = ToolCall(id, tool, args)

    private fun build(
        messages: List<MessageEntity>,
        rows: List<ToolCallEntity> = emptyList(),
        running: Boolean = false,
        pending: Set<String> = emptySet(),
        allowed: Collection<String> = emptyList(),
        targets: Map<String, ConfirmTarget> = emptyMap(),
    ) = buildChatItems(messages, rows, running, pending, allowed, targets, requiresConfirmation = { tool, args -> tool == "erase" || (tool == "memory" && args.contains("forget")) })

    private fun List<ChatItem>.shape() = map {
        when (it) {
            is MessageItem -> if (it.message.isUser) "user" else "assistant"
            is ToolLineItem -> "line(${it.calls.size})"
            is ConfirmationItem -> "confirm:${it.decision.name}"
            is AskAnswerItem -> "answer"
        }
    }

    /** TEN-185: a placeholder call is a plain running line even when its name resolves to a gated tool, and keeps its line's key. */
    @Test
    fun aCallStillBeingWrittenIsNeverGatedAndTheLineKeepsItsKey() {
        val writing = message("assistant", "", listOf(call("writing_0", "erase")), id = "a1")
        val real = message("assistant", "", listOf(call("c1", "datetime")), id = "a1")
        val before = build(listOf(message("user", "go"), writing), running = true)
        val after = build(listOf(message("user", "go"), real), running = true)

        assertEquals(listOf("user", "line(1)"), before.shape())
        assertEquals((before[1] as ToolLineItem).key, (after[1] as ToolLineItem).key)
    }

    @Test
    fun callsBetweenTwoAnswersCollapseIntoOneLine() {
        // text + call, then a reply with only a call, then the answer: the second assistant message adds no bubble.
        val a1 = message("assistant", "Let me look.", listOf(call("c1", "datetime")), id = "a1")
        val a2 = message("assistant", "", listOf(call("c2", "device_info")), id = "a2")
        val messages = listOf(
            message("user", "when and how much battery?"),
            a1,
            message("tool", "14:02", toolCallId = "c1"),
            a2,
            message("tool", "82%", toolCallId = "c2"),
            message("assistant", "It is 14:02 and you have 82%.", id = "a3"),
        )

        val items = build(messages, listOf(row("a1", "c1", "datetime"), row("a2", "c2", "device_info")))

        assertEquals(listOf("user", "assistant", "line(2)", "assistant"), items.shape())
        val line = items[2] as ToolLineItem
        assertEquals(listOf("14:02", "82%"), line.calls.map { it.output })
        assertEquals("tools:a1:0", line.key)
    }

    @Test
    fun aTurnReadsInRealOrderThinkingLineThinkingAnswer() {
        val a1 = message("assistant", "", listOf(call("c1", "datetime")), id = "a1").copy(thinking = "first thoughts")
        val a2 = message("assistant", "It is 14:02.", id = "a2").copy(thinking = "second thoughts")
        val messages = listOf(message("user", "q"), a1, message("tool", "14:02", toolCallId = "c1"), a2)

        val items = build(messages, listOf(row("a1", "c1", "datetime")))

        assertEquals(listOf("user", "assistant", "line(1)", "assistant"), items.shape())
        assertEquals("first thoughts", (items[1] as MessageItem).message.thinking)
        assertEquals("", (items[1] as MessageItem).message.content)
        assertEquals("second thoughts", (items[3] as MessageItem).message.thinking)
    }

    @Test
    fun thinkingBetweenTwoCallsSplitsTheLine() {
        val a1 = message("assistant", "", listOf(call("c1", "datetime")), id = "a1")
        val a2 = message("assistant", "", listOf(call("c2", "device_info")), id = "a2").copy(thinking = "hm")
        val messages = listOf(message("user", "q"), a1, message("tool", "x", toolCallId = "c1"), a2, message("tool", "y", toolCallId = "c2"))

        val items = build(messages, listOf(row("a1", "c1", "datetime"), row("a2", "c2", "device_info")))

        assertEquals(listOf("user", "line(1)", "assistant", "line(1)"), items.shape())
    }

    @Test
    fun anEmptyThinkPairIsNoReasoningBlock() {
        val a1 = message("assistant", "", listOf(call("c1", "datetime")), id = "a1").copy(thinking = "\n\n")
        val a2 = message("assistant", "It is 14:02.", id = "a2").copy(thinking = " \n")
        val messages = listOf(message("user", "q"), a1, message("tool", "14:02", toolCallId = "c1"), a2)

        val items = build(messages, listOf(row("a1", "c1", "datetime")))

        assertEquals(listOf("user", "line(1)", "assistant"), items.shape())
        assertEquals(null, (items[2] as MessageItem).message.thinking)
    }

    @Test
    fun aLineSitsBetweenTheParagraphsItWasCalledFrom() {
        val a1 = message("assistant", "First.", listOf(call("c1", "datetime")), id = "a1")
        val messages = listOf(message("user", "q"), a1, message("tool", "x", toolCallId = "c1"), message("assistant", "Second."))

        assertEquals(listOf("user", "assistant", "line(1)", "assistant"), build(messages, listOf(row("a1", "c1", "datetime"))).shape())
    }

    @Test
    fun aTurnWithNoTextBeforeItsCallStartsWithTheLine() {
        val a1 = message("assistant", "", listOf(call("c1", "datetime")), id = "a1")
        val messages = listOf(message("user", "q"), a1, message("tool", "x", toolCallId = "c1"), message("assistant", "Done."))

        assertEquals(listOf("user", "line(1)", "assistant"), build(messages, listOf(row("a1", "c1", "datetime"))).shape())
    }

    @Test
    fun aCallWithNoRowIsRunningWhileTheTurnRunsAndStoppedAfter() {
        val a1 = message("assistant", "", listOf(call("c1", "datetime")), id = "a1")
        val messages = listOf(message("user", "q"), a1)

        val running = (build(messages, running = true)[1] as ToolLineItem).calls.single()
        val abandoned = (build(messages, running = false)[1] as ToolLineItem).calls.single()

        assertEquals(ToolCallState.Running, running.state)
        assertEquals(ToolCallState.Stopped, abandoned.state)
    }

    @Test
    fun storedStatusDurationAndDestinationComeFromTheAuditRow() {
        val a1 = message("assistant", "", listOf(call("c1", "web_search")), id = "a1")
        val stored = row("a1", "c1", "web_search", status = "timeout").copy(destination = "DuckDuckGo")
        val items = build(listOf(message("user", "q"), a1, message("tool", "Error: timed out", toolCallId = "c1")), listOf(stored))

        val shown = (items[1] as ToolLineItem).calls.single()
        assertEquals(ToolCallState.Timeout, shown.state)
        assertEquals(12L, shown.durationMs)
        assertEquals("DuckDuckGo", shown.destination)
        assertEquals("Error: timed out", shown.output)
    }

    @Test
    fun aCallThatNeedsConfirmationIsACardNotPartOfALine() {
        val a1 = message("assistant", "", listOf(call("c1", "datetime"), call("c2", "erase", """{"id":1}""")), id = "a1")
        val messages = listOf(message("user", "q"), a1)

        val pending = build(messages, running = true, pending = setOf("c2"))
        assertEquals(listOf("user", "line(1)", "confirm:Pending"), pending.shape())

        // Between the Allow tap and the stored result, and before the gate was reached at all.
        assertEquals("confirm:Allowed", build(messages, running = true, allowed = listOf("c2")).shape().last())
        assertEquals(listOf("user", "line(1)"), build(messages, running = true).shape())
    }

    @Test
    fun theStoredDecisionIsWhatAReopenedChatShows() {
        val a1 = message("assistant", "", listOf(call("c1", "erase", """{"id":1}""")), id = "a1")
        val a2 = message("assistant", "", listOf(call("c2", "erase", """{"id":2}""")), id = "a2")
        val messages = listOf(
            message("user", "q"),
            a1,
            message("tool", "Error: the user refused this action.", toolCallId = "c1"),
            a2,
            message("tool", "erased", toolCallId = "c2"),
        )
        val rows = listOf(row("a1", "c1", "erase", status = "refused"), row("a2", "c2", "erase", status = "ok"))

        val items = build(messages, rows)

        assertEquals(listOf("user", "confirm:Denied", "confirm:Allowed"), items.shape())
        assertEquals("confirm:a1:c1", items[1].key)
    }

    @Test
    fun aMemoryCallIsGatedOnlyForForget() {
        val a1 = message(
            "assistant",
            "",
            listOf(call("c1", "memory", """{"action":"list"}"""), call("c2", "memory", """{"action":"forget","id":3}""")),
            id = "a1",
        )
        val rows = listOf(
            row("a1", "c1", "memory", args = """{"action":"list"}"""),
            row("a1", "c2", "memory", status = "refused", args = """{"action":"forget","id":3}"""),
        )

        assertEquals(listOf("line(1)", "confirm:Denied"), build(listOf(a1), rows).shape())
    }

    @Test
    fun aForgetCardCarriesTheFactItActsOn() {
        val a1 = message("assistant", "", listOf(call("c1", "memory", """{"action":"forget","id":1}""")), id = "a1")
        val tea = ConfirmTarget("1", "Likes tea")

        val items = build(listOf(a1), running = true, pending = setOf("c1"), targets = mapOf("c1" to tea, "other" to ConfirmTarget("2", "x")))

        assertEquals(tea, (items.single() as ConfirmationItem).target)
    }

    @Test
    fun aStoppedGatedCallIsACardThatSaysStopped() {
        val a1 = message("assistant", "", listOf(call("c1", "erase", """{"id":1}""")), id = "a1")

        val items = build(listOf(a1), listOf(row("a1", "c1", "erase", status = "stopped")))

        assertEquals(listOf("confirm:Stopped"), items.shape())
        assertEquals(ToolCallState.Stopped, (items.single() as ConfirmationItem).call.state)
    }

    /** Templates reuse `call_1` from one turn to the next: outputs, keys and decisions must not bleed across turns. */
    @Test
    fun aCallIdReusedAcrossTurnsKeepsItsOwnOutputKeyAndDecision() {
        val a1 = message("assistant", "", listOf(call("call_1", "erase", """{"id":1}""")), id = "a1")
        val a2 = message("assistant", "Done.", id = "a2")
        val a3 = message("assistant", "", listOf(call("call_1", "erase", """{"id":2}""")), id = "a3")
        val messages = listOf(
            message("user", "q1"), a1, message("tool", "first", toolCallId = "call_1"), a2,
            message("user", "q2"), a3,
        )
        val rows = listOf(row("a1", "call_1", "erase", status = "ok"))

        // The second `call_1` is pending: the first, answered, card must stay Allowed.
        val items = build(messages, rows, running = true, pending = setOf("call_1"))

        val cards = items.filterIsInstance<ConfirmationItem>()
        assertEquals(listOf(ConfirmDecision.Allowed, ConfirmDecision.Pending), cards.map { it.decision })
        assertEquals("first", cards[0].call.output)
        assertEquals(null, cards[1].call.output)
        assertEquals(items.size, items.map { it.key }.toSet().size)
    }

    @Test
    fun outputsPairWithTheClosestPrecedingCaller() {
        val a1 = message("assistant", "", listOf(call("call_1", "datetime")), id = "a1")
        val a2 = message("assistant", "", listOf(call("call_1", "device_info")), id = "a2")
        val messages = listOf(
            message("user", "q"), a1, message("tool", "time", toolCallId = "call_1"),
            a2, message("tool", "battery", toolCallId = "call_1"),
        )

        val items = build(messages, listOf(row("a1", "call_1", "datetime"), row("a2", "call_1", "device_info")))

        assertEquals(listOf("time", "battery"), (items[1] as ToolLineItem).calls.map { it.output })
    }

    /** A gated call that failed (limit, invalid arguments, a tool error) was never an "Allowed" decision. */
    @Test
    fun aGatedCallThatErroredIsAnErrorLineNotAnAllowedCard() {
        val a1 = message("assistant", "", listOf(call("c1", "erase", """{"id":1}""")), id = "a1")

        val items = build(listOf(a1), listOf(row("a1", "c1", "erase", status = "error")))

        assertEquals(listOf("line(1)"), items.shape())
        assertEquals(ToolCallState.Error, (items.single() as ToolLineItem).calls.single().state)
    }

    @Test
    fun aPlainChatHasNoToolRows() {
        val items = build(listOf(message("user", "hi"), message("assistant", "hello")))

        assertEquals(listOf("user", "assistant"), items.shape())
        assertTrue(items.none { it is ToolLineItem || it is ConfirmationItem })
    }

    // ask_user (TEN-187)

    private val askArgs = """{"question":"Which Jade?","options":["Jade Martin","Jade Leroy"]}"""

    private fun asked(result: String?, running: Boolean = false, status: String = "ok"): List<ChatItem> {
        val a1 = message("assistant", "Two contacts are named Jade.", listOf(call("c1", "ask_user", askArgs)), id = "a1")
        val stored = listOfNotNull(message("user", "text Jade"), a1, result?.let { message("tool", it, toolCallId = "c1") })
        return build(stored, if (result == null) emptyList() else listOf(row("a1", "c1", "ask_user", status = status)), running = running)
    }

    
    fun anOpenQuestionIsARunningLineAndNothingElse() {
        val items = asked(result = null, running = true)

        assertEquals(listOf("user", "assistant", "line(1)"), items.shape())
        assertEquals(ToolCallState.Running, (items.last() as ToolLineItem).calls.single().state)
    }

    
    fun anAnsweredQuestionIsItsLineThenTheQuestionAndAnswerAsAUserRow() {
        val items = asked("""{"answer":"Jade Martin"}""")

        assertEquals(listOf("user", "assistant", "line(1)", "answer"), items.shape())
        assertEquals(ToolCallState.Ok, (items[2] as ToolLineItem).calls.single().state)
        val answer = items[3] as AskAnswerItem
        assertEquals("Which Jade?", answer.question)
        assertEquals(listOf("Jade Martin"), answer.answers)
        assertEquals("ask:a1:c1", answer.key)
    }

    
    fun aMultipleAnswerKeepsEveryAnswer() {
        val items = asked("""{"answers":["Terraform","Ansible"]}""")

        assertEquals(listOf("Terraform", "Ansible"), (items.last() as AskAnswerItem).answers)
    }

    
    fun aDismissedQuestionIsADismissedLineWithNoAnswerRow() {
        val items = asked("""{"dismissed":true}""")

        assertEquals(listOf("user", "assistant", "line(1)"), items.shape())
        assertEquals(ToolCallState.Dismissed, (items.last() as ToolLineItem).calls.single().state)
    }

    
    fun aQuestionLeftByAKilledProcessIsStoppedWithNoAnswerRow() {
        val items = asked("Error: stopped by the user.", status = "stopped")

        assertEquals(listOf("user", "assistant", "line(1)"), items.shape())
        assertEquals(ToolCallState.Stopped, (items.last() as ToolLineItem).calls.single().state)
    }

    
    fun aQuestionSplitsTheLineOfTheCallsAroundIt() {
        val a1 = message("assistant", "", listOf(call("c1", "datetime"), call("c2", "ask_user", askArgs), call("c3", "device_info")), id = "a1")
        val messages = listOf(
            message("user", "q"), a1,
            message("tool", "14:02", toolCallId = "c1"), message("tool", """{"answer":"Jade Leroy"}""", toolCallId = "c2"), message("tool", "82%", toolCallId = "c3"),
        )

        val items = build(messages, listOf(row("a1", "c1", "datetime"), row("a1", "c2", "ask_user"), row("a1", "c3", "device_info")))

        assertEquals(listOf("user", "line(1)", "line(1)", "answer", "line(1)"), items.shape())
    }
}
