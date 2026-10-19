package io.github.tensaiapp.engine

import com.tensai.llamakt.ChatParseCallback
import com.tensai.llamakt.LlamaEngine
import com.tensai.llamakt.SamplingParams
import com.tensai.llamakt.TokenCallback
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TEN-79's first defect: a reply that ran out of budget was reported as [StopReason.Complete], so it
 * was persisted with no mark, read back as a finished answer, and replayed into every later prompt
 * as one.
 *
 * The comparison it turns on — sampled tokens against the cap — was always right. What was wrong was
 * the number fed into it: [LlamaKtEngineImpl] counted invocations of llama.kt's token callback,
 * which fires once per *flushable piece of text*, not once per token. llama.kt holds a token back
 * whenever it would split a UTF-8 sequence, so a single French accent costs the count one, and the
 * count then reads as "we stopped one short of the cap" on a decode that reached it exactly.
 *
 * Both halves are pinned here: [DecodeOutcome] for the derivation, and
 * [completionReportsHowManyTokensItSampled] for the only thing that can supply the true figure.
 * What no JVM test can reach is the wiring between them — `LlamaKtEngineImpl` cannot be constructed
 * off-device (`System.loadLibrary` in `LlamaEngine`'s companion), so "the impl passes what
 * `completion()` returned" is verified on the phone.
 */
class DecodeOutcomeTest {

    private val budget = ReasoningBudget(reasoningMaxTokens = 32, responseMaxTokens = 32, endMarker = "</think>")

    @Test
    fun aReplyThatEndedBeforeTheCapIsComplete() {
        val reason = DecodeOutcome.stopReason(
            budgetStopReason = null,
            tokensPredicted = 40,
            nPredict = 64,
            clipped = false,
            phaseStopReason = null,
        )

        assertEquals(StopReason.Complete, reason)
    }

    /**
     * The bug the user actually hits, written the way it happens.
     *
     * A 64-token budget, a reply in French: "Ça va" alone splits `Ç` into two byte-fallback tokens,
     * so llama.cpp sampled all 64 while the token callback fired 63 times. Feed the derivation the
     * callback count and it answers `Complete` — the reply reads as finished, and the next prompt
     * carries it as if it were. Feed it what llama.cpp actually sampled and the reply is marked for
     * what it is: cut off at its budget.
     */
    @Test
    fun anAccentedReplyThatSpentItsWholeBudgetIsNotComplete() {
        val nPredict = 64

        fun reasonFrom(count: Int) = DecodeOutcome.stopReason(
            budgetStopReason = null,
            tokensPredicted = count,
            nPredict = nPredict,
            clipped = false,
            phaseStopReason = null,
        )

        // What llama.cpp sampled: the budget, to the token.
        assertEquals(StopReason.TokenBudget, reasonFrom(64))
        // What the token callback counted for that same reply, one short — and a decode that
        // really did stop one short is a complete reply, which is exactly why the undercount was
        // invisible. The fix is upstream of this comparison, not in it.
        assertEquals(StopReason.Complete, reasonFrom(63))
    }

    @Test
    fun spendingTheWholeBudgetWhileReasoningKeepsTheTrackersPhase() {
        val reason = DecodeOutcome.stopReason(
            budgetStopReason = null,
            tokensPredicted = 64,
            nPredict = 64,
            clipped = false,
            phaseStopReason = StopReason.ReasoningBudgetExhausted,
        )

        assertEquals(StopReason.ReasoningBudgetExhausted, reason)
    }

    @Test
    fun spendingTheWholeBudgetWhileAnsweringKeepsTheTrackersPhase() {
        val reason = DecodeOutcome.stopReason(
            budgetStopReason = null,
            tokensPredicted = 64,
            nPredict = 64,
            clipped = false,
            phaseStopReason = StopReason.ResponseBudgetExhausted,
        )

        assertEquals(StopReason.ResponseBudgetExhausted, reason)
    }

    /** A tracker that cut the decode short saw the ending happen; nothing inferred here overrides it. */
    @Test
    fun aTrackedBudgetStopOutranksEverythingElse() {
        val reason = DecodeOutcome.stopReason(
            budgetStopReason = StopReason.ReasoningBudgetExhausted,
            tokensPredicted = 10,
            nPredict = 64,
            clipped = true,
            phaseStopReason = StopReason.ResponseBudgetExhausted,
        )

        assertEquals(StopReason.ReasoningBudgetExhausted, reason)
    }

    // ---------------------------------------------------------------------------------------
    // TEN-79's second defect: the clip that TEN-65 applies has to be reported as itself.
    // ---------------------------------------------------------------------------------------

    /**
     * The prompt left 64 tokens of room out of the 2048 the user asked for, and the reply used all
     * 64. Calling that [StopReason.TokenBudget] sends the user to raise a max-tokens setting that
     * had nothing to do with it — `available` does not depend on it, so the next reply is cut at
     * exactly the same place.
     */
    @Test
    fun aReplyCutOffByTheContextWindowIsNotReportedAsTheUsersOwnLimit() {
        val reason = DecodeOutcome.stopReason(
            budgetStopReason = null,
            tokensPredicted = 64,
            nPredict = 64,
            clipped = true,
            phaseStopReason = null,
        )

        assertEquals(StopReason.ContextBudgetClipped, reason)
    }

    /** Same for a thinking model: the phase split describes a budget this decode never got to spend. */
    @Test
    fun theContextWindowOutranksTheReasoningPhasesToo() {
        val reason = DecodeOutcome.stopReason(
            budgetStopReason = null,
            tokensPredicted = 64,
            nPredict = 64,
            clipped = true,
            phaseStopReason = StopReason.ResponseBudgetExhausted,
        )

        assertEquals(StopReason.ContextBudgetClipped, reason)
    }

    /** A clipped decode that ended on its own is still a complete reply — the clip never bit. */
    @Test
    fun aClippedDecodeThatEndedOnItsOwnIsStillComplete() {
        val reason = DecodeOutcome.stopReason(
            budgetStopReason = null,
            tokensPredicted = 12,
            nPredict = 64,
            clipped = true,
            phaseStopReason = null,
        )

        assertEquals(StopReason.Complete, reason)
    }

    // ---------------------------------------------------------------------------------------
    // TEN-91: a decode that died in the native engine must not read as one that finished.
    //
    // `tensai_jni.cpp` used to let a C++ exception unwind into the VM, which killed the process —
    // so there was no wrong answer to give, only no process. Now the JNI guard catches it and
    // answers with a sentinel, which puts a value where there wasn't one, and the whole question
    // becomes what this derivation does with it.
    // ---------------------------------------------------------------------------------------

    /**
     * The sentinel is negative precisely so it cannot pass for a count, because every non-negative
     * one it could have used means something true. `0` is the worst of them: it is what the early
     * exits in `nativeCompletion` legitimately return, and `0 < nPredict` for every cap there is,
     * so a crashed decode would land on [StopReason.Complete] and be persisted, read back, and
     * replayed into every later prompt as a reply the model chose to end.
     *
     * That is TEN-79's defect arriving by a new road, which is why it is pinned on the same
     * derivation rather than only where the sentinel is produced.
     */
    @Test
    fun aDecodeThatDiedInTheNativeEngineIsNotReportedAsComplete() {
        val reason = DecodeOutcome.stopReason(
            budgetStopReason = null,
            tokensPredicted = -1,
            nPredict = 64,
            clipped = false,
            phaseStopReason = null,
        )

        assertEquals(StopReason.Failed, reason)
    }

    /**
     * The one case that outranks everything else in this derivation still loses to it.
     *
     * [aTrackedBudgetStopOutranksEverythingElse] holds because a tracker that cut the decode short
     * watched the ending happen. Here nothing did: the decode was still running when the native
     * side threw, so whatever phase the tracker had reached describes a budget being spent, not a
     * reply ending. Reporting it would name a plausible ending for a decode that never had one.
     */
    @Test
    fun theNativeFailureSentinelOutranksEvenATrackedBudgetStop() {
        val reason = DecodeOutcome.stopReason(
            budgetStopReason = StopReason.ReasoningBudgetExhausted,
            tokensPredicted = -1,
            nPredict = 64,
            clipped = true,
            phaseStopReason = StopReason.ResponseBudgetExhausted,
        )

        assertEquals(StopReason.Failed, reason)
    }

    /**
     * `LlamaKtEngineImpl.decode` tests `< 0`, not `== -1`, and so does the derivation: the guard in
     * `tensai_jni.cpp` is one `jint` fallback today, and pinning the exact value here would make
     * this suite pass while a second guard returning some other negative sentinel walked straight
     * back into [StopReason.Complete].
     */
    @Test
    fun anyNegativeCountIsAFailureNotJustMinusOne() {
        for (count in listOf(-1, -2, -64, Int.MIN_VALUE)) {
            val reason = DecodeOutcome.stopReason(
                budgetStopReason = null,
                tokensPredicted = count,
                nPredict = 64,
                clipped = false,
                phaseStopReason = null,
            )

            assertEquals("tokensPredicted = $count", StopReason.Failed, reason)
        }
    }

    /** Sanity: the split really does add up to the sum the engine hands llama.cpp as `nPredict`. */
    @Test
    fun theReasoningSplitIsTheCapTheDerivationComparesAgainst() {
        assertEquals(64, budget.reasoningMaxTokens + budget.responseMaxTokens)
    }

    /**
     * The one figure that can be compared against `nPredict` has to cross the JNI boundary, and
     * before TEN-79 it did not: `completion()` returned `Unit`, which is why the app had nothing to
     * compare and fell back to counting callbacks.
     *
     * Reflection rather than a call: `LlamaEngine`'s companion loads the native library in its
     * static initializer, so nothing here may *instantiate* the class. Reading a method signature
     * does not trigger that initializer.
     */
    @Test
    fun completionReportsHowManyTokensItSampled() {
        val completion = LlamaEngine::class.java.getDeclaredMethod(
            "completion",
            String::class.java,
            SamplingParams::class.java,
            TokenCallback::class.java,
            ChatParseCallback::class.java,
        )

        assertEquals(Int::class.javaPrimitiveType, completion.returnType)
    }
}
