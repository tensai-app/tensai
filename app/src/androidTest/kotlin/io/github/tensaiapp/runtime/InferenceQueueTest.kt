package io.github.tensaiapp.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tensaiapp.engine.BackendHint
import io.github.tensaiapp.engine.ChatMessage
import io.github.tensaiapp.engine.DecodeEvent
import io.github.tensaiapp.engine.DecodeRequest
import io.github.tensaiapp.engine.LlamaKtEngineImpl
import io.github.tensaiapp.engine.MessageRole
import io.github.tensaiapp.engine.ModelLoadConfig
import io.github.tensaiapp.engine.SamplingConfig
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
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
 *   /sdcard/Android/data/io.github.tensaiapp/files/test-model.gguf
 * ```
 */
@RunWith(AndroidJUnit4::class)
class InferenceQueueTest {

    /**
     * Backstop for exactly the TEN-55 failure mode the `withTimeout`s below can't catch: a
     * coroutine stuck inside a non-cancellable blocking native call (llama.cpp's `load`/
     * `completion`, both routed through `LlamaKtEngineImpl`'s single-thread dispatcher) never
     * reaches a suspension point for `withTimeout` to fire on — same mechanism as TEN-17's
     * `interrupt()` not being honored during prefill. Confirmed on device: a genuinely stuck
     * `loadThenGenerate` ran past every internal `withTimeout` with zero of them firing, `utime`/
     * `stime` flat, only killed by the host after 240s+.
     *
     * [Timeout] runs each test method on a separate thread and reports a failure from *outside*
     * it once the deadline passes, so a verdict comes back even if the test's own thread is still
     * blocked (and now leaked) afterward. It does not, and structurally cannot, unstick the native
     * call itself — only [withTimeout] failing at a real suspension point does that.
     *
     * 90s: comfortably above this class's nominal runtime — a healthy `loadThenGenerate` takes a
     * few seconds, and the full 29-test suite finishes in ~40s — with margin for a slow or
     * thermally throttled device, while still failing in well under a minute and a half rather
     * than the 200+s hang observed on device.
     */
    @get:Rule
    val globalTimeout: Timeout = Timeout.seconds(90)

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

    /**
     * Every engine [newQueue] has built, closed in [closeEngines] (TEN-66).
     *
     * A queue does not own the engine it is handed and must not close it (see [InferenceQueue]'s
     * KDoc), so with one queue — and therefore one engine — built per test, the closing has to
     * happen here, at the construction site. Without it each test left its engine's
     * `llama-kt-engine` thread alive for the rest of the instrumented run: two of them were
     * observed still resident during the TEN-55 freeze.
     */
    private val engines = mutableListOf<LlamaKtEngineImpl>()

    private fun newQueue() = InferenceQueue(LlamaKtEngineImpl().also(engines::add))

    /**
     * Runs after each test's own `unloadTestModel()` — the order [LlamaKtEngineImpl.close]
     * requires, since releasing the model runs on the dispatcher this shuts down. A test that
     * failed before unloading still gets its thread back: `close` interrupts an in-flight
     * completion rather than waiting for it. It does not get the *weights* back, but that is the
     * pre-existing shape of these tests (the unloads are not in a `finally`), not something
     * closing the engine could fix.
     */
    @After
    fun closeEngines() {
        engines.forEach { it.close() }
        engines.clear()
    }

    private suspend fun InferenceQueue.loadTestModel() = withTimeout(LOAD_TIMEOUT_MS) {
        loadModel(ModelLoadConfig(path = modelPath, nCtx = 512, backend = BackendHint.Cpu))
    }

    /**
     * [InferenceQueue.unloadModel] bounded the same way as every join below — see
     * [UNLOAD_OR_CANCEL_TIMEOUT_MS]. A silent freeze here is exactly the TEN-55 failure mode: a
     * genuine hang must fail the test, not the whole runner.
     */
    private suspend fun InferenceQueue.unloadTestModel() = withTimeout(UNLOAD_OR_CANCEL_TIMEOUT_MS) {
        unloadModel()
    }

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
        val events = withTimeout(GENERATE_TIMEOUT_MS) { queue.generate(request).toList() }

        val errorEvents = events.filterIsInstance<DecodeEvent.Error>()
        val doneEvents = events.filterIsInstance<DecodeEvent.Done>()
        assertTrue("expected no error events, got $errorEvents", errorEvents.isEmpty())
        assertTrue("expected exactly one done event, got ${doneEvents.size}", doneEvents.size == 1)
        assertTrue(
            "expected ModelReady after generation, got ${queue.state.value}",
            queue.state.value is QueueState.ModelReady,
        )

        queue.unloadTestModel()
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

        val secondEvents = withTimeout(GENERATE_TIMEOUT_MS) {
            queue.generate(
                DecodeRequest(
                    messages = listOf(ChatMessage(role = MessageRole.User, content = "hi")),
                    sampling = SamplingConfig(maxTokens = 4),
                ),
            ).toList()
        }

        val rejected = secondEvents.singleOrNull() as? DecodeEvent.Error
        assertTrue(
            "expected a single Error(QueueBusyException) event, got $secondEvents",
            rejected != null && rejected.cause is QueueBusyException,
        )

        firstJob.cancel()
        withTimeout(UNLOAD_OR_CANCEL_TIMEOUT_MS) { firstJob.join() }
        queue.unloadTestModel()
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
        withTimeout(UNLOAD_OR_CANCEL_TIMEOUT_MS) { collectJob.join() }

        withTimeout(2_000) {
            while (queue.state.value !is QueueState.ModelReady) delay(10)
        }
        assertTrue(queue.state.value is QueueState.ModelReady)

        queue.unloadTestModel()
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
        withTimeout(UNLOAD_OR_CANCEL_TIMEOUT_MS) { collectJob.join() }

        withTimeout(2_000) {
            while (queue.state.value !is QueueState.ModelReady) delay(10)
        }
        assertTrue(queue.state.value is QueueState.ModelReady)

        queue.unloadTestModel()
    }

    private companion object {
        /**
         * Bound on [InferenceQueue.loadModel]. Mmapping and initializing the ~379 MiB test GGUF
         * normally takes low single-digit seconds on CPU; 60s leaves headroom for a slow or
         * thermally throttled device while still failing loudly — rather than freezing the whole
         * instrumented run — if the native load genuinely never returns (TEN-55).
         */
        const val LOAD_TIMEOUT_MS = 60_000L

        /**
         * Bound on [InferenceQueue.unloadModel] and on joining a job after `cancel()`/
         * [InferenceQueue.cancelActive]. Every test here runs with `nCtx = 512`, so the prefill
         * `interrupt()` can't preempt until it returns (see `LlamaKtEngineImpl`'s KDoc, confirmed on
         * device for TEN-17) is short — cancellation should resolve in well under a second. 30s is
         * generous slack for a slow device without letting a real hang run indefinitely.
         */
        const val UNLOAD_OR_CANCEL_TIMEOUT_MS = 30_000L

        /**
         * Bound on collecting a full generation to completion. Both uses here cap `maxTokens` low
         * (4 or 16), so even a slow CPU decode finishes in seconds; 60s is a uniform "this is
         * actually stuck" threshold, matching [LOAD_TIMEOUT_MS].
         */
        const val GENERATE_TIMEOUT_MS = 60_000L
    }
}
