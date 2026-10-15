package io.github.hokanosekai.tensai.data.models

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one thing about [ModelDao] that cannot be checked without SQLite: what
 * [ModelDao.withStaleKvCacheShape] actually selects.
 *
 * `KvCacheShapeBackfillTest` covers the backfill's logic on the JVM, but it necessarily runs
 * against a fake DAO that re-implements the predicate in Kotlin — so it would pass just as
 * happily over a `@Query` that said `=` where it means `!=`. Room only checks that the columns
 * exist. That leaves the comparison itself unverified anywhere else, and getting a comparison
 * subtly wrong is exactly the shape of the bug TEN-72 is fixing.
 *
 * Unlike `ModelRepositoryTest` this needs no pushed GGUF: in-memory Room and rows inserted by
 * hand, nothing else.
 */
@RunWith(AndroidJUnit4::class)
class ModelDaoTest {

    private lateinit var db: TensaiDb
    private lateinit var dao: ModelDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, TensaiDb::class.java).build()
        dao = db.modelDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Every version other than the current one is stale, in both directions. The pre-v8 `0`, a
     * superseded formula's number and a version from a submodule newer than this app all come
     * back; only the row that matches is left out.
     */
    @Test
    fun withStaleKvCacheShapeReturnsEveryRowThatDoesNotMatch() = runBlocking {
        dao.insert(model(id = "unrecorded", kvShapeVersion = 0))
        dao.insert(model(id = "superseded", kvShapeVersion = 1))
        dao.insert(model(id = "current", kvShapeVersion = 2))
        dao.insert(model(id = "ahead", kvShapeVersion = 3))

        val stale = dao.withStaleKvCacheShape(2).map { it.id }.sorted()

        assertEquals(listOf("ahead", "superseded", "unrecorded"), stale)
    }

    /** Nothing to do once every row has been stamped — the "one pass, ever" guarantee, in SQL. */
    @Test
    fun withStaleKvCacheShapeReturnsNothingOnceEveryRowMatches() = runBlocking {
        dao.insert(model(id = "m1", kvShapeVersion = 2))
        dao.insert(model(id = "m2", kvShapeVersion = 2))

        assertEquals(emptyList<ModelEntity>(), dao.withStaleKvCacheShape(2))
    }

    /**
     * The write half: [ModelDao.updateKvCacheShape] must stamp the version along with the three
     * numbers, or the row it just corrected stays selected forever.
     */
    @Test
    fun updateKvCacheShapeStampsTheVersionWithTheNumbers() = runBlocking {
        dao.insert(model(id = "m1", kvShapeVersion = 1, kvFullElementsPerToken = 57_344))

        dao.updateKvCacheShape(
            id = "m1",
            kvFullElementsPerToken = 114_688,
            kvSwaElementsPerToken = 0,
            slidingWindow = 0,
            kvShapeVersion = 2,
            supportsThinking = true,
            supportsTools = true,
        )

        val updated = checkNotNull(dao.getById("m1"))
        assertEquals(114_688L, updated.kvFullElementsPerToken)
        assertEquals(2L, updated.kvShapeVersion)
        assertEquals(emptyList<ModelEntity>(), dao.withStaleKvCacheShape(2))
    }

    private fun model(
        id: String,
        kvShapeVersion: Long,
        kvFullElementsPerToken: Long = 114_688,
    ) = ModelEntity(
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
        kvFullElementsPerToken = kvFullElementsPerToken,
        kvShapeVersion = kvShapeVersion,
    )
}
