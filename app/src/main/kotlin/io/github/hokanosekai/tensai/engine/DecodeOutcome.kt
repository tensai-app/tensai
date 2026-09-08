package io.github.hokanosekai.tensai.engine

/**
 * Why a finished decode stopped, worked out from the little llama.cpp tells us about it (TEN-79).
 *
 * `completion()` reports no reason of its own — it returns how many tokens it sampled and nothing
 * else — so the ending has to be inferred by comparing that count against the cap it was given.
 * Getting the *count* wrong is what made this worth naming: [LlamaKtEngineImpl] used to count token
 * callbacks instead, which is a smaller and unrelated number (llama.kt holds a token back while a
 * UTF-8 sequence is incomplete, so a single accented character costs a callback), and a decode that
 * had spent its whole budget was reported as [StopReason.Complete] — a reply cut off mid-sentence,
 * persisted with no mark on it, and replayed into every later prompt as if the model had finished
 * speaking.
 *
 * Kept as its own object for the same reason [ContextBudget] is: this is the part of
 * [LlamaKtEngineImpl.decode] actually worth a unit test, and the rest of that method cannot be
 * exercised without a native engine.
 */
internal object DecodeOutcome {

    /**
     * @param budgetStopReason set when [ReasoningBudgetTracker] cut the decode short itself; it saw
     *   the ending first-hand and so outranks anything inferred here.
     * @param tokensPredicted tokens llama.cpp actually sampled, as returned by `completion()`.
     *   **Not** a count of token callbacks — see this object's KDoc.
     * @param nPredict the cap llama.cpp was given, which TEN-65 may have clipped below what the
     *   caller asked for.
     * @param clipped whether that clip actually happened. Decides which of the two "ran out of
     *   budget" endings applies, because only one of them names something the user can change —
     *   see [StopReason.ContextBudgetClipped].
     * @param phaseStopReason [ReasoningBudgetTracker.currentPhaseStopReason], or `null` for a
     *   decode with no reasoning/response split.
     */
    fun stopReason(
        budgetStopReason: StopReason?,
        tokensPredicted: Int,
        nPredict: Int,
        clipped: Boolean,
        phaseStopReason: StopReason?,
    ): StopReason = budgetStopReason ?: when {
        // Stopped before the cap: end-of-sequence, or a stop sequence matched. The two are
        // indistinguishable from out here and deliberately merged (see [StopReason.Complete]).
        tokensPredicted < nPredict -> StopReason.Complete
        // The cap the caller set was never in play — the context window imposed a smaller one, and
        // pointing the user at their max-tokens setting would send them to change a number that
        // cannot lengthen this reply. Ahead of both budget endings, including the reasoning
        // tracker's, whose phases describe a split of a budget this decode never got to spend.
        clipped -> StopReason.ContextBudgetClipped
        phaseStopReason != null -> phaseStopReason
        else -> StopReason.TokenBudget
    }
}
