package io.github.hokanosekai.tensai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetTest {

    @Test
    fun availableSubtractsPromptTokensAndTheSafetyMargin() {
        val available = ContextBudget.available(nCtxEffective = 4096, promptTokens = 3200)

        assertEquals(4096 - 3200 - ContextBudget.SafetyMarginTokens, available)
    }

    @Test
    fun availableNeverGoesNegativeWhenThePromptAloneExceedsNCtx() {
        val available = ContextBudget.available(nCtxEffective = 4096, promptTokens = 5000)

        assertEquals(0, available)
    }

    @Test
    fun hasRoomIsFalseWhenNothingIsLeft() {
        assertFalse(ContextBudget.hasRoom(ContextBudget.available(nCtxEffective = 4096, promptTokens = 4096)))
    }

    @Test
    fun hasRoomIsFalseWhenTheRemainderIsTooSmallToBeUseful() {
        // "dérisoire", not just zero — a couple of tokens of room isn't a reply.
        val available = ContextBudget.available(
            nCtxEffective = 4096,
            promptTokens = 4096 - ContextBudget.SafetyMarginTokens - (ContextBudget.MinResponseTokens - 1),
        )

        assertTrue(available in 1 until ContextBudget.MinResponseTokens)
        assertFalse(ContextBudget.hasRoom(available))
    }

    @Test
    fun hasRoomIsTrueOnceTheMinimumIsReached() {
        val available = ContextBudget.available(
            nCtxEffective = 4096,
            promptTokens = 4096 - ContextBudget.SafetyMarginTokens - ContextBudget.MinResponseTokens,
        )

        assertEquals(ContextBudget.MinResponseTokens, available)
        assertTrue(ContextBudget.hasRoom(available))
    }

    @Test
    fun hasRoomIsTrueWithPlentyOfSpaceLeft() {
        assertTrue(ContextBudget.hasRoom(ContextBudget.available(nCtxEffective = 4096, promptTokens = 500)))
    }
}
