package io.github.tensaiapp.bench

import io.github.tensaiapp.agent.AgentRuntime
import io.github.tensaiapp.agent.DateTimeTool
import io.github.tensaiapp.agent.ReplyGenerator
import io.github.tensaiapp.agent.Tool
import io.github.tensaiapp.agent.ToolRegistry
import io.github.tensaiapp.agent.ToolResult
import io.github.tensaiapp.agent.ToolTier
import io.github.tensaiapp.data.session.SaveResult
import io.github.tensaiapp.engine.DecodeEvent
import io.github.tensaiapp.engine.DecodeRequest
import io.github.tensaiapp.engine.DecodeStats
import io.github.tensaiapp.engine.MessageRole
import io.github.tensaiapp.engine.ReasoningBudget
import io.github.tensaiapp.engine.SamplingConfig
import io.github.tensaiapp.engine.StopReason
import io.github.tensaiapp.engine.ToolCall
import io.github.tensaiapp.runtime.QueueBusyException
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of the debug agent bench: its list, verdicts, totals, the gate that keeps it free of side effects, and its trigger. */
class AgentBenchTest {

    private class FakeTool(
        override val name: String,
        override val tier: ToolTier = ToolTier.Local,
        override val sendsOrDeletes: Boolean = false,
    ) : Tool {
        override val description = "test tool"
        override val parametersSchema = """{"type":"object","properties":{}}"""
        var runs = 0

        override suspend fun execute(argsJson: String): ToolResult {
            runs++
            return ToolResult.ok("ran $name")
        }
    }

    /** Plays [replies] one per generation, counting them. */
    private class ScriptedGenerator(vararg replies: List<DecodeEvent>) : ReplyGenerator {
        private val queue = ArrayDeque(replies.toList())
        var generations = 0
        val requests = mutableListOf<DecodeRequest>()

        override fun generate(request: DecodeRequest): Flow<DecodeEvent> = flow {
            generations++
            requests += request
            (queue.removeFirstOrNull() ?: error("no scripted reply left")).forEach { emit(it) }
        }
    }

    private fun reply(text: String, reasoning: String? = null, calls: List<ToolCall> = emptyList(), tokens: Int = 10, ttftMs: Long = 1L) = listOf(
        DecodeEvent.Token(text),
        DecodeEvent.Parsed(text, reasoning, emptyList()),
        DecodeEvent.ToolTurn(text, reasoning, calls, toolsIgnored = false, toolParseFailed = false, truncated = false),
        DecodeEvent.Done(DecodeStats(tokensGenerated = tokens, tokPerSec = 1f, ttftMs = ttftMs, stopReason = StopReason.Complete)),
    )

    private fun runner(
        vararg tools: Tool,
        gate: RefusingGate = RefusingGate(),
        generator: ReplyGenerator,
        system: ((Set<String>) -> String?)? = null,
        sampling: SamplingConfig = SamplingConfig(),
    ): BenchRunner {
        // Every tier granted: the bench refuses a network call whatever the user granted.
        val registry = ToolRegistry(tools.map(::BenchTool)) { ToolTier.entries.toSet() }
        var now = 0L
        return BenchRunner(
            // No wait for a busy queue: the turn ends on the first refusal.
            runtime = AgentRuntime(registry, gate, waitForQueue = { false }),
            gate = gate,
            generator = generator,
            sampling = sampling,
            countTokens = { it.length },
            clock = { now += 5; now },
            system = system,
        )
    }

    private fun prompt(id: String, vararg expected: String) = BenchPrompt(id, "question $id", expected.toSet())

    @Test
    fun theRepoListParsesWithHalfTheShortPromptsExpectingNoToolAndCappedLongFormAsks() {
        val prompts = parseBenchPrompts(File("src/debug/assets/bench/agent-prompts.json").readText())
        val (longForm, short) = prompts.partition { it.maxTokens != null }

        assertTrue("about 20 short prompts, got ${short.size}", short.size in 18..24)
        assertEquals(short.size / 2, short.count { it.expected.isEmpty() })
        assertTrue("a few long-form asks, got ${longForm.size}", longForm.size in 3..6)
        assertTrue(longForm.all { it.expected.isEmpty() })
        assertEquals(
            "Write a long story about a lighthouse keeper and a storm, at least 900 words.",
            prompts.single { it.id == "story-lighthouse" }.prompt,
        )
        assertEquals("Name two colors of the French flag, one line.", prompts.single { it.id == "flag-colors" }.prompt)
        assertEquals(setOf("datetime", "convert_time"), prompts.single { it.id == "time-tokyo" }.expected)
        assertEquals(setOf("set_timer"), prompts.single { it.id == "timer-ten-minutes" }.expected)
    }

    @Test
    fun expectedIsNoneAToolOrASet() {
        val prompts = parseBenchPrompts(
            """{"version":1,"prompts":[
                {"id":"a","prompt":"p","expected":"none"},
                {"id":"b","prompt":"p","expected":"calculate"},
                {"id":"c","prompt":"p","expected":["datetime","convert_time"]}]}""",
        )

        assertEquals(listOf(emptySet(), setOf("calculate"), setOf("datetime", "convert_time")), prompts.map { it.expected })
    }

    @Test
    fun maxTokensIsOptional() {
        val prompts = parseBenchPrompts(
            """{"version":1,"prompts":[
                {"id":"a","prompt":"p","expected":"none","max_tokens":96},
                {"id":"b","prompt":"p","expected":"none"}]}""",
        )

        assertEquals(listOf(96, null), prompts.map { it.maxTokens })
    }

    @Test(expected = IllegalArgumentException::class)
    fun aMaxTokensOfZeroIsRefused() {
        parseBenchPrompts("""{"prompts":[{"id":"a","prompt":"p","expected":"none","max_tokens":0}]}""")
    }

    @Test
    fun maxTokensCapsEveryReplyOfThePromptAndOnlyThatPrompt() = runBlocking {
        val sampling = SamplingConfig(maxTokens = 2048, reasoningBudget = ReasoningBudget.splitting(2048, "</think>"))
        val gen = ScriptedGenerator(
            reply("", calls = listOf(ToolCall("c1", "datetime", "{}"))),
            reply("Once upon a time"),
            reply("Paris"),
        )
        val bench = runner(FakeTool("datetime"), generator = gen, sampling = sampling)

        val capped = bench.run(BenchPrompt("story", "Answer at length.", emptySet(), maxTokens = 96))
        bench.run(BenchPrompt("capital", "Capital of France?", emptySet()))

        assertEquals(BenchVerdict.FalseCall, capped.verdict)
        // The reasoning keeps the run's 1024, the answer is held to 96: the sum stays the decode's cap.
        val answerOnly = ReasoningBudget(1024, 96, "</think>")
        assertEquals(listOf(1120, 1120, 2048), gen.requests.map { it.sampling.maxTokens })
        assertEquals(listOf(answerOnly, answerOnly, sampling.reasoningBudget), gen.requests.map { it.sampling.reasoningBudget })
    }

    @Test
    fun withoutThinkingTheCapIsTheWholeReply() = runBlocking {
        val gen = ScriptedGenerator(reply("The keeper climbed the stairs as the wind"))
        runner(FakeTool("datetime"), generator = gen).run(BenchPrompt("story", "Answer at length.", emptySet(), maxTokens = 8))

        assertEquals(8, gen.requests.single().sampling.maxTokens)
        assertNull(gen.requests.single().sampling.reasoningBudget)
    }

    @Test
    fun aCallTheCapCutShortIsCountedAndLogged() = runBlocking {
        // The engine drops a half-written call from the finished turn; the stream's parse had opened it.
        val cut = listOf(
            DecodeEvent.Parsed("", null, listOf("web_search")),
            DecodeEvent.ToolTurn("", null, emptyList(), toolsIgnored = false, toolParseFailed = false, truncated = true),
            DecodeEvent.Done(DecodeStats(tokensGenerated = 96, tokPerSec = 1f, ttftMs = 1L, stopReason = StopReason.TokenBudget)),
        )
        val gen = ScriptedGenerator(cut)
        val result = runner(FakeTool("web_search", tier = ToolTier.Network), generator = gen)
            .run(BenchPrompt("story", "Answer at length.", emptySet(), maxTokens = 96))

        assertEquals(BenchVerdict.FalseCall, result.verdict)
        assertTrue(result.calls.isEmpty())
        val line = Json.parseToJsonElement(result.toLogLine()).jsonObject
        assertEquals(listOf("web_search"), line.getValue("truncated_calls").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun aHalfWrittenNameThePromptDoesNotOfferIsNotACall() = runBlocking {
        val cut = listOf(
            DecodeEvent.Parsed("", null, listOf("web_se")),
            DecodeEvent.ToolTurn("", null, emptyList(), toolsIgnored = false, toolParseFailed = false, truncated = true),
            DecodeEvent.Done(DecodeStats(tokensGenerated = 96, tokPerSec = 1f, ttftMs = 1L, stopReason = StopReason.TokenBudget)),
        )
        val result = runner(FakeTool("web_search", tier = ToolTier.Network), generator = ScriptedGenerator(cut))
            .run(BenchPrompt("story", "Answer at length.", emptySet(), maxTokens = 96))

        assertEquals(BenchVerdict.Correct, result.verdict)
        assertTrue(result.truncatedCalls.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun aDuplicateIdIsRefused() {
        parseBenchPrompts("""{"prompts":[{"id":"a","prompt":"p","expected":"none"},{"id":"a","prompt":"q","expected":"none"}]}""")
    }

    @Test
    fun verdicts() {
        val none = emptySet<String>()
        val time = setOf("datetime", "convert_time")
        val all = setOf("datetime", "convert_time", "calculate", "convert")
        assertEquals(BenchVerdict.Correct, BenchVerdict.of(none, emptyList(), all))
        assertEquals(BenchVerdict.FalseCall, BenchVerdict.of(none, listOf("convert"), all))
        assertEquals(BenchVerdict.Missing, BenchVerdict.of(time, emptyList(), all))
        assertEquals(BenchVerdict.Correct, BenchVerdict.of(time, listOf("convert_time"), all))
        assertEquals(BenchVerdict.Correct, BenchVerdict.of(time, listOf("calculate", "datetime"), all))
        assertEquals(BenchVerdict.WrongTool, BenchVerdict.of(time, listOf("calculate"), all))
        assertEquals(BenchVerdict.Error, BenchVerdict.of(none, emptyList(), all, failed = true))
        assertEquals(
            listOf("correct", "false_call", "missing", "wrong_tool", "unavailable", "error"),
            BenchVerdict.entries.map { it.storageName },
        )
    }

    @Test
    fun anExpectedToolTheUserHasNotGrantedIsUnavailableNotMissingAndLeftOutOfTheRates() {
        // set_timer's tier off: the prompt offers no such tool.
        val offered = setOf("datetime", "calculate")
        assertEquals(BenchVerdict.Unavailable, BenchVerdict.of(setOf("set_timer"), emptyList(), offered))
        assertEquals(BenchVerdict.Unavailable, BenchVerdict.of(setOf("set_timer"), listOf("calculate"), offered))
        // One of a set is enough to judge.
        assertEquals(BenchVerdict.Missing, BenchVerdict.of(setOf("datetime", "convert_time"), emptyList(), offered))

        val summary = BenchSummary.of(
            listOf(
                result(setOf("set_timer"), BenchVerdict.Unavailable, 100, 1_000),
                result(setOf("calculate"), BenchVerdict.Missing, 100, 1_000),
                result(setOf("calculate"), BenchVerdict.Correct, 100, 1_000),
            ),
        )
        assertEquals(1, summary.counts[BenchVerdict.Unavailable])
        assertEquals(0.5, summary.missingRate!!, 0.0)
    }

    private fun result(expected: Set<String>, verdict: BenchVerdict, ttftMs: Long?, totalMs: Long, prefixCold: Boolean = false) = BenchResult(
        prompt = BenchPrompt("p", "p", expected),
        calls = emptyList(),
        refused = emptyList(),
        verdict = verdict,
        tokens = 0,
        reasoningTokens = 0,
        ttftMs = ttftMs,
        totalMs = totalMs,
        stopReason = null,
        prefixCold = prefixCold,
    )

    @Test
    fun summaryRatesAreOverThePromptsTheyCanHappenToAndErrorsAreLeftOut() {
        val none = emptySet<String>()
        val calc = setOf("calculate")
        val summary = BenchSummary.of(
            listOf(
                result(none, BenchVerdict.Correct, 100, 1_000),
                result(none, BenchVerdict.FalseCall, 300, 3_000),
                result(none, BenchVerdict.Correct, 200, 2_000),
                result(calc, BenchVerdict.Missing, null, 4_000),
                result(calc, BenchVerdict.Correct, 400, 5_000),
                result(calc, BenchVerdict.Error, 9_000, 90_000),
            ),
        )

        assertEquals(3, summary.counts[BenchVerdict.Correct])
        assertEquals(1, summary.counts[BenchVerdict.FalseCall])
        assertEquals(1, summary.counts[BenchVerdict.Missing])
        assertEquals(0, summary.counts[BenchVerdict.WrongTool])
        assertEquals(1, summary.counts[BenchVerdict.Error])
        assertEquals(0.333, summary.falseCallRate!!, 0.0)
        assertEquals(0.5, summary.missingRate!!, 0.0)
        // ttft: 100, 200, 300, 400 (the missing one streamed nothing): even count, mean of the middle two.
        assertEquals(250L, summary.medianTtftMs)
        assertEquals(3_000L, summary.medianTotalMs)
        assertNull(BenchSummary.of(emptyList()).falseCallRate)
    }

    @Test
    fun theGateRefusesAndRecordsWhatTheBenchMustNotRunAndRunsThePureTools() = runBlocking {
        val timer = FakeTool("set_timer", ToolTier.PhoneAction)
        val search = FakeTool("web_search", ToolTier.Network)
        val forgetful = FakeTool("memory", sendsOrDeletes = true)
        val calculate = FakeTool("calculate")
        val gen = ScriptedGenerator(
            reply(
                "",
                reasoning = "four",
                calls = listOf(
                    ToolCall("1", "set_timer", """{"seconds":600}"""),
                    ToolCall("2", "web_search", """{"query":"x"}"""),
                    ToolCall("3", "memory", """{"action":"forget","id":1}"""),
                    ToolCall("4", "calculate", """{"expression":"1+1"}"""),
                ),
            ),
            reply("done", tokens = 4),
        )
        val gate = RefusingGate()

        val result = runner(timer, search, forgetful, calculate, gate = gate, generator = gen).run(prompt("timer", "set_timer"))

        assertEquals(0, timer.runs)
        assertEquals(0, search.runs)
        assertEquals(0, forgetful.runs)
        assertEquals(1, calculate.runs)
        assertEquals(listOf("set_timer", "web_search", "memory"), result.refused)
        assertEquals(listOf("set_timer", "web_search", "memory", "calculate"), result.calls.map { it.name })
        assertEquals(BenchVerdict.Correct, result.verdict)
        assertEquals(14, result.tokens)
        assertEquals(4, result.reasoningTokens)
        assertEquals("Complete", result.stopReason)
        assertTrue(gate.drain().isEmpty())
    }

    @Test
    fun memoryListRunsOtherMemoryActionsDoNot() {
        assertTrue(benchRuns("memory", """{"action":"list"}"""))
        assertTrue(!benchRuns("memory", """{"action":"remember","text":"x"}"""))
        assertTrue(!benchRuns("torch", """{"on":true}"""))
        assertTrue(!benchRuns("ask_user", "{}"))
    }

    @Test
    fun aCallWhenNoneWasExpectedIsLoggedAsAFalseCall() = runBlocking {
        val gen = ScriptedGenerator(
            reply("", calls = listOf(ToolCall("1", "convert", "{\"value\":1,\"from\":\"${"x".repeat(200)}\"}"))),
            reply("blue, white"),
        )

        val result = runner(FakeTool("convert"), generator = gen).run(prompt("flag-colors"))
        val line = Json.parseToJsonElement(result.toLogLine()).jsonObject

        assertEquals("false_call", line.getValue("verdict").jsonPrimitive.content)
        assertEquals("none", line.getValue("expected").jsonPrimitive.content)
        val args = line.getValue("calls").jsonArray.single().jsonObject.getValue("args").jsonPrimitive.content
        assertEquals(BenchResult.MaxArgsChars + 3, args.length)
    }

    @Test
    fun ttftIsTheEngineFigureOfTheFirstReplyAndTotalIsWallClock() = runBlocking {
        val gen = ScriptedGenerator(
            reply("", calls = listOf(ToolCall("1", "calculate", "{}")), ttftMs = 42),
            reply("2", ttftMs = 7),
        )

        val result = runner(FakeTool("calculate"), generator = gen).run(prompt("sum", "calculate"))

        assertEquals(42L, result.ttftMs)
        // The test clock moves 5 ms a read: the start and the end of the turn.
        assertEquals(5L, result.totalMs)
    }

    @Test
    fun aColdFirstPromptIsMarkedAndLeftOutOfTheMedians() = runBlocking {
        val gen = ScriptedGenerator(reply("a", ttftMs = 9_000), reply("b", ttftMs = 100))
        val lines = mutableListOf<String>()

        val results = runBench(listOf(prompt("one"), prompt("two")), runner(generator = gen), firstCold = true, beforeEach = { null }, log = { lines += it })

        assertEquals(listOf(true, false), results.map { it.prefixCold })
        assertTrue(lines[0].contains("\"prefix_cold\":true"))
        assertTrue(!lines[1].contains("prefix_cold"))
        assertEquals(100L, BenchSummary.of(results).medianTtftMs)
    }

    @Test
    fun aConversationSentToBetweenTwoPromptsIsSavedBeforeTheNextPromptTakesTheCache() = runBlocking {
        val gen = ScriptedGenerator(reply("a"), reply("b"))
        var owner: Pair<String, String>? = null
        val saved = mutableListOf<Pair<String, Int>>()
        val beforeEach = suspend {
            val ok = handOverCache(
                owner,
                save = { id, _ -> saved += id to gen.generations; SaveResult.Saved(10, 100) },
                forget = { id -> if (owner?.first == id) owner = null },
            )
            if (ok) null else "save failed"
        }

        val results = runBench(
            listOf(prompt("one"), prompt("two")),
            runner(generator = gen),
            firstCold = false,
            beforeEach = beforeEach,
            // A chat send right after the first prompt: the cache is now conversation X's.
            log = { if (it.contains("\"id\":\"one\"")) owner = "X" to "key" },
        )

        assertEquals(2, results.size)
        // Saved after the first prompt's generation, before the second's, and forgotten.
        assertEquals(listOf("X" to 1), saved)
        assertNull(owner)
    }

    @Test
    fun aFailedSaveStopsTheRunAndKeepsTheOwner() = runBlocking {
        val gen = ScriptedGenerator(reply("a"), reply("b"))
        val owner = "X" to "key"
        var forgotten = false
        val lines = mutableListOf<String>()

        val results = runBench(
            listOf(prompt("one")),
            runner(generator = gen),
            firstCold = false,
            beforeEach = {
                if (handOverCache(owner, save = { _, _ -> SaveResult.NotAttempted }, forget = { forgotten = true })) null else "save failed"
            },
            log = { lines += it },
        )

        assertTrue(results.isEmpty())
        assertEquals(0, gen.generations)
        assertTrue(!forgotten)
        assertTrue(lines.single().contains("\"event\":\"error\""))
    }

    @Test
    fun aTurnTheQueueRefusesStopsTheRun() = runBlocking {
        val gen = ScriptedGenerator(listOf(DecodeEvent.Error(QueueBusyException("no model"))), reply("b"))
        val lines = mutableListOf<String>()

        val results = runBench(listOf(prompt("one"), prompt("two")), runner(generator = gen), firstCold = false, beforeEach = { null }, log = { lines += it })

        assertEquals(listOf(BenchVerdict.Error), results.map { it.verdict })
        assertEquals(1, gen.generations)
        assertTrue(lines.last().contains("\"event\":\"error\""))
    }

    @Test
    fun theBasePromptAndExamplesTogglesDefaultToWhatAChatSends() {
        assertEquals(
            BenchTrigger(thinking = true, basePrompt = true, examples = false),
            benchTrigger(debug = true, bench = "agent", thinking = null, defaultThinking = true),
        )
        assertEquals(
            BenchTrigger(thinking = false, basePrompt = true, examples = true),
            benchTrigger(debug = true, bench = "agent", thinking = false, defaultThinking = true, basePrompt = true, examples = true),
        )
        // The examples ride in the base prompt: asked for without it, they are off.
        assertEquals(
            BenchTrigger(thinking = true, basePrompt = false, examples = false),
            benchTrigger(debug = true, bench = "agent", thinking = null, defaultThinking = true, basePrompt = false, examples = true),
        )
    }

    @Test
    fun theToolGrammarIsOnUnlessTurnedOffAndShownInTheSummary() {
        assertTrue(benchTrigger(debug = true, bench = "agent", thinking = null, defaultThinking = true)!!.toolGrammar)
        val off = benchTrigger(debug = true, bench = "agent", thinking = null, defaultThinking = true, toolGrammar = false)!!
        assertFalse(off.toolGrammar)
        val shown = BenchSummary.of(emptyList()).toDisplayLines(off, "m", emptyList())
        assertTrue(shown.any { it.endsWith("tool grammar off") })
        assertEquals("false", Json.parseToJsonElement(BenchSummary.of(emptyList()).toLogLine(off)).jsonObject.getValue("tool_grammar").jsonPrimitive.content)
    }

    @Test
    fun theSystemMessageOpensTheWarmedPrefixAndEveryPrompt() = runBlocking {
        val gen = ScriptedGenerator(reply("blue"))
        val runner = runner(FakeTool("calculate"), generator = gen, system = { "base for ${it.sorted().joinToString()}" })

        assertEquals(listOf(MessageRole.System), runner.prefixRequest().messages.map { it.role })
        assertEquals("base for calculate", runner.prefixRequest().messages.single().content)
        runner.run(prompt("flag-colors"))
        val sent = gen.requests.single().messages
        assertEquals(listOf(MessageRole.System, MessageRole.User), sent.map { it.role })
        assertEquals("base for calculate", sent.first().content)
    }

    @Test
    fun aCreativePromptGetsTheNoToolsSystemMessageAsInAChatAndThePrefixKeepsTheTools() = runBlocking {
        val gen = ScriptedGenerator(reply("Once upon a time"))
        val runner = runner(FakeTool("web_search", tier = ToolTier.Network), generator = gen, system = { if (it.isEmpty()) "plain" else "tools" })

        val result = runner.run(BenchPrompt("story-lighthouse", "Write a long story about a lighthouse keeper and a storm, at least 900 words.", emptySet()))

        assertEquals("tools", runner.prefixRequest().messages.single().content)
        val sent = gen.requests.single()
        assertTrue(sent.tools.isEmpty())
        assertEquals("plain", sent.messages.first().content)
        assertEquals(BenchVerdict.Correct, result.verdict)
    }

    @Test
    fun withoutASystemMessageAPromptIsTheUserMessageAlone() = runBlocking {
        val gen = ScriptedGenerator(reply("blue"))
        val runner = runner(FakeTool("calculate"), generator = gen)

        assertTrue(runner.prefixRequest().messages.isEmpty())
        runner.run(prompt("flag-colors"))
        assertEquals(listOf(MessageRole.User), gen.requests.single().messages.map { it.role })
    }

    @Test
    fun theSummaryNamesTheTogglesInTheLogAndOnScreen() = runBlocking {
        val gen = ScriptedGenerator(reply("blue"), reply("", calls = listOf(ToolCall("1", "calculate", "{}"))), reply("56"))
        val runner = runner(FakeTool("calculate"), generator = gen)
        val results = runBench(listOf(prompt("colors"), prompt("times")), runner, firstCold = false, beforeEach = { null }, log = {})
        val trigger = BenchTrigger(thinking = false, basePrompt = true, examples = true)
        val summary = BenchSummary.of(results)

        val line = Json.parseToJsonElement(summary.toLogLine(trigger)).jsonObject
        assertEquals("true", line.getValue("base_prompt").jsonPrimitive.content)
        assertEquals("true", line.getValue("examples").jsonPrimitive.content)
        assertEquals("true", line.getValue("tool_grammar").jsonPrimitive.content)
        val shown = summary.toDisplayLines(trigger, "Qwen3-1.7B", results)
        assertEquals("Qwen3-1.7B", shown.first())
        assertTrue(shown.contains("thinking off · base prompt on · examples on · tool grammar on"))
        assertTrue(shown.any { it.startsWith("prompts 2: correct 1 false_call 1") })
        assertTrue("the wrong prompt is named", shown.contains("false_call: times"))
    }

    @Test
    fun aToolWrittenOutAsACallIsNamedInTextAPlainWordIsNot() {
        val tools = setOf("datetime", "calculate", "convert", "set_timer")

        assertEquals(listOf("datetime"), toolsNamedInText("You: call datetime, then answer from its result.", tools))
        assertEquals(listOf("calculate"), toolsNamedInText("I would use calculate(48291*7364).", tools))
        assertEquals(listOf("convert"), toolsNamedInText("""{"name": "convert", "arguments": {}}""", tools))
        assertEquals(listOf("set_timer"), toolsNamedInText("Use set_timer for that.", tools))
        assertEquals(listOf("calculate"), toolsNamedInText("Try `calculate`.", tools))
        assertTrue(toolsNamedInText("To convert miles, multiply by 1.609. I can calculate it: 42.2 km.", tools).isEmpty())
    }

    @Test
    fun aReplyThatNamesAToolInsteadOfCallingItIsCountedWhateverItsVerdict() = runBlocking {
        val gen = ScriptedGenerator(reply("call datetime"), reply("blue, white"))
        val runner = runner(FakeTool("datetime"), generator = gen)

        val results = runBench(listOf(prompt("time", "datetime"), prompt("colors")), runner, firstCold = false, beforeEach = { null }, log = {})

        assertEquals(listOf(listOf("datetime"), emptyList<String>()), results.map { it.namedInText })
        assertEquals(BenchVerdict.Missing, results.first().verdict)
        val summary = BenchSummary.of(results)
        assertEquals(1, summary.namedInText)
        assertEquals("1", Json.parseToJsonElement(summary.toLogLine(BenchTrigger(true))).jsonObject.getValue("named_in_text").jsonPrimitive.content)
        assertTrue(Json.parseToJsonElement(results.first().toLogLine()).jsonObject.containsKey("named_in_text"))
        assertTrue(summary.toDisplayLines(BenchTrigger(true), "m", results).contains("named_in_text 1: time (datetime)"))
    }

    @Test
    fun aCallWhoseResultIsAnErrorIsLoggedAndCountedTheVerdictUnchanged() = runBlocking {
        val bad = ToolCall("c1", "datetime", """{"timezone":"Mars/Olympus"}""")
        val good = ToolCall("c2", "datetime", "{}")
        val gen = ScriptedGenerator(reply("", calls = listOf(bad)), reply("I could not tell."), reply("", calls = listOf(good)), reply("It is noon."))
        val runner = runner(DateTimeTool(), generator = gen)

        val results = runBench(listOf(prompt("time", "datetime"), prompt("time_ok", "datetime")), runner, firstCold = false, beforeEach = { null }, log = {})

        assertEquals(listOf(BenchVerdict.Correct, BenchVerdict.Correct), results.map { it.verdict })
        assertEquals(results.first().calls.map { it.id }.toSet(), results.first().callErrors.keys)
        assertTrue(results.last().callErrors.isEmpty())
        val call = Json.parseToJsonElement(results.first().toLogLine()).jsonObject.getValue("calls").jsonArray.single().jsonObject
        assertTrue(call.getValue("error").jsonPrimitive.content, call.getValue("error").jsonPrimitive.content.startsWith("timezone: unknown time zone 'Mars/Olympus'"))
        assertFalse(Json.parseToJsonElement(results.last().toLogLine()).jsonObject.getValue("calls").jsonArray.single().jsonObject.containsKey("error"))
        val summary = BenchSummary.of(results)
        assertEquals(1, summary.toolErrors)
        assertEquals("1", Json.parseToJsonElement(summary.toLogLine(BenchTrigger(true))).jsonObject.getValue("tool_errors").jsonPrimitive.content)
        assertTrue(summary.toDisplayLines(BenchTrigger(true), "m", results).contains("tool_errors 1: time (datetime)"))
    }

    @Test
    fun eachResultIsReportedAsItComes() = runBlocking {
        val gen = ScriptedGenerator(reply("a"), reply("b"))
        val seen = mutableListOf<String>()

        runBench(listOf(prompt("one"), prompt("two")), runner(generator = gen), firstCold = false, beforeEach = { null }, log = {}, onResult = { seen += it.prompt.id })

        assertEquals(listOf("one", "two"), seen)
    }

    @Test
    fun theResultKeepsTheTurnsReplyAndReasoningForTheBenchScreen() = runBlocking {
        val gen = ScriptedGenerator(
            reply("", reasoning = "the user wants the time", calls = listOf(ToolCall("c1", "datetime", "{}"))),
            reply("It is ten.", reasoning = "the tool said 10:00"),
            reply("Paris"),
        )
        val bench = runner(FakeTool("datetime"), generator = gen)

        val called = bench.run(prompt("time", "datetime"))
        val plain = bench.run(prompt("capital"))

        assertEquals("It is ten.", called.reply)
        assertEquals("the user wants the time\n\nthe tool said 10:00", called.reasoning)
        assertEquals("Paris", plain.reply)
        assertNull(plain.reasoning)
        // Never in the log: the screen keeps it, logcat does not.
        assertFalse(called.toLogLine().contains("It is ten."))
    }

    @Test
    fun eachPromptRunsItsRepetitionsInARowAndOnlyTheFirstTrialIsCold() = runBlocking {
        val gen = ScriptedGenerator(*Array(6) { reply("r$it") })
        val lines = mutableListOf<String>()
        val started = mutableListOf<Pair<String, Int>>()

        val results = runBench(
            listOf(prompt("one"), prompt("two")),
            runner(generator = gen),
            firstCold = true,
            beforeEach = { null },
            log = { lines += it },
            repetitions = 3,
            onStart = { p, rep -> started += p.id to rep },
        )

        assertEquals(listOf("one", "one", "one", "two", "two", "two"), results.map { it.prompt.id })
        assertEquals(listOf(1, 2, 3, 1, 2, 3), results.map { it.repetition })
        assertEquals(listOf(true, false, false, false, false, false), results.map { it.prefixCold })
        assertEquals(results.map { it.prompt.id to it.repetition }, started)
        assertFalse(lines[0].contains("\"rep\""))
        assertTrue(lines[1].contains("\"rep\":2"))
        assertEquals(6, BenchSummary.of(results).counts.values.sum())
    }

    @Test
    fun greedyIsTemperatureZeroOverTheChatsSamplingAndOffKeepsItWhole() {
        val chat = SamplingConfig(temperature = 0.8f, topK = 40, maxTokens = 2048)

        val greedy = BenchTrigger(thinking = true, toolGrammar = false, greedy = true).sampling(chat)
        val asToday = BenchTrigger(thinking = true).sampling(chat)

        assertEquals(chat.copy(temperature = 0f, constrainToolCalls = false), greedy)
        assertEquals(chat, asToday)
        assertTrue(BenchSummary.of(emptyList()).toLogLine(BenchTrigger(true, greedy = true)).contains("\"greedy\":true"))
        assertFalse(BenchSummary.of(emptyList()).toLogLine(BenchTrigger(true)).contains("greedy"))
    }

    @Test
    fun repetitionsAreOneToFive() {
        assertEquals(1, BenchTrigger(thinking = true).repetitions)
        assertEquals(5, BenchTrigger(thinking = true, repetitions = 5).repetitions)
        assertTrue(runCatching { BenchTrigger(thinking = true, repetitions = 0) }.isFailure)
        assertTrue(runCatching { BenchTrigger(thinking = true, repetitions = 6) }.isFailure)
    }

    @Test
    fun onlyADebugBuildHonoursTheTrigger() {
        assertNull(benchTrigger(debug = false, bench = "agent", thinking = false, defaultThinking = true))
        assertNull(benchTrigger(debug = true, bench = null, thinking = null, defaultThinking = true))
        assertNull(benchTrigger(debug = true, bench = "other", thinking = null, defaultThinking = true))
        assertEquals(BenchTrigger(thinking = true), benchTrigger(debug = true, bench = "agent", thinking = null, defaultThinking = true))
        assertEquals(BenchTrigger(thinking = false), benchTrigger(debug = true, bench = "agent", thinking = false, defaultThinking = true))
        assertEquals(BenchTrigger(thinking = true, greedy = true), benchTrigger(debug = true, bench = "agent", thinking = null, defaultThinking = true, greedy = true))
        assertFalse(benchTrigger(debug = true, bench = "agent", thinking = null, defaultThinking = true, greedy = false)!!.greedy)
    }

    @Test
    fun `one bench at a time, whichever starts it`() {
        assertTrue(BenchGate.tryEnter())
        try {
            assertFalse("a second run is refused while the first holds the gate", BenchGate.tryEnter())
        } finally {
            BenchGate.leave()
        }
        assertTrue(BenchGate.tryEnter())
        BenchGate.leave()
    }

    @Test
    fun `the gate's busy state follows whoever holds it`() {
        assertFalse(BenchGate.busy.value)
        assertTrue(BenchGate.tryEnter())
        try {
            assertTrue("the screens lock what competes for the engine", BenchGate.busy.value)
        } finally {
            BenchGate.leave()
        }
        assertFalse(BenchGate.busy.value)
    }
}
