package io.github.hokanosekai.tensai.ui.screens.loading

import android.content.ContextWrapper
import io.github.hokanosekai.tensai.compat.DeviceProfile
import io.github.hokanosekai.tensai.data.BackendPreference
import io.github.hokanosekai.tensai.data.models.ModelDao
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.data.models.ModelRepository
import io.github.hokanosekai.tensai.engine.DecodeEvent
import io.github.hokanosekai.tensai.engine.DecodeRequest
import io.github.hokanosekai.tensai.engine.GgufInfo
import io.github.hokanosekai.tensai.engine.LlamaKtEngine
import io.github.hokanosekai.tensai.engine.LoadedModel
import io.github.hokanosekai.tensai.engine.ModelLoadConfig
import io.github.hokanosekai.tensai.runtime.InferenceQueue
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
 * Built the same JVM-safe way as `LoadModelBackendTest` — a bare `ContextWrapper(null)` and an
 * explicit `storageRoot`, no device, no GGUF.
 */
class LoadModelCancellationTest {

    private lateinit var storageRoot: File
    private lateinit var modelDao: FakeModelDao
    private lateinit var modelRepository: ModelRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        storageRoot = Files.createTempDirectory("tensai-load-cancel-test").toFile()
        modelDao = FakeModelDao()
        modelRepository = ModelRepository(
            context = ContextWrapper(null),
            modelDao = modelDao,
            readMetadata = { throw UnsupportedOperationException("not needed by these tests") },
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
    fun cancellingAnInFlightLoadLeavesNoFailureDialogBehind() = runBlocking {
        val inLoad = CompletableDeferred<Unit>()
        val viewModel = LoadModelViewModel(
            modelId = MODEL_ID,
            modelRepository = modelRepository,
            deviceProfile = { profile() },
            backendPreference = { BackendPreference.Auto },
            queue = InferenceQueue(HangingEngine(inLoad)),
            scope = scope,
        )
        modelDao.insert(model())

        viewModel.start()
        withTimeout(5_000) { inLoad.await() }
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
     * Announces that the load has started, then hangs until cancelled. The hang is the point: a
     * load that returns promptly can only be cancelled before or after itself, never during, and
     * "during" is the whole window this ticket is about.
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

        override suspend fun withUnknownKvCacheShape(): List<ModelEntity> =
            models.values.filter { it.kvFullElementsPerToken == 0L }

        override suspend fun updateKvCacheShape(
            id: String,
            kvFullElementsPerToken: Long,
            kvSwaElementsPerToken: Long,
            slidingWindow: Long,
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
