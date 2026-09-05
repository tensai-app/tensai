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
    fun responseRunsToTheWholeBudgetOnceTheMarkerCloses() {
        val tracker = ReasoningBudgetTracker(
            ReasoningBudget(reasoningMaxTokens = 10, responseMaxTokens = 2, endMarker = "</think>"),
        )

        assertNull(tracker.onToken("reasoning"))
        assertNull(tracker.onToken("</think>"))

        // Two reasoning tokens spent of a 12-token total, so the answer has 10 — responseMaxTokens
        // is the floor it was guaranteed, not a ceiling it is held to (TEN-69).
        repeat(9) { assertNull(tracker.onToken("the")) }
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

        // 15 reasoning tokens of a 101-token total leaves the answer 86.
        repeat(85) { assertNull(tracker.onToken("answer")) }
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

        // Now in the response phase, which gets the 183 tokens the 17 spent character-by-character
        // above left of the 200-token total — the reasoning cap (100, nearly spent) must have no
        // further say from here on.
        repeat(182) { assertNull(tracker.onToken("x")) }
        assertEquals(StopReason.ResponseBudgetExhausted, tracker.onToken("x"))
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

    /**
     * The case recorded on device on 2026-09-05 (Qwen3-1.7B, thinking on, "write a detailed
     * essay"): the model opened and closed its think block in three tokens, then answered. The
     * persisted row read `tokensGenerated = 1027`, `stopReason = ResponseBudgetExhausted`,
     * `thinking IS NULL` — the answer was cut after 1024 tokens even though 1021 tokens of the
     * reasoning half had never been spent and the decode had 2048 to give.
     */
    @Test
    fun anEmptyThinkBlockLeavesItsWholeBudgetToTheResponse() {
        val tracker = ReasoningBudgetTracker(
            ReasoningBudget(reasoningMaxTokens = 1024, responseMaxTokens = 1024, endMarker = "</think>"),
        )

        assertNull(tracker.onToken("<think>"))
        assertNull(tracker.onToken("\n\n"))
        assertNull(tracker.onToken("</think>"))

        repeat(2044) { i ->
            assertNull("stopped at answer token ${i + 1}", tracker.onToken("word "))
        }
        assertEquals(StopReason.ResponseBudgetExhausted, tracker.onToken("last"))
    }

    /**
     * Same defect, in the form no end-marker scan can ever catch: the model emits no reasoning
     * block at all, so there is no closing marker to find. Only the engine's own parse
     * ([DecodeEvent.Parsed], fed here through [ReasoningBudgetTracker.onParsed]) can say the model
     * went straight to its answer.
     */
    @Test
    fun aModelThatSkipsReasoningEntirelyAnswersIntoTheWholeBudget() {
        val tracker = ReasoningBudgetTracker(
            ReasoningBudget(reasoningMaxTokens = 1024, responseMaxTokens = 1024, endMarker = "</think>"),
        )

        // llama.kt fires the token callback first, then the chat-parse callback for that same
        // token — the order the engine's two callbacks run in.
        val answer = StringBuilder()
        repeat(2047) { i ->
            answer.append("word ")
            assertNull("stopped at answer token ${i + 1}", tracker.onToken("word "))
            tracker.onParsed(answer.toString(), "reasoning so far")
        }
        assertEquals(StopReason.ResponseBudgetExhausted, tracker.onToken("last"))
    }

    /**
     * Symptom 1 of TEN-69: harmony/gpt-oss and Gemma-4 delimit reasoning with `<|channel|>`, so a
     * tracker looking for `</think>` never leaves the reasoning phase and cuts the decode at
     * [ReasoningBudget.reasoningMaxTokens] with no answer written. The engine's parse knows the
     * difference without any marker being configured for it.
     */
    @Test
    fun engineSplitEndsTheReasoningPhaseForAModelThatNeverEmitsTheMarker() {
        val tracker = ReasoningBudgetTracker(
            ReasoningBudget(reasoningMaxTokens = 4, responseMaxTokens = 6, endMarker = "</think>"),
        )

        // Analysis channel: the parser reports reasoning, no content yet.
        assertNull(tracker.onToken("<|channel|>analysis<|message|>"))
        tracker.onParsed("", "reasoning so far")
        assertNull(tracker.onToken("weighing it up"))
        tracker.onParsed("", "reasoning so far")

        // Final channel: content appears, which is the engine saying the answer has started.
        assertNull(tracker.onToken("<|channel|>final<|message|>"))
        tracker.onParsed("Here", "reasoning so far")

        // Would have been ReasoningBudgetExhausted on the fourth token without the split above.
        repeat(6) { assertNull(tracker.onToken("more answer")) }
        assertEquals(StopReason.ResponseBudgetExhausted, tracker.onToken("last"))
    }

    /** The engine's split must not disarm the reasoning cap for a model that really does reason. */
    @Test
    fun engineSplitStillCapsReasoningThatRunsLong() {
        val tracker = ReasoningBudgetTracker(
            ReasoningBudget(reasoningMaxTokens = 3, responseMaxTokens = 100, endMarker = "</think>"),
        )

        assertNull(tracker.onToken("thinking"))
        tracker.onParsed("", "reasoning so far")
        assertNull(tracker.onToken("still thinking"))
        tracker.onParsed("", "reasoning so far")
        assertEquals(StopReason.ReasoningBudgetExhausted, tracker.onToken("and still"))
    }

    /**
     * A parse is not monotonic — it can retract content a later token disproves (see
     * [DecodeEvent.Parsed]) — so the phase follows the engine back rather than latching on the
     * first flip, which would leave the reasoning cap disarmed for the rest of the decode.
     */
    @Test
    fun engineRetractingItsContentPutsThePhaseBackToReasoning() {
        val tracker = ReasoningBudgetTracker(
            ReasoningBudget(reasoningMaxTokens = 2, responseMaxTokens = 100, endMarker = "</think>"),
        )

        assertNull(tracker.onToken("<"))
        tracker.onParsed("<", "reasoning so far")
        assertNull(tracker.onToken("think>"))
        tracker.onParsed("", "reasoning so far")

        assertEquals(StopReason.ReasoningBudgetExhausted, tracker.onToken("reasoning"))
    }

    /**
     * Handing tokens back and forth between the two counters must not let their sum drift past what
     * the decode was allowed in total — the reasoning cap alone cannot hold it down once the answer
     * has already spent most of the budget.
     */
    @Test
    fun theTotalStillHoldsWhenAParseHandsTokensBackToTheReasoningPhase() {
        val tracker = ReasoningBudgetTracker(
            ReasoningBudget(reasoningMaxTokens = 100, responseMaxTokens = 5, endMarker = "</think>"),
        )

        assertNull(tracker.onToken("first"))
        tracker.onParsed("first", "reasoning so far")
        repeat(99) { assertNull(tracker.onToken("answer")) }
        tracker.onParsed("", "reasoning so far")

        // 100 of the 105 tokens are gone and the reasoning cap is nowhere near: only the total can
        // stop this, and the reason names the phase the decode died in.
        repeat(4) { assertNull(tracker.onToken("thinking again")) }
        assertEquals(StopReason.ReasoningBudgetExhausted, tracker.onToken("and again"))
    }

    /** Once the engine has spoken, a stray marker in the raw stream must not move the phase. */
    @Test
    fun engineSplitOutranksAMarkerInTheRawStream() {
        val tracker = ReasoningBudgetTracker(
            ReasoningBudget(reasoningMaxTokens = 3, responseMaxTokens = 100, endMarker = "</think>"),
        )

        assertNull(tracker.onToken("reasoning"))
        tracker.onParsed("", "reasoning so far")
        // The model quoting its own closing tag inside the reasoning it is still writing.
        assertNull(tracker.onToken("</think>"))
        tracker.onParsed("", "reasoning so far")
        assertEquals(StopReason.ReasoningBudgetExhausted, tracker.onToken("more reasoning"))
    }

    /**
     * A parse carrying neither content nor reasoning has resolved nothing, and must not be read as
     * "the engine reports reasoning": taking it would hand the phase over and switch the marker scan
     * off for good, so the `</think>` that follows would never be seen and the answer would be cut at
     * the reasoning cap — TEN-60 reopened. llama.kt doesn't currently deliver such a call, but
     * `ChatParseCallback` does not promise it won't.
     */
    @Test
    fun aParseThatResolvedNothingDoesNotHandThePhaseOver() {
        val tracker = ReasoningBudgetTracker(
            ReasoningBudget(reasoningMaxTokens = 3, responseMaxTokens = 5, endMarker = "</think>"),
        )

        assertNull(tracker.onToken("reasoning"))
        tracker.onParsed("", "")
        // The marker scan has to still be armed, or this switch never happens.
        assertNull(tracker.onToken("</think>"))
        repeat(5) { assertNull(tracker.onToken("answer")) }
        assertEquals(StopReason.ResponseBudgetExhausted, tracker.onToken("last"))
    }

    /** No parse ever arrives (raw completion, unrenderable template): the marker scan still runs. */
    @Test
    fun markerScanStillDrivesThePhaseWhenNoParseEverArrives() {
        val tracker = ReasoningBudgetTracker(
            ReasoningBudget(reasoningMaxTokens = 3, responseMaxTokens = 5, endMarker = "</think>"),
        )

        assertNull(tracker.onToken("reasoning"))
        assertNull(tracker.onToken("</think>"))
        repeat(5) { assertNull(tracker.onToken("answer")) }
        assertEquals(StopReason.ResponseBudgetExhausted, tracker.onToken("last"))
    }

    @Test
    fun currentPhaseStopReasonFollowsTheEngineSplit() {
        val tracker = ReasoningBudgetTracker(
            ReasoningBudget(reasoningMaxTokens = 100, responseMaxTokens = 100, endMarker = "</think>"),
        )

        assertEquals(StopReason.ReasoningBudgetExhausted, tracker.currentPhaseStopReason())
        tracker.onToken("thinking")
        tracker.onParsed("", "reasoning so far")
        assertEquals(StopReason.ReasoningBudgetExhausted, tracker.currentPhaseStopReason())
        tracker.onToken("answering")
        tracker.onParsed("the answer", "reasoning so far")
        assertEquals(StopReason.ResponseBudgetExhausted, tracker.currentPhaseStopReason())
    }

    @Test
    fun samplingConfigReasoningBudgetDefaultsToNull() {
        assertNull(SamplingConfig().reasoningBudget)
    }
}
