package io.github.hokanosekai.tensai.engine

/**
 * Tracks a decode's [ReasoningBudget] token by token, deciding when [LlamaKtEngineImpl] should cut
 * generation short (TEN-61). One instance per decode.
 *
 * This has to live here, counting llama.kt's own `TokenCallback` invocations, rather than being
 * derived from [DecodeEvent.Token] text further up in the app: llama.kt calls back exactly once
 * per sampled token, but [DecodeEvent.Token] is contractually "maybe a fragment", so a count kept
 * from that side could drift from [DecodeStats.tokensGenerated]. See [ReasoningBudget]'s KDoc for
 * why the marker itself is opaque to this class.
 *
 * [ReasoningBudget.endMarker] arrives fragmented across an arbitrary number of tokens — a model
 * rarely emits a closing tag as a single token — so matching holds back only the suffix of
 * accumulated reasoning text that could still grow into the marker (at most `endMarker.length - 1`
 * characters, same trick as
 * [io.github.hokanosekai.tensai.data.chat.ThinkingStreamParser]'s own lookahead) and drops
 * everything else as it goes, then drops the whole window once the marker is found — nothing about
 * a long reasoning phase accumulates in memory once its end has been seen.
 */
internal class ReasoningBudgetTracker(private val budget: ReasoningBudget) {

    private enum class Phase { Reasoning, Response }

    private var phase = Phase.Reasoning
    private var reasoningTokens = 0
    private var responseTokens = 0

    /** Suffix of reasoning text seen so far that could still grow into [ReasoningBudget.endMarker]. */
    private val window = StringBuilder()

    /**
     * Records one streamed token's [text]. Returns the [StopReason] generation should be cut off
     * for, or `null` to keep going.
     */
    fun onToken(text: String): StopReason? {
        if (phase == Phase.Reasoning) {
            reasoningTokens++
            window.append(text)
            if (window.indexOf(budget.endMarker) >= 0) {
                phase = Phase.Response
                window.setLength(0)
                return null
            }
            val keep = longestSuffixThatIsMarkerPrefix()
            if (keep < window.length) window.delete(0, window.length - keep)
            return if (reasoningTokens >= budget.reasoningMaxTokens) StopReason.ReasoningBudgetExhausted else null
        }
        responseTokens++
        return if (responseTokens >= budget.responseMaxTokens) StopReason.ResponseBudgetExhausted else null
    }

    /**
     * Which [StopReason] this decode would be blamed on if generation stopped right now — [phase]'s
     * own cap, not the caller's outer `nPredict`. Normally unreachable: [onToken] fires before or
     * exactly when the running total reaches `reasoningMaxTokens + responseMaxTokens`, so
     * [LlamaKtEngineImpl] never has to fall back to this (TEN-61's "outer safety net" KDoc). TEN-65
     * can clip the outer `nPredict` below that sum when the context window is tight, though, in
     * which case llama.cpp's own cap wins the race before either phase counter above gets there —
     * this is what [LlamaKtEngineImpl] asks instead of guessing, so the reported reason still names
     * the right phase rather than defaulting to a misleading [StopReason.Complete].
     */
    fun currentPhaseStopReason(): StopReason =
        if (phase == Phase.Reasoning) StopReason.ReasoningBudgetExhausted else StopReason.ResponseBudgetExhausted

    private fun longestSuffixThatIsMarkerPrefix(): Int {
        val marker = budget.endMarker
        val maxLen = minOf(window.length, marker.length - 1)
        for (len in maxLen downTo 1) {
            if (window.endsWith(marker.substring(0, len))) return len
        }
        return 0
    }
}
