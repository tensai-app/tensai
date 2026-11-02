package io.github.tensaiapp.agent

import io.github.tensaiapp.BuildConfig
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The harness's own instructions: the first layer of every conversation's system message, before the
 * user's (see [SystemPrompt]). Kept short, a small model loses the thread of a long one.
 *
 * Each tool line names a tool, so it is there only when that tool is offered: a line about a tool the
 * model cannot call invites the call. A plain chat (no tools) gets the first two lines and the date.
 *
 * No date when `datetime` is offered: the tool is where "now" comes from, and a date in the system
 * message would change the cached tool prefix (keyed by its text) every day. Without the tool, the day
 * the conversation started, not today: it stays the same for the whole conversation, so the prompt its
 * KV cache and saved session hold still matches on the next day.
 */
object BasePrompt {
    /** Bumped with every change of the text, so a measurement can say which one it ran. */
    const val VERSION = 2

    /** The tools a line of the prompt names: with all of them offered, every rule is there (what Settings shows). */
    val ToolsNamed = setOf(AskUserTool.NAME, WebSearchTool.NAME, "web_fetch", "datetime")

    /** The stricter tool rule of [ToolPolicy.AlwaysStrict]. */
    const val StrictLine = "Call a tool only for live data, a computation you cannot do reliably, or an action."

    private val dateFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)

    /**
     * The text for a request offering [tools] (by name), `null` for a plain chat, in a conversation
     * started on [startedOn]. [examples]: the few-shot examples under the rules, debug builds only (an
     * experiment, measured with the agent bench before it ships). [strict]: [StrictLine] under the first tool rule,
     * debug builds only too ([ToolPolicy.AlwaysStrict]).
     */
    fun render(tools: Set<String>?, startedOn: LocalDate, examples: Boolean = false, strict: Boolean = false): String = buildList {
        add("You are tensai, an assistant running on the user's phone.")
        add("Reply in the language of the user's last message.")
        if (!tools.isNullOrEmpty()) {
            add("Answer directly when you can. Call a tool only when the answer needs one.")
            // A constant in a release, as the examples below.
            if (strict && BuildConfig.DEBUG) add(StrictLine)
            add("Write stories, poems, letters and other texts yourself, with no tool.")
            add("Never invent a tool result. If a tool fails, say so.")
            if (AskUserTool.NAME in tools) add("Call ask_user only when the request is genuinely ambiguous.")
            if (WebSearchTool.NAME in tools) {
                add(if ("web_fetch" in tools) "web_search finds pages. Call web_fetch on one when you need a precise fact from it." else "web_search finds pages.")
            }
        }
        if (tools != null && "datetime" in tools) {
            add("For the current date or time, call datetime; for another place, pass its IANA timezone.")
        } else {
            add("This conversation started on ${startedOn.format(dateFormat)}.")
        }
        // A constant in a release: R8 drops the branch and the examples' text with it.
        if (examples && BuildConfig.DEBUG && !tools.isNullOrEmpty()) addAll(examplesFor(tools))
    }.joinToString("\n")

    /** One exchange per tool among the ones offered, then one that needs none. None is a prompt of the bench. */
    private fun examplesFor(tools: Set<String>): List<String> = buildList {
        add("")
        add("Examples:")
        if ("datetime" in tools) add("User: What day of the week is it?\nYou: call datetime, then answer from its result.")
        if ("calculate" in tools) add("User: What is 1234 times 5678?\nYou: call calculate with 1234*5678.")
        if ("convert" in tools) add("User: How many pounds is 5 kg?\nYou: call convert.")
        add("User: Translate \"thank you\" into German.\nYou: no tool needed, answer \"Danke.\"")
    }
}

/**
 * Builds a request's system message from its layers, in this order: [BasePrompt], the user's
 * instructions for every chat (Settings), then the ones of this conversation. Each layer is appended,
 * none replaces another, and the user's two are labelled so the model knows where they come from and
 * which one is narrower (a layer alone needs no label: what a chat sent before there were layers).
 * `null` when every layer is empty.
 *
 * The engine keys its cached tool prefix on the text of the system part, and the queue and a restored
 * session compare tokens, so any layer that changes changes what is matched against: nothing else has
 * to be invalidated by hand.
 *
 * @param base whether [BasePrompt] goes first: always, except in the debug agent bench's A/B.
 * @param examples [BasePrompt]'s few-shot examples, see [BasePrompt.render].
 * @param strict [BasePrompt]'s stricter tool line, see [BasePrompt.render].
 * @param clock today, for a conversation that has no start time yet (a draft).
 */
class SystemPrompt(
    private val base: Boolean = true,
    private val examples: Boolean = false,
    private val strict: Boolean = false,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    /**
     * The system message of a request offering [tools] (`null` on the plain path), in a conversation
     * created at [startedAt] (epoch millis, `null` for a draft), with the user's [global] and [chat]
     * instructions.
     */
    fun build(tools: Set<String>?, startedAt: Long?, global: String?, chat: String?): String? {
        val startedOn = Instant.ofEpochMilli(startedAt ?: clock.millis()).atZone(clock.zone).toLocalDate()
        return assemble(if (base) BasePrompt.render(tools, startedOn, examples, strict) else null, global, chat)
    }

    companion object {
        const val GLOBAL_LABEL = "The user's instructions for every chat:"
        const val CHAT_LABEL = "The user's instructions for this chat:"

        /** The layers joined, blank ones left out. */
        fun assemble(base: String?, global: String?, chat: String?): String? {
            val b = base?.trim()?.takeIf { it.isNotEmpty() }
            val g = global?.trim()?.takeIf { it.isNotEmpty() }
            val c = chat?.trim()?.takeIf { it.isNotEmpty() }
            val labelled = listOfNotNull(b, g, c).size > 1
            return listOfNotNull(
                b,
                g?.let { if (labelled) "$GLOBAL_LABEL\n$it" else it },
                c?.let { if (labelled) "$CHAT_LABEL\n$it" else it },
            ).joinToString("\n\n").ifEmpty { null }
        }
    }
}
