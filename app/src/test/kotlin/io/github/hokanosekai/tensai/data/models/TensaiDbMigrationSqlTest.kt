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
 * [TensaiDbMigrations.MIGRATION_11_12] against the exported schemas, on the JVM (TEN-142). The
 * instrumented `TensaiDbMigrationTest` is the real proof (it runs the migration on SQLite and lets
 * Room diff the result), but it needs a device. This one catches the same drift with no device: the
 * SQL the migration emits is recorded, then compared with what changed between `11.json` and
 * `12.json`, the files Room generated from [ModelEntity].
 */
class TensaiDbMigrationSqlTest {

    private fun schema(version: Int): JSONObject {
        val file = File("schemas/io.github.hokanosekai.tensai.data.models.TensaiDb/$version.json")
        assertTrue("missing ${file.absolutePath}, build the app once to export it", file.exists())
        return JSONObject(file.readText())
    }

    private class Column(val name: String, val affinity: String, val notNull: Boolean)

    private fun modelColumns(schema: JSONObject): List<Column> {
        val entities = schema.getJSONObject("database").getJSONArray("entities")
        val models = (0 until entities.length()).map { entities.getJSONObject(it) }.first { it.getString("tableName") == "models" }
        val fields = models.getJSONArray("fields")
        return (0 until fields.length()).map {
            val f = fields.getJSONObject(it)
            Column(f.getString("columnName"), f.getString("affinity"), f.getBoolean("notNull"))
        }
    }

    private fun modelsCreateSql(schema: JSONObject): String {
        val entities = schema.getJSONObject("database").getJSONArray("entities")
        return (0 until entities.length()).map { entities.getJSONObject(it) }
            .first { it.getString("tableName") == "models" }.getString("createSql")
    }

    /** What [TensaiDbMigrations.MIGRATION_11_12] executes, in order. */
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
        TensaiDbMigrations.MIGRATION_11_12.migrate(db)
        return sql
    }

    @Test
    fun theMigrationGoesFromElevenToTwelve() {
        assertEquals(11, TensaiDbMigrations.MIGRATION_11_12.startVersion)
        assertEquals(12, TensaiDbMigrations.MIGRATION_11_12.endVersion)
        assertTrue(TensaiDbMigrations.MIGRATION_11_12 in TensaiDbMigrations.ALL)
    }

    @Test
    fun theChainIsContiguousAndEndsAtTheSchemaVersion() {
        val all = TensaiDbMigrations.ALL.sortedBy { it.startVersion }
        all.zipWithNext { a, b -> assertEquals("a gap after v${a.endVersion}", a.endVersion, b.startVersion) }
        assertEquals(1, all.first().startVersion)
        assertEquals(schema(12).getJSONObject("database").getInt("version"), all.last().endVersion)
    }

    @Test
    fun theMigrationAddsExactlyTheColumnsTwelveHasOverEleven() {
        val eleven = modelColumns(schema(11)).associateBy { it.name }
        val twelve = modelColumns(schema(12))
        val added = twelve.filter { it.name !in eleven }

        assertEquals(TensaiDbMigrations.ORIGIN_COLUMNS_11_12.map { it.first }, added.map { it.name })
        added.forEach { column ->
            assertFalse("${column.name} must be nullable, old rows have no value for it", column.notNull)
            assertEquals(column.name, TensaiDbMigrations.ORIGIN_COLUMNS_11_12.first { it.first == column.name }.second, column.affinity)
        }
        twelve.filter { it.name in eleven }.forEach {
            assertEquals("${it.name} changed type", eleven.getValue(it.name).affinity, it.affinity)
            assertEquals("${it.name} changed nullability", eleven.getValue(it.name).notNull, it.notNull)
        }
        assertEquals("no column dropped", eleven.keys, twelve.map { it.name }.filter { it in eleven }.toSet())
    }

    @Test
    fun theEmittedSqlIsAnAddColumnPerNewColumnAndNothingElse() {
        val sql = recordedSql()

        assertEquals(
            TensaiDbMigrations.ORIGIN_COLUMNS_11_12.map { (name, type) -> "ALTER TABLE `models` ADD COLUMN `$name` $type" },
            sql,
        )
        // The column definitions of the generated CREATE TABLE say the same thing (nullable: no NOT NULL).
        val create = modelsCreateSql(schema(12))
        TensaiDbMigrations.ORIGIN_COLUMNS_11_12.forEach { (name, type) ->
            assertTrue("`$name` $type missing from the v12 CREATE TABLE: $create", create.contains("`$name` $type"))
            assertFalse("`$name` must not be NOT NULL", create.contains("`$name` $type NOT NULL"))
        }
    }

    @Test
    fun theEntityFieldsAreTheV12Columns() {
        val twelve = modelColumns(schema(12)).map { it.name }.toSet()
        val entity = ModelEntity::class.java.declaredFields.map { it.name }
            .filter { it != "Companion" && !it.startsWith("$") }.toSet()

        assertEquals("ModelEntity and 12.json drifted apart, rebuild to re-export the schema", twelve, entity)
    }
}
