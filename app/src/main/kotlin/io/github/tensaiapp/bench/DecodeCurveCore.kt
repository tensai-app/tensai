package io.github.tensaiapp.bench

import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The debug decode curve (TEN-188): decode speed against the context already in the KV cache, no tools, thinking
 * off. The runner is in the debug source set; what is pure is here.
 */
object DecodeCurve {
    /** Context lengths measured, in tokens of filler before the instruction. */
    val Lengths = listOf(0, 512, 1024, 2048, 4096)

    /** Tokens decoded at each point: the instruction asks for far more, so the cap ends it. */
    const val EvalTokens = 128

    /** Room kept free past a point's filler and decode, for the template, the instruction and the margins. */
    const val HeadroomTokens = 128

    /**
     * Asks for more than [EvalTokens] tokens of output from any model (Qwen splits numbers into digits: ~3 tokens a
     * line), so the decode ends on its cap rather than on an end of turn.
     */
    const val Instruction = "Count from 1 to 300, one number per line. Write only the numbers."

    /**
     * Neutral prose repeated to fill the context. Ends with a blank line so repeats tokenize the same way each
     * time, which keeps the filler's length a multiple of [Paragraph]'s count.
     */
    const val Paragraph =
        "The harbour town wakes slowly. Fishing boats come back before noon, the market opens on the square, " +
            "and the ferry to the islands leaves twice a day when the sea allows it. In winter most shops close early; " +
            "in summer the streets stay busy late into the evening, and the old lighthouse is open to visitors.\n\n"

    /** [lengths] that fit [nCtx] with [EvalTokens] decoded and [HeadroomTokens] to spare. */
    fun lengthsFor(nCtx: Int, lengths: List<Int> = Lengths): List<Int> =
        lengths.filter { it + EvalTokens + HeadroomTokens <= nCtx }

    /** How many [Paragraph]s make about [targetTokens], given one is [paragraphTokens] long. */
    fun paragraphsFor(targetTokens: Int, paragraphTokens: Int): Int {
        require(paragraphTokens > 0) { "paragraphTokens must be positive" }
        return (targetTokens.toDouble() / paragraphTokens).roundToInt()
    }

    /**
     * The user message of a point: [nonce] first, so no two prompts share more than the template's opening with the
     * one the cache holds and each point prefills from an almost empty cache; then [paragraphs] of filler; then the
     * [Instruction].
     */
    fun prompt(nonce: String, paragraphs: Int): String = buildString {
        append("[").append(nonce).append("]\n\n")
        repeat(paragraphs) { append(Paragraph) }
        append(Instruction)
    }
}

/**
 * What the model in memory was loaded with, for the log lines. [nThreads] `null` when llama.cpp picked it.
 */
data class CurveSetup(
    val model: String,
    val backend: String,
    val nCtx: Int,
    val nThreads: Int?,
    val flashAttn: String,
    val kv: String,
)

/**
 * One point of the curve.
 *
 * @param promptTokens the KV cache's fill after the decode minus [evalTokens] (one token off at most), `null` when
 *   it could not be read.
 * @param decodeTps tokens per second after the first token, as the chat reports it, `null` when not measured.
 * @param prefillTps [promptTokens] over the time to first token (prompt formatting and tokenizing included), `null` when not measured.
 */
data class DecodePoint(
    val ctx: Int,
    val promptTokens: Int?,
    val evalTokens: Int,
    val decodeTps: Double?,
    val prefillTps: Double?,
    val ttftMs: Long?,
    val stop: String?,
    val error: String? = null,
) {
    fun toLogLine(setup: CurveSetup): String = buildJsonObject {
        put("event", "decode")
        put("ctx", ctx)
        put("prompt_tokens", promptTokens)
        put("eval_tokens", evalTokens)
        put("decode_tps", decodeTps)
        put("prefill_tps", prefillTps)
        put("ttft_ms", ttftMs)
        put("stop", stop)
        putSetup(setup)
        error?.let { put("error", it) }
    }.toString()

    companion object {
        /** Rounded to 0.01, `null` for no figure (0 or less). */
        fun rate(tokens: Int, ms: Long): Double? = if (tokens <= 0 || ms <= 0) null else round2(tokens * 1000.0 / ms)

        internal fun round2(v: Double): Double = Math.round(v * 100) / 100.0
    }
}

private fun JsonObjectBuilder.putSetup(setup: CurveSetup) {
    put("n_threads", setup.nThreads)
    put("flash_attn", setup.flashAttn)
    put("kv", setup.kv)
}

/** The run's `decode_summary` line: the setup and every point's decode and prefill speed. */
fun decodeSummaryLine(setup: CurveSetup, points: List<DecodePoint>, skipped: List<Int>): String = buildJsonObject {
    put("event", "decode_summary")
    put("model", setup.model)
    put("backend", setup.backend)
    put("n_ctx", setup.nCtx)
    putSetup(setup)
    put(
        "points",
        buildJsonArray {
            points.forEach { p ->
                add(buildJsonObject {
                    put("ctx", p.ctx)
                    put("prompt_tokens", p.promptTokens)
                    put("decode_tps", p.decodeTps)
                    put("prefill_tps", p.prefillTps)
                })
            }
        },
    )
    put("skipped", buildJsonArray { skipped.forEach { add(JsonPrimitive(it)) } })
}.toString()

/** The run as lines for the debug panel, to be read off a screenshot: the setup, then one row per point. */
fun decodeDisplayLines(setup: CurveSetup, points: List<DecodePoint>, skipped: List<Int>): List<String> = buildList {
    fun fmt(v: Double?) = v?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
    add(setup.model)
    add("${setup.backend} · n_ctx ${setup.nCtx} · n_threads ${setup.nThreads ?: "auto"} · fa ${setup.flashAttn} · kv ${setup.kv}")
    add(String.format(Locale.US, "%6s %7s %5s %9s %9s", "ctx", "prompt", "eval", "dec t/s", "pre t/s"))
    points.forEach { p ->
        add(
            String.format(Locale.US, "%6d %7s %5d %9s %9s", p.ctx, p.promptTokens?.toString() ?: "-", p.evalTokens, fmt(p.decodeTps), fmt(p.prefillTps)) +
                (p.error?.let { " $it" } ?: ""),
        )
    }
    if (skipped.isNotEmpty()) add("skipped (over n_ctx): ${skipped.joinToString(", ")}")
}
