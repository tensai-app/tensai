package io.github.hokanosekai.tensai.data.models

import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TensaiDbMigrations.MIGRATION_16_17] against the exported schemas, on the JVM (TEN-172): the SQL it
 * emits is recorded and compared with what changed between `16.json` and `17.json`. Same idea as
 * [TensaiDbMigration15To16SqlTest].
 */
class TensaiDbMigration16To17SqlTest {

    private fun entity(version: Int, table: String): JSONObject {
        val file = File("schemas/io.github.hokanosekai.tensai.data.models.TensaiDb/$version.json")
        assertTrue("missing ${file.absolutePath}, build the app once to export it", file.exists())
        val entities = JSONObject(file.readText()).getJSONObject("database").getJSONArray("entities")
        return (0 until entities.length()).map { entities.getJSONObject(it) }.first { it.getString("tableName") == table }
    }

    private fun columns(version: Int, table: String): Map<String, JSONObject> {
        val fields = entity(version, table).getJSONArray("fields")
        return (0 until fields.length()).map { fields.getJSONObject(it) }.associateBy { it.getString("columnName") }
    }

    private fun recordedSql(): List<String> {
        val sql = mutableListOf<String>()
        val db = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            check(method.name == "execSQL") { "the migration may only run execSQL, it called ${method.name}" }
            sql += args[0] as String
            null
        } as SupportSQLiteDatabase
        TensaiDbMigrations.MIGRATION_16_17.migrate(db)
        return sql
    }

    @Test
    fun theMigrationGoesFromSixteenToSeventeenAndIsInTheChain() {
        assertEquals(16, TensaiDbMigrations.MIGRATION_16_17.startVersion)
        assertEquals(17, TensaiDbMigrations.MIGRATION_16_17.endVersion)
        assertTrue(TensaiDbMigrations.MIGRATION_16_17 in TensaiDbMigrations.ALL)
    }

    @Test
    fun seventeenAddsTheNullableToolColumnsToMessages() {
        val added = columns(17, "messages").filterKeys { it !in columns(16, "messages") }
        assertEquals(setOf("toolCallId", "toolName"), added.keys)
        added.values.forEach {
            assertEquals("TEXT", it.getString("affinity"))
            assertFalse("null is not a tool message, every existing row", it.getBoolean("notNull"))
        }
    }

    @Test
    fun seventeenAddsToolsEnabledToConversations() {
        val added = columns(17, "conversations").filterKeys { it !in columns(16, "conversations") }
        assertEquals(setOf("toolsEnabled"), added.keys)
        assertEquals("INTEGER", added.getValue("toolsEnabled").getString("affinity"))
        assertTrue(added.getValue("toolsEnabled").getBoolean("notNull"))
        // The entity declares no SQL default, so the generated CREATE TABLE has none: Room accepts
        // a column that has one the entity does not mention.
        assertTrue(entity(17, "conversations").getString("createSql").contains("`toolsEnabled` INTEGER NOT NULL"))
    }

    @Test
    fun theEmittedSqlAddsThemAndCreatesTheToolCallsTable() {
        val sql = recordedSql()

        assertEquals(
            listOf(
                "ALTER TABLE `messages` ADD COLUMN `toolCallId` TEXT",
                "ALTER TABLE `messages` ADD COLUMN `toolName` TEXT",
                "ALTER TABLE `conversations` ADD COLUMN `toolsEnabled` INTEGER NOT NULL DEFAULT 1",
            ),
            sql.take(3),
        )
        assertEquals(5, sql.size)
        // The CREATE statements are the table's exported ones, whitespace aside.
        val toolCalls = entity(17, "tool_calls")
        assertEquals(
            toolCalls.getString("createSql").replace("`\${TABLE_NAME}`", "`tool_calls`"),
            sql[3],
        )
        val index = toolCalls.getJSONArray("indices").getJSONObject(0)
        assertEquals(index.getString("createSql").replace("`\${TABLE_NAME}`", "`tool_calls`"), sql[4])
    }

    @Test
    fun toolCallsHoldsTheAuditColumns() {
        assertEquals(
            setOf(
                "id", "messageId", "callId", "tool", "tier", "server", "argsJson",
                "startedAt", "durationMs", "resultTokens", "status", "destination",
            ),
            columns(17, "tool_calls").keys,
        )
        assertFalse(columns(17, "tool_calls").getValue("server").getBoolean("notNull"))
    }
}
