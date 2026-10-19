package io.github.tensaiapp.hf

import org.junit.Assert.assertEquals
import org.junit.Test

/** A 27B repo that also ships a 13 MB unlabelled .gguf (an auxiliary file, seen on Qwen3.8-27B). */
class HfPlausibleQuantsTest {

    private val mb = 1024L * 1024
    private fun f(name: String, sizeMb: Long) = HfQuantFile(name, QuantLabel.parse(name), sizeMb * mb, null)

    private val params27b = 27_000_000_000L

    @Test
    fun `a tiny unlabelled gguf is not offered next to real quants`() {
        val files = listOf(f("aux.gguf", 13), f("m-Q4_K_M.gguf", 16_000), f("m-Q8_0.gguf", 28_000))
        assertEquals(listOf("Q4_K_M", "Q8_0"), HfParser.plausibleQuants(files, params27b).map { it.quant })
    }

    @Test
    fun `a file under 1_5 bits per weight is dropped even when labelled`() {
        val files = listOf(f("m-Q4_K_M.gguf", 100), f("m-Q4_0.gguf", 15_000))
        assertEquals(listOf("Q4_0"), HfParser.plausibleQuants(files, params27b).map { it.quant })
    }

    @Test
    fun `a repo with only a tiny file has no quant at all`() {
        assertEquals(emptyList<HfQuantFile>(), HfParser.plausibleQuants(listOf(f("aux.gguf", 13)), params27b))
    }

    @Test
    fun `without a param count only the label rule applies`() {
        val files = listOf(f("aux.gguf", 13), f("m-Q4_K_M.gguf", 1_000))
        assertEquals(listOf("Q4_K_M"), HfParser.plausibleQuants(files, 0).map { it.quant })
    }

    @Test
    fun `removed ARM repack quants are never offered`() {
        val files = listOf(f("m-Q4_0_4_4.gguf", 4_000), f("m-Q4_0_8_8.gguf", 4_000), f("m-Q4_0.gguf", 4_000))
        assertEquals(listOf("Q4_0"), HfParser.plausibleQuants(files, 7_000_000_000L).map { it.quant })
    }

    @Test
    fun `a repo with only removed quants has no quant at all`() {
        val files = listOf(f("m-Q4_0_4_8.gguf", 4_000), f("m-Q4_0_8_8.gguf", 4_000))
        assertEquals(emptyList<HfQuantFile>(), HfParser.plausibleQuants(files, 7_000_000_000L))
    }

    @Test
    fun `an unlabelled file alone is kept`() {
        assertEquals(1, HfParser.plausibleQuants(listOf(f("model.gguf", 4_000)), 7_000_000_000L).size)
    }

    @Test
    fun `the fixture repo keeps working`() {
        val d = HfParser.parseDetail(fixture("detail_qwen3_1_7b.json"))
        assertEquals(true, d.quants.isNotEmpty())
    }
}
