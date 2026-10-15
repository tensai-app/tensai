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
     * Rows whose header-derived data (KV-cache shape and capabilities) was produced by anything other
     * than [kvShapeVersion]: the backfill re-reads them for both. A migration may reset the version
     * to 0 to refresh capabilities. See
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
            "kvShapeVersion = :kvShapeVersion, supportsThinking = :supportsThinking, " +
            "supportsTools = :supportsTools WHERE id = :id",
    )
    suspend fun updateKvCacheShape(
        id: String,
        kvFullElementsPerToken: Long,
        kvSwaElementsPerToken: Long,
        slidingWindow: Long,
        kvShapeVersion: Long,
        supportsThinking: Boolean,
        supportsTools: Boolean,
    )

    /**
     * Sets (or, with null, clears) [id]'s own context setting, see [ModelEntity.contextOverride]. Its own
     * statement rather than a read-modify-write of the row, so it cannot undo a concurrent
     * [updateKvCacheShape].
     */
    @Query("UPDATE models SET contextOverride = :contextOverride WHERE id = :id")
    suspend fun setContextOverride(id: String, contextOverride: String?)

    /**
     * REPLACE, not ABORT: a re-import of a missing file relinks its row by inserting it again under
     * the same id, and that has to be one statement (one transaction). A delete followed by an
     * insert could be interrupted between the two and lose the row. Ids are UUIDs, so a fresh
     * import never collides and behaves as before. No foreign key points at `models`, so REPLACE
     * cannot cascade into anything.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: ModelEntity)

    @Delete
    suspend fun delete(model: ModelEntity)
}
