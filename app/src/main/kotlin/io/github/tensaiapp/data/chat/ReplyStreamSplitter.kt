package io.github.tensaiapp.data.chat

/**
 * Splits a streaming assistant reply into `content` and `thinking`, preferring the engine's own
 * split when it offers one and falling back to [ThinkingStreamParser] otherwise (TEN-47). One
 * instance per streaming reply, used by
 * [io.github.tensaiapp.ui.screens.chat.ChatViewModel.streamInto].
 *
 * Why two sources rather than one: which markers delimit a model's reasoning is a property of its
 * chat template, so only the layer that applies that template can know — that's the engine, via
 * [io.github.tensaiapp.engine.DecodeEvent.Parsed]. [ThinkingStreamParser] only ever knew
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
 *
 * A reply of a request with tools (`toolsActive`, TEN-185) differs on one point: the engine reports
 * every parse, even an empty one (see [io.github.tensaiapp.engine.DecodeEvent.Parsed]), and
 * an empty one is accepted. Its content then stays what the engine resolved, call markup excluded,
 * instead of the raw text the fallback would show while the parser holds a call marker back. Without
 * tools nothing changes: a literal `<tool_call>` written as text stays visible.
 */
class ReplyStreamSplitter(private val toolsActive: Boolean = false) {

    private val fallback = ThinkingStreamParser()

    private var engineContent: String? = null
    private var engineThinking: String? = null
    private var chunks = 0
    private var finished = false

    /** Feeds the next chunk of raw streamed text — see [ThinkingStreamParser.accept]. */
    fun accept(chunk: String) {
        chunks++
        fallback.accept(chunk)
    }

    /**
     * Feeds an engine-provided split (cumulative, see
     * [io.github.tensaiapp.engine.DecodeEvent.Parsed]). Ignored while both halves are
     * empty: an engine that has resolved nothing yet must not blank out what the fallback already
     * has. With `toolsActive` an empty split counts too, see the class doc.
     */
    fun acceptParsed(content: String, thinking: String?) {
        if (!toolsActive && content.isEmpty() && thinking.isNullOrEmpty()) return
        engineContent = content
        engineThinking = thinking
    }

    /** Flushes the fallback's held-back text — call exactly once, when the stream ends. */
    fun finish() {
        finished = true
        fallback.finish()
    }

    /** Whether the engine's split is what [content]/[thinking] report. */
    val usingEngineSplit: Boolean get() = engineContent != null

    /**
     * With tools the fallback is not shown at first (TEN-185): the engine reports its first parse right
     * after the first token, and until then a reply opening on a call marker would flash raw (the token
     * reaches the caller before that parse does). Held until the engine speaks, [HoldChunks] chunks
     * passed without it (no parser: the fallback is all there is) or [finish].
     */
    private val held: Boolean get() = toolsActive && engineContent == null && !finished && chunks < HoldChunks

    /** The fallback's own content, for a reply the engine could not resolve: only valid after [finish]. */
    val fallbackContent: String get() = fallback.content

    /** Everything outside the reasoning block, resolved so far. */
    val content: String get() = engineContent ?: if (held) "" else fallback.content

    /** The reasoning block's interior, or `null` if none has been seen. */
    val thinking: String? get() = if (engineContent != null) engineThinking else if (held) null else fallback.thinking

    private companion object {
        const val HoldChunks = 8
    }
}
