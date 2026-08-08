package io.github.hokanosekai.tensai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReasoningBudgetTrackerTest {

    @Test
    fun reasoningBudgetExhaustedBeforeMarkerEverAppears() {
        val tracker = ReasoningBudgetTracker(
            ReasoningBudget(reasoningMaxTokens = 3, responseMaxTokens = 10, endMarker = "</think>"),
        )

        assertNull(tracker.onToken("still"))
        assertNull(tracker.onToken("thinking"))
        assertEquals(StopReason.ReasoningBudgetExhausted, tracker.onToken("hard"))
    }

    @Test
    fun responseBudgetExhaustedAfterMarkerCloses() {
        val tracker = ReasoningBudgetTracker(
            ReasoningBudget(reasoningMaxTokens = 10, responseMaxTokens = 2, endMarker = "</think>"),
        )

        assertNull(tracker.onToken("reasoning"))
        assertNull(tracker.onToken("</think>"))
        assertNull(tracker.onToken("the"))
        assertEquals(StopReason.ResponseBudgetExhausted, tracker.onToken("answer"))
    }

    @Test
    fun markerFragmentedAcrossManyTokensIsStillMatched() {
        val tracker = ReasoningBudgetTracker(
            ReasoningBudget(reasoningMaxTokens = 100, responseMaxTokens = 1, endMarker = "</think>"),
        )

        "reasoning</thi".forEach { char -> assertNull(tracker.onToken(char.toString())) }
        // The marker completes here: "nk>" finishes what "</thi" started — this token is what
        // flips the phase, so it's still attributed to reasoning and returns null.
        assertNull(tracker.onToken("nk>"))
        // First token counted against the response budget (cap of 1) — exhausts it immediately.
        assertEquals(StopReason.ResponseBudgetExhausted, tracker.onToken("answer"))
    }

    @Test
    fun markerSplitOneCharacterAtATimeAcrossTheBoundary() {
        val tracker = ReasoningBudgetTracker(
            ReasoningBudget(reasoningMaxTokens = 100, responseMaxTokens = 100, endMarker = "</think>"),
        )

        "reasoning".forEach { char -> assertNull(tracker.onToken(char.toString())) }
        // Feed "</think>" one character at a time — the marker must still be recognised once the
        // last character lands, even though no single token ever contained more than one char of it.
        "</think>".dropLast(1).forEach { char -> assertNull(tracker.onToken(char.toString())) }
        assertNull(tracker.onToken(">"))

        // Now in the response phase, counted against responseMaxTokens (100) — the reasoning
        // budget (also 100, already nearly spent character-by-character above) must have no
        // further say from here on.
        val stopReason = (1..150).asSequence().map { tracker.onToken("x") }.firstOrNull { it != null }
        assertEquals(StopReason.ResponseBudgetExhausted, stopReason)
    }

    @Test
    fun neverClosingTheThinkBlockEventuallyExhaustsTheReasoningBudgetEvenWithPlentyOfResponseRoom() {
        val tracker = ReasoningBudgetTracker(
            ReasoningBudget(reasoningMaxTokens = 5, responseMaxTokens = 1000, endMarker = "</think>"),
        )

        var stopReason: StopReason? = null
        repeat(5) { i -> stopReason = tracker.onToken("thought $i ") }

        assertEquals(StopReason.ReasoningBudgetExhausted, stopReason)
    }

    @Test
    fun staysWithinBothBudgetsWhenTheModelWrapsUpOnItsOwn() {
        val tracker = ReasoningBudgetTracker(
            ReasoningBudget(reasoningMaxTokens = 10, responseMaxTokens = 10, endMarker = "</think>"),
        )

        assertNull(tracker.onToken("a short thought"))
        assertNull(tracker.onToken("</think>"))
        assertNull(tracker.onToken("a short answer"))
    }

    @Test
    fun samplingConfigReasoningBudgetDefaultsToNull() {
        assertNull(SamplingConfig().reasoningBudget)
    }
}
