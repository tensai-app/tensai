package io.github.hokanosekai.tensai.agent

import io.github.hokanosekai.tensai.engine.DecodeEvent
import io.github.hokanosekai.tensai.engine.DecodeRequest
import io.github.hokanosekai.tensai.engine.DecodeStats
import io.github.hokanosekai.tensai.engine.ChatMessage
import io.github.hokanosekai.tensai.engine.MessageRole
import io.github.hokanosekai.tensai.engine.ToolCall
import io.github.hokanosekai.tensai.engine.ToolChoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import io.github.hokanosekai.tensai.runtime.QueueBusyException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests of [AgentRuntime] (TEN-172) against a scripted engine: every reply the "model" makes is
 * written down in advance, and the requests the runtime sends are kept to check what it hands back.
 */
class AgentRuntimeTest {

    /** Plays [replies] one per generation, keeping each [DecodeRequest]. */
    private class ScriptedGenerator(vararg replies: List<DecodeEvent>) : ReplyGenerator {
        val requests = mutableListOf<DecodeRequest>()
        private val queue = ArrayDeque(replies.toList())

        override fun generate(request: DecodeRequest): Flow<DecodeEvent> = flow {
            requests += request
            (queue.removeFirstOrNull() ?: error("no scripted reply left for generation ${requests.size}")).forEach { emit(it) }
        }
    }

    private fun reply(
        text: String = "",
        calls: List<ToolCall> = emptyList(),
        content: String = text,
        toolParseFailed: Boolean = false,
        truncated: Boolean = false,
    ): List<DecodeEvent> = listOf(
        DecodeEvent.Token(text),
        DecodeEvent.ToolTurn(content, null, calls, toolsIgnored = false, toolParseFailed = toolParseFailed, truncated = truncated),
        DecodeEvent.Done(DecodeStats(tokensGenerated = 1, tokPerSec = 1f, ttftMs = 1L)),
    )

    private fun call(id: String, name: String = "echo", args: String = """{"text":"hi"}""") = ToolCall(id, name, args)

    private class FakeTool(
        override val name: String = "echo",
        override val tier: ToolTier = ToolTier.Local,
        override val sendsOrDeletes: Boolean = false,
        override val parametersSchema: String = """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""",
        val body: suspend (String) -> ToolResult = { ToolResult.ok("echo:$it") },
    ) : Tool {
        override val description = "test tool"
        var runs = 0

        override suspend fun execute(argsJson: String): ToolResult {
            runs++
            return body(argsJson)
        }
    }

    private val userTurn = DecodeRequest(listOf(ChatMessage(MessageRole.User, "go")))

    private fun runtime(
        vararg tools: Tool,
        gate: ConfirmationGate = ConfirmationGate.AutoApprove,
        countTokens: suspend (String) -> Int? = { null },
        timeoutMs: (Tool) -> Long = { it.tier.timeoutMs },
    ) = AgentRuntime(ToolRegistry(tools.toList()), gate, countTokens = countTokens, timeoutMs = timeoutMs)

    private fun List<AgentEvent>.outcomes() = filterIsInstance<AgentEvent.ToolCallFinished>().map { it.outcome }

    @Test
    fun noCallIsOneGenerationThenDone() = runBlocking {
        val gen = ScriptedGenerator(reply("hello"))

        val events = runtime(FakeTool()).run(userTurn, gen).toList()

        assertEquals(1, gen.requests.size)
        assertEquals(listOf("echo"), gen.requests[0].tools.map { it.name })
        assertEquals(ToolChoice.Auto, gen.requests[0].toolChoice)
        assertEquals(AgentEvent.TurnStarted, events.first())
        assertEquals(AgentEvent.Done, events.last())
        assertTrue(events.contains(AgentEvent.Token("hello")))
        assertTrue(events.outcomes().isEmpty())
        val finished = events.filterIsInstance<AgentEvent.ReplyFinished>().single()
        assertTrue(finished.calls.isEmpty())
    }

    @Test
    fun oneCallIsRunAndItsResultComesBackToTheModel() = runBlocking {
        val tool = FakeTool()
        val gen = ScriptedGenerator(reply(calls = listOf(call("c1"))), reply("done"))

        val events = runtime(tool).run(userTurn, gen).toList()

        assertEquals(1, tool.runs)
        val outcome = events.outcomes().single()
        assertEquals(ToolCallStatus.Ok, outcome.status)
        assertEquals("""echo:{"text":"hi"}""", outcome.output)
        assertEquals(ToolTier.Local, outcome.tier)
        assertEquals(2, gen.requests.size)
        val second = gen.requests[1].messages
        assertEquals(listOf(MessageRole.User, MessageRole.Assistant, MessageRole.Tool), second.map { it.role })
        assertEquals(listOf("c1"), second[1].toolCalls!!.map { it.id })
        assertEquals("c1", second[2].toolCallId)
        assertEquals(outcome.output, second[2].content)
        assertEquals(AgentEvent.Done, events.last())
    }

    @Test
    fun severalCallsInOneReplyAnswerInOrderBeforeTheNextGeneration() = runBlocking {
        val tool = FakeTool()
        val gen = ScriptedGenerator(
            reply(calls = listOf(call("a", args = """{"text":"1"}"""), call("b", args = """{"text":"2"}"""))),
            reply("done"),
        )

        val events = runtime(tool).run(userTurn, gen).toList()

        assertEquals(2, tool.runs)
        assertEquals(listOf("a", "b"), events.outcomes().map { it.call.id })
        assertEquals(2, gen.requests.size)
        assertEquals(
            listOf(MessageRole.User, MessageRole.Assistant, MessageRole.Tool, MessageRole.Tool),
            gen.requests[1].messages.map { it.role },
        )
        assertEquals(listOf("a", "b"), gen.requests[1].messages.filter { it.role == MessageRole.Tool }.map { it.toolCallId })
    }

    @Test
    fun chainedCallsAcrossGenerationsKeepGrowingTheHistory() = runBlocking {
        val tool = FakeTool()
        val gen = ScriptedGenerator(
            reply(calls = listOf(call("a"))),
            reply(calls = listOf(call("b"))),
            reply("done"),
        )

        val events = runtime(tool).run(userTurn, gen).toList()

        assertEquals(2, tool.runs)
        assertEquals(3, gen.requests.size)
        assertEquals(listOf(1, 3, 5), gen.requests.map { it.messages.size })
        assertEquals(listOf(0, 1, 2), events.filterIsInstance<AgentEvent.ReplyStarted>().map { it.index })
    }

    @Test
    fun theSixthCallIsNotRunAndTheModelIsForcedToAnswer() = runBlocking {
        val tool = FakeTool()
        val gen = ScriptedGenerator(
            reply(calls = listOf(call("1"), call("2"))),
            reply(calls = listOf(call("3"), call("4"))),
            reply(calls = listOf(call("5"), call("6"))),
            reply("final"),
        )

        val events = runtime(tool).run(userTurn, gen).toList()

        assertEquals(5, tool.runs)
        val outcomes = events.outcomes()
        assertEquals(6, outcomes.size)
        assertEquals(ToolCallStatus.Error, outcomes.last().status)
        assertTrue(outcomes.last().output.contains("limit"))
        assertEquals(4, gen.requests.size)
        assertEquals(listOf(ToolChoice.Auto, ToolChoice.Auto, ToolChoice.Auto, ToolChoice.None), gen.requests.map { it.toolChoice })
        // The tools stay in the forced prompt: same prefix, the KV cache is reused.
        assertEquals(listOf("echo"), gen.requests[3].tools.map { it.name })
        assertEquals(AgentEvent.Done, events.last())
    }

    @Test
    fun aCallMadeAfterTheLimitIsDroppedNotRun() = runBlocking {
        val tool = FakeTool()
        val gen = ScriptedGenerator(
            reply(calls = (1..5).map { call("c$it") }),
            reply("final", calls = listOf(call("late"))),
        )

        val events = runtime(tool).run(userTurn, gen).toList()

        assertEquals(5, tool.runs)
        assertEquals(2, gen.requests.size)
        assertEquals(ToolChoice.None, gen.requests[1].toolChoice)
        assertTrue(events.filterIsInstance<AgentEvent.ReplyFinished>().last().calls.isEmpty())
        val last = events.filterIsInstance<AgentEvent.ReplyFinished>().last()
        assertTrue(last.droppedCalls)
        // The parsed answer, not the streamed text that still carries the call markup.
        assertEquals("final", last.content)
        assertEquals(AgentEvent.Done, events.last())
    }

    @Test
    fun anUnknownToolIsAnErrorTheModelSees() = runBlocking {
        val gen = ScriptedGenerator(reply(calls = listOf(call("x", name = "nope"))), reply("sorry"))

        val events = runtime(FakeTool()).run(userTurn, gen).toList()

        val outcome = events.outcomes().single()
        assertEquals(ToolCallStatus.Error, outcome.status)
        assertNull(outcome.tier)
        assertTrue(outcome.output.contains("unknown tool 'nope'"))
        assertTrue(outcome.output.contains("echo"))
        assertEquals(outcome.output, gen.requests[1].messages.last().content)
        assertEquals(AgentEvent.Done, events.last())
    }

    @Test
    fun aToolOutsideTheGrantedTiersIsUnknownToo() = runBlocking {
        val network = FakeTool(name = "web", tier = ToolTier.Network)
        val gen = ScriptedGenerator(reply(calls = listOf(call("w", name = "web"))), reply("ok"))

        val events = AgentRuntime(ToolRegistry(listOf(network))).run(userTurn, gen).toList()

        assertEquals(0, network.runs)
        assertTrue(gen.requests[0].tools.isEmpty())
        assertEquals(ToolCallStatus.Error, events.outcomes().single().status)
    }

    @Test
    fun argumentsThatAreNotJsonAreAnErrorAndTheToolDoesNotRun() = runBlocking {
        val tool = FakeTool()
        val gen = ScriptedGenerator(reply(calls = listOf(call("a", args = "{not json"))), reply("ok"))

        val events = runtime(tool).run(userTurn, gen).toList()

        assertEquals(0, tool.runs)
        val outcome = events.outcomes().single()
        assertEquals(ToolCallStatus.Error, outcome.status)
        assertTrue(outcome.output.contains("invalid arguments"))
        // The schema comes along so the model can correct itself.
        assertTrue(outcome.output.contains("required"))
        // What gets replayed into the prompt is always a JSON object, llama.kt refuses anything else.
        assertEquals("{}", gen.requests[1].messages[1].toolCalls!!.single().argumentsJson)
    }

    @Test
    fun aMissingRequiredArgumentIsAnError() = runBlocking {
        val tool = FakeTool()
        val gen = ScriptedGenerator(reply(calls = listOf(call("a", args = "{}"))), reply("ok"))

        val events = runtime(tool).run(userTurn, gen).toList()

        assertEquals(0, tool.runs)
        assertTrue(events.outcomes().single().output.contains("missing required: text"))
    }

    @Test
    fun blankArgumentsPassForAToolWithNoRequiredKey() = runBlocking {
        val tool = FakeTool(parametersSchema = """{"type":"object","properties":{}}""")
        val gen = ScriptedGenerator(reply(calls = listOf(call("a", args = ""))), reply("ok"))

        val events = runtime(tool).run(userTurn, gen).toList()

        assertEquals(1, tool.runs)
        assertEquals(ToolCallStatus.Ok, events.outcomes().single().status)
    }

    @Test
    fun aToolThatRunsPastItsTimeoutIsCancelledAndReported() = runBlocking {
        var finished = false
        val tool = FakeTool(body = {
            delay(60_000)
            finished = true
            ToolResult.ok("late")
        })
        val gen = ScriptedGenerator(reply(calls = listOf(call("a"))), reply("ok"))

        val events = runtime(tool, timeoutMs = { 50 }).run(userTurn, gen).toList()

        val outcome = events.outcomes().single()
        assertEquals(ToolCallStatus.Timeout, outcome.status)
        assertTrue(outcome.output.contains("timed out"))
        assertFalse(finished)
        assertEquals(AgentEvent.Done, events.last())
    }

    @Test
    fun aToolThatIgnoresCancellationIsAbandonedAtTheTimeoutAndTheLoopGoesOn() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val tool = FakeTool(body = {
            // Deaf to cancellation, as a blocking call that never checks would be.
            withContext(NonCancellable) { release.await() }
            ToolResult.ok("late")
        })
        val gen = ScriptedGenerator(reply(calls = listOf(call("a"))), reply("ok"))

        val events = withTimeout(5_000) { runtime(tool, timeoutMs = { 50 }).run(userTurn, gen).toList() }

        assertEquals(ToolCallStatus.Timeout, events.outcomes().single().status)
        assertEquals(AgentEvent.Done, events.last())
        release.complete(Unit)
        Unit
    }

    @Test
    fun aGenerationRejectedAsBusyIsRetriedOnceAfterTheQueueFrees() = runBlocking {
        var waits = 0
        var generations = 0
        val gen = ReplyGenerator {
            flow {
                generations++
                // The second generation is the one a foreign task took the queue from.
                if (generations == 2) {
                    emit(DecodeEvent.Error(QueueBusyException("busy")))
                } else {
                    reply(calls = if (generations == 1) listOf(call("a")) else emptyList()).forEach { emit(it) }
                }
            }
        }
        val rt = AgentRuntime(ToolRegistry(listOf(FakeTool())), waitForQueue = { waits++; true })

        val events = rt.run(userTurn, gen).toList()

        assertEquals(1, waits)
        assertEquals(3, generations)
        assertEquals(AgentEvent.Done, events.last())
    }

    @Test
    fun aQueueThatStaysBusyEndsTheTurnWithAClearError() = runBlocking {
        val gen = ReplyGenerator { flow { emit(DecodeEvent.Error(QueueBusyException("busy"))) } }
        val rt = AgentRuntime(ToolRegistry(listOf(FakeTool())), waitForQueue = { false })

        val events = rt.run(userTurn, gen).toList()

        val error = events.last() as AgentEvent.Error
        assertTrue(error.cause is QueueBusyException)
        assertTrue(error.cause.message!!.contains("another task"))
    }

    @Test
    fun generatedCallIdsDoNotRepeatAcrossRepliesOrTurns() = runBlocking {
        suspend fun ids(): List<String> {
            val gen = ScriptedGenerator(reply(calls = listOf(call(""))), reply(calls = listOf(call(""))), reply("ok"))
            return runtime(FakeTool()).run(userTurn, gen).toList()
                .filterIsInstance<AgentEvent.ReplyFinished>().flatMap { r -> r.calls.map { it.id } }
        }

        val first = ids()
        val second = ids()

        assertEquals(2, first.toSet().size)
        assertEquals(4, (first + second).toSet().size)
    }

    @Test
    fun theDefaultTimeoutsAreTenSecondsAndThirtyForTheNetwork() {
        assertEquals(10_000L, ToolTier.Local.timeoutMs)
        assertEquals(10_000L, ToolTier.PhoneAction.timeoutMs)
        assertEquals(30_000L, ToolTier.Network.timeoutMs)
    }

    @Test
    fun anExceptionInAToolIsAnErrorResultNotACrash() = runBlocking {
        val tool = FakeTool(body = { throw IllegalStateException("boom") })
        val gen = ScriptedGenerator(reply(calls = listOf(call("a"))), reply("ok"))

        val events = runtime(tool).run(userTurn, gen).toList()

        val outcome = events.outcomes().single()
        assertEquals(ToolCallStatus.Error, outcome.status)
        assertTrue(outcome.output.contains("boom"))
        assertEquals(AgentEvent.Done, events.last())
    }

    @Test
    fun anErrorResultOfTheToolIsReportedAsAnError() = runBlocking {
        val tool = FakeTool(body = { ToolResult.error("no such city", destination = "open-meteo") })
        val gen = ScriptedGenerator(reply(calls = listOf(call("a"))), reply("ok"))

        val outcome = runtime(tool).run(userTurn, gen).toList().outcomes().single()

        assertEquals(ToolCallStatus.Error, outcome.status)
        assertEquals("no such city", outcome.output)
        assertEquals("open-meteo", outcome.destination)
    }

    @Test
    fun aRefusedConfirmationNeverRunsTheToolAndTheModelIsTold() = runBlocking {
        val tool = FakeTool(name = "send", sendsOrDeletes = true)
        val asked = mutableListOf<String>()
        val gate = ConfirmationGate { call, _ ->
            asked += call.id
            false
        }
        val gen = ScriptedGenerator(reply(calls = listOf(call("s", name = "send"))), reply("understood"))

        val events = runtime(tool, gate = gate).run(userTurn, gen).toList()

        assertEquals(listOf("s"), asked)
        assertEquals(0, tool.runs)
        val outcome = events.outcomes().single()
        assertEquals(ToolCallStatus.Refused, outcome.status)
        assertEquals(0L, outcome.durationMs)
        assertTrue(gen.requests[1].messages.last().content.contains("refused"))
    }

    @Test
    fun anApprovedConfirmationRunsTheTool() = runBlocking {
        val tool = FakeTool(name = "send", sendsOrDeletes = true)
        val gen = ScriptedGenerator(reply(calls = listOf(call("s", name = "send"))), reply("sent"))

        val events = runtime(tool, gate = { _, _ -> true }).run(userTurn, gen).toList()

        assertEquals(1, tool.runs)
        assertEquals(ToolCallStatus.Ok, events.outcomes().single().status)
    }

    @Test
    fun aToolThatDoesNotSendOrDeleteIsNeverAskedAbout() = runBlocking {
        val tool = FakeTool()
        var asked = 0
        val gen = ScriptedGenerator(reply(calls = listOf(call("a"))), reply("ok"))

        runtime(tool, gate = { _, _ -> asked++ >= 0 }).run(userTurn, gen).toList()

        assertEquals(0, asked)
        assertEquals(1, tool.runs)
    }

    @Test
    fun stopDuringAToolCancelsItAndEndsTheLoop() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val tool = FakeTool(body = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        })
        val gen = ScriptedGenerator(reply(calls = listOf(call("a"))), reply("never"))
        val events = mutableListOf<AgentEvent>()

        val job = launch { runtime(tool).run(userTurn, gen).collect { events += it } }
        entered.await()
        job.cancelAndJoin()

        // The tool runs detached on IO: it is cancelled, not necessarily by the time the turn is.
        withTimeout(5_000) { cancelled.await() }
        assertEquals(1, gen.requests.size)
        assertTrue(events.any { it is AgentEvent.ToolCallStarted })
        assertTrue(events.outcomes().isEmpty())
        assertFalse(events.contains(AgentEvent.Done))
    }

    @Test
    fun stopDuringAConfirmationEndsTheLoopWithoutRunningTheTool() = runBlocking {
        val asking = CompletableDeferred<Unit>()
        val tool = FakeTool(name = "send", sendsOrDeletes = true)
        val gate = ConfirmationGate { _, _ ->
            asking.complete(Unit)
            awaitCancellation()
        }
        val gen = ScriptedGenerator(reply(calls = listOf(call("s", name = "send"))), reply("never"))

        val job = launch { runtime(tool, gate = gate).run(userTurn, gen).collect { } }
        asking.await()
        job.cancelAndJoin()

        assertEquals(0, tool.runs)
        assertEquals(1, gen.requests.size)
    }

    @Test
    fun aStoppedOutcomeIsBuiltForTheCallsThatNeverRan() {
        val outcome = ToolCallOutcome.stopped(call("a"), ToolTier.Local, now = 7L)

        assertEquals(ToolCallStatus.Stopped, outcome.status)
        assertEquals(7L, outcome.startedAt)
        assertEquals("stopped", outcome.status.storageName)
    }

    @Test
    fun aReplyTheParserRejectedEndsTheTurnWithItsRawTextAndRunsNothing() = runBlocking {
        val tool = FakeTool()
        val gen = ScriptedGenerator(reply(content = "<tool_call>{broken", toolParseFailed = true))

        val events = runtime(tool).run(userTurn, gen).toList()

        assertEquals(0, tool.runs)
        val finished = events.filterIsInstance<AgentEvent.ReplyFinished>().single()
        assertTrue(finished.toolParseFailed)
        assertEquals("<tool_call>{broken", finished.content)
        assertTrue(finished.calls.isEmpty())
        assertEquals(AgentEvent.Done, events.last())
        assertEquals(1, gen.requests.size)
    }

    @Test
    fun aTruncatedReplyEndsTheTurnAndIsFlagged() = runBlocking {
        val gen = ScriptedGenerator(reply("a long answer cut o", truncated = true))

        val events = runtime(FakeTool()).run(userTurn, gen).toList()

        val finished = events.filterIsInstance<AgentEvent.ReplyFinished>().single()
        assertTrue(finished.truncated)
        assertEquals(AgentEvent.Done, events.last())
    }

    @Test
    fun aGenerationFailureEndsTheTurnWithAnError() = runBlocking {
        val boom = RuntimeException("native failure")
        val gen = ScriptedGenerator(listOf(DecodeEvent.Token("par"), DecodeEvent.Error(boom)))

        val events = runtime(FakeTool()).run(userTurn, gen).toList()

        assertEquals(AgentEvent.Error(boom), events.last())
        assertTrue(events.none { it is AgentEvent.ReplyFinished || it == AgentEvent.Done })
    }

    @Test
    fun callIdsTheTemplateLeftBlankOrRepeatedGetUniqueOnes() = runBlocking {
        val gen = ScriptedGenerator(reply(calls = listOf(call(""), call("x"), call("x"))), reply("ok"))

        val events = runtime(FakeTool()).run(userTurn, gen).toList()

        val ids = events.filterIsInstance<AgentEvent.ReplyFinished>().first().calls.map { it.id }
        assertEquals(3, ids.toSet().size)
        assertEquals("x", ids[1])
        assertEquals(ids, gen.requests[1].messages.filter { it.role == MessageRole.Tool }.map { it.toolCallId })
    }

    @Test
    fun aHugeResultIsCutToTheCharBudgetWhenTheEngineCannotCount() = runBlocking {
        val tool = FakeTool(body = { ToolResult.ok("x".repeat(10_000)) })
        val gen = ScriptedGenerator(reply(calls = listOf(call("a"))), reply("ok"))

        val outcome = runtime(tool).run(userTurn, gen).toList().outcomes().single()

        assertTrue(outcome.output.startsWith("x".repeat(2048)))
        assertTrue(outcome.output.endsWith("[truncated]"))
        assertTrue(outcome.output.length < 2100)
        // 4 chars per token estimate.
        assertEquals(512, outcome.resultTokens)
    }

    @Test
    fun theTokenizerCountDecidesTheCutWhenThereIsOne() = runBlocking {
        val tool = FakeTool(body = { ToolResult.ok("x".repeat(10_000)) })
        val gen = ScriptedGenerator(reply(calls = listOf(call("a"))), reply("ok"))

        // Two chars per token: the 2048-char body is 1024 tokens, twice the budget.
        val outcome = runtime(tool, countTokens = { it.length / 2 }).run(userTurn, gen).toList().outcomes().single()

        assertEquals("x".repeat(1024) + "\n[truncated]", outcome.output)
        assertEquals(512, outcome.resultTokens)
    }

    @Test
    fun aShortResultIsLeftAloneAndCounted() = runBlocking {
        val tool = FakeTool(body = { ToolResult.ok("short") })
        val gen = ScriptedGenerator(reply(calls = listOf(call("a"))), reply("ok"))

        val outcome = runtime(tool, countTokens = { 3 }).run(userTurn, gen).toList().outcomes().single()

        assertEquals("short", outcome.output)
        assertEquals(3, outcome.resultTokens)
    }

    @Test
    fun aCounterThatThrowsFallsBackToTheEstimate() = runBlocking {
        val tool = FakeTool(body = { ToolResult.ok("12345678") })
        val gen = ScriptedGenerator(reply(calls = listOf(call("a"))), reply("ok"))

        val outcome = runtime(tool, countTokens = { error("engine busy") }).run(userTurn, gen).toList().outcomes().single()

        assertEquals("12345678", outcome.output)
        assertEquals(2, outcome.resultTokens)
    }

    @Test
    fun withNoActiveToolTheTurnIsAPlainGeneration() = runBlocking {
        val gen = ScriptedGenerator(listOf(DecodeEvent.Token("hi"), DecodeEvent.Done(DecodeStats(1, 1f, 1L))))

        val events = AgentRuntime(ToolRegistry()).run(userTurn, gen).toList()

        assertTrue(gen.requests.single().tools.isEmpty())
        assertEquals(AgentEvent.Done, events.last())
        assertFalse(AgentRuntime(ToolRegistry()).hasTools())
    }
}
