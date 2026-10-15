package io.github.hokanosekai.tensai.data.models

import io.github.hokanosekai.tensai.engine.GgufInfo
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-only tests for [KvCacheShapeBackfill] — which rows a pass picks up, and, just as important,
 * which it leaves alone. See that class's KDoc for why it isn't inside `ModelRepository`.
 *
 * Two things shape these tests.
 *
 * The bug TEN-72 fixes is *invisible in the value*: a row derived by the superseded formula holds
 * a non-zero, perfectly ordinary-looking number. So [aRowDerivedByASupersededFormulaIsReRead]
 * gives the stale row a healthy number on purpose — the old `WHERE kvFullElementsPerToken = 0`
 * test would sail past it, and any test that used 0 to mean "stale" would pass without the fix.
 *
 * The other half is what a pass must *not* do. Re-deriving a shape can move the `n_ctx` the load
 * policy picks, and `SessionRepository` throws away every persisted session of a model whose
 * `nCtx` changed — tens of seconds of re-prefill per conversation. A pass that fired on every
 * start would be a permanent tax, so [aSecondPassAfterASuccessfulOneReadsNothing] runs the thing
 * twice and counts header reads rather than trusting the query.
 *
 * [FakeModelDao] really applies its updates. A fake that accepted `updateKvCacheShape` and threw
 * it away (as the fakes in the ViewModel tests do, having no use for it) would make every
 * assertion below unfalsifiable — the second pass would keep finding the same stale rows and
 * nothing would notice.
 */
class KvCacheShapeBackfillTest {

    /**
     * What the native library reports today. Arbitrary, and never referenced by production code:
     * the whole design of TEN-72 is that the app stores and compares this number without knowing
     * it, so a test that hard-coded the real value would be asserting on the wrong thing.
     */
    private val currentVersion = 4L

    private val dao = FakeModelDao()
    private val readPaths = mutableListOf<String>()

    // ---- rows a pass must pick up ----

    /**
     * The regression this issue is about. `m1` was imported between TEN-18's first formula and its
     * correction: it carries a *plausible* number that happens to be an under-count, and an
     * under-counted KV cache is what makes the app promise a context the device can't back.
     */
    @Test
    fun aRowDerivedByASupersededFormulaIsReRead() = runBlocking {
        dao.put(row(id = "m1", kvFull = 57_344, version = currentVersion - 1))

        backfill(shapeOf("m1" to header(kvFull = 114_688, version = currentVersion))).run()

        assertEquals(listOf("/models/m1.gguf"), readPaths)
        assertEquals(114_688L, dao.rows.getValue("m1").kvFullElementsPerToken)
        assertEquals(currentVersion, dao.rows.getValue("m1").kvShapeVersion)
    }

    /**
     * A row from before v8, carrying the migration's "derived by something unrecorded" 0.
     *
     * Non-regression, not a pin on TEN-72: the pre-TEN-72 code found this row too, since it had no
     * shape at all. It is here so a future change to the selection can't quietly drop the case the
     * v7 backfill did handle. What TEN-72 actually adds is the test above it.
     */
    @Test
    fun aRowWithNoRecordedVersionIsReRead() = runBlocking {
        dao.put(row(id = "m1", kvFull = 0, version = 0))

        backfill(shapeOf("m1" to header(kvFull = 114_688, version = currentVersion))).run()

        assertEquals(listOf("/models/m1.gguf"), readPaths)
        assertEquals(114_688L, dao.rows.getValue("m1").kvFullElementsPerToken)
    }

    /**
     * The submodule is pinned separately from the app, so the app can meet a version it has never
     * heard of — including one *ahead* of what it last wrote. That is a difference like any other,
     * not an error and not a downgrade to refuse: the numbers on the row were still produced by
     * code that isn't the code about to size the load.
     */
    @Test
    fun aRowFromAVersionAheadOfTheCurrentOneIsAlsoReRead() = runBlocking {
        dao.put(row(id = "m1", kvFull = 999_999, version = currentVersion + 12))

        backfill(shapeOf("m1" to header(kvFull = 114_688, version = currentVersion))).run()

        assertEquals(listOf("/models/m1.gguf"), readPaths)
        assertEquals(114_688L, dao.rows.getValue("m1").kvFullElementsPerToken)
        assertEquals(currentVersion, dao.rows.getValue("m1").kvShapeVersion)
    }

    // ---- rows a pass must leave alone ----

    /**
     * Nothing at all happens to a row already on the current formula — not even the header read.
     * Re-reading it would produce the same numbers and be harmless, which is exactly why it needs
     * asserting: "harmless" is how a per-start pass over every imported model gets shipped.
     */
    @Test
    fun aRowAlreadyOnTheCurrentFormulaIsNotEvenRead() = runBlocking {
        dao.put(row(id = "m1", kvFull = 114_688, version = currentVersion))

        backfill(shapeOf("m1" to header(kvFull = 1, version = currentVersion))).run()

        assertTrue("expected no header read at all, got $readPaths", readPaths.isEmpty())
        assertEquals(114_688L, dao.rows.getValue("m1").kvFullElementsPerToken)
    }

    /** Mixed library: the stale one is corrected, the current one isn't touched. */
    @Test
    fun onlyTheStaleRowsOfALibraryArePickedUp() = runBlocking {
        dao.put(row(id = "stale", kvFull = 57_344, version = currentVersion - 1))
        dao.put(row(id = "fresh", kvFull = 114_688, version = currentVersion))

        backfill(
            shapeOf(
                "stale" to header(kvFull = 114_688, version = currentVersion),
                "fresh" to header(kvFull = 1, version = currentVersion),
            ),
        ).run()

        assertEquals(listOf("/models/stale.gguf"), readPaths)
        assertEquals(114_688L, dao.rows.getValue("stale").kvFullElementsPerToken)
        assertEquals(114_688L, dao.rows.getValue("fresh").kvFullElementsPerToken)
    }

    // ---- capabilities (TEN-154) ----

    /**
     * A row from before v13 holds `supportsTools = false` because the column did not exist, not
     * because the model has no tools: the pass reads the template and corrects it, in the same
     * re-read as the shape.
     */
    @Test
    fun aStaleRowGetsItsToolsFromTheChatTemplate() = runBlocking {
        dao.put(row(id = "m1", kvFull = 114_688, version = currentVersion - 1))
        dao.put(row(id = "m2", kvFull = 114_688, version = currentVersion - 1))

        backfill(
            shapeOf(
                "m1" to header(kvFull = 114_688, version = currentVersion, template = "{%- if tools %}x{%- endif %}"),
                "m2" to header(kvFull = 114_688, version = currentVersion, template = "{{ messages }}"),
            ),
        ).run()

        assertTrue(dao.rows.getValue("m1").supportsTools)
        assertEquals(false, dao.rows.getValue("m2").supportsTools)
    }

    @Test
    fun aSeparateToolUseTemplateCountsAsToolSupport() = runBlocking {
        dao.put(row(id = "m1", kvFull = 114_688, version = 0))

        backfill(shapeOf("m1" to header(kvFull = 114_688, version = currentVersion, template = "{{ messages }}", toolUse = true))).run()

        assertTrue(dao.rows.getValue("m1").supportsTools)
    }

    @Test
    fun theThinkingFlagIsRefreshedFromTheHeaderToo() = runBlocking {
        dao.put(row(id = "m1", kvFull = 114_688, version = currentVersion - 1).copy(supportsThinking = false))

        backfill(shapeOf("m1" to header(kvFull = 114_688, version = currentVersion, thinking = true))).run()

        assertTrue(dao.rows.getValue("m1").supportsThinking)
    }

    @Test
    fun aRowAlreadyOnTheCurrentVersionKeepsItsCapabilities() = runBlocking {
        dao.put(row(id = "m1", kvFull = 114_688, version = currentVersion).copy(supportsTools = true))

        backfill(shapeOf("m1" to header(kvFull = 114_688, version = currentVersion, template = ""))).run()

        assertTrue(readPaths.isEmpty())
        assertTrue(dao.rows.getValue("m1").supportsTools)
    }

    // ---- the pass must not repeat itself ----

    /**
     * Two starts in a row, one pass. The second run is the one that would be re-prefilling every
     * conversation of every model, forever, if a corrected row didn't come back marked current.
     */
    @Test
    fun aSecondPassAfterASuccessfulOneReadsNothing() = runBlocking {
        dao.put(row(id = "m1", kvFull = 57_344, version = currentVersion - 1))
        dao.put(row(id = "m2", kvFull = 0, version = 0))
        val backfill = backfill(
            shapeOf(
                "m1" to header(kvFull = 114_688, version = currentVersion),
                "m2" to header(kvFull = 229_376, version = currentVersion),
            ),
        )

        backfill.run()
        val afterFirst = readPaths.toList()
        backfill.run()

        assertEquals(listOf("/models/m1.gguf", "/models/m2.gguf"), afterFirst)
        assertEquals("expected the second pass to read nothing", afterFirst, readPaths)
    }

    /**
     * A header the current formula can't get an attention shape out of is a *result*, not a
     * failure: asking the same formula the same question every start can only produce the same
     * nothing. The row is stamped and drops to `KvCacheSize`'s over-estimate, which is the safe
     * side and the same state a fresh import of that file would land in.
     */
    @Test
    fun aHeaderThatYieldsNoShapeIsStampedRatherThanReAskedForever() = runBlocking {
        dao.put(row(id = "m1", kvFull = 0, version = 0))
        val backfill = backfill(shapeOf("m1" to header(kvFull = 0, version = currentVersion)))

        backfill.run()
        backfill.run()

        assertEquals(listOf("/models/m1.gguf"), readPaths)
        assertEquals(0L, dao.rows.getValue("m1").kvFullElementsPerToken)
        assertEquals(currentVersion, dao.rows.getValue("m1").kvShapeVersion)
    }

    // ---- failure is per model, and is worth retrying ----

    /**
     * A file that has been moved or deleted leaves its row exactly as it was — including still
     * stale, so a later start tries again once the file is back. Unlike an unreadable header,
     * this says nothing about the model.
     */
    @Test
    fun aFileThatCannotBeReadLeavesItsRowAloneAndStale() = runBlocking {
        dao.put(row(id = "m1", kvFull = 57_344, version = currentVersion - 1))
        val backfill = backfill { throw IOException("no such file") }

        backfill.run()
        backfill.run()

        assertEquals(57_344L, dao.rows.getValue("m1").kvFullElementsPerToken)
        assertEquals(currentVersion - 1, dao.rows.getValue("m1").kvShapeVersion)
        assertEquals(listOf("/models/m1.gguf", "/models/m1.gguf"), readPaths)
    }

    /** One unreadable model does not cost the rest of the library its correction. */
    @Test
    fun oneUnreadableModelDoesNotAbandonTheRowsAfterIt() = runBlocking {
        dao.put(row(id = "broken", kvFull = 57_344, version = 0))
        dao.put(row(id = "fine", kvFull = 57_344, version = 0))
        val shapes = mapOf("fine" to header(kvFull = 114_688, version = currentVersion))

        backfill { path ->
            shapes[path.removePrefix("/models/").removeSuffix(".gguf")]
                ?: throw IOException("no such file")
        }.run()

        assertEquals(57_344L, dao.rows.getValue("broken").kvFullElementsPerToken)
        assertEquals(114_688L, dao.rows.getValue("fine").kvFullElementsPerToken)
    }

    // ---- helpers ----

    private fun backfill(readMetadata: suspend (String) -> GgufInfo) = KvCacheShapeBackfill(
        modelDao = dao,
        readMetadata = { path ->
            readPaths.add(path)
            readMetadata(path)
        },
        currentShapeVersion = { currentVersion },
    )

    /** Resolves `/models/<id>.gguf` back to an id, so a test can key its headers by model. */
    private fun shapeOf(vararg shapes: Pair<String, GgufInfo>): suspend (String) -> GgufInfo {
        val byId = shapes.toMap()
        return { path ->
            byId.getValue(path.removePrefix("/models/").removeSuffix(".gguf"))
        }
    }

    private fun header(kvFull: Long, version: Long, template: String = "", thinking: Boolean = true, toolUse: Boolean = false) = GgufInfo(
        architecture = "qwen3",
        name = "Qwen3 1.7B",
        quantLabel = "Q4_K_M",
        contextLength = 40_960,
        embeddingLength = 2048,
        blockCount = 28,
        paramCount = 1_700_000_000,
        vocabSize = 151_936,
        fileSizeBytes = 0,
        supportsThinking = thinking,
        chatTemplate = template,
        hasToolUseTemplate = toolUse,
        kvFullElementsPerToken = kvFull,
        kvShapeVersion = version,
    )

    private fun row(id: String, kvFull: Long, version: Long) = ModelEntity(
        id = id,
        displayName = id,
        fileName = "$id.gguf",
        filePath = "/models/$id.gguf",
        sizeBytes = 1_234_567,
        architecture = "qwen3",
        quantLabel = "Q4_K_M",
        paramCount = 1_700_000_000,
        contextLength = 40_960,
        embeddingLength = 2048,
        blockCount = 28,
        supportsThinking = true,
        importedAt = 1000,
        kvFullElementsPerToken = kvFull,
        kvShapeVersion = version,
    )

    /**
     * In-memory [ModelDao] that actually performs its updates — see this class's KDoc for why
     * that matters here specifically. Insertion order is preserved so the read order is assertable.
     */
    private class FakeModelDao : ModelDao {
        val rows = linkedMapOf<String, ModelEntity>()
        private val flow = MutableStateFlow<List<ModelEntity>>(emptyList())

        fun put(model: ModelEntity) {
            rows[model.id] = model
            flow.value = rows.values.toList()
        }

        override fun observeAll(): Flow<List<ModelEntity>> = flow

        override suspend fun getById(id: String): ModelEntity? = rows[id]

        override suspend fun withStaleKvCacheShape(kvShapeVersion: Long): List<ModelEntity> =
            rows.values.filter { it.kvShapeVersion != kvShapeVersion }

        override suspend fun updateKvCacheShape(
            id: String,
            kvFullElementsPerToken: Long,
            kvSwaElementsPerToken: Long,
            slidingWindow: Long,
            kvShapeVersion: Long,
            supportsThinking: Boolean,
            supportsTools: Boolean,
        ) {
            val existing = rows[id] ?: return
            rows[id] = existing.copy(
                kvFullElementsPerToken = kvFullElementsPerToken,
                kvSwaElementsPerToken = kvSwaElementsPerToken,
                slidingWindow = slidingWindow,
                kvShapeVersion = kvShapeVersion,
                supportsThinking = supportsThinking,
                supportsTools = supportsTools,
            )
            flow.value = rows.values.toList()
        }

        override suspend fun insert(model: ModelEntity) = put(model)

        override suspend fun delete(model: ModelEntity) {
            rows.remove(model.id)
            flow.value = rows.values.toList()
        }
    }
}
