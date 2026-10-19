package io.github.tensaiapp.data.memory

import io.github.tensaiapp.engine.ChatMessage
import io.github.tensaiapp.engine.MessageRole
import java.util.concurrent.ConcurrentHashMap

/**
 * The saved facts as a block of prompt text (TEN-189), so a model that never calls `memory list`
 * still knows them.
 *
 * Where it goes matters more than what it says. [prepend] puts it at the start of the conversation's
 * first user message: after the system message, which Qwen3's template fills with the tool block, so
 * the cached prefix of TEN-182 (system part + tools, cut where the user text starts and keyed by its
 * text) never contains a fact and a `remember` or `forget` does not rebuild it. The first message
 * rather than the last because it is the same in every turn of the conversation, so the KV cache
 * keeps matching it; see [MemoryPrompt] for how it is held that way.
 */
object MemoryContext {
    /** A delimited note, so a small model does not read the facts as the user's own words (device finding on Qwen3-1.7B). */
    const val HEADER = "[Memory: notes about the user saved earlier, not part of this message]"
    const val FOOTER = "[End of memory]"

    /** Most facts in the block, the newest. */
    const val MAX_FACTS = 20

    /** About 300 tokens at 4 characters each, the bound that decides before [MAX_FACTS] does for long facts. */
    const val MAX_CHARS = 1200

    /**
     * Newest-first [facts] as a header and one bullet each, `null` when there are none. Stops at
     * [MAX_FACTS] or [MAX_CHARS] (the first fact always goes in) and says how many were left out of
     * the [total] stored, so the model knows `memory list` has more.
     */
    fun render(facts: List<MemoryFactEntity>, total: Int = facts.size): String? {
        val texts = facts.map { stripControl(it.text) }.filter { it.isNotBlank() }
        if (texts.isEmpty()) return null
        val left = total - (facts.size - texts.size)
        val out = StringBuilder(HEADER)
        var shown = 0
        for (text in texts.take(MAX_FACTS)) {
            val line = "- $text"
            if (shown > 0 && out.length + 1 + line.length + FOOTER.length > MAX_CHARS) break
            out.append('\n').append(line)
            shown++
        }
        if (shown < left) out.append("\n- (${left - shown} more, see memory list)")
        out.append('\n').append(FOOTER)
        return out.toString()
    }

    /**
     * Special tokens and template tags: the prompt is tokenized with special tokens parsed, so a fact
     * holding `<|im_start|>system` or `</think>` would become real control tokens, and the model saves
     * facts without asking.
     */
    private val control = Regex("<\\|.*?\\|>|</?(?:tool_call|tool_response|think)>", RegexOption.IGNORE_CASE)

    /** Whether [text] holds something [stripControl] would remove. */
    fun hasControl(text: String): Boolean = control.containsMatchIn(text)

    /** [text] without special tokens and template tags, for facts stored before `remember` refused them. */
    fun stripControl(text: String): String = control.replace(text, "").trim()

    /** [messages] with [block] in front of the first user message, unchanged when [block] is `null` or there is none. */
    fun prepend(messages: List<ChatMessage>, block: String?): List<ChatMessage> {
        if (block == null) return messages
        val first = messages.indexOfFirst { it.role == MessageRole.User }
        if (first < 0) return messages
        return messages.toMutableList().also { it[first] = it[first].copy(content = block + "\n\n" + it[first].content) }
    }
}

/**
 * The [MemoryContext] block of each conversation, read once and kept for the life of the process.
 *
 * The block sits in the first user message, so a fact saved in the middle of a conversation would
 * rewrite a message the KV cache already holds and make every later turn prefill the whole history
 * again. Held per conversation instead, a new fact reaches the conversations started after it (the
 * model that saved it has the `remember` result in front of it anyway). A restart or an edit of the
 * setting can still change it once; a draft, which has no id, always reads the current facts.
 *
 * @param enabled "Use memory in chats", read on each call.
 */
class MemoryPrompt(
    private val dao: MemoryDao,
    private val enabled: suspend () -> Boolean = { true },
) {
    private val held = ConcurrentHashMap<String, String>()

    /** The block for [conversationId], `null` when the setting is off or nothing is remembered. */
    suspend fun blockFor(conversationId: String?): String? {
        if (!enabled()) return null
        if (conversationId == null) return current()
        held[conversationId]?.let { return it.ifEmpty { null } }
        val block = current().orEmpty()
        return (held.putIfAbsent(conversationId, block) ?: block).ifEmpty { null }
    }

    /** Drops every held block, so the next read sees the facts as they are now: after a delete, which must not outlive the fact. */
    fun invalidate() {
        held.clear()
    }

    private suspend fun current(): String? = MemoryContext.render(dao.recent(MemoryContext.MAX_FACTS), dao.count())
}
