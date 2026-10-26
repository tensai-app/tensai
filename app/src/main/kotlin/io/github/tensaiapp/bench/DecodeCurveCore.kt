package io.github.tensaiapp.bench

import kotlin.math.roundToInt

/**
 * The debug decode curve (TEN-188): decode speed against the context already in the KV cache, no tools, thinking
 * off. The runner is in the debug source set; what is pure is here. The bench screen's Decode tab (TEN-190) picks the
 * points of [Lengths] and the tokens decoded at each.
 */
object DecodeCurve {
    /** Context lengths measured, in tokens of filler before the instruction. */
    val Lengths = listOf(0, 512, 1024, 2048, 4096)

    /** Tokens decoded at each point by default: the instruction asks for far more, so the cap ends it. */
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

    /** The tokens per point the bench screen offers (TEN-190). */
    val EvalTokenChoices = listOf(64, EvalTokens, 256)

    /** [lengths] that fit [nCtx] with [evalTokens] decoded and [HeadroomTokens] to spare. */
    fun lengthsFor(nCtx: Int, lengths: List<Int> = Lengths, evalTokens: Int = EvalTokens): List<Int> =
        lengths.filter { contextNeeded(it, evalTokens) <= nCtx }

    /** The context a point of [ctx] tokens needs: its filler, its [evalTokens] decoded, [HeadroomTokens]. */
    fun contextNeeded(ctx: Int, evalTokens: Int = EvalTokens): Int = ctx + evalTokens + HeadroomTokens

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
