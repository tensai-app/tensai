package io.github.tensaiapp.agent

import io.github.tensaiapp.engine.ChatMessage
import io.github.tensaiapp.engine.DecodeEvent
import io.github.tensaiapp.engine.DecodeRequest
import io.github.tensaiapp.engine.DecodeStats
import io.github.tensaiapp.engine.MessageRole
import io.github.tensaiapp.engine.ToolCall
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `ask_user` (TEN-187): the arguments it accepts, what the model reads back, and the gate it waits on. */
class AskUserToolTest {

    private fun args(question: String = "Which Jade?", options: String = """["A","B"]""", extra: String = "") =
        """{"question":"$question","options":$options$extra}"""

    private fun invalid(argsJson: String): String =
        try {
            AskUserTool.parse(argsJson)
            error("expected the arguments to be refused")
        } catch (e: IllegalArgumentException) {
            e.message.orEmpty()
        }

    // Arguments.

    @Test
    fun optionsAreTrimmedAndBlanksAndDuplicatesDropped() {
        val parsed = AskUserTool.parse(args(options = """["  A ","","B","A","  ","B "]"""))

        assertEquals(listOf("A", "B"), parsed.options)
        assertFalse(parsed.multiple)
    }

    @Test
    fun multipleReadsABooleanOrItsString() {
        assertTrue(AskUserTool.parse(args(extra = ""","multiple":true""")).multiple)
        assertTrue(AskUserTool.parse(args(extra = ""","multiple":"true"""")).multiple)
        assertFalse(AskUserTool.parse(args(extra = ""","multiple":null""")).multiple)
    }

    @Test
    fun aBlankQuestionIsRefused() {
        assertTrue(invalid(args(question = "  ")).contains("question"))
    }

    @Test
    fun fewerThanTwoDistinctOptionsAreRefused() {
        assertTrue(invalid(args(options = "[]")).contains("at least 2"))
        assertTrue(invalid(args(options = """["A"]""")).contains("at least 2"))
        assertTrue(invalid(args(options = """["A"," A ","  "]""")).contains("at least 2"))
        assertTrue(invalid(args(options = "\"A, B\"")).contains("array"))
    }

    @Test
    fun moreThanSixOptionsAreRefusedAfterTheDuplicatesGo() {
        val seven = """["1","2","3","4","5","6","7"]"""
        assertTrue(invalid(args(options = seven)).contains("at most 6"))
        assertEquals(6, AskUserTool.parse(args(options = """["1","2","3","4","5","6","6","1"]""")).options.size)
    }

    @Test
    fun badArgumentsComeBackToTheModelAsAnError() = runBlocking {
        val result = AskUserTool(AskUserGate()).execute(args(options = """["A"]"""))

        assertTrue(result.isError)
        assertTrue(result.text.contains("at least 2"))
    }

    // Result mapping.

    @Test
    fun aSingleChoiceAnswersWithOneAnswer() {
        assertEquals("Jade Martin", JSONObject(AskUserTool.result(listOf("Jade Martin"), multiple = false)).getString("answer"))
    }

    @Test
    fun aMultipleChoiceAnswersWithTheList() {
        val json = JSONObject(AskUserTool.result(listOf("Terraform", "Ansible"), multiple = true))

        assertEquals(listOf("Terraform", "Ansible"), List(json.getJSONArray("answers").length()) { json.getJSONArray("answers").getString(it) })
        assertFalse(json.has("answer"))
    }

    @Test
    fun aTypedAnswerToASingleChoiceIsTheAnswer() {
        assertEquals("""{"answer":"my own"}""", AskUserTool.result(listOf("my own"), multiple = false))
    }

    @Test
    fun typedTextOfAMultipleChoiceIsOneMoreAnswer() {
        assertEquals("""{"answers":["Terraform","Pulumi"]}""", AskUserTool.result(listOf("Terraform", "Pulumi"), multiple = true))
    }

    @Test
    fun aDismissedQuestionIsDismissedTrue() {
        assertEquals("""{"dismissed":true}""", AskUserTool.result(null, multiple = false))
        assertEquals("""{"dismissed":true}""", AskUserTool.result(null, multiple = true))
    }

    @Test
    fun theResultReadsBackForTheChat() {
        assertEquals(listOf("A"), AskUserResult.answers("""{"answer":"A"}"""))
        assertEquals(listOf("A", "B"), AskUserResult.answers("""{"answers":["A","B"]}"""))
        assertTrue(AskUserResult.answers("""{"dismissed":true}""").isEmpty())
        assertTrue(AskUserResult.answers("Error: stopped by the user.").isEmpty())
        assertTrue(AskUserResult.isDismissed("""{"dismissed":true}"""))
        assertFalse(AskUserResult.isDismissed("""{"answer":"A"}"""))
        assertFalse(AskUserResult.isDismissed(null))
    }

    // Caps and round trips.

    @Test
    fun theQuestionAndEachOptionAreCappedWithAnEllipsis() {
        val parsed = AskUserTool.parse(args(question = "q".repeat(500), options = """["${"a".repeat(300)}","b"]"""))

        assertEquals(AskUserTool.MAX_QUESTION_CHARS, parsed.question.length)
        assertTrue(parsed.question.endsWith("\u2026"))
        assertEquals(AskUserTool.MAX_OPTION_CHARS, parsed.options[0].length)
        assertEquals("b", parsed.options[1])
    }

    @Test
    fun aCapNeverSplitsASurrogatePair() {
        val parsed = AskUserTool.parse(args(options = """["${"\uD83D\uDE00".repeat(100)}","b"]"""))

        assertTrue(parsed.options[0].length <= AskUserTool.MAX_OPTION_CHARS)
        assertFalse(parsed.options[0].dropLast(1).last().isHighSurrogate())
    }

    @Test
    fun theWorstCaseResultSurvivesTheRuntimeClipAndReadsBack() = runBlocking {
        val gate = AskUserGate()
        // Every char of every answer needs escaping in JSON, the worst for length.
        val options = (1..6).joinToString(",", "[", "]") { "\"" + "\\\"".repeat(AskUserTool.MAX_OPTION_CHARS - 1) + it.toString() + "\"" }
        val runtime = AgentRuntime(ToolRegistry(listOf(AskUserTool(gate))))
        val events = async {
            runtime.run(userTurn, scripted(reply(listOf(ToolCall("x", "ask_user", args(question = "q", options = options, extra = ""","multiple":true""")))), reply())).toList()
        }
        val pending = withTimeout(5_000) { gate.pending.first { it.isNotEmpty() } }.values.single()
        val typed = "\"".repeat(AskUserTool.MAX_ANSWER_CHARS)
        val answers = pending.options + typed
        gate.answer(pending.callId, answers)

        val outcome = events.await().filterIsInstance<AgentEvent.ToolCallFinished>().single().outcome
        assertFalse(outcome.output.contains("[truncated]"))
        assertTrue(outcome.output.length < AgentRuntime.ResultTokenBudget * 4)
        assertEquals(answers, AskUserResult.answers(outcome.output))
    }

    @Test
    fun quotesBackslashesAndNonAsciiRoundTrip() {
        val typed = "il a dit \"\u00e7a\" \\ \u65e5\u672c\u8a9e \uD83D\uDE00 {\"x\":1}"

        assertEquals(listOf(typed), AskUserResult.answers(AskUserTool.result(listOf(typed), multiple = false)))
        assertEquals(listOf("A", typed), AskUserResult.answers(AskUserTool.result(listOf("A", typed), multiple = true)))
    }

    // The gate.

    @Test
    fun theToolWaitsForTheAnswerAndMapsIt() = runBlocking {
        val gate = AskUserGate()
        val tool = AskUserTool(gate)
        val running = async(start = CoroutineStart.UNDISPATCHED) { withContext(ToolCallContext("c1")) { tool.execute(args(extra = ""","multiple":true""")) } }

        val asked = withTimeout(5_000) { gate.pending.first { it.isNotEmpty() } }.getValue("c1")
        assertEquals("Which Jade?", asked.question)
        assertEquals(listOf("A", "B"), asked.options)
        assertTrue(asked.multiple)
        gate.answer("c1", listOf("B", "typed"))

        val result = running.await()
        assertFalse(result.isError)
        assertEquals("""{"answers":["B","typed"]}""", result.text)
        assertTrue(gate.pending.value.isEmpty())
    }

    @Test
    fun dismissingGivesDismissed() = runBlocking {
        val gate = AskUserGate()
        val running = async(start = CoroutineStart.UNDISPATCHED) { withContext(ToolCallContext("c1")) { AskUserTool(gate).execute(args()) } }

        gate.dismiss("c1")

        assertEquals("""{"dismissed":true}""", running.await().text)
        assertTrue(gate.pending.value.isEmpty())
    }

    @Test
    fun leavingTheChatDismissesItsQuestionsOnly() = runBlocking {
        val gate = AskUserGate()
        val first = async(start = CoroutineStart.UNDISPATCHED) { gate.ask("c1", "Q1", listOf("A", "B"), false) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { gate.ask("c2", "Q2", listOf("A", "B"), false) }

        gate.dismiss(listOf("c1", "nope"))

        assertNull(first.await())
        assertEquals(setOf("c2"), gate.pending.value.keys)
        gate.answer("c2", listOf("A"))
        assertEquals(listOf("A"), second.await())
    }

    @Test
    fun stopDropsTheQuestionWithoutAnswering() = runBlocking {
        val gate = AskUserGate()
        val asking = async(start = CoroutineStart.UNDISPATCHED) { gate.ask("c1", "Q", listOf("A", "B"), false) }
        withTimeout(5_000) { gate.pending.first { it.isNotEmpty() } }

        asking.cancelAndJoin()

        assertTrue(gate.pending.value.isEmpty())
    }

    @Test
    fun anAnswerForAnotherCallOrAnEmptyOneIsIgnored() = runBlocking {
        val gate = AskUserGate()
        val asking = async(start = CoroutineStart.UNDISPATCHED) { gate.ask("c1", "Q", listOf("A", "B"), false) }

        gate.answer("other", listOf("A"))
        gate.answer("c1", emptyList())

        assertEquals(setOf("c1"), gate.pending.value.keys)
        assertTrue(asking.isActive)
        gate.answer("c1", listOf("B"))
        assertEquals(listOf("B"), asking.await())
    }

    @Test
    fun withoutACallContextTheToolRefusesInsteadOfWaiting() = runBlocking {
        val result = AskUserTool(AskUserGate()).execute(args())

        assertTrue(result.isError)
    }

    // Through the runtime.

    private fun scripted(vararg replies: List<DecodeEvent>): ReplyGenerator {
        val queue = ArrayDeque(replies.toList())
        return ReplyGenerator { flow { queue.removeFirst().forEach { emit(it) } } }
    }

    private fun reply(calls: List<ToolCall> = emptyList()) = listOf(
        DecodeEvent.Token(""),
        DecodeEvent.ToolTurn("", null, calls, toolsIgnored = false, toolParseFailed = false, truncated = false),
        DecodeEvent.Done(DecodeStats(tokensGenerated = 1, tokPerSec = 1f, ttftMs = 1L)),
    )

    private val userTurn = DecodeRequest(listOf(ChatMessage(MessageRole.User, "text Jade")))

    @Test
    fun theRuntimeHasNoTimeoutForAQuestionAndKeysItByCallId() = runBlocking {
        val gate = AskUserGate()
        // A timeout of 50 ms would end any other Local tool long before the answer.
        val runtime = AgentRuntime(ToolRegistry(listOf(AskUserTool(gate))), timeoutMs = { 50 })
        val events = async {
            runtime.run(userTurn, scripted(reply(listOf(ToolCall("x", "ask_user", args()))), reply())).toList()
        }

        val pending = withTimeout(5_000) { gate.pending.first { it.isNotEmpty() } }
        val callId = pending.keys.single()
        assertTrue(callId.startsWith("call_"))
        delay(300)
        assertTrue(events.isActive)
        gate.answer(callId, listOf("A"))

        val outcome = events.await().filterIsInstance<AgentEvent.ToolCallFinished>().single().outcome
        assertEquals(ToolCallStatus.Ok, outcome.status)
        assertEquals("""{"answer":"A"}""", outcome.output)
    }

    @Test
    fun stopCancelsAQuestionTheRuntimeIsWaitingOn() = runBlocking {
        val gate = AskUserGate()
        val runtime = AgentRuntime(ToolRegistry(listOf(AskUserTool(gate))))
        val turn = launch { runtime.run(userTurn, scripted(reply(listOf(ToolCall("x", "ask_user", args()))))).toList() }
        withTimeout(5_000) { gate.pending.first { it.isNotEmpty() } }

        turn.cancelAndJoin()

        withTimeout(5_000) { gate.pending.first { it.isEmpty() } }
        Unit
    }

    @Test
    fun theToolIsLocalWaitsForTheUserAndNeedsNoConfirmation() {
        val tool = AskUserTool(AskUserGate())

        assertEquals(ToolTier.Local, tool.tier)
        assertTrue(tool.waitsForUser)
        assertFalse(tool.requiresConfirmation(args()))
        assertEquals("Ask the user to pick among options when the request is ambiguous. Prefer this over guessing.", tool.description)
        assertNull(ToolArgs.check(tool.parametersSchema, args()))
    }
}
