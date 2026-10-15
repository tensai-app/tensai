package io.github.hokanosekai.tensai.data.chat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: String): ConversationEntity?

    /** Reactive counterpart of [getConversation] — see [ChatRepository.observeConversation]. */
    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeConversation(id: String): Flow<ConversationEntity?>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun messageCount(conversationId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    /*
     * Per-column updates for the settings sheet. Each is one SQL statement, so two settings written
     * from concurrent coroutines cannot overwrite each other the way a get-copy-update of the whole
     * row can (the second copy would carry the first read's stale value of the other column). All
     * are no-ops when the conversation does not exist.
     */

    @Query("UPDATE conversations SET samplingJson = :samplingJson WHERE id = :id")
    suspend fun updateSamplingJson(id: String, samplingJson: String?)

    @Query("UPDATE conversations SET systemPrompt = :systemPrompt WHERE id = :id")
    suspend fun updateSystemPrompt(id: String, systemPrompt: String?)

    @Query("UPDATE conversations SET enableThinking = :enableThinking WHERE id = :id")
    suspend fun updateEnableThinking(id: String, enableThinking: Boolean)

    /** Sampling override dropped and thinking back on, atomically: the sheet's Reset. */
    @Query("UPDATE conversations SET samplingJson = NULL, enableThinking = 1 WHERE id = :id")
    suspend fun resetSamplingAndThinking(id: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessage(message: MessageEntity)

    /**
     * Updates a message's mutable fields in place, by [id] — used to grow a streaming assistant
     * message's [MessageEntity.content] token by token (see [ChatRepository.startAssistantMessage])
     * without a read-modify-write round trip on every tick.
     */
    @Query(
        "UPDATE messages SET content = :content, thinking = :thinking, tokensGenerated = :tokensGenerated, " +
            "tokPerSec = :tokPerSec, ttftMs = :ttftMs, stopReason = :stopReason WHERE id = :id",
    )
    suspend fun updateMessage(
        id: String,
        content: String,
        thinking: String?,
        tokensGenerated: Int?,
        tokPerSec: Float?,
        ttftMs: Long?,
        stopReason: String?,
    )

    /** Records [tokenCount] for [id], counted with [modelId], see [MessageEntity.tokenCount]. */
    @Query("UPDATE messages SET tokenCount = :tokenCount, tokenCountModelId = :modelId WHERE id = :id")
    suspend fun updateTokenCount(id: String, tokenCount: Int, modelId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)

    /** Deletes a single message by [id] — see [ChatRepository.deleteMessage]. */
    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversationById(id: String)

    /** Deletes [id] and every message under it — cascaded manually, there is no Room FK. */
    @Transaction
    suspend fun deleteConversation(id: String) {
        deleteMessagesForConversation(id)
        deleteConversationById(id)
    }

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun conversationCount(): Int
}
