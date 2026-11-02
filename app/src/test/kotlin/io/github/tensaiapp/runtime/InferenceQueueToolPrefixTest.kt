package io.github.tensaiapp.runtime

import io.github.tensaiapp.data.session.SessionRestoreOutcome
import io.github.tensaiapp.data.session.SessionSaveOutcome
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
import io.github.tensaiapp.engine.ToolChoice
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
        queue.warmToolPrefix(request.copy(tools = request.tools + ToolDefinition("phone", "opens an app", "{}")), info)

        assertEquals(listOf(false, true), engine.replaceFlags)
        assertTrue(queue.holdsOnlyWarmedPrefix())
    }

    @Test
    fun aWarmUpWithoutToolsLeavesThePrefixAndTheNextGenerationAlone() = inTest {
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)
        val info = load(queue)

        queue.warmToolPrefix(request, info)
        engine.usedCells = 40
        assertEquals(WarmUpOutcome.Skipped, queue.warmToolPrefix(request.copy(tools = emptyList()), info))
        queue.generate(request.copy(messages = request.messages + ChatMessage(MessageRole.User, "hi"))).toList()

        assertEquals("the prefix built first is still the one resident", listOf("prefix-start", "prefix-end", "decode"), engine.events)
        assertEquals(listOf(false), engine.replaceFlags)
        assertTrue(queue.holdsPrefixOf(request))
    }

    @Test
    fun aStopDuringThePrefixRestoreLeavesTheQueueReadyAndTheNextGenerationResumes() = inTest {
        val engine = FakeEngine(hold = CompletableDeferred())
        val queue = InferenceQueue(engine)
        load(queue)
        val asked = request.messages + ChatMessage(MessageRole.User, "hi")
        queue.generate(request.copy(messages = asked, tools = emptyList())).toList()
        engine.usedCells = 300

        val stopped = async { runCatching { queue.generate(request.copy(messages = asked)).toList() } }
        withTimeout(5_000) { engine.entered.await() }
        queue.cancelActive()
        stopped.await()

        assertTrue(queue.state.value is QueueState.ModelReady)
        assertEquals(HALF_PREFIX, engine.usedCells)
        queue.generate(request.copy(messages = asked)).toList()

        assertEquals(listOf("decode", "prefix-start", "prefix-cancelled", "decode"), engine.events)
        assertEquals("the cache already holds part of this prefix: the decode prefills the rest", listOf(true), engine.replaceFlags)
    }

    @Test
    fun aSavedConversationIsReplacedWhenTheCacheStillHoldsWhatWasSaved() = inTest {
        val engine = FakeEngine(usedCells = 300)
        val queue = InferenceQueue(engine)
        val info = load(queue)
        var replaced = 0

        assertEquals(WarmUpOutcome.Warmed, queue.warmToolPrefix(request, info, replaceSaved = 300, onReplace = { replaced++ }))

        assertEquals(listOf(true), engine.replaceFlags)
        assertEquals(1, replaced)
        assertTrue("a saved session may now restore over it", queue.holdsOnlyWarmedPrefix())
        assertTrue(queue.holdsPrefixOf(request))
    }

    @Test
    fun aConversationThatMovedOnSinceItsSaveIsLeftAlone() = inTest {
        val engine = FakeEngine(usedCells = 300)
        val queue = InferenceQueue(engine)
        val info = load(queue)
        var replaced = 0

        queue.warmToolPrefix(request, info, replaceSaved = 290, onReplace = { replaced++ })

        assertEquals("what was generated after the save is not on disk", listOf(false), engine.replaceFlags)
        assertEquals(0, replaced)
        assertFalse(queue.holdsOnlyWarmedPrefix())
    }

    @Test
    fun aGenerationDuringTheReplaceOfAConversationFinishesThePrefix() = inTest {
        val engine = FakeEngine(hold = CompletableDeferred(), usedCells = 300)
        val queue = InferenceQueue(engine)
        val info = load(queue)

        val outcome = async { queue.warmToolPrefix(request, info, replaceSaved = 300) }
        withTimeout(5_000) { engine.entered.await() }
        val events = withTimeout(5_000) { queue.generate(request).toList() }

        assertTrue(events.last() is DecodeEvent.Done)
        assertEquals(WarmUpOutcome.Interrupted, outcome.await())
        assertEquals(listOf("prefix-start", "prefix-cancelled", "prefix-start", "prefix-end", "decode"), engine.events)
        assertEquals(listOf(true, true), engine.replaceFlags)
        assertFalse(queue.holdsOnlyWarmedPrefix())
    }

    @Test
    fun aCacheHoldingOnlyAPrefixIsNeverSavedAsAConversationsSession() = inTest {
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)
        val info = load(queue)
        queue.warmToolPrefix(request, info)

        assertEquals(SessionSaveOutcome.NotAttempted, queue.saveSession("/tmp/session"))

        queue.generate(request).toList()
        assertEquals("a conversation's state is saved as before", SessionSaveOutcome.Wrote(7), queue.saveSession("/tmp/session"))
    }

    @Test
    fun theResidentPrefixIsTheLastGenerationsAndUnknownAfterARestore() = inTest {
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)
        val info = load(queue)
        assertFalse("nothing in the cache", queue.holdsPrefixOf(request))

        queue.generate(request.copy(messages = request.messages + ChatMessage(MessageRole.User, "hi"))).toList()
        assertTrue("the user's words come after the prefix", queue.holdsPrefixOf(request))
        assertTrue("the template never sees the tool choice", queue.holdsPrefixOf(request.copy(toolChoice = ToolChoice.None)))
        assertFalse(queue.holdsPrefixOf(request.copy(tools = request.tools + ToolDefinition("phone", "opens an app", "{}"))))

        queue.restoreSession("/tmp/session", expectedModel = info)
        assertFalse(queue.holdsPrefixOf(request))
    }

    @Test
    fun aToolGenerationAfterOneWithoutToolsPutsThePrefixBackFirst() = inTest {
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)
        load(queue)
        val asked = request.messages + ChatMessage(MessageRole.User, "hi")

        queue.generate(request.copy(messages = asked, tools = emptyList())).toList()
        engine.usedCells = 300
        queue.generate(request.copy(messages = asked)).toList()

        assertEquals("a creative turn holds no tool prefix", listOf("decode", "prefix-start", "prefix-end", "decode"), engine.events)
        assertEquals("over the cache that turn left", listOf(true), engine.replaceFlags)
    }

    @Test
    fun aToolGenerationLeavesTheCacheAloneAfterAToolOneOrARestore() = inTest {
        val engine = FakeEngine(usedCells = 300)
        val queue = InferenceQueue(engine)
        val info = load(queue)
        val asked = request.messages + ChatMessage(MessageRole.User, "hi")

        queue.generate(request.copy(messages = asked)).toList()
        queue.generate(request.copy(messages = asked)).toList()
        queue.generate(request.copy(messages = asked, tools = emptyList())).toList()
        queue.restoreSession("/tmp/session", expectedModel = info)
        queue.generate(request.copy(messages = asked)).toList()

        assertEquals("the prefix is resident, or a conversation's own session is", List(4) { "decode" }, engine.events)
    }

    /** TEN-196: the process dies after a creative turn, and only the saved session says what the cache held. */
    @Test
    fun aSessionSavedWithoutToolsPutsThePrefixBackBeforeTheFirstToolTurnAfterItsRestore() = inTest {
        val asked = request.messages + ChatMessage(MessageRole.User, "hi")
        val before = InferenceQueue(FakeEngine())
        load(before)
        before.generate(request.copy(messages = asked, tools = emptyList())).toList()
        val saved = before.saveSession("/tmp/session")
        assertEquals(SessionSaveOutcome.Wrote(7, withoutTools = true), saved)

        val engine = FakeEngine()
        val queue = InferenceQueue(engine)
        val info = load(queue)
        assertEquals(SessionRestoreOutcome.Restored, queue.restoreSession("/tmp/session", info, withoutTools = true))
        engine.usedCells = 300
        queue.generate(request.copy(messages = asked)).toList()
        queue.generate(request.copy(messages = asked)).toList()

        assertEquals(listOf("prefix-start", "prefix-end", "decode", "decode"), engine.events)
        assertEquals("over the restored cache, once", listOf(true), engine.replaceFlags)
        assertEquals("a tool turn's cache is saved as one", SessionSaveOutcome.Wrote(7), queue.saveSession("/tmp/session"))
    }

    /** TEN-196: the swap hook saves the outgoing session, and has to see what its cache held. */
    @Test
    fun theSwapHookStillKnowsTheOutgoingCacheHoldsNoToolPrefix() = inTest {
        val queue = InferenceQueue(FakeEngine())
        load(queue)
        queue.generate(request.copy(tools = emptyList())).toList()
        var seen: Boolean? = null
        queue.onBeforeModelSwap = { seen = queue.holdsNoToolPrefix() }

        load(queue, "/models/b.gguf")

        assertEquals(true, seen)
        assertFalse("the new model's cache is unknown", queue.holdsNoToolPrefix())
    }

    /** TEN-196: a restored no-tools session belongs to the outgoing model, not the next one. */
    @Test
    fun aSwapForgetsThatTheRestoredSessionHeldNoToolPrefix() = inTest {
        val queue = InferenceQueue(FakeEngine())
        val info = load(queue)
        queue.restoreSession("/tmp/session", info, withoutTools = true)
        assertTrue(queue.holdsNoToolPrefix())

        load(queue, "/models/b.gguf")

        assertFalse(queue.holdsNoToolPrefix())
    }

    /** TEN-196: the second restore replaces the whole cache, the first one's flag goes with it. */
    @Test
    fun aRestoreOfAToolSessionForgetsThatThePreviousOneHeldNoToolPrefix() = inTest {
        val engine = FakeEngine(usedCells = 300)
        val queue = InferenceQueue(engine)
        val info = load(queue)
        val asked = request.messages + ChatMessage(MessageRole.User, "hi")

        queue.restoreSession("/tmp/session", info, withoutTools = true)
        queue.restoreSession("/tmp/session", info)
        queue.generate(request.copy(messages = asked)).toList()

        assertEquals(listOf("decode"), engine.events)
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
