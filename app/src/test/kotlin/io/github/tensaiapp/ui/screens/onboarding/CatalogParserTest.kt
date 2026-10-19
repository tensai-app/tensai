package io.github.tensaiapp.ui.screens.onboarding

import io.github.tensaiapp.hf.QuantLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The embedded `catalog.json`: the file the APK ships must parse, and say what the onboarding has always said. */
class CatalogParserTest {

    @Test
    fun theShippedCatalogParses() {
        val all = CatalogParser.loadEmbedded()

        assertEquals(6, all.size)
        assertEquals(all.size, all.map { it.hfPath.lowercase() }.toSet().size)
        assertTrue(all.all { it.supportsTools })
    }

    @Test
    fun everyEntryNamesAGgufFileOfItsOwnQuantInItsOwnRepo() {
        for (m in CatalogParser.loadEmbedded()) {
            assertTrue("${m.hfPath}: ${m.fileName}", m.fileName.endsWith(".gguf"))
            assertEquals(m.hfPath, m.quantLabel, QuantLabel.parse(m.fileName))
            assertFalse(m.hfPath, QuantLabel.isSplit(m.fileName) || QuantLabel.isMmproj(m.fileName))
        }
    }

    @Test
    fun onboardingKeepsItsThreeModelsInOrderWithTheSameFacts() {
        val models = OnboardingCatalog.models

        assertEquals(
            listOf("Llama 3.2 1B Instruct", "Qwen3 1.7B Instruct", "Qwen3 4B Instruct"),
            models.map { it.name },
        )
        val qwen = models[1]
        assertEquals("bartowski/Qwen_Qwen3-1.7B-GGUF", qwen.hfPath)
        assertEquals(1_282_439_584L, qwen.facts.sizeBytes)
        assertEquals(1_720_000_000L, qwen.facts.paramCount)
        assertEquals("qwen3", qwen.facts.architecture)
        assertEquals(40_960L, qwen.facts.contextLength)
        assertEquals(2048L, qwen.facts.embeddingLength)
        assertEquals(28L, qwen.facts.blockCount)
        assertEquals(2L * 28 * 8 * 128, qwen.facts.kvFullElementsPerToken)
        assertEquals(0L, qwen.facts.kvSwaElementsPerToken)
        assertEquals(0L, qwen.facts.slidingWindow)
    }

    @Test
    fun allHoldsEveryEntryAndModelsOnlyTheOnboardingOnes() {
        assertEquals(6, OnboardingCatalog.all.size)
        assertEquals(3, OnboardingCatalog.models.size)
        assertTrue(OnboardingCatalog.all.containsAll(OnboardingCatalog.models))
        assertTrue(OnboardingCatalog.models.all { it.onboarding })
    }

    @Test
    fun unknownKeysAreIgnoredSoTheFileCanGrowFields() {
        val models = CatalogParser.parse(entries(entry(extra = """, "future": {"a": 1}""")))

        assertEquals(1, models.size)
    }

    @Test
    fun aBadFileIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { CatalogParser.parse("""{"models": []}""") }
        assertThrows(IllegalArgumentException::class.java) { CatalogParser.parse(entries(entry(repo = "no-slash"))) }
        assertThrows(IllegalArgumentException::class.java) { CatalogParser.parse(entries(entry(file = "m.bin"))) }
        assertThrows(IllegalArgumentException::class.java) { CatalogParser.parse(entries(entry(size = 0))) }
        assertThrows(IllegalArgumentException::class.java) { CatalogParser.parse(entries(entry(), entry())) }
        assertThrows(Exception::class.java) { CatalogParser.parse("not json") }
    }

    private fun entries(vararg items: String) = """{"version": 1, "models": [${items.joinToString(",")}]}"""

    private fun entry(
        repo: String = "a/b-GGUF",
        file: String = "b-Q4_K_M.gguf",
        size: Long = 1000,
        extra: String = "",
    ) = """{"repo": "$repo", "file": "$file", "name": "B", "sizeBytes": $size, "params": 1000000000,
        "architecture": "llama", "contextLength": 4096, "embeddingLength": 1024, "blockCount": 8,
        "kvHeads": 4, "headDim": 64$extra}"""
}
