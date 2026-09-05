package io.github.hokanosekai.tensai.data.models

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {

    @Query("SELECT * FROM models ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun getById(id: String): ModelEntity?

    /**
     * Rows whose KV-cache shape is still the migration's `0` sentinel — see
     * [TensaiDbMigrations.MIGRATION_6_7] and
     * [ModelRepository.backfillKvCacheShape]. Only [ModelEntity.kvFullElementsPerToken] is tested:
     * the other two columns are legitimately `0` for a model without sliding-window layers.
     */
    @Query("SELECT * FROM models WHERE kvFullElementsPerToken = 0")
    suspend fun withUnknownKvCacheShape(): List<ModelEntity>

    @Query(
        "UPDATE models SET kvFullElementsPerToken = :kvFullElementsPerToken, " +
            "kvSwaElementsPerToken = :kvSwaElementsPerToken, slidingWindow = :slidingWindow WHERE id = :id",
    )
    suspend fun updateKvCacheShape(
        id: String,
        kvFullElementsPerToken: Long,
        kvSwaElementsPerToken: Long,
        slidingWindow: Long,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(model: ModelEntity)

    @Delete
    suspend fun delete(model: ModelEntity)
}
