package io.github.tensaiapp.bench

import io.github.tensaiapp.engine.DecodeStats
import java.util.Locale

/**
 * The release benchmark of Settings > This device (TEN-208): prefill and decode speed and the time to the first token,
 * at a few context sizes, on the model already loaded. What is pure is here, the runner is [DeviceBench]. The decode
 * curve of a debug build ([DecodeCurve], Settings > Bench) measures the same way with more points and more knobs; the
 * agent bench stays debug-only.
 */
object DeviceBenchPlan {
    /** Context sizes measured, in tokens of filler, those that fit the loaded context. A few minutes on a mid-range phone. */
    val Lengths = listOf(256, 1024, 2048)

    /** Tokens decoded at each size. */
    const val EvalTokens = 64

    /** The short unmeasured decode before the first size. */
    const val WarmUpTokens = 8
}

/** The loaded model, as the report names it. */
data class DeviceBenchModel(
    val name: String,
    val quant: String?,
    val backend: String,
    val threads: Int?,
    val nCtx: Int,
)

/** One context size: llama.cpp's prefill and decode rates, the time to the first token, or why the decode failed. */
data class DeviceBenchPoint(
    val ctx: Int,
    val prefillTps: Double?,
    val decodeTps: Double?,
    val ttftMs: Long?,
    val error: String? = null,
) {
    companion object {
        fun of(ctx: Int, stats: DecodeStats?, error: String?): DeviceBenchPoint {
            val timings = stats?.timings
            return DeviceBenchPoint(
                ctx = ctx,
                prefillTps = timings?.let { rate(it.promptTokens, it.promptMs) },
                decodeTps = timings?.let { rate(it.evalTokens, it.evalMs) },
                ttftMs = stats?.ttftMs,
                error = error,
            )
        }

        private fun rate(tokens: Int, ms: Double): Double? = if (tokens <= 0 || ms <= 0) null else tokens * 1000.0 / ms
    }
}

/** What the runner needs from the engine. The production one is the inference queue ([DeviceBench]); tests fake it. */
interface DeviceBenchEngine {
    /** The model loaded and idle, `null` when none is loaded. */
    suspend fun model(): DeviceBenchModel?

    /** Nothing else is using the model (no reply streaming, no warm-up). */
    fun idle(): Boolean

    /** The same model is still loaded. */
    fun stillLoaded(model: DeviceBenchModel): Boolean

    /** Saves the open conversation's session so the bench may overwrite the cache. `false` when it could not. */
    suspend fun handOver(): Boolean

    /** Tokens of one [DecodeCurve.Paragraph] on the loaded model, `null` when it cannot count. */
    suspend fun paragraphTokens(): Int?

    /** One decode of [text] as the only user message, capped at [maxTokens]. */
    suspend fun decode(text: String, maxTokens: Int): DeviceBenchDecode

    /** Leaves the cache restorable once the run is over: it holds filler nobody owns ([io.github.tensaiapp.runtime.InferenceQueue.releaseBenchCache]). */
    suspend fun releaseCache()
}

/**
 * One decode's [stats], or why it failed ([error]). [interrupted]: it did not run to its end for a reason that is not the
 * model's (a stop, a cancel, the queue taken by something else), so it measures nothing.
 */
data class DeviceBenchDecode(val stats: DecodeStats?, val error: String? = null, val interrupted: Boolean = false)

/** How a run ended. */
sealed interface DeviceBenchOutcome {
    data object NoModel : DeviceBenchOutcome

    data object Busy : DeviceBenchOutcome

    /** Not even the smallest size fits the loaded context. */
    data object NothingFits : DeviceBenchOutcome

    /** Stopped by the engine before any size was measured: [reason] in English, for the log. */
    data class Failed(val reason: String) : DeviceBenchOutcome

    /** The sizes measured, all of them unless [stopped]. */
    data class Done(val model: DeviceBenchModel, val points: List<DeviceBenchPoint>, val stopped: Boolean) : DeviceBenchOutcome
}

/** Where a run is: [done] sizes measured of [total], [current] the one being measured. */
data class DeviceBenchProgress(val done: Int, val total: Int, val current: Int?)

/**
 * Runs the plan on [engine]: a warm-up decode, then one decode per size of [lengths] that fits, each prompt opening with
 * its own [nonce] so it prefills from an almost empty cache ([DecodeCurve.prompt]). [stopRequested] is read before and
 * after each decode: a stop cuts the decode going on short (the caller cancels it), and a size cut short, or any decode
 * [DeviceBenchDecode.interrupted], is dropped and ends the run as stopped. Once the cache was handed over, it is left
 * restorable ([DeviceBenchEngine.releaseCache]) however the run ends.
 */
suspend fun runDeviceBench(
    engine: DeviceBenchEngine,
    stopRequested: () -> Boolean,
    lengths: List<Int> = DeviceBenchPlan.Lengths,
    tokens: Int = DeviceBenchPlan.EvalTokens,
    nonce: () -> String,
    onProgress: (DeviceBenchProgress) -> Unit = {},
): DeviceBenchOutcome {
    val model = engine.model() ?: return DeviceBenchOutcome.NoModel
    if (!engine.idle()) return DeviceBenchOutcome.Busy
    val sizes = DecodeCurve.lengthsFor(model.nCtx, lengths, tokens)
    if (sizes.isEmpty()) return DeviceBenchOutcome.NothingFits
    if (!engine.handOver()) return DeviceBenchOutcome.Failed("could not save the open conversation's session")
    // Not after a handover that failed mid-run: the cache then holds a conversation sent to meanwhile, not saved.
    var release = true
    try {
        val paragraph = engine.paragraphTokens()?.takeIf { it > 0 } ?: return DeviceBenchOutcome.Failed("cannot count tokens")
        val warmUp = engine.decode(DecodeCurve.prompt(nonce(), 0), DeviceBenchPlan.WarmUpTokens)
        if (warmUp.interrupted || stopRequested()) return DeviceBenchOutcome.Done(model, emptyList(), stopped = true)
        warmUp.error?.let { return DeviceBenchOutcome.Failed("the warm-up decode failed: $it") }
        val points = mutableListOf<DeviceBenchPoint>()
        for (ctx in sizes) {
            if (stopRequested() || !engine.stillLoaded(model)) return DeviceBenchOutcome.Done(model, points, stopped = true)
            if (!engine.handOver()) {
                release = false
                return DeviceBenchOutcome.Done(model, points, stopped = true)
            }
            onProgress(DeviceBenchProgress(points.size, sizes.size, ctx))
            val decode = engine.decode(DecodeCurve.prompt(nonce(), DecodeCurve.paragraphsFor(ctx, paragraph)), tokens)
            if (decode.interrupted || stopRequested()) return DeviceBenchOutcome.Done(model, points, stopped = true)
            points += DeviceBenchPoint.of(ctx, decode.stats, decode.error)
            onProgress(DeviceBenchProgress(points.size, sizes.size, null))
        }
        return DeviceBenchOutcome.Done(model, points, stopped = false)
    } finally {
        if (release) engine.releaseCache()
    }
}

/**
 * The backend of an engine label as the bench shows and saves it: "CPU", "Vulkan". The engine reports
 * "<backend>: <device>" ("CPU: CPU", "Vulkan: Mali-G68"); a label with no colon is kept whole.
 */
fun backendName(label: String): String = label.substringBefore(':').trim().ifBlank { label }

/**
 * The plain-text report a tester pastes into a "Device report" issue (TEN-208). The field order is the contract with
 * that form: keep it. Labels stay in English whatever the app's language, so every report reads the same. Per-size
 * values are listed in the order of [contextSizes].
 */
data class DeviceReport(
    val appVersion: String,
    val phone: String,
    val soc: String?,
    val ramBytes: Long,
    val android: String,
    val model: DeviceBenchModel,
    val points: List<DeviceBenchPoint>,
    val charging: Boolean?,
    val thermal: String?,
    val stopped: Boolean = false,
) {
    fun text(): String = buildString {
        line("app version", appVersion)
        line("phone", phone)
        line("SoC", soc)
        // 1024^3, the unit of the This device screen next to it.
        line("RAM", if (ramBytes > 0) String.format(Locale.ROOT, "%.1f GB", ramBytes / (1024.0 * 1024.0 * 1024.0)) else null)
        line("Android version", android)
        line("model", model.name)
        line("quant", model.quant)
        line("backend", model.backend)
        line("threads", model.threads?.toString() ?: "auto")
        line("context sizes", points.joinToString(", ") { it.ctx.toString() })
        line("prefill tok/s", points.joinToString(", ") { number(it.prefillTps) })
        line("decode tok/s", points.joinToString(", ") { number(it.decodeTps) })
        line("first token (ms)", points.joinToString(", ") { it.ttftMs?.toString() ?: "-" })
        line("charging", charging?.let { if (it) "yes" else "no" })
        line("thermal status", thermal)
        if (stopped) append("(stopped before the last size)\n")
    }

    private fun StringBuilder.line(label: String, value: String?) {
        append(label).append(": ").append(value?.takeIf { it.isNotBlank() } ?: "unknown").append('\n')
    }

    private fun number(v: Double?): String = v?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "-"

    companion object {
        /** [android.os.PowerManager]'s THERMAL_STATUS_* as a word, `null` for a value it did not have. */
        fun thermalName(status: Int): String? = when (status) {
            0 -> "none"
            1 -> "light"
            2 -> "moderate"
            3 -> "severe"
            4 -> "critical"
            5 -> "emergency"
            6 -> "shutdown"
            else -> null
        }
    }
}
