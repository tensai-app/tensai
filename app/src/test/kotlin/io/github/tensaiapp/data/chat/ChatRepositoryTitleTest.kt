package io.github.tensaiapp.data.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for TEN-105: [ChatRepository] stores no display text for an untitled conversation.
 * A new row's title is empty (the UI labels it in the app language, see `conversationLabel`), and
 * the first user message still names it, since that rule keys on the message count, not on the
 * placeholder.
 */
class ChatRepositoryTitleTest {

    private val dao = FakeChatDao()
    private val repository = ChatRepository(dao)

    @Test
    fun newConversationStoresAnEmptyTitleNotAnEnglishPlaceholder() = runBlocking {
        val id = repository.createConversation(modelId = null)

        assertEquals("", repository.getConversation(id)!!.title)
    }

    @Test
    fun firstUserMessageStillTitlesTheConversation() = runBlocking {
        val id = repository.createConversation(modelId = null)

        repository.appendUserMessage(id, "  How do I parse a GGUF header?\n")

        assertEquals("How do I parse a GGUF header?", repository.getConversation(id)!!.title)
    }

    @Test
    fun laterMessagesKeepTheFirstTitle() = runBlocking {
        val id = repository.createConversation(modelId = null)
        repository.appendUserMessage(id, "first question")

        repository.appendUserMessage(id, "second question")

        assertEquals("first question", repository.getConversation(id)!!.title)
    }

    /** In-memory [ChatDao] covering what [ChatRepository]'s title logic touches. */
    internal class FakeChatDao : ChatDao {
        /** Makes every read a suspension point, so a get-copy-update from two coroutines interleaves for real. */
        var yieldOnRead = false

        private val conversations = mutableMapOf<String, ConversationEntity>()
        private val messages = mutableMapOf<String, MutableList<MessageEntity>>()
        private val conversationsFlow = MutableStateFlow<List<ConversationEntity>>(emptyList())

        override fun observeConversations(): Flow<List<ConversationEntity>> = conversationsFlow

        override fun observeToolCalls(conversationId: String): Flow<List<ToolCallEntity>> = MutableStateFlow(emptyList())

        override suspend fun toolActivity(conversationId: String): List<ToolActivityRow> = emptyList()

        override fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
            MutableStateFlow(messages[conversationId].orEmpty().toList())

        override suspend fun getConversation(id: String): ConversationEntity? {
            val read = conversations[id]
            if (yieldOnRead) kotlinx.coroutines.yield()
            return read
        }

        override fun observeConversation(id: String): Flow<ConversationEntity?> =
            conversationsFlow.map { list -> list.find { it.id == id } }

        override suspend fun messageCount(conversationId: String): Int = messages[conversationId]?.size ?: 0

        override suspend fun insertConversation(conversation: ConversationEntity) {
            conversations[conversation.id] = conversation
            conversationsFlow.value = conversations.values.sortedByDescending { it.updatedAt }
        }

        override suspend fun updateConversation(conversation: ConversationEntity) = insertConversation(conversation)

        override suspend fun updateSamplingJson(id: String, samplingJson: String?) = patch(id) { it.copy(samplingJson = samplingJson) }

        override suspend fun updateSystemPrompt(id: String, systemPrompt: String?) = patch(id) { it.copy(systemPrompt = systemPrompt) }

        override suspend fun updateEnableThinking(id: String, enableThinking: Boolean) = patch(id) { it.copy(enableThinking = enableThinking) }

        override suspend fun resetSamplingAndThinking(id: String) = patch(id) { it.copy(samplingJson = null, enableThinking = true) }

        /** One atomic column update, like the SQL it stands in for: no suspension point between read and write. */
        private fun patch(id: String, change: (ConversationEntity) -> ConversationEntity) {
            val current = conversations[id] ?: return
            conversations[id] = change(current)
            conversationsFlow.value = conversations.values.sortedByDescending { it.updatedAt }
        }

        override suspend fun insertMessage(message: MessageEntity) {
            messages.getOrPut(message.conversationId) { mutableListOf() }.add(message)
        }

        override suspend fun updateMessage(
            id: String,
            content: String,
            thinking: String?,
            tokensGenerated: Int?,
            tokPerSec: Float?,
            ttftMs: Long?,
            stopReason: String?,
        ) = Unit

        override suspend fun updateToolsEnabled(id: String, toolsEnabled: Boolean) = patch(id) { it.copy(toolsEnabled = toolsEnabled) }

        val toolCalls = mutableListOf<ToolCallEntity>()

        override suspend fun updateToolCalls(id: String, toolCallsJson: String?) {
            for (list in messages.values) {
                val index = list.indexOfFirst { it.id == id }
                if (index >= 0) list[index] = list[index].copy(toolCallsJson = toolCallsJson)
            }
        }

        override suspend fun insertToolCall(toolCall: ToolCallEntity) {
            toolCalls += toolCall
        }

        override suspend fun toolCallCount(messageId: String, callId: String): Int =
            toolCalls.count { it.messageId == messageId && it.callId == callId }

        override suspend fun deleteToolCallsForMessage(messageId: String) {
            toolCalls.removeAll { it.messageId == messageId }
        }

        override suspend fun deleteToolCallsForConversation(conversationId: String) {
            val ids = messages[conversationId].orEmpty().map { it.id }.toSet()
            toolCalls.removeAll { it.messageId in ids }
        }

        override suspend fun updateTokenCount(id: String, tokenCount: Int, modelId: String) = Unit

        override suspend fun deleteMessagesForConversation(conversationId: String) {
            messages.remove(conversationId)
        }

        override suspend fun deleteMessage(id: String) {
            messages.values.forEach { list -> list.removeIf { it.id == id } }
        }

        override suspend fun deleteConversationById(id: String) {
            conversations.remove(id)
            conversationsFlow.value = conversations.values.sortedByDescending { it.updatedAt }
        }

        override suspend fun conversationCount(): Int = conversations.size
    }
}
