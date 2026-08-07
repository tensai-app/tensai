package io.github.hokanosekai.tensai.runtime

import io.github.hokanosekai.tensai.engine.BackendHint
import io.github.hokanosekai.tensai.engine.ChatMessage
import io.github.hokanosekai.tensai.engine.DecodeEvent
import io.github.hokanosekai.tensai.engine.DecodeRequest
import io.github.hokanosekai.tensai.engine.DecodeStats
import io.github.hokanosekai.tensai.engine.InferenceEngine
import io.github.hokanosekai.tensai.engine.LoadedModel
import io.github.hokanosekai.tensai.engine.LoadedModelInfo
import io.github.hokanosekai.tensai.engine.MessageRole
import io.github.hokanosekai.tensai.engine.ModelLoadConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fast, JVM-only unit tests for [InferenceQueue]'s guards against a fake [InferenceEngine] — no
 * GGUF, no device required. See `InferenceQueueTest` under `androidTest` for the same class
 * exercised against a real engine/model.
 *
 * Covers TEN-50: a repeat [InferenceQueue.loadModel] call for the model already loaded (or being
 * loaded) must not trigger a second, redundant [InferenceEngine.loadModel] — this is what a
 * `LoadModelViewModel` recreated across a config change/process restart used to do before this
 * fix, doubling load time. [InferenceQueue.generate]'s existing busy-rejection is covered too, as
 * a fast regression check for the same "no second concurrent generation" contract `ChatViewModel`
 * relies on.
 */
class InferenceQueueTest {

    private fun newRequest() = DecodeRequest(
        messages = listOf(ChatMessage(role = MessageRole.User, content = "hi")),
    )

    @Test
    fun repeatLoadModelForTheSameAlreadyLoadedPathReturnsTheCachedInfoWithoutReloading() = runBlocking {
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)
        val config = ModelLoadConfig(path = "/models/a.gguf")

        val first = queue.loadModel(config)
        val second = queue.loadModel(config)

        assertEquals(1, engine.loadCallCount)
        assertEquals(first, second)
        assertTrue(queue.state.value is QueueState.ModelReady)
    }

    @Test
    fun concurrentLoadModelCallsForTheSamePathOnlyLoadOnce() = runBlocking {
        // A slow engine widens the window during which the second call can race the first —
        // without the TEN-50 fix, this reproduces the double-load bug (two full loads instead of
        // one, the second waiting behind the mutex and then reloading from scratch).
        val engine = FakeEngine(loadDelayMs = 50)
        val queue = InferenceQueue(engine)
        val config = ModelLoadConfig(path = "/models/a.gguf")

        val first = async { queue.loadModel(config) }
        val second = async { queue.loadModel(config) }

        val firstInfo = first.await()
        val secondInfo = second.await()

        assertEquals(1, engine.loadCallCount)
        assertEquals(firstInfo, secondInfo)
        assertTrue(queue.state.value is QueueState.ModelReady)
    }

    @Test
    fun loadModelForADifferentPathStillReplacesTheLoadedModel() = runBlocking {
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)

        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))
        val second = queue.loadModel(ModelLoadConfig(path = "/models/b.gguf"))

        assertEquals(2, engine.loadCallCount)
        assertEquals("/models/b.gguf", second.name)
        assertTrue(queue.state.value is QueueState.ModelReady)
    }

    @Test
    fun reloadingTheSamePathWithADifferentBackendIsNotDeduped() = runBlocking {
        // The dedup key is the whole load config, not just the path: a CPU retry after a Vulkan
        // failure reuses the path but must really reload, not silently keep the GPU-loaded model.
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)

        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf", backend = BackendHint.Auto))
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf", backend = BackendHint.Cpu))

        assertEquals(2, engine.loadCallCount)
        assertTrue(queue.state.value is QueueState.ModelReady)
    }

    @Test
    fun reloadingTheSamePathWithADifferentContextSizeIsNotDeduped() = runBlocking {
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)

        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf", nCtx = 4096))
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf", nCtx = 2048))

        assertEquals(2, engine.loadCallCount)
        assertTrue(queue.state.value is QueueState.ModelReady)
    }

    @Test
    fun repeatLoadWithADifferentProgressCallbackIsStillDeduped() = runBlocking {
        // onProgress is per-call and identifies nothing — a recreated LoadModelViewModel passes a
        // brand new lambda for the very same model, and that must still hit the dedup path.
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)

        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf", onProgress = {}))
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf", onProgress = {}))

        assertEquals(1, engine.loadCallCount)
    }

    @Test
    fun concurrentGenerateCallsRejectTheSecondWithQueueBusy() = runBlocking {
        val decodeGate = CompletableDeferred<Unit>()
        val engine = FakeEngine(decodeGate = decodeGate)
        val queue = InferenceQueue(engine)
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))

        val firstJob = launch { queue.generate(newRequest()).collect { } }
        withTimeout(2_000) {
            while (queue.state.value !is QueueState.Generating) delay(5)
        }

        val secondEvents = queue.generate(newRequest()).toList()
        val rejected = secondEvents.singleOrNull() as? DecodeEvent.Error
        assertTrue(
            "expected a single Error(QueueBusyException) event, got $secondEvents",
            rejected != null && rejected.cause is QueueBusyException,
        )

        decodeGate.complete(Unit)
        firstJob.join()
        assertTrue(queue.state.value is QueueState.ModelReady)
    }

    /** Tracks how many times [loadModel] actually reached the engine — the thing TEN-50's fix avoids doubling. */
    private class FakeEngine(
        private val loadDelayMs: Long = 0L,
        private val decodeGate: CompletableDeferred<Unit>? = null,
    ) : InferenceEngine {
        var loadCallCount = 0
            private set

        override suspend fun loadModel(config: ModelLoadConfig): LoadedModel {
            loadCallCount++
            if (loadDelayMs > 0) delay(loadDelayMs)
            return FakeLoadedModel(
                LoadedModelInfo(name = config.path, nCtxEffective = config.nCtx, backend = "CPU"),
                decodeGate,
            )
        }
    }

    private class FakeLoadedModel(
        override val metadata: LoadedModelInfo,
        private val decodeGate: CompletableDeferred<Unit>?,
    ) : LoadedModel {
        override suspend fun decode(request: DecodeRequest): Flow<DecodeEvent> = flow {
            decodeGate?.await()
            emit(DecodeEvent.Done(DecodeStats(tokensGenerated = 0, tokPerSec = 0f, ttftMs = 0)))
        }

        override suspend fun tokenize(text: String): Int = 0

        override suspend fun free() = Unit
    }
}
