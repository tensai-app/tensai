package io.github.tensaiapp.data.models

import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TensaiDbMigrations.MIGRATION_18_19] against the exported schemas, on the JVM (TEN-196): the SQL it
 * emits is recorded and compared with what changed between `18.json` and `19.json`. Same idea as
 * [TensaiDbMigration16To17SqlTest].
 */
class TensaiDbMigration18To19SqlTest {

    private fun entity(version: Int, table: String): JSONObject {
        val file = File("schemas/io.github.tensaiapp.data.models.TensaiDb/$version.json")
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
        TensaiDbMigrations.MIGRATION_18_19.migrate(db)
        return sql
    }

    @Test
    fun theMigrationGoesFromEighteenToNineteenAndIsLastInTheChain() {
        assertEquals(18, TensaiDbMigrations.MIGRATION_18_19.startVersion)
        assertEquals(19, TensaiDbMigrations.MIGRATION_18_19.endVersion)
        assertEquals(TensaiDbMigrations.MIGRATION_18_19, TensaiDbMigrations.ALL.last())
    }

    @Test
    fun nineteenAddsWithoutToolsToSessions() {
        val added = columns(19, "sessions").filterKeys { it !in columns(18, "sessions") }
        assertEquals(setOf("withoutTools"), added.keys)
        assertEquals("INTEGER", added.getValue("withoutTools").getString("affinity"))
        assertTrue(added.getValue("withoutTools").getBoolean("notNull"))
        // As `conversations.toolsEnabled`: the entity declares no SQL default, and Room accepts the one the migration adds.
        assertTrue(entity(19, "sessions").getString("createSql").contains("`withoutTools` INTEGER NOT NULL"))
    }

    @Test
    fun theEmittedSqlAddsItAsUnknownForEveryExistingRow() {
        assertEquals(listOf("ALTER TABLE `sessions` ADD COLUMN `withoutTools` INTEGER NOT NULL DEFAULT 0"), recordedSql())
    }
}
