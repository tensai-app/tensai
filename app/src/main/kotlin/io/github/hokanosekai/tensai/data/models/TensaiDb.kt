package io.github.hokanosekai.tensai.data.models

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * App-wide Room database — currently just the [ModelEntity] table.
 *
 * Version 2 added [ModelEntity.embeddingLength] and [ModelEntity.blockCount] (TEN-29, for
 * [io.github.hokanosekai.tensai.compat.CompatibilityChecker]'s KV-cache estimate). No migration
 * path is defined — the app has no released users yet, so
 * [androidx.room.RoomDatabase.Builder.fallbackToDestructiveMigration] (re-import is cheap; models
 * are just files the user already has) is preferable to writing a migration nobody depends on.
 * Revisit once this schema ships to real users.
 */
@Database(entities = [ModelEntity::class], version = 2, exportSchema = false)
abstract class TensaiDb : RoomDatabase() {

    abstract fun modelDao(): ModelDao

    companion object {
        private const val DB_NAME = "tensai.db"

        fun build(context: Context): TensaiDb =
            Room.databaseBuilder(context.applicationContext, TensaiDb::class.java, DB_NAME)
                .fallbackToDestructiveMigration()
                .build()
    }
}
