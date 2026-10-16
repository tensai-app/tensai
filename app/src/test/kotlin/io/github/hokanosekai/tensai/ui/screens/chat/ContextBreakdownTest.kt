package io.github.hokanosekai.tensai.ui.screens.chat

import io.github.hokanosekai.tensai.runtime.EffectiveBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBreakdownTest {

    private fun ContextBreakdown.tokens(part: ContextPart) = rows.firstOrNull { it.part == part }?.tokens

    @Test
    fun theRowsAddUpToTheWindowAndOverheadIsWhatTheCountsDoNotExplain() {
        val b = contextBreakdown(
            length = 1000,
            cacheUsed = 400,
            counts = ContextCounts(system = 50, user = 100, assistant = 200),
            reserve = 16,
        )
        assertEquals(50, b.tokens(ContextPart.System))
        assertEquals(100, b.tokens(ContextPart.User))
        assertEquals(200, b.tokens(ContextPart.Assistant))
        assertEquals(50, b.tokens(ContextPart.Overhead))
        assertEquals(16, b.tokens(ContextPart.Reserved))
        assertEquals(584, b.tokens(ContextPart.Free))
        assertEquals(1000, b.rows.sumOf { it.tokens })
        assertEquals(400, b.used)
        assertEquals(40, b.usedPercent)
        assertFalse(b.estimate)
        assertFalse(b.full)
    }

    @Test
    fun zeroPartsHaveNoRow() {
        val b = contextBreakdown(length = 100, cacheUsed = 10, counts = ContextCounts(user = 10), reserve = 16)
        assertEquals(listOf(ContextPart.User, ContextPart.Reserved, ContextPart.Free), b.rows.map { it.part })
        assertNull(b.tokens(ContextPart.Thinking))
        assertNull(b.tokens(ContextPart.Overhead))
    }

    @Test
    fun theOrderIsTheSheetsOwn() {
        val b = contextBreakdown(100, 50, ContextCounts(system = 1, user = 1, assistant = 1, thinking = 1), 16)
        assertEquals(
            listOf(
                ContextPart.System, ContextPart.User, ContextPart.Assistant, ContextPart.Thinking,
                ContextPart.Overhead, ContextPart.Reserved, ContextPart.Free,
            ),
            b.rows.map { it.part },
        )
    }

    @Test
    fun aTinyRowIsZeroPercent() {
        val b = contextBreakdown(length = 32768, cacheUsed = 100, counts = ContextCounts(user = 3), reserve = 16)
        assertEquals(0, b.rows.first { it.part == ContextPart.User }.percent)
        assertEquals(0, b.rows.first { it.part == ContextPart.Reserved }.percent)
        assertEquals(0, b.rows.first { it.part == ContextPart.Overhead }.percent)
    }

    @Test
    fun percentsAreRounded() {
        val b = contextBreakdown(length = 1000, cacheUsed = 335, counts = ContextCounts(user = 335), reserve = 16)
        assertEquals(34, b.rows.first { it.part == ContextPart.User }.percent)
        assertEquals(34, b.usedPercent)
    }

    @Test
    fun fullIsNoRoomLeftOnceTheReserveIsKept() {
        assertFalse(contextBreakdown(100, 83, ContextCounts(user = 83), 16).full)
        val atTheEdge = contextBreakdown(100, 84, ContextCounts(user = 84), 16)
        assertTrue(atTheEdge.full)
        assertNull(atTheEdge.tokens(ContextPart.Free))
        assertEquals(16, atTheEdge.tokens(ContextPart.Reserved))
        // The percent stays used over length, the reserve is not in it.
        assertEquals(84, atTheEdge.usedPercent)
    }

    @Test
    fun anOverfullCacheClipsTheReserveAndNeverGoesNegative() {
        val b = contextBreakdown(length = 100, cacheUsed = 95, counts = ContextCounts(user = 90), reserve = 16)
        assertTrue(b.full)
        assertEquals(5, b.tokens(ContextPart.Reserved))
        assertNull(b.tokens(ContextPart.Free))
        assertEquals(100, b.rows.sumOf { it.tokens })

        val over = contextBreakdown(length = 100, cacheUsed = 130, counts = ContextCounts(user = 90), reserve = 16)
        assertTrue(over.full)
        assertNull(over.tokens(ContextPart.Reserved))
        assertEquals(100, over.usedPercent)
    }

    @Test
    fun overheadIsNeverNegative() {
        val b = contextBreakdown(100, cacheUsed = 5, counts = ContextCounts(user = 10), reserve = 16)
        // A cache holding less than the counts is not this conversation's yet: an estimate.
        assertTrue(b.estimate)
        assertNull(b.tokens(ContextPart.Overhead))
        assertEquals(10, b.used)
    }

    @Test
    fun anEstimateUsesTheStoredCountsAndTheLastKnownOverhead() {
        val b = contextBreakdown(
            length = 100,
            cacheUsed = null,
            counts = ContextCounts(user = 10, assistant = 20),
            reserve = 16,
            lastOverhead = 6,
        )
        assertTrue(b.estimate)
        assertEquals(36, b.used)
        assertEquals(6, b.tokens(ContextPart.Overhead))
        assertEquals(48, b.tokens(ContextPart.Free))

        val noHistory = contextBreakdown(100, null, ContextCounts(user = 10), 16)
        assertNull(noHistory.tokens(ContextPart.Overhead))
        assertEquals(10, noHistory.used)
    }

    @Test
    fun anEmptyConversationWithAnEmptyCacheIsNotAnEstimate() {
        val b = contextBreakdown(length = 100, cacheUsed = 0, counts = ContextCounts(), reserve = 16)
        assertFalse(b.estimate)
        assertEquals(listOf(ContextPart.Reserved, ContextPart.Free), b.rows.map { it.part })
    }

    @Test
    fun anUnknownWindowHasNoReserveNoFreeSpaceAndIsNeverFull() {
        val b = contextBreakdown(length = 0, cacheUsed = null, counts = ContextCounts(user = 10, assistant = 10), reserve = 16)
        assertEquals(listOf(ContextPart.User, ContextPart.Assistant), b.rows.map { it.part })
        assertEquals(50, b.rows.first().percent)
        assertFalse(b.full)
        assertEquals(0, b.usedPercent)
    }

    @Test
    fun theBackendLabelMapsToABackend() {
        assertEquals(EffectiveBackend.Vulkan, effectiveBackendOf("Vulkan"))
        assertEquals(EffectiveBackend.OpenCl, effectiveBackendOf("OpenCL"))
        assertEquals(EffectiveBackend.Cpu, effectiveBackendOf("CPU"))
        assertNull(effectiveBackendOf("unknown"))
    }

    @Test
    fun toolDefinitionsAndResultsAreTheirOwnRowsAndNotOverhead() {
        val b = contextBreakdown(
            length = 1000,
            cacheUsed = 300,
            counts = ContextCounts(user = 100, assistant = 50, toolDefinitions = 60, toolResults = 40),
            reserve = 16,
        )

        assertEquals(60, b.tokens(ContextPart.ToolDefinitions))
        assertEquals(40, b.tokens(ContextPart.ToolResults))
        assertEquals(50, b.tokens(ContextPart.Overhead))
        assertEquals(
            listOf(
                ContextPart.User, ContextPart.Assistant, ContextPart.ToolDefinitions, ContextPart.ToolResults,
                ContextPart.Overhead, ContextPart.Reserved, ContextPart.Free,
            ),
            b.rows.map { it.part },
        )
        assertEquals(1000, b.rows.sumOf { it.tokens })
    }

    @Test
    fun aCacheSmallerThanTheToolShareMakesItAnEstimate() {
        val b = contextBreakdown(length = 1000, cacheUsed = 100, counts = ContextCounts(user = 50, toolResults = 80), reserve = 16)

        assertTrue(b.estimate)
        assertEquals(130, b.used)
    }

    @Test
    fun theFallbackMessageFollowsTheLanguage() {
        assertEquals("I couldn't finish with the tools available.", toolFallbackMessage("en"))
        assertEquals("I couldn't finish with the tools available.", toolFallbackMessage("de"))
        assertEquals("Je n'ai pas pu terminer avec les outils disponibles.", toolFallbackMessage("fr"))
    }
}
