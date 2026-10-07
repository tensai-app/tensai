package io.github.hokanosekai.tensai.ui.screens.chat

import io.github.hokanosekai.tensai.engine.SamplingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetModelTest {

    @Test
    fun theAppDefaultsAreTheBalancedPresetAt2048Tokens() {
        val d = SamplingConfig()
        assertEquals(ResponseStyle.Balanced, detectStyle(d))
        assertEquals(2048, d.maxTokens)
        assertTrue(2048 in MaxTokenChoices)
    }

    @Test
    fun eachPresetIsDetectedFromItsOwnValues() {
        for (style in ResponseStyle.entries) {
            assertEquals(style, detectStyle(SamplingConfig().withStyle(style)))
        }
    }

    @Test
    fun oneOffValueIsCustom() {
        val c = SamplingConfig().withStyle(ResponseStyle.Precise).copy(topK = 21)
        assertNull(detectStyle(c))
        assertNull(detectStyle(SamplingConfig(temperature = 0.85f)))
    }

    @Test
    fun floatNoiseDoesNotBreakDetection() {
        assertEquals(ResponseStyle.Balanced, detectStyle(SamplingConfig(temperature = 0.8000001f, topP = 0.9499999f)))
    }

    @Test
    fun pillClampsToTheRangeOnCommit() {
        assertEquals(2f, commitPillText("5", 0f, 2f, 2)!!, 0f)
        assertEquals(0f, commitPillText("-1", 0f, 2f, 2)!!, 0f)
        assertEquals(1f, commitPillText("0", 1f, 200f, 0)!!, 0f)
        assertEquals(200f, commitPillText("999", 1f, 200f, 0)!!, 0f)
    }

    @Test
    fun pillRoundsAndAcceptsACommaAndRejectsGarbage() {
        assertEquals(0.73f, commitPillText("0,734", 0f, 1f, 2)!!, 0f)
        assertEquals(41f, commitPillText("40.6", 1f, 200f, 0)!!, 0f)
        assertNull(commitPillText("", 0f, 1f, 2))
        assertNull(commitPillText("abc", 0f, 1f, 2))
        assertNull(commitPillText("NaN", 0f, 1f, 2))
    }

    @Test
    fun sliderSnapsToItsStep() {
        assertEquals(0.85f, snapToStep(0.8312f, 0f, 2f, 0.05f), 0f)
        assertEquals(2f, snapToStep(9f, 0f, 2f, 0.05f), 0f)
        assertEquals(37f, snapToStep(36.6f, 1f, 200f, 1f), 0f)
    }

    @Test
    fun defaultsMeansEverythingInTheSheet() {
        val d = SamplingConfig()
        assertTrue(isSheetDefault(d, true, true))
        assertFalse(isSheetDefault(d.copy(maxTokens = 512), true, true))
        assertFalse(isSheetDefault(d.copy(stopSequences = listOf("x")), true, true))
        assertFalse(isSheetDefault(d.withStyle(ResponseStyle.Creative), true, true))
        assertFalse(isSheetDefault(d, false, true))
    }

    @Test
    fun thinkingIsIgnoredWhenTheModelCannotThink() {
        assertTrue(isSheetDefault(SamplingConfig(), false, false))
    }

    @Test
    fun maxTokenLabels() {
        assertEquals(listOf("512", "1k", "2k", "4k", "8k"), MaxTokenChoices.map(::maxTokensLabel))
        assertEquals("3000", maxTokensLabel(3000))
    }

    @Test
    fun creativePresetValues() {
        val c = ResponseStyle.Creative
        assertEquals(1.0f, c.temperature, 0f)
        assertEquals(0.95f, c.topP, 0f)
        assertEquals(0.05f, c.minP, 0f)
        assertEquals(60, c.topK)
        assertEquals(ResponseStyle.Creative, detectStyle(SamplingConfig(temperature = 1.0f, topP = 0.95f, minP = 0.05f, topK = 60)))
        assertNull("the old Creative values are now a custom mix", detectStyle(SamplingConfig(temperature = 1.2f, topP = 0.98f, minP = 0.02f, topK = 80)))
    }

    @Test
    fun chipsAtOrAboveTheContextAreNotOffered() {
        assertEquals(listOf(512, 1024, 2048), MaxTokenChoices.filter { maxTokenFits(it, 4096) })
        assertTrue(maxTokenFits(2048, 2049))
        assertFalse(maxTokenFits(2048, 2048))
    }

    @Test
    fun noLoadedModelDisablesNothing() {
        assertTrue(MaxTokenChoices.all { maxTokenFits(it, null) })
    }

    @Test
    fun summaryListsTemperatureTopPMinPAndTopK() {
        assertEquals("t 0.80 · p 0.95 · m 0.05 · k 40", samplingSummary(SamplingConfig()))
    }
}
