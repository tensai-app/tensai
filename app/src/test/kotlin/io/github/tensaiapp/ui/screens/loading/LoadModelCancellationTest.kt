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
import io.github.tensaiapp.engine.GgufInfo
import io.github.tensaiapp.engine.LlamaKtEngine
import io.github.tensaiapp.engine.LoadedModel
import io.github.tensaiapp.engine.ModelLoadConfig
import io.github.tensaiapp.runtime.InferenceQueue
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TEN-73, at the screen this ticket names: a cancelled load must not be posted as a load failure.
 *
 * `LoadFailureTest` pins the classifier in isolation; this drives a real [LoadModelViewModel] over
 * a real [InferenceQueue] and reads [LoadModelViewModel.state] *after* the scope has been cancelled
 * and joined, which is the only place the answer is observable — inside the cancelled coroutine
 * there is nothing to see but the exception `cancel()` raised.
 *
 * A caveat this test cannot express and that belongs next to it: nothing in the app cancels a load.
 * [LoadModelViewModel] runs on `RuntimeGraph.applicationScope` precisely so that leaving the screen
 * doesn't ("Continue in background", TEN-30), and that scope lives as long as the process. The
 * cancellable `scope` here is a test injection. So this is a latent defect pinned, not a bug a user
 * could have reported.
 *
 * Where the cancellation lands moved with TEN-82. The load itself is now `NonCancellable` — a
 * blocking native load has no suspension point to stop at, and letting the caller walk away from
 * it drops the only reference to several GB — so what is left to cancel is the *wait* in front of
 * it: [InferenceQueue]'s mutex. That is not a contrivance, it is the ordinary case on a device,
 * where a second `LoadModelViewModel` (a config change, a retry from a dialog) queues up behind a
 * load already running. The question TEN-73 asks is unchanged, only asked one suspension point
 * earlier.
 *
 * Built the same JVM-safe way as `LoadModelBackendTest` — a bare `ContextWrapper(null)` and an
 * explicit `storageRoot`, no device, no GGUF.
 */
class LoadModelCancellationTest {

    private lateinit var storageRoot: File
    private lateinit var modelDao: FakeModelDao
    private lateinit var modelRepository: ModelRepository
    private lateinit var scope: CoroutineScope

    /**
     * Holds the queue's mutex for the duration of a test, on a scope of its own so cancelling
     * [scope] does not touch it. Its load never returns and, since TEN-82, cancelling it would not
     * make it return either — which is why it gets a scope nothing joins.
     */
    private lateinit var occupier: CoroutineScope

    @Before
    fun setUp() {
        storageRoot = Files.createTempDirectory("tensai-load-cancel-test").toFile()
        modelDao = FakeModelDao()
        modelRepository = ModelRepository(
            context = ContextWrapper(null),
            modelDao = modelDao,
            readMetadata = { throw UnsupportedOperationException("not needed by these tests") },
            kvShapeVersion = { throw UnsupportedOperationException("not needed by these tests") },
            unlessInUse = { _, block -> block(); true },
            storageRoot = storageRoot,
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        occupier = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        occupier.cancel()
        storageRoot.deleteRecursively()
    }

    @Test
    fun cancellingAnInFlightLoadLeavesNoFailureDialogBehind() = runBlocking {
        val inLoad = CompletableDeferred<Unit>()
        val queue = InferenceQueue(HangingEngine(inLoad))
        // Take the queue first, and never give it back: that is what puts the view model's own
        // load on the queue's mutex, the suspension point a cancellation can still be delivered
        // at (see this class's KDoc).
        occupier.launch { queue.loadModel(ModelLoadConfig(path = "/models/other.gguf")) }
        withTimeout(5_000) { inLoad.await() }

        val viewModel = LoadModelViewModel(
            modelId = MODEL_ID,
            modelRepository = modelRepository,
            deviceProfile = { profile() },
            backendPreference = { BackendPreference.Auto },
            contextPrefs = { ContextPrefs() },
            queue = queue,
            scope = scope,
        )
        modelDao.insert(model())

        viewModel.start()
        // [LoadUiState.InProgress] is published immediately before the `queue.loadModel` call, and
        // nothing between the two suspends — so this is the load waiting on the mutex.
        withTimeout(5_000) { viewModel.state.first { it is LoadUiState.InProgress } }
        scope.coroutineContext[Job]!!.cancelAndJoin()

        val state = viewModel.state.value
        assertTrue(
            "the load was cancelled, not broken — telling the user it \"failed for an unknown " +
                "reason\" is a dialog about something that never happened; got $state",
            state !is LoadUiState.GenericFailed,
        )
        assertTrue(
            "and nothing else settled it either: the last thing that happened is that it was " +
                "still loading; got $state",
            state is LoadUiState.InProgress,
        )
    }

    private fun model(): ModelEntity {
        val file = File(storageRoot, "model.gguf").apply { writeText("not really a gguf") }
        return ModelEntity(
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
    }

    /** 8 GB total / 6 GB free — enough headroom that nothing here is blocked as "won't fit". */
    private fun profile() = DeviceProfile(
        totalRamBytes = 8L * 1024 * 1024 * 1024,
        availableRamBytes = 6L * 1024 * 1024 * 1024,
        socModel = "test-soc",
        bigCoreCount = 4,
        hasVulkan = false,
        hasOpenCl = false,
    )

    /**
     * Announces that the load has started, then never returns. The hang is the point: a load that
     * returns promptly hands the queue's mutex straight back, and it is holding that mutex that
     * puts a *second* load in the window this ticket is about.
     *
     * Not cancellable any more — since TEN-82 the queue runs this under `NonCancellable`, so the
     * `awaitCancellation` here waits for something that will never come. Deliberate: it is what
     * makes the mutex stay taken for the whole test, and the coroutine it strands dies with the
     * JVM having held no thread (see [occupier]).
     */
    private class HangingEngine(private val inLoad: CompletableDeferred<Unit>) : LlamaKtEngine {
        override suspend fun loadModel(config: ModelLoadConfig): LoadedModel {
            inLoad.complete(Unit)
            awaitCancellation()
        }

        override fun kvCacheUsedCells(): Int = 0

        override suspend fun saveSession(path: String): Int = 0

        override suspend fun loadSession(path: String): Boolean = true

        override suspend fun readMetadata(path: String): GgufInfo =
            throw UnsupportedOperationException("not needed by these tests")
    }

    /** In-memory [ModelDao] — same shape as `LoadModelBackendTest`'s own. */
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
