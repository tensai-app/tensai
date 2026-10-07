package io.github.hokanosekai.tensai.runtime

import io.github.hokanosekai.tensai.data.session.SessionRestoreOutcome
import io.github.hokanosekai.tensai.data.session.SessionSaveOutcome
import io.github.hokanosekai.tensai.engine.ChatMessage
import io.github.hokanosekai.tensai.engine.DecodeEvent
import io.github.hokanosekai.tensai.engine.DecodeRequest
import io.github.hokanosekai.tensai.engine.DecodeStats
import io.github.hokanosekai.tensai.engine.GgufInfo
import io.github.hokanosekai.tensai.engine.LlamaKtEngine
import io.github.hokanosekai.tensai.engine.LoadedModel
import io.github.hokanosekai.tensai.engine.LoadedModelInfo
import io.github.hokanosekai.tensai.engine.MessageRole
import io.github.hokanosekai.tensai.engine.ModelLoadConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
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
 * Fast, JVM-only tests for [InferenceQueue.warmUp] (TEN-88): background work that yields to
 * everything else that wants the engine. A fake model records, in one ordered list, when a warm-up
 * started, was cancelled, ended, and when the other calls reached the engine, the *order* is what
 * "cancelled and awaited before" means, and a bare outcome would pass without it.
 */
class InferenceQueueWarmUpTest {

    private val history = listOf(
        ChatMessage(MessageRole.User, "q"),
        ChatMessage(MessageRole.Assistant, "a long reply"),
    )

    private fun request() = DecodeRequest(messages = listOf(ChatMessage(MessageRole.User, "next")))

    /**
     * [runBlocking] under a deadline. Without one a test whose warm-up was *not* cancelled (the very
     * regression these tests exist for) leaves a coroutine parked on its hold, and `runBlocking`
     * waits for its children for good: the failure would be a hung build, not a red test. The
     * timeout cancels everything launched inside it.
     */
    private fun inTest(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    private suspend fun InferenceQueue.warm(info: LoadedModelInfo) =
        warmUp(history, enableThinking = true, expectedModel = info)

    private suspend fun load(engine: FakeEngine, queue: InferenceQueue): LoadedModelInfo =
        queue.loadModel(ModelLoadConfig(path = "/models/a.gguf"))

    @Test
    fun aWarmUpOnAReadyQueueCompletesAndNeverShowsAsGenerating() = inTest {
        val engine = FakeEngine(hold = CompletableDeferred())
        val queue = InferenceQueue(engine)
        val info = load(engine, queue)

        val outcome = async { queue.warm(info) }
        withTimeout(5_000) { engine.warmUpEntered.await() }
        assertTrue(
            "the chrome keeps saying ready: a warm-up is not a state of the queue",
            queue.state.value is QueueState.ModelReady,
        )
        engine.hold!!.complete(Unit)

        assertEquals(WarmUpOutcome.Warmed, outcome.await())
        assertEquals(listOf("warm-start", "warm-end"), engine.events)
    }

    @Test
    fun aGenerationCancelsARunningWarmUpAndStartsOnlyAfterItHasWoundDown() = inTest {
        val engine = FakeEngine(hold = CompletableDeferred())
        val queue = InferenceQueue(engine)
        val info = load(engine, queue)

        val outcome = async { queue.warm(info) }
        withTimeout(5_000) { engine.warmUpEntered.await() }
        val events = withTimeout(5_000) { queue.generate(request()).toList() }

        assertTrue(events.last() is DecodeEvent.Done)
        assertEquals("interrupted, not failed, and the caller was not cancelled", WarmUpOutcome.Interrupted, outcome.await())
        assertEquals(
            "the decode reached the engine only once the warm-up had returned it",
            listOf("warm-start", "warm-cancelled", "decode"),
            engine.events,
        )
    }

    @Test
    fun aModelSwapCancelsARunningWarmUpAndWaitsForItBeforeReleasingTheModel() = inTest {
        val engine = FakeEngine(hold = CompletableDeferred())
        val queue = InferenceQueue(engine)
        val info = load(engine, queue)

        val outcome = async { queue.warm(info) }
        withTimeout(5_000) { engine.warmUpEntered.await() }
        withTimeout(5_000) { queue.loadModel(ModelLoadConfig(path = "/models/b.gguf")) }

        assertEquals(WarmUpOutcome.Interrupted, outcome.await())
        assertEquals(listOf("warm-start", "warm-cancelled", "free"), engine.events)
    }

    @Test
    fun anUnloadCancelsARunningWarmUpToo() = inTest {
        val engine = FakeEngine(hold = CompletableDeferred())
        val queue = InferenceQueue(engine)
        val info = load(engine, queue)

        val outcome = async { queue.warm(info) }
        withTimeout(5_000) { engine.warmUpEntered.await() }
        withTimeout(5_000) { queue.unloadModel() }

        assertEquals(WarmUpOutcome.Interrupted, outcome.await())
        assertEquals(listOf("warm-start", "warm-cancelled", "free"), engine.events)
        assertTrue(queue.state.value is QueueState.Idle)
    }

    @Test
    fun aRestoreCancelsARunningWarmUpBecauseItReplacesTheWholeCache() = inTest {
        val engine = FakeEngine(hold = CompletableDeferred())
        val queue = InferenceQueue(engine)
        val info = load(engine, queue)

        val outcome = async { queue.warm(info) }
        withTimeout(5_000) { engine.warmUpEntered.await() }
        val restored = withTimeout(5_000) { queue.restoreSession("/s/a", expectedModel = info) }

        assertEquals(SessionRestoreOutcome.Restored, restored)
        assertEquals(WarmUpOutcome.Interrupted, outcome.await())
        assertEquals(listOf("warm-start", "warm-cancelled", "restore"), engine.events)
    }

    /**
     * The session decision: a save cancels the warm-up and does not wait it out. A save that waited
     * minutes would leave nothing on disk for that long, and `onStop` is the one that has to land
     * before the process may die. The order is the proof: the dump reaches the engine after the
     * warm-up has returned the thread, and its hold is never released, so it cannot have ended on
     * its own.
     */
    @Test
    fun aSaveCancelsTheWarmUpAndSavesWhatIsCached() = inTest {
        val engine = FakeEngine(hold = CompletableDeferred())
        val queue = InferenceQueue(engine)
        val info = load(engine, queue)

        val outcome = async { queue.warm(info) }
        withTimeout(5_000) { engine.warmUpEntered.await() }
        val saved = withTimeout(5_000) { queue.saveSession("/s/a") }

        assertEquals(SessionSaveOutcome.Wrote(7), saved)
        assertEquals(WarmUpOutcome.Interrupted, outcome.await())
        assertEquals(listOf("warm-start", "warm-cancelled", "save"), engine.events)
    }

    @Test
    fun onlyOneWarmUpRunsAtATime() = inTest {
        val engine = FakeEngine(hold = CompletableDeferred())
        val queue = InferenceQueue(engine)
        val info = load(engine, queue)

        val first = async { queue.warm(info) }
        withTimeout(5_000) { engine.warmUpEntered.await() }
        val second = queue.warm(info)

        assertEquals(WarmUpOutcome.Skipped, second)
        assertEquals("the second never reached the engine", listOf("warm-start"), engine.events)
        engine.hold!!.complete(Unit)
        assertEquals(WarmUpOutcome.Warmed, first.await())
    }

    @Test
    fun aWarmUpForAModelThatIsNotTheLoadedOneIsSkipped() = inTest {
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)
        val info = load(engine, queue)

        val outcome = queue.warm(info.copy(modelId = "another"))

        assertEquals(WarmUpOutcome.Skipped, outcome)
        assertTrue(engine.events.isEmpty())
    }

    @Test
    fun aWarmUpWhileAGenerationRunsIsSkippedAndLeavesItAlone() = inTest {
        val engine = FakeEngine(decodeHold = CompletableDeferred())
        val queue = InferenceQueue(engine)
        val info = load(engine, queue)

        val generation = launch { queue.generate(request()).toList() }
        withTimeout(5_000) { engine.decodeEntered.await() }
        val outcome = queue.warm(info)

        assertEquals(WarmUpOutcome.Skipped, outcome)
        assertTrue("the generation is untouched", queue.state.value is QueueState.Generating)
        engine.decodeHold!!.complete(Unit)
        generation.join()
    }

    @Test
    fun cancellingTheCallerOfAWarmUpPropagatesAndFreesTheQueueForTheNext() = inTest {
        val engine = FakeEngine(hold = CompletableDeferred())
        val queue = InferenceQueue(engine)
        val info = load(engine, queue)

        val caller = launch { queue.warm(info) }
        withTimeout(5_000) { engine.warmUpEntered.await() }
        caller.cancelAndJoin()

        assertTrue(caller.isCancelled)
        assertEquals(listOf("warm-start", "warm-cancelled"), engine.events)
        engine.hold!!.complete(Unit)
        assertEquals("nothing is left registered", WarmUpOutcome.Warmed, queue.warm(info))
    }

    @Test
    fun anEngineThatThrowsMakesTheWarmUpFailButNotTheQueue() = inTest {
        val engine = FakeEngine(warmUpThrows = true)
        val queue = InferenceQueue(engine)
        val info = load(engine, queue)

        assertEquals(WarmUpOutcome.Failed, queue.warm(info))
        assertTrue(queue.generate(request()).toList().last() is DecodeEvent.Done)
    }

    @Test
    fun anEngineThatDeclinesReportsSkipped() = inTest {
        val engine = FakeEngine(warmUpResult = false)
        val queue = InferenceQueue(engine)
        val info = load(engine, queue)

        assertEquals(WarmUpOutcome.Skipped, queue.warm(info))
    }

    /** One ordered log of everything that reached the model, so tests can assert on sequence. */
    private class FakeEngine(
        // Holds a warm-up open until completed, or until it is cancelled.
        val hold: CompletableDeferred<Unit>? = null,
        val decodeHold: CompletableDeferred<Unit>? = null,
        private val warmUpThrows: Boolean = false,
        private val warmUpResult: Boolean = true,
    ) : LlamaKtEngine {
        val events = mutableListOf<String>()
        val warmUpEntered = CompletableDeferred<Unit>()
        val decodeEntered = CompletableDeferred<Unit>()

        override suspend fun loadModel(config: ModelLoadConfig): LoadedModel = object : LoadedModel {
            override val metadata = LoadedModelInfo(
                name = config.path,
                nCtxEffective = config.nCtx,
                backend = "CPU",
                kvCacheType = config.kvCacheType,
            )

            override suspend fun decode(request: DecodeRequest): Flow<DecodeEvent> = flow {
                events += "decode"
                decodeEntered.complete(Unit)
                decodeHold?.await()
                emit(DecodeEvent.Done(DecodeStats(tokensGenerated = 0, tokPerSec = 0f, ttftMs = 0)))
            }

            override suspend fun warmUp(messages: List<ChatMessage>, enableThinking: Boolean): Boolean {
                events += "warm-start"
                warmUpEntered.complete(Unit)
                if (warmUpThrows) throw IllegalStateException("prefill broke")
                if (hold != null) {
                    try {
                        hold.await()
                    } catch (e: CancellationException) {
                        events += "warm-cancelled"
                        throw e
                    }
                }
                events += "warm-end"
                return warmUpResult
            }

            override suspend fun tokenize(text: String): Int = 0

            override suspend fun free() {
                events += "free"
            }
        }

        override fun kvCacheUsedCells(): Int = 0

        override suspend fun saveSession(path: String): Int {
            events += "save"
            return 7
        }

        override suspend fun loadSession(path: String): Boolean {
            events += "restore"
            return true
        }

        override suspend fun readMetadata(path: String): GgufInfo =
            throw UnsupportedOperationException("not needed by these tests")
    }
}
