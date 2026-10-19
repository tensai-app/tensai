package io.github.tensaiapp.engine

/**
 * The prompt a [LoadedModel.warmUp] prefills (TEN-88): the next turn's prompt, up to the point
 * where the user's own words start.
 *
 * What the KV cache holds after a reply is what the model *generated*, reasoning included. What
 * the next turn sends is the history re-rendered through the chat template, which drops reasoning
 * from past assistant turns (and, with thinking off, does not replay the empty think block the
 * generation prompt carried). The two agree up to the start of the last reply and no further, so
 * llama.cpp's prefix reuse stops there and re-decodes the whole reply before the first token of
 * the next turn. This builds the text to put in the cache instead, while nobody is waiting for it.
 *
 * It cannot be had by formatting the history as it stands. A template renders the *last* assistant
 * turn differently depending on whether a user message follows it (Qwen3 keeps reasoning for
 * assistant turns after the last user query), and the next real prompt will have one. So the
 * history is formatted with a placeholder user message appended, which puts the last reply in the
 * form it will have, and the placeholder is then cut away without knowing anything about the
 * template: two renders with different placeholder contents agree up to exactly where the content
 * starts. That prefix includes the user turn's header (`<|im_start|>user\n` and the like), which
 * the real prompt contains too, and nothing of the user's text or of the generation prompt behind
 * it.
 *
 * A pure function of [format] so the arithmetic can be tested with a fake template, no engine.
 */
internal object WarmUpPrompt {

    /**
     * Two contents that differ from their very first character, so the common prefix of the two
     * renders ends where the content begins. Single characters on purpose: nothing a template
     * could trim, escape or reflow into something shared.
     */
    private const val PlaceholderA = "a"
    private const val PlaceholderB = "b"

    /**
     * @param messages the conversation as the next prompt will carry it, ending on the assistant
     *   reply that just finished (system prompt first, if any), as
     *   [io.github.tensaiapp.data.chat.ChatRepository.toEngineMessages] builds it.
     * @param format renders messages through the model's chat template, with the generation
     *   prompt, for the thinking setting the next turn will use.
     * @return the text to prefill, or `null` when the two renders share nothing, or turn out
     *   identical (a template that never renders the user's content): then there is no boundary to
     *   cut at, and warming nothing beats warming a guess.
     */
    fun build(messages: List<ChatMessage>, format: (List<ChatMessage>) -> String): String? {
        val a = format(messages + ChatMessage(MessageRole.User, PlaceholderA))
        val b = format(messages + ChatMessage(MessageRole.User, PlaceholderB))
        val prefix = a.commonPrefixWith(b)
        return prefix.takeIf { it.isNotEmpty() && it.length < a.length && it.length < b.length }
    }
}
