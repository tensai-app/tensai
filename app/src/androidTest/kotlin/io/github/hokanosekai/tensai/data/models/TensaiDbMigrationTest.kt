package io.github.hokanosekai.tensai.data.models

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
                    "('m1', 'Model One', 'm1.gguf', '/data/data/io.github.hokanosekai.tensai/files/models/m1.gguf', " +
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
                    "'/data/data/io.github.hokanosekai.tensai/files/models/qwen3.gguf', " +
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
}
