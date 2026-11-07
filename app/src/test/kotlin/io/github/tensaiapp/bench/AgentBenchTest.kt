package io.github.tensaiapp.bench

import io.github.tensaiapp.agent.AgentRuntime
import io.github.tensaiapp.agent.BasePrompt
import io.github.tensaiapp.agent.DateTimeTool
import io.github.tensaiapp.agent.ReplyGenerator
import io.github.tensaiapp.agent.Tool
import io.github.tensaiapp.agent.ToolPolicy
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
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
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

    private fun reply(text: String, reasoning: String? = null, calls: List<ToolCall> = emptyList(), tokens: Int = 10, ttftMs: Long = 1L, stop: StopReason = StopReason.Complete) = listOf(
        DecodeEvent.Token(text),
        DecodeEvent.Parsed(text, reasoning, emptyList()),
        DecodeEvent.ToolTurn(text, reasoning, calls, toolsIgnored = false, toolParseFailed = false, truncated = false),
        DecodeEvent.Done(DecodeStats(tokensGenerated = tokens, tokPerSec = 1f, ttftMs = ttftMs, stopReason = stop)),
    )

    private fun runner(
        vararg tools: Tool,
        gate: RefusingGate = RefusingGate(),
        generator: ReplyGenerator,
        system: ((Set<String>) -> String?)? = null,
        sampling: SamplingConfig = SamplingConfig(),
        toolPolicy: ToolPolicy = ToolPolicy.Current,
    ): BenchRunner {
        // Every tier granted: the bench refuses a network call whatever the user granted.
        val registry = ToolRegistry(tools.map(::BenchTool)) { ToolTier.entries.toSet() }
        var now = 0L
        return BenchRunner(
            // No wait for a busy queue: the turn ends on the first refusal.
            runtime = AgentRuntime(registry, gate, waitForQueue = { false }, toolPolicy = toolPolicy),
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
        val prompts = parseBenchPrompts(File("src/debug/assets/bench/agent-prompts.json").readText()).filter { "reasoning" !in it.tags }
        val (longForm, short) = prompts.partition { it.maxTokens != null }

        assertTrue("about 20 short prompts, got ${short.size}", short.size in 18..24)
        assertEquals(short.size / 2, short.count { it.expected.isEmpty() })
        // Never more long-form asks than short ones: the short ones hold the tool / no-tool balance.
        assertTrue("a few long-form asks, got ${longForm.size}", longForm.size in 3..short.size)
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
    fun theCreativeAsksOfTheToolPolicyRunsCallNoToolAndAreCapped() {
        val prompts = parseBenchPrompts(File("src/debug/assets/bench/agent-prompts.json").readText()).associateBy { it.id }

        // When added (TEN-197), CreativeRequest missed the first four (a song, a verse, a dialogue: nouns it does not
        // list; "30 ans": a digit) and caught the French story, a control for the current policy.
        listOf("song-autumn-fr", "rap-city", "dialogue-couch", "poem-sister-fr", "story-dragon-fr").forEach { id ->
            val prompt = prompts.getValue(id)
            assertTrue(id, prompt.expected.isEmpty() && prompt.maxTokens != null)
        }
    }

    // Debug builds only: BuildConfig.DEBUG gates StrictLine, so always-strict fails under testReleaseUnitTest.
    @Test
    fun eachToolPolicyOffersItsToolsAndSystemMessageOnACreativeAndOnAToolPromptOnTheWarmedPrefix() = runBlocking {
        val day = LocalDate.of(2026, 10, 24)
        val clock = Clock.fixed(day.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)
        val story = BenchPrompt("story", "Write a long story about a lighthouse keeper.", emptySet())
        val time = BenchPrompt("time", "What time is it right now?", setOf("datetime"))

        for (policy in ToolPolicy.entries) {
            val prompts = BenchTrigger(thinking = true, toolPolicy = policy).systemPrompt(clock)
            val gen = ScriptedGenerator(reply("Once"), reply("Noon"))
            val runner = runner(FakeTool("datetime"), generator = gen, system = { prompts.build(it, null, null, null) }, toolPolicy = policy)

            runner.run(story)
            runner.run(time)

            val (creative, tool) = gen.requests
            val withTools = BasePrompt.render(setOf("datetime"), day, strict = policy.strictPrompt)
            assertEquals(policy.storageName, policy.strictPrompt, BasePrompt.StrictLine in withTools.lines())
            if (policy == ToolPolicy.Current) {
                // Today's rule: a creative first turn gets no tools and the no-tools text.
                assertEquals(emptyList<String>(), creative.tools.map { it.name })
                assertEquals(BasePrompt.render(emptySet(), day), creative.messages.first().content)
            } else {
                assertEquals(policy.storageName, listOf("datetime"), creative.tools.map { it.name })
                assertEquals(policy.storageName, withTools, creative.messages.first().content)
            }
            assertEquals(policy.storageName, listOf("datetime"), tool.tools.map { it.name })
            assertEquals(policy.storageName, withTools, tool.messages.first().content)
            assertEquals(policy.storageName, policy == ToolPolicy.AlwaysStrict, BasePrompt.StrictLine in tool.messages.first().content.lines())
            // The prefix the bench warms is the system message of every turn with tools, the strict line included.
            assertEquals(policy.storageName, withTools, runner.prefixRequest().messages.single().content)
        }
    }

    @Test
    fun theToolPolicyIsTheChatsUnlessNamedAndShownInTheSummary() {
        assertEquals(ToolPolicy.Current, benchTrigger(debug = true, bench = "agent", thinking = null, defaultThinking = true)!!.toolPolicy)
        assertEquals(ToolPolicy.Current, benchTrigger(debug = true, bench = "agent", thinking = null, defaultThinking = true, toolPolicy = "never")!!.toolPolicy)
        assertEquals(ToolPolicy.Always, benchTrigger(debug = true, bench = "agent", thinking = null, defaultThinking = true, toolPolicy = "always")!!.toolPolicy)
        val strict = benchTrigger(debug = true, bench = "agent", thinking = null, defaultThinking = true, toolPolicy = "always-strict")!!
        assertEquals(ToolPolicy.AlwaysStrict, strict.toolPolicy)
        // The strict line rides in the base prompt: without it, the run is the one it really is.
        assertEquals(
            ToolPolicy.Always,
            benchTrigger(debug = true, bench = "agent", thinking = null, defaultThinking = true, basePrompt = false, toolPolicy = "always-strict")!!.toolPolicy,
        )

        assertEquals("always-strict", Json.parseToJsonElement(BenchSummary.of(emptyList()).toLogLine(strict)).jsonObject.getValue("tool_policy").jsonPrimitive.content)
        assertTrue(BenchSummary.of(emptyList()).toDisplayLines(strict, "m", emptyList())[1].endsWith(" · tools always-strict"))
        assertFalse(BenchSummary.of(emptyList()).toDisplayLines(BenchTrigger(thinking = true), "m", emptyList())[1].contains("tools"))
    }

    @Test
    fun theReasoningPromptsCallNoToolAndTheirAnswersHoldWhatWorkingItOutGivesNotTheUsualSlips() {
        val reasoning = parseBenchPrompts(File("src/debug/assets/bench/agent-prompts.json").readText()).filter { "reasoning" in it.tags }

        assertEquals(listOf("reason-money-left", "reason-ages", "reason-seats", "reason-letter-count", "reason-laundry"), reasoning.map { it.id })
        assertTrue(reasoning.all { it.expected.isEmpty() && it.maxTokens == null })
        val answers = reasoning.associate { it.id to it.answer!! }
        // 20 - 3 × 4 - 4 / 2. Slips: forgetting the pen (8), the pen at full price (4).
        assertTrue(answers.getValue("reason-money-left").matches("20 - 12 - 2 = 6"))
        listOf("8", "4 euros").forEach { assertFalse(it, answers.getValue("reason-money-left").matches(it)) }
        // 2a + 5 + a + 5 = 34. Slips: Tom's age (16), forgetting one of the two 5 years (9.67, 9), halving 34 - 10 (12).
        assertTrue(answers.getValue("reason-ages").matches("Ana is 8 years old (Tom is 16)."))
        listOf("16", "9.67", "9", "12").forEach { assertFalse(it, answers.getValue("reason-ages").matches(it)) }
        // Bea 3, Cal 4, Dan 2 (the only seat left off the ends), Eve 1 left of Ali 5.
        assertTrue(answers.getValue("reason-seats").matches("Eve."))
        listOf("Ali", "Dan", "Bea", "Evelyn").forEach { assertFalse(it, answers.getValue("reason-seats").matches(it)) }
        // e-x-c-e-l-l-e-n-c-e.
        assertTrue(answers.getValue("reason-letter-count").matches("4"))
        listOf("3", "5").forEach { assertFalse(it, answers.getValue("reason-letter-count").matches(it)) }
        // The dryer is the bottleneck: load 2 dries 50-80 behind load 1, then folds 80-90. Slips: in series (120), one
        // load's time plus a wash (80), overlapping everything (60).
        assertTrue(answers.getValue("reason-laundry").matches("At minute 90."))
        listOf("120", "80", "60", "70").forEach { assertFalse(it, answers.getValue("reason-laundry").matches(it)) }
    }

    @Test
    fun tagsAreOptionalNames() {
        val prompts = parseBenchPrompts(
            """{"version":1,"prompts":[
                {"id":"a","prompt":"p","expected":"none","tags":["reasoning"]},
                {"id":"b","prompt":"p","expected":"none"}]}""",
        )

        assertEquals(listOf(setOf("reasoning"), emptySet()), prompts.map { it.tags })
    }

    @Test
    fun aTaggedPromptIsCountedInItsGroupsAnswersAndCallingAToolThereIsAFalseCall() = runBlocking {
        val gen = ScriptedGenerator(
            reply("6"),
            reply("", calls = listOf(ToolCall("c1", "calculate", "{}"))),
            reply("It is 4."),
            reply("Paris"),
        )
        val bench = runner(FakeTool("calculate"), generator = gen)
        val prompts = listOf(
            BenchPrompt("money", "q", emptySet(), answer = AnswerCheck.Number(6.0), tags = setOf("reasoning")),
            BenchPrompt("ages", "q", emptySet(), answer = AnswerCheck.Number(8.0), tags = setOf("reasoning")),
            BenchPrompt("capital", "q", emptySet(), answer = AnswerCheck.Text("Paris")),
        )
        val lines = mutableListOf<String>()

        val results = runBench(prompts, bench, firstCold = false, beforeEach = { null }, log = { lines += it })

        assertEquals(listOf(BenchVerdict.Correct, BenchVerdict.FalseCall, BenchVerdict.Correct), results.map { it.verdict })
        assertTrue(lines[0].contains("\"tags\":[\"reasoning\"]"))
        assertFalse(lines[2].contains("tags"))
        val summary = BenchSummary.of(results)
        assertEquals(mapOf("reasoning" to (1 to 2)), summary.tagAnswers)
        val line = Json.parseToJsonElement(summary.toLogLine(BenchTrigger(true))).jsonObject
        assertEquals("1", line.getValue("reasoning_ok").jsonPrimitive.content)
        assertEquals("2", line.getValue("reasoning_checked").jsonPrimitive.content)
        assertTrue(summary.toDisplayLines(BenchTrigger(true), "m", results).contains("reasoning_ok 1/2"))
        assertFalse(BenchSummary.of(results.takeLast(1)).toLogLine(BenchTrigger(true)).contains("reasoning_ok"))
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

    @Test
    fun aNumberAnswerIsAnyNumberOfTheReplyWithinItsToleranceWhateverTheUnitOrFormat() {
        val celsius = AnswerCheck.Number(37.0, tolerance = 0.05)

        listOf("37 °C", "37C", "It is 37.0 degrees.", "98.6°F is about 37 degrees Celsius.", "**37**", "\\boxed{37}").forEach {
            assertTrue(it, celsius.matches(it))
        }
        listOf("36.9 °C", "66.6 °C", "370 C", "3.7", "no idea").forEach { assertFalse(it, celsius.matches(it)) }
        assertTrue(AnswerCheck.Number(355614924.0).matches("48,291 × 7,364 = 355,614,924"))
        assertTrue(AnswerCheck.Number(355614924.0).matches("355\u202F614\u202F924"))
        assertFalse(AnswerCheck.Number(355614924.0).matches("355,614,925"))
        assertTrue(AnswerCheck.Number(5.0).matches("Half of 10 is 5."))
        assertFalse("a number is read whole, not a digit of it", AnswerCheck.Number(5.0).matches("Half of 10 is 0.5"))
        assertTrue(AnswerCheck.Number(-40.0).matches("It is \u221240 °C."))
        assertFalse("a minus between operands is not a sign", AnswerCheck.Number(-3.0).matches("2-3 is not a date"))
        assertTrue("1.2e6 is one number", AnswerCheck.Number(1_200_000.0).matches("about 1.2e6 people"))
        assertFalse("1.2e6 is not 6", AnswerCheck.Number(6.0).matches("about 1.2e6 people"))
        assertTrue(AnswerCheck.Number(0.5).matches("Half of 1 is .5"))
        assertEquals("37 ± 0.05", celsius.shown)
        assertEquals("802.9675 ± 0.0001", AnswerCheck.Number(802.9675, 0.0001).shown)
        assertEquals("355614924", AnswerCheck.Number(355614924.0).shown)
    }

    @Test
    fun aNumberRoundedToItsOwnDecimalsHoldsTheExactValueATruncatedOneDoesNot() {
        val quotient = AnswerCheck.Number(98765.0 / 123)

        listOf("802.96747967", "802.9675", "802.97", "803").forEach { assertTrue(it, quotient.matches(it)) }
        listOf("802.9674", "802.96", "802").forEach { assertFalse(it, quotient.matches(it)) }
        assertTrue(AnswerCheck.Number(42.1648128).matches("about 42 km"))
        assertTrue(AnswerCheck.Number(42.1648128).matches("42.2 km"))
        assertTrue(AnswerCheck.Number(314.269680529319).matches("roughly 314"))
        assertFalse(AnswerCheck.Number(56.0).matches("56.4"))
        assertFalse(AnswerCheck.Number(56.0).matches("57"))
        assertTrue(AnswerCheck.Number(56.0).matches("56.0"))
    }

    @Test
    fun aTextAnswerIsWordsOfTheReplyCaseAndAccentsAside() {
        val spanish = AnswerCheck.Text("buenos dias")

        assertTrue(spanish.matches("¡Buenos días!"))
        assertTrue(spanish.matches("In Spanish: buenos  dias."))
        assertFalse(spanish.matches("Buenas noches"))
        assertTrue(AnswerCheck.Text("Tokyo").matches("The capital of Japan is Tokyo."))
        assertFalse("a word, not part of one", AnswerCheck.Text("Tokyo").matches("Tokyoite"))
        assertEquals("tokyo", AnswerCheck.Text("Tokyo").shown)
    }

    @Test
    fun anAnswerIsCorrectWrongCutEmptyOrUnchecked() {
        val check = AnswerCheck.Number(56.0)

        assertEquals(AnswerOutcome.Correct, AnswerOutcome.of(check, "56", StopReason.Complete.name))
        assertEquals(AnswerOutcome.Wrong, AnswerOutcome.of(check, "54", StopReason.Complete.name))
        assertEquals(AnswerOutcome.Cut, AnswerOutcome.of(check, null, StopReason.ReasoningBudgetExhausted.name))
        assertEquals("the model ended the turn with nothing: not a cap", AnswerOutcome.Empty, AnswerOutcome.of(check, null, StopReason.Complete.name))
        assertEquals(AnswerOutcome.Empty, AnswerOutcome.of(check, "  ", StopReason.Complete.name))
        assertEquals("no stats, no telling", AnswerOutcome.Cut, AnswerOutcome.of(check, null, null))
        // TEN-204: time-tokyo has no check, and an empty answer after its tool must still show as one.
        assertEquals(AnswerOutcome.Empty, AnswerOutcome.of(null, null, StopReason.Complete.name))
        assertEquals(AnswerOutcome.Unchecked, AnswerOutcome.of(null, "It is 23:04.", StopReason.Complete.name))
        assertEquals("a cap, not the model", AnswerOutcome.Unchecked, AnswerOutcome.of(null, null, StopReason.TokenBudget.name))
        assertEquals(AnswerOutcome.Cut, AnswerOutcome.of(check, "7 times 8 is", StopReason.ResponseBudgetExhausted.name))
        assertEquals("the answer came before the cap", AnswerOutcome.Correct, AnswerOutcome.of(check, "56, because", StopReason.ResponseBudgetExhausted.name))
        assertEquals(AnswerOutcome.Unchecked, AnswerOutcome.of(null, "56", StopReason.Complete.name))
        assertEquals(AnswerOutcome.Unchecked, AnswerOutcome.of(check, null, null, failed = true))
    }

    @Test
    fun anAnswerIsANumberWithAnOptionalToleranceOrAText() {
        val prompts = parseBenchPrompts(
            """{"version":1,"prompts":[
                {"id":"a","prompt":"p","expected":"none","answer":{"number":56}},
                {"id":"b","prompt":"p","expected":"convert","answer":{"number":37,"tolerance":0.05}},
                {"id":"c","prompt":"p","expected":"none","answer":{"text":"Tokyo"}},
                {"id":"d","prompt":"p","expected":"none"}]}""",
        )

        assertEquals(listOf(AnswerCheck.Number(56.0), AnswerCheck.Number(37.0, 0.05), AnswerCheck.Text("Tokyo"), null), prompts.map { it.answer })
    }

    @Test
    fun theRepoListChecksTheVerifiablePromptsAndLeavesTheClockOut() {
        val answers = parseBenchPrompts(File("src/debug/assets/bench/agent-prompts.json").readText()).associate { it.id to it.answer }

        assertEquals(AnswerCheck.Number(37.0), answers["fahrenheit-to-celsius"])
        assertEquals(98765.0 / 123, (answers["divide-decimals"] as AnswerCheck.Number).value, 1e-9)
        assertEquals(26.2 * 1.609344, (answers["miles-to-km"] as AnswerCheck.Number).value, 1e-9)
        assertEquals(kotlin.math.sqrt(98765.4321), (answers["square-root"] as AnswerCheck.Number).value, 1e-9)
        assertFalse(".5 is not 5", answers.getValue("half-of-ten")!!.matches("Half of 10 is .5"))
        assertTrue(answers.getValue("half-of-ten")!!.matches("Half of 10 is 5."))
        assertTrue(answers.getValue("divide-decimals")!!.matches("about 802.97"))
        assertTrue(answers.getValue("miles-to-km")!!.matches("about 42 km"))
        assertTrue(answers.getValue("square-root")!!.matches("about 314"))
        assertEquals(AnswerCheck.Number(355614924.0), answers["multiply-large"])
        assertEquals(AnswerCheck.Text("Tokyo"), answers["capital-japan"])
        assertTrue(answers.getValue("divide-decimals")!!.matches("98765 / 123 = 802.9675"))
        assertTrue(answers.getValue("square-root")!!.matches("about 314.27"))
        assertTrue(answers.getValue("miles-to-km")!!.matches("26.2 miles is 42.16 km"))
        listOf("time-now", "date-today", "time-tokyo", "haiku-autumn", "timer-ten-minutes").forEach { assertNull(it, answers.getValue(it)) }
    }

    @Test
    fun eachResultIsCheckedOnItsReplyAndTheSummaryCountsTheAnswersAndTheMedianReasoning() = runBlocking {
        val gen = ScriptedGenerator(
            reply("", reasoning = "seven eights", calls = listOf(ToolCall("c1", "calculate", "{}"))),
            reply("It is 56.", reasoning = "the tool said 56"),
            reply("98.6 °F is 66 °C.", reasoning = "hmm"),
            reply("", reasoning = "thinking and thinking", stop = StopReason.ReasoningBudgetExhausted),
            reply("", reasoning = "ab cd"),
            reply("blue, white"),
        )
        val bench = runner(FakeTool("calculate"), generator = gen)
        val prompts = listOf(
            BenchPrompt("times", "7 times 8?", setOf("calculate"), answer = AnswerCheck.Number(56.0)),
            BenchPrompt("temp", "98.6 F in C?", emptySet(), answer = AnswerCheck.Number(37.0, 0.05)),
            BenchPrompt("half", "Half of 10?", emptySet(), answer = AnswerCheck.Number(5.0)),
            BenchPrompt("eight", "8 + 0?", emptySet(), answer = AnswerCheck.Number(8.0)),
            BenchPrompt("colors", "Two colors?", emptySet()),
        )
        val lines = mutableListOf<String>()

        val results = runBench(prompts, bench, firstCold = false, beforeEach = { null }, log = { lines += it })

        assertEquals(
            listOf(AnswerOutcome.Correct, AnswerOutcome.Wrong, AnswerOutcome.Cut, AnswerOutcome.Empty, AnswerOutcome.Unchecked),
            results.map { it.answer },
        )
        val first = Json.parseToJsonElement(lines.first()).jsonObject
        assertEquals("correct", first.getValue("answer").jsonPrimitive.content)
        assertEquals("56", first.getValue("expected_answer").jsonPrimitive.content)
        // Reasoning counted as its length here: 12 + 16 over the turn, 16 the largest reply.
        assertEquals("28", first.getValue("reasoning_tokens").jsonPrimitive.content)
        assertEquals("16", first.getValue("max_reply_reasoning_tokens").jsonPrimitive.content)
        assertFalse(Json.parseToJsonElement(lines[4]).jsonObject.containsKey("expected_answer"))
        val summary = BenchSummary.of(results)
        val line = Json.parseToJsonElement(summary.toLogLine(BenchTrigger(true))).jsonObject
        assertEquals(
            listOf("1", "1", "1", "1"),
            listOf("answer_ok", "answer_wrong", "answer_cut", "answer_empty").map { line.getValue(it).jsonPrimitive.content },
        )
        // The turns: 28, 3, 21, 5, 0; their largest replies: 16, 3, 21, 5, 0.
        assertEquals(5L, summary.medianReasoningTokens)
        assertEquals("5", line.getValue("median_reasoning_tokens").jsonPrimitive.content)
        assertEquals(listOf(16, 3, 21, 5, 0), results.map { it.maxReplyReasoningTokens })
        assertEquals("5", line.getValue("median_max_reply_reasoning_tokens").jsonPrimitive.content)
        val shown = summary.toDisplayLines(BenchTrigger(true), "m", results)
        assertTrue(shown.contains("answers ok 1 · wrong 1 · cut 1 · empty 1"))
        assertTrue(shown.contains("answer_wrong: temp"))
        assertTrue(shown.contains("answer_cut: half"))
        assertTrue(shown.contains("answer_empty: eight"))
    }

    @Test
    fun aReasoningBudgetLowersTheChatsReasoningCapAndKeepsItsTotal() {
        val chat = SamplingConfig(maxTokens = 2048, reasoningBudget = ReasoningBudget.splitting(2048, "</think>"))

        assertEquals(ReasoningBudget(256, 1792, "</think>"), BenchTrigger(thinking = true, reasoningBudget = 256).sampling(chat).reasoningBudget)
        assertEquals(ReasoningBudget(128, 1920, "</think>"), BenchTrigger(thinking = true, reasoningBudget = 128).sampling(chat).reasoningBudget)
        assertEquals(chat.reasoningBudget, BenchTrigger(thinking = true).sampling(chat).reasoningBudget)
        assertEquals(2048, BenchTrigger(thinking = true, reasoningBudget = 256).sampling(chat).maxTokens)
        assertNull("no thinking, nothing to cap", BenchTrigger(thinking = true, reasoningBudget = 256).sampling(SamplingConfig(maxTokens = 2048)).reasoningBudget)
        assertTrue(runCatching { BenchTrigger(thinking = true, reasoningBudget = 0) }.isFailure)
    }

    @Test
    fun theReasoningBudgetReachesEveryGenerationOfTheRunAndTheSummary() = runBlocking {
        val chat = SamplingConfig(maxTokens = 2048, reasoningBudget = ReasoningBudget.splitting(2048, "</think>"))
        val trigger = BenchTrigger(thinking = true, reasoningBudget = 256)
        val gen = ScriptedGenerator(reply("", calls = listOf(ToolCall("c1", "datetime", "{}"))), reply("It is ten."), reply("Once"))
        val bench = runner(FakeTool("datetime"), generator = gen, sampling = trigger.sampling(chat))

        bench.run(prompt("time", "datetime"))
        bench.run(BenchPrompt("story", "Answer at length.", emptySet(), maxTokens = 96))

        assertEquals(listOf(256, 256, 256), gen.requests.map { it.sampling.reasoningBudget?.reasoningMaxTokens })
        assertEquals(ReasoningBudget(256, 96, "</think>"), gen.requests.last().sampling.reasoningBudget)
        assertEquals("256", Json.parseToJsonElement(BenchSummary.of(emptyList()).toLogLine(trigger)).jsonObject.getValue("reasoning_budget").jsonPrimitive.content)
        assertTrue(BenchSummary.of(emptyList()).toDisplayLines(trigger, "m", emptyList())[1].endsWith(" · reasoning 256"))
    }

    @Test
    fun aReplyThatReasonsPastTheCapIsMarkedSinceNothingEnforcedIt() = runBlocking {
        val sampling = SamplingConfig(maxTokens = 104, reasoningBudget = ReasoningBudget(4, 100, "</think>"))
        val gen = ScriptedGenerator(reply("56", reasoning = "x".repeat(4 + BenchResult.ReasoningCapSlack + 1)), reply("56", reasoning = "x".repeat(4 + BenchResult.ReasoningCapSlack)))
        val bench = runner(generator = gen, sampling = sampling)
        val lines = mutableListOf<String>()

        val results = runBench(listOf(prompt("over"), prompt("within")), bench, firstCold = false, beforeEach = { null }, log = { lines += it })

        assertEquals(listOf(true, false), results.map { it.reasoningOverCap })
        assertTrue(lines[0].contains("\"reasoning_over_cap\":true"))
        assertFalse(lines[1].contains("reasoning_over_cap"))
        val summary = BenchSummary.of(results)
        assertEquals("1", Json.parseToJsonElement(summary.toLogLine(BenchTrigger(true))).jsonObject.getValue("reasoning_over_cap").jsonPrimitive.content)
        assertTrue(summary.toDisplayLines(BenchTrigger(true), "m", results).contains("reasoning_over_cap 1: the cap was not enforced"))
        assertFalse("no cap, nothing to exceed", runner(generator = ScriptedGenerator(reply("56", reasoning = "x".repeat(500)))).run(prompt("free")).reasoningOverCap)
    }

    @Test
    fun theScreenOffersBudgetsBelowTheChatsOwn() {
        assertEquals(listOf(512, 256, 128), BenchTrigger.ReasoningBudgets)
    }

    @Test
    fun anAdaptiveRunGivesEachPromptTheBudgetItsTextChoosesAndRecordsIt() = runBlocking {
        val chat = SamplingConfig(maxTokens = 2048, reasoningBudget = ReasoningBudget.splitting(2048, "</think>"))
        val laundry = "Two loads of laundry each need washing (20 minutes), then drying (30 minutes), then folding (10 minutes), in " +
            "that order. There is one washer, one dryer and one person folding, and each handles one load at a time."
        val gen = ScriptedGenerator(reply("Tokyo"), reply("90"), reply("Once"))
        val bench = BenchRunner(
            runtime = AgentRuntime(ToolRegistry(emptyList()) { ToolTier.entries.toSet() }, RefusingGate(), waitForQueue = { false }),
            gate = RefusingGate(),
            generator = gen,
            sampling = chat,
            countTokens = { it.length },
            samplingFor = { text -> chat.copy(reasoningBudget = ReasoningBudget.forMessage(text, chat.maxTokens, "</think>")) },
        )
        val lines = mutableListOf<String>()

        val results = runBench(
            listOf(BenchPrompt("capital", "Capital of Japan?", emptySet()), BenchPrompt("laundry", laundry, emptySet()), BenchPrompt("story", "A story.", emptySet(), maxTokens = 96)),
            bench,
            firstCold = false,
            beforeEach = { null },
            log = { lines += it },
        )

        assertEquals(listOf(256, 1024, 256), gen.requests.map { it.sampling.reasoningBudget?.reasoningMaxTokens })
        assertEquals("the long-form cap still holds the answer", ReasoningBudget(256, 96, "</think>"), gen.requests.last().sampling.reasoningBudget)
        assertEquals(listOf(256, 1024, 256), results.map { it.reasoningCap })
        assertEquals("1024", Json.parseToJsonElement(lines[1]).jsonObject.getValue("reasoning_cap").jsonPrimitive.content)
        assertEquals(chat, bench.prefixRequest().sampling)
    }

    @Test
    fun theBudgetModeIsAdaptiveFixedOrDefaultAndAdaptiveTakesNoFixedBudget() {
        assertEquals("adaptive", BenchTrigger(thinking = true, adaptiveReasoning = true).reasoningBudgetMode)
        assertEquals("fixed", BenchTrigger(thinking = true, reasoningBudget = 256).reasoningBudgetMode)
        assertEquals("default", BenchTrigger(thinking = true).reasoningBudgetMode)
        assertTrue(runCatching { BenchTrigger(thinking = true, reasoningBudget = 256, adaptiveReasoning = true) }.isFailure)
        val adaptive = BenchTrigger(thinking = true, adaptiveReasoning = true)
        assertEquals("adaptive", Json.parseToJsonElement(BenchSummary.of(emptyList()).toLogLine(adaptive)).jsonObject.getValue("reasoning_budget_mode").jsonPrimitive.content)
        assertTrue(BenchSummary.of(emptyList()).toDisplayLines(adaptive, "m", emptyList())[1].endsWith(" · reasoning adaptive"))
        assertEquals(adaptive, benchTrigger(debug = true, bench = "agent", thinking = true, defaultThinking = true, reasoningBudget = 256, adaptiveReasoning = true))
        assertFalse(benchTrigger(debug = true, bench = "agent", thinking = false, defaultThinking = true, adaptiveReasoning = true)!!.adaptiveReasoning)
    }

    @Test
    fun theTriggerTakesAReasoningBudgetOnlyWithThinking() {
        assertEquals(256, benchTrigger(debug = true, bench = "agent", thinking = true, defaultThinking = true, reasoningBudget = 256)!!.reasoningBudget)
        assertNull(benchTrigger(debug = true, bench = "agent", thinking = false, defaultThinking = true, reasoningBudget = 256)!!.reasoningBudget)
        assertNull(benchTrigger(debug = true, bench = "agent", thinking = null, defaultThinking = true, reasoningBudget = 0)!!.reasoningBudget)
        assertNull(benchTrigger(debug = true, bench = "agent", thinking = null, defaultThinking = true)!!.reasoningBudget)
    }
}
