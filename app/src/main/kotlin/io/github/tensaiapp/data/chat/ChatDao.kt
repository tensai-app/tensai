package io.github.tensaiapp.data.chat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query(
        "SELECT * FROM tool_calls WHERE messageId IN (SELECT id FROM messages WHERE conversationId = :conversationId) " +
            "ORDER BY startedAt ASC",
    )
    fun observeToolCalls(conversationId: String): Flow<List<ToolCallEntity>>

    /** One row per tool of a conversation, every status counted: the "Agent activity" section (TEN-162). */
    @Query(
        "SELECT tool, COUNT(*) AS calls, SUM(resultTokens) AS resultTokens, SUM(durationMs) AS durationMs " +
            "FROM tool_calls WHERE messageId IN (SELECT id FROM messages WHERE conversationId = :conversationId) " +
            "GROUP BY tool ORDER BY calls DESC, tool ASC",
    )
    suspend fun toolActivity(conversationId: String): List<ToolActivityRow>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: String): ConversationEntity?

    /** Reactive counterpart of [getConversation] — see [ChatRepository.observeConversation]. */
    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeConversation(id: String): Flow<ConversationEntity?>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun messageCount(conversationId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConversation(conversation: ConversationEntity)

    /*
     * The message path's writes to the conversation row: its own columns only, never the whole row.
     * A reply starts while the settings sheet is open, and a row read before a setting is saved and
     * written back after would undo it (TEN-179). No-ops when the conversation does not exist.
     */

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateUpdatedAt(id: String, updatedAt: Long)

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

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

    @Query("UPDATE conversations SET toolsEnabled = :toolsEnabled WHERE id = :id")
    suspend fun updateToolsEnabled(id: String, toolsEnabled: Boolean)

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

    /** Sets a message's [MessageEntity.sendState], `null` once it goes out. */
    @Query("UPDATE messages SET sendState = :sendState WHERE id = :id")
    suspend fun updateSendState(id: String, sendState: String?)

    /** Moves every message in the [from] send state to [to], see [ChatRepository.markWaitingNotSent]. */
    @Query("UPDATE messages SET sendState = :to WHERE sendState = :from")
    suspend fun replaceSendState(from: String, to: String)

    /** Stores the calls an assistant message made, see [MessageEntity.toolCallsJson]. */
    @Query("UPDATE messages SET toolCallsJson = :toolCallsJson WHERE id = :id")
    suspend fun updateToolCalls(id: String, toolCallsJson: String?)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertToolCall(toolCall: ToolCallEntity)

    @Query("SELECT COUNT(*) FROM tool_calls WHERE messageId = :messageId AND callId = :callId")
    suspend fun toolCallCount(messageId: String, callId: String): Int

    /**
     * Writes a call's `tool` message and audit row together, or nothing when the reply already has a
     * result for that call id (a stop's cleanup racing the result it was cleaning up after). `true` when written.
     */
    @Transaction
    suspend fun insertToolResult(message: MessageEntity, toolCall: ToolCallEntity): Boolean {
        if (toolCallCount(toolCall.messageId, toolCall.callId) > 0) return false
        insertMessage(message)
        insertToolCall(toolCall)
        return true
    }

    @Query("DELETE FROM tool_calls WHERE messageId = :messageId")
    suspend fun deleteToolCallsForMessage(messageId: String)

    @Query("DELETE FROM tool_calls WHERE messageId IN (SELECT id FROM messages WHERE conversationId = :conversationId)")
    suspend fun deleteToolCallsForConversation(conversationId: String)

    /** Records [tokenCount] for [id], counted with [modelId], see [MessageEntity.tokenCount]. */
    @Query("UPDATE messages SET tokenCount = :tokenCount, tokenCountModelId = :modelId WHERE id = :id")
    suspend fun updateTokenCount(id: String, tokenCount: Int, modelId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)

    /** Deletes a single message by [id] — see [ChatRepository.deleteMessage]. */
    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: String)

    /** Deletes a message and the audit rows of the calls it made, see [ChatRepository.deleteMessage]. */
    @Transaction
    suspend fun deleteMessageAndToolCalls(id: String) {
        deleteToolCallsForMessage(id)
        deleteMessage(id)
    }

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversationById(id: String)

    /** Deletes [id] and every message under it — cascaded manually, there is no Room FK. */
    @Transaction
    suspend fun deleteConversation(id: String) {
        deleteToolCallsForConversation(id)
        deleteMessagesForConversation(id)
        deleteConversationById(id)
    }

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun conversationCount(): Int
}
