package io.github.hokanosekai.tensai.data.chat

import io.github.hokanosekai.tensai.engine.ChatMessage
import io.github.hokanosekai.tensai.engine.DecodeStats
import io.github.hokanosekai.tensai.engine.MessageRole
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
     * and returns its id.
     */
    suspend fun createConversation(modelId: String?): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.insertConversation(
            ConversationEntity(
                id = id,
                title = "New chat",
                modelId = modelId,
                samplingJson = null,
                systemPrompt = null,
                enableThinking = true,
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
     */
    suspend fun updateAssistantMessage(
        id: String,
        content: String,
        thinking: String? = null,
        stats: DecodeStats? = null,
    ) {
        dao.updateMessage(
            id = id,
            content = content,
            thinking = thinking,
            tokensGenerated = stats?.tokensGenerated,
            tokPerSec = stats?.tokPerSec,
            ttftMs = stats?.ttftMs,
        )
    }

    /** Deletes [conversationId] and every message under it. No-op if it doesn't exist. */
    suspend fun deleteConversation(conversationId: String) = dao.deleteConversation(conversationId)

    /** Persists [enabled] as [conversationId]'s [ConversationEntity.enableThinking]. No-op if the conversation doesn't exist (e.g. deleted meanwhile). */
    suspend fun setEnableThinking(conversationId: String, enabled: Boolean) {
        val conversation = dao.getConversation(conversationId) ?: return
        dao.updateConversation(conversation.copy(enableThinking = enabled))
    }

    /** The conversation itself, or `null` if it doesn't exist (e.g. deleted meanwhile). */
    suspend fun getConversation(conversationId: String): ConversationEntity? = dao.getConversation(conversationId)

    /** Deletes a single message by [id] — used by [io.github.hokanosekai.tensai.ui.screens.chat.ChatViewModel.regenerate] to drop the assistant reply being redone. */
    suspend fun deleteMessage(id: String) = dao.deleteMessage(id)

    /**
     * Converts a conversation's stored history into the engine's [ChatMessage] format — [messages]
     * is expected in [observeMessages]'s chronological order. [systemPrompt], if non-blank, is
     * prepended as a [MessageRole.System] message. Thinking content is dropped (the engine only
     * takes rendered content) and every [ChatMessage.toolCalls] comes out `null` — no message with
     * a stored tool call exists yet, see [MessageEntity]'s KDoc.
     */
    fun toEngineMessages(messages: List<MessageEntity>, systemPrompt: String?): List<ChatMessage> {
        val history = messages.map { ChatMessage(role = it.role.toEngineRole(), content = it.content, toolCalls = null) }
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
                createdAt = System.currentTimeMillis(),
            ),
        )
        return id
    }
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
