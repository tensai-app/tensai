package io.github.tensaiapp.ui.screens.chat

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tensaiapp.data.chat.ChatRepository
import io.github.tensaiapp.data.models.TensaiDb
import io.github.tensaiapp.data.session.SessionRepository
import io.github.tensaiapp.engine.BackendHint
import io.github.tensaiapp.engine.LlamaKtEngineImpl
import io.github.tensaiapp.engine.ModelLoadConfig
import io.github.tensaiapp.runtime.InferenceQueue
import io.github.tensaiapp.runtime.QueueState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * Instrumented tests for [ChatViewModel] against a real GGUF, on top of a real [InferenceQueue]
 * and an in-memory [TensaiDb] — same model convention as `EngineSmokeTest`/`InferenceQueueTest`,
 * skipped (not failed) when absent:
 *
 * ```
 * adb push some-small-model.gguf \
 *   /sdcard/Android/data/io.github.tensaiapp/files/test-model.gguf
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ChatViewModelTest {

    /**
     * Backstop for a coroutine stuck inside a non-cancellable native call ([withTimeout] can't
     * catch that — see [io.github.tensaiapp.runtime.InferenceQueueTest]'s
     * `globalTimeout` for the full TEN-55/TEN-17 rationale and why 90s). Wraps `@Before`/`@Test`/
     * `@After` together. Runs on a separate thread and fails from the outside on timeout; does
     * not unstick the native call itself.
     */
    @get:Rule
    val globalTimeout: Timeout = Timeout.seconds(90)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val modelPath: String by lazy {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(dir, "test-model.gguf").absolutePath
    }

    private fun assumeModelPresent() {
        assumeTrue(
            "No test model at $modelPath — skipping (adb push a GGUF there to run this test)",
            File(modelPath).exists(),
        )
    }

    private lateinit var db: TensaiDb
    private lateinit var chatRepository: ChatRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var engine: LlamaKtEngineImpl
    private lateinit var queue: InferenceQueue
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, TensaiDb::class.java).build()
        chatRepository = ChatRepository(db.chatDao())
        engine = LlamaKtEngineImpl()
        queue = InferenceQueue(engine)
        // cacheDir, never filesDir: this must never touch the real device's imported models (see
        // ModelRepository's storageRoot KDoc) — same reasoning applies to any real Room db.
        sessionRepository = SessionRepository(
            dao = db.sessionDao(),
            storageRoot = context.cacheDir,
        )
        // A dedicated scope per test rather than RuntimeGraph.applicationScope — same idea as
        // InferenceQueueTest's own fresh InferenceQueue per test: no shared state across tests.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        // TEN-66: the queue is handed this engine and does not own it, so closing it is this
        // test's job — one engine is built per test here, and each one's `llama-kt-engine` thread
        // would otherwise stay alive for the rest of the instrumented run. Last, after the tests'
        // own `unloadTestModel()`: releasing a model runs on the dispatcher this shuts down.
        engine.close()
    }

    private suspend fun loadTestModel() = withTimeout(LOAD_TIMEOUT_MS) {
        queue.loadModel(ModelLoadConfig(path = modelPath, nCtx = 512, backend = BackendHint.Cpu))
    }

    /** Bounded like [loadTestModel] (TEN-55) — a stuck unload must fail the test, not the runner. */
    private suspend fun unloadTestModel() = withTimeout(UNLOAD_TIMEOUT_MS) { queue.unloadModel() }

    private fun newViewModel(conversationId: String) = ChatViewModel(
        conversationId = conversationId,
        chatRepository = chatRepository,
        inferenceQueue = queue,
        sessionRepository = sessionRepository,
        scope = scope,
    )

    @Test
    fun sendPersistsUserMessageAndStreamsAssistantReplyWithStats() = runBlocking {
        assumeModelPresent()
        loadTestModel()

        val conversationId = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversationId)

        viewModel.send("Say hi")

        val messages = withTimeout(30_000) {
            var current = chatRepository.observeMessages(conversationId).first()
            while (current.size < 2 || current[1].tokensGenerated == null) {
                delay(50)
                current = chatRepository.observeMessages(conversationId).first()
            }
            current
        }

        assertEquals(2, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("Say hi", messages[0].content)
        assertEquals("assistant", messages[1].role)
        assertTrue("expected a non-empty assistant reply", messages[1].content.isNotBlank())
        assertNotNull("expected stats once the reply finished", messages[1].tokensGenerated)
        assertTrue((messages[1].tokPerSec ?: 0f) > 0f)

        unloadTestModel()
    }

    @Test
    fun stopKeepsThePartialReplyAndReturnsTheQueueToReady() = runBlocking {
        assumeModelPresent()
        loadTestModel()

        val conversationId = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversationId)

        viewModel.send("Tell a long, detailed story about a journey.")

        withTimeout(10_000) {
            while (queue.state.value !is QueueState.Generating) delay(10)
        }
        // Wait for actual streamed content, not a fixed delay — TTFT on CPU is not bounded
        // tightly enough for a fixed sleep to reliably land after the first token.
        withTimeout(20_000) {
            var current = chatRepository.observeMessages(conversationId).first()
            while (current.size < 2 || current[1].content.isEmpty()) {
                delay(50)
                current = chatRepository.observeMessages(conversationId).first()
            }
        }
        viewModel.stop()

        withTimeout(5_000) {
            while (queue.state.value !is QueueState.ModelReady) delay(10)
        }
        assertTrue(queue.state.value is QueueState.ModelReady)

        val messages = chatRepository.observeMessages(conversationId).first()
        assertEquals(2, messages.size)
        assertTrue("expected some partial content to have been kept", messages[1].content.isNotEmpty())
        assertNull("a stopped reply never finished, so it has no stats", messages[1].tokensGenerated)

        unloadTestModel()
    }

    private companion object {
        /** See [io.github.tensaiapp.runtime.InferenceQueueTest]'s `LOAD_TIMEOUT_MS` for rationale. */
        const val LOAD_TIMEOUT_MS = 60_000L

        /** See [io.github.tensaiapp.runtime.InferenceQueueTest]'s `UNLOAD_OR_CANCEL_TIMEOUT_MS`. */
        const val UNLOAD_TIMEOUT_MS = 30_000L
    }
}
