package io.github.tensaiapp.engine

import io.github.tensaiapp.data.chat.SamplingRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEN-35's one real hazard, pinned. [LlamaKtEngineImpl] derives llama.cpp's `nPredict` from
 * `reasoningMaxTokens + responseMaxTokens`, and [ReasoningBudgetTracker] holds the whole decode to
 * that same sum — so the moment [SamplingConfig.maxTokens] became user-settable, any budget built
 * independently of it would have made the setting a lie: a user asking for 512 tokens would still
 * have generated up to the old hardcoded 2048.
 *
 * [ReasoningBudget.splitting] is what makes that impossible, and this is the test that says so.
 */
class ReasoningBudgetSplittingTest {

    private val marker = "</think>"

    /** The invariant itself, across the whole settable range and its edges. */
    @Test
    fun theTwoCapsAlwaysSumToTheMaxTokensTheyWereSplitFrom() {
        val cases = listOf(
            SamplingRanges.MaxTokens.first,
            SamplingRanges.MaxTokens.last,
            SamplingConfig().maxTokens,
            2, 3, 65, 127, 512, 513, 1000, 4097,
        )

        for (maxTokens in cases) {
            val budget = ReasoningBudget.splitting(maxTokens, marker)
            assertEquals(
                "reasoning + response must equal maxTokens for $maxTokens",
                maxTokens,
                budget.reasoningMaxTokens + budget.responseMaxTokens,
            )
        }
    }

    /**
     * The regression this replaces. Before TEN-35 the two caps were a pair of hardcoded 1024s;
     * TEN-61 and TEN-69's behaviour is defined against that split, so the default must land on it
     * exactly — a derived split that happened to change the default would silently re-tune every
     * thinking model in the app.
     */
    @Test
    fun theDefaultMaxTokensStillYieldsTheTenSixtyOneSplit() {
        val budget = ReasoningBudget.splitting(SamplingConfig().maxTokens, marker)

        assertEquals(1024, budget.reasoningMaxTokens)
        assertEquals(1024, budget.responseMaxTokens)
        assertEquals(marker, budget.endMarker)
    }

    /**
     * The concrete case from the ticket: 512 asked for, 512 generated at most — not 2048. Stated as
     * its own test because it is the bug in one line.
     */
    @Test
    fun aLoweredMaxTokensLowersWhatTheEngineWillActuallyGenerate() {
        val budget = ReasoningBudget.splitting(512, marker)

        assertEquals(
            "nPredict is derived from this sum — see LlamaKtEngineImpl",
            512,
            budget.reasoningMaxTokens + budget.responseMaxTokens,
        )
        assertEquals(256, budget.reasoningMaxTokens)
        assertEquals(256, budget.responseMaxTokens)
    }

    /** An odd total goes to the answer, which is the half the user reads. */
    @Test
    fun anOddTotalGivesTheExtraTokenToTheAnswer() {
        val budget = ReasoningBudget.splitting(513, marker)

        assertEquals(256, budget.reasoningMaxTokens)
        assertEquals(257, budget.responseMaxTokens)
    }

    /**
     * A reasoning cap of 0 stops the decode on its first token — [ReasoningBudgetTracker.onToken]
     * compares `>=` — which is a generation that can never produce anything. [SamplingRanges] keeps
     * a caller from getting near it, but the factory must be safe on its own.
     */
    @Test
    fun anAbsurdlySmallTotalStillLeavesRoomForOneReasoningToken() {
        for (maxTokens in listOf(Int.MIN_VALUE, -1, 0, 1, 2)) {
            val budget = ReasoningBudget.splitting(maxTokens, marker)
            assertTrue(
                "a reasoning cap of ${budget.reasoningMaxTokens} would stop the decode at its first token",
                budget.reasoningMaxTokens >= 1,
            )
            assertTrue(budget.responseMaxTokens >= 1)
        }
    }

    /**
     * The invariant is only worth anything if the tracker actually enforces it, so this walks a
     * real [ReasoningBudgetTracker] to the end of a 128-token budget and checks it stops there —
     * not at 1024, and not at the response cap of 64.
     */
    @Test
    fun theTrackerStopsAtTheSplitTotalAndNotAtTheOldHardcodedOne() {
        val budget = ReasoningBudget.splitting(128, marker)
        val tracker = ReasoningBudgetTracker(budget)

        // Reasoning ends immediately, so the whole 128 is the answer's to spend.
        assertEquals(null, tracker.onToken(marker))

        var stoppedAt: Int? = null
        for (i in 2..200) {
            if (tracker.onToken("x") != null) {
                stoppedAt = i
                break
            }
        }

        assertEquals("the decode must run to the full split total", 128, stoppedAt)
    }
}
