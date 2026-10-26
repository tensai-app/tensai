package io.github.tensaiapp.bench

import io.github.tensaiapp.engine.ReasoningBudget
import io.github.tensaiapp.engine.SamplingConfig
import io.github.tensaiapp.engine.ToolCall
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of the saved bench runs (TEN-187): their JSON, the store, and what Compare reads off them. Debug only, as the code. */
class BenchRunTest {

    private val config = BenchRunConfig(
        model = "Qwen3-1.7B",
        quant = "Q4_K_M",
        backend = "CPU",
        kvType = "q8_0",
        thinking = true,
        basePrompt = true,
        basePromptVersion = 2,
        examples = false,
        toolGrammar = true,
        repetitions = 3,
        sampling = BenchSampling.of(SamplingConfig()),
        prompts = 2,
        tools = listOf("datetime", "convert"),
        appVersionName = "0.3.5",
        appVersionCode = 12,
        llamaKtCommit = "abc1234",
    )

    private fun result(id: String, verdict: BenchVerdict, rep: Int = 1, expected: Set<String> = emptySet(), ttftMs: Long? = 100, totalMs: Long = 1000, prefixCold: Boolean = false) =
        BenchResult(
            prompt = BenchPrompt(id, "question $id", expected),
            calls = if (verdict == BenchVerdict.FalseCall) listOf(ToolCall("c", "convert", """{"value":3}""")) else emptyList(),
            refused = emptyList(),
            verdict = verdict,
            tokens = 12,
            reasoningTokens = 4,
            ttftMs = ttftMs,
            totalMs = totalMs,
            stopReason = "Complete",
            prefixCold = prefixCold,
            repetition = rep,
            reply = "reply $id",
            reasoning = "thought $id",
        )

    private fun trial(id: String, verdict: BenchVerdict, rep: Int = 1) = BenchTrial.of(result(id, verdict, rep))

    private fun run(startedAt: Long, vararg trials: BenchTrial) = BenchRun(
        id = BenchRun.idFor(startedAt),
        startedAtMs = startedAt,
        finishedAtMs = startedAt + 1,
        config = config,
        plannedTrials = trials.size,
        results = trials.toList(),
    )

    @Test
    fun aRunReadsBackAsItWasWritten() {
        val run = BenchRun.of(
            startedAtMs = 1_000,
            finishedAtMs = 2_000,
            config = config,
            planned = 2,
            results = listOf(result("colors", BenchVerdict.FalseCall), result("time", BenchVerdict.Correct, expected = setOf("datetime"))),
            stopReason = null,
        )

        val text = BenchRunJson.encode(run)
        val back = BenchRunJson.decode(text)

        assertEquals(run, back)
        val json = Json.parseToJsonElement(text).jsonObject
        assertEquals(BenchRun.Kind, json.getValue("kind").jsonPrimitive.content)
        val saved = json.getValue("config").jsonObject
        // The whole config, nulls included, so a reader never guesses a default.
        listOf("model", "quant", "backend", "kv_type", "thinking", "base_prompt", "base_prompt_version", "examples", "tool_grammar", "repetitions", "sampling", "app_version_name", "app_version_code", "llama_kt_commit")
            .forEach { assertTrue("config has $it", saved.containsKey(it)) }
        assertTrue(saved.getValue("sampling").jsonObject.keys.containsAll(listOf("seeds", "seeded", "greedy")))
        val first = json.getValue("results").jsonArray.first().jsonObject
        assertEquals("false_call", first.getValue("verdict").jsonPrimitive.content)
        assertEquals("""{"value":3}""", first.getValue("calls").jsonArray.single().jsonObject.getValue("args").jsonPrimitive.content)
    }

    @Test
    fun unknownFieldsAreSkippedAndMissingOnesTakeTheirDefault() {
        val text = """{"kind":"tensai.bench.agent_run","version":1,"id":"run-5","started_at_ms":5,"finished_at_ms":6,
            "from_a_later_build":{"x":1},
            "config":{"model":"m","thinking":false,"base_prompt":true,"base_prompt_version":2,"examples":false,"tool_grammar":true,
              "sampling":{"temperature":0.8,"top_p":0.95,"top_k":40,"min_p":0.05,"repeat_penalty":1.1,"max_tokens":2048,"new_knob":3},
              "prompts":1,"gpu_layers":99},
            "planned_trials":1,
            "results":[{"id":"a","prompt":"p","verdict":"correct","later":true}]}"""

        val run = BenchRunJson.decode(text)

        assertEquals("run-5", run.id)
        assertEquals(1, run.config.repetitions)
        assertNull(run.config.quant)
        assertFalse(run.config.sampling.greedy)
        assertFalse(run.config.sampling.seeded)
        assertFalse(run.partial)
        assertEquals(BenchVerdict.Correct, run.results.single().benchVerdict)
        assertEquals(emptyList<BenchCall>(), run.results.single().calls)
    }

    @Test
    fun aVerdictThisBuildDoesNotKnowReadsAsAnError() {
        assertEquals(BenchVerdict.Error, trial("a", BenchVerdict.Correct).copy(verdict = "maybe").benchVerdict)
    }

    @Test
    fun aStoppedRunIsSavedPartialWithItsGradedTrialsAndWhy() {
        val stopped = BenchRun.of(1, 2, config, planned = 6, results = listOf(result("a", BenchVerdict.Correct), result("a", BenchVerdict.Missing, rep = 2)), stopReason = BenchRun.StoppedByUser)
        val full = BenchRun.of(1, 2, config, planned = 1, results = listOf(result("a", BenchVerdict.Correct)), stopReason = "the model was unloaded or replaced")

        assertTrue(stopped.partial)
        assertEquals(2, stopped.results.size)
        assertEquals(6, stopped.plannedTrials)
        assertEquals(BenchRun.StoppedByUser, stopped.stopReason)
        assertEquals(listOf(1, 2), stopped.results.map { it.rep })
        // Ended after its last trial: a full run, whatever came next.
        assertFalse(full.partial)
        assertNull(full.stopReason)
        assertEquals(stopped, BenchRunJson.decode(BenchRunJson.encode(stopped)))
    }

    @Test
    fun replyAndReasoningAreCutAtTheCapAndSaySo() {
        val long = "x".repeat(BenchTrial.MaxTextChars + 10)
        val saved = BenchTrial.of(result("a", BenchVerdict.Correct).copy(reply = long, reasoning = "short"))

        assertEquals(BenchTrial.MaxTextChars, saved.reply!!.length)
        assertTrue(saved.replyCut)
        assertEquals("short", saved.reasoning)
        assertFalse(saved.reasoningCut)
        assertEquals(null to false, capText(null))
        assertEquals("abc" to false, capText("abc", max = 3))
    }

    @Test
    fun theBackendIsTheEnginesLabelWithoutItsDevice() {
        assertEquals("CPU", backendName("CPU: CPU"))
        assertEquals("Vulkan", backendName("Vulkan: Mali-G68 MC4"))
        assertEquals("unknown", backendName("unknown"))
    }

    @Test
    fun aCutNeverSplitsASurrogatePair() {
        val (cut, wasCut) = capText("ab😀cd", max = 3)

        assertEquals("ab", cut)
        assertTrue(wasCut)
    }

    @Test
    fun theSamplingIsRecordedWithNoSeedSinceTheEngineTakesNone() {
        val sampling = BenchSampling.of(SamplingConfig(temperature = 0.7f, maxTokens = 1024, reasoningBudget = ReasoningBudget.splitting(1024, "</think>")))

        assertEquals(0.7f, sampling.temperature)
        assertEquals(1024, sampling.maxTokens)
        assertEquals(512, sampling.reasoningMaxTokens)
        assertNull(sampling.seeds)
        assertFalse(sampling.seeded)
        assertNull(BenchSampling.of(SamplingConfig(enableThinking = false)).reasoningMaxTokens)
    }

    @Test
    fun aGreedyRunRecordsTemperatureZeroAndTheFlagInItsSamplingAndShowsTheChip() {
        val trigger = BenchTrigger(thinking = true, greedy = true)
        val sampling = BenchSampling.of(trigger.sampling(SamplingConfig()), greedy = trigger.greedy)
        val run = run(1, trial("a", BenchVerdict.Correct)).let { it.copy(config = it.config.copy(sampling = sampling)) }

        val back = BenchRunJson.decode(BenchRunJson.encode(run)).config.sampling

        assertEquals(0f, back.temperature)
        assertTrue(back.greedy)
        assertEquals(SamplingConfig().temperature, BenchSampling.of(BenchTrigger(thinking = true).sampling(SamplingConfig())).temperature)
    }

    @Test
    fun totalsCountEveryRepetitionAndTheMediansLeaveOutErrorsAndTheColdTrial() {
        val trials = listOf(
            BenchTrial.of(result("a", BenchVerdict.Correct, rep = 1, ttftMs = 9_000, totalMs = 9_000, prefixCold = true)),
            BenchTrial.of(result("a", BenchVerdict.Correct, rep = 2, ttftMs = 100, totalMs = 1_000)),
            BenchTrial.of(result("a", BenchVerdict.Missing, rep = 3, ttftMs = 300, totalMs = 3_000)),
            BenchTrial.of(result("b", BenchVerdict.Error, rep = 1, ttftMs = 5, totalMs = 5)),
            BenchTrial.of(result("b", BenchVerdict.Correct, rep = 2, ttftMs = 200, totalMs = 2_000)).copy(namedInText = listOf("convert")),
        )

        val totals = RunTotals.of(trials)

        assertEquals(5, totals.trials)
        assertEquals(3, totals.count(BenchVerdict.Correct))
        assertEquals(1, totals.count(BenchVerdict.Missing))
        assertEquals(1, totals.count(BenchVerdict.Error))
        assertEquals(1, totals.namedInText)
        assertEquals(200L, totals.ttftP50Ms)
        assertEquals(2_000L, totals.totalP50Ms)
        assertEquals(0.6, totals.value(SummaryMetric.Correct)!!, 1e-9)
    }

    @Test
    fun aPassCellIsTheShareOfCorrectTrialsAndItsMainFailure() {
        val all = PassCell(listOf(trial("a", BenchVerdict.Correct), trial("a", BenchVerdict.Correct, 2), trial("a", BenchVerdict.Correct, 3)))
        val mixed = PassCell(listOf(trial("a", BenchVerdict.Correct), trial("a", BenchVerdict.Missing, 2), trial("a", BenchVerdict.Missing, 3)))
        val none = PassCell(listOf(trial("a", BenchVerdict.WrongTool), trial("a", BenchVerdict.FalseCall, 2)))
        val na = PassCell(listOf(trial("a", BenchVerdict.Unavailable), trial("a", BenchVerdict.Unavailable, 2)))

        assertEquals(3 to 3, all.passes to all.total)
        assertNull(all.failure)
        assertEquals(1 to 3, mixed.passes to mixed.total)
        assertEquals(BenchVerdict.Missing, mixed.failure)
        assertEquals(0, none.passes)
        // A tie goes to the bar's order: false before wrong.
        assertEquals(BenchVerdict.FalseCall, none.failure)
        assertTrue(na.unavailable)
        assertFalse(none.unavailable)
    }

    @Test
    fun aRowChangedWhenTheRunsDisagreeNotWhenTheyOnlyDifferInRepetitions() {
        val a = run(1, trial("same", BenchVerdict.Correct), trial("flip", BenchVerdict.Correct), trial("rate", BenchVerdict.Missing), trial("only-a", BenchVerdict.Missing))
        val b = run(
            2,
            trial("same", BenchVerdict.Correct), trial("same", BenchVerdict.Correct, 2),
            trial("flip", BenchVerdict.FalseCall), trial("flip", BenchVerdict.FalseCall, 2),
            trial("rate", BenchVerdict.Missing), trial("rate", BenchVerdict.Correct, 2),
        )

        val rows = compareRows(listOf(a, b)).associateBy { it.promptId }

        assertEquals(listOf("same", "flip", "rate", "only-a"), compareRows(listOf(a, b)).map { it.promptId })
        assertFalse(rows.getValue("same").changed)
        assertTrue(rows.getValue("flip").changed)
        assertTrue(rows.getValue("rate").changed)
        // b never reached it: nothing to disagree with.
        assertFalse(rows.getValue("only-a").changed)
        assertNull(rows.getValue("only-a").cells[1])
        assertFalse(compareRows(listOf(a)).any { it.changed })
    }

    @Test
    fun theBestOfAMetricIsTheHighestCorrectAndTheLowestOfTheRestWithNoBestWhenAllAreEqual() {
        val good = RunTotals.of(listOf(trial("a", BenchVerdict.Correct), trial("b", BenchVerdict.Correct)))
        val bad = RunTotals.of(listOf(trial("a", BenchVerdict.Correct), trial("b", BenchVerdict.FalseCall)))
        val alsoGood = RunTotals.of(listOf(trial("a", BenchVerdict.Correct), trial("b", BenchVerdict.Correct)))

        assertEquals(setOf(0, 2), bestRuns(listOf(good, bad, alsoGood), SummaryMetric.Correct))
        assertEquals(setOf(0, 2), bestRuns(listOf(good, bad, alsoGood), SummaryMetric.FalseCall))
        assertEquals(emptySet<Int>(), bestRuns(listOf(good, alsoGood), SummaryMetric.Correct))
        assertEquals(emptySet<Int>(), bestRuns(listOf(good), SummaryMetric.Correct))
    }

    @Test
    fun theCompareExportIsJsonWithEachRunWholeAndItsSummary() {
        val a = run(1, trial("x", BenchVerdict.Correct))
        val b = run(2, trial("x", BenchVerdict.FalseCall))

        val json = Json.parseToJsonElement(BenchRunJson.encodeCompare(listOf(a, b))).jsonObject

        assertEquals(BenchRunJson.CompareKind, json.getValue("kind").jsonPrimitive.content)
        val runs = json.getValue("runs").jsonArray.map { it.jsonObject }
        assertEquals(listOf("a", "b"), runs.map { it.getValue("key").jsonPrimitive.content })
        assertEquals(listOf(a, b), runs.map { BenchRunJson.decode(it.getValue("run").toString()) })
        val summary = runs[1].getValue("summary") as JsonObject
        assertEquals("1", summary.getValue("false_call").jsonPrimitive.content)
        assertTrue(summary.containsKey("named_in_text"))
    }

    /** Runs [runThenSave] as [AgentBench] does, the finished run built and kept in [saved]. */
    private suspend fun runAndKeep(results: MutableList<BenchResult>, saved: MutableList<BenchRun>, stop: () -> Boolean = { false }, planned: Int = 3, run: suspend () -> String?) =
        runThenSave(results, stop, run) { graded, why -> if (graded.isNotEmpty()) saved += BenchRun.of(1, 2, config, planned, graded, why) }

    @Test
    fun stopMidRunSavesWhatWasGradedAsAPartialRun() = runBlocking {
        val results = mutableListOf<BenchResult>()
        val saved = mutableListOf<BenchRun>()
        var stop = false
        val graded = CompletableDeferred<Unit>()

        val job = launch {
            runAndKeep(results, saved, stop = { stop }) {
                results += result("a", BenchVerdict.Correct)
                results += result("b", BenchVerdict.Missing)
                graded.complete(Unit)
                awaitCancellation()
            }
        }
        graded.await()
        stop = true
        job.cancelAndJoin()

        val run = saved.single()
        assertTrue(run.partial)
        assertEquals(listOf("a", "b"), run.results.map { it.id })
        assertEquals(BenchRun.StoppedByUser, run.stopReason)
    }

    @Test
    fun aRunThatThrowsSavesWhatItGradedWithWhy() = runBlocking {
        val results = mutableListOf<BenchResult>()
        val saved = mutableListOf<BenchRun>()

        val reason = runAndKeep(results, saved) {
            results += result("a", BenchVerdict.Correct)
            error("engine gone")
        }

        assertEquals(BenchRun.ThrewPrefix + "engine gone", reason)
        assertEquals(listOf("a"), saved.single().results.map { it.id })
        assertEquals(reason, saved.single().stopReason)
        assertTrue(saved.single().partial)
    }

    @Test
    fun aRunToTheEndSavesAFullRunAndOneThatGradedNothingSavesNone() = runBlocking {
        val results = mutableListOf<BenchResult>()
        val saved = mutableListOf<BenchRun>()

        runAndKeep(results, saved, planned = 2) {
            results += result("a", BenchVerdict.Correct)
            results += result("b", BenchVerdict.Correct)
            null
        }
        runAndKeep(mutableListOf(), saved) { error("no model") }

        assertFalse(saved.single().partial)
        assertNull(saved.single().stopReason)
    }

    @Test
    fun theStoreKeepsOneFileARunNewestFirstAndSkipsWhatItCannotRead() {
        val dir = Files.createTempDirectory("bench").toFile()
        try {
            val warnings = mutableListOf<String>()
            val store = BenchRunStore(dir) { warnings += it }
            store.save(run(1, trial("a", BenchVerdict.Correct)))
            store.save(run(3, trial("a", BenchVerdict.Missing)))
            File(dir, "broken.json").writeText("{not json")

            val reread = BenchRunStore(dir) { warnings += it }.apply { refresh() }

            assertEquals(listOf("run-3", "run-1"), reread.runs.value!!.map { it.id })
            assertEquals(1, warnings.size)
            reread.delete("run-1")
            assertEquals(listOf("run-3"), reread.runs.value!!.map { it.id })
            assertEquals(setOf("run-3.json", "broken.json"), dir.list()!!.toSet())
        } finally {
            dir.deleteRecursively()
        }
    }
}
