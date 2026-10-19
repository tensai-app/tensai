package io.github.tensaiapp.data.models

import io.github.tensaiapp.hf.QuantLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelEntityTest {

    private fun model(sourceQuant: String?) = ModelEntity(
        id = "m",
        displayName = "Llama 3.2 1B Instruct",
        fileName = "Llama-3.2-1B-Instruct-Q4_K_L.gguf",
        filePath = "/models/m.gguf",
        sizeBytes = 1L,
        architecture = "llama",
        quantLabel = "Q4_K_M",
        paramCount = 1L,
        contextLength = 1L,
        embeddingLength = 1L,
        blockCount = 1L,
        supportsThinking = false,
        importedAt = 0L,
        sourceQuant = sourceQuant,
    )

    @Test
    fun `an import shows the GGUF label`() {
        assertEquals("Q4_K_M", model(null).displayQuant)
    }

    @Test
    fun `a download shows the quant the user picked`() {
        assertEquals("Q4_K_L", model("Q4_K_L").displayQuant)
        assertEquals("UD-Q4_K_XL", model("UD-Q4_K_XL").displayQuant)
    }

    @Test
    fun `a blank source quant falls back to the GGUF label`() {
        assertEquals("Q4_K_M", model("").displayQuant)
        assertEquals("Q4_K_M", model("  ").displayQuant)
    }

    @Test
    fun `an unknown source quant falls back to the GGUF label`() {
        assertEquals("Q4_K_M", model(QuantLabel.UNKNOWN).displayQuant)
    }
}
