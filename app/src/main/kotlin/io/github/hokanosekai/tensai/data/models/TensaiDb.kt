package io.github.hokanosekai.tensai.data.models

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.hokanosekai.tensai.data.chat.ChatDao
import io.github.hokanosekai.tensai.data.chat.ConversationEntity
import io.github.hokanosekai.tensai.data.chat.MessageEntity

/**
 * App-wide Room database — [ModelEntity] plus the chat tables ([ConversationEntity], [MessageEntity]).
 *
 * Version 2 added [ModelEntity.embeddingLength] and [ModelEntity.blockCount] (TEN-29, for
 * [io.github.hokanosekai.tensai.compat.CompatibilityChecker]'s KV-cache estimate). Version 3 added
 * the chat tables (TEN-31). Version 4 added [ConversationEntity.enableThinking] (TEN-33). No
 * migration path is defined for any of these — the app has no released users yet, so
 * [androidx.room.RoomDatabase.Builder.fallbackToDestructiveMigration] (re-import is cheap; models
 * are just files the user already has, and there's no chat history worth preserving pre-release)
 * is preferable to writing migrations nobody depends on. Revisit once this schema ships to real
 * users.
 */
@Database(
    entities = [ModelEntity::class, ConversationEntity::class, MessageEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class TensaiDb : RoomDatabase() {

    abstract fun modelDao(): ModelDao

    abstract fun chatDao(): ChatDao

    companion object {
        private const val DB_NAME = "tensai.db"

        fun build(context: Context): TensaiDb =
            Room.databaseBuilder(context.applicationContext, TensaiDb::class.java, DB_NAME)
                .fallbackToDestructiveMigration()
                .build()
    }
}
