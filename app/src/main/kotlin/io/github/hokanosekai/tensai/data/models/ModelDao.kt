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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(model: ModelEntity)

    @Delete
    suspend fun delete(model: ModelEntity)
}
