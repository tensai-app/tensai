package io.github.tensaiapp.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextUsageTest {
    @Test
    fun toneFollowsTheThresholds() {
        assertEquals(ContextTone.Primary, contextTone(0f))
        assertEquals(ContextTone.Primary, contextTone(0.69f))
        assertEquals(ContextTone.Warning, contextTone(0.7f))
        assertEquals(ContextTone.Warning, contextTone(0.9f))
        assertEquals(ContextTone.Error, contextTone(0.91f))
        assertEquals(ContextTone.Error, contextTone(1f))
    }

    @Test
    fun fractionIsClampedAndSurvivesAnUnsizedWindow() {
        assertEquals(0.5f, ContextUsageState.Loaded(50, 100).fraction(), 0.0001f)
        assertEquals(1f, ContextUsageState.Loaded(120, 100).fraction(), 0f)
        assertEquals(0f, ContextUsageState.Loaded(10, 0).fraction(), 0f)
    }

    @Test
    fun tokensAreFormattedInThousands() {
        assertEquals("0", formatTokens(0))
        assertEquals("512", formatTokens(512))
        assertEquals("999", formatTokens(999))
        assertEquals("1.0k", formatTokens(1000))
        assertEquals("11.2k", formatTokens(11234))
    }

    @Test
    fun contextLengthIsFormattedInKibiTokens() {
        assertEquals("32k", formatContextLength(32768))
        assertEquals("4k", formatContextLength(4096))
        assertEquals("8k", formatContextLength(8192))
        assertEquals("512", formatContextLength(512))
    }

    @Test
    fun percentIsRoundedAndBounded() {
        assertEquals(34, contextPercent(11234, 32768))
        assertEquals(0, contextPercent(0, 100))
        assertEquals(100, contextPercent(150, 100))
        assertEquals(0, contextPercent(10, 0))
    }
}
