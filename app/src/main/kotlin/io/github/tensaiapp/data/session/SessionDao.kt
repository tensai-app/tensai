package io.github.tensaiapp.data.session

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Query("SELECT * FROM sessions WHERE conversationId = :conversationId")
    suspend fun getByConversation(conversationId: String): SessionEntity?

    /** All sessions, most recently accessed first — feeds a future Settings > Storage list. */
    @Query("SELECT * FROM sessions ORDER BY lastAccessedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    /** Every persisted session, in no particular order — [SessionStoragePolicy] does its own sorting. */
    @Query("SELECT * FROM sessions")
    suspend fun allSessions(): List<SessionEntity>

    /** [conversationId] is the primary key, so this both creates a conversation's first session and overwrites its later ones. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE conversationId = :conversationId")
    suspend fun delete(conversationId: String)

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}
