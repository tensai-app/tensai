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
 * [TensaiDbMigrations.MIGRATION_14_15] against the exported schemas, on the JVM (TEN-159): the SQL it
 * emits is recorded and compared with what changed between `14.json` and `15.json`. Same idea as
 * [TensaiDbMigration12To13SqlTest].
 */
class TensaiDbMigration14To15SqlTest {

    private fun columns(version: Int, table: String): Map<String, JSONObject> {
        val file = File("schemas/io.github.hokanosekai.tensai.data.models.TensaiDb/$version.json")
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
        TensaiDbMigrations.MIGRATION_14_15.migrate(db)
        return sql
    }

    @Test
    fun theMigrationGoesFromFourteenToFifteenAndIsInTheChain() {
        assertEquals(14, TensaiDbMigrations.MIGRATION_14_15.startVersion)
        assertEquals(15, TensaiDbMigrations.MIGRATION_14_15.endVersion)
        assertTrue(TensaiDbMigrations.MIGRATION_14_15 in TensaiDbMigrations.ALL)
    }

    @Test
    fun fifteenAddsTheTwoModelColumnsAndTheSessionOne() {
        val models = columns(15, "models").filterKeys { it !in columns(14, "models") }
        assertEquals(setOf("hasChatTemplate", "chatTemplateOverride"), models.keys)
        assertEquals("INTEGER", models.getValue("hasChatTemplate").getString("affinity"))
        assertTrue(models.getValue("hasChatTemplate").getBoolean("notNull"))
        assertEquals("TEXT", models.getValue("chatTemplateOverride").getString("affinity"))
        assertFalse("null is Auto, every existing row", models.getValue("chatTemplateOverride").getBoolean("notNull"))

        val sessions = columns(15, "sessions").filterKeys { it !in columns(14, "sessions") }
        assertEquals(setOf("chatTemplate"), sessions.keys)
        assertEquals("TEXT", sessions.getValue("chatTemplate").getString("affinity"))
        assertTrue(sessions.getValue("chatTemplate").getBoolean("notNull"))
    }

    @Test
    fun theEmittedSqlAddsThemAndMakesEveryRowStaleForTheBackfill() {
        assertEquals(
            listOf(
                "ALTER TABLE `models` ADD COLUMN `hasChatTemplate` INTEGER NOT NULL DEFAULT 1",
                "ALTER TABLE `models` ADD COLUMN `chatTemplateOverride` TEXT",
                "ALTER TABLE `sessions` ADD COLUMN `chatTemplate` TEXT NOT NULL DEFAULT ''",
                "UPDATE `models` SET `kvShapeVersion` = 0",
            ),
            recordedSql(),
        )
    }
}
