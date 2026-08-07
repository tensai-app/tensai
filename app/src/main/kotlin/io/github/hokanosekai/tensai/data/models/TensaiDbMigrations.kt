package io.github.hokanosekai.tensai.data.models

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every [TensaiDb] schema migration, reconstructed from git history (see each `Migration`'s KDoc
 * for the commit it corresponds to) now that [TensaiDb] no longer
 * [androidx.room.RoomDatabase.Builder.fallbackToDestructiveMigration]s — see [TensaiDb]'s KDoc for
 * why that was safe to drop only once every past version bump had a real migration behind it.
 *
 * Each `CREATE TABLE`/`CREATE INDEX` below is copied verbatim from the exported schema JSON under
 * `app/schemas/` for the version it targets, so it stays byte-for-byte what Room itself would have
 * generated at that point in history — [androidx.room.testing.MigrationTestHelper] validates this
 * against those same JSON files.
 */
object TensaiDbMigrations {

    /**
     * v1 → v2 (TEN-29, commit fd480e8): adds [ModelEntity.embeddingLength] and
     * [ModelEntity.blockCount]. Existing rows get `0` for both — the real values only come from
     * re-parsing the GGUF header (see [io.github.hokanosekai.tensai.engine.GgufInfo]), which this
     * migration has no access to — so a model imported before this migration will under-report
     * its KV-cache footprint in [io.github.hokanosekai.tensai.compat.CompatibilityChecker] until
     * it's re-imported. Acceptable: this is the one version gap old enough to predate any
     * real device data.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `models` ADD COLUMN `embeddingLength` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `models` ADD COLUMN `blockCount` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** v2 → v3 (TEN-31, commit e767574): adds the chat tables, [ConversationEntity] and [MessageEntity]. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `conversations` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                    "`modelId` TEXT, `samplingJson` TEXT, `systemPrompt` TEXT, `createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, " +
                    "`role` TEXT NOT NULL, `content` TEXT NOT NULL, `thinking` TEXT, `toolCallsJson` TEXT, " +
                    "`tokensGenerated` INTEGER, `tokPerSec` REAL, `ttftMs` INTEGER, `createdAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_conversationId` ON `messages` (`conversationId`)")
        }
    }

    /**
     * v3 → v4 (TEN-33, commit 729482e): adds [ConversationEntity.enableThinking]. Existing
     * conversations default to `false` — thinking was off for all of them anyway, since the
     * toggle didn't exist yet.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `conversations` ADD COLUMN `enableThinking` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v4 → v5 (TEN-60): adds [MessageEntity.stopReason]. Nullable with no default, so every
     * message generated before this point keeps `null` — we genuinely don't know whether those
     * replies ended on their own or were cut off at the old 512-token cap, and writing a guess
     * into them would be worse than admitting it.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `stopReason` TEXT")
        }
    }

    /** All migrations, in order — pass to [androidx.room.RoomDatabase.Builder.addMigrations]. */
    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
}
