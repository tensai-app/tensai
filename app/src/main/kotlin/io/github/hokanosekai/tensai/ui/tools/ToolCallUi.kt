package io.github.hokanosekai.tensai.ui.tools

import io.github.hokanosekai.tensai.R

/** How a call stands, from the persisted audit row (`tool_calls.status`) or, with no row yet, from the turn running. */
enum class ToolCallState {
    /** Made by the model, no result stored yet, and the turn is still running. */
    Running,
    Ok,
    Error,
    Timeout,
    Refused,
    Stopped,
    ;

    /** The call went wrong: the tool failed, was unknown, had bad arguments or timed out. */
    val failed: Boolean get() = this == Error || this == Timeout

    companion object {
        /** The state a stored [status] stands for, [Error] for a name this build does not know. */
        fun fromStorage(status: String): ToolCallState = when (status) {
            "ok" -> Ok
            "refused" -> Refused
            "timeout" -> Timeout
            "stopped" -> Stopped
            else -> Error
        }
    }
}

/**
 * One tool call as the chat shows it (TEN-173), built from the assistant message that made it, its `tool`
 * message and its audit row, see [io.github.hokanosekai.tensai.ui.screens.chat.buildChatItems].
 *
 * @param id the call's id, unique since the runtime makes them so. A chat stored earlier may repeat ids across turns,
 *   so anything that keys on a call pairs it with [messageId], the assistant message that made it.
 * @param output what the model was told, `null` until the call ended.
 * @param destination where a network call sent its request.
 * @param confirmed the call goes through the confirmation gate: it shows as a card, not in a tool line.
 */
data class ToolCallUi(
    val id: String,
    val messageId: String,
    val tool: String,
    val argsJson: String,
    val state: ToolCallState,
    val durationMs: Long?,
    val output: String?,
    val destination: String?,
    val confirmed: Boolean,
)

/** What a [ToolLineSummary] looks like: picks the tint and the leading icon. */
enum class LineStatus { Running, Ok, Error, Refused, Stopped }

/** The text and tint of one tool line, see [toolLineSummary]. */
data class ToolLineSummary(val label: UiText, val status: LineStatus)

/**
 * The line a group of consecutive calls collapses into (TEN-173):
 * - a call still running wins, with its running label ("Searching the web...");
 * - then a failure: "<tool> failed", for the first call that failed;
 * - then a refusal: "<tool> refused";
 * - then a stop: "Stopped";
 * - otherwise one call reads as its action ("Set a timer"), several calls with the same action as that action
 *   and a count ("Searched the web x3"), anything else as "Used n tools".
 */
fun toolLineSummary(calls: List<ToolCallUi>): ToolLineSummary {
    calls.firstOrNull { it.state == ToolCallState.Running }?.let {
        return ToolLineSummary(ToolUiCatalog.runningLabel(it.tool, it.argsJson), LineStatus.Running)
    }
    calls.firstOrNull { it.state.failed }?.let {
        return ToolLineSummary(UiText.of(R.string.tool_line_failed, ToolUiCatalog.name(it.tool)), LineStatus.Error)
    }
    calls.firstOrNull { it.state == ToolCallState.Refused }?.let {
        return ToolLineSummary(ToolUiCatalog.refusedLabel(it.tool), LineStatus.Refused)
    }
    if (calls.any { it.state == ToolCallState.Stopped }) {
        return ToolLineSummary(UiText.of(R.string.tool_line_stopped), LineStatus.Stopped)
    }
    val labels = calls.map { ToolUiCatalog.doneLabel(it.tool, it.argsJson) }
    val label = when {
        calls.size == 1 -> labels.first()
        labels.distinct().size == 1 -> UiText.of(R.string.tool_line_repeated, labels.first(), calls.size)
        else -> UiText.Plural(R.plurals.tool_line_used_tools, listOf(calls.size))
    }
    return ToolLineSummary(label, LineStatus.Ok)
}

/** The label of one call in the timeline: its action once done, "<tool> failed" and the like otherwise. */
fun toolCallLabel(call: ToolCallUi): UiText = when {
    call.state == ToolCallState.Running -> ToolUiCatalog.runningLabel(call.tool, call.argsJson)
    call.state.failed -> UiText.of(R.string.tool_line_failed, ToolUiCatalog.name(call.tool))
    call.state == ToolCallState.Refused -> ToolUiCatalog.refusedLabel(call.tool)
    call.state == ToolCallState.Stopped -> UiText.of(R.string.tool_line_stopped)
    else -> ToolUiCatalog.doneLabel(call.tool, call.argsJson)
}

/** The arguments of a call on one line, "forget · 1": what the timeline shows under the action. */
fun toolCallSummary(call: ToolCallUi): String =
    ToolArgsView.values(call.argsJson).values.take(3).joinToString(" · ")

/** "120 ms", "1.4 s": [ms] for the timeline and the detail header. */
fun formatToolDuration(ms: Long): String =
    if (ms < 1_000) "$ms ms" else String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)
