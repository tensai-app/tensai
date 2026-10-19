package io.github.tensaiapp.data.models

import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TensaiDbMigrations.MIGRATION_15_16] against the exported schemas, on the JVM (TEN-161): the SQL it
 * emits is recorded and compared with what changed between `15.json` and `16.json`. Same idea as
 * [TensaiDbMigration14To15SqlTest].
 */
class TensaiDbMigration15To16SqlTest {

    private fun columns(version: Int, table: String): Map<String, JSONObject> {
        val file = File("schemas/io.github.tensaiapp.data.models.TensaiDb/$version.json")
        assertTrue("missing ${file.absolutePath}, build the app once to export it", file.exists())
        val entities = JSONObject(file.readText()).getJSONObject("database").getJSONArray("entities")
        val entity = (0 until entities.length()).map { entities.getJSONObject(it) }.first { it.getString("tableName") == table }
        val fields = entity.getJSONArray("fields")
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
        TensaiDbMigrations.MIGRATION_15_16.migrate(db)
        return sql
    }

    @Test
    fun theMigrationGoesFromFifteenToSixteenAndIsInTheChain() {
        assertEquals(15, TensaiDbMigrations.MIGRATION_15_16.startVersion)
        assertEquals(16, TensaiDbMigrations.MIGRATION_15_16.endVersion)
        assertTrue(TensaiDbMigrations.MIGRATION_15_16 in TensaiDbMigrations.ALL)
    }

    @Test
    fun sixteenAddsTheTwoNullableTokenCountColumnsToMessages() {
        val added = columns(16, "messages").filterKeys { it !in columns(15, "messages") }
        assertEquals(setOf("tokenCount", "tokenCountModelId"), added.keys)
        assertEquals("INTEGER", added.getValue("tokenCount").getString("affinity"))
        assertEquals("TEXT", added.getValue("tokenCountModelId").getString("affinity"))
        assertFalse("null is not counted yet, every existing row", added.getValue("tokenCount").getBoolean("notNull"))
        assertFalse(added.getValue("tokenCountModelId").getBoolean("notNull"))
    }

    @Test
    fun theEmittedSqlAddsThem() {
        assertEquals(
            listOf(
                "ALTER TABLE `messages` ADD COLUMN `tokenCount` INTEGER",
                "ALTER TABLE `messages` ADD COLUMN `tokenCountModelId` TEXT",
            ),
            recordedSql(),
        )
    }
}
