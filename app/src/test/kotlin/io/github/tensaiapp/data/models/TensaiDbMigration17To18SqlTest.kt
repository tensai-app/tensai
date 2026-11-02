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
 * [TensaiDbMigrations.MIGRATION_17_18] against the exported schemas, on the JVM (TEN-176): the SQL it
 * emits is recorded and compared with what `18.json` says. Same idea as [TensaiDbMigration16To17SqlTest].
 */
class TensaiDbMigration17To18SqlTest {

    private fun entities(version: Int): List<JSONObject> {
        val file = File("schemas/io.github.tensaiapp.data.models.TensaiDb/$version.json")
        assertTrue("missing ${file.absolutePath}, build the app once to export it", file.exists())
        val all = JSONObject(file.readText()).getJSONObject("database").getJSONArray("entities")
        return (0 until all.length()).map { all.getJSONObject(it) }
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
        TensaiDbMigrations.MIGRATION_17_18.migrate(db)
        return sql
    }

    @Test
    fun theMigrationGoesFromSeventeenToEighteenAndIsInTheChain() {
        assertEquals(17, TensaiDbMigrations.MIGRATION_17_18.startVersion)
        assertEquals(18, TensaiDbMigrations.MIGRATION_17_18.endVersion)
        assertTrue(TensaiDbMigrations.MIGRATION_17_18 in TensaiDbMigrations.ALL)
    }

    @Test
    fun eighteenAddsOnlyTheMemoryFactsTable() {
        val added = entities(18).map { it.getString("tableName") } - entities(17).map { it.getString("tableName") }.toSet()

        assertEquals(listOf("memory_facts"), added)
    }

    @Test
    fun theEmittedSqlIsTheExportedCreateStatement() {
        val sql = recordedSql()
        val table = entities(18).first { it.getString("tableName") == "memory_facts" }

        assertEquals(1, sql.size)
        assertEquals(table.getString("createSql").replace("`\${TABLE_NAME}`", "`memory_facts`"), sql[0])
    }

    @Test
    fun memoryFactsHoldsTheFactColumns() {
        val table = entities(18).first { it.getString("tableName") == "memory_facts" }
        val fields = table.getJSONArray("fields")
        val columns = (0 until fields.length()).map { fields.getJSONObject(it) }.associateBy { it.getString("columnName") }

        assertEquals(setOf("id", "text", "createdAt", "sourceConversationId"), columns.keys)
        assertFalse("a fact may come from no conversation", columns.getValue("sourceConversationId").getBoolean("notNull"))
        assertTrue(columns.getValue("text").getBoolean("notNull"))
        assertTrue(table.getJSONObject("primaryKey").getBoolean("autoGenerate"))
    }
}
