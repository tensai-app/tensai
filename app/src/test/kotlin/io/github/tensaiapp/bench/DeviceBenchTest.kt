package io.github.tensaiapp.bench

import io.github.tensaiapp.engine.DecodeStats
import io.github.tensaiapp.engine.DecodeTimings
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of the release benchmark (TEN-208): the report's fields, the run on a fake engine, no network on its path. */
class DeviceBenchTest {

    private val model = DeviceBenchModel(name = "Qwen3-1.7B", quant = "Q4_K_M", backend = "CPU", threads = 4, nCtx = 4096)

    private fun report(charging: Boolean? = true, thermal: String? = "light", points: List<DeviceBenchPoint> = listOf(
        DeviceBenchPoint(256, prefillTps = 61.234, decodeTps = 11.04, ttftMs = 420),
        DeviceBenchPoint(1024, prefillTps = 55.0, decodeTps = null, ttftMs = null, error = "oom"),
    )) = DeviceReport(
        appVersion = "0.3.8 (38)",
        phone = "Xiaomi M2101K9G",
        soc = "MT6877",
        ramBytes = 7_700_000_000,
        android = "13 (API 33)",
        model = model,
        points = points,
        charging = charging,
        thermal = thermal,
    )

    @Test
    fun reportListsItsFieldsInTheOrderTheIssueFormExpects() {
        val labels = report().text().lines().filter { it.isNotEmpty() }.map { it.substringBefore(": ") }
        assertEquals(
            listOf(
                "app version", "phone", "SoC", "RAM", "Android version", "model", "quant", "backend", "threads",
                "context sizes", "prefill tok/s", "decode tok/s", "first token (ms)", "charging", "thermal status",
            ),
            labels,
        )
    }

    @Test
    fun perSizeValuesFollowTheContextSizesAMissingFigureIsADash() {
        val text = report().text()
        assertTrue(text, "context sizes: 256, 1024\n" in text)
        assertTrue(text, "prefill tok/s: 61.2, 55.0\n" in text)
        assertTrue(text, "decode tok/s: 11.0, -\n" in text)
        assertTrue(text, "first token (ms): 420, -\n" in text)
        assertTrue(text, "RAM: 7.7 GB\n" in text)
    }

    @Test
    fun chargingAndThermalStatus() {
        assertTrue("charging: yes\n" in report(charging = true).text())
        assertTrue("charging: no\n" in report(charging = false).text())
        assertTrue("charging: unknown\n" in report(charging = null).text())
        assertTrue("thermal status: light\n" in report(thermal = "light").text())
        // No PowerManager answer: the field stays, so the order never shifts.
        assertTrue("thermal status: unknown\n" in report(thermal = null).text())
        assertEquals("moderate", DeviceReport.thermalName(2))
        assertEquals(null, DeviceReport.thermalName(42))
    }

    /**
     * A fake engine: decodes at fixed rates, [onDecode] sees each decode's max tokens, [interruptAt] (1-based, the warm-up
     * first) is a decode cut short, [failHandOverAt] a handover that fails.
     */
    private class FakeEngine(
        private val loaded: DeviceBenchModel?,
        private val busy: Boolean = false,
        private val warmUpError: String? = null,
        private val interruptAt: Int? = null,
        private val failHandOverAt: Int? = null,
        private val onDecode: (Int) -> Unit = {},
    ) : DeviceBenchEngine {
        val prompts = mutableListOf<String>()
        var handOvers = 0
        var releases = 0

        override suspend fun model() = loaded

        override fun idle() = !busy

        override fun stillLoaded(model: DeviceBenchModel) = model == loaded

        override suspend fun handOver(): Boolean = ++handOvers != failHandOverAt

        override suspend fun paragraphTokens() = 60

        override suspend fun decode(text: String, maxTokens: Int): DeviceBenchDecode {
            prompts += text
            onDecode(maxTokens)
            if (prompts.size == interruptAt) return DeviceBenchDecode(null, interrupted = true)
            if (maxTokens == DeviceBenchPlan.WarmUpTokens && warmUpError != null) return DeviceBenchDecode(null, warmUpError)
            val timings = DecodeTimings(promptTokens = 600, promptMs = 10_000.0, evalTokens = 63, evalMs = 6_300.0)
            return DeviceBenchDecode(DecodeStats(tokensGenerated = 64, tokPerSec = 10f, ttftMs = 10_050, timings = timings))
        }

        override suspend fun releaseCache() {
            releases++
        }
    }

    private var n = 0
    private fun nonce() = "n${n++}"

    @Test
    fun measuresTheSizesThatFitAfterAWarmUp() = runTest {
        val engine = FakeEngine(model.copy(nCtx = 2048))
        val outcome = runDeviceBench(engine, { false }, nonce = ::nonce)
        // 2048 needs 2048 + 64 decoded + 128 headroom: it does not fit.
        val done = outcome as DeviceBenchOutcome.Done
        assertEquals(listOf(256, 1024), done.points.map { it.ctx })
        assertEquals(false, done.stopped)
        assertEquals(60.0, done.points[0].prefillTps!!, 0.001)
        assertEquals(10.0, done.points[0].decodeTps!!, 0.001)
        assertEquals(10_050L, done.points[0].ttftMs)
        // Warm-up, then one decode per size, each prompt its own.
        assertEquals(3, engine.prompts.size)
        assertEquals(3, engine.prompts.distinct().size)
        // The session handed over before the run and before each size, the cache left restorable after.
        assertEquals(3, engine.handOvers)
        assertEquals(1, engine.releases)
    }

    @Test
    fun aStopCutsTheSizeGoingOnAndKeepsTheOnesBefore() = runTest {
        var stop = false
        var decodes = 0
        val engine = FakeEngine(model, onDecode = { if (++decodes == 3) stop = true })
        val progress = mutableListOf<DeviceBenchProgress>()
        val done = runDeviceBench(engine, { stop }, nonce = ::nonce, onProgress = { progress += it }) as DeviceBenchOutcome.Done
        assertEquals(listOf(256), done.points.map { it.ctx })
        assertEquals(true, done.stopped)
        assertEquals(DeviceBenchProgress(0, 3, 256), progress.first())
        assertEquals(DeviceBenchProgress(1, 3, 1024), progress.last())
        assertEquals("a stop leaves the cache restorable too", 1, engine.releases)
    }

    @Test
    fun aDecodeCutShortIsAStopNotAMeasure() = runTest {
        // A Cancelled stop reason or a queue taken by something else, on the second size.
        val engine = FakeEngine(model, interruptAt = 3)
        val done = runDeviceBench(engine, { false }, nonce = ::nonce) as DeviceBenchOutcome.Done
        assertEquals(listOf(256), done.points.map { it.ctx })
        assertEquals(true, done.stopped)
        assertEquals(1, engine.releases)
        // A warm-up cut short: nothing measured, a stop all the same.
        val warmUp = runDeviceBench(FakeEngine(model, interruptAt = 1), { false }, nonce = ::nonce) as DeviceBenchOutcome.Done
        assertEquals(emptyList<DeviceBenchPoint>(), warmUp.points)
        assertEquals(true, warmUp.stopped)
    }

    @Test
    fun theCacheIsLeftAsItIsWhenTheBenchNeverHadItOrSomeoneTookItOver() = runTest {
        for (engine in listOf(FakeEngine(null), FakeEngine(model, busy = true), FakeEngine(model.copy(nCtx = 300)), FakeEngine(model, failHandOverAt = 1))) {
            runDeviceBench(engine, { false }, nonce = ::nonce)
            assertEquals(0, engine.releases)
        }
        // A conversation sent to between two sizes whose session could not be saved: the cache is that conversation's.
        val takenOver = FakeEngine(model, failHandOverAt = 3)
        val done = runDeviceBench(takenOver, { false }, nonce = ::nonce) as DeviceBenchOutcome.Done
        assertEquals(listOf(256), done.points.map { it.ctx })
        assertEquals(0, takenOver.releases)
        // A failed warm-up decoded something: released.
        val failed = FakeEngine(model, warmUpError = "boom")
        runDeviceBench(failed, { false }, nonce = ::nonce)
        assertEquals(1, failed.releases)
    }

    @Test
    fun noModelABusyModelTooSmallAContextAFailedWarmUp() = runTest {
        assertEquals(DeviceBenchOutcome.NoModel, runDeviceBench(FakeEngine(null), { false }, nonce = ::nonce))
        assertEquals(DeviceBenchOutcome.Busy, runDeviceBench(FakeEngine(model, busy = true), { false }, nonce = ::nonce))
        assertEquals(DeviceBenchOutcome.NothingFits, runDeviceBench(FakeEngine(model.copy(nCtx = 300)), { false }, nonce = ::nonce))
        val failed = runDeviceBench(FakeEngine(model, warmUpError = "boom"), { false }, nonce = ::nonce)
        assertTrue(failed.toString(), failed is DeviceBenchOutcome.Failed)
    }

    @Test
    fun theBenchmarksCodeImportsNothingThatReachesTheNetwork() {
        val files = listOf(
            "src/main/kotlin/io/github/tensaiapp/bench/DeviceBenchCore.kt",
            "src/main/kotlin/io/github/tensaiapp/bench/DeviceBench.kt",
            "src/main/kotlin/io/github/tensaiapp/ui/screens/settings/DeviceBenchPanel.kt",
        )
        val forbidden = listOf("java.net.", "javax.net.", "okhttp3.", "android.net.", "io.github.tensaiapp.websearch", "io.github.tensaiapp.tools")
        for (path in files) {
            val imports = File(path).readLines().filter { it.startsWith("import ") }
            assertTrue("$path has no imports?", imports.isNotEmpty())
            val hits = imports.filter { line -> forbidden.any { line.removePrefix("import ").startsWith(it) } }
            assertEquals("$path imports $hits", emptyList<String>(), hits)
        }
    }
}
