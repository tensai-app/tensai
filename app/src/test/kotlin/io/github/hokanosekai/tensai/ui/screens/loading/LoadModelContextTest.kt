package io.github.hokanosekai.tensai.ui.screens.loading

import android.content.ContextWrapper
import io.github.hokanosekai.tensai.compat.DeviceProfile
import io.github.hokanosekai.tensai.compat.Verdict
import io.github.hokanosekai.tensai.data.BackendPreference
import io.github.hokanosekai.tensai.data.models.ModelDao
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.data.models.ModelRepository
import io.github.hokanosekai.tensai.engine.DecodeEvent
import io.github.hokanosekai.tensai.engine.DecodeRequest
import io.github.hokanosekai.tensai.engine.DecodeStats
import io.github.hokanosekai.tensai.engine.GgufInfo
import io.github.hokanosekai.tensai.engine.KvCacheType
import io.github.hokanosekai.tensai.engine.LlamaKtEngine
import io.github.hokanosekai.tensai.engine.LoadedModel
import io.github.hokanosekai.tensai.engine.LoadedModelInfo
import io.github.hokanosekai.tensai.engine.ModelLoadConfig
import io.github.hokanosekai.tensai.runtime.InferenceQueue
import io.github.hokanosekai.tensai.runtime.LoadPlan
import io.github.hokanosekai.tensai.runtime.LoadPolicy
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TEN-77 seen from the screen: what `n_ctx` actually reaches the engine, and what the user is
 * shown when nothing usable can.
 *
 * The measurement this defends was taken on the reference device (OPPO CPH2251, 8 GB,
 * Gemma-4-E2B-It, 2026-09-08). Loading the same model twice with the same build produced `n_ctx`
 * 256 once — the system log carrying `SCENE_LOW_MEMORY ... availMem=1577` — and 4096 the next
 * time. At 256 the chat took a 31-token prompt, had 225 tokens left to answer in, and said
 * nothing: no error, no dialog, only "Gemma-4-E2B-It · 256" in the top bar. The model itself was
 * never in danger — llama.cpp mmaps the weights, so the load succeeded with free RAM far below the
 * file's own size — and its KV cache measured ~17 MiB at full context, noise against 2.9 GB of
 * weights. The whole 16x swing came from budgeting against free RAM.
 *
 * Same harness as [LoadModelBackendTest]: a real [LoadModelViewModel] driven against a fake engine
 * that records the [ModelLoadConfig] it is called with, and a [ModelRepository] built on a bare
 * `ContextWrapper(null)` with an explicit `storageRoot`.
 */
class LoadModelContextTest {

    private lateinit var storageRoot: File
    private lateinit var modelDao: FakeModelDao
    private lateinit var modelRepository: ModelRepository
    private lateinit var engine: RecordingEngine
    private lateinit var queue: InferenceQueue
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        storageRoot = Files.createTempDirectory("tensai-load-context-test").toFile()
        modelDao = FakeModelDao()
        engine = RecordingEngine()
        queue = InferenceQueue(engine)
        modelRepository = ModelRepository(
            context = ContextWrapper(null),
            modelDao = modelDao,
            readMetadata = { throw UnsupportedOperationException("not needed by these tests") },
            kvShapeVersion = { throw UnsupportedOperationException("not needed by these tests") },
            currentLoadedModelId = { null },
            storageRoot = storageRoot,
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        storageRoot.deleteRecursively()
    }

    /**
     * The bug, end to end. Free RAM at the logged 1577 MB is far below this model's 2.9 GB of
     * weights, and it does not matter: the load is budgeted against the phone's total RAM, so the
     * context that reaches the engine is the full 4096 rather than the 256 the old policy handed
     * it.
     */
    @Test
    fun aTransientMemoryCrunchDoesNotShrinkTheContextThatReachesTheEngine() = runBlocking {
        val viewModel = viewModel(gemmaSizedModel(), phone(totalRamGb = 8, availableRamMb = 1_577))

        viewModel.start()
        val config = awaitLoad()

        assertEquals(
            "free RAM at load time must not decide the context — 256 tokens is a chat that " +
                "cannot answer, and nothing in the UI says so",
            LoadPolicy.CTX_LATENCY_CAP,
            config.nCtx,
        )
        assertEquals(KvCacheType.F16, config.kvCacheType)
    }

    /**
     * TEN-80's transport half. This is the one moment the row's id and the file about to be loaded
     * are both in hand; if the id does not leave here it cannot be recovered afterwards, because
     * the display name that used to stand in for it names the *model* and not the file — two
     * quantisations of one model carry the same one.
     */
    @Test
    fun theLoadCarriesTheRowsIdSoWhatIsLoadedCanBeNamedExactly() = runBlocking {
        val model = gemmaSizedModel()

        viewModel(model, phone(totalRamGb = 8, availableRamMb = 6_000)).start()

        assertEquals(model.id, awaitLoad().modelId)
    }

    /**
     * ...and it does not decide it on the *next* load either, which is the half of TEN-77 that
     * costs the user time rather than words: `n_ctx` is part of
     * [io.github.hokanosekai.tensai.data.session.SessionModelKey], so a context that moves with
     * free RAM makes every persisted session unrestorable — a measured 23.5x time-to-first-token
     * win thrown away, and hundreds of MB of `.llamastate` kept on disk for nothing.
     */
    @Test
    fun thePlannedContextIsTheSameAcrossLoadsUnderDifferentMemoryPressure() = runBlocking {
        val model = gemmaSizedModel()
        val contexts = mutableListOf<Pair<Long, Int>>()

        for (availableRamMb in listOf(1_577L, 3_500L, 6_000L)) {
            // A fresh engine and queue per iteration rather than an unload between them:
            // InferenceQueue dedups a repeat call for the config it already holds, which is
            // exactly what this test is trying to observe three times over.
            engine = RecordingEngine()
            queue = InferenceQueue(engine)
            viewModel(model, phone(totalRamGb = 8, availableRamMb = availableRamMb)).start()
            contexts += availableRamMb to awaitLoad().nCtx
        }

        assertEquals(
            "the same model on the same phone has to plan the same context every time: $contexts",
            1,
            contexts.map { it.second }.distinct().size,
        )
    }

    /**
     * A model the phone genuinely cannot hold is stopped at the dialog with the figures on it,
     * and no load is attempted — the plan agrees, and says so as [LoadPlan.Infeasible] rather than
     * by naming a context nobody could chat at.
     */
    @Test
    fun aModelThePhoneCannotHoldIsBlockedWithTheFiguresInsteadOfPlannedTiny() = runBlocking {
        val model = oversizedModel()
        val profile = phone(totalRamGb = 4, availableRamMb = 3_000)

        assertTrue(
            "the policy and the checker budget against the same usable RAM, so they have to agree",
            LoadPolicy.compute(model, profile) is LoadPlan.Infeasible,
        )

        val viewModel = viewModel(model, profile)
        viewModel.start()
        val blocked = viewModel.awaitState<LoadUiState.Blocked>()

        assertEquals(Verdict.WontFit, blocked.compatibility.verdict)
        // The two figures the dialog's sentence is built from, and they have to be the ones that
        // stopped the load — not a second estimate taken a moment later.
        assertEquals(profile.totalRamBytes, blocked.compatibility.deviceRamBytes)
        assertTrue(
            "an estimate that fits wouldn't have blocked anything: ${blocked.compatibility}",
            blocked.compatibility.estimatedBytes > profile.usableRamBytes,
        )
        assertNull("nothing may be loaded while the user is still being asked", engine.lastConfig)
    }

    /**
     * "Load anyway" stays a live button. It does not land back on the dialog it came from, and it
     * does not quietly become the 256-token load TEN-77 removed: it tries the floor — the smallest
     * context still worth chatting at — and leaves the refusal to the engine, which surfaces as
     * the "Not enough memory" dialog rather than as silence.
     */
    @Test
    fun loadingAnywayTriesTheFloorRatherThanADegenerateContext() = runBlocking {
        val viewModel = viewModel(oversizedModel(), phone(totalRamGb = 4, availableRamMb = 3_000))

        viewModel.start()
        viewModel.awaitState<LoadUiState.Blocked>()
        viewModel.loadAnyway()
        val config = awaitLoad()

        assertEquals(LoadPolicy.MIN_USABLE_CTX, config.nCtx)
        assertEquals(KvCacheType.Q8_0, config.kvCacheType)
    }

    private fun viewModel(model: ModelEntity, profile: DeviceProfile): LoadModelViewModel {
        runBlocking { modelDao.insert(model) }
        return LoadModelViewModel(
            modelId = model.id,
            modelRepository = modelRepository,
            deviceProfile = { profile },
            backendPreference = { BackendPreference.Cpu },
            queue = queue,
            scope = scope,
        )
    }

    /**
     * [LoadModelViewModel.start] is fire-and-forget on [scope], which this test owns — so joining
     * the jobs it launched is the load being over, rather than polling for it under a budget that
     * turns a slow machine into a failed test (TEN-42, same fix as `SettingsViewModelTest`).
     */
    private suspend fun awaitViewModel() {
        scope.coroutineContext.job.children.toList().forEach { it.join() }
    }

    private suspend fun awaitLoad(): ModelLoadConfig {
        awaitViewModel()
        return requireNotNull(engine.lastConfig) { "the load finished without reaching the engine" }
    }

    /** The state a settled [LoadModelViewModel] came to rest in — see [awaitViewModel]. */
    private suspend inline fun <reified T : LoadUiState> LoadModelViewModel.awaitState(): T {
        awaitViewModel()
        return state.value as? T
            ?: throw AssertionError("expected ${T::class.simpleName}, was ${state.value}")
    }

    /**
     * The reference device's Gemma-4-E2B-It: 2.9 GB of weights, and a KV cache measured at ~17 MiB
     * for a full 4096-token context — the two figures whose ratio is the whole argument for not
     * shrinking the context to protect memory.
     */
    private fun gemmaSizedModel() = model(
        id = "gemma",
        sizeBytes = 2_900L * 1024 * 1024,
        kvFullElementsPerToken = 1_024,
        kvSwaElementsPerToken = 1_536,
        slidingWindow = 512,
    )

    /** 6 GB of weights: no phone in this test file can hold it, whatever its memory is doing. */
    private fun oversizedModel() = model(id = "oversized", sizeBytes = 6L * 1024 * 1024 * 1024)

    private fun model(
        id: String,
        sizeBytes: Long,
        kvFullElementsPerToken: Long = 1_024,
        kvSwaElementsPerToken: Long = 0,
        slidingWindow: Long = 0,
    ): ModelEntity {
        val file = File(storageRoot, "$id.gguf").apply { writeText("not really a gguf") }
        return ModelEntity(
            id = id,
            displayName = id,
            fileName = file.name,
            filePath = file.absolutePath,
            sizeBytes = sizeBytes,
            architecture = "gemma4",
            quantLabel = "Q4_K_M",
            paramCount = 2_000_000_000,
            contextLength = 4096,
            embeddingLength = 2_048,
            blockCount = 30,
            supportsThinking = false,
            importedAt = 0L,
            kvFullElementsPerToken = kvFullElementsPerToken,
            kvSwaElementsPerToken = kvSwaElementsPerToken,
            slidingWindow = slidingWindow,
        )
    }

    private fun phone(totalRamGb: Long, availableRamMb: Long) = DeviceProfile(
        totalRamBytes = totalRamGb * 1024 * 1024 * 1024,
        availableRamBytes = availableRamMb * 1024 * 1024,
        socModel = "MT6789 (Dimensity 900)",
        bigCoreCount = 2,
        hasVulkan = false,
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
                    modelId = config.modelId,
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

    /** In-memory [ModelDao] — same shape as [LoadModelBackendTest]'s own. */
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
            supportsThinking: Boolean,
            supportsTools: Boolean,
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
}
