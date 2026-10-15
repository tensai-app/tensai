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
 * [TensaiDbMigrations.MIGRATION_13_14] against the exported schemas, on the JVM (TEN-150): the SQL
 * it emits is recorded and compared with what changed between `13.json` and `14.json`. Same idea as
 * [TensaiDbMigrationSqlTest], which covers the previous step.
 */
class TensaiDbMigration13To14SqlTest {

    private fun modelsTable(version: Int): JSONObject {
        val file = File("schemas/io.github.hokanosekai.tensai.data.models.TensaiDb/$version.json")
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
        TensaiDbMigrations.MIGRATION_13_14.migrate(db)
        return sql
    }

    @Test
    fun theMigrationGoesFromThirteenToFourteenAndIsInTheChain() {
        assertEquals(13, TensaiDbMigrations.MIGRATION_13_14.startVersion)
        assertEquals(14, TensaiDbMigrations.MIGRATION_13_14.endVersion)
        assertTrue(TensaiDbMigrations.MIGRATION_13_14 in TensaiDbMigrations.ALL)
    }

    @Test
    fun fourteenAddsExactlyContextOverrideAsANullableText() {
        val thirteen = columns(13)
        val added = columns(14).filterKeys { it !in thirteen }

        assertEquals(setOf("contextOverride"), added.keys)
        val column = added.getValue("contextOverride")
        assertEquals("TEXT", column.getString("affinity"))
        assertFalse("null is \"use the global setting\", every existing row", column.getBoolean("notNull"))
    }

    @Test
    fun theEmittedSqlOnlyAddsThatColumnAndLeavesTheRowsAndTheBackfillAlone() {
        assertEquals(
            listOf("ALTER TABLE `models` ADD COLUMN `contextOverride` TEXT"),
            recordedSql(),
        )
        assertTrue(modelsTable(14).getString("createSql").contains("`contextOverride` TEXT"))
    }
}
