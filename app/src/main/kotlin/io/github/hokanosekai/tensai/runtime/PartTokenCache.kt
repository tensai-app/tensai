package io.github.hokanosekai.tensai.runtime

import java.util.concurrent.ConcurrentHashMap

/**
 * Token counts of the texts the context sheet counts as fixed parts of a prompt (system prompt, tool
 * definitions, memory block), by model and text (TEN-190).
 *
 * The sheet counts them with the model's tokenizer, which is not available while a reply is being
 * generated, and a chat screen is a new instance per conversation. Held per screen, a conversation
 * opened mid-reply, or just after another one, had no count for text that was in the cache all along
 * (the shared prefix of TEN-182 holds the system prompt and the tools), and the sheet put those tokens
 * in the template overhead. Shared, the last count made for the same model and text answers instead.
 */
class PartTokenCache {
    private val counts = ConcurrentHashMap<Pair<String, String>, Int>()

    fun get(model: String?, text: String): Int? = model?.let { counts[it to text] }

    fun put(model: String?, text: String, tokens: Int) {
        if (model == null) return
        // Only a handful of texts are ever live, a system prompt edited a hundred times is not worth keeping.
        if (counts.size >= MAX_ENTRIES) counts.clear()
        counts[model to text] = tokens
    }

    private companion object {
        const val MAX_ENTRIES = 64
    }
}
