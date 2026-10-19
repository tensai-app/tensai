package io.github.tensaiapp.data.models

/**
 * A chat format the app can force a model onto (TEN-159), by the name the native side knows it
 * under (`tensai_chat_templates.hpp`; `chatml` is llama.cpp's own). Persisted by [id] in
 * [ModelEntity.chatTemplateOverride]: `null` there is "Auto", not a constant of this enum.
 */
enum class ChatTemplateChoice(val id: String) {
    ChatML("chatml"),
    Mistral("mistral"),
    Llama3("llama3"),
    Gemma("gemma"),
    Phi3("phi3");

    companion object {
        /** The choice persisted under [id], `null` for `null` or an id this build doesn't know. */
        fun parse(id: String?): ChatTemplateChoice? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Which [ChatTemplateChoice] a model without a chat template of its own most likely wants (TEN-159).
 * llama.cpp falls back to ChatML for such a file, which a Mistral or Llama model does not know:
 * it answers with the ChatML tokens as text. Only used when [ModelEntity.hasChatTemplate] is false
 * and the user picked nothing: a template in the file always wins.
 */
object ChatTemplateGuess {
    // Fine-tunes of a Mistral / Llama base that were trained on ChatML (or close to it, zephyr).
    private val CHATML = Regex("hermes|dolphin|nous|chatml|zephyr")
    private val MISTRAL = Regex("mistral|mathstral|mixtral|codestral")
    // Left boundary: codellama-34b, llama-30b and llama-33b are not Llama 3.
    private val LLAMA3 = Regex("(?<![a-z])llama-?3(?!\\d)")
    private val PHI3 = Regex("phi-?3")

    /**
     * Looks at the GGUF `general.architecture` and then every name it can get (`general.name`, the
     * repo, the file), lowercased. The order is the order of the rules: ChatML fine-tunes (hermes,
     * openhermes, dolphin, nous, chatml, zephyr) come first, as OpenHermes-2.5-Mistral-7B is ChatML
     * whatever its base; then a `Mistral-7B` fine-tune of a Llama 3 base is still a Mistral.
     * Anything unrecognised, Qwen included, is ChatML.
     */
    fun of(architecture: String, vararg names: String?): ChatTemplateChoice {
        val haystack = (listOf<String?>(architecture) + names).filterNotNull().joinToString(" ").lowercase()
        return when {
            CHATML.containsMatchIn(haystack) -> ChatTemplateChoice.ChatML
            MISTRAL.containsMatchIn(haystack) -> ChatTemplateChoice.Mistral
            LLAMA3.containsMatchIn(haystack) -> ChatTemplateChoice.Llama3
            "gemma" in haystack -> ChatTemplateChoice.Gemma
            PHI3.containsMatchIn(haystack) -> ChatTemplateChoice.Phi3
            else -> ChatTemplateChoice.ChatML
        }
    }
}
