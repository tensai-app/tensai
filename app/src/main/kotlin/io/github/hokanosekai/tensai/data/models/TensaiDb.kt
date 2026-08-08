package io.github.hokanosekai.tensai.data.models

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.hokanosekai.tensai.data.chat.ChatDao
import io.github.hokanosekai.tensai.data.chat.ConversationEntity
import io.github.hokanosekai.tensai.data.chat.MessageEntity
import io.github.hokanosekai.tensai.data.session.SessionDao
import io.github.hokanosekai.tensai.data.session.SessionEntity

/**
 * App-wide Room database — [ModelEntity] plus the chat tables ([ConversationEntity], [MessageEntity]).
 *
 * Version 2 added [ModelEntity.embeddingLength] and [ModelEntity.blockCount] (TEN-29, for
 * [io.github.hokanosekai.tensai.compat.CompatibilityChecker]'s KV-cache estimate). Version 3 added
 * the chat tables (TEN-31). Version 4 added [ConversationEntity.enableThinking] (TEN-33). All three
 * bumps happened pre-release without a migration path — [androidx.room.RoomDatabase.Builder]'s
 * `fallbackToDestructiveMigration` covered the gap while there was nobody's data to lose. TEN-48
 * reconstructed the real [TensaiDbMigrations] for all of them and dropped the fallback: the app is
 * still pre-release, but the user's imported models are real data worth keeping now, so every
 * future version bump needs its own [androidx.room.migration.Migration] added to
 * [TensaiDbMigrations.ALL] — no more destructive fallback.
 *
 * Version 6 added [SessionEntity] (TEN-34): persisted KV-cache/prompt state per conversation, so
 * resuming one doesn't need a full re-prefill — see
 * [io.github.hokanosekai.tensai.data.session.SessionRepository].
 *
 * [exportSchema] is on and the schema JSON for every version lives under `app/schemas/` (see the
 * `ksp { arg("room.schemaLocation", ...) }` block in the app module's `build.gradle.kts`) —
 * required for [androidx.room.testing.MigrationTestHelper] to validate [TensaiDbMigrations]
 * against what Room actually generated at each version.
 */
@Database(
    entities = [ModelEntity::class, ConversationEntity::class, MessageEntity::class, SessionEntity::class],
    version = 6,
    exportSchema = true,
)
abstract class TensaiDb : RoomDatabase() {

    abstract fun modelDao(): ModelDao

    abstract fun chatDao(): ChatDao

    abstract fun sessionDao(): SessionDao

    companion object {
        private const val DB_NAME = "tensai.db"

        fun build(context: Context): TensaiDb =
            Room.databaseBuilder(context.applicationContext, TensaiDb::class.java, DB_NAME)
                .addMigrations(*TensaiDbMigrations.ALL)
                // Guards against a downgrade (e.g. a debug build reinstalled over a newer one)
                // hitting a version it has no migration path for — destructive only in that
                // narrow, self-inflicted case, never on a normal forward upgrade.
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
