package io.github.tensaiapp.runtime

import io.github.tensaiapp.data.session.SessionRestoreOutcome
import io.github.tensaiapp.data.session.SessionSaveOutcome
import io.github.tensaiapp.engine.BackendHint
import io.github.tensaiapp.engine.ChatMessage
import io.github.tensaiapp.engine.DecodeEvent
import io.github.tensaiapp.engine.DecodeRequest
import io.github.tensaiapp.engine.DecodeStats
import io.github.tensaiapp.engine.InferenceEngine
import io.github.tensaiapp.engine.KvCacheType
import io.github.tensaiapp.engine.LlamaKtEngine
import io.github.tensaiapp.engine.LoadedModel
import io.github.tensaiapp.engine.LoadedModelInfo
import io.github.tensaiapp.engine.MessageRole
import io.github.tensaiapp.engine.ModelLoadConfig
import io.github.tensaiapp.engine.GgufInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext
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
 *
 * And TEN-82, the two remaining halves of the same "a cancelled caller must not walk away
 * mid-swap" rule TEN-74 established for the free: the load itself, whose allocation is not
 * cancellable and so must be handed back rather than dropped, and the save hook that has to
 * precede the free.
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

    /**
     * TEN-84: the dedup has to be decided *before* the generation is cancelled, or a load that
     * turns out to be a no-op kills a reply on its way to discovering it was one.
     *
     * The old order asked [QueueState.ModelReady], which is false for the whole duration of a
     * reply (see [residentModel]), so a repeat load — "Load model" tapped on the model already
     * loaded — fell through to the `Generating` branch, cancelled the reply, joined it, went round
     * the loop and only then recognized the request as a no-op. `loadCallCount` is 1 either way,
     * which is exactly why this test asserts on the generation instead: the user paid a reply for
     * a tap that loaded nothing.
     */
    @Test
    fun aRepeatLoadOfTheResidentModelLeavesTheGenerationInFlightAlone() = runBlocking {
        val decodeGate = CompletableDeferred<Unit>()
        val inDecode = CompletableDeferred<Unit>()
        val engine = FakeEngine(decodeGate = decodeGate, decodeEntered = inDecode)
        val queue = InferenceQueue(engine)
        val config = ModelLoadConfig(path = "/models/a.gguf")
        val loaded = queue.loadModel(config)
        val stopSignal = GenerationStopSignal()
        val events = mutableListOf<DecodeEvent>()

        val collectJob = launch { queue.generate(newRequest(), stopSignal).collect { events += it } }
        withTimeout(2_000) { inDecode.await() }

        val again = withTimeout(2_000) { queue.loadModel(config) }

        assertEquals("the resident model is what a no-op load hands back", loaded, again)
        assertEquals("and it issues no second engine load", 1, engine.loadCallCount)
        assertTrue(
            "the reply is still streaming — a load that loads nothing has no reason to stop it",
            queue.state.value is QueueState.Generating,
        )

        decodeGate.complete(Unit)
        withTimeout(2_000) { collectJob.join() }

        assertEquals("the reply reached its own terminal event", 1, events.size)
        assertTrue(events.single() is DecodeEvent.Done)
        assertFalse("nobody stopped anything", stopSignal.wasRequested)
    }

    @Test
    fun concurrentGenerateCallsRejectTheSecondWithQueueBusy() = runBlocking {
        val decodeGate = CompletableDeferred<Unit>()
        val inDecode = CompletableDeferred<Unit>()
        val engine = FakeEngine(decodeGate = decodeGate, decodeEntered = inDecode)
        val queue = InferenceQueue(engine)
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))

        val firstJob = launch { queue.generate(newRequest()).collect { } }
        withTimeout(2_000) { inDecode.await() }

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
        val inDecode = CompletableDeferred<Unit>()
        val engine = FakeEngine(decodeGate = CompletableDeferred(), decodeEntered = inDecode)
        val queue = InferenceQueue(engine)
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))

        val collectJob = launch { queue.generate(newRequest()).collect { } }
        withTimeout(2_000) { inDecode.await() }

        queue.cancelActive()

        // join() already implies the `NonCancellable` finally has run to completion, so the state
        // is settled by the time this returns — no second polling loop needed.
        withTimeout(2_000) { collectJob.join() }
        assertTrue(queue.state.value is QueueState.ModelReady)
    }

    /**
     * TEN-79: a stop is a fact about the generation that was cancelled, not about whoever asked for
     * it. [InferenceQueue.cancelActive] is the one place both are known at once — it is app-wide,
     * so it stops whatever is running regardless of which screen (or the notification) called it —
     * and it is where the fact is recorded, on the signal that generation was started with.
     */
    @Test
    fun cancelActiveMarksTheStopSignalOfTheGenerationItCancels() = runBlocking {
        val inDecode = CompletableDeferred<Unit>()
        val engine = FakeEngine(decodeGate = CompletableDeferred(), decodeEntered = inDecode)
        val queue = InferenceQueue(engine)
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))
        val stopSignal = GenerationStopSignal()

        val collectJob = launch { queue.generate(newRequest(), stopSignal).collect { } }
        withTimeout(2_000) { inDecode.await() }
        assertFalse("nothing has been asked of it yet", stopSignal.wasRequested)

        queue.cancelActive()
        withTimeout(2_000) { collectJob.join() }

        assertTrue("the generation that was cut is the one that carries the reason", stopSignal.wasRequested)
    }

    /**
     * The negative that gives the signal its meaning: a generation cancelled by something other
     * than a stop must come out unmarked. A model swap cancels the active generation to get at the
     * weights (see [InferenceQueue.loadModel]) — labelling that as "the user stopped it" would put
     * a stop chip on a reply nobody stopped, which is the same class of lie in the other direction.
     */
    @Test
    fun aGenerationCancelledByAModelSwapIsNotMarkedAsStopped() = runBlocking {
        val inDecode = CompletableDeferred<Unit>()
        val engine = FakeEngine(decodeGate = CompletableDeferred(), decodeEntered = inDecode)
        val queue = InferenceQueue(engine)
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))
        val stopSignal = GenerationStopSignal()

        val collectJob = launch { queue.generate(newRequest(), stopSignal).collect { } }
        withTimeout(2_000) { inDecode.await() }

        queue.loadModel(ModelLoadConfig(path = "/models/b.gguf"))
        withTimeout(2_000) { collectJob.join() }

        assertFalse("a swap is not a stop", stopSignal.wasRequested)
    }

    @Test
    fun saveSessionReportsNotAttemptedWhenNoModelIsLoaded() = runBlocking {
        val queue = InferenceQueue(FakeEngine())

        assertEquals(SessionSaveOutcome.NotAttempted, queue.saveSession("/sessions/a.state"))
    }

    @Test
    fun saveSessionReportsNotAttemptedWhenTheEngineIsNotALlamaKtEngine() = runBlocking {
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))

        assertEquals(SessionSaveOutcome.NotAttempted, queue.saveSession("/sessions/a.state"))
    }

    @Test
    fun saveSessionDelegatesToTheLlamaKtEngineWhenReady() = runBlocking {
        val engine = FakeLlamaKtEngine(saveTokens = 42)
        val queue = InferenceQueue(engine)
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))

        assertEquals(SessionSaveOutcome.Wrote(42), queue.saveSession("/sessions/a.state"))
        assertEquals(listOf("/sessions/a.state"), engine.savedPaths)
    }

    @Test
    fun saveSessionReportsEngineFailedRatherThanThrowingWhenTheEngineFails() = runBlocking {
        val engine = FakeLlamaKtEngine(saveThrows = true)
        val queue = InferenceQueue(engine)
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))

        assertEquals(SessionSaveOutcome.EngineFailed, queue.saveSession("/sessions/a.state"))
    }

    /**
     * TEN-78's third case, at the layer that decides it. Mid-generation still declines — see
     * [InferenceQueue.saveSession]'s KDoc for why that stays — but it must say *which* kind of no
     * it is. As `-1` it reached `SessionRepository` as "the engine tried and failed", which
     * `ChatViewModel` answers by cancelling the incoming conversation's restore. A reply streaming
     * in is not an engine failure, and the app is in that state constantly.
     */
    @Test
    fun saveSessionReportsNotAttemptedRatherThanAFailureWhileGenerating() = runBlocking {
        val inDecode = CompletableDeferred<Unit>()
        val engine = FakeLlamaKtEngine(
            saveTokens = 42,
            decodeGate = CompletableDeferred(),
            decodeEntered = inDecode,
        )
        val queue = InferenceQueue(engine)
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))
        val collectJob = launch { queue.generate(newRequest()).collect { } }
        withTimeout(2_000) { inDecode.await() }

        val outcome = queue.saveSession("/sessions/a.state")

        assertEquals(SessionSaveOutcome.NotAttempted, outcome)
        assertEquals("the engine must not have been asked at all", emptyList<String>(), engine.savedPaths)
        collectJob.cancelAndJoin()
    }

    @Test
    fun restoreSessionReportsNotAttemptedWhenNoModelIsLoaded() = runBlocking {
        val queue = InferenceQueue(FakeEngine())

        assertEquals(SessionRestoreOutcome.NotAttempted, queue.restoreSession("/sessions/a.state"))
    }

    @Test
    fun restoreSessionReportsNotAttemptedWhenTheEngineIsNotALlamaKtEngine() = runBlocking {
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))

        assertEquals(SessionRestoreOutcome.NotAttempted, queue.restoreSession("/sessions/a.state"))
    }

    @Test
    fun restoreSessionDelegatesToTheLlamaKtEngineWhenReady() = runBlocking {
        val engine = FakeLlamaKtEngine(loadResult = true)
        val queue = InferenceQueue(engine)
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))

        assertEquals(SessionRestoreOutcome.Restored, queue.restoreSession("/sessions/a.state"))
        assertEquals(listOf("/sessions/a.state"), engine.loadedPaths)
    }

    @Test
    fun restoreSessionReportsEngineRefusedWhenTheEngineSaysNo() = runBlocking {
        val engine = FakeLlamaKtEngine(loadResult = false)
        val queue = InferenceQueue(engine)
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))

        assertEquals(SessionRestoreOutcome.EngineRefused, queue.restoreSession("/sessions/a.state"))
    }

    @Test
    fun restoreSessionReportsEngineRefusedRatherThanThrowingWhenTheEngineFails() = runBlocking {
        val engine = FakeLlamaKtEngine(loadThrows = true)
        val queue = InferenceQueue(engine)
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))

        assertEquals(SessionRestoreOutcome.EngineRefused, queue.restoreSession("/sessions/a.state"))
    }

    /**
     * TEN-81's worse variant, and why the re-check has to be under [InferenceQueue]'s own mutex.
     *
     * The caller validates a session's model/context/KV-cache-type against whatever is loaded, then
     * calls this — and between the two, a `loadModel` can complete. The queue is back on
     * [QueueState.ModelReady], so the old guard passed, but over a *different* model. Handing
     * `loadSession` one model's KV state while another is resident is not a lost session, it is a
     * corrupt one.
     */
    @Test
    fun restoreSessionRefusesToLoadOntoAModelOtherThanTheOneTheCallerValidatedAgainst() = runBlocking {
        val engine = FakeLlamaKtEngine(loadResult = true)
        val queue = InferenceQueue(engine)
        val loaded = queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))
        // What the caller checked against, a moment before some other coroutine swapped the model.
        val staleExpectation = loaded.copy(name = "a-different-model")

        val outcome = queue.restoreSession("/sessions/a.state", expectedModel = staleExpectation)

        assertEquals(SessionRestoreOutcome.NotAttempted, outcome)
        assertEquals(
            "a session must never reach loadSession against a model it was not checked against",
            emptyList<String>(),
            engine.loadedPaths,
        )
    }

    @Test
    fun restoreSessionProceedsWhenTheExpectedModelIsStillTheResidentOne() = runBlocking {
        val engine = FakeLlamaKtEngine(loadResult = true)
        val queue = InferenceQueue(engine)
        val loaded = queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))

        val outcome = queue.restoreSession("/sessions/a.state", expectedModel = loaded)

        assertEquals(SessionRestoreOutcome.Restored, outcome)
        assertEquals(listOf("/sessions/a.state"), engine.loadedPaths)
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
        // [entered] is what makes that window deterministic, so the delay only has to outlast the
        // cancel — and since TEN-82 the hook runs to its end even on a cancelled caller, so it is
        // time this test really spends. A timeout-sized wait here would just be a slow test.
        queue.onBeforeModelSwap = {
            entered.complete(Unit)
            delay(50)
        }

        val unload = launch { queue.unloadModel() }
        entered.await()
        unload.cancelAndJoin()

        assertEquals("a cancelled release must still release", 1, engine.freeCount)
        assertEquals(QueueState.Idle, queue.state.value)
    }

    /**
     * TEN-82, first of two. The mirror image of [aCancelledUnloadStillFreesTheModel] on the load
     * side, and the more expensive one: `LlamaKtEngineImpl` allocates the weights inside a
     * `withContext(dispatcher)` whose blocking native call has no suspension point to cancel at,
     * so a cancelled caller gets the whole allocation and loses the handle to it.
     *
     * Nothing reports that. [InferenceQueue.state] falls back to [QueueState.Idle], the queue
     * reads as empty, and the user's retry loads a second copy of a multi-GB model on top of the
     * first — which is how a silent leak becomes an OOM kill.
     */
    @Test
    fun aCancelledLoadStillRegistersTheModelItLoaded() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val engine = FakeEngine(loadDelayMs = 50, loadEntered = entered)
        val queue = InferenceQueue(engine)

        val load = launch { queue.loadModel(ModelLoadConfig(path = "/models/a.gguf")) }
        entered.await()
        load.cancelAndJoin()

        assertEquals("the load allocated, so there is something to account for", 1, engine.allocatedCount)
        assertTrue(
            "a cancelled load dropped the model it had already allocated: ${queue.state.value}",
            queue.state.value is QueueState.ModelReady,
        )
        // Being registered is the whole point of registering it: the model stays reachable, so
        // whoever comes next can still get rid of it.
        queue.unloadModel()
        assertEquals("what was allocated must still be freeable", engine.allocatedCount, engine.freeCount)
    }

    /**
     * TEN-82, second of two. [InferenceQueue.onBeforeModelSwap] is where `RuntimeGraph` writes the
     * outgoing conversation's KV cache to disk, and it suspends on that I/O. Abandoning it
     * half-way and freeing the model anyway destroys the cache with nothing written — silently,
     * because the hook's body wraps the save in a `runCatching` whose `Result` nobody reads.
     *
     * Pinned by observing [FakeEngine.freeCount] from inside the hook: "the save finished" and
     * "the save finished *before* the free" are the same guarantee, and asserting only the first
     * would leave the ordering to a `try`/`finally` nobody is checking.
     */
    @Test
    fun aCancelledSwapStillRunsTheSaveHookToCompletion() = runBlocking {
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))

        val entered = CompletableDeferred<Unit>()
        var freeCountWhenHookReturned = -1
        queue.onBeforeModelSwap = {
            entered.complete(Unit)
            // The save's own file I/O: a suspension point, which is all a cancellation needs.
            delay(50)
            freeCountWhenHookReturned = engine.freeCount
        }

        val unload = launch { queue.unloadModel() }
        entered.await()
        unload.cancelAndJoin()

        assertEquals(
            "a cancelled swap freed the model without ever finishing the session save",
            0,
            freeCountWhenHookReturned,
        )
        assertEquals(1, engine.freeCount)
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
        setPrivateField(queue, "activeGeneration", null)
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
        // Completed once a load is inside the uninterruptible native section below, which is the
        // only way a test can cancel the caller in the window TEN-82 is about.
        private val loadEntered: CompletableDeferred<Unit>? = null,
        // Same idea for the decode — see [FakeLoadedModel.decodeEntered].
        private val decodeEntered: CompletableDeferred<Unit>? = null,
    ) : InferenceEngine {
        var loadCallCount = 0
            private set

        /** How many loads actually allocated — TEN-82's leak numerator, against [freeCount]. */
        var allocatedCount = 0
            private set

        /** How many models this engine handed out have been released — TEN-74's leak check. */
        var freeCount = 0
            private set

        override suspend fun loadModel(config: ModelLoadConfig): LoadedModel {
            loadCallCount++
            // Mirrors `LlamaKtEngineImpl.loadModel`, which is a blocking native load inside a
            // `withContext(dispatcher)`. The native call has no suspension point, so cancelling
            // the caller does not stop it: the weights are allocated in full ...
            val model = withContext(NonCancellable) {
                loadEntered?.complete(Unit)
                if (loadDelayMs > 0) delay(loadDelayMs)
                allocatedCount++
                FakeLoadedModel(
                    LoadedModelInfo(
                        name = config.path,
                        nCtxEffective = config.nCtx,
                        backend = "CPU",
                        kvCacheType = config.kvCacheType,
                    ),
                    decodeGate,
                    decodeEntered,
                    onFree = { freeCount++ },
                )
            }
            // ... and it is the dispatch's *return* that throws rather than handing the model
            // back, dropping the only reference to it. A fake that threw before allocating instead
            // would pass `aCancelledLoadStillRegistersTheModelItLoaded` whatever the queue does.
            coroutineContext.ensureActive()
            return model
        }
    }

    private class FakeLoadedModel(
        override val metadata: LoadedModelInfo,
        private val decodeGate: CompletableDeferred<Unit>?,
        // Completed on entering the decode, before the gate below is waited on. [InferenceQueue]
        // publishes the active job and [QueueState.Generating] under its mutex *before* calling
        // decode, so a test that awaits this is standing at a point where the queue is provably
        // generating — no poll, and no guess about how fast the machine is (TEN-94).
        private val decodeEntered: CompletableDeferred<Unit>? = null,
        private val onFree: () -> Unit = {},
    ) : LoadedModel {
        override suspend fun decode(request: DecodeRequest): Flow<DecodeEvent> = flow {
            decodeEntered?.complete(Unit)
            decodeGate?.await()
            emit(DecodeEvent.Done(DecodeStats(tokensGenerated = 0, tokPerSec = 0f, ttftMs = 0)))
        }

        override suspend fun tokenize(text: String): Int = 0

        override suspend fun free() {
            // Suspends like the real one (`LlamaKtEngineImpl.free` dispatches onto the engine's own
            // single thread), so a cancelled caller reaching here throws before doing the work unless
            // the queue guards it. A non-suspending fake silently passes
            // `aCancelledUnloadStillFreesTheModel`.
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
        // Same idea one layer out: holds the *decode* open so the queue can be observed on
        // [QueueState.Generating] while a save is issued against it (TEN-78).
        private val decodeGate: CompletableDeferred<Unit>? = null,
        // And the matching signal that the decode has started — see [FakeLoadedModel.decodeEntered].
        private val decodeEntered: CompletableDeferred<Unit>? = null,
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
            decodeGate = decodeGate,
            decodeEntered = decodeEntered,
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
