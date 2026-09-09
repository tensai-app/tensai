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
     *   the ending first-hand and so outranks anything inferred here. Except a negative
     *   [tokensPredicted], which says there was no ending to see.
     * @param tokensPredicted tokens llama.cpp actually sampled, as returned by `completion()`.
     *   **Not** a count of token callbacks — see this object's KDoc. Negative is not a count at
     *   all: it means a C++ exception escaped the decode and `tensai_jni.cpp`'s JNI guard answered
     *   with a sentinel instead of letting it kill the process (TEN-91). Answered with
     *   [StopReason.Failed] here.
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
    ): StopReason = when {
        // Ahead of `budgetStopReason`, and ahead of everything else: a negative count means the
        // decode never reached an ending to have a reason for. The reasoning tracker's own verdict
        // describes a budget it was watching being spent, which is not what happened either.
        //
        // First because of what the next branch would otherwise do with it. `-1 < nPredict` holds
        // for every cap there is, so a crashed decode would fall straight into
        // [StopReason.Complete] and be persisted, and later replayed into every prompt, as a reply
        // the model chose to end. That is the exact failure mode TEN-79 named for a different
        // cause, arriving by a new route.
        //
        // [StopReason.Failed] is what the app layer already calls this, and its contract holds:
        // engines report a failure by emitting [DecodeEvent.Error], which is what
        // [LlamaKtEngineImpl.decode] does on a negative count — it never builds a [DecodeStats]
        // around this value. The branch is here rather than only there so that "negative" cannot
        // read as "finished" through any caller, present or future.
        tokensPredicted < 0 -> StopReason.Failed
        budgetStopReason != null -> budgetStopReason
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
