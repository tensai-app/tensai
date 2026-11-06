package io.github.tensaiapp.data.models

import android.content.ContextWrapper
import io.github.tensaiapp.engine.ChatMessage
import io.github.tensaiapp.engine.DecodeEvent
import io.github.tensaiapp.engine.DecodeRequest
import io.github.tensaiapp.engine.DecodeStats
import io.github.tensaiapp.engine.InferenceEngine
import io.github.tensaiapp.engine.LoadedModel
import io.github.tensaiapp.engine.LoadedModelInfo
import io.github.tensaiapp.engine.MessageRole
import io.github.tensaiapp.engine.ModelLoadConfig
import io.github.tensaiapp.engine.ModelLoadException
import io.github.tensaiapp.runtime.residentModelId
import io.github.tensaiapp.runtime.InferenceQueue
import io.github.tensaiapp.runtime.QueueState
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * TEN-76: [ModelRepository.deleteModel]'s in-use guard must hold *while a reply is streaming*, not
 * only while the queue sits idle.
 *
 * The guard itself was never the defect — its wiring was. `RuntimeGraph` derived
 * `currentLoadedModelId` by matching [QueueState.ModelReady] alone, which reads as "no model
 * loaded" for the entire duration of a generation ([QueueState.Generating]). Open the Models
 * screen mid-reply, tap delete, and the guard waved through an `unlink` of a multi-gigabyte
 * `.gguf` that llama.cpp still had mmapped. Irreversible, unwarned, and a full re-download.
 *
 * So these tests wire [ModelRepository] through `queue::unlessInUse`, as `RuntimeGraph` does, and
 * drive a *real* [InferenceQueue] into `Generating` (and, since TEN-177, `Loading`) against a gated
 * fake engine. Point the guard back at `ModelReady` alone and
 * [theResidentModelCannotBeDeletedWhileItIsGenerating] fails, deleting the file for good measure.
 *
 * `ModelRepository` is built against a bare `ContextWrapper(null)`, safe because `storageRoot` is
 * passed explicitly so the `Context` is never touched — same trick as the JVM `ChatViewModelTest`,
 * and see `ModelRepository`'s KDoc for why a test must never point `storageRoot` at a real
 * `filesDir` (TEN-48).
 */
class ModelDeleteGuardTest {

    private lateinit var storageRoot: File
    private lateinit var modelDao: FakeModelDao
    private lateinit var engine: FakeEngine
    private lateinit var queue: InferenceQueue
    private lateinit var repository: ModelRepository

    @Before
    fun setUp() {
        storageRoot = Files.createTempDirectory("tensai-delete-guard-test").toFile()
        modelDao = FakeModelDao()
        engine = FakeEngine()
        queue = InferenceQueue(engine, modelFileExists = { File(it).exists() })
        repository = ModelRepository(
            context = ContextWrapper(null),
            modelDao = modelDao,
            readMetadata = { throw UnsupportedOperationException("not needed by these tests") },
            kvShapeVersion = { throw UnsupportedOperationException("not needed by these tests") },
            // The wiring under test — identical to `RuntimeGraph`'s.
            unlessInUse = queue::unlessInUse,
            storageRoot = storageRoot,
        )
    }

    @After
    fun tearDown() {
        storageRoot.deleteRecursively()
    }

    /** The one that matters: mid-generation, the resident model's weights must stay on disk. */
    @Test
    fun theResidentModelCannotBeDeletedWhileItIsGenerating() = runBlocking {
        val weights = importedModel()
        queue.loadModel(ModelLoadConfig(path = weights.path, modelId = MODEL_ID))
        val generation = launchGenerationAndWaitUntilItIsRunning()

        try {
            repository.deleteModel(MODEL_ID)
            fail("deleteModel returned instead of refusing: the guard was blind during Generating")
        } catch (e: ModelInUseException) {
            assertTrue(
                "the refusal should name the model, got \"${e.message}\"",
                e.message.orEmpty().contains(MODEL_NAME),
            )
        }

        assertTrue("the .gguf was unlinked while llama.cpp still had it open", weights.exists())
        assertEquals("the row was dropped while the model was still loaded", 1, modelDao.rowCount())

        generation.cancel()
    }

    /** The already-covered half of the same guard, kept as the contrast case. */
    @Test
    fun theResidentModelCannotBeDeletedWhileTheQueueIsIdleEither() = runBlocking {
        val weights = importedModel()
        queue.loadModel(ModelLoadConfig(path = weights.path, modelId = MODEL_ID))

        try {
            repository.deleteModel(MODEL_ID)
            fail("deleteModel returned instead of refusing")
        } catch (e: ModelInUseException) {
            // expected
        }

        assertTrue(weights.exists())
    }

    /**
     * The guard is about *this* model, not about the queue being busy — a second imported model
     * nobody has loaded stays deletable while the first one generates. Without this, the fix would
     * be indistinguishable from "block every delete during a reply", which is not what it does.
     */
    @Test
    fun anUnloadedModelIsStillDeletableWhileAnotherOneGenerates() = runBlocking {
        val resident = importedModel()
        val other = importedModel(id = "model-2", name = "other-model", fileName = "other.gguf")
        queue.loadModel(ModelLoadConfig(path = resident.path, modelId = MODEL_ID))
        val generation = launchGenerationAndWaitUntilItIsRunning()

        repository.deleteModel("model-2")

        assertFalse("the wrong file was kept", other.exists())
        assertTrue("the resident model was touched", resident.exists())

        generation.cancel()
    }

    /**
     * TEN-80: the guard is about this *file*, not about its name. Two quantisations of one model
     * share a GGUF display name, so while the guard compared by [ModelEntity.displayName] holding
     * both — the ordinary reason to hold two — made the loaded one lock the other against deletion,
     * with a refusal naming a model the user could see was not the one they had picked.
     */
    @Test
    fun anUnloadedModelSharingTheResidentModelsDisplayNameIsStillDeletable() = runBlocking {
        val resident = importedModel()
        val sameName = importedModel(id = "model-2", name = MODEL_NAME, fileName = "other-quant.gguf")
        queue.loadModel(ModelLoadConfig(path = resident.path, modelId = MODEL_ID))

        repository.deleteModel("model-2")

        assertFalse("the other quantisation was kept, blocked by a name it merely shares", sameName.exists())
        assertTrue("the resident model was touched", resident.exists())
    }

    /**
     * TEN-177: a load is not a state the model is resident in yet, and the guard read it as "nothing
     * loaded". Start loading a model, go back, open it, Delete: the file was unlinked under the load.
     */
    @Test
    fun theModelBeingLoadedCannotBeDeleted() = runBlocking {
        val weights = importedModel()
        engine.loadGate = CompletableDeferred()
        val load = launch { queue.loadModel(ModelLoadConfig(path = weights.path, modelId = MODEL_ID)) }
        withTimeout(TIMEOUT_MS) { engine.loadEntered.await() }

        // Released whatever happens: the queue runs the engine's load uncancellably, so a failed
        // assertion would otherwise leave the test waiting on it for good.
        try {
            try {
                withTimeout(TIMEOUT_MS) { repository.deleteModel(MODEL_ID) }
                fail("deleteModel returned instead of refusing: the guard was blind during Loading")
            } catch (e: ModelInUseException) {
                // expected, and at once: not after waiting the load out
            }

            assertTrue("the .gguf was unlinked while it was being loaded", weights.exists())
            assertEquals(1, modelDao.rowCount())
        } finally {
            engine.loadGate!!.complete(Unit)
        }
        load.join()
    }

    /** The contrast: a load of one model does not lock another, nor does an idle queue. */
    @Test
    fun anotherModelIsStillDeletableWhileOneLoadsAndAnyWhenIdle() = runBlocking {
        val loading = importedModel()
        val other = importedModel(id = "model-2", name = "other-model", fileName = "other.gguf")
        val third = importedModel(id = "model-3", name = "third-model", fileName = "third.gguf")
        engine.loadGate = CompletableDeferred()
        val load = launch { queue.loadModel(ModelLoadConfig(path = loading.path, modelId = MODEL_ID)) }
        withTimeout(TIMEOUT_MS) { engine.loadEntered.await() }

        // Waits for the load to end: the check and the unlink share the queue's lock.
        val deletion = async { repository.deleteModel("model-2") }
        try {
            assertNull("the delete went through in the middle of a load", withTimeoutOrNull(200) { deletion.await() })
        } finally {
            engine.loadGate!!.complete(Unit)
        }
        withTimeout(TIMEOUT_MS) { deletion.await() }
        load.join()
        assertFalse(other.exists())
        assertTrue(loading.exists())

        queue.unloadModel()
        repository.deleteModel("model-3")
        assertFalse(third.exists())
    }

    /**
     * TEN-177's race: the check and the unlink were not one step, so a load could start between them
     * and load from a file about to be unlinked. A load asked for while a delete of the same model runs
     * must wait until the delete is over (and then find no file), never start in the middle of it.
     */
    @Test
    fun aLoadAskedForDuringADeleteOfThatModelWaitsForIt() = runBlocking {
        val weights = importedModel()
        modelDao.deleteGate = CompletableDeferred()
        val deletion = launch { repository.deleteModel(MODEL_ID) }
        withTimeout(TIMEOUT_MS) { modelDao.deleteEntered.await() }

        val load = async { runCatching { queue.loadModel(ModelLoadConfig(path = weights.path, modelId = MODEL_ID)) } }
        try {
            assertNull(
                "the load reached the engine while the delete was still under way",
                withTimeoutOrNull(300) { engine.loadEntered.await() },
            )
        } finally {
            modelDao.deleteGate!!.complete(Unit)
        }
        deletion.join()
        load.await()
        assertFalse(weights.exists())
    }

    /**
     * The other end of that race: a load of a model deleted while the load waited for the lock fails
     * before it releases the resident model, which would otherwise be unloaded for nothing.
     */
    @Test
    fun aLoadOfADeletedModelKeepsTheResidentOne() = runBlocking {
        val resident = importedModel()
        val deleted = importedModel(id = "model-2", name = "other-model", fileName = "other.gguf")
        queue.loadModel(ModelLoadConfig(path = resident.path, modelId = MODEL_ID))
        repository.deleteModel("model-2")

        val failure = runCatching { queue.loadModel(ModelLoadConfig(path = deleted.path, modelId = "model-2")) }

        assertTrue(failure.exceptionOrNull() is ModelLoadException)
        assertEquals("the resident model was released for a load that could not happen", MODEL_ID, queue.residentModelId)
    }

    /** Writes a fake `.gguf` under [storageRoot] and inserts the row [ModelRepository] reads. */
    private suspend fun importedModel(
        id: String = MODEL_ID,
        name: String = MODEL_NAME,
        fileName: String = "resident.gguf",
    ): File {
        val file = File(storageRoot, fileName).apply { writeText("gguf") }
        modelDao.insert(
            ModelEntity(
                id = id,
                displayName = name,
                fileName = fileName,
                filePath = file.path,
                sizeBytes = file.length(),
                architecture = "test",
                quantLabel = "F16",
                paramCount = 1L,
                contextLength = 1L,
                embeddingLength = 1L,
                blockCount = 1L,
                supportsThinking = false,
                importedAt = 0L,
            ),
        )
        return file
    }

    /**
     * Starts a generation that never finishes on its own and returns once the engine's decode has
     * actually begun — the engine says so ([FakeEngine.inDecode]) rather than the test asking the
     * state over and over. [InferenceQueue] publishes [QueueState.Generating] under its mutex
     * before it calls decode, so this point is past it (TEN-94). Callers cancel the returned [Job].
     */
    private suspend fun CoroutineScope.launchGenerationAndWaitUntilItIsRunning(): Job {
        val request = DecodeRequest(messages = listOf(ChatMessage(role = MessageRole.User, content = "hi")))
        val job = launch { queue.generate(request).collect { } }
        withTimeout(TIMEOUT_MS) { engine.inDecode.await() }
        return job
    }

    /** In-memory [ModelDao] — mirrors the conventions of the other JVM suites' private copies. */
    private class FakeModelDao : ModelDao {
        private val models = mutableMapOf<String, ModelEntity>()
        private val flow = MutableStateFlow<List<ModelEntity>>(emptyList())

        fun rowCount(): Int = models.size

        /** Holds [delete] once entered, after the unlink, until completed. */
        var deleteGate: CompletableDeferred<Unit>? = null
        val deleteEntered = CompletableDeferred<Unit>()

        override fun observeAll(): Flow<List<ModelEntity>> = flow

        override suspend fun getById(id: String): ModelEntity? = models[id]

        override suspend fun setContextOverride(id: String, contextOverride: String?) = Unit

        override suspend fun setChatTemplateOverride(id: String, chatTemplateOverride: String?) = Unit

        override suspend fun withStaleKvCacheShape(kvShapeVersion: Long): List<ModelEntity> =
            models.values.filter { it.kvShapeVersion != kvShapeVersion }

        override suspend fun updateKvCacheShape(
            id: String,
            kvFullElementsPerToken: Long,
            kvSwaElementsPerToken: Long,
            slidingWindow: Long,
            kvShapeVersion: Long,
            supportsThinking: Boolean,
            supportsTools: Boolean,
            hasChatTemplate: Boolean,
        ) {
            val existing = models[id] ?: return
            models[id] = existing.copy(
                kvFullElementsPerToken = kvFullElementsPerToken,
                kvSwaElementsPerToken = kvSwaElementsPerToken,
                slidingWindow = slidingWindow,
                kvShapeVersion = kvShapeVersion,
            )
            flow.value = models.values.toList()
        }

        override suspend fun insert(model: ModelEntity) {
            models[model.id] = model
            flow.value = models.values.toList()
        }

        override suspend fun delete(model: ModelEntity) {
            deleteEntered.complete(Unit)
            deleteGate?.await()
            models.remove(model.id)
            flow.value = models.values.toList()
        }
    }

    /**
     * An engine whose decode blocks on a gate nothing here ever completes, so the queue stays in
     * [QueueState.Generating] for as long as a test needs it to — the state the guard used to be
     * blind in. Reports [MODEL_NAME] regardless of path — a GGUF header name is a property of the
     * model, not of the file, which is exactly why the guard stopped matching on it (TEN-80) — and
     * echoes back whatever [ModelLoadConfig.modelId] it was loaded with, as the real engine does.
     */
    private class FakeEngine : InferenceEngine {
        /** Completed on entering the decode: the signal a test waits on instead of polling. */
        val inDecode = CompletableDeferred<Unit>()

        /** Holds a load inside the engine until completed, so the queue stays on [QueueState.Loading]. */
        var loadGate: CompletableDeferred<Unit>? = null
        val loadEntered = CompletableDeferred<Unit>()

        override suspend fun loadModel(config: ModelLoadConfig): LoadedModel {
            loadEntered.complete(Unit)
            loadGate?.await()
            return loadedModel(config)
        }

        private fun loadedModel(config: ModelLoadConfig): LoadedModel = object : LoadedModel {
            override val metadata = LoadedModelInfo(
                name = MODEL_NAME,
                nCtxEffective = config.nCtx,
                backend = "CPU",
                kvCacheType = config.kvCacheType,
                modelId = config.modelId,
            )

            override suspend fun decode(request: DecodeRequest): Flow<DecodeEvent> = flow {
                inDecode.complete(Unit)
                emit(DecodeEvent.Token("…"))
                CompletableDeferred<Unit>().await()
                emit(DecodeEvent.Done(DecodeStats(tokensGenerated = 1, tokPerSec = 1f, ttftMs = 1L)))
            }

            override suspend fun tokenize(text: String): Int = 0

            override suspend fun free() = Unit
        }
    }

    private companion object {
        const val MODEL_ID = "model-1"
        const val MODEL_NAME = "resident-model"
        const val TIMEOUT_MS = 2_000L
    }
}
