package io.github.hokanosekai.tensai.data.chat

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
    private class FakeChatDao : ChatDao {
        private val conversations = mutableMapOf<String, ConversationEntity>()
        private val messages = mutableMapOf<String, MutableList<MessageEntity>>()
        private val conversationsFlow = MutableStateFlow<List<ConversationEntity>>(emptyList())

        override fun observeConversations(): Flow<List<ConversationEntity>> = conversationsFlow

        override fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
            MutableStateFlow(messages[conversationId].orEmpty().toList())

        override suspend fun getConversation(id: String): ConversationEntity? = conversations[id]

        override fun observeConversation(id: String): Flow<ConversationEntity?> =
            conversationsFlow.map { list -> list.find { it.id == id } }

        override suspend fun messageCount(conversationId: String): Int = messages[conversationId]?.size ?: 0

        override suspend fun insertConversation(conversation: ConversationEntity) {
            conversations[conversation.id] = conversation
            conversationsFlow.value = conversations.values.sortedByDescending { it.updatedAt }
        }

        override suspend fun updateConversation(conversation: ConversationEntity) = insertConversation(conversation)

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
