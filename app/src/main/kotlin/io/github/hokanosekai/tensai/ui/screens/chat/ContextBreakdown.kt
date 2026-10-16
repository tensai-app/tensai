package io.github.hokanosekai.tensai.ui.screens.chat

import kotlin.math.roundToInt

/** One slice of the context window, in the order the sheet lists them. */
enum class ContextPart { System, User, Assistant, Thinking, ToolDefinitions, Memory, ToolResults, Overhead, Reserved, Free }

/** A [ContextPart] with something in it: [percent] is of the whole window, 0 when under half a percent (shown "<1"). */
data class ContextPartRow(val part: ContextPart, val tokens: Int, val percent: Int)

/**
 * What the context sheet's window card draws (TEN-161).
 *
 * @param used tokens in the window, the cache's own count when [estimate] is off.
 * @param length the window, `0` when no model was ever loaded to tell.
 * @param rows the parts with tokens in them, in [ContextPart] order; [ContextPart.Free] last.
 * @param estimate the figures come from stored counts, not from the cache: no model is loaded, or
 *   the cache belongs to another conversation (or is not holding this one yet).
 * @param full no free space left once the reply's reserve is taken: the send is refused, TEN-65.
 */
data class ContextBreakdown(
    val used: Int,
    val length: Int,
    val rows: List<ContextPartRow>,
    val estimate: Boolean,
    val full: Boolean,
) {
    /** Whole percent of the window in use, not clamped by the reserve: `34` is "34% used". */
    val usedPercent: Int get() = if (length <= 0) 0 else (used * 100f / length).roundToInt().coerceIn(0, 100)
}

/**
 * Token counts of what the conversation sends, per part.
 * [toolDefinitions]: the tools' name, description and schema as text, an approximation of what the
 * template renders (its own wrapping stays in overhead). [memory]: the saved facts block in front of the
 * first user message (TEN-189), counted apart from [system] and [user]. [toolResults]: `tool` messages
 * plus the serialized calls of assistant messages (TEN-172).
 */
data class ContextCounts(
    val system: Int = 0,
    val user: Int = 0,
    val assistant: Int = 0,
    val thinking: Int = 0,
    val toolDefinitions: Int = 0,
    val memory: Int = 0,
    val toolResults: Int = 0,
) {
    val sum: Int get() = system + user + assistant + thinking + toolDefinitions + memory + toolResults
}

/**
 * Splits a window of [length] tokens into [ContextPart]s.
 *
 * @param cacheUsed the cache's token count when it is this conversation's, `null` when it is not
 *   (no model, or another conversation's): the breakdown is then an estimate. A cache holding less
 *   than [counts] adds up to is not this conversation's yet either (a restore happens on the first
 *   send), and reads as an estimate too.
 * @param reserve what the window keeps back for the reply, see
 *   [io.github.hokanosekai.tensai.engine.ContextBudget.ReservedTokens].
 * @param lastOverhead the template overhead last measured for this conversation, used by an estimate.
 *
 * Template overhead is whatever the cache holds beyond the counted parts (role markers, the
 * template's own text), never negative. Reserved is clipped to the room left so the rows always add
 * up to [length]; [ContextBreakdown.full] is judged on the whole [reserve], which is the figure the
 * send is refused on.
 */
fun contextBreakdown(
    length: Int,
    cacheUsed: Int?,
    counts: ContextCounts,
    reserve: Int,
    lastOverhead: Int? = null,
): ContextBreakdown {
    val parts = counts.copy(
        system = counts.system.coerceAtLeast(0),
        user = counts.user.coerceAtLeast(0),
        assistant = counts.assistant.coerceAtLeast(0),
        thinking = counts.thinking.coerceAtLeast(0),
        toolDefinitions = counts.toolDefinitions.coerceAtLeast(0),
        memory = counts.memory.coerceAtLeast(0),
        toolResults = counts.toolResults.coerceAtLeast(0),
    )
    val sum = parts.sum
    val estimate = cacheUsed == null || cacheUsed < sum
    val overhead = if (estimate) (lastOverhead ?: 0).coerceAtLeast(0) else cacheUsed!! - sum
    val used = if (estimate) sum + overhead else cacheUsed!!

    val room = (length - used).coerceAtLeast(0)
    val reserved = if (length > 0) minOf(reserve.coerceAtLeast(0), room) else 0
    val free = if (length > 0) room - reserved else 0
    val full = length > 0 && length - used - reserve <= 0

    // Percent of the window, or of what is used when the window is unknown.
    val base = if (length > 0) length else used
    fun row(part: ContextPart, tokens: Int): ContextPartRow? =
        if (tokens <= 0) null else ContextPartRow(part, tokens, if (base <= 0) 0 else (tokens * 100f / base).roundToInt())

    val rows = listOfNotNull(
        row(ContextPart.System, parts.system),
        row(ContextPart.User, parts.user),
        row(ContextPart.Assistant, parts.assistant),
        row(ContextPart.Thinking, parts.thinking),
        row(ContextPart.ToolDefinitions, parts.toolDefinitions),
        row(ContextPart.Memory, parts.memory),
        row(ContextPart.ToolResults, parts.toolResults),
        row(ContextPart.Overhead, overhead),
        row(ContextPart.Reserved, reserved),
        row(ContextPart.Free, free),
    )
    return ContextBreakdown(used = used, length = length, rows = rows, estimate = estimate, full = full)
}
