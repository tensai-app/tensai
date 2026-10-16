package io.github.hokanosekai.tensai.data.chat

import androidx.room.Dao
import androidx.room.Query

/** One conversation that matched a `search_chats` query, with its most recent matching message. */
data class ChatSearchHit(
    val conversationId: String,
    val title: String,
    val createdAt: Long,
    val content: String,
)

/**
 * Read-only search for the `search_chats` tool (TEN-175), apart from [ChatDao] so that adding it does
 * not touch every implementation of that interface.
 *
 * LIKE rather than FTS: no FTS table exists, and a personal chat history is small enough that a scan
 * is fast. `%term%` cannot use an index anyway; what bounds the cost is the `role` filter and the
 * `LIMIT`. Switch to an FTS table if a history ever grows large enough to notice.
 */
@Dao
interface ChatSearchDao {

    /**
     * One row per conversation (its newest matching message, SQLite takes the bare columns from the row
     * `MAX` picked), newest first. Tool messages are left out: they are JSON the user never wrote or read.
     * [pattern] comes from [ChatSearch.likePattern]. [excludeConversationId] is the conversation asking, `null` to search all.
     */
    @Query(
        "SELECT m.conversationId AS conversationId, c.title AS title, MAX(m.createdAt) AS createdAt, m.content AS content " +
            "FROM messages m JOIN conversations c ON c.id = m.conversationId " +
            "WHERE m.role IN ('user', 'assistant') AND (:excludeConversationId IS NULL OR m.conversationId != :excludeConversationId) " +
            "AND (m.content LIKE :pattern ESCAPE '\\' OR c.title LIKE :pattern ESCAPE '\\') " +
            "GROUP BY m.conversationId ORDER BY createdAt DESC LIMIT :limit",
    )
    suspend fun search(pattern: String, limit: Int, excludeConversationId: String?): List<ChatSearchHit>
}
