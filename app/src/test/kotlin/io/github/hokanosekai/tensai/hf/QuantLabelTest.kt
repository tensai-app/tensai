package io.github.hokanosekai.tensai.hf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantLabelTest {

    private fun label(f: String) = QuantLabel.parse(f)

    @Test
    fun `common labels`() {
        assertEquals("Q4_K_M", label("Qwen_Qwen3-1.7B-Q4_K_M.gguf"))
        assertEquals("Q8_0", label("model-Q8_0.gguf"))
        assertEquals("IQ4_XS", label("model-IQ4_XS.gguf"))
        assertEquals("F16", label("model-f16.gguf"))
        assertEquals("BF16", label("model-BF16.gguf"))
        assertEquals("Q6_K", label("model-Q6_K.gguf"))
        assertEquals("Q3_K_XL", label("Qwen3-UD-Q3_K_XL.gguf"))
        assertEquals("Q4_0", label("model.Q4_0.gguf"))
        assertEquals("Q8_0_XL", label("MiniCPM5-2B-Elffuss-Code-Q8_0-XL.gguf"))
        assertEquals("Q4_K_L", label("Llama-3.2-1B-Instruct-Q4_K_L.gguf"))
    }

    @Test
    fun `case and separators do not matter`() {
        assertEquals("Q4_K_M", label("model-q4_k_m.gguf"))
        assertEquals("Q4_K_M", label("model-q4-k-m.gguf"))
        assertEquals("Q4_K_M", label("model.Q4.K.M.gguf"))
        assertEquals("IQ3_M", label("MODEL-iq3-m.GGUF"))
    }

    @Test
    fun `folder is ignored and the last label wins`() {
        assertEquals("Q8_0", label("Q4_K_M/model-Q8_0.gguf"))
        assertEquals("Q4_K_M", label("Q8_0-distill-Q4_K_M.gguf"))
    }

    @Test
    fun `no label falls back to unknown, model size tokens are not labels`() {
        assertEquals(QuantLabel.UNKNOWN, label("Qwen3-1.7B.gguf"))
        assertEquals(QuantLabel.UNKNOWN, label("llama-3.2-8B-instruct.gguf"))
        assertEquals("Q4_K_M", label("Qwen3-4B-Q4_K_M.gguf"))
    }

    @Test
    fun `split and mmproj detection`() {
        assertTrue(QuantLabel.isSplit("m-Q8_0-00001-of-00004.gguf"))
        assertTrue(QuantLabel.isSplit("dir/M-Q8_0-00002-OF-00004.GGUF"))
        assertFalse(QuantLabel.isSplit("m-Q8_0.gguf"))
        assertTrue(QuantLabel.isMmproj("mmproj-model-f16.gguf"))
        assertTrue(QuantLabel.isMmproj("dir/Model-MMProj-Q8_0.gguf"))
        assertFalse(QuantLabel.isMmproj("model-Q8_0.gguf"))
        assertTrue(QuantLabel.isGguf("A.GGUF"))
        assertFalse(QuantLabel.isGguf("README.md"))
    }

    @Test
    fun `fp16, bare q4, ternary and ARM repack labels`() {
        assertEquals("F16", label("model-FP16.gguf"))
        assertEquals("F16", label("model-fp16.gguf"))
        assertEquals("Q4", label("model-q4.gguf"))
        assertEquals("Q8", label("model.Q8.gguf"))
        assertEquals("TQ1_0", label("model-TQ1_0.gguf"))
        assertEquals("TQ2_0", label("model-tq2-0.gguf"))
        assertEquals("Q4_0_4_4", label("model-Q4_0_4_4.gguf"))
        assertEquals("Q4_0_4_8", label("model-q4_0_4_8.gguf"))
        assertEquals("Q4_0_8_8", label("model-Q4_0_8_8.gguf"))
        assertEquals("Q4_0", label("model-Q4_0.gguf"))
        assertEquals("Q4_K_M", label("model-Q4_K_M.gguf"))
    }

    @Test
    fun theRemovedArmRepacksAreUnsupportedAndNothingElseIs() {
        listOf("Q4_0_4_4", "Q4_0_4_8", "Q4_0_8_8").forEach { assertTrue(QuantLabel.isUnsupported(it)) }
        listOf("Q4_0", "Q4_K_M", "Q8_0", "IQ4_XS", QuantLabel.UNKNOWN).forEach { assertFalse(QuantLabel.isUnsupported(it)) }
        assertTrue(QuantLabel.isUnsupportedFile("dir/model-q4_0_4_8.gguf"))
        assertFalse(QuantLabel.isUnsupportedFile("model-Q4_0.gguf"))
    }
}
