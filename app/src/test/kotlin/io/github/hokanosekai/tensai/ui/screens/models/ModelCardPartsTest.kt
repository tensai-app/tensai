package io.github.hokanosekai.tensai.ui.screens.models

import io.github.hokanosekai.tensai.runtime.EffectiveBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TEN-157: the pure parts of the shared model card (subtitle, facts line, chips, backend row). */
class ModelCardPartsTest {

    private val SIZE = 2_040_109_466L

    @Test
    fun ownerIsTheRepoAuthor() {
        assertEquals("unsloth", repoOwner("unsloth/Qwen3-GGUF"))
        assertNull(repoOwner(null))
        assertNull(repoOwner("no-slash"))
        assertNull(repoOwner("/name"))
    }

    @Test
    fun subtitleJoinsWhatIsThere() {
        assertEquals("unsloth · 3.2B params", cardSubtitle("unsloth", "3.2B params"))
        assertEquals("3.2B params", cardSubtitle(null, "3.2B params"))
        assertEquals("unsloth", cardSubtitle("unsloth", null))
        assertNull(cardSubtitle(null, null))
        assertNull(cardSubtitle(" ", ""))
    }

    @Test
    fun factsLineHasQuantSizeAndBackend() {
        assertEquals("Q4_K_M · 1.9 GB · CPU", cardFacts("Q4_K_M", SIZE, EffectiveBackend.Cpu))
        assertEquals("Q4_K_M · 1.9 GB · Vulkan", cardFacts("Q4_K_M", SIZE, EffectiveBackend.Vulkan))
        assertEquals("Q4_K_M · 1.9 GB · OpenCL", cardFacts("Q4_K_M", SIZE, EffectiveBackend.OpenCl))
    }

    @Test
    fun factsLineOmitsAMissingBackendOrQuant() {
        assertEquals("Q4_K_M · 1.9 GB", cardFacts("Q4_K_M", SIZE, null))
        assertEquals("1.9 GB · CPU", cardFacts("", SIZE, EffectiveBackend.Cpu))
        assertNull(cardFacts(null, 0L, null))
    }

    @Test
    fun factsLineOmitsAnEmptySize() {
        assertEquals("Q4_K_M · CPU", cardFacts("Q4_K_M", 0L, EffectiveBackend.Cpu))
    }

    @Test
    fun chipsKeepTheKitOrder() {
        assertEquals(
            listOf(CardChip.Loaded, CardChip.OnDevice, CardChip.Tools, CardChip.Thinking, CardChip.Gated, CardChip.Imported),
            cardChips(loaded = true, onDevice = true, tools = true, thinking = true, gated = true, imported = true),
        )
        assertEquals(listOf(CardChip.Tools, CardChip.Gated), cardChips(gated = true, tools = true))
    }

    @Test
    fun noChipMeansAnEmptyList() {
        assertEquals(emptyList<CardChip>(), cardChips())
    }

    @Test
    fun backendRowShowsTheHardware() {
        assertEquals("Vulkan · Mali-G68", backendHardware(EffectiveBackend.Vulkan, "Mali-G68", null, "8 cores"))
        assertEquals("OpenCL · Adreno 740", backendHardware(EffectiveBackend.OpenCl, null, "Adreno 740", "8 cores"))
        assertEquals("CPU · 8 cores", backendHardware(EffectiveBackend.Cpu, "Mali-G68", null, "8 cores"))
    }

    @Test
    fun backendRowFallsBackToTheNameAlone() {
        assertEquals("Vulkan", backendHardware(EffectiveBackend.Vulkan, null, null, "8 cores"))
        assertEquals("Vulkan", backendHardware(EffectiveBackend.Vulkan, " ", null, null))
        assertEquals("CPU", backendHardware(EffectiveBackend.Cpu, null, null, null))
    }
}
