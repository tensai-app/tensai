package io.github.tensaiapp.data.models

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates every [TensaiDbMigrations] entry against the real schema JSONs under `app/schemas/`
 * (see [TensaiDb]'s KDoc) — the reason TEN-48 could drop `fallbackToDestructiveMigration` at all.
 * [helper] builds a database at an old version straight from the exported schema, then
 * [MigrationTestHelper.runMigrationsAndValidate] both runs the migration and diffs the result
 * against the target version's exported schema — a silent column/index mismatch fails the test,
 * not just a missing exception.
 */
@RunWith(AndroidJUnit4::class)
class TensaiDbMigrationTest {

    private val testDbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TensaiDb::class.java,
    )

    /**
     * The migration this issue exists for: a model imported before any of [TensaiDbMigrations]
     * existed must survive all the way to the current version, not get wiped by a destructive
     * fallback.
     */
    @Test
    fun modelRowSurvivesV1ToV4() {
        helper.createDatabase(testDbName, 1).apply {
            execSQL(
                "INSERT INTO models (id, displayName, fileName, filePath, sizeBytes, architecture, " +
                    "quantLabel, paramCount, contextLength, supportsThinking, importedAt) VALUES " +
                    "('m1', 'Model One', 'm1.gguf', '/data/data/io.github.tensaiapp/files/models/m1.gguf', " +
                    "123456, 'llama', 'Q4_K_M', 7000000000, 4096, 1, 1000)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 4, true, *TensaiDbMigrations.ALL)

        val cursor = migrated.query("SELECT * FROM models WHERE id = 'm1'")
        assertTrue("expected the pre-migration row to still be there", cursor.moveToFirst())
        assertEquals("Model One", cursor.getString(cursor.getColumnIndexOrThrow("displayName")))
        assertEquals(4096L, cursor.getLong(cursor.getColumnIndexOrThrow("contextLength")))
        // Backfilled by MIGRATION_1_2 — see its KDoc for why 0 is the best a migration can do here.
        assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("embeddingLength")))
        assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("blockCount")))
        cursor.close()
    }

    @Test
    fun migrate1To2AddsModelColumns() {
        helper.createDatabase(testDbName, 1).close()

        helper.runMigrationsAndValidate(testDbName, 2, true, TensaiDbMigrations.MIGRATION_1_2)
    }

    @Test
    fun migrate2To3AddsChatTables() {
        helper.createDatabase(testDbName, 2).close()

        val migrated = helper.runMigrationsAndValidate(testDbName, 3, true, TensaiDbMigrations.MIGRATION_2_3)

        migrated.query("SELECT * FROM conversations").use { assertEquals(0, it.count) }
        migrated.query("SELECT * FROM messages").use { assertEquals(0, it.count) }
    }

    @Test
    fun migrate3To4AddsEnableThinkingColumn() {
        helper.createDatabase(testDbName, 3).apply {
            execSQL(
                "INSERT INTO conversations (id, title, modelId, samplingJson, systemPrompt, createdAt, updatedAt) " +
                    "VALUES ('c1', 'New chat', null, null, null, 1000, 1000)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 4, true, TensaiDbMigrations.MIGRATION_3_4)

        val cursor = migrated.query("SELECT * FROM conversations WHERE id = 'c1'")
        assertTrue(cursor.moveToFirst())
        assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("enableThinking")))
        cursor.close()
    }

    @Test
    fun migrate4To5AddsStopReasonAndLeavesOldRepliesUnknown() {
        helper.createDatabase(testDbName, 4).apply {
            execSQL(
                "INSERT INTO conversations (id, title, modelId, samplingJson, systemPrompt, createdAt, " +
                    "updatedAt, enableThinking) VALUES ('c1', 'New chat', null, null, null, 1000, 1000, 0)",
            )
            execSQL(
                "INSERT INTO messages (id, conversationId, role, content, thinking, toolCallsJson, " +
                    "tokensGenerated, tokPerSec, ttftMs, createdAt) VALUES " +
                    "('m1', 'c1', 'assistant', 'a reply from before v5', null, null, 512, 5.9, 2729, 1000)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 5, true, TensaiDbMigrations.MIGRATION_4_5)

        val cursor = migrated.query("SELECT * FROM messages WHERE id = 'm1'")
        assertTrue("expected the pre-migration reply to still be there", cursor.moveToFirst())
        assertEquals("a reply from before v5", cursor.getString(cursor.getColumnIndexOrThrow("content")))
        assertEquals(512L, cursor.getLong(cursor.getColumnIndexOrThrow("tokensGenerated")))
        // Left null on purpose: this reply may well have been cut off at the old 512 cap, but
        // nothing recorded that at the time, and inventing a reason would be worse than admitting
        // we don't know. See MIGRATION_4_5's KDoc.
        assertTrue(
            "expected stopReason to stay unknown for a pre-v5 reply",
            cursor.isNull(cursor.getColumnIndexOrThrow("stopReason")),
        )
        cursor.close()
    }

    @Test
    fun migrate5To6AddsSessionsTable() {
        helper.createDatabase(testDbName, 5).close()

        val migrated = helper.runMigrationsAndValidate(testDbName, 6, true, TensaiDbMigrations.MIGRATION_5_6)

        migrated.query("SELECT * FROM sessions").use { assertEquals(0, it.count) }
    }

    /**
     * A model imported before TEN-18 keeps its row and lands on the `0` sentinel for all three
     * KV-shape columns — which [KvCacheSize] reads as *unknown* and answers with the old
     * over-estimate, so the model's planned context can only stay the same or grow later, never
     * jump to one the device can't afford. `ModelRepository.backfillKvCacheShape` replaces the
     * sentinel on the next start; this test is about the state it starts from.
     */
    @Test
    fun migrate6To7LeavesExistingModelsWithoutAKvShape() {
        helper.createDatabase(testDbName, 6).apply {
            execSQL(
                "INSERT INTO models (id, displayName, fileName, filePath, sizeBytes, architecture, " +
                    "quantLabel, paramCount, contextLength, embeddingLength, blockCount, supportsThinking, " +
                    "importedAt) VALUES ('m1', 'Qwen3 1.7B', 'qwen3.gguf', " +
                    "'/data/data/io.github.tensaiapp/files/models/qwen3.gguf', " +
                    "1234567, 'qwen3', 'Q4_K_M', 1700000000, 40960, 2048, 28, 0, 1000)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 7, true, TensaiDbMigrations.MIGRATION_6_7)

        val cursor = migrated.query("SELECT * FROM models WHERE id = 'm1'")
        assertTrue("expected the pre-migration model to still be there", cursor.moveToFirst())
        assertEquals("Qwen3 1.7B", cursor.getString(cursor.getColumnIndexOrThrow("displayName")))
        assertEquals(28L, cursor.getLong(cursor.getColumnIndexOrThrow("blockCount")))
        assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("kvFullElementsPerToken")))
        assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("kvSwaElementsPerToken")))
        assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("slidingWindow")))
        cursor.close()
    }

    /**
     * A model carrying a KV shape from before TEN-72 keeps every column it had, and lands on
     * `kvShapeVersion = 0` — *unrecorded*, which is not a version the native library ever reports,
     * so `KvCacheShapeBackfill` re-derives the row once on the next start.
     *
     * The row below is the case the issue exists for: it was imported between TEN-18's first
     * formula and its correction, so its 57 344 is an under-count that looks like any other
     * number. The pre-v8 backfill only looked for zeroes and would have left it standing forever;
     * nothing this migration can see distinguishes it from a correct row either, which is why the
     * default is 0 for *every* pre-v8 row rather than only the zeroed ones.
     */
    @Test
    fun migrate7To8LeavesExistingKvShapesInPlaceButMarksThemUnrecorded() {
        helper.createDatabase(testDbName, 7).apply {
            execSQL(
                "INSERT INTO models (id, displayName, fileName, filePath, sizeBytes, architecture, " +
                    "quantLabel, paramCount, contextLength, embeddingLength, blockCount, supportsThinking, " +
                    "importedAt, kvFullElementsPerToken, kvSwaElementsPerToken, slidingWindow) VALUES " +
                    "('m1', 'Qwen3 1.7B', 'qwen3.gguf', " +
                    "'/data/data/io.github.tensaiapp/files/models/qwen3.gguf', " +
                    "1234567, 'qwen3', 'Q4_K_M', 1700000000, 40960, 2048, 28, 0, 1000, 57344, 0, 0)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 8, true, TensaiDbMigrations.MIGRATION_7_8)

        val cursor = migrated.query("SELECT * FROM models WHERE id = 'm1'")
        assertTrue("expected the pre-migration model to still be there", cursor.moveToFirst())
        assertEquals("Qwen3 1.7B", cursor.getString(cursor.getColumnIndexOrThrow("displayName")))
        assertEquals(1234567L, cursor.getLong(cursor.getColumnIndexOrThrow("sizeBytes")))
        assertEquals(28L, cursor.getLong(cursor.getColumnIndexOrThrow("blockCount")))
        // Untouched — the migration adds a column, it does not re-derive anything. The correction
        // is the backfill's job, on the next start, with the GGUF header in hand.
        assertEquals(57344L, cursor.getLong(cursor.getColumnIndexOrThrow("kvFullElementsPerToken")))
        assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("kvShapeVersion")))
        cursor.close()
    }

    /**
     * TEN-105: an empty conversation still carrying the old English "New chat" placeholder comes
     * out untitled (`''`), which the drawer renders in the app language. A conversation that has
     * messages keeps its title even when it reads "New chat": that one was named after its first
     * message, which happened to be that text. Any other title is left alone.
     */
    @Test
    fun migrate8To9ClearsThePlaceholderTitleOfEmptyConversationsOnly() {
        helper.createDatabase(testDbName, 8).apply {
            execSQL(
                "INSERT INTO conversations (id, title, modelId, samplingJson, systemPrompt, createdAt, " +
                    "updatedAt, enableThinking) VALUES ('empty', 'New chat', null, null, null, 1000, 1000, 1)",
            )
            execSQL(
                "INSERT INTO conversations (id, title, modelId, samplingJson, systemPrompt, createdAt, " +
                    "updatedAt, enableThinking) VALUES ('named', 'New chat', null, null, null, 1000, 1000, 1)",
            )
            execSQL(
                "INSERT INTO messages (id, conversationId, role, content, thinking, toolCallsJson, " +
                    "tokensGenerated, tokPerSec, ttftMs, createdAt, stopReason) VALUES " +
                    "('m1', 'named', 'user', 'New chat', null, null, null, null, null, 1000, null)",
            )
            execSQL(
                "INSERT INTO conversations (id, title, modelId, samplingJson, systemPrompt, createdAt, " +
                    "updatedAt, enableThinking) VALUES ('other', 'Parse a GGUF header', null, null, null, 1000, 1000, 1)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 9, true, TensaiDbMigrations.MIGRATION_8_9)

        fun titleOf(id: String): String = migrated.query("SELECT title FROM conversations WHERE id = '$id'").use {
            assertTrue("expected conversation $id to still be there", it.moveToFirst())
            it.getString(0)
        }
        assertEquals("", titleOf("empty"))
        assertEquals("New chat", titleOf("named"))
        assertEquals("Parse a GGUF header", titleOf("other"))
    }

    /**
     * TEN-106: a conversation that never got a message is deleted, whatever its title. One with
     * messages stays, including one holding only a user message (a reply that failed before its
     * first token), along with its messages.
     */
    @Test
    fun migrate9To10DeletesConversationsWithoutMessagesOnly() {
        helper.createDatabase(testDbName, 9).apply {
            execSQL(
                "INSERT INTO conversations (id, title, modelId, samplingJson, systemPrompt, createdAt, " +
                    "updatedAt, enableThinking) VALUES ('empty', '', null, null, null, 1000, 1000, 1)",
            )
            execSQL(
                "INSERT INTO conversations (id, title, modelId, samplingJson, systemPrompt, createdAt, " +
                    "updatedAt, enableThinking) VALUES ('empty-titled', 'Parse a GGUF header', null, null, null, 1000, 1000, 1)",
            )
            execSQL(
                "INSERT INTO conversations (id, title, modelId, samplingJson, systemPrompt, createdAt, " +
                    "updatedAt, enableThinking) VALUES ('answered', 'hi', 'm1', null, 'Be brief.', 1000, 2000, 0)",
            )
            execSQL(
                "INSERT INTO messages (id, conversationId, role, content, thinking, toolCallsJson, " +
                    "tokensGenerated, tokPerSec, ttftMs, createdAt, stopReason) VALUES " +
                    "('m1', 'answered', 'user', 'hi', null, null, null, null, null, 1000, null)",
            )
            execSQL(
                "INSERT INTO messages (id, conversationId, role, content, thinking, toolCallsJson, " +
                    "tokensGenerated, tokPerSec, ttftMs, createdAt, stopReason) VALUES " +
                    "('m2', 'answered', 'assistant', 'hello', null, null, 2, 1.0, 10, 2000, 'EndOfTurn')",
            )
            execSQL(
                "INSERT INTO conversations (id, title, modelId, samplingJson, systemPrompt, createdAt, " +
                    "updatedAt, enableThinking) VALUES ('unanswered', 'hi', null, null, null, 1000, 1000, 1)",
            )
            execSQL(
                "INSERT INTO messages (id, conversationId, role, content, thinking, toolCallsJson, " +
                    "tokensGenerated, tokPerSec, ttftMs, createdAt, stopReason) VALUES " +
                    "('m3', 'unanswered', 'user', 'hi', null, null, null, null, null, 1000, null)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 10, true, TensaiDbMigrations.MIGRATION_9_10)

        val remaining = migrated.query("SELECT id FROM conversations ORDER BY id").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertEquals(listOf("answered", "unanswered"), remaining)
        migrated.query("SELECT systemPrompt, enableThinking FROM conversations WHERE id = 'answered'").use {
            assertTrue(it.moveToFirst())
            assertEquals("a kept row keeps its settings", "Be brief.", it.getString(0))
            assertEquals(0L, it.getLong(1))
        }
        migrated.query("SELECT COUNT(*) FROM messages").use {
            assertTrue(it.moveToFirst())
            assertEquals("no message is touched", 3L, it.getLong(0))
        }
    }

    /** v10 -> v11 (TEN-141) adds an empty `downloads` table and leaves the models alone. */
    @Test
    fun downloadsTableAppearsInV11() {
        helper.createDatabase(testDbName, 10).apply {
            execSQL(
                "INSERT INTO models (id, displayName, fileName, filePath, sizeBytes, architecture, quantLabel, " +
                    "paramCount, contextLength, supportsThinking, importedAt, embeddingLength, blockCount, " +
                    "kvFullElementsPerToken, kvSwaElementsPerToken, slidingWindow, kvShapeVersion) VALUES " +
                    "('m1', 'Model One', 'm1.gguf', '/x/m1.gguf', 1, 'llama', 'Q4_K_M', 1, 1, 0, 1, 1, 1, 0, 0, 0, 0)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 11, true, TensaiDbMigrations.MIGRATION_10_11)

        migrated.query("SELECT COUNT(*) FROM downloads").use {
            assertTrue(it.moveToFirst())
            assertEquals(0L, it.getLong(0))
        }
        migrated.query("SELECT COUNT(*) FROM models").use {
            assertTrue(it.moveToFirst())
            assertEquals("the model row is untouched", 1L, it.getLong(0))
        }
    }

    /**
     * v11 -> v12 (TEN-142) adds the nullable origin columns: an existing model keeps every value and
     * reads null for the new ones, and a row written afterwards can carry an origin.
     */
    @Test
    fun originColumnsAppearInV12AndOldRowsReadNull() {
        helper.createDatabase(testDbName, 11).apply {
            execSQL(
                "INSERT INTO models (id, displayName, fileName, filePath, sizeBytes, architecture, quantLabel, " +
                    "paramCount, contextLength, supportsThinking, importedAt, embeddingLength, blockCount, " +
                    "kvFullElementsPerToken, kvSwaElementsPerToken, slidingWindow, kvShapeVersion) VALUES " +
                    "('m1', 'Model One', 'm1.gguf', '/x/m1.gguf', 1, 'llama', 'Q4_K_M', 1, 1, 0, 1, 1, 1, 0, 0, 0, 0)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 12, true, TensaiDbMigrations.MIGRATION_11_12)

        migrated.query(
            "SELECT displayName, sourceRepo, sourceFile, sourceQuant, sourceSha256, sourceCommit, downloadedAt " +
                "FROM models WHERE id = 'm1'",
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("Model One", it.getString(0))
            for (column in 1..6) assertTrue("column $column must read null", it.isNull(column))
        }
        migrated.execSQL(
            "UPDATE models SET sourceRepo = 'org/repo', sourceFile = 'f.gguf', sourceQuant = 'Q4_K_M', " +
                "sourceSha256 = 'ab', sourceCommit = 'cd', downloadedAt = 5 WHERE id = 'm1'",
        )
        migrated.query("SELECT sourceRepo, downloadedAt FROM models WHERE id = 'm1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("org/repo", it.getString(0))
            assertEquals(5L, it.getLong(1))
        }
    }

    /** The full chain still lands a v1 row on v8 intact, not just the single hop above. */
    @Test
    fun modelRowSurvivesV1ToV8() {
        helper.createDatabase(testDbName, 1).apply {
            execSQL(
                "INSERT INTO models (id, displayName, fileName, filePath, sizeBytes, architecture, " +
                    "quantLabel, paramCount, contextLength, supportsThinking, importedAt) VALUES " +
                    "('m1', 'Model One', 'm1.gguf', '/data/data/io.github.tensaiapp/files/models/m1.gguf', " +
                    "123456, 'llama', 'Q4_K_M', 7000000000, 4096, 1, 1000)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 8, true, *TensaiDbMigrations.ALL)

        val cursor = migrated.query("SELECT * FROM models WHERE id = 'm1'")
        assertTrue("expected the pre-migration row to still be there", cursor.moveToFirst())
        assertEquals("Model One", cursor.getString(cursor.getColumnIndexOrThrow("displayName")))
        assertEquals(4096L, cursor.getLong(cursor.getColumnIndexOrThrow("contextLength")))
        assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("kvShapeVersion")))
        cursor.close()
    }
}
