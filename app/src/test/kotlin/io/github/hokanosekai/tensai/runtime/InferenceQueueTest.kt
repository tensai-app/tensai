package io.github.hokanosekai.tensai.runtime

import io.github.hokanosekai.tensai.engine.BackendHint
import io.github.hokanosekai.tensai.engine.ChatMessage
import io.github.hokanosekai.tensai.engine.DecodeEvent
import io.github.hokanosekai.tensai.engine.DecodeRequest
import io.github.hokanosekai.tensai.engine.DecodeStats
import io.github.hokanosekai.tensai.engine.InferenceEngine
import io.github.hokanosekai.tensai.engine.KvCacheType
import io.github.hokanosekai.tensai.engine.LlamaKtEngine
import io.github.hokanosekai.tensai.engine.LoadedModel
import io.github.hokanosekai.tensai.engine.LoadedModelInfo
import io.github.hokanosekai.tensai.engine.MessageRole
import io.github.hokanosekai.tensai.engine.ModelLoadConfig
import io.github.hokanosekai.tensai.engine.GgufInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 *
 * Covers TEN-74 too — the three independent links that took a throwing [InferenceQueue.onBeforeModelSwap]
 * all the way to a queue spinning a core flat out: the unload that left [QueueState.ModelReady]
 * over a freed-but-never-released model, the [InferenceQueue.generate] rejection that skipped its
 * own cleanup, and the retry loops that spun on the result. Each is pinned on its own, and the
 * three are pinned in conjunction.
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

    /**
     * The "Stop" contract, without a device: [InferenceQueue.cancelActive] must actually stop the
     * decode, and the queue must settle back to [QueueState.ModelReady] rather than stay stuck
     * mid-generation. The gate here never completes, so nothing but the cancel can end it.
     *
     * `androidTest`'s `cancelActiveFromOutside` covers the same contract against a real model;
     * this one runs in milliseconds and covers the cleanup path (the `NonCancellable` finally) on
     * every build.
     *
     * **It does not pin TEN-54's ordering fix, and cannot.** Measured: with
     * [InferenceQueue]'s two swapped write pairs reverted and this test left alone, it still
     * passes. `runBlocking` confines everything to one thread, and the begin step's two writes
     * (job, then state) have no suspension point between them, so the window where `state` reads
     * `Generating` with no job behind it is unobservable from here — as it would be from any
     * single-threaded test. Pinning it would take a reader racing on another thread against two
     * adjacent volatile writes, which would fail to observe the bug nearly every run: a test that
     * is green for the wrong reason is worse than an honest gap. The ordering rests on the
     * `@Volatile` argument written on [InferenceQueue]'s field instead.
     */
    @Test
    fun cancelActiveStopsAGenerationAndSettlesBackToModelReady() = runBlocking {
        val engine = FakeEngine(decodeGate = CompletableDeferred())
        val queue = InferenceQueue(engine)
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))

        val collectJob = launch { queue.generate(newRequest()).collect { } }
        withTimeout(2_000) {
            while (queue.state.value !is QueueState.Generating) delay(5)
        }

        queue.cancelActive()

        // join() already implies the `NonCancellable` finally has run to completion, so the state
        // is settled by the time this returns — no second polling loop needed.
        withTimeout(2_000) { collectJob.join() }
        assertTrue(queue.state.value is QueueState.ModelReady)
    }

    @Test
    fun saveSessionReturnsNegativeOneWhenNoModelIsLoaded() = runBlocking {
        val queue = InferenceQueue(FakeEngine())

        assertEquals(-1, queue.saveSession("/sessions/a.state"))
    }

    @Test
    fun saveSessionReturnsNegativeOneWhenTheEngineIsNotALlamaKtEngine() = runBlocking {
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))

        assertEquals(-1, queue.saveSession("/sessions/a.state"))
    }

    @Test
    fun saveSessionDelegatesToTheLlamaKtEngineWhenReady() = runBlocking {
        val engine = FakeLlamaKtEngine(saveTokens = 42)
        val queue = InferenceQueue(engine)
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))

        assertEquals(42, queue.saveSession("/sessions/a.state"))
        assertEquals(listOf("/sessions/a.state"), engine.savedPaths)
    }

    @Test
    fun saveSessionReturnsNegativeOneRatherThanThrowingWhenTheEngineFails() = runBlocking {
        val engine = FakeLlamaKtEngine(saveThrows = true)
        val queue = InferenceQueue(engine)
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))

        assertEquals(-1, queue.saveSession("/sessions/a.state"))
    }

    @Test
    fun restoreSessionReturnsFalseWhenNoModelIsLoaded() = runBlocking {
        val queue = InferenceQueue(FakeEngine())

        assertEquals(false, queue.restoreSession("/sessions/a.state"))
    }

    @Test
    fun restoreSessionReturnsFalseWhenTheEngineIsNotALlamaKtEngine() = runBlocking {
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))

        assertEquals(false, queue.restoreSession("/sessions/a.state"))
    }

    @Test
    fun restoreSessionDelegatesToTheLlamaKtEngineWhenReady() = runBlocking {
        val engine = FakeLlamaKtEngine(loadResult = true)
        val queue = InferenceQueue(engine)
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))

        assertEquals(true, queue.restoreSession("/sessions/a.state"))
        assertEquals(listOf("/sessions/a.state"), engine.loadedPaths)
    }

    @Test
    fun restoreSessionReturnsFalseRatherThanThrowingWhenTheEngineFails() = runBlocking {
        val engine = FakeLlamaKtEngine(loadThrows = true)
        val queue = InferenceQueue(engine)
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))

        assertEquals(false, queue.restoreSession("/sessions/a.state"))
    }

    /**
     * TEN-73. The two methods above answer every failure with a sentinel and never throw, which is
     * what their callers want — but a cancellation is not a failure, and `-1` swallowed it. This is
     * the layer that decides whether anyone upstream ever sees one: `SessionRepository` takes these
     * two as its `saveOnEngine`/`restoreOnEngine`, so its own cancellation guards are unreachable
     * unless these re-throw first.
     *
     * The outcome is read after [cancelAndJoin], not inside the cancelled coroutine, where the only
     * thing observable would be the exception `cancel()` itself raises.
     */
    @Test
    fun aCancelledSaveSessionPropagatesTheCancellationRatherThanReportingAnEngineFailure() = runBlocking {
        val inSave = CompletableDeferred<Unit>()
        val queue = InferenceQueue(FakeLlamaKtEngine(saveGate = inSave))
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))
        var outcome: Any? = null

        val job = launch(Dispatchers.IO) {
            try {
                outcome = queue.saveSession("/sessions/a.state")
            } catch (e: Throwable) {
                outcome = e
                throw e
            }
        }
        inSave.await()
        job.cancelAndJoin()

        assertTrue(
            "a cancelled dump reported as $outcome is indistinguishable from an engine that tried " +
                "and failed",
            outcome is CancellationException,
        )
    }

    /** The restore half of the same guard — where a swallowed cancellation costs a good session. */
    @Test
    fun aCancelledRestoreSessionPropagatesTheCancellationRatherThanReportingAnEngineFailure() = runBlocking {
        val inLoad = CompletableDeferred<Unit>()
        val queue = InferenceQueue(FakeLlamaKtEngine(loadGate = inLoad))
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))
        var outcome: Any? = null

        val job = launch(Dispatchers.IO) {
            try {
                outcome = queue.restoreSession("/sessions/a.state")
            } catch (e: Throwable) {
                outcome = e
                throw e
            }
        }
        inLoad.await()
        job.cancelAndJoin()

        assertTrue(
            "`false` is read upstream as \"this session is unusable\" and self-heals it off disk; " +
                "got $outcome",
            outcome is CancellationException,
        )
    }

    /**
     * TEN-74, first link. The hook is documented as being allowed to throw, and its exception
     * still propagates — but it must not take the model's last reference with it (several GB of
     * native memory nothing can free any more) nor leave [InferenceQueue.state] advertising a
     * model that is already gone.
     */
    @Test
    fun unloadModelFreesTheModelAndSettlesToIdleEvenWhenTheSwapHookThrows() = runBlocking {
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))
        queue.onBeforeModelSwap = { throw RuntimeException("hook failed") }

        val thrown = runCatching { queue.unloadModel() }.exceptionOrNull()

        assertTrue("expected the hook's exception to reach the caller, got $thrown", thrown is RuntimeException)
        assertEquals("the model must be freed even when the hook throws", 1, engine.freeCount)
        assertEquals(QueueState.Idle, queue.state.value)
    }

    /** The same guarantee on the swap path, where the hook also runs. */
    @Test
    fun loadModelFreesThePreviousModelAndSettlesToIdleEvenWhenTheSwapHookThrows() = runBlocking {
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))
        queue.onBeforeModelSwap = { throw RuntimeException("hook failed") }

        val thrown = runCatching { queue.loadModel(ModelLoadConfig(path = "/models/b.gguf")) }.exceptionOrNull()

        assertTrue("expected the hook's exception to reach the caller, got $thrown", thrown is RuntimeException)
        assertEquals(QueueState.Idle, queue.state.value)
        assertEquals(1, engine.freeCount)
    }

    /**
     * Same leak as the two tests above, through a door that is much easier to open than a throwing
     * hook: a cancelled caller. A `viewModelScope` cleared while a swap is in flight is an ordinary
     * Android lifecycle event, not a programming error.
     *
     * [LoadedModel.free] suspends — `LlamaKtEngineImpl` routes it through its own dispatcher — so
     * once the calling coroutine is cancelled, an unguarded `free()` throws at its first suspension
     * point having freed nothing, and the `runCatching` around it files that away as just another
     * failed release. [FakeLoadedModel.free] suspends here for exactly that reason; with a
     * non-suspending fake this test cannot fail, whatever the queue does.
     */
    @Test
    fun aCancelledUnloadStillFreesTheModel() = runBlocking {
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))

        val entered = CompletableDeferred<Unit>()
        // Suspends inside the release, holding it open long enough to be cancelled mid-flight.
        queue.onBeforeModelSwap = {
            entered.complete(Unit)
            delay(10_000)
        }

        val unload = launch { queue.unloadModel() }
        entered.await()
        unload.cancelAndJoin()

        assertEquals("a cancelled release must still release", 1, engine.freeCount)
        assertEquals(QueueState.Idle, queue.state.value)
    }

    /**
     * TEN-74, second link, pinned on its own. A [InferenceQueue.generate] that claims the queue
     * and then finds nothing loaded must still hand it back; leaving [QueueState.Generating]
     * behind with no generation running is what every later load/unload then wedges on.
     */
    @Test
    fun aGenerateThatFindsNothingLoadedDoesNotLeaveTheQueueGenerating() = runBlocking {
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))
        breakLoadedModelInvariant(queue)

        val events = queue.generate(newRequest()).toList()

        val rejected = events.singleOrNull() as? DecodeEvent.Error
        assertTrue(
            "expected a single Error(QueueBusyException) event, got $events",
            rejected != null && rejected.cause is QueueBusyException,
        )
        assertFalse(
            "queue stayed Generating after a rejected generation: ${queue.state.value}",
            queue.state.value is QueueState.Generating,
        )
    }

    /**
     * TEN-74, third link, pinned on its own: a [QueueState.Generating] with no live generation
     * behind it must fail the call, not spin. Bounded by [assertReturnsWithin], not `withTimeout` —
     * see its KDoc for why.
     */
    @Test
    fun loadAndUnloadFailFastRatherThanSpinWhenGeneratingHasNoJobBehindIt() {
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)
        runBlocking { queue.loadModel(ModelLoadConfig(path = "/models/a.gguf")) }
        wedgeOnGenerating(queue)

        val unloadError = assertReturnsWithin(WEDGE_TIMEOUT_MS) { queue.unloadModel() }
        assertTrue("expected unloadModel to fail loudly, got $unloadError", unloadError is IllegalStateException)

        val loadError = assertReturnsWithin(WEDGE_TIMEOUT_MS) { queue.loadModel(ModelLoadConfig(path = "/models/b.gguf")) }
        assertTrue("expected loadModel to fail loudly, got $loadError", loadError is IllegalStateException)
    }

    /**
     * TEN-74 end to end, through the three links in the order they chain: a throwing hook breaks
     * the invariant, a generation turns the break into a stuck [QueueState.Generating], and the
     * next load/unload spins on it forever. Nothing here reaches into the queue's internals — this
     * is the whole bug as a caller can trigger it.
     */
    @Test
    fun aThrowingSwapHookDoesNotWedgeEveryLaterLoadAndUnload() {
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)
        runBlocking {
            queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))
            queue.onBeforeModelSwap = { throw RuntimeException("hook failed") }
            runCatching { queue.unloadModel() }
            queue.generate(newRequest()).toList()
        }

        assertNull(assertReturnsWithin(WEDGE_TIMEOUT_MS) { queue.unloadModel() })
        assertNull(assertReturnsWithin(WEDGE_TIMEOUT_MS) { queue.loadModel(ModelLoadConfig(path = "/models/b.gguf")) })
        assertTrue("expected the queue to be usable again, got ${queue.state.value}", queue.state.value is QueueState.ModelReady)
    }

    /**
     * Runs [block] in its own daemon thread and fails the test if it has not returned within
     * [timeoutMs], handing back whatever it threw (or `null`).
     *
     * A thread-level bound rather than `withTimeout` on purpose: the retry loops this guards
     * ([InferenceQueue.loadModel]/[InferenceQueue.unloadModel]) spin *without ever reaching a
     * cancellable suspension point* once the queue is wedged, so a coroutine timeout would never
     * be delivered and the build would hang on this test instead of failing it. The abandoned
     * thread is a daemon, so it cannot hold the JVM open past the run — and it only survives the
     * call at all when the test is already failing.
     */
    private fun assertReturnsWithin(timeoutMs: Long, block: suspend () -> Unit): Throwable? {
        var thrown: Throwable? = null
        val thread = Thread { thrown = runCatching { runBlocking { block() } }.exceptionOrNull() }
        thread.isDaemon = true
        thread.start()
        thread.join(timeoutMs)
        assertFalse("call did not return within ${timeoutMs}ms — the queue is spinning", thread.isAlive)
        return thrown
    }

    /**
     * Puts [queue] in the state TEN-74's first link used to leave behind: [QueueState.ModelReady]
     * still published, every loaded-model field already cleared.
     *
     * Written straight onto the fields, because no supported path produces this any more — that is
     * the point of the fix. Driving it through a throwing hook instead would make this test pass
     * for the wrong reason the moment that first link is fixed, and stop pinning the second one at
     * all. Defence in depth is only worth having if it is tested in depth.
     */
    private fun breakLoadedModelInvariant(queue: InferenceQueue) {
        setPrivateField(queue, "loadedModel", null)
        setPrivateField(queue, "loadedInfo", null)
        setPrivateField(queue, "loadedConfig", null)
    }

    /**
     * Publishes [QueueState.Generating] over [queue] with no job behind it — the wedge that used
     * to make its retry loops spin. Same white-box rationale as [breakLoadedModelInvariant].
     */
    private fun wedgeOnGenerating(queue: InferenceQueue) {
        val info = queue.state.value.residentModel!!
        setPrivateField(queue, "activeGenerationJob", null)
        @Suppress("UNCHECKED_CAST")
        val state = readPrivateField(queue, "_state") as MutableStateFlow<QueueState>
        state.value = QueueState.Generating(info)
    }

    private fun setPrivateField(queue: InferenceQueue, name: String, value: Any?) {
        InferenceQueue::class.java.getDeclaredField(name).apply { isAccessible = true }.set(queue, value)
    }

    private fun readPrivateField(queue: InferenceQueue, name: String): Any =
        InferenceQueue::class.java.getDeclaredField(name).apply { isAccessible = true }.get(queue)!!

    /** Tracks how many times [loadModel] actually reached the engine — the thing TEN-50's fix avoids doubling. */
    private class FakeEngine(
        private val loadDelayMs: Long = 0L,
        private val decodeGate: CompletableDeferred<Unit>? = null,
    ) : InferenceEngine {
        var loadCallCount = 0
            private set

        /** How many models this engine handed out have been released — TEN-74's leak check. */
        var freeCount = 0
            private set

        override suspend fun loadModel(config: ModelLoadConfig): LoadedModel {
            loadCallCount++
            if (loadDelayMs > 0) delay(loadDelayMs)
            return FakeLoadedModel(
                LoadedModelInfo(
                    name = config.path,
                    nCtxEffective = config.nCtx,
                    backend = "CPU",
                    kvCacheType = config.kvCacheType,
                ),
                decodeGate,
                onFree = { freeCount++ },
            )
        }
    }

    private class FakeLoadedModel(
        override val metadata: LoadedModelInfo,
        private val decodeGate: CompletableDeferred<Unit>?,
        private val onFree: () -> Unit = {},
    ) : LoadedModel {
        override suspend fun decode(request: DecodeRequest): Flow<DecodeEvent> = flow {
            decodeGate?.await()
            emit(DecodeEvent.Done(DecodeStats(tokensGenerated = 0, tokPerSec = 0f, ttftMs = 0)))
        }

        override suspend fun tokenize(text: String): Int = 0

        override suspend fun free() {
            // Suspends like the real one (`LlamaKtEngineImpl.free` is `withContext(dispatcher)`), so a
            // cancelled caller reaching here throws before doing the work unless the queue guards
            // it. A non-suspending fake silently passes `aCancelledUnloadStillFreesTheModel`.
            yield()
            onFree()
        }
    }

    /** A [LlamaKtEngine] fake — covers [InferenceQueue.saveSession]/[InferenceQueue.restoreSession], which need the sub-interface [FakeEngine] deliberately doesn't implement. */
    private class FakeLlamaKtEngine(
        private val saveTokens: Int = 0,
        private val saveThrows: Boolean = false,
        private val loadResult: Boolean = false,
        private val loadThrows: Boolean = false,
        // Completed once the call is inside the engine, then it hangs until cancelled — the only
        // way to cancel a save/restore *while it is running* rather than before it starts, which
        // is the window TEN-73 is about. A fake that returned promptly could never reach it.
        private val saveGate: CompletableDeferred<Unit>? = null,
        private val loadGate: CompletableDeferred<Unit>? = null,
    ) : LlamaKtEngine {
        val savedPaths = mutableListOf<String>()
        val loadedPaths = mutableListOf<String>()

        override suspend fun loadModel(config: ModelLoadConfig): LoadedModel = FakeLoadedModel(
            LoadedModelInfo(
                name = config.path,
                nCtxEffective = config.nCtx,
                backend = "CPU",
                kvCacheType = config.kvCacheType,
            ),
            decodeGate = null,
        )

        override fun kvCacheUsedCells(): Int = 0

        override suspend fun saveSession(path: String): Int {
            savedPaths += path
            if (saveThrows) throw RuntimeException("save failed")
            saveGate?.let { it.complete(Unit); awaitCancellation() }
            return saveTokens
        }

        override suspend fun loadSession(path: String): Boolean {
            loadedPaths += path
            if (loadThrows) throw RuntimeException("load failed")
            loadGate?.let { it.complete(Unit); awaitCancellation() }
            return loadResult
        }

        override suspend fun readMetadata(path: String): GgufInfo =
            throw UnsupportedOperationException("not needed by these tests")
    }

    private companion object {
        /**
         * Bound on a load/unload that a TEN-74 wedge would never let return. Generous on purpose:
         * it is only ever waited out on a test that is already failing.
         */
        const val WEDGE_TIMEOUT_MS = 5_000L
    }
}
