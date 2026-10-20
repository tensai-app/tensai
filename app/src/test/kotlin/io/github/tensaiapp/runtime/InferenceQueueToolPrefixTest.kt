package io.github.tensaiapp.runtime

import io.github.tensaiapp.data.session.SessionRestoreOutcome
import io.github.tensaiapp.engine.ChatMessage
import io.github.tensaiapp.engine.DecodeEvent
import io.github.tensaiapp.engine.DecodeRequest
import io.github.tensaiapp.engine.DecodeStats
import io.github.tensaiapp.engine.GgufInfo
import io.github.tensaiapp.engine.LlamaKtEngine
import io.github.tensaiapp.engine.LoadedModel
import io.github.tensaiapp.engine.LoadedModelInfo
import io.github.tensaiapp.engine.MessageRole
import io.github.tensaiapp.engine.ModelLoadConfig
import io.github.tensaiapp.engine.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fast, JVM-only tests for [InferenceQueue.warmToolPrefix] (TEN-205), the same background work as
 * [InferenceQueue.warmUp] (see [InferenceQueueWarmUpTest]) aimed at a model that was just loaded:
 * it yields to everything, and it knows whether the cache holds only the prefix it put there.
 */
class InferenceQueueToolPrefixTest {

    private val request = DecodeRequest(
        messages = listOf(ChatMessage(MessageRole.System, "be brief")),
        tools = listOf(ToolDefinition("datetime", "the time", "{}")),
    )

    private companion object {
        const val FULL_PREFIX = 40
        const val HALF_PREFIX = 20
    }

    private fun inTest(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    private suspend fun load(queue: InferenceQueue, path: String = "/models/a.gguf"): LoadedModelInfo =
        queue.loadModel(ModelLoadConfig(path = path))

    @Test
    fun aWarmUpOnAReadyQueueFillsTheCacheAndNeverShowsAsGenerating() = inTest {
        val engine = FakeEngine(hold = CompletableDeferred())
        val queue = InferenceQueue(engine)
        val info = load(queue)

        val outcome = async { queue.warmToolPrefix(request, info) }
        withTimeout(5_000) { engine.entered.await() }
        assertTrue("not a state of the queue", queue.state.value is QueueState.ModelReady)
        engine.hold!!.complete(Unit)

        assertEquals(WarmUpOutcome.Warmed, outcome.await())
        assertEquals(listOf("prefix-start", "prefix-end"), engine.events)
        assertTrue(queue.holdsOnlyWarmedPrefix())
    }

    @Test
    fun aGenerationCancelsARunningWarmUpAndStartsOnlyAfterItHasWoundDown() = inTest {
        val engine = FakeEngine(hold = CompletableDeferred())
        val queue = InferenceQueue(engine)
        val info = load(queue)

        val outcome = async { queue.warmToolPrefix(request, info) }
        withTimeout(5_000) { engine.entered.await() }
        val events = withTimeout(5_000) { queue.generate(request).toList() }

        assertTrue(events.last() is DecodeEvent.Done)
        assertEquals(WarmUpOutcome.Interrupted, outcome.await())
        assertEquals(
            "the generation finished the prefix (file included) before it decoded",
            listOf("prefix-start", "prefix-cancelled", "prefix-start", "prefix-end", "decode"),
            engine.events,
        )
        assertEquals("the finishing call may carry on over what is resident", listOf(false, true), engine.replaceFlags)
        assertEquals("the half the warm-up reached was resumed, not redone", FULL_PREFIX, engine.prefilled)
        assertEquals(FULL_PREFIX, engine.usedCells)
        assertFalse("a conversation has taken the cache over", queue.holdsOnlyWarmedPrefix())
    }

    @Test
    fun aGenerationAfterACompletedWarmUpHasNothingToFinish() = inTest {
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)
        val info = load(queue)

        assertEquals(WarmUpOutcome.Warmed, queue.warmToolPrefix(request, info))
        queue.generate(request).toList()

        assertEquals(listOf("prefix-start", "prefix-end", "decode"), engine.events)
        assertEquals(listOf(false), engine.replaceFlags)
    }

    @Test
    fun aGenerationWithoutToolsDoesNotFinishAPrefix() = inTest {
        val engine = FakeEngine(hold = CompletableDeferred())
        val queue = InferenceQueue(engine)
        val info = load(queue)

        val outcome = async { queue.warmToolPrefix(request, info) }
        withTimeout(5_000) { engine.entered.await() }
        queue.generate(request.copy(tools = emptyList())).toList()

        assertEquals(WarmUpOutcome.Interrupted, outcome.await())
        assertEquals(listOf(false), engine.replaceFlags)
    }

    @Test
    fun cancellingTheCallerPropagatesLeavesAPartialPrefixAndTheQueueUsable() = inTest {
        val engine = FakeEngine(hold = CompletableDeferred())
        val queue = InferenceQueue(engine)
        val info = load(queue)

        val warm = async { queue.warmToolPrefix(request, info) }
        withTimeout(5_000) { engine.entered.await() }
        warm.cancel()

        assertTrue("the caller's own cancellation is not turned into an outcome", runCatching { warm.await() }.exceptionOrNull() is CancellationException)
        assertEquals("what it decoded stays", HALF_PREFIX, engine.usedCells)
        assertTrue("a partial prefix counts as prefix-only", queue.holdsOnlyWarmedPrefix())
        assertTrue(queue.state.value is QueueState.ModelReady)

        val events = withTimeout(5_000) { queue.generate(request).toList() }
        assertTrue(events.last() is DecodeEvent.Done)
        assertEquals(FULL_PREFIX, engine.usedCells)
    }

    @Test
    fun aModelSwapCancelsARunningWarmUpAndWaitsForItBeforeReleasingTheModel() = inTest {
        val engine = FakeEngine(hold = CompletableDeferred())
        val queue = InferenceQueue(engine)
        val info = load(queue)

        val outcome = async { queue.warmToolPrefix(request, info) }
        withTimeout(5_000) { engine.entered.await() }
        withTimeout(5_000) { load(queue, "/models/b.gguf") }

        assertEquals(WarmUpOutcome.Interrupted, outcome.await())
        assertEquals(listOf("prefix-start", "prefix-cancelled", "free"), engine.events)
        assertFalse("the cache of the old model is gone", queue.holdsOnlyWarmedPrefix())
    }

    @Test
    fun anUnloadCancelsARunningWarmUpToo() = inTest {
        val engine = FakeEngine(hold = CompletableDeferred())
        val queue = InferenceQueue(engine)
        val info = load(queue)

        val outcome = async { queue.warmToolPrefix(request, info) }
        withTimeout(5_000) { engine.entered.await() }
        withTimeout(5_000) { queue.unloadModel() }

        assertEquals(WarmUpOutcome.Interrupted, outcome.await())
        assertEquals(listOf("prefix-start", "prefix-cancelled", "free"), engine.events)
        assertTrue(queue.state.value is QueueState.Idle)
    }

    @Test
    fun aSessionRestoreEndsThePrefixOnlyState() = inTest {
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)
        val info = load(queue)
        queue.warmToolPrefix(request, info)
        assertTrue(queue.holdsOnlyWarmedPrefix())

        assertEquals(SessionRestoreOutcome.Restored, queue.restoreSession("/tmp/session", expectedModel = info))

        assertFalse(queue.holdsOnlyWarmedPrefix())
    }

    @Test
    fun aCacheThatHoldsAConversationIsNeverCalledPrefixOnly() = inTest {
        val engine = FakeEngine(usedCells = 300)
        val queue = InferenceQueue(engine)
        val info = load(queue)

        queue.warmToolPrefix(request, info)

        assertFalse("the engine left it alone, and so must the flag that would let a restore replace it", queue.holdsOnlyWarmedPrefix())
        assertEquals("and asked it not to replace what is there", listOf(false), engine.replaceFlags)
    }

    @Test
    fun aSecondWarmUpOverThePrefixItBuiltMayReplaceIt() = inTest {
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)
        val info = load(queue)

        queue.warmToolPrefix(request, info)
        engine.usedCells = 40
        queue.warmToolPrefix(request.copy(tools = emptyList()), info)

        assertEquals(listOf(false, true), engine.replaceFlags)
        assertTrue(queue.holdsOnlyWarmedPrefix())
    }

    @Test
    fun aWarmUpOnAnotherModelThanTheExpectedOneIsSkipped() = inTest {
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)
        val info = load(queue)
        load(queue, "/models/b.gguf")

        assertEquals(WarmUpOutcome.Skipped, queue.warmToolPrefix(request, info))
        assertTrue(engine.events.none { it.startsWith("prefix") })
    }

    private class FakeEngine(
        val hold: CompletableDeferred<Unit>? = null,
        var usedCells: Int = 0,
    ) : LlamaKtEngine {
        val events = mutableListOf<String>()

        /** Tokens the fake prefilled in all: resuming from what is resident adds only the rest. */
        var prefilled = 0
            private set
        val replaceFlags = mutableListOf<Boolean>()
        val entered = CompletableDeferred<Unit>()

        override suspend fun loadModel(config: ModelLoadConfig): LoadedModel = object : LoadedModel {
            override val metadata = LoadedModelInfo(
                name = config.path,
                nCtxEffective = config.nCtx,
                backend = "CPU",
                kvCacheType = config.kvCacheType,
            )

            override suspend fun decode(request: DecodeRequest): Flow<DecodeEvent> = flow {
                events += "decode"
                emit(DecodeEvent.Done(DecodeStats(tokensGenerated = 0, tokPerSec = 0f, ttftMs = 0)))
            }

            override suspend fun warmToolPrefix(request: DecodeRequest, replaceResident: Boolean): Boolean {
                // The hold is for the first call only: a finishing call after a cancel must be able to complete.
                val first = replaceFlags.isEmpty()
                events += "prefix-start"
                replaceFlags += replaceResident
                entered.complete(Unit)
                if (usedCells > 0 && !replaceResident) return false
                if (first && hold != null) {
                    // Half way before it waits: what a cancel leaves in the cache.
                    prefilled += HALF_PREFIX - usedCells
                    usedCells = HALF_PREFIX
                    try {
                        hold.await()
                    } catch (e: CancellationException) {
                        events += "prefix-cancelled"
                        throw e
                    }
                }
                prefilled += FULL_PREFIX - usedCells
                usedCells = FULL_PREFIX
                events += "prefix-end"
                return true
            }

            override suspend fun tokenize(text: String): Int = 0

            override suspend fun free() {
                events += "free"
            }
        }

        override fun kvCacheUsedCells(): Int = usedCells

        override suspend fun saveSession(path: String): Int = 7

        override suspend fun loadSession(path: String): Boolean = true

        override suspend fun readMetadata(path: String): GgufInfo =
            throw UnsupportedOperationException("not needed by these tests")
    }
}
