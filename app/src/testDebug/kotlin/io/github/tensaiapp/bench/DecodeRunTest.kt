package io.github.tensaiapp.bench

import io.github.tensaiapp.engine.CpuLoadKnobs
import io.github.tensaiapp.engine.DecodeStats
import io.github.tensaiapp.engine.DecodeTimings
import io.github.tensaiapp.engine.FlashAttnMode
import io.github.tensaiapp.engine.KvKnob
import io.github.tensaiapp.engine.StopReason
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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

/** JVM tests of the saved decode runs (TEN-190): their JSON, the store, and what the Decode tab reads off them. Debug only, as the code. */
class DecodeRunTest {

    private val loaded = DecodeKnobs(nThreads = 2, flashAttn = "auto", kv = "q8_0")

    private val config = DecodeRunConfig(
        model = "Qwen3-1.7B",
        quant = "Q4_K_M",
        backend = "CPU",
        nCtx = 8192,
        requested = DecodeKnobs(nThreads = 2),
        loaded = loaded,
        tokensPerPoint = 128,
        points = listOf(0, 512, 1024, 2048, 4096),
        appVersionName = "0.3.5",
        appVersionCode = 12,
        llamaKtCommit = "abc1234",
        device = "Xiaomi M2101K9G",
        soc = "MT6877",
    )

    private fun point(ctx: Int, decode: Double?, prefill: Double? = 40.0) =
        DecodeRunPoint(ctx = ctx, promptTokens = ctx + 40, evalTokens = 127, generated = 128, decodeTps = decode, prefillTps = prefill)

    private fun run(startedAt: Long, points: List<DecodeRunPoint>, config: DecodeRunConfig = this.config) =
        DecodeRun.of(startedAt, startedAt + 372_000, config, points, stopReason = null)

    @Test
    fun aRunReadsBackAsItWasWritten() {
        val run = run(1_000, listOf(point(0, 10.19, 40.27), point(2048, 5.21, 30.86))).copy(name = "fa off")
        assertEquals(run, DecodeRunJson.decode(DecodeRunJson.encode(run)))
        assertEquals("decode-1000", run.id)
    }

    @Test
    fun aRunFromALaterOrEarlierBuildStillReads() {
        // A field a later build added is skipped; one an older build lacked takes its default.
        val text = """
            {"kind":"tensai.bench.decode_run","id":"decode-5","started_at_ms":5,"finished_at_ms":9,"future":true,
             "config":{"model":"m","n_ctx":4096,"loaded":{"kv":"f16","extra":1},"tokens_per_point":64,"points":[0]},
             "points":[{"ctx":0,"decode_tps":9.5,"new_metric":3}]}
        """.trimIndent()
        val run = DecodeRunJson.decode(text)
        assertEquals("f16", run.config.loaded.kv)
        assertEquals("auto", run.config.loaded.flashAttn)
        assertNull(run.config.requested)
        assertEquals(9.5, run.points.single().decodeTps!!, 0.0)
        assertFalse(run.partial)
    }

    @Test
    fun aPointTakesItsSpeedsFromTheEngineTimingsNotTheWallClock() {
        val stats = DecodeStats(
            tokensGenerated = 128,
            tokPerSec = 99f,
            ttftMs = 70_000,
            stopReason = StopReason.TokenBudget,
            timings = DecodeTimings(promptTokens = 2088, promptMs = 67_660.0, evalTokens = 127, evalMs = 24_380.0),
        )
        // The cache holds the prompt and the 127 decoded tokens; the 128th was sampled, never decoded.
        val p = DecodeRunPoint.of(2048, stats, error = null, kvTokens = 2215, ms = 95_000)
        assertEquals(2088, p.promptTokens)
        assertEquals(2088, p.prefillTokens)
        assertEquals(127, p.evalTokens)
        assertEquals(5.21, p.decodeTps!!, 0.0)
        assertEquals(30.86, p.prefillTps!!, 0.0)
        assertEquals("TokenBudget", p.stop)
    }

    @Test
    fun aPointWithoutEngineTimingsHasNoSpeed() {
        val p = DecodeRunPoint.of(0, DecodeStats(128, 9f, 800), error = null, kvTokens = null, ms = 20_000)
        assertNull(p.decodeTps)
        assertNull(p.prefillTps)
        assertNull(p.promptTokens)
        // No timings: the decodes are one less than the tokens sampled.
        assertEquals(40, DecodeRunPoint.of(0, DecodeStats(128, 9f, 800), error = null, kvTokens = 167, ms = 1).promptTokens)
        val failed = DecodeRunPoint.of(512, null, error = "busy", kvTokens = 0, ms = 3)
        assertEquals("busy", failed.error)
        assertEquals(0, failed.generated)
    }

    @Test
    fun cancelMidRunSavesTheFinishedPointsAsAPartialRun() = runBlocking {
        val points = mutableListOf<DecodeRunPoint>()
        val saved = mutableListOf<DecodeRun>()
        var stop = false
        val measured = CompletableDeferred<Unit>()

        val job = launch {
            runThenSave(points, { stop }, run = {
                points += point(0, 10.0)
                points += point(512, 9.0)
                measured.complete(Unit)
                awaitCancellation()
            }) { done, why -> if (done.isNotEmpty()) saved += DecodeRun.of(1, 2, config, done, why) }
        }
        measured.await()
        stop = true
        job.cancelAndJoin()

        val run = saved.single()
        assertTrue(run.partial)
        assertEquals(listOf(0, 512), run.points.map { it.ctx })
        assertEquals(BenchRun.StoppedByUser, run.stopReason)
    }

    @Test
    fun aRunOfEveryPointIsFullAndKeepsNoReason() {
        val all = config.points.map { point(it, 8.0) }
        val run = DecodeRun.of(1, 2, config, all, stopReason = "whatever ended it")
        assertFalse(run.partial)
        assertNull(run.stopReason)
    }

    @Test
    fun theTakeawayReadsTheFirstPointAgainst2k() {
        val t = DecodeTakeaway.of(listOf(point(0, 10.19, 40.27), point(512, 9.0), point(2048, 5.21, 30.86), point(4096, 3.0)))!!
        assertEquals(0, t.fromCtx)
        assertEquals(2048, t.toCtx)
        assertEquals(-48.87, t.decodeChangePct, 0.01)
        assertEquals(40.27, t.prefillFrom!!, 0.0)
        assertEquals(30.86, t.prefillTo!!, 0.0)
    }

    @Test
    fun without2kTheTakeawayReadsTheLastPoint() {
        val t = DecodeTakeaway.of(listOf(point(0, 10.0), point(512, 9.0), point(1024, 8.0)))!!
        assertEquals(1024, t.toCtx)
        assertEquals(-20.0, t.decodeChangePct, 1e-9)
        // An unmeasured point is no end of the curve.
        assertEquals(512, DecodeTakeaway.of(listOf(point(0, 10.0), point(512, 9.0), point(1024, null)))!!.toCtx)
    }

    @Test
    fun onePointIsNoTakeaway() {
        assertNull(DecodeTakeaway.of(listOf(point(0, 10.0))))
        assertNull(DecodeTakeaway.of(emptyList()))
        assertNull(DecodeTakeaway.of(listOf(point(0, null), point(2048, 5.0))))
    }

    @Test
    fun withinTwoPercentEitherWayIsNoise() {
        assertEquals(DeltaKind.Noise, deltaKind(2.0))
        assertEquals(DeltaKind.Noise, deltaKind(-2.0))
        assertEquals(DeltaKind.Noise, deltaKind(0.0))
        assertEquals(DeltaKind.Faster, deltaKind(2.01))
        assertEquals(DeltaKind.Slower, deltaKind(-2.01))
        assertEquals(10.0, deltaPct(10.0, 11.0)!!, 1e-9)
        assertNull(deltaPct(null, 11.0))
        assertNull(deltaPct(0.0, 11.0))
    }

    @Test
    fun aComparisonPairsPointsByContextAndReadsItsHeadlineAt2k() {
        val a = run(1, listOf(point(0, 10.0, 40.0), point(2048, 5.0, 31.0), point(4096, 3.0)))
        val b = run(2, listOf(point(0, 10.1, 30.0), point(2048, 5.5, 21.08)), config.copy(loaded = loaded.copy(flashAttn = "off")))
        val c = DecodeCompare(a, b)

        assertEquals(listOf(0, 2048, 4096), c.rows.map { it.ctx })
        assertEquals(1.0, c.rows[0].decodeDelta!!, 1e-9)
        assertEquals(DeltaKind.Noise, deltaKind(c.rows[0].decodeDelta!!))
        assertEquals(DeltaKind.Faster, deltaKind(c.rows[1].decodeDelta!!))
        assertEquals(DeltaKind.Slower, deltaKind(c.rows[1].prefillDelta!!))
        assertNull(c.rows[2].bDecode)
        assertNull(c.rows[2].decodeDelta)
        assertEquals(2048, c.headline!!.ctx)
        assertEquals(listOf(KnobChange("fa", "auto", "off")), c.changes)
        assertTrue(c.sameModel)
    }

    @Test
    fun theKnobsThatDifferFromTheLoadAreListedInChipOrder() {
        val applied = CpuLoadKnobs()
        assertEquals(emptyList<KnobChange>(), knobChanges(DecodeKnobs.of(applied), DecodeKnobs.of(applied)))
        val wanted = CpuLoadKnobs(decodeThreads = 4, flashAttn = FlashAttnMode.Off)
        assertEquals(
            listOf(KnobChange("n_threads", "auto", "4"), KnobChange("fa", "auto", "off")),
            knobChanges(DecodeKnobs.of(applied), DecodeKnobs.of(wanted)),
        )
        assertEquals("fa auto → off", KnobChange("fa", "auto", "off").label)
        // q8_0 runs with flash attention on whatever the fa knob says: that is the value compared.
        assertEquals(
            listOf(KnobChange("kv", "auto", "q8_0")),
            knobChanges(DecodeKnobs.of(CpuLoadKnobs(flashAttn = FlashAttnMode.On)), DecodeKnobs.of(CpuLoadKnobs(kv = KvKnob.Q8_0))),
        )
    }

    @Test
    fun theEstimateAddsPrefillAndDecodeOfEveryPointAndTheWarmUp() {
        val rates = DecodeRates(prefillTps = 40.0, decodeTps = 8.0)
        // (0 + 40) / 40 s prefill, 128 / 8 s decode.
        assertEquals(17_000.0, DecodeEstimate.pointMs(0, 128, rates), 1e-6)
        assertEquals(((2048 + 40) / 40.0 + 16) * 1000, DecodeEstimate.pointMs(2048, 128, rates), 1e-6)
        val total = DecodeEstimate.totalMs(listOf(0, 2048), 128, rates)
        assertEquals(DecodeEstimate.pointMs(0, DecodeEstimate.WarmUpTokens, rates) + 17_000 + 68_200, total, 1e-6)
        assertEquals(2, DecodeEstimate.minutes(total))
        assertEquals(1, DecodeEstimate.minutes(0.0))
        assertEquals(DecodeRates(35.0, 7.5), DecodeRates.of(listOf(point(0, 8.0, 40.0), point(512, 7.0, 30.0), point(1024, null, null))))
        assertNull(DecodeRates.of(listOf(point(0, null, null))))
        assertEquals(DecodeRates.Gpu, DecodeRates.defaultFor("Vulkan"))
    }

    @Test
    fun copyAsTextIsAMarkdownTableUnderTheConfig() {
        val run = DecodeRun.of(
            startedAtMs = 0,
            finishedAtMs = 372_000,
            config = config,
            points = listOf(
                DecodeRunPoint(ctx = 0, promptTokens = 31, evalTokens = 127, decodeTps = 10.19, prefillTps = 40.27),
                DecodeRunPoint(ctx = 2048, promptTokens = 2088, evalTokens = 127, decodeTps = 5.21, prefillTps = 30.86),
                DecodeRunPoint(ctx = 4096, error = "busy"),
            ),
            stopReason = BenchRun.StoppedByUser,
        )
        val expected = """
            **Decode run** · 1970-01-01 00:00 · 6 min 12 s
            Qwen3-1.7B Q4_K_M · CPU · n_ctx 8192
            loaded: n_threads 2 · fa auto · kv q8_0 · 128 tok/point
            requested: n_threads 2 · fa auto · kv auto
            app 0.3.5 (12) · llama.kt abc1234 · MT6877 · Xiaomi M2101K9G
            partial 3/5: stopped
            decode 10.19 → 5.21 tok/s (-49%) from 0 to 2k · prefill 40.27 → 30.86

            | ctx | prompt | eval | decode t/s | prefill t/s |
            |---:|---:|---:|---:|---:|
            | 0 | 31 | 127 | 10.19 | 40.27 |
            | 2048 | 2088 | 127 | 5.21 | 30.86 |
            | 4096 | - | 0 | - | - | busy
        """.trimIndent()
        assertEquals(expected, DecodeRunText.of(run, zone = ZoneOffset.UTC))
        assertFalse(DecodeRunText.of(run, zone = ZoneOffset.UTC).contains('\u2014'))
    }

    @Test
    fun copyAsTextOfAComparisonHasBothTablesAndMarksTheNoise() {
        val a = run(0, listOf(point(0, 10.0, 40.0), point(2048, 5.0, 31.0)))
        val b = run(60_000, listOf(point(0, 10.1, 30.0), point(2048, 5.5, 21.08)), config.copy(loaded = loaded.copy(flashAttn = "off")))
        val text = DecodeRunText.compare(DecodeCompare(a, b), zone = ZoneOffset.UTC)
        val lines = text.lines()
        assertEquals("**Decode compare** · A vs B · fa auto → off", lines[0])
        assertTrue(lines[1], lines[1].startsWith("A: 1970-01-01 00:00 · Qwen3-1.7B Q4_K_M · CPU · n_ctx 8192 · n_threads 2 · fa auto"))
        assertTrue(lines[2], lines[2].startsWith("B: 1970-01-01 00:01") && "fa off" in lines[2])
        assertEquals("B vs A at 2k: decode +10%, prefill -32%", lines[3])
        assertTrue("| 0 | 10.00 | 10.10 | +1.0% (noise) |" in lines)
        assertTrue("| 2048 | 5.00 | 5.50 | +10.0% |" in lines)
        assertTrue("| 2048 | 31.00 | 21.08 | -32.0% |" in lines)
        assertEquals(2, lines.count { it == "| ctx | A | B | B vs A |" })
        assertFalse(text.contains('\u2014'))
    }

    @Test
    fun percentagesSignTheirChangeAndShowNoSignForZero() {
        assertEquals("+10%", pctText(9.6))
        assertEquals("−32%", pctText(-32.0))
        assertEquals("-32.0%", pctText(-32.0, "-", decimals = 1))
        assertEquals("0%", pctText(-0.2))
        assertEquals("2k", ctxLabel(2048))
        assertEquals("512", ctxLabel(512))
        assertEquals("0", ctxLabel(0))
    }

    @Test
    fun theLogLinesCarryThePointsAndTheLoad() {
        val p = point(1024, 6.5, 40.2)
        val line = Json.parseToJsonElement(DecodeRunJson.pointLine(p, config)).jsonObject
        assertEquals("decode", line.getValue("event").jsonPrimitive.content)
        assertEquals(1024, line.getValue("ctx").jsonPrimitive.content.toInt())
        assertEquals(6.5, line.getValue("decode_tps").jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("q8_0", line.getValue("loaded").jsonObject.getValue("kv").jsonPrimitive.content)

        val partial = DecodeRun.of(1, 2, config.copy(skipped = listOf(8192)), listOf(point(0, 8.0), p), "stopped")
        val summary = Json.parseToJsonElement(DecodeRunJson.summaryLine(partial)).jsonObject
        assertEquals("decode_summary", summary.getValue("event").jsonPrimitive.content)
        assertEquals(2, summary.getValue("points").jsonArray.size)
        assertEquals(listOf("8192"), summary.getValue("skipped").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("stopped", summary.getValue("stop_reason").jsonPrimitive.content)
    }

    @Test
    fun theDecodeAndAgentStoresShareTheFolderButReadOnlyTheirOwnFiles() {
        val dir = Files.createTempDirectory("bench").toFile()
        try {
            val warnings = mutableListOf<String>()
            val decode = DecodeRunStore(dir) { warnings += it }
            decode.save(run(1, listOf(point(0, 8.0))))
            decode.save(run(3, listOf(point(0, 9.0))))
            File(dir, "decode-2.json").writeText("{not json")
            File(dir, "run-7.json").writeText("""{"kind":"tensai.bench.agent_run"}""")

            val reread = DecodeRunStore(dir) { warnings += it }.apply { refresh() }
            assertEquals(listOf("decode-3", "decode-1"), reread.runs.value!!.map { it.id })
            assertEquals(1, warnings.size)

            val agent = BenchRunStore(dir) { warnings += it }.apply { refresh() }
            assertEquals(2, warnings.size)
            assertTrue(warnings.last(), warnings.last().startsWith("skipped run-7.json"))
            assertEquals(emptyList<BenchRun>(), agent.runs.value)

            // A rename is a save over the same file.
            reread.save(reread.runs.value!!.first().copy(name = "fa off"))
            assertEquals("fa off", DecodeRunStore(dir) {}.apply { refresh() }.runs.value!!.first().name)
        } finally {
            dir.deleteRecursively()
        }
    }
}
