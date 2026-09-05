package io.github.hokanosekai.tensai.data.chat

/**
 * Incrementally splits a streaming assistant reply into `content` and `thinking` around a single
 * `<think>...</think>` pair (TEN-33) — the convention several chat-template-driven models (e.g.
 * Qwen3) use to emit their reasoning ahead of the actual answer. Used by
 * [io.github.hokanosekai.tensai.ui.screens.chat.ChatViewModel.streamInto], one instance per
 * streaming reply.
 *
 * Feeding this whole tokens at a time is not required — [accept] tolerates the opening/closing tag
 * arriving split across an arbitrary number of chunks (a model rarely emits a tag as a single
 * token), by holding back a chunk's trailing text whenever it's a prefix of the tag currently being
 * watched for, and only flushing it once either the tag completes or enough new text disproves the
 * match.
 *
 * Only the first `<think>` is treated as an opening tag; text before it (rare, but the model is
 * free to preface its reasoning) and everything after the matching `</think>` both count as
 * `content`. A `<think>` that's still open when the stream ends (see [finish]) leaves [thinking]
 * populated and [content] empty — the caller renders that as "thinking still in progress".
 */
class ThinkingStreamParser {

    private enum class State { BeforeThink, InThink, AfterThink }

    private val contentBuilder = StringBuilder()
    private val thinkingBuilder = StringBuilder()

    /** Text not yet resolved as either `content` or `thinking` — see [process]'s lookahead. */
    private val pending = StringBuilder()

    private var state = State.BeforeThink
    private var thinkOpened = false

    /** Everything outside the `<think>...</think>` pair, resolved so far. */
    val content: String get() = contentBuilder.toString()

    /** The `<think>...</think>` interior, or `null` if no `<think>` has opened yet. */
    val thinking: String? get() = if (thinkOpened) thinkingBuilder.toString() else null

    /** Feeds the next chunk of streamed text — may be a whole token, a fragment of one, or several. */
    fun accept(chunk: String) {
        pending.append(chunk)
        process()
    }

    /**
     * Flushes whatever text is still held back in [pending] — call exactly once, when the stream
     * ends (on `DecodeEvent.Done`/`DecodeEvent.Error`), otherwise a trailing fragment that looked
     * like it might grow into a tag (e.g. a final "</th") but never did would stay stuck unflushed.
     */
    fun finish() {
        if (pending.isEmpty()) return
        sinkFor(state).append(pending)
        pending.clear()
    }

    private fun process() {
        while (pending.isNotEmpty()) {
            val tag = tagFor(state) ?: run {
                contentBuilder.append(pending)
                pending.clear()
                return
            }
            val idx = pending.indexOf(tag)
            if (idx >= 0) {
                sinkFor(state).append(pending, 0, idx)
                pending.delete(0, idx + tag.length)
                // Only BeforeThink and InThink can be here: [tagFor] returns null for AfterThink,
                // which the `?: run { … return }` above has already turned into an early exit. So
                // this is "opened the think, or closed it", not a transition table.
                //
                // Kept as an exhaustive `when` over a dead arm rather than collapsed to an
                // if/else: [tagFor] and [sinkFor] would already fail to compile on a fourth
                // [State], so the build breaking is not what is at stake — *which* build breaks
                // is. An if/else would route a new state silently onto AfterThink here while the
                // other two `when`s demanded an answer, so the transition would be decided by
                // omission. This way the compiler asks in all three places.
                state = when (state) {
                    State.BeforeThink -> {
                        thinkOpened = true
                        State.InThink
                    }
                    State.InThink -> State.AfterThink
                    State.AfterThink -> error("unreachable: tagFor returns null for AfterThink")
                }
            } else {
                val heldBack = longestSuffixThatIsTagPrefix(pending, tag)
                val flushLength = pending.length - heldBack
                if (flushLength <= 0) return
                sinkFor(state).append(pending, 0, flushLength)
                pending.delete(0, flushLength)
                return
            }
        }
    }

    private fun tagFor(state: State): String? = when (state) {
        State.BeforeThink -> OpenTag
        State.InThink -> CloseTag
        State.AfterThink -> null
    }

    private fun sinkFor(state: State): StringBuilder = when (state) {
        State.BeforeThink, State.AfterThink -> contentBuilder
        State.InThink -> thinkingBuilder
    }

    /** Longest suffix of [text] that is itself a strict prefix of [tag] — the part that must be held back in case the next chunk completes the tag. */
    private fun longestSuffixThatIsTagPrefix(text: CharSequence, tag: String): Int {
        val maxLen = minOf(text.length, tag.length - 1)
        for (len in maxLen downTo 1) {
            if (text.endsWith(tag.substring(0, len))) return len
        }
        return 0
    }

    companion object {
        private const val OpenTag = "<think>"

        /**
         * The closing tag itself, exposed (unlike [OpenTag]) so
         * [io.github.hokanosekai.tensai.ui.screens.chat.ChatViewModel] can pass it straight
         * through as [io.github.hokanosekai.tensai.engine.ReasoningBudget.endMarker] (TEN-61) —
         * the one place outside this parser that needs to know `<think>`'s format at all, since the
         * engine itself is kept opaque to it (see [io.github.hokanosekai.tensai.engine.ReasoningBudget]'s KDoc).
         */
        const val CloseTag = "</think>"
    }
}
