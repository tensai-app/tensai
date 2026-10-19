package io.github.tensaiapp.ui.screens.chat

import io.github.tensaiapp.agent.AskUserResult
import io.github.tensaiapp.agent.AskUserTool
import io.github.tensaiapp.data.chat.MessageEntity
import io.github.tensaiapp.data.chat.ToolCallEntity
import io.github.tensaiapp.data.chat.ToolCallsJson
import io.github.tensaiapp.engine.ToolCall
import io.github.tensaiapp.ui.tools.ToolCallState
import io.github.tensaiapp.ui.tools.ToolArgsView
import io.github.tensaiapp.ui.tools.ToolCallUi

/** Id prefix of the placeholder calls stored while a reply is still writing them (TEN-185): never gated, a plain running line. */
internal const val WritingCallIdPrefix = "writing_"

/** What the answer to a [ConfirmationItem] stands at. */
enum class ConfirmDecision { Pending, Allowed, Denied, Stopped }

/** One row of the conversation list: a message, a tool line, a confirmation card or an answer to a question (TEN-173, TEN-174, TEN-187). */
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
 * What the user answered an `ask_user` question, drawn as a user bubble under the call's line (TEN-187). Built from
 * the call and its result, not a stored message: the model reads the result, never a user message that was not typed.
 */
data class AskAnswerItem(override val key: String, val question: String, val answers: List<String>) : ChatItem

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
 *   [ToolLineItem]. Its key is its first call's message and position, so it stays put while the group grows.
 * - A call that [requiresConfirmation] is a [ConfirmationItem] when it was decided: [ConfirmDecision.Allowed]
 *   once it ran ok, [ConfirmDecision.Denied] when refused, [ConfirmDecision.Stopped] when Stop cut it. While
 *   the turn runs it is pending while in [pendingCallIds], allowed once in [allowedIds], and shows nothing
 *   before the gate is reached. One that failed ([ToolCallState.failed]) never was a decision (it may have
 *   failed before the gate: call limit, invalid arguments): it is an error in a line.
 * - An `ask_user` call is its own [ToolLineItem], followed by an [AskAnswerItem] once the user answered. A dismissed one
 *   (a result of `{"dismissed": true}`) is [ToolCallState.Dismissed] and has no answer.
 * - A call with no stored result is [ToolCallState.Running] while the turn [agentRunning], and
 *   [ToolCallState.Stopped] otherwise (a killed process leaves such calls behind).
 *
 * @param allowedIds calls the user allowed whose result is not stored yet, see
 *   [io.github.tensaiapp.agent.UiConfirmationGate.allowed].
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
    // Keyed by the line's first call position in its message, not its id: the placeholder id of a call still being
    // written is replaced by the real one, and the line must not be rebuilt for it.
    var groupKey = ""

    fun flush() {
        if (group.isEmpty()) return
        items += ToolLineItem(groupKey, group.toList())
        group.clear()
    }

    for (message in messages) {
        if (message.role != "user" && message.role != "assistant") continue
        message.toUi()?.let {
            flush()
            items += MessageItem(it)
        }
        for ((index, call) in calls[message.id].orEmpty().withIndex()) {
            val ui = toolCallUi(message.id, call, rows[message.id to call.id], outputs[message.id to call.id], agentRunning, requiresConfirmation)
            if (call.name == AskUserTool.NAME) {
                // Its own line, and the answer under it: a question is a turn of the conversation, not a tool run to fold.
                flush()
                val asked = if (ui.state == ToolCallState.Ok && AskUserResult.isDismissed(ui.output)) ui.copy(state = ToolCallState.Dismissed) else ui
                items += ToolLineItem("tools:${message.id}:$index", listOf(asked))
                val answers = if (asked.state == ToolCallState.Ok) AskUserResult.answers(asked.output) else emptyList()
                if (answers.isNotEmpty()) {
                    items += AskAnswerItem("ask:${message.id}:${call.id}", AskUserTool.capQuestion(ToolArgsView.values(call.argumentsJson)["question"].orEmpty()), answers)
                }
                continue
            }
            val decision = if (!ui.confirmed) null else when (ui.state) {
                ToolCallState.Ok -> ConfirmDecision.Allowed
                ToolCallState.Refused -> ConfirmDecision.Denied
                ToolCallState.Stopped -> ConfirmDecision.Stopped
                ToolCallState.Running -> when {
                    call.id in pendingCallIds -> ConfirmDecision.Pending
                    call.id in allowedIds -> ConfirmDecision.Allowed
                    else -> null
                }
                ToolCallState.Error, ToolCallState.Timeout, ToolCallState.Dismissed -> null
            }
            when {
                decision != null -> {
                    flush()
                    items += ConfirmationItem("confirm:${message.id}:${call.id}", ui, decision)
                }
                // Gated and running but not asked yet: nothing to show until the gate is reached.
                ui.confirmed && ui.state == ToolCallState.Running -> flush()
                else -> {
                    if (group.isEmpty()) groupKey = "tools:${message.id}:$index"
                    group += ui
                }
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
        confirmed = !call.id.startsWith(WritingCallIdPrefix) && requiresConfirmation(call.name, call.argumentsJson),
    )
}
