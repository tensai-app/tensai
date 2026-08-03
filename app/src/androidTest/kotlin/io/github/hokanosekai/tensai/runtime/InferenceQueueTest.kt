package io.github.hokanosekai.tensai.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.hokanosekai.tensai.engine.BackendHint
import io.github.hokanosekai.tensai.engine.ChatMessage
import io.github.hokanosekai.tensai.engine.DecodeEvent
import io.github.hokanosekai.tensai.engine.DecodeRequest
import io.github.hokanosekai.tensai.engine.LlamaKtEngineImpl
import io.github.hokanosekai.tensai.engine.MessageRole
import io.github.hokanosekai.tensai.engine.ModelLoadConfig
import io.github.hokanosekai.tensai.engine.SamplingConfig
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [InferenceQueue] against a real GGUF, on top of
 * [LlamaKtEngineImpl].
 *
 * Same model convention as `EngineSmokeTest` — skipped (not failed) when
 * absent:
 *
 * ```
 * adb push some-small-model.gguf \
 *   /sdcard/Android/data/io.github.hokanosekai.tensai/files/test-model.gguf
 * ```
 */
@RunWith(AndroidJUnit4::class)
class InferenceQueueTest {

    private val modelPath: String by lazy {
        val dir = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext.getExternalFilesDir(null)
        File(dir, "test-model.gguf").absolutePath
    }

    private fun assumeModelPresent() {
        assumeTrue(
            "No test model at $modelPath — skipping (adb push a GGUF there to run this test)",
            File(modelPath).exists(),
        )
    }

    private fun newQueue() = InferenceQueue(LlamaKtEngineImpl())

    private suspend fun InferenceQueue.loadTestModel() = loadModel(
        ModelLoadConfig(path = modelPath, nCtx = 512, backend = BackendHint.Cpu),
    )

    @Test
    fun loadThenGenerate() = runBlocking {
        assumeModelPresent()
        val queue = newQueue()

        queue.loadTestModel()
        assertTrue("expected ModelReady after load, got ${queue.state.value}", queue.state.value is QueueState.ModelReady)

        val request = DecodeRequest(
            messages = listOf(ChatMessage(role = MessageRole.User, content = "Say hi")),
            sampling = SamplingConfig(maxTokens = 16),
        )
        val events = queue.generate(request).toList()

        val errorEvents = events.filterIsInstance<DecodeEvent.Error>()
        val doneEvents = events.filterIsInstance<DecodeEvent.Done>()
        assertTrue("expected no error events, got $errorEvents", errorEvents.isEmpty())
        assertTrue("expected exactly one done event, got ${doneEvents.size}", doneEvents.size == 1)
        assertTrue(
            "expected ModelReady after generation, got ${queue.state.value}",
            queue.state.value is QueueState.ModelReady,
        )

        queue.unloadModel()
    }

    @Test
    fun rejectsConcurrentGenerate() = runBlocking {
        assumeModelPresent()
        val queue = newQueue()
        queue.loadTestModel()

        val longRequest = DecodeRequest(
            messages = listOf(ChatMessage(role = MessageRole.User, content = "Tell a long story")),
            sampling = SamplingConfig(maxTokens = 512),
        )

        val firstJob = launch { queue.generate(longRequest).collect { } }

        withTimeout(5_000) {
            while (queue.state.value !is QueueState.Generating) delay(10)
        }

        val secondEvents = queue.generate(
            DecodeRequest(
                messages = listOf(ChatMessage(role = MessageRole.User, content = "hi")),
                sampling = SamplingConfig(maxTokens = 4),
            ),
        ).toList()

        val rejected = secondEvents.singleOrNull() as? DecodeEvent.Error
        assertTrue(
            "expected a single Error(QueueBusyException) event, got $secondEvents",
            rejected != null && rejected.cause is QueueBusyException,
        )

        firstJob.cancel()
        firstJob.join()
        queue.unloadModel()
    }

    @Test
    fun cancelStopsGeneration() = runBlocking {
        assumeModelPresent()
        val queue = newQueue()
        queue.loadTestModel()

        val request = DecodeRequest(
            messages = listOf(ChatMessage(role = MessageRole.User, content = "Tell a long story")),
            sampling = SamplingConfig(maxTokens = 512),
        )

        var tokensCollected = 0
        val collectJob = launch {
            queue.generate(request).collect { event ->
                if (event is DecodeEvent.Token) tokensCollected++
            }
        }

        withTimeout(10_000) {
            while (tokensCollected < 3) delay(10)
        }
        collectJob.cancel()
        collectJob.join()

        withTimeout(2_000) {
            while (queue.state.value !is QueueState.ModelReady) delay(10)
        }
        assertTrue(queue.state.value is QueueState.ModelReady)

        queue.unloadModel()
    }

    @Test
    fun cancelActiveFromOutside() = runBlocking {
        assumeModelPresent()
        val queue = newQueue()
        queue.loadTestModel()

        val request = DecodeRequest(
            messages = listOf(ChatMessage(role = MessageRole.User, content = "Tell a long story")),
            sampling = SamplingConfig(maxTokens = 512),
        )

        val collectJob = launch { queue.generate(request).collect { } }

        withTimeout(5_000) {
            while (queue.state.value !is QueueState.Generating) delay(10)
        }

        queue.cancelActive()
        collectJob.join()

        withTimeout(2_000) {
            while (queue.state.value !is QueueState.ModelReady) delay(10)
        }
        assertTrue(queue.state.value is QueueState.ModelReady)

        queue.unloadModel()
    }
}
