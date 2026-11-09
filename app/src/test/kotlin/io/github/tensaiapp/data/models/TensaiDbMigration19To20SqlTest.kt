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
 * [TensaiDbMigrations.MIGRATION_19_20] against the exported schemas, on the JVM (TEN-224): the SQL it
 * emits is recorded and compared with what changed between `19.json` and `20.json`. Same idea as
 * [TensaiDbMigration18To19SqlTest].
 */
class TensaiDbMigration19To20SqlTest {

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
        TensaiDbMigrations.MIGRATION_19_20.migrate(db)
        return sql
    }

    @Test
    fun theMigrationGoesFromNineteenToTwentyAndIsLastInTheChain() {
        assertEquals(19, TensaiDbMigrations.MIGRATION_19_20.startVersion)
        assertEquals(20, TensaiDbMigrations.MIGRATION_19_20.endVersion)
        assertEquals(TensaiDbMigrations.MIGRATION_19_20, TensaiDbMigrations.ALL.last())
    }

    @Test
    fun twentyAddsSendStateToMessages() {
        val added = columns(20, "messages").filterKeys { it !in columns(19, "messages") }
        assertEquals(setOf("sendState"), added.keys)
        assertEquals("TEXT", added.getValue("sendState").getString("affinity"))
        assertFalse(added.getValue("sendState").optBoolean("notNull", false))
    }

    @Test
    fun theEmittedSqlAddsItAsSentForEveryExistingRow() {
        assertEquals(listOf("ALTER TABLE `messages` ADD COLUMN `sendState` TEXT"), recordedSql())
    }
}
