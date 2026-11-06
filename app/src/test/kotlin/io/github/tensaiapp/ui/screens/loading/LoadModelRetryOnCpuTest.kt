package io.github.tensaiapp.ui.screens.loading

import android.content.ContextWrapper
import io.github.tensaiapp.compat.DeviceProfile
import io.github.tensaiapp.data.BackendPreference
import io.github.tensaiapp.data.ContextPrefs
import io.github.tensaiapp.data.ContextSetting
import io.github.tensaiapp.data.models.ModelDao
import io.github.tensaiapp.data.models.ModelEntity
import io.github.tensaiapp.data.models.ModelRepository
import io.github.tensaiapp.engine.GgufInfo
import io.github.tensaiapp.engine.LlamaKtEngine
import io.github.tensaiapp.engine.LoadedModel
import io.github.tensaiapp.engine.ModelLoadConfig
import io.github.tensaiapp.runtime.InferenceQueue
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TEN-100: "Retry on CPU" is only offered when the failed attempt ran on a non-CPU backend.
 * Drives a real [LoadModelViewModel] against an engine that always throws.
 */
class LoadModelRetryOnCpuTest {

    private lateinit var storageRoot: File
    private lateinit var modelDao: FakeModelDao
    private lateinit var modelRepository: ModelRepository
    private lateinit var engine: FailingEngine
    private lateinit var queue: InferenceQueue
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        storageRoot = Files.createTempDirectory("tensai-load-retry-cpu-test").toFile()
        modelDao = FakeModelDao()
        engine = FailingEngine()
        queue = InferenceQueue(engine)
        modelRepository = ModelRepository(
            context = ContextWrapper(null),
            modelDao = modelDao,
            readMetadata = { throw UnsupportedOperationException("not needed by these tests") },
            kvShapeVersion = { throw UnsupportedOperationException("not needed by these tests") },
            unlessInUse = { _, block -> block(); true },
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
    fun aFailedCpuAttemptDoesNotOfferCpuRetry() = runBlocking {
        val failed = failWith(BackendPreference.Cpu, architecture = "gemma4")

        assertFalse(failed.canRetryOnCpu)
    }

    @Test
    fun aFailedGpuAttemptOffersCpuRetry() = runBlocking {
        val failed = failWith(BackendPreference.Gpu, architecture = "qwen2")

        assertTrue(failed.canRetryOnCpu)
    }

    /** No GPU backend: the engine runs Auto on CPU, so the attempt was a CPU one. */
    @Test
    fun anAutoAttemptOnADeviceWithoutGpuDoesNotOfferCpuRetry() = runBlocking {
        val failed = failWith(BackendPreference.Auto, architecture = "unknown-arch", hasGpu = false)

        assertFalse(failed.canRetryOnCpu)
    }

    /** Same for a forced GPU preference: with nothing to offload to, llama.cpp stays on CPU. */
    @Test
    fun aGpuAttemptOnADeviceWithoutGpuDoesNotOfferCpuRetry() = runBlocking {
        val failed = failWith(BackendPreference.Gpu, architecture = "qwen2", hasGpu = false)

        assertFalse(failed.canRetryOnCpu)
    }

    @Test
    fun afterRetryingOnCpuTheNextFailureNoLongerOffersIt() = runBlocking {
        val viewModel = viewModel(BackendPreference.Gpu, architecture = "qwen2")
        viewModel.start()
        awaitDone()
        assertTrue((viewModel.state.value as LoadUiState.GenericFailed).canRetryOnCpu)

        viewModel.retryOnCpu()
        awaitDone()

        assertFalse((viewModel.state.value as LoadUiState.GenericFailed).canRetryOnCpu)
    }

    private suspend fun failWith(
        preference: BackendPreference,
        architecture: String,
        hasGpu: Boolean = true,
    ): LoadUiState.GenericFailed {
        val viewModel = viewModel(preference, architecture, hasGpu)
        viewModel.start()
        awaitDone()
        return viewModel.state.value as LoadUiState.GenericFailed
    }

    private fun viewModel(
        preference: BackendPreference,
        architecture: String,
        hasGpu: Boolean = true,
    ): LoadModelViewModel {
        val file = File(storageRoot, "model.gguf").apply { writeText("not really a gguf") }
        runBlocking { modelDao.insert(model(file, architecture)) }

        return LoadModelViewModel(
            modelId = MODEL_ID,
            modelRepository = modelRepository,
            deviceProfile = { profile(hasGpu) },
            backendPreference = { preference },
            contextPrefs = { ContextPrefs() },
            queue = queue,
            scope = scope,
        )
    }

    /** Joining the jobs [LoadModelViewModel.start] launched on [scope] is the attempt being over. */
    private suspend fun awaitDone() {
        scope.coroutineContext.job.children.toList().forEach { it.join() }
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

    /** 8 GB total / 6 GB free, enough headroom that no model here is blocked as "won't fit". */
    private fun profile(hasGpu: Boolean) = DeviceProfile(
        totalRamBytes = 8L * 1024 * 1024 * 1024,
        availableRamBytes = 6L * 1024 * 1024 * 1024,
        socModel = "test-soc",
        bigCoreCount = 4,
        hasVulkan = hasGpu,
        hasOpenCl = false,
    )

    /** Fails every load, as a structurally invalid file would. */
    private class FailingEngine : LlamaKtEngine {
        override suspend fun loadModel(config: ModelLoadConfig): LoadedModel =
            throw RuntimeException("load failed")

        override fun kvCacheUsedCells(): Int = 0

        override suspend fun saveSession(path: String): Int = 0

        override suspend fun loadSession(path: String): Boolean = true

        override suspend fun readMetadata(path: String): GgufInfo =
            throw UnsupportedOperationException("not needed by these tests")
    }

    /** In-memory [ModelDao], same shape as `ChatViewModelTest`'s own. */
    private class FakeModelDao : ModelDao {
        private val models = mutableMapOf<String, ModelEntity>()
        private val flow = MutableStateFlow<List<ModelEntity>>(emptyList())

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
