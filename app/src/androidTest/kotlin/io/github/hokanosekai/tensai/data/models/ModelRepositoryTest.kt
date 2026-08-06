package io.github.hokanosekai.tensai.data.models

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hokanosekai.tensai.engine.LlamaKtEngineImpl
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
 *   /sdcard/Android/data/io.github.hokanosekai.tensai/files/test-model.gguf
 * ```
 *
 * No plain JVM unit test accompanies this: [ModelRepository]'s dedup/import logic is inseparable
 * from `Context` (`ContentResolver` for the source `Uri`, `StatFs` and real file I/O for the
 * copy) and the project has neither Robolectric nor a mocking library to fake those — an
 * instrumented test against the real framework classes is the only isolatable option here.
 */
@RunWith(AndroidJUnit4::class)
class ModelRepositoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val sourceModelFile: File by lazy {
        File(context.getExternalFilesDir(null), "test-model.gguf")
    }

    private lateinit var db: TensaiDb
    private lateinit var repository: ModelRepository

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
        val engine = LlamaKtEngineImpl()
        repository = ModelRepository(
            context = context,
            modelDao = db.modelDao(),
            readMetadata = engine::readMetadata,
            currentLoadedModelName = { null },
            storageRoot = storageRoot,
        )
    }

    @After
    fun tearDown() {
        // setUp may have bailed on its assumeTrue before initializing anything —
        // tearDown must not turn that skip into a failure.
        if (!::db.isInitialized) return
        db.close()
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
}
