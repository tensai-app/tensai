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
     * Rows whose KV-cache shape was derived by anything other than [kvShapeVersion] — see
     * [KvCacheShapeBackfill], which is the only caller, and [TensaiDbMigrations.MIGRATION_7_8] for
     * where the pre-TEN-72 rows' `0` comes from.
     *
     * `!=` rather than `<`: the version identifies a formula, it does not rank one. A submodule
     * pinned back to an older llama.kt is as much a mismatch as a newer one, and either way the
     * stored numbers were not produced by the code that will be sizing the load.
     *
     * This replaced a `WHERE kvFullElementsPerToken = 0` test, which could only find rows that had
     * never been derived at all. A row derived by a *superseded* formula carries a perfectly
     * ordinary non-zero number and was invisible to it — the whole point of TEN-72.
     */
    @Query("SELECT * FROM models WHERE kvShapeVersion != :kvShapeVersion")
    suspend fun withStaleKvCacheShape(kvShapeVersion: Long): List<ModelEntity>

    @Query(
        "UPDATE models SET kvFullElementsPerToken = :kvFullElementsPerToken, " +
            "kvSwaElementsPerToken = :kvSwaElementsPerToken, slidingWindow = :slidingWindow, " +
            "kvShapeVersion = :kvShapeVersion WHERE id = :id",
    )
    suspend fun updateKvCacheShape(
        id: String,
        kvFullElementsPerToken: Long,
        kvSwaElementsPerToken: Long,
        slidingWindow: Long,
        kvShapeVersion: Long,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(model: ModelEntity)

    @Delete
    suspend fun delete(model: ModelEntity)
}
