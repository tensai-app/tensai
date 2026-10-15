package io.github.hokanosekai.tensai.data.models

import io.github.hokanosekai.tensai.data.models.ChatTemplateChoice.ChatML
import io.github.hokanosekai.tensai.data.models.ChatTemplateChoice.Gemma
import io.github.hokanosekai.tensai.data.models.ChatTemplateChoice.Llama3
import io.github.hokanosekai.tensai.data.models.ChatTemplateChoice.Mistral
import io.github.hokanosekai.tensai.data.models.ChatTemplateChoice.Phi3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TEN-159: which chat format a model without a template gets, and what a load ends up forcing. */
class ChatTemplateGuessTest {

    @Test
    fun `the mistral family is Mistral`() {
        assertEquals(Mistral, ChatTemplateGuess.of("llama", "mathstral-7B-v0.1"))
        assertEquals(Mistral, ChatTemplateGuess.of("llama", "Mistral-7B-Instruct-v0.2"))
        assertEquals(Mistral, ChatTemplateGuess.of("llama", "x", "Mixtral-8x7B", null))
        assertEquals(Mistral, ChatTemplateGuess.of("llama", "x", "MaziyarPanahi/Codestral-22B-v0.1-GGUF"))
        assertEquals(Mistral, ChatTemplateGuess.of("mistral3", "x"))
    }

    @Test
    fun `llama 3 is Llama 3 however it is spelled`() {
        assertEquals(Llama3, ChatTemplateGuess.of("llama", "Meta-Llama-3-8B-Instruct"))
        assertEquals(Llama3, ChatTemplateGuess.of("llama", "x", "Llama3.1-8B.gguf"))
        assertEquals(Llama3, ChatTemplateGuess.of("llama", "llama-3.2-1b"))
    }

    @Test
    fun `ChatML fine-tunes of a Mistral base are ChatML`() {
        assertEquals(ChatML, ChatTemplateGuess.of("llama", "OpenHermes-2.5-Mistral-7B"))
        assertEquals(ChatML, ChatTemplateGuess.of("llama", "Hermes-2-Pro-Mistral-7B"))
        assertEquals(ChatML, ChatTemplateGuess.of("llama", "dolphin-2.6-mistral-7b"))
        assertEquals(ChatML, ChatTemplateGuess.of("llama", "x", "NousResearch/Nous-Hermes-2-Mixtral"))
        assertEquals(ChatML, ChatTemplateGuess.of("llama", "zephyr-7b-beta"))
    }

    @Test
    fun `llama 3 needs a left boundary and no digit after`() {
        assertEquals(ChatML, ChatTemplateGuess.of("llama", "codellama-34b"))
        assertEquals(ChatML, ChatTemplateGuess.of("llama", "llama-30b"))
        assertEquals(ChatML, ChatTemplateGuess.of("llama", "llama-33b"))
        assertEquals(Llama3, ChatTemplateGuess.of("llama", "Llama-3-8B"))
    }

    @Test
    fun `gemma and phi 3`() {
        assertEquals(Gemma, ChatTemplateGuess.of("gemma2", "x"))
        assertEquals(Gemma, ChatTemplateGuess.of("llama", "Gemma-2-9B"))
        assertEquals(Phi3, ChatTemplateGuess.of("phi3", "x"))
        assertEquals(Phi3, ChatTemplateGuess.of("llama", "Phi-3-mini-4k"))
    }

    @Test
    fun `qwen and anything unknown is ChatML`() {
        assertEquals(ChatML, ChatTemplateGuess.of("qwen2", "Qwen2.5-7B"))
        assertEquals(ChatML, ChatTemplateGuess.of("llama", "Llama-2-7b"))
        assertEquals(ChatML, ChatTemplateGuess.of("", "", null))
    }

    @Test
    fun `the first rule wins`() {
        assertEquals(Mistral, ChatTemplateGuess.of("llama", "Mistral-7B on Llama-3 base"))
        assertEquals(Llama3, ChatTemplateGuess.of("llama", "Llama-3 distilled from Gemma"))
    }

    @Test
    fun `a choice is persisted by id`() {
        assertEquals(listOf("chatml", "mistral", "llama3", "gemma", "phi3"), ChatTemplateChoice.entries.map { it.id })
        assertEquals(Llama3, ChatTemplateChoice.parse("llama3"))
        assertNull(ChatTemplateChoice.parse(null))
        assertNull(ChatTemplateChoice.parse("auto"))
    }

    private fun model(
        hasTemplate: Boolean,
        override: String? = null,
        name: String = "mathstral-7B-v0.1",
    ) = ModelEntity(
        id = "m",
        displayName = name,
        fileName = "$name.IQ1_S.gguf",
        filePath = "/models/m.gguf",
        sizeBytes = 1L,
        architecture = "llama",
        quantLabel = "IQ1_S",
        paramCount = 1L,
        contextLength = 1L,
        embeddingLength = 1L,
        blockCount = 1L,
        supportsThinking = false,
        importedAt = 0L,
        hasChatTemplate = hasTemplate,
        chatTemplateOverride = override,
    )

    @Test
    fun `a template in the file is kept on Auto`() {
        assertNull(model(hasTemplate = true).chatTemplateToLoad)
    }

    @Test
    fun `a file without a template gets the guess on Auto`() {
        assertEquals(Mistral, model(hasTemplate = false).chatTemplateToLoad)
    }

    @Test
    fun `the user's choice wins over the file and the guess`() {
        assertEquals(Gemma, model(hasTemplate = true, override = "gemma").chatTemplateToLoad)
        assertEquals(Gemma, model(hasTemplate = false, override = "gemma").chatTemplateToLoad)
    }

    @Test
    fun `an unknown stored id reads as Auto`() {
        assertNull(model(hasTemplate = true, override = "from-the-future").chatTemplateToLoad)
        assertEquals(Mistral, model(hasTemplate = false, override = "from-the-future").chatTemplateToLoad)
    }
}
