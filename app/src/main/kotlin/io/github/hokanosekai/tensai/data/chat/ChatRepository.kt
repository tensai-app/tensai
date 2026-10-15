package io.github.hokanosekai.tensai.data.chat

import io.github.hokanosekai.tensai.engine.ChatMessage
import io.github.hokanosekai.tensai.engine.DecodeStats
import io.github.hokanosekai.tensai.engine.MessageRole
import io.github.hokanosekai.tensai.engine.SamplingConfig
import io.github.hokanosekai.tensai.engine.StopReason
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Max length of a [ConversationEntity.title] auto-derived from the first user message. */
private const val TITLE_MAX_LENGTH = 40

/**
 * Owns [ConversationEntity] and [MessageEntity] rows — the chat history persisted across process
 * death, and the source [toEngineMessages] converts into the format
 * [io.github.hokanosekai.tensai.runtime.InferenceQueue.generate] expects.
 */
class ChatRepository(private val dao: ChatDao) {

    /** All conversations, most recently updated first — feeds the drawer's "Recents" list. */
    fun observeConversations(): Flow<List<ConversationEntity>> = dao.observeConversations()

    /** A single conversation's messages, oldest first. */
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> = dao.observeMessages(conversationId)

    /** A single conversation, reactively — used by [io.github.hokanosekai.tensai.ui.screens.chat.ChatViewModel.enableThinking] to reflect [ConversationEntity.enableThinking] as it's toggled. */
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
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.insertConversation(
            ConversationEntity(
                id = id,
                title = "",
                modelId = modelId,
                samplingJson = samplingJson,
                systemPrompt = systemPrompt?.ifBlank { null },
                enableThinking = enableThinking,
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
     *   [io.github.hokanosekai.tensai.engine.DecodeEvent.Done] reports. Passed explicitly for
     *   [io.github.hokanosekai.tensai.engine.StopReason.Failed] (TEN-52), the one stop reason that
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
     * next [io.github.hokanosekai.tensai.engine.DecodeRequest] is built. No-op if the conversation
     * doesn't exist (e.g. deleted meanwhile).
     *
     * Only [SamplingJson]'s own subset is kept: [io.github.hokanosekai.tensai.engine.SamplingConfig.enableThinking]
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

    /** Persists [tokenCount] on [id], counted with [modelId], see [MessageEntity.tokenCount]. */
    suspend fun setTokenCount(id: String, tokenCount: Int, modelId: String) = dao.updateTokenCount(id, tokenCount, modelId)

    /** Deletes a single message by [id] — used by [io.github.hokanosekai.tensai.ui.screens.chat.ChatViewModel.regenerate] to drop the assistant reply being redone. */
    suspend fun deleteMessage(id: String) = dao.deleteMessage(id)

    /**
     * Converts a conversation's stored history into the engine's [ChatMessage] format — [messages]
     * is expected in [observeMessages]'s chronological order. [systemPrompt], if non-blank, is
     * prepended as a [MessageRole.System] message. Thinking content is dropped (the engine only
     * takes rendered content) and every [ChatMessage.toolCalls] comes out `null` — no message with
     * a stored tool call exists yet, see [MessageEntity]'s KDoc.
     *
     * An assistant turn with empty content is left out (TEN-110), whatever its thinking: thinking
     * is never replayed, so a thinking-only row would otherwise render as an empty assistant turn.
     * Such a row is a stop before the answer (kept so the user sees it), a reply cut inside its
     * reasoning, or one a killed process left behind. None is something the assistant said, and an
     * empty assistant turn in the rendered prompt would only break strict templates and the prefix
     * the warm-up and session reuse (TEN-88) match against.
     * Dropping it can leave two user turns side by side, which are merged: see
     * [mergeAdjacentUserTurns].
     */
    fun toEngineMessages(messages: List<MessageEntity>, systemPrompt: String?): List<ChatMessage> {
        val history = messages
            .filterNot { it.role == "assistant" && it.content.isEmpty() }
            .map { ChatMessage(role = it.role.toEngineRole(), content = it.content, toolCalls = null) }
            .mergeAdjacentUserTurns()
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
                createdAt = System.currentTimeMillis(),
            ),
        )
        return id
    }
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

private fun String.toEngineRole(): MessageRole = when (this) {
    "system" -> MessageRole.System
    "user" -> MessageRole.User
    "assistant" -> MessageRole.Assistant
    "tool" -> MessageRole.Tool
    else -> throw IllegalArgumentException("Unknown stored message role: $this")
}
