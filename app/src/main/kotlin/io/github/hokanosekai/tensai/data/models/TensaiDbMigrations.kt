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

    /**
     * v5 → v6 (TEN-34): adds the `sessions` table backing
     * [io.github.hokanosekai.tensai.data.session.SessionRepository] — a brand new table, so
     * nothing to backfill for existing rows in other tables.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `sessions` (`conversationId` TEXT NOT NULL, `modelId` TEXT NOT NULL, " +
                    "`filePath` TEXT NOT NULL, `nCtx` INTEGER NOT NULL, `kvCacheType` TEXT NOT NULL, " +
                    "`backend` TEXT NOT NULL, `tokenCount` INTEGER NOT NULL, `sizeBytes` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `lastAccessedAt` INTEGER NOT NULL, PRIMARY KEY(`conversationId`))",
            )
        }
    }

    /**
     * v6 → v7 (TEN-18): adds [ModelEntity.kvFullElementsPerToken],
     * [ModelEntity.kvSwaElementsPerToken] and [ModelEntity.slidingWindow], the model's real KV
     * shape as read from its GGUF header.
     *
     * Existing rows get `0`, which [KvCacheSize] treats as *unknown* rather than *none* and
     * answers with the pre-TEN-18 over-estimate — so a model imported before this migration keeps
     * exactly the context it had, never a larger one it can't afford. Unlike [MIGRATION_1_2],
     * which left its `0`s standing forever, that sentinel is temporary:
     * [ModelRepository.backfillKvCacheShape] re-reads the header of every row still carrying it on
     * the next app start (a header-only read, milliseconds even on a multi-GB file), so the user
     * doesn't have to re-import to get the better estimate. The sentinel is what makes that
     * backfill safe to fail — a moved or deleted file just leaves the row conservative.
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `models` ADD COLUMN `kvFullElementsPerToken` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `models` ADD COLUMN `kvSwaElementsPerToken` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `models` ADD COLUMN `slidingWindow` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** All migrations, in order — pass to [androidx.room.RoomDatabase.Builder.addMigrations]. */
    val ALL = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
    )
}
