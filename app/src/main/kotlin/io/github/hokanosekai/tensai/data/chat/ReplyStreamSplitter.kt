package io.github.hokanosekai.tensai.data.chat

/**
 * Splits a streaming assistant reply into `content` and `thinking`, preferring the engine's own
 * split when it offers one and falling back to [ThinkingStreamParser] otherwise (TEN-47). One
 * instance per streaming reply, used by
 * [io.github.hokanosekai.tensai.ui.screens.chat.ChatViewModel.streamInto].
 *
 * Why two sources rather than one: which markers delimit a model's reasoning is a property of its
 * chat template, so only the layer that applies that template can know — that's the engine, via
 * [io.github.hokanosekai.tensai.engine.DecodeEvent.Parsed]. [ThinkingStreamParser] only ever knew
 * `<think>…</think>`, which is why Gemma-4 and gpt-oss/harmony replies used to spill their
 * `<|channel|>` markers straight into the chat. But the engine's split is best-effort by contract
 * (no chat parser, an unrenderable template, or a reply that doesn't match its own template's
 * advertised format all yield nothing), and `<think>` remains right for the models that do use it,
 * so the regex-free tag parser stays as the floor rather than being replaced.
 *
 * Both are fed for the whole stream — [ThinkingStreamParser] is a few `StringBuilder` appends per
 * token, cheap enough that keeping it warm costs less than the machinery to decide when to start
 * it. Which one is *read* is decided by [acceptParsed]: the first non-empty engine split wins, for
 * good. That's a one-way switch on purpose — the engine holds text back while a marker is still
 * ambiguous and reports nothing at all for those tokens, and falling back to the tag parser in
 * those gaps would make the rendered reply flicker between two disagreeing splits.
 */
class ReplyStreamSplitter {

    private val fallback = ThinkingStreamParser()

    private var engineContent: String? = null
    private var engineThinking: String? = null

    /** Feeds the next chunk of raw streamed text — see [ThinkingStreamParser.accept]. */
    fun accept(chunk: String) = fallback.accept(chunk)

    /**
     * Feeds an engine-provided split (cumulative, see
     * [io.github.hokanosekai.tensai.engine.DecodeEvent.Parsed]). Ignored while both halves are
     * empty: an engine that has resolved nothing yet must not blank out what the fallback already
     * has.
     */
    fun acceptParsed(content: String, thinking: String?) {
        if (content.isEmpty() && thinking.isNullOrEmpty()) return
        engineContent = content
        engineThinking = thinking
    }

    /** Flushes the fallback's held-back text — call exactly once, when the stream ends. */
    fun finish() = fallback.finish()

    /** Whether the engine's split is what [content]/[thinking] report. */
    val usingEngineSplit: Boolean get() = engineContent != null

    /** Everything outside the reasoning block, resolved so far. */
    val content: String get() = engineContent ?: fallback.content

    /** The reasoning block's interior, or `null` if none has been seen. */
    val thinking: String? get() = if (engineContent != null) engineThinking else fallback.thinking
}
