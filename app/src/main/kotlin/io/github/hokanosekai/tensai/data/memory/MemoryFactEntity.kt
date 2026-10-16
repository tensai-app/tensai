package io.github.hokanosekai.tensai.data.memory

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One thing tensai was asked to remember about the user (TEN-176), written and read through the
 * `memory` tool and listed in Settings > Memory.
 *
 * @param id small auto-incremented integer rather than a UUID: the model has to write it back to
 *   `forget`, and a 1.7B model copies "12" reliably where it garbles 36 characters.
 * @param sourceConversationId the conversation the fact came from, `null` when none was active. Not a
 *   foreign key: deleting a conversation does not forget what was learned in it.
 */
@Entity(tableName = "memory_facts")
data class MemoryFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long,
    val sourceConversationId: String? = null,
)

@Dao
interface MemoryDao {

    @Insert
    suspend fun insert(fact: MemoryFactEntity): Long

    /** Newest first, for the tool's `list` (capped) and the Settings screen. */
    @Query("SELECT * FROM memory_facts ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<MemoryFactEntity>>

    @Query("SELECT COUNT(*) FROM memory_facts")
    suspend fun count(): Int

    /** Live count, for the Settings row. */
    @Query("SELECT COUNT(*) FROM memory_facts")
    fun observeCount(): Flow<Int>

    /** The id of a fact with exactly this text, so a repeated `remember` does not stack copies. */
    @Query("SELECT id FROM memory_facts WHERE text = :text COLLATE NOCASE LIMIT 1")
    suspend fun findIdByText(text: String): Long?

    /** Rows deleted: 0 when [id] does not exist. */
    @Query("DELETE FROM memory_facts WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("DELETE FROM memory_facts")
    suspend fun deleteAll()
}
