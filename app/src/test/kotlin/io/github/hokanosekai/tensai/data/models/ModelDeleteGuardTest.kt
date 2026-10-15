package io.github.hokanosekai.tensai.data.models

import android.content.ContextWrapper
import io.github.hokanosekai.tensai.engine.ChatMessage
import io.github.hokanosekai.tensai.engine.DecodeEvent
import io.github.hokanosekai.tensai.engine.DecodeRequest
import io.github.hokanosekai.tensai.engine.DecodeStats
import io.github.hokanosekai.tensai.engine.InferenceEngine
import io.github.hokanosekai.tensai.engine.LoadedModel
import io.github.hokanosekai.tensai.engine.LoadedModelInfo
import io.github.hokanosekai.tensai.engine.MessageRole
import io.github.hokanosekai.tensai.engine.ModelLoadConfig
import io.github.hokanosekai.tensai.runtime.InferenceQueue
import io.github.hokanosekai.tensai.runtime.QueueState
import io.github.hokanosekai.tensai.runtime.residentModelId
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * So these tests wire [ModelRepository] through
 * [io.github.hokanosekai.tensai.runtime.residentModelId] — the same one-liner `RuntimeGraph`
 * wires — and drive a *real* [InferenceQueue] into `Generating` against a gated fake engine. Point
 * that property back at `ModelReady` alone and
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
        queue = InferenceQueue(engine)
        repository = ModelRepository(
            context = ContextWrapper(null),
            modelDao = modelDao,
            readMetadata = { throw UnsupportedOperationException("not needed by these tests") },
            kvShapeVersion = { throw UnsupportedOperationException("not needed by these tests") },
            // The wiring under test — identical to `RuntimeGraph`'s.
            currentLoadedModelId = { queue.residentModelId },
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

        override fun observeAll(): Flow<List<ModelEntity>> = flow

        override suspend fun getById(id: String): ModelEntity? = models[id]

        override suspend fun setContextOverride(id: String, contextOverride: String?) = Unit

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

        override suspend fun loadModel(config: ModelLoadConfig): LoadedModel = object : LoadedModel {
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
