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

    /**
     * v7 → v8 (TEN-72): adds [ModelEntity.kvShapeVersion], recording *which* revision of the
     * native derivation produced a row's KV-shape columns.
     *
     * Existing rows get `0`, and `0` means **unrecorded**, not "version zero" — the native library
     * numbers its derivations from 1. That default is not a fallback, it is the honest answer:
     * TEN-18 shipped one formula and its follow-up fix shipped a second, corrected one, neither
     * stamped, so a row written between the two carries an under-count that reads exactly like a
     * correct value. Nothing in the database can tell them apart after the fact, so every
     * pre-v8 row is declared stale and re-read once by [KvCacheShapeBackfill].
     *
     * The alternative — defaulting to the current version and trusting what's there — would leave
     * precisely the rows this issue exists for frozen on an under-count, and an under-counted
     * KV cache is the direction that gets a load OOM-killed (see [KvCacheSize]).
     *
     * The re-read costs one header parse per model, once, and only rewrites `n_ctx`-relevant
     * numbers where the old formula actually got them wrong; a row that was already correct comes
     * back identical, so nothing downstream (notably the session invalidation keyed on `nCtx`)
     * notices. From then on the row's version matches and [ModelDao.withStaleKvCacheShape] skips
     * it — unlike [MIGRATION_6_7]'s sentinel, this one is never re-tested by guesswork.
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `models` ADD COLUMN `kvShapeVersion` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v8 -> v9 (TEN-105): no schema change, a data fix. [ConversationEntity.title] used to start as
     * the English literal "New chat", which then showed in the drawer whatever the app language.
     * New rows now start empty and the UI renders the localized string for an empty title; this
     * brings the rows already stored in line, so "untitled" is one state (`''`) rather than two.
     *
     * Only conversations with no message are touched. Those are exactly the ones still carrying the
     * default: [ChatRepository.appendUserMessage] replaces the title on the first message, so a row
     * with messages and the title "New chat" is one whose first message was literally that text,
     * and it keeps it.
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "UPDATE `conversations` SET `title` = '' WHERE `title` = 'New chat' " +
                    "AND NOT EXISTS (SELECT 1 FROM `messages` WHERE `messages`.`conversationId` = `conversations`.`id`)",
            )
        }
    }

    /**
     * v9 -> v10 (TEN-106): no schema change, a data fix. Every way into a new chat used to insert
     * its [ConversationEntity] row before any message, so a chat opened and left without sending
     * stayed behind as an empty "New chat" in the drawer, for good (43 of 78 rows on the reference
     * device). New chats are now drafts that only get a row on their first send; this deletes the
     * empty rows already stored.
     *
     * "Empty" means no [io.github.hokanosekai.tensai.data.chat.MessageEntity] at all, whatever the
     * title says. A conversation with only a user message (its reply failed before a first token)
     * has a message and is kept.
     *
     * Nothing else references a conversation that could be left dangling: `messages` has no rows
     * for these by definition, and there is no foreign key anywhere. `sessions` is keyed by
     * conversation id but deliberately left alone. A session is only written after a generation,
     * which needs a message, so an empty conversation should have none; and if one did, deleting
     * its row here would orphan its `.llamastate` file, which a migration cannot delete and only
     * Settings > Storage > Clear would ever sweep. Left in place, the row stays visible to
     * [io.github.hokanosekai.tensai.data.session.SessionRepository]'s LRU eviction, which deletes
     * row and file together.
     */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "DELETE FROM `conversations` " +
                    "WHERE NOT EXISTS (SELECT 1 FROM `messages` WHERE `messages`.`conversationId` = `conversations`.`id`)",
            )
        }
    }

    /**
     * v10 -> v11 (TEN-141): adds the `downloads` table behind
     * [io.github.hokanosekai.tensai.download.DownloadManager]. Brand new, so existing rows are untouched.
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `downloads` (`id` TEXT NOT NULL, `repo` TEXT NOT NULL, " +
                    "`fileName` TEXT NOT NULL, `quant` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                    "`sizeBytes` INTEGER NOT NULL, `sha256` TEXT, `status` TEXT NOT NULL, " +
                    "`bytesDone` INTEGER NOT NULL, `error` TEXT, `queuedAt` INTEGER NOT NULL, `modelId` TEXT, `commitSha` TEXT, " +
                    "`allowMobile` INTEGER NOT NULL, `ioRetries` INTEGER NOT NULL, `notBefore` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))",
            )
        }
    }

    /**
     * v11 -> v12 (TEN-142): the origin of a downloaded model, six nullable columns on `models`. A
     * plain `ADD COLUMN` with no default, so every existing row reads null: they were all imported
     * from the phone or downloaded before the origin was recorded. Nothing can be backfilled.
     * [ORIGIN_COLUMNS_11_12] is what [TensaiDbMigrationSqlTest] checks against the exported v12 schema.
     */
    internal val ORIGIN_COLUMNS_11_12 = listOf(
        "sourceRepo" to "TEXT",
        "sourceFile" to "TEXT",
        "sourceQuant" to "TEXT",
        "sourceSha256" to "TEXT",
        "sourceCommit" to "TEXT",
        "downloadedAt" to "INTEGER",
    )

    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ORIGIN_COLUMNS_11_12.forEach { (name, type) ->
                db.execSQL("ALTER TABLE `models` ADD COLUMN `$name` $type")
            }
        }
    }

    /**
     * v12 -> v13 (TEN-154): `models.supportsTools`. `DEFAULT 0` because SQLite needs one to add a
     * NOT NULL column, so every existing row reads "no tools" until [KvCacheShapeBackfill] re-reads
     * its header. The migration resets `kvShapeVersion` to 0 on every row to make them all stale: the
     * backfill re-reads a stale row for its KV shape and its capabilities, once, and stamps it current.
     */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `models` ADD COLUMN `supportsTools` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE `models` SET `kvShapeVersion` = 0")
        }
    }

    /**
     * v13 -> v14 (TEN-150): `models.contextOverride`, the per-model [io.github.hokanosekai.tensai.data.ContextSetting]
     * by name. Nullable with no default: every existing row reads null, "use the global setting",
     * which is what they were doing. Nothing to backfill, and no `kvShapeVersion` reset: the column
     * is the user's choice, not something the header backfill derives.
     */
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `models` ADD COLUMN `contextOverride` TEXT")
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
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
    )
}
