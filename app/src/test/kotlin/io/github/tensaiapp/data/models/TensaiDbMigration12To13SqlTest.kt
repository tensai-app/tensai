package io.github.tensaiapp.data.models

import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TensaiDbMigrations.MIGRATION_12_13] against the exported schemas, on the JVM (TEN-154): the SQL
 * it emits is recorded and compared with what changed between `12.json` and `13.json`. Same idea as
 * [TensaiDbMigrationSqlTest], which covers the previous step.
 */
class TensaiDbMigration12To13SqlTest {

    private fun modelsTable(version: Int): JSONObject {
        val file = File("schemas/io.github.tensaiapp.data.models.TensaiDb/$version.json")
        assertTrue("missing ${file.absolutePath}, build the app once to export it", file.exists())
        val entities = JSONObject(file.readText()).getJSONObject("database").getJSONArray("entities")
        return (0 until entities.length()).map { entities.getJSONObject(it) }.first { it.getString("tableName") == "models" }
    }

    private fun columns(version: Int): Map<String, JSONObject> {
        val fields = modelsTable(version).getJSONArray("fields")
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
        TensaiDbMigrations.MIGRATION_12_13.migrate(db)
        return sql
    }

    @Test
    fun theMigrationGoesFromTwelveToThirteenAndIsInTheChain() {
        assertEquals(12, TensaiDbMigrations.MIGRATION_12_13.startVersion)
        assertEquals(13, TensaiDbMigrations.MIGRATION_12_13.endVersion)
        assertTrue(TensaiDbMigrations.MIGRATION_12_13 in TensaiDbMigrations.ALL)
    }

    @Test
    fun thirteenAddsExactlySupportsToolsAsANotNullInteger() {
        val twelve = columns(12)
        val added = columns(13).filterKeys { it !in twelve }

        assertEquals(setOf("supportsTools"), added.keys)
        val column = added.getValue("supportsTools")
        assertEquals("INTEGER", column.getString("affinity"))
        assertTrue(column.getBoolean("notNull"))
    }

    @Test
    fun theEmittedSqlAddsThatColumnAndMakesEveryRowStaleForTheBackfill() {
        assertEquals(
            listOf(
                "ALTER TABLE `models` ADD COLUMN `supportsTools` INTEGER NOT NULL DEFAULT 0",
                "UPDATE `models` SET `kvShapeVersion` = 0",
            ),
            recordedSql(),
        )
        // The entity declares no SQL default, so the generated CREATE TABLE has none: Room accepts
        // the migrated table because the column's name, type and nullability match.
        assertTrue(modelsTable(13).getString("createSql").contains("`supportsTools` INTEGER NOT NULL"))
    }
}
