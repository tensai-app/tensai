package io.github.hokanosekai.tensai.data.models

import io.github.hokanosekai.tensai.engine.GgufInfo
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Fast, JVM-only unit tests for [ModelImporter] — the three failure modes TEN-53 closed, each
 * driven to a conclusion against a real temp directory and a [FakeModelDao], no device and no
 * GGUF involved. See [ModelImporter]'s KDoc for why the import logic lives in its own class
 * rather than inside `ModelRepository`, which cannot be constructed off a device.
 *
 * The tests assert on the **state of the directory afterwards**, not only on the exception that
 * came out: an orphaned partial copy is invisible from the throw site, and it was precisely by
 * looking only at the exception that the old code's leaks went unnoticed.
 */
class ModelImporterTest {

    private lateinit var modelsDir: File
    private lateinit var dao: FakeModelDao
    private lateinit var importer: ModelImporter

    /** Free space the importer is told it has — larger than anything these tests copy. */
    private val plentyOfSpace = { 1_000_000_000L }

    @Before
    fun setUp() {
        // A real temp dir under the JVM's own, never an app filesDir — same rule as
        // SessionRepositoryTest, for the reason in ModelRepository's storageRoot KDoc.
        modelsDir = Files.createTempDirectory("tensai-model-import-test").toFile()
        dao = FakeModelDao()
        importer = newImporter()
    }

    @After
    fun tearDown() {
        modelsDir.deleteRecursively()
    }

    private fun newImporter(readMetadata: suspend (String) -> GgufInfo = { ggufInfo() }) = ModelImporter(
        modelsDir = modelsDir,
        modelDao = dao,
        models = dao.observeAll(),
        readMetadata = readMetadata,
    )

    private fun filesOnDisk(): List<String> = modelsDir.listFiles().orEmpty().map { it.name }.sorted()

    // ---- 1. the race ----

    /**
     * Two imports of the same source name, running at the same time, must end up with a file each.
     *
     * The race is *forced*, not hoped for: both coroutines run on [Dispatchers.IO] (real threads,
     * not a test scheduler that would interleave them at suspension points of its own choosing)
     * and each one parks on a shared [CountDownLatch] inside `openSource` — which the importer
     * calls **after** reserving its destination and **before** writing a single byte. Neither can
     * get past that point until the other has also reserved, so the two reservations provably
     * overlap. That window is the bug: the old code picked a name with `exists()` and only opened
     * the output stream here, so with both coroutines parked, both were holding `model.gguf` and
     * whichever wrote second silently destroyed the other's copy.
     *
     * The two sources are given different sizes so that neither the pre-copy nor the post-copy
     * dedup check can fire and mask the file-level outcome this test is about.
     */
    @Test
    fun twoConcurrentImportsOfTheSameSourceNameEachGetTheirOwnFile() = runBlocking {
        val bothReserved = CountDownLatch(2)
        fun source(fill: Byte, size: Int): () -> InputStream = {
            bothReserved.countDown()
            check(bothReserved.await(10, TimeUnit.SECONDS)) {
                "the other import never reached its copy — the race never happened, so this test proves nothing"
            }
            ByteArrayInputStream(ByteArray(size) { fill })
        }

        val first = async(Dispatchers.IO) {
            importer.import("model.gguf", sourceSize = 64, plentyOfSpace, source(1, 64)) {}
        }
        val second = async(Dispatchers.IO) {
            importer.import("model.gguf", sourceSize = 96, plentyOfSpace, source(2, 96)) {}
        }
        val imported = listOf(first.await(), second.await())

        assertEquals(
            "two concurrent imports landed on the same path — one overwrote the other",
            2,
            imported.map { it.filePath }.toSet().size,
        )
        assertEquals(listOf("model-1.gguf", "model.gguf"), filesOnDisk())
        // Sizes, not just names: two distinct paths would still be a failure if the bytes of one
        // import had gone into the other's file.
        assertEquals(
            "each file must hold its own import's bytes",
            setOf(64L, 96L),
            imported.map { File(it.filePath).length() }.toSet(),
        )
        assertEquals(2, dao.rows.size)
    }

    // ---- 2. the cleanup ----

    /**
     * The SAF permission-revoked case from the ticket: a [SecurityException] out of the middle of
     * the copy. It is not an [IOException], so the old single `catch (e: IOException)` never saw
     * it and the partial file stayed on disk forever — unreferenced by any row, so nothing in the
     * app could ever offer to delete it.
     */
    @Test
    fun aSecurityExceptionMidCopyLeavesNothingOnDisk() = runBlocking {
        val thrown = importFailingWith(SecurityException("EACCES (Permission denied)"))

        assertTrue("expected the copy failure to be classified, got $thrown", thrown is ModelImportException)
        assertTrue("expected the SecurityException to survive as the cause", thrown.cause is SecurityException)
        assertEquals("no partial copy may survive the failure", emptyList<String>(), filesOnDisk())
        assertTrue("nothing should have been persisted", dao.rows.isEmpty())
    }

    /** Same contract for a throwable nobody anticipated — the cleanup cannot be per-type. */
    @Test
    fun anUnanticipatedExceptionMidCopyAlsoLeavesNothingOnDisk() = runBlocking {
        val thrown = importFailingWith(IllegalStateException("something nobody listed"))

        assertTrue("expected a classified copy failure, got $thrown", thrown is ModelImportException)
        assertEquals(emptyList<String>(), filesOnDisk())
        assertTrue(dao.rows.isEmpty())
    }

    /** And for a failure after the copy has fully succeeded — a header that will not parse. */
    @Test
    fun aMetadataFailureAfterACompleteCopyLeavesNothingOnDisk() = runBlocking {
        importer = newImporter(readMetadata = { throw IOException("Failed to read magic at offset 0") })

        try {
            importer.import("model.gguf", 64, plentyOfSpace, { ByteArrayInputStream(ByteArray(64)) }) {}
            fail("expected ModelMetadataException")
        } catch (expected: ModelMetadataException) {
            assertEquals("model.gguf", expected.fileName)
        }

        assertEquals("the copy is complete but useless — it must not be kept", emptyList<String>(), filesOnDisk())
        assertTrue(dao.rows.isEmpty())
    }

    /**
     * TEN-98: a header that parses but describes no model (zero tensors, the TEN-91 fixture) can
     * never load, so it is refused like an unparseable one and nothing is kept.
     */
    @Test
    fun aGgufWithZeroParametersIsRejectedAndLeavesNothingBehind() = runBlocking {
        importer = newImporter(readMetadata = { ggufInfo(paramCount = 0) })
        assertRejectedAsNotAModel()
    }

    /** Same for a missing block count: llama.cpp requires `<arch>.block_count`. */
    @Test
    fun aGgufWithZeroBlocksIsRejectedAndLeavesNothingBehind() = runBlocking {
        importer = newImporter(readMetadata = { ggufInfo(blockCount = 0) })
        assertRejectedAsNotAModel()
    }

    private suspend fun assertRejectedAsNotAModel() {
        try {
            importer.import("model.gguf", 64, plentyOfSpace, { ByteArrayInputStream(ByteArray(64)) }) {}
            fail("expected ModelMetadataException")
        } catch (expected: ModelMetadataException) {
            assertEquals("model.gguf", expected.fileName)
        }

        assertEquals("the copy must be removed", emptyList<String>(), filesOnDisk())
        assertTrue("no row may be inserted", dao.rows.isEmpty())
    }

    /**
     * The reservation is a real file from the moment it is made, so the empty file it leaves
     * behind has to be cleaned up on the same path as a half-written one. This is the case the
     * atomic-creation approach opens and this is where it is closed: the failure here happens
     * before the copy writes anything at all.
     */
    @Test
    fun anEmptyReservationIsCleanedUpWhenTheSourceCannotEvenBeOpened() = runBlocking {
        try {
            importer.import("model.gguf", 64, plentyOfSpace, { throw IOException("stream is gone") }) {}
            fail("expected ModelImportException")
        } catch (expected: ModelImportException) {
            assertTrue(expected.cause is IOException)
        }

        assertEquals("the reservation must not outlive the import", emptyList<String>(), filesOnDisk())
    }

    // ---- 3. the cancellation ----

    /**
     * Cancelling mid-copy must come out as a [CancellationException] and take the partial file
     * with it.
     *
     * The stream is deliberately long and slow rather than endless: if the importer ever stops
     * observing cancellation during the copy, this test ends by *succeeding* at the import within
     * a second, and the assertion below fails with a readable message instead of hanging the
     * suite forever.
     */
    @Test
    fun cancellingDuringTheCopyPropagatesCancellationAndLeavesNothingOnDisk() = runBlocking {
        val copyStarted = CompletableDeferred<Unit>()
        var outcome: Throwable? = null

        val job = launch(Dispatchers.IO) {
            try {
                importer.import("model.gguf", null, plentyOfSpace, { SlowInputStream(copyStarted) }) {}
            } catch (e: Throwable) {
                outcome = e
                throw e
            }
        }
        copyStarted.await()
        job.cancelAndJoin()

        assertNotNull("the import ran to completion instead of noticing it had been cancelled", outcome)
        assertTrue(
            "a cancelled copy must surface as a cancellation, not as $outcome",
            outcome is CancellationException,
        )
        assertEquals("the partial copy must go with the cancellation", emptyList<String>(), filesOnDisk())
        assertTrue(dao.rows.isEmpty())
    }

    /**
     * The defect the ticket names: cancelling while the GGUF header is being read used to be
     * caught by the broad `catch (e: Exception)` around it and re-thrown as a
     * [ModelMetadataException] — so closing the screen mid-import told the user their file was
     * probably not a GGUF, and swallowed the cancellation on the way.
     */
    @Test
    fun cancellingDuringTheMetadataReadPropagatesCancellationRatherThanAMetadataFailure() = runBlocking {
        val inMetadata = CompletableDeferred<Unit>()
        importer = newImporter(readMetadata = { inMetadata.complete(Unit); awaitCancellation() })
        var outcome: Throwable? = null

        val job = launch(Dispatchers.IO) {
            try {
                importer.import("model.gguf", 64, plentyOfSpace, { ByteArrayInputStream(ByteArray(64)) }) {}
            } catch (e: Throwable) {
                outcome = e
                throw e
            }
        }
        inMetadata.await()
        job.cancelAndJoin()

        assertFalse("a cancellation is not a verdict on the file", outcome is ModelMetadataException)
        assertTrue("expected a CancellationException, got $outcome", outcome is CancellationException)
        assertEquals(emptyList<String>(), filesOnDisk())
        assertTrue(dao.rows.isEmpty())
    }

    // ---- the paths that must still work ----

    @Test
    fun aPlainImportCopiesTheBytesReportsProgressAndPersistsTheRow() = runBlocking {
        val progress = mutableListOf<Float>()
        val bytes = ByteArray(1024) { 9.toByte() }

        val entity = importer.import("qwen.gguf", 1024, plentyOfSpace, { ByteArrayInputStream(bytes) }) {
            progress.add(it)
        }

        assertEquals(listOf("qwen.gguf"), filesOnDisk())
        assertTrue(File(entity.filePath).readBytes().contentEquals(bytes))
        assertEquals(1024L, entity.sizeBytes)
        assertTrue("expected progress to reach 1.0, got $progress", progress.contains(1f))
        assertEquals(listOf(entity), dao.rows)
    }

    /**
     * The import half of TEN-72's "one backfill pass, ever": a model imported now already carries
     * the shape version that produced its numbers, so `KvCacheShapeBackfill` never selects it.
     * Leave this off and every fresh import lands on the migration's `0`, which no longer matches
     * anything the library reports — so its header would be re-read on every start, forever.
     */
    @Test
    fun anImportRecordsWhichFormulaDerivedItsKvShape() = runBlocking {
        val entity = importer.import("qwen.gguf", 64, plentyOfSpace, { ByteArrayInputStream(ByteArray(64)) }) {}

        assertEquals(SHAPE_VERSION, entity.kvShapeVersion)
        assertEquals(SHAPE_VERSION, dao.rows.single().kvShapeVersion)
    }

    @Test
    fun aSecondImportOfTheSameNameAndSizeIsRejectedBeforeAnythingIsCopied() = runBlocking {
        importer.import("qwen.gguf", 64, plentyOfSpace, { ByteArrayInputStream(ByteArray(64)) }) {}

        try {
            importer.import("qwen.gguf", 64, plentyOfSpace, { ByteArrayInputStream(ByteArray(64)) }) {}
            fail("expected DuplicateModelException")
        } catch (expected: DuplicateModelException) {
            assertEquals("qwen.gguf", expected.fileName)
        }

        assertEquals("the refused import must not have reserved anything", listOf("qwen.gguf"), filesOnDisk())
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun anImportLargerThanTheFreeSpaceIsRefusedBeforeAnythingIsCopied() = runBlocking {
        try {
            importer.import("qwen.gguf", 4_000_000_000, { 1_000_000L }, { ByteArrayInputStream(ByteArray(0)) }) {}
            fail("expected InsufficientStorageException")
        } catch (expected: InsufficientStorageException) {
            assertEquals(4_000_000_000, expected.neededBytes)
            assertEquals(1_000_000, expected.freeBytes)
        }

        assertEquals(emptyList<String>(), filesOnDisk())
    }

    /** Runs an import whose source blows up partway through, and returns what came out. */
    private suspend fun importFailingWith(failure: Throwable): Throwable {
        try {
            importer.import("model.gguf", null, plentyOfSpace, { FailingInputStream(failure) }) {}
        } catch (e: Throwable) {
            return e
        }
        throw AssertionError("expected the import to fail with $failure")
    }

    /** Serves one buffer of bytes, then throws [failure] — a copy that breaks partway. */
    private class FailingInputStream(private val failure: Throwable) : InputStream() {
        private var served = false

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served) throw failure
            served = true
            b.fill(1.toByte(), off, off + minOf(len, 4096))
            return minOf(len, 4096)
        }

        override fun read(): Int = throw UnsupportedOperationException("the copy reads in buffers")
    }

    /**
     * A source that takes about a second to serve half a megabyte, completing [started] on its
     * first read. Bounded on purpose — see the cancellation test's KDoc.
     */
    private class SlowInputStream(private val started: CompletableDeferred<Unit>) : InputStream() {
        private var chunksLeft = 500

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            started.complete(Unit)
            if (chunksLeft-- <= 0) return -1
            Thread.sleep(2)
            val n = minOf(len, 1024)
            b.fill(3.toByte(), off, off + n)
            return n
        }

        override fun read(): Int = throw UnsupportedOperationException("the copy reads in buffers")
    }

    // ---- capabilities (TEN-154) ----

    @Test
    fun aNewModelGetsItsToolsAndThinkingFromTheHeader() = runBlocking {
        importer = newImporter(readMetadata = { ggufInfo(template = "{%- if tools %}{{ enable_thinking }}{%- endif %}") })

        val stored = importMine()

        assertTrue(stored.supportsTools)
        assertTrue(stored.supportsThinking)
    }

    @Test
    fun aTemplateWithoutToolsLeavesTheFlagOff() = runBlocking {
        importer = newImporter(readMetadata = { ggufInfo(template = "{{ messages }}") })

        assertEquals(false, importMine().supportsTools)
    }

    // ---- re-import of a missing file (TEN-122) ----

    private suspend fun importMine(using: ModelImporter = importer) =
        using.import("m.gguf", sourceSize = 64, plentyOfSpace, { ByteArrayInputStream(ByteArray(64)) }) {}

    /** The row survives a re-import of its own file: same id, new path that exists. */
    @Test
    fun reImportingAMissingFileRelinksTheRowAndKeepsItsId() = runBlocking {
        val first = importMine()
        File(first.filePath).delete()

        val again = importMine()

        assertEquals("relink must keep the id so saved sessions stay attached", first.id, again.id)
        assertEquals(1, dao.rows.size)
        assertEquals(again, dao.rows.single())
        assertTrue(File(again.filePath).exists())
    }

    /** A failed re-import changes nothing: the row is the one it was, and no copy is left. */
    @Test
    fun aFailedReImportLeavesTheMissingRowUntouched() = runBlocking {
        val first = importMine()
        File(first.filePath).delete()

        try {
            importMine(newImporter(readMetadata = { throw IOException("bad header") }))
            fail("expected ModelMetadataException")
        } catch (expected: ModelMetadataException) {
            // expected
        }

        assertEquals(listOf(first), dao.rows)
        assertEquals(emptyList<String>(), filesOnDisk())
    }

    /** A row whose file is still there keeps rejecting the same file. */
    @Test
    fun reImportingAPresentFileIsStillADuplicate() = runBlocking {
        importMine()
        try {
            importMine()
            fail("expected DuplicateModelException")
        } catch (expected: DuplicateModelException) {
            // expected
        }
        assertEquals(1, dao.rows.size)
    }

    /**
     * The relink is one atomic write: if it fails, the old row is still there and the new copy is
     * removed. (A delete-then-insert would have lost the row here.)
     */
    @Test
    fun aFailedRelinkWriteKeepsTheOldRowAndRemovesTheCopy() = runBlocking {
        val first = importMine()
        File(first.filePath).delete()
        dao.failNextInsert = IOException("disk full")

        try {
            importMine()
            fail("expected the insert failure")
        } catch (expected: IOException) {
            // expected
        }

        assertEquals(listOf(first), dao.rows)
        assertEquals(emptyList<String>(), filesOnDisk())
    }

    /**
     * A competing relink of the same row commits while this one is still copying. Under the commit
     * lock the importer re-reads the row, finds the other one's file on disk and refuses this one
     * as the duplicate it now is, removing its copy: no file is left without a row. (Simulated
     * from inside the source stream, which runs after the destination is reserved and before the
     * commit, so the interleaving is exact and not left to thread timing.)
     */
    @Test
    fun aRelinkThatLosesToAConcurrentOneIsRefusedAndLeavesNoOrphanFile() = runBlocking {
        val first = importMine()
        File(first.filePath).delete()
        val winnerFile = File(modelsDir, "winner.gguf").apply { writeBytes(ByteArray(64)) }
        val winnerRow = first.copy(filePath = winnerFile.absolutePath)

        val source: () -> InputStream = {
            runBlocking { dao.insert(winnerRow) }
            ByteArrayInputStream(ByteArray(64))
        }
        try {
            importer.import("m.gguf", 64, plentyOfSpace, source) {}
            fail("expected DuplicateModelException")
        } catch (expected: DuplicateModelException) {
            // expected
        }

        assertEquals(listOf(winnerRow), dao.rows)
        assertEquals(listOf("winner.gguf"), filesOnDisk())
    }

    // ---- a finished download takes the same road (TEN-141) ----

    private fun partFile(name: String = "dl.part", content: String = "gguf-bytes"): File =
        File(modelsDir, name).also { it.writeText(content) }

    @Test
    fun adoptRenamesThePartAndStoresTheRow() = runBlocking {
        val part = partFile()

        val stored = importer.adopt(part, "model.gguf").model

        assertEquals(listOf("model.gguf"), filesOnDisk())
        assertFalse("the part file is gone, renamed", part.exists())
        assertEquals("gguf-bytes", File(stored.filePath).readText())
        assertEquals(10L, stored.sizeBytes)
        assertEquals(listOf(stored), dao.rows)
    }

    @Test
    fun adoptPicksAFreeNameWhenTheOneItWantsIsTaken() = runBlocking {
        File(modelsDir, "model.gguf").writeText("someone else")

        val stored = importer.adopt(partFile(), "model.gguf").model

        assertEquals("model-1.gguf", File(stored.filePath).name)
        assertEquals("someone else", File(modelsDir, "model.gguf").readText())
    }

    @Test
    fun adoptRefusesADuplicateAndLeavesThePartAlone() = runBlocking {
        importer.adopt(partFile("first.part"), "model.gguf")
        val second = partFile("second.part")

        try {
            importer.adopt(second, "model.gguf")
            fail("expected DuplicateModelException")
        } catch (_: DuplicateModelException) {
        }

        assertTrue("the caller still owns its part", second.exists())
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun adoptPutsTheFileBackAsThePartWhenTheDatabaseFails() = runBlocking {
        val part = partFile()
        dao.failNextInsert = IllegalStateException("database is locked")

        try {
            importer.adopt(part, "model.gguf")
            fail("expected the database failure")
        } catch (_: IllegalStateException) {
        }

        assertTrue("the finished download is still there to register again", part.exists())
        assertEquals(listOf("dl.part"), filesOnDisk())
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun adoptOfSomethingThatIsNotAModelDeletesTheDestinationAndKeepsNoRow() = runBlocking {
        val bad = newImporter(readMetadata = { throw IOException("not a gguf") })

        try {
            bad.adopt(partFile(), "model.gguf")
            fail("expected ModelMetadataException")
        } catch (_: ModelMetadataException) {
        }

        assertEquals(emptyList<String>(), filesOnDisk())
        assertTrue(dao.rows.isEmpty())
    }

    // ---- the origin of a downloaded model (TEN-142) ----

    private val origin = ModelOrigin(
        repo = "bartowski/Qwen3-GGUF",
        file = "sub/Qwen3-Q4_K_M.gguf",
        quant = "Q4_K_M",
        sha256 = "ab".repeat(32),
        commit = "c".repeat(40),
        downloadedAt = 1_700_000_000_000,
    )

    @Test
    fun adoptStoresTheOriginOnTheRow() = runBlocking {
        val stored = importer.adopt(partFile(), "model.gguf", origin).model

        assertEquals(origin, stored.origin)
        assertEquals("bartowski/Qwen3-GGUF", stored.sourceRepo)
        assertEquals("sub/Qwen3-Q4_K_M.gguf", stored.sourceFile)
        assertEquals("Q4_K_M", stored.sourceQuant)
        assertEquals("ab".repeat(32), stored.sourceSha256)
        assertEquals("c".repeat(40), stored.sourceCommit)
        assertEquals(1_700_000_000_000, stored.downloadedAt)
        assertEquals(listOf(stored), dao.rows)
    }

    @Test
    fun anImportHasNoOrigin() = runBlocking {
        val stored = importMine()

        assertNull(stored.origin)
        assertNull(stored.sourceRepo)
        assertNull(stored.downloadedAt)
    }

    @Test
    fun adoptWithoutAnOriginStoresNone() = runBlocking {
        assertNull(importer.adopt(partFile(), "model.gguf").model.origin)
    }

    @Test
    fun relinkingAMissingModelThroughADownloadKeepsItsIdAndWritesTheOrigin() = runBlocking {
        val first = importer.adopt(partFile("a.part"), "model.gguf", origin).model
        File(first.filePath).delete()
        val newer = origin.copy(commit = "d".repeat(40), downloadedAt = 1_800_000_000_000)

        val registered = importer.adopt(partFile("b.part"), "model.gguf", newer)

        assertEquals("same id, so saved sessions stay attached", first.id, registered.model.id)
        assertEquals(first, registered.previous)
        assertEquals(newer, registered.model.origin)
        assertEquals(listOf(registered.model), dao.rows)
    }

    @Test
    fun relinkingAnOriginRowWithAPlainImportKeepsItsOrigin() = runBlocking {
        val first = importer.adopt(partFile("a.part"), "model.gguf", origin).model
        File(first.filePath).delete()

        val again = importer.import("model.gguf", 10, plentyOfSpace, { ByteArrayInputStream(ByteArray(10)) }) {}

        assertEquals(first.id, again.id)
        assertEquals(origin, again.origin)
    }

    /** A relink is the user's own model again, so the context they chose for it (TEN-150) stays chosen. */
    @Test
    fun reImportingAMissingFileKeepsTheModelsContextOverride() = runBlocking {
        val first = importMine()
        dao.insert(first.copy(contextOverride = "K8"))
        File(first.filePath).delete()

        val again = importMine()

        assertEquals(first.id, again.id)
        assertEquals("K8", again.contextOverride)
        assertEquals("K8", dao.rows.single().contextOverride)
    }

    /** In-memory stand-in for Room, same idea as `SessionRepositoryTest`'s `FakeSessionDao`. */
    private class FakeModelDao : ModelDao {
        /** Makes the next insert throw before touching anything, like a transaction that rolled back. */
        var failNextInsert: Throwable? = null

        val rows = mutableListOf<ModelEntity>()
        private val flow = MutableStateFlow<List<ModelEntity>>(emptyList())

        override fun observeAll(): Flow<List<ModelEntity>> = flow

        override suspend fun getById(id: String): ModelEntity? = rows.find { it.id == id }

        override suspend fun setContextOverride(id: String, contextOverride: String?) = Unit

        override suspend fun withStaleKvCacheShape(kvShapeVersion: Long): List<ModelEntity> =
            rows.filter { it.kvShapeVersion != kvShapeVersion }

        override suspend fun updateKvCacheShape(
            id: String,
            kvFullElementsPerToken: Long,
            kvSwaElementsPerToken: Long,
            slidingWindow: Long,
            kvShapeVersion: Long,
            supportsThinking: Boolean,
            supportsTools: Boolean,
        ) = Unit

        // Synchronized because the race test really does insert from two threads at once.
        override suspend fun insert(model: ModelEntity) = synchronized(rows) {
            failNextInsert?.let { failNextInsert = null; throw it }
            // REPLACE on the primary key, one atomic step, like the real DAO.
            rows.removeAll { it.id == model.id }
            rows.add(model)
            flow.value = rows.toList()
        }

        override suspend fun delete(model: ModelEntity) = synchronized(rows) {
            rows.remove(model)
            flow.value = rows.toList()
        }
    }

    private fun ggufInfo(paramCount: Long = 1_700_000_000, blockCount: Long = 28, template: String = "") = GgufInfo(
        architecture = "qwen3",
        name = "",
        quantLabel = "Q4_K_M",
        contextLength = 4096,
        embeddingLength = 2048,
        blockCount = blockCount,
        paramCount = paramCount,
        vocabSize = 151_936,
        fileSizeBytes = 0,
        supportsThinking = true,
        chatTemplate = template,
        kvFullElementsPerToken = 114_688,
        kvShapeVersion = SHAPE_VERSION,
    )

    private companion object {
        /**
         * Stands in for whatever `tensai_jni.cpp` reports (TEN-72) — an arbitrary number on
         * purpose, since nothing on the Kotlin side is allowed to know or assume it.
         */
        const val SHAPE_VERSION = 7L
    }
}
