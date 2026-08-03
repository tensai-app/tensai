package io.github.hokanosekai.tensai.data.models

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** App-wide Room database — currently just the [ModelEntity] table. */
@Database(entities = [ModelEntity::class], version = 1, exportSchema = false)
abstract class TensaiDb : RoomDatabase() {

    abstract fun modelDao(): ModelDao

    companion object {
        private const val DB_NAME = "tensai.db"

        fun build(context: Context): TensaiDb =
            Room.databaseBuilder(context.applicationContext, TensaiDb::class.java, DB_NAME).build()
    }
}
