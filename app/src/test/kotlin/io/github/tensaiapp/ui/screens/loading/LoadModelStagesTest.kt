package io.github.tensaiapp.ui.screens.loading

import android.content.ContextWrapper
import io.github.tensaiapp.compat.DeviceProfile
import io.github.tensaiapp.data.BackendPreference
import io.github.tensaiapp.data.ContextPrefs
import io.github.tensaiapp.data.ContextSetting
import io.github.tensaiapp.data.models.ModelDao
import io.github.tensaiapp.data.models.ModelEntity
import io.github.tensaiapp.data.models.ModelRepository
import io.github.tensaiapp.engine.DecodeEvent
import io.github.tensaiapp.engine.DecodeRequest
import io.github.tensaiapp.engine.DecodeStats
import io.github.tensaiapp.engine.GgufInfo
import io.github.tensaiapp.engine.LlamaKtEngine
import io.github.tensaiapp.engine.LoadStage
import io.github.tensaiapp.engine.LoadedModel
import io.github.tensaiapp.engine.LoadedModelInfo
import io.github.tensaiapp.engine.ModelLoadConfig
import io.github.tensaiapp.runtime.InferenceQueue
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TEN-123: the loading screen's state follows the engine's real stages, and "Ready" is held
 * before [LoadUiState.Success]. The engine is gated, so each stage is observed at a point the
 * test controls instead of racing a timer.
 */
class LoadModelStagesTest {

    private lateinit var storageRoot: File
    private lateinit var modelDao: FakeModelDao
    private lateinit var modelRepository: ModelRepository
    private lateinit var engine: StagedEngine
    private lateinit var queue: InferenceQueue
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        storageRoot = Files.createTempDirectory("tensai-load-stages-test").toFile()
        modelDao = FakeModelDao()
        engine = StagedEngine()
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
    fun theScreenFollowsEachStageTheEngineReports() = runBlocking {
        val viewModel = viewModel(readyHoldMillis = 0)
        viewModel.start()

        val weights = awaitProgress(viewModel) { it.stage == LoadStage.Weights && it.weightsProgress == 0.5f }
        assertEquals(40, weights.percent)

        engine.gateContext.complete(Unit)
        val context = awaitProgress(viewModel) { it.stage == LoadStage.Context }
        assertEquals(80, context.percent)
        assertEquals("Vulkan", context.backendLabel)

        engine.gateWarmup.complete(Unit)
        val warmup = awaitProgress(viewModel) { it.stage == LoadStage.Warmup }
        assertEquals(92, warmup.percent)

        engine.gateReady.complete(Unit)
        withTimeout(5_000) { viewModel.state.first { it is LoadUiState.Success } }
        Unit
    }

    @Test
    fun readyIsShownAtOneHundredPercentBeforeSuccess() = runBlocking {
        val viewModel = viewModel(readyHoldMillis = 400)
        engine.gateContext.complete(Unit)
        engine.gateWarmup.complete(Unit)
        engine.gateReady.complete(Unit)
        viewModel.start()

        val ready = awaitProgress(viewModel) { it.stage == LoadStage.Ready }
        assertEquals(100, ready.percent)
        assertFalse("Success must wait out the hold", viewModel.state.value is LoadUiState.Success)

        withTimeout(5_000) { viewModel.state.first { it is LoadUiState.Success } }
        Unit
    }

    @Test
    fun anAlreadyResidentModelGoesStraightToSuccessWithoutAReadyHold() = runBlocking {
        engine.gateContext.complete(Unit)
        engine.gateWarmup.complete(Unit)
        engine.gateReady.complete(Unit)
        val first = viewModel(readyHoldMillis = 0)
        first.start()
        withTimeout(5_000) { first.state.first { it is LoadUiState.Success } }

        // A 60 s hold would time the wait below out if the dedup path still honoured it.
        val again = viewModel(readyHoldMillis = 60_000)
        again.start()
        withTimeout(5_000) { again.state.first { it is LoadUiState.Success } }
        Unit
    }

    @Test
    fun aScreenOpenedMidLoadResumesAtTheQueuesStageInsteadOfZero() = runBlocking {
        val first = viewModel(readyHoldMillis = 0)
        first.start()
        awaitProgress(first) { it.stage == LoadStage.Weights && it.weightsProgress == 0.5f }

        val reopened = viewModel(readyHoldMillis = 0)
        reopened.start()
        // Never 0 percent: the queue already knows better.
        val seen = awaitProgress(reopened) { true }
        assertTrue("resumed at ${seen.percent}%", seen.percent >= 40)
        engine.gateContext.complete(Unit)
        engine.gateWarmup.complete(Unit)
        engine.gateReady.complete(Unit)
        withTimeout(5_000) { first.state.first { it is LoadUiState.Success } }
        Unit
    }

    private suspend fun awaitProgress(
        viewModel: LoadModelViewModel,
        predicate: (LoadUiState.InProgress) -> Boolean,
    ): LoadUiState.InProgress = withTimeout(5_000) {
        viewModel.state.first { it is LoadUiState.InProgress && predicate(it) } as LoadUiState.InProgress
    }

    private fun viewModel(readyHoldMillis: Long): LoadModelViewModel {
        val file = File(storageRoot, "model.gguf").apply { writeText("not really a gguf") }
        runBlocking { modelDao.insert(model(file)) }
        return LoadModelViewModel(
            modelId = MODEL_ID,
            modelRepository = modelRepository,
            deviceProfile = { profile() },
            backendPreference = { BackendPreference.Gpu },
            contextPrefs = { ContextPrefs() },
            queue = queue,
            scope = scope,
            readyHoldMillis = readyHoldMillis,
        )
    }

    private fun model(file: File) = ModelEntity(
        id = MODEL_ID,
        displayName = "test-model",
        fileName = file.name,
        filePath = file.absolutePath,
        sizeBytes = 1_000_000,
        architecture = "qwen2",
        quantLabel = "Q4_K_M",
        paramCount = 2_000_000_000,
        contextLength = 4096,
        embeddingLength = 1536,
        blockCount = 28,
        supportsThinking = false,
        importedAt = 0L,
        kvFullElementsPerToken = 1024,
    )

    private fun profile() = DeviceProfile(
        totalRamBytes = 8L * 1024 * 1024 * 1024,
        availableRamBytes = 6L * 1024 * 1024 * 1024,
        socModel = "test-soc",
        bigCoreCount = 4,
        hasVulkan = true,
        hasOpenCl = false,
    )

    /** Walks the stages in order, parking at each gate until the test opens it. */
    private class StagedEngine : LlamaKtEngine {
        val gateContext = CompletableDeferred<Unit>()
        val gateWarmup = CompletableDeferred<Unit>()
        val gateReady = CompletableDeferred<Unit>()

        override suspend fun loadModel(config: ModelLoadConfig): LoadedModel {
            config.onStage?.invoke(LoadStage.Weights)
            config.onProgress?.invoke(0.5f)
            gateContext.await()
            config.onProgress?.invoke(1f)
            config.onStage?.invoke(LoadStage.Context)
            gateWarmup.await()
            config.onStage?.invoke(LoadStage.Warmup)
            gateReady.await()
            config.onStage?.invoke(LoadStage.Ready)
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
