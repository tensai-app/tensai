package io.github.hokanosekai.tensai.ui.screens.loading

import android.content.ContextWrapper
import io.github.hokanosekai.tensai.compat.DeviceProfile
import io.github.hokanosekai.tensai.data.BackendPreference
import io.github.hokanosekai.tensai.data.models.ModelDao
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.data.models.ModelRepository
import io.github.hokanosekai.tensai.engine.BackendHint
import io.github.hokanosekai.tensai.engine.DecodeEvent
import io.github.hokanosekai.tensai.engine.DecodeRequest
import io.github.hokanosekai.tensai.engine.DecodeStats
import io.github.hokanosekai.tensai.engine.GgufInfo
import io.github.hokanosekai.tensai.engine.LlamaKtEngine
import io.github.hokanosekai.tensai.engine.LoadedModel
import io.github.hokanosekai.tensai.engine.LoadedModelInfo
import io.github.hokanosekai.tensai.engine.ModelLoadConfig
import io.github.hokanosekai.tensai.runtime.InferenceQueue
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * TEN-36's third promise: the backend the user picks in onboarding is the backend a model is
 * actually loaded with. A setting the UI shows and nothing obeys would be worse than no setting,
 * so this drives a real [LoadModelViewModel] and reads the [ModelLoadConfig] that reaches the
 * engine — a fake one ([RecordingEngine]), no device or GGUF involved.
 *
 * [ModelRepository] is built against a bare `ContextWrapper(null)` with an explicit `storageRoot`,
 * the same JVM-safe construction `ChatViewModelTest` uses (see its KDoc).
 */
class LoadModelBackendTest {

    private lateinit var storageRoot: File
    private lateinit var modelDao: FakeModelDao
    private lateinit var modelRepository: ModelRepository
    private lateinit var engine: RecordingEngine
    private lateinit var queue: InferenceQueue
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        storageRoot = Files.createTempDirectory("tensai-load-backend-test").toFile()
        modelDao = FakeModelDao()
        engine = RecordingEngine()
        queue = InferenceQueue(engine)
        modelRepository = ModelRepository(
            context = ContextWrapper(null),
            modelDao = modelDao,
            readMetadata = { throw UnsupportedOperationException("not needed by these tests") },
            kvShapeVersion = { throw UnsupportedOperationException("not needed by these tests") },
            currentLoadedModelName = { null },
            storageRoot = storageRoot,
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        storageRoot.deleteRecursively()
    }

    @Test
    fun autoFollowsTheCheckersRecommendationForTheModel() = runBlocking {
        // gemma4 on a device with Vulkan is the checker's GPU-preferring case.
        val config = loadWith(BackendPreference.Auto, architecture = "gemma4", hasVulkan = true)

        assertEquals(BackendHint.Gpu, config.backend)
    }

    @Test
    fun aCpuPreferenceOverridesAGpuRecommendation() = runBlocking {
        val config = loadWith(BackendPreference.Cpu, architecture = "gemma4", hasVulkan = true)

        assertEquals(BackendHint.Cpu, config.backend)
    }

    @Test
    fun aGpuPreferenceOverridesACpuRecommendation() = runBlocking {
        // A dense qwen is the checker's CPU case — the preference has to win over it.
        val config = loadWith(BackendPreference.Gpu, architecture = "qwen2", hasVulkan = true)

        assertEquals(BackendHint.Gpu, config.backend)
    }

    @Test
    fun retryingOnCpuOverridesEvenAGpuPreference() = runBlocking {
        val viewModel = viewModel(BackendPreference.Gpu, architecture = "qwen2", hasVulkan = true)

        viewModel.retryOnCpu()
        val config = awaitLoad()

        assertEquals(
            "a manual \"Retry on CPU\" that loaded on GPU anyway would be a dead button",
            BackendHint.Cpu,
            config.backend,
        )
    }

    private suspend fun loadWith(
        preference: BackendPreference,
        architecture: String,
        hasVulkan: Boolean,
    ): ModelLoadConfig {
        viewModel(preference, architecture, hasVulkan).start()
        return awaitLoad()
    }

    private fun viewModel(
        preference: BackendPreference,
        architecture: String,
        hasVulkan: Boolean,
    ): LoadModelViewModel {
        val file = File(storageRoot, "model.gguf").apply { writeText("not really a gguf") }
        runBlocking { modelDao.insert(model(file, architecture)) }

        return LoadModelViewModel(
            modelId = MODEL_ID,
            modelRepository = modelRepository,
            deviceProfile = { profile(hasVulkan) },
            backendPreference = { preference },
            queue = queue,
            scope = scope,
        )
    }

    private suspend fun awaitLoad(): ModelLoadConfig = withTimeout(5_000) {
        while (engine.lastConfig == null) delay(10)
        engine.lastConfig!!
    }

    private fun model(file: File, architecture: String) = ModelEntity(
        id = MODEL_ID,
        displayName = "test-model",
        fileName = file.name,
        filePath = file.absolutePath,
        sizeBytes = 1_000_000,
        architecture = architecture,
        quantLabel = "Q4_K_M",
        paramCount = 2_000_000_000,
        contextLength = 4096,
        embeddingLength = 1536,
        blockCount = 28,
        supportsThinking = false,
        importedAt = 0L,
        kvFullElementsPerToken = 1024,
    )

    /** 8 GB total / 6 GB free — enough headroom that no model here is blocked as "won't fit". */
    private fun profile(hasVulkan: Boolean) = DeviceProfile(
        totalRamBytes = 8L * 1024 * 1024 * 1024,
        availableRamBytes = 6L * 1024 * 1024 * 1024,
        socModel = "test-soc",
        bigCoreCount = 4,
        hasVulkan = hasVulkan,
        hasOpenCl = false,
    )

    /** Records the [ModelLoadConfig] the load actually reached the engine with. */
    private class RecordingEngine : LlamaKtEngine {
        @Volatile
        var lastConfig: ModelLoadConfig? = null

        override suspend fun loadModel(config: ModelLoadConfig): LoadedModel {
            lastConfig = config
            return object : LoadedModel {
                override val metadata = LoadedModelInfo(
                    name = "test-model",
                    nCtxEffective = config.nCtx,
                    backend = config.backend.name,
                    kvCacheType = config.kvCacheType,
                )

                override suspend fun decode(request: DecodeRequest): Flow<DecodeEvent> = flow {
                    emit(DecodeEvent.Done(DecodeStats(tokensGenerated = 0, tokPerSec = 0f, ttftMs = 0L)))
                }

                override suspend fun tokenize(text: String): Int = 0

                override suspend fun free() = Unit
            }
        }

        override fun kvCacheUsedCells(): Int = 0

        override suspend fun saveSession(path: String): Int = 0

        override suspend fun loadSession(path: String): Boolean = true

        override suspend fun readMetadata(path: String): GgufInfo =
            throw UnsupportedOperationException("not needed by these tests")
    }

    /** In-memory [ModelDao] — same shape as `ChatViewModelTest`'s own. */
    private class FakeModelDao : ModelDao {
        private val models = mutableMapOf<String, ModelEntity>()
        private val flow = MutableStateFlow<List<ModelEntity>>(emptyList())

        override fun observeAll(): Flow<List<ModelEntity>> = flow

        override suspend fun getById(id: String): ModelEntity? = models[id]

        override suspend fun withStaleKvCacheShape(kvShapeVersion: Long): List<ModelEntity> =
            models.values.filter { it.kvShapeVersion != kvShapeVersion }

        override suspend fun updateKvCacheShape(
            id: String,
            kvFullElementsPerToken: Long,
            kvSwaElementsPerToken: Long,
            slidingWindow: Long,
            kvShapeVersion: Long,
        ) = Unit

        override suspend fun insert(model: ModelEntity) {
            models[model.id] = model
            flow.value = models.values.toList()
        }

        override suspend fun delete(model: ModelEntity) {
            models.remove(model.id)
            flow.value = models.values.toList()
        }
    }

    private companion object {
        const val MODEL_ID = "model-1"
    }
}
