package io.github.hokanosekai.tensai.ui.screens.chat

import io.github.hokanosekai.tensai.data.chat.MessageEntity
import io.github.hokanosekai.tensai.data.chat.ToolCallEntity
import io.github.hokanosekai.tensai.data.chat.ToolCallsJson
import io.github.hokanosekai.tensai.engine.ToolCall
import io.github.hokanosekai.tensai.ui.tools.ToolCallState
import io.github.hokanosekai.tensai.ui.tools.ToolCallUi

/** What the answer to a [ConfirmationItem] stands at. */
enum class ConfirmDecision { Pending, Allowed, Denied, Stopped }

/** One row of the conversation list: a message, a tool line, or a confirmation card (TEN-173, TEN-174). */
sealed interface ChatItem {
    /** Stable across rebuilds, the list's key. */
    val key: String
}

data class MessageItem(val message: ChatMessageUi) : ChatItem {
    override val key: String get() = message.id
}

/** The consecutive tool calls between two pieces of assistant text, as one line. */
data class ToolLineItem(override val key: String, val calls: List<ToolCallUi>) : ChatItem

/** A call that went through the confirmation gate: asking, or answered. */
data class ConfirmationItem(override val key: String, val call: ToolCallUi, val decision: ConfirmDecision) : ChatItem

/**
 * Builds the conversation list from what is stored: messages in order, with the calls of each assistant
 * message turned into tool lines and confirmation cards between them. The same function serves a chat that
 * is reopened and one that is running, as every step of a turn is written down when it happens.
 *
 * Calls are identified by their assistant message and their id together: the runtime makes ids unique, but a
 * chat stored before that (or a template's own `call_1`) repeats them from one turn to the next. A `tool`
 * message answers the closest assistant message before it, and item keys carry the message id.
 *
 * - An assistant message without text (its calls only) adds no bubble, only its calls (and its thinking, when
 *   [MessageEntity.toUi] keeps it).
 * - Calls that follow each other, across assistant messages, with no visible message between, are one
 *   [ToolLineItem]. Its key is its first call's, so it stays put while the group grows.
 * - A call that [requiresConfirmation] is a [ConfirmationItem] when it was decided: [ConfirmDecision.Allowed]
 *   once it ran ok, [ConfirmDecision.Denied] when refused, [ConfirmDecision.Stopped] when Stop cut it. While
 *   the turn runs it is pending while in [pendingCallIds], allowed once in [allowedIds], and shows nothing
 *   before the gate is reached. One that failed ([ToolCallState.failed]) never was a decision (it may have
 *   failed before the gate: call limit, invalid arguments): it is an error in a line.
 * - A call with no stored result is [ToolCallState.Running] while the turn [agentRunning], and
 *   [ToolCallState.Stopped] otherwise (a killed process leaves such calls behind).
 *
 * @param allowedIds calls the user allowed whose result is not stored yet, see
 *   [io.github.hokanosekai.tensai.agent.UiConfirmationGate.allowed].
 * @param decode the calls of an assistant message, [ToolCallsJson.decode] unless the caller caches it.
 */
internal fun buildChatItems(
    messages: List<MessageEntity>,
    toolCalls: List<ToolCallEntity>,
    agentRunning: Boolean,
    pendingCallIds: Set<String>,
    allowedIds: Collection<String>,
    requiresConfirmation: (tool: String, argsJson: String) -> Boolean,
    decode: (MessageEntity) -> List<ToolCall> = { ToolCallsJson.decode(it.toolCallsJson) },
): List<ChatItem> {
    val rows = toolCalls.associateBy { it.messageId to it.callId }
    val calls = HashMap<String, List<ToolCall>>()
    val outputs = HashMap<Pair<String, String>, String>()
    var caller: MessageEntity? = null
    for (message in messages) {
        if (message.role == "assistant") {
            val made = if (message.toolCallsJson.isNullOrBlank()) emptyList() else decode(message)
            if (made.isNotEmpty()) {
                calls[message.id] = made
                caller = message
            }
        } else if (message.role == "tool" && caller != null && message.toolCallId != null) {
            if (calls.getValue(caller.id).any { it.id == message.toolCallId }) outputs[caller.id to message.toolCallId] = message.content
        }
    }

    val items = ArrayList<ChatItem>()
    val group = ArrayList<ToolCallUi>()

    fun flush() {
        if (group.isEmpty()) return
        items += ToolLineItem("tools:${group.first().messageId}:${group.first().id}", group.toList())
        group.clear()
    }

    for (message in messages) {
        if (message.role != "user" && message.role != "assistant") continue
        message.toUi()?.let {
            flush()
            items += MessageItem(it)
        }
        for (call in calls[message.id].orEmpty()) {
            val ui = toolCallUi(message.id, call, rows[message.id to call.id], outputs[message.id to call.id], agentRunning, requiresConfirmation)
            val decision = if (!ui.confirmed) null else when (ui.state) {
                ToolCallState.Ok -> ConfirmDecision.Allowed
                ToolCallState.Refused -> ConfirmDecision.Denied
                ToolCallState.Stopped -> ConfirmDecision.Stopped
                ToolCallState.Running -> when {
                    call.id in pendingCallIds -> ConfirmDecision.Pending
                    call.id in allowedIds -> ConfirmDecision.Allowed
                    else -> null
                }
                ToolCallState.Error, ToolCallState.Timeout -> null
            }
            when {
                decision != null -> {
                    flush()
                    items += ConfirmationItem("confirm:${message.id}:${call.id}", ui, decision)
                }
                // Gated and running but not asked yet: nothing to show until the gate is reached.
                ui.confirmed && ui.state == ToolCallState.Running -> flush()
                else -> group += ui
            }
        }
    }
    flush()
    return items
}

private fun toolCallUi(
    messageId: String,
    call: ToolCall,
    row: ToolCallEntity?,
    output: String?,
    agentRunning: Boolean,
    requiresConfirmation: (String, String) -> Boolean,
): ToolCallUi {
    val state = when {
        row != null -> ToolCallState.fromStorage(row.status)
        agentRunning -> ToolCallState.Running
        else -> ToolCallState.Stopped
    }
    return ToolCallUi(
        id = call.id,
        messageId = messageId,
        tool = call.name,
        argsJson = call.argumentsJson,
        state = state,
        durationMs = row?.durationMs,
        output = output,
        destination = row?.destination,
        confirmed = requiresConfirmation(call.name, call.argumentsJson),
    )
}
