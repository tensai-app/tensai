package io.github.tensaiapp.data.chat

import io.github.tensaiapp.agent.ToolCallOutcome
import io.github.tensaiapp.data.memory.MemoryContext
import io.github.tensaiapp.engine.ChatMessage
import io.github.tensaiapp.engine.DecodeStats
import io.github.tensaiapp.engine.MessageRole
import io.github.tensaiapp.engine.SamplingConfig
import io.github.tensaiapp.engine.StopReason
import io.github.tensaiapp.engine.ToolCall
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow

/** Max length of a [ConversationEntity.title] auto-derived from the first user message. */
private const val TITLE_MAX_LENGTH = 40

/**
 * Owns [ConversationEntity] and [MessageEntity] rows — the chat history persisted across process
 * death, and the source [toEngineMessages] converts into the format
 * [io.github.tensaiapp.runtime.InferenceQueue.generate] expects.
 *
 * @param now epoch millis a new conversation is stamped with: its day is the base prompt's date, see
 *   [io.github.tensaiapp.agent.SystemPrompt].
 */
class ChatRepository(private val dao: ChatDao, private val now: () -> Long = System::currentTimeMillis) {

    /** All conversations, most recently updated first — feeds the drawer's "Recents" list. */
    fun observeConversations(): Flow<List<ConversationEntity>> = dao.observeConversations()

    /** A single conversation's messages, oldest first. */
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> = dao.observeMessages(conversationId)

    /** The audit rows of every tool call in a conversation, oldest first: what the chat's tool lines are built from (TEN-173). */
    fun observeToolCalls(conversationId: String): Flow<List<ToolCallEntity>> = dao.observeToolCalls(conversationId)

    /** Per-tool totals of a conversation, most called first (TEN-162). */
    suspend fun toolActivity(conversationId: String): List<ToolActivityRow> = dao.toolActivity(conversationId)

    /** A single conversation, reactively — used by [io.github.tensaiapp.ui.screens.chat.ChatViewModel.enableThinking] to reflect [ConversationEntity.enableThinking] as it's toggled. */
    fun observeConversation(conversationId: String): Flow<ConversationEntity?> = dao.observeConversation(conversationId)

    /**
     * Creates an empty conversation bound to [modelId] (nullable — see [ConversationEntity.modelId])
     * and returns its id. The title starts empty (TEN-105): "untitled" is a display concern, so the
     * UI renders it with the localized `drawer_new_chat` string instead of an English literal
     * frozen into the row.
     *
     * The settings default to a fresh conversation's. A new chat is a draft with no row until its
     * first message (TEN-106), and whatever the user set on that draft in the settings sheet is
     * passed here, so the row is born with it rather than patched right after.
     * [systemPrompt] is normalized blank to `null`, same as [setSystemPrompt].
     */
    suspend fun createConversation(
        modelId: String?,
        samplingJson: String? = null,
        systemPrompt: String? = null,
        enableThinking: Boolean = true,
        toolsEnabled: Boolean = true,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = now()
        dao.insertConversation(
            ConversationEntity(
                id = id,
                title = "",
                modelId = modelId,
                samplingJson = samplingJson,
                systemPrompt = systemPrompt?.ifBlank { null },
                enableThinking = enableThinking,
                toolsEnabled = toolsEnabled,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return id
    }

    /**
     * Appends a user message to [conversationId]. If this is the conversation's first message,
     * also sets [ConversationEntity.title] from [content] (truncated to [TITLE_MAX_LENGTH]).
     */
    suspend fun appendUserMessage(conversationId: String, content: String) {
        val isFirstMessage = dao.messageCount(conversationId) == 0
        insertMessage(conversationId, MessageRole.User, content)
        val conversation = dao.getConversation(conversationId) ?: return
        val title = if (isFirstMessage) truncateTitle(content) else conversation.title
        dao.updateConversation(conversation.copy(title = title, updatedAt = System.currentTimeMillis()))
    }

    /** Inserts an empty assistant message and returns its id — the streaming decode fills it in via [updateAssistantMessage]. */
    suspend fun startAssistantMessage(conversationId: String): String {
        val id = insertMessage(conversationId, MessageRole.Assistant, "")
        val conversation = dao.getConversation(conversationId) ?: return id
        dao.updateConversation(conversation.copy(updatedAt = System.currentTimeMillis()))
        return id
    }

    /**
     * Updates a streaming assistant message's content/thinking/stats in place, by [id] — called
     * on every token while [startAssistantMessage]'s reply streams in, so unlike
     * [appendUserMessage]/[startAssistantMessage] this deliberately does not also touch the owning
     * conversation's `updatedAt` (that already happened once, in [startAssistantMessage] — bumping
     * it again on every token would just be write amplification for no observable benefit).
     *
     * @param stopReason defaults to [stats]' own, which is what a decode that reached
     *   [io.github.tensaiapp.engine.DecodeEvent.Done] reports. Passed explicitly for
     *   [io.github.tensaiapp.engine.StopReason.Failed] (TEN-52), the one stop reason that
     *   comes with no stats to carry it: a failed decode never produced any.
     */
    suspend fun updateAssistantMessage(
        id: String,
        content: String,
        thinking: String? = null,
        stats: DecodeStats? = null,
        stopReason: StopReason? = stats?.stopReason,
    ) {
        dao.updateMessage(
            id = id,
            content = content,
            thinking = thinking,
            tokensGenerated = stats?.tokensGenerated,
            tokPerSec = stats?.tokPerSec,
            ttftMs = stats?.ttftMs,
            stopReason = stopReason?.name,
        )
    }

    /** Deletes [conversationId] and every message under it. No-op if it doesn't exist. */
    suspend fun deleteConversation(conversationId: String) = dao.deleteConversation(conversationId)

    /** Persists [enabled] as [conversationId]'s [ConversationEntity.enableThinking]. No-op if the conversation doesn't exist (e.g. deleted meanwhile). */
    suspend fun setEnableThinking(conversationId: String, enabled: Boolean) {
        dao.updateEnableThinking(conversationId, enabled)
    }

    /**
     * Persists [config] as [conversationId]'s [ConversationEntity.samplingJson] (TEN-35). Takes
     * effect on the next generation — nothing reloads the model, the config is read fresh when the
     * next [io.github.tensaiapp.engine.DecodeRequest] is built. No-op if the conversation
     * doesn't exist (e.g. deleted meanwhile).
     *
     * Only [SamplingJson]'s own subset is kept: [io.github.tensaiapp.engine.SamplingConfig.enableThinking]
     * belongs to [setEnableThinking] and its own column, and `reasoningBudget` is derived, never
     * stored — see [SamplingJson]'s KDoc.
     */
    suspend fun setSampling(conversationId: String, config: SamplingConfig) {
        dao.updateSamplingJson(conversationId, SamplingJson.encode(config))
    }

    /**
     * Drops [conversationId]'s sampling override, back to [SamplingConfig]'s defaults — stored as
     * `null` rather than as an encoded copy of the defaults, so a conversation that was never
     * customized and one that was reset are the same row, and both follow the defaults if those
     * ever move.
     *
     * Deliberately leaves [ConversationEntity.systemPrompt] alone. It is edited in the same sheet,
     * but it is the one setting here the user *wrote* rather than dialled, and there is no undo: a
     * button that resets four sliders should not also silently delete a page of prose.
     */
    suspend fun resetSampling(conversationId: String) {
        dao.updateSamplingJson(conversationId, null)
    }

    /**
     * The settings sheet's Reset: drops the sampling override and, when [resetThinking], turns
     * thinking back on, in one statement so neither write can be lost to the other. Leaves the
     * system prompt alone, same reason as [resetSampling].
     */
    suspend fun resetTuning(conversationId: String, resetThinking: Boolean) {
        if (resetThinking) dao.resetSamplingAndThinking(conversationId) else dao.updateSamplingJson(conversationId, null)
    }

    /**
     * Persists [prompt] as [conversationId]'s [ConversationEntity.systemPrompt], normalizing blank
     * to `null` — [toEngineMessages] treats the two identically, and storing `null` keeps "no
     * system prompt" a single state rather than two. No-op if the conversation doesn't exist.
     */
    suspend fun setSystemPrompt(conversationId: String, prompt: String) {
        dao.updateSystemPrompt(conversationId, prompt.ifBlank { null })
    }

    /** The conversation itself, or `null` if it doesn't exist (e.g. deleted meanwhile). */
    suspend fun getConversation(conversationId: String): ConversationEntity? = dao.getConversation(conversationId)

    /** Persists [enabled] as [conversationId]'s [ConversationEntity.toolsEnabled]. No-op if the conversation doesn't exist. */
    suspend fun setToolsEnabled(conversationId: String, enabled: Boolean) {
        dao.updateToolsEnabled(conversationId, enabled)
    }

    /** Stores [calls] on the assistant message [id], see [MessageEntity.toolCallsJson]. */
    suspend fun setToolCalls(id: String, calls: List<ToolCall>) {
        dao.updateToolCalls(id, calls.takeIf { it.isNotEmpty() }?.let(ToolCallsJson::encode))
    }

    /**
     * Records a finished tool call: the `tool` message the model will be shown next turn, and the
     * audit row. [assistantMessageId] is the reply that made the call.
     */
    suspend fun recordToolCall(conversationId: String, assistantMessageId: String, outcome: ToolCallOutcome) {
        dao.insertToolResult(
            toolResultMessage(conversationId, outcome),
            toolCallRow(assistantMessageId, outcome),
        )
    }

    private fun toolResultMessage(conversationId: String, outcome: ToolCallOutcome): MessageEntity {
        val call = outcome.call
        return MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = MessageRole.Tool.toStorageString(),
            content = outcome.output,
            thinking = null,
            toolCallsJson = null,
            tokensGenerated = null,
            tokPerSec = null,
            ttftMs = null,
            stopReason = null,
            createdAt = nextCreatedAt(),
            toolCallId = call.id,
            toolName = call.name,
        )
    }

    private fun toolCallRow(assistantMessageId: String, outcome: ToolCallOutcome): ToolCallEntity {
        val call = outcome.call
        return ToolCallEntity(
                id = UUID.randomUUID().toString(),
                messageId = assistantMessageId,
                callId = call.id,
                tool = call.name,
                tier = outcome.tier?.name,
            server = null,
            argsJson = call.argumentsJson,
            startedAt = outcome.startedAt,
            durationMs = outcome.durationMs,
            resultTokens = outcome.resultTokens,
            status = outcome.status.storageName,
            destination = outcome.destination,
    )
}

/** Persists [tokenCount] on [id], counted with [modelId], see [MessageEntity.tokenCount]. */
    suspend fun setTokenCount(id: String, tokenCount: Int, modelId: String) = dao.updateTokenCount(id, tokenCount, modelId)

    /** Deletes a single message by [id] — used by [io.github.tensaiapp.ui.screens.chat.ChatViewModel.regenerate] to drop the assistant reply being redone. */
    suspend fun deleteMessage(id: String) = dao.deleteMessageAndToolCalls(id)

    /**
     * Converts a conversation's stored history into the engine's [ChatMessage] format — [messages]
     * is expected in [observeMessages]'s chronological order. [systemPrompt], if non-blank, is
     * prepended as a [MessageRole.System] message. Thinking is dropped, except the one of the
     * assistant messages after the last user message (TEN-191): the current turn's reasoning, which the template
     * keeps for them and the KV cache holds, so the next pass does not restart it. An assistant message's stored tool calls and the `tool` messages that
     * answer them are replayed (TEN-172), so the next turn sees what the tools returned; arguments the
     * model wrote as invalid JSON are replayed as `{}`, see [ToolCall.replayable].
     *
     * An assistant turn with empty content and no tool call is left out (TEN-110), whatever its thinking: thinking
     * is not replayed once its turn is over, and a thinking-only row would otherwise render as an empty assistant turn.
     * Such a row is a stop before the answer (kept so the user sees it), a reply cut inside its
     * reasoning, or one a killed process left behind. None is something the assistant said, and an
     * empty assistant turn in the rendered prompt would only break strict templates and the prefix
     * the warm-up and session reuse (TEN-88) match against.
     * Dropping it can leave two user turns side by side, which are merged: see
     *
     * A call only stays in the history next to its result: see [withAnsweredCallsOnly].
     *
     * [memory], the saved facts (TEN-189), goes in front of the first user message, never in the system
     * message: see [io.github.tensaiapp.data.memory.MemoryContext].
     */
    fun toEngineMessages(messages: List<MessageEntity>, systemPrompt: String?, memory: String? = null): List<ChatMessage> {
        val answered = withAnsweredCallsOnly(messages)
        val lastUser = answered.indexOfLast { it.role == "user" }
        val currentTurn = if (lastUser < 0) emptySet() else answered.subList(lastUser + 1, answered.size).mapTo(HashSet()) { it.id }
        val history = answered
            .filterNot { it.role == "assistant" && it.content.isEmpty() && it.toolCallsJson.isNullOrBlank() }
            .map {
                ChatMessage(
                    role = it.role.toEngineRole(),
                    content = it.content,
                    toolCalls = ToolCallsJson.decode(it.toolCallsJson).takeIf { calls -> calls.isNotEmpty() }?.map { c -> c.replayable() },
                    toolCallId = it.toolCallId,
                    reasoning = it.thinking?.takeIf { t -> it.role == "assistant" && it.id in currentTurn && t.isNotBlank() },
                )
            }
            .mergeAdjacentUserTurns()
            .let { MemoryContext.prepend(it, memory) }
        return if (!systemPrompt.isNullOrBlank()) {
            listOf(ChatMessage(role = MessageRole.System, content = systemPrompt, toolCalls = null)) + history
        } else {
            history
        }
    }

    private suspend fun insertMessage(conversationId: String, role: MessageRole, content: String): String {
        val id = UUID.randomUUID().toString()
        dao.insertMessage(
            MessageEntity(
                id = id,
                conversationId = conversationId,
                role = role.toStorageString(),
                content = content,
                thinking = null,
                toolCallsJson = null,
                tokensGenerated = null,
                tokPerSec = null,
                ttftMs = null,
                stopReason = null,
                createdAt = nextCreatedAt(),
            ),
        )
        return id
    }

    /**
     * Wall clock, but never twice the same value: messages sort by [MessageEntity.createdAt] alone, and a
     * reply, its tool results and the next reply can land in one millisecond.
     */
    private fun nextCreatedAt(): Long = lastCreatedAt.updateAndGet { maxOf(System.currentTimeMillis(), it + 1) }

    private val lastCreatedAt = AtomicLong(0)
}

/**
 * Repairs what a stop or a killed process can leave: an assistant message whose calls got no `tool`
 * message right after it (a template would be handed calls with no results), or a `tool` message with
 * no call. Per assistant message, only the calls answered by the `tool` messages that directly follow
 * it are kept, with those results; the rest is dropped. Nothing is written back, the stored rows stay. The chat reads
 * the same projection to rebuild what the agent's gate saw, see [io.github.tensaiapp.agent.ConversationTrust].
 */
internal fun withAnsweredCallsOnly(messages: List<MessageEntity>): List<MessageEntity> {
    val out = ArrayList<MessageEntity>(messages.size)
    var i = 0
    while (i < messages.size) {
        val m = messages[i]
        if (m.role == "assistant" && !m.toolCallsJson.isNullOrBlank()) {
            var j = i + 1
            while (j < messages.size && messages[j].role == "tool") j++
            val results = messages.subList(i + 1, j)
            val calls = ToolCallsJson.decode(m.toolCallsJson)
            val answered = calls.filter { c -> results.any { it.toolCallId == c.id } }
            out += m.copy(
                toolCallsJson = when (answered.size) {
                    calls.size -> m.toolCallsJson
                    0 -> null
                    else -> ToolCallsJson.encode(answered)
                },
            )
            out += results.filter { r -> answered.any { it.id == r.toolCallId } }
            i = j
        } else {
            // A `tool` message reaching here follows no assistant message with calls: an orphan.
            if (m.role != "tool") out += m
            i++
        }
    }
    return out
}

/**
 * Joins runs of adjacent user messages into one, with a blank line between, in order. Strict
 * templates (Gemma's raises "Conversation roles must alternate") reject two user turns in a row,
 * and dropping an empty assistant turn (see [ChatRepository.toEngineMessages]) is what creates
 * them. Nothing else is merged: system and assistant turns are untouched.
 */
private fun List<ChatMessage>.mergeAdjacentUserTurns(): List<ChatMessage> {
    val out = ArrayList<ChatMessage>(size)
    for (m in this) {
        val prev = out.lastOrNull()
        if (m.role == MessageRole.User && prev?.role == MessageRole.User) {
            out[out.lastIndex] = prev.copy(content = prev.content + "\n\n" + m.content)
        } else {
            out += m
        }
    }
    return out
}

private fun truncateTitle(content: String): String {
    val trimmed = content.trim().replace('\n', ' ')
    return if (trimmed.length <= TITLE_MAX_LENGTH) trimmed else trimmed.take(TITLE_MAX_LENGTH).trimEnd() + "…"
}

private fun MessageRole.toStorageString(): String = when (this) {
    MessageRole.System -> "system"
    MessageRole.User -> "user"
    MessageRole.Assistant -> "assistant"
    MessageRole.Tool -> "tool"
}

internal fun String.toEngineRole(): MessageRole = when (this) {
    "system" -> MessageRole.System
    "user" -> MessageRole.User
    "assistant" -> MessageRole.Assistant
    "tool" -> MessageRole.Tool
    else -> throw IllegalArgumentException("Unknown stored message role: $this")
}
