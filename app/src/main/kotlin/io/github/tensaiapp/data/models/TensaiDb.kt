package io.github.tensaiapp.data.models

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.tensaiapp.data.chat.ChatDao
import io.github.tensaiapp.data.chat.ChatSearchDao
import io.github.tensaiapp.data.chat.ConversationEntity
import io.github.tensaiapp.data.chat.MessageEntity
import io.github.tensaiapp.data.chat.ToolCallEntity
import io.github.tensaiapp.data.memory.MemoryDao
import io.github.tensaiapp.data.memory.MemoryFactEntity
import io.github.tensaiapp.data.session.SessionDao
import io.github.tensaiapp.data.session.SessionEntity
import io.github.tensaiapp.download.DownloadDao
import io.github.tensaiapp.download.DownloadEntity

/**
 * App-wide Room database — [ModelEntity] plus the chat tables ([ConversationEntity], [MessageEntity]).
 *
 * Version 2 added [ModelEntity.embeddingLength] and [ModelEntity.blockCount] (TEN-29, for
 * [io.github.tensaiapp.compat.CompatibilityChecker]'s KV-cache estimate). Version 3 added
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
 * [io.github.tensaiapp.data.session.SessionRepository].
 *
 * Version 7 added [ModelEntity]'s KV-cache shape columns (TEN-18) — see [KvCacheSize]. Rows
 * predating it get `0`, which [KvCacheSize] reads as *unknown* and answers with the old
 * over-estimate; [ModelRepository.backfillKvCacheShape] then fills them in on the next start.
 *
 * Version 8 added [ModelEntity.kvShapeVersion] (TEN-72): *which* derivation produced those
 * numbers. Without it a shape computed by a superseded formula is indistinguishable from a current
 * one — same column, same plausible magnitude — and the version-7 backfill, which only looked for
 * zeroes, could never revisit it. See [KvCacheShapeBackfill].
 *
 * Version 9 changed no schema (TEN-105): [TensaiDbMigrations.MIGRATION_8_9] clears the English
 * "New chat" placeholder from conversations that never got a message, now that an untitled
 * conversation is stored with an empty title and labelled by the UI in the app's language.
 *
 * Version 10 changed no schema either (TEN-106): [TensaiDbMigrations.MIGRATION_9_10] deletes the
 * conversations that never got a message. A new chat is now a draft with no row until its first
 * send, so those rows are no longer created, and the ones left from before go.
 *
 * Version 11 added [DownloadEntity] (TEN-141): the model download queue, see
 * [io.github.tensaiapp.download.DownloadManager]. A new table only, nothing to backfill.
 *
 * Version 12 added [ModelEntity]'s origin columns (TEN-142): `sourceRepo`, `sourceFile`, `sourceQuant`,
 * `sourceSha256`, `sourceCommit` and `downloadedAt`, all nullable. Rows imported from the phone, and
 * every row from before, keep them null: only a finished download has an origin.
 *
 * Version 13 added `supportsTools` (TEN-154), see [TensaiDbMigrations.MIGRATION_12_13].
 *
 * Version 14 added `contextOverride` (TEN-150): the model's own context setting, null = use the global one.
 *
 * Version 15 added `models.hasChatTemplate` and `models.chatTemplateOverride` plus `sessions.chatTemplate` (TEN-159),
 * see [TensaiDbMigrations.MIGRATION_14_15].
 *
 * Version 16 added `messages.tokenCount` and `messages.tokenCountModelId` (TEN-161), see [TensaiDbMigrations.MIGRATION_15_16].
 *
 * Version 17 added tool calling (TEN-172): `messages.toolCallId`/`toolName`, `conversations.toolsEnabled` and the
 * [ToolCallEntity] table, see [TensaiDbMigrations.MIGRATION_16_17].
 *
 * Version 18 added the [MemoryFactEntity] table (TEN-176): what the `memory` tool remembers about the user, see
 * [TensaiDbMigrations.MIGRATION_17_18]. A new table only.
 *
 * Version 19 added `sessions.withoutTools` (TEN-196), see [TensaiDbMigrations.MIGRATION_18_19].
 *
 * Version 20 added `messages.sendState` (TEN-224), see [TensaiDbMigrations.MIGRATION_19_20].
 *
 * [exportSchema] is on and the schema JSON for every version lives under `app/schemas/` (see the
 * `ksp { arg("room.schemaLocation", ...) }` block in the app module's `build.gradle.kts`) —
 * required for [androidx.room.testing.MigrationTestHelper] to validate [TensaiDbMigrations]
 * against what Room actually generated at each version.
 */
@Database(
    entities = [
        ModelEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        SessionEntity::class,
        DownloadEntity::class,
        ToolCallEntity::class,
        MemoryFactEntity::class,
    ],
    version = 20,
    exportSchema = true,
)
abstract class TensaiDb : RoomDatabase() {

    abstract fun modelDao(): ModelDao

    abstract fun chatDao(): ChatDao

    abstract fun sessionDao(): SessionDao

    abstract fun downloadDao(): DownloadDao

    abstract fun chatSearchDao(): ChatSearchDao

    abstract fun memoryDao(): MemoryDao

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
