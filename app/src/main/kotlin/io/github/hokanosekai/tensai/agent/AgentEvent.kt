package io.github.hokanosekai.tensai.agent

import io.github.hokanosekai.tensai.engine.DecodeStats
import io.github.hokanosekai.tensai.engine.ToolCall

/**
 * How a tool call ended. Stored by its lowercase [storageName] in `tool_calls.status`, a string
 * column like [io.github.hokanosekai.tensai.engine.StopReason]'s so reordering cannot rewrite history.
 */
enum class ToolCallStatus {
    /** The tool answered. */
    Ok,

    /** Unknown tool, bad arguments, an exception, a call over the per-turn limit, or an error result of the tool. */
    Error,

    /** The [ConfirmationGate] said no, or a phone action found no app to open ([ToolResult.refused]). */
    Refused,

    /** The tool ran past its tier's timeout and was cancelled. */
    Timeout,

    /** Stop came first. */
    Stopped,
    ;

    val storageName: String get() = name.lowercase()
}

/**
 * One finished tool call, as the audit log and the model's `tool` message see it.
 *
 * @param tier `null` for a call to a tool that does not exist.
 * @param startedAt epoch millis the call began (before the confirmation, if any).
 * @param durationMs how long the tool ran, confirmation wait excluded; 0 when it never ran.
 * @param output what the model is told, after truncation to the token budget.
 * @param resultTokens tokens of [output]: counted with the loaded model's tokenizer when there is
 *   one, a 4 chars per token estimate otherwise.
 * @param destination see [ToolResult.destination].
 */
data class ToolCallOutcome(
    val call: ToolCall,
    val tier: ToolTier?,
    val status: ToolCallStatus,
    val startedAt: Long,
    val durationMs: Long,
    val output: String,
    val resultTokens: Int,
    val destination: String?,
) {
    companion object {
        /** A call Stop cut short, or never let start: [AgentRuntime.run] cannot emit it once its collector is cancelled. */
        fun stopped(call: ToolCall, tier: ToolTier?, now: Long) = ToolCallOutcome(
            call = call,
            tier = tier,
            status = ToolCallStatus.Stopped,
            startedAt = now,
            durationMs = 0,
            output = "Error: stopped by the user.",
            resultTokens = 0,
            destination = null,
        )
    }
}

/**
 * What [AgentRuntime.run] reports, in order, for the chat to render and record (TEN-173). A turn is
 * one or more replies: each reply that makes tool calls is followed by their results and by the next
 * reply, until one makes none. Always ends with [Done] or [Error], except when the collector is
 * cancelled (Stop), which ends it with the cancellation.
 */
sealed interface AgentEvent {
    /** The turn began. */
    data object TurnStarted : AgentEvent

    /** A reply (one generation) began. [index] counts from 0 within the turn. */
    data class ReplyStarted(val index: Int) : AgentEvent

    /** Raw streamed text of the current reply, see [io.github.hokanosekai.tensai.engine.DecodeEvent.Token]. */
    data class Token(val text: String) : AgentEvent

    /** The engine's split of the current reply, see [io.github.hokanosekai.tensai.engine.DecodeEvent.Parsed]. */
    data class Parsed(val content: String, val reasoning: String?, val toolCalls: List<String> = emptyList()) : AgentEvent

    /**
     * The current reply ended.
     *
     * @param content the final answer text with call markup stripped, `null` when the engine gave no
     *   chat result: use the streamed content then.
     * @param calls the calls to execute, ids made unique. Empty for the last reply of the turn.
     * @param droppedCalls the reply made calls the runtime did not honour (the forced final answer after the
     *   call limit): [content] is then the answer without their markup, use it over the streamed text.
     * @param stats stats of this generation, `null` when none was reported.
     * @param toolsIgnored see [io.github.hokanosekai.tensai.engine.DecodeEvent.ToolTurn.toolsIgnored].
     * @param toolParseFailed see [io.github.hokanosekai.tensai.engine.DecodeEvent.ToolTurn.toolParseFailed].
     * @param truncated see [io.github.hokanosekai.tensai.engine.DecodeEvent.ToolTurn.truncated].
     */
    data class ReplyFinished(
        val index: Int,
        val content: String?,
        val calls: List<ToolCall>,
        val droppedCalls: Boolean = false,
        val stats: DecodeStats?,
        val toolsIgnored: Boolean,
        val toolParseFailed: Boolean,
        val truncated: Boolean,
    ) : AgentEvent

    /** A call is about to be validated, confirmed and run. */
    data class ToolCallStarted(val call: ToolCall, val tier: ToolTier?, val startedAt: Long) : AgentEvent

    /** A call ended, whichever way: see [ToolCallStatus]. Every call of a [ReplyFinished] gets exactly one, unless Stop cancels the turn first, see [ToolCallOutcome.stopped]. */
    data class ToolCallFinished(val outcome: ToolCallOutcome) : AgentEvent

    /** The turn ended with a reply that makes no call. */
    data object Done : AgentEvent

    /** A generation failed. The turn ends here. */
    data class Error(val cause: Throwable) : AgentEvent
}
