package io.github.tensaiapp.data.models

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tensaiapp.engine.LlamaKtEngineImpl
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [ModelRepository] against a real GGUF file — same test-model
 * convention as `EngineSmokeTest`, skipped (not failed) when absent:
 *
 * ```
 * adb push some-small-model.gguf \
 *   /sdcard/Android/data/io.github.tensaiapp/files/test-model.gguf
 * ```
 *
 * What is left here is only what genuinely needs the framework: [ModelRepository] itself is
 * inseparable from `Context` (`ContentResolver` for the source `Uri`, `StatFs` for free space)
 * and the project has neither Robolectric nor a mocking library to fake those. The import logic
 * proper — dedup, destination reservation, cleanup, cancellation — moved to [ModelImporter] in
 * TEN-53 and is covered by `ModelImporterTest` on the JVM, which is where the concurrency and
 * failure cases belong: they need to be provoked, not waited for.
 */
@RunWith(AndroidJUnit4::class)
class ModelRepositoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val sourceModelFile: File by lazy {
        File(context.getExternalFilesDir(null), "test-model.gguf")
    }

    private lateinit var db: TensaiDb
    private lateinit var repository: ModelRepository
    private lateinit var engine: LlamaKtEngineImpl

    // Isolated storage root for this test — NEVER context.filesDir. The repository deletes/
    // overwrites files under `<root>/models/`; pointing that at the real filesDir wiped the user's
    // imported models when this suite ran (TEN-48 incident). A fresh temp dir keeps the test's
    // file I/O entirely inside its own sandbox.
    private lateinit var storageRoot: File

    @Before
    fun setUp() {
        assumeTrue(
            "No test model at ${sourceModelFile.absolutePath} — skipping " +
                "(adb push a GGUF there to run this test)",
            sourceModelFile.exists(),
        )
        storageRoot = File(context.cacheDir, "model-repo-test-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(context, TensaiDb::class.java).build()
        engine = LlamaKtEngineImpl()
        repository = ModelRepository(
            context = context,
            modelDao = db.modelDao(),
            readMetadata = engine::readMetadata,
            kvShapeVersion = engine::kvShapeVersion,
            currentLoadedModelId = { null },
            storageRoot = storageRoot,
        )
    }

    @After
    fun tearDown() {
        // setUp may have bailed on its assumeTrue before initializing anything —
        // tearDown must not turn that skip into a failure.
        if (!::db.isInitialized) return
        db.close()
        // TEN-66: this suite builds an engine per test, only to borrow its `readMetadata`. It is
        // still a whole engine, with a `llama-kt-engine` thread nothing else ever reclaims, so it
        // gets closed like any other. No model is ever loaded on it here, so there is nothing to
        // free first.
        engine.close()
        // Only ever deletes this test's own temp sandbox — never the app's real storage.
        storageRoot.deleteRecursively()
    }

    @Test
    fun importReadsMetadataAndPersists() = runBlocking {
        val progressValues = mutableListOf<Float>()

        val entity = repository.importModel(Uri.fromFile(sourceModelFile)) { progress ->
            progressValues.add(progress)
        }

        assertTrue("expected the imported file to exist at ${entity.filePath}", File(entity.filePath).exists())
        assertEquals(sourceModelFile.length(), entity.sizeBytes)
        assertTrue("architecture should not be blank", entity.architecture.isNotBlank())
        assertTrue("quantLabel should not be blank", entity.quantLabel.isNotBlank())
        assertTrue("contextLength should be > 0, was ${entity.contextLength}", entity.contextLength > 0)
        assertTrue("expected progress to reach 1.0, got $progressValues", progressValues.contains(1f))

        val stored = repository.models.first()
        assertEquals(1, stored.size)
        assertEquals(entity.id, stored.single().id)
    }

    @Test
    fun duplicateImportThrows() = runBlocking {
        repository.importModel(Uri.fromFile(sourceModelFile)) { }

        try {
            repository.importModel(Uri.fromFile(sourceModelFile)) { }
            fail("expected DuplicateModelException")
        } catch (expected: DuplicateModelException) {
            // expected — see ModelRepository.importModel's dedup check.
        }

        assertEquals(1, repository.models.first().size)
    }

    @Test
    fun deleteRemovesFileAndRow() = runBlocking {
        val entity = repository.importModel(Uri.fromFile(sourceModelFile)) { }
        val file = File(entity.filePath)
        assertTrue("expected the imported file to exist before delete", file.exists())

        repository.deleteModel(entity.id)

        assertFalse("file should be gone after delete", file.exists())
        assertTrue("DB row should be gone after delete", repository.models.first().isEmpty())
    }

    /**
     * Simulates TEN-49's orphaned-row scenario: the imported file is deleted from underneath the
     * row (e.g. storage wiped outside the app), leaving [ModelEntity.filePath] dangling. This is
     * exactly the `File(entity.filePath).exists()` check the Models list/detail screens run per
     * model (see `ModelCard.rememberFileExists`) to decide whether to show "File missing".
     */
    @Test
    fun fileExistsReflectsManualDeletionUnderneathTheRow() = runBlocking {
        val entity = repository.importModel(Uri.fromFile(sourceModelFile)) { }
        val file = File(entity.filePath)
        assertTrue("expected the imported file to exist right after import", file.exists())

        assertTrue("expected the file to actually be deletable", file.delete())

        assertFalse("file should read as missing once deleted out from under the row", file.exists())
        assertEquals(
            "the row itself should survive an out-of-band file deletion",
            1,
            repository.models.first().size,
        )
    }

    /**
     * [ModelRepository.deleteModel]'s cleanup path — a no-op-on-missing-file
     * `File.delete()` (returns `false`, doesn't throw) followed by the row delete — must handle
     * an already-missing file (the orphaned-row case) without throwing, since that's the exact
     * path the Models list's "Remove" action takes for a file-missing card.
     */
    @Test
    fun deleteModelOnAlreadyMissingFileRemovesRowWithoutThrowing() = runBlocking {
        val entity = repository.importModel(Uri.fromFile(sourceModelFile)) { }
        File(entity.filePath).delete()

        repository.deleteModel(entity.id)

        assertTrue("DB row should be gone after delete", repository.models.first().isEmpty())
    }
}
