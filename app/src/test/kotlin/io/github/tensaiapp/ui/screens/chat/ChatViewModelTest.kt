package io.github.tensaiapp.ui.screens.chat

import android.content.ContextWrapper
import io.github.tensaiapp.agent.AgentRuntime
import io.github.tensaiapp.agent.ToolCallOutcome
import io.github.tensaiapp.agent.ToolCallStatus
import io.github.tensaiapp.agent.Tool
import io.github.tensaiapp.agent.ToolResult
import io.github.tensaiapp.agent.ToolTier
import io.github.tensaiapp.agent.TierGrants
import io.github.tensaiapp.agent.BasePrompt
import io.github.tensaiapp.agent.CalculateTool
import io.github.tensaiapp.agent.DateTimeTool
import io.github.tensaiapp.agent.SystemPrompt
import io.github.tensaiapp.agent.ToolRegistry
import io.github.tensaiapp.agent.AskUserGate
import io.github.tensaiapp.agent.AskUserTool
import io.github.tensaiapp.agent.UiConfirmationGate
import io.github.tensaiapp.data.chat.ChatDao
import io.github.tensaiapp.data.chat.ChatRepository
import io.github.tensaiapp.data.chat.ConversationEntity
import io.github.tensaiapp.data.chat.MessageEntity
import io.github.tensaiapp.data.chat.ToolActivityRow
import io.github.tensaiapp.data.chat.ToolCallEntity
import io.github.tensaiapp.data.chat.ToolCallsJson
import io.github.tensaiapp.data.chat.ThinkingStreamParser
import io.github.tensaiapp.data.memory.FakeMemoryDao
import io.github.tensaiapp.data.memory.MemoryFactEntity
import io.github.tensaiapp.data.memory.MemoryContext
import io.github.tensaiapp.data.memory.MemoryPrompt
import io.github.tensaiapp.data.models.ModelDao
import io.github.tensaiapp.data.models.ModelEntity
import io.github.tensaiapp.data.models.ModelRepository
import io.github.tensaiapp.data.session.SessionDao
import io.github.tensaiapp.data.session.SessionEntity
import io.github.tensaiapp.data.session.SessionRepository
import io.github.tensaiapp.engine.ChatMessage
import io.github.tensaiapp.engine.DecodeEvent
import io.github.tensaiapp.engine.DecodeRequest
import io.github.tensaiapp.engine.DecodeStats
import io.github.tensaiapp.engine.GgufInfo
import io.github.tensaiapp.engine.InferenceException
import io.github.tensaiapp.engine.LlamaKtEngine
import io.github.tensaiapp.engine.LoadedModel
import io.github.tensaiapp.engine.LoadedModelInfo
import io.github.tensaiapp.engine.MessageRole
import io.github.tensaiapp.engine.ToolCall
import io.github.tensaiapp.engine.ModelLoadConfig
import io.github.tensaiapp.engine.SamplingConfig
import io.github.tensaiapp.engine.StopReason
import io.github.tensaiapp.engine.PrefixCache
import io.github.tensaiapp.engine.WarmUpPrompt
import io.github.tensaiapp.runtime.ActiveSessionTracker
import io.github.tensaiapp.runtime.ChatTurns
import io.github.tensaiapp.runtime.AppForegroundTracker
import io.github.tensaiapp.runtime.InferenceQueue
import io.github.tensaiapp.runtime.PartTokenCache
import io.github.tensaiapp.runtime.QueueState
import io.github.tensaiapp.runtime.residentModelId
import io.github.tensaiapp.ui.tools.ToolCallState
import io.github.tensaiapp.ui.TechnicalJargon
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Fast, JVM-only unit tests for [ChatViewModel]'s TEN-62 conversation-switch handling — a
 * [FakeLlamaKtEngine] stands in for the native engine (no GGUF/device needed, same idea as
 * `InferenceQueueTest`'s fakes), [FakeChatDao]/[FakeModelDao]/[FakeSessionDao] stand in for Room,
 * and [ModelRepository] is built against a bare `ContextWrapper(null)` — safe here because
 * `storageRoot` is passed explicitly, so `ModelRepository` never actually touches the `Context` it
 * holds (see its KDoc's `storageRoot` parameter). See `ChatViewModelTest` under `androidTest` for
 * the same class exercised against a real engine/model/device for [send]/[stop]'s own behavior.
 */
class ChatViewModelTest {

    private lateinit var storageRoot: File
    private lateinit var chatDao: FakeChatDao
    private lateinit var modelDao: FakeModelDao
    private lateinit var sessionDao: FakeSessionDao
    private lateinit var engine: FakeLlamaKtEngine
    private lateinit var inferenceQueue: InferenceQueue
    private lateinit var chatRepository: ChatRepository
    private lateinit var modelRepository: ModelRepository
    private lateinit var sessionRepository: SessionRepository
    private var agentRuntime = AgentRuntime(ToolRegistry())
    private var confirmationGate = UiConfirmationGate()
    private var askUserGate = AskUserGate()
    private lateinit var memoryDao: FakeMemoryDao
    private var memoryEnabled = true
    private lateinit var partTokens: PartTokenCache
    private lateinit var chatTurns: ChatTurns

    /** The tiers [toolGrants] says are granted, what a registry built on [grants] and the view models read (TEN-205). */
    private val grantedTiers = MutableStateFlow(setOf(ToolTier.Local))
    private val grants = TierGrants { grantedTiers.value }

    /** The user's instructions for every chat, as Settings would store them. */
    private val globalInstructions = MutableStateFlow("")

    /** What the view model logged, in order: the JVM has no logcat. */
    private val logLines = mutableListOf<String>()

    /**
     * The system message builder of every view model built here. Without the base prompt by default: the tests
     * written before it compare requests message by message, and the ones about it turn it on.
     */
    private var systemPrompts = SystemPrompt(base = false)

    /**
     * The job every [ChatViewModel] built here launches under — and the only thing that launches
     * under it. That exclusivity is the whole point of it: see [awaitGeneration].
     */
    private lateinit var viewModelJob: Job

    @Before
    fun setUp() {
        storageRoot = Files.createTempDirectory("tensai-chatviewmodel-test").toFile()
        viewModelJob = Job()
        chatDao = FakeChatDao()
        modelDao = FakeModelDao()
        sessionDao = FakeSessionDao()
        engine = FakeLlamaKtEngine()
        inferenceQueue = InferenceQueue(engine)
        memoryDao = FakeMemoryDao()
        memoryEnabled = true
        partTokens = PartTokenCache()
        chatTurns = ChatTurns()
        grantedTiers.value = setOf(ToolTier.Local)
        chatRepository = ChatRepository(chatDao, now = fixedClock::millis)
        modelRepository = ModelRepository(
            context = ContextWrapper(null),
            modelDao = modelDao,
            readMetadata = { throw UnsupportedOperationException("not needed by these tests") },
            kvShapeVersion = { throw UnsupportedOperationException("not needed by these tests") },
            // Wired exactly as `RuntimeGraph` wires it — see `residentModelId` (TEN-76, TEN-80).
            currentLoadedModelId = { inferenceQueue.residentModelId },
            storageRoot = storageRoot,
        )
        sessionRepository = SessionRepository(
            dao = sessionDao,
            storageRoot = storageRoot,
            currentTokenCount = { inferenceQueue.currentKvTokenCount() },
        )
        // ActiveSessionTracker is process-wide, mutable state — clear it so no leftover value from
        // a previous test (or from ChatViewModel's real usage elsewhere) leaks into this one.
        ActiveSessionTracker.clear()
        // Process-wide too, and a warm-up (TEN-88) only starts with the user in the app.
        AppForegroundTracker.onActivityStarted()
    }

    @After
    fun tearDown() {
        // Cancelled rather than joined: anything still running is dispatched on the event loop of
        // the `runBlocking` that has just returned, so nothing is left to resume it and a join here
        // would wait for good. Every test that has something to wait for has already waited for it
        // — see [awaitGeneration].
        viewModelJob.cancel()
        storageRoot.deleteRecursively()
        AppForegroundTracker.onActivityStopped()
        ActiveSessionTracker.clear()
    }

    /**
     * Launches on the test's own thread — [runBlocking]'s event loop, which is what keeps the
     * in-memory fakes below confined to one thread and free of locking — but under [viewModelJob]
     * rather than the receiver's job, so [awaitGeneration] has something to join that holds the
     * generations and nothing else.
     */
    private fun CoroutineScope.newViewModel(conversationId: String?) = ChatViewModel(
        conversationId = conversationId,
        chatRepository = chatRepository,
        inferenceQueue = inferenceQueue,
        modelRepository = modelRepository,
        sessionRepository = sessionRepository,
        scope = CoroutineScope(this.coroutineContext + viewModelJob),
        agentRuntime = agentRuntime,
        confirmationGate = confirmationGate,
        askUserGate = askUserGate,
        memoryPrompt = MemoryPrompt(memoryDao, enabled = { memoryEnabled }),
        partTokens = partTokens,
        toolGrants = grantedTiers,
        chatTurns = chatTurns,
        globalInstructions = globalInstructions,
        systemPrompts = systemPrompts,
        log = { logLines += it },
    )

    private suspend fun loadFakeModel(supportsThinking: Boolean = false, supportsTools: Boolean = false) {
        importModel(supportsThinking = supportsThinking, supportsTools = supportsTools)
        loadImportedModel(MODEL_ID)
    }

    /**
     * Inserts one imported model's row. Defaults to the single model the rest of these tests use;
     * the TEN-80 tests import a second one that deliberately shares [MODEL_NAME], which is what two
     * quantisations of the same model look like — the GGUF header names the model, not the file.
     */
    private suspend fun importModel(
        id: String = MODEL_ID,
        fileName: String = "fake.gguf",
        importedAt: Long = 0L,
        supportsThinking: Boolean = false,
        supportsTools: Boolean = false,
    ) {
        modelDao.insert(
            ModelEntity(
                id = id,
                displayName = MODEL_NAME,
                fileName = fileName,
                filePath = "/models/$fileName",
                sizeBytes = 1L,
                architecture = "test",
                quantLabel = "F16",
                paramCount = 1L,
                contextLength = NCTX.toLong(),
                embeddingLength = 1L,
                blockCount = 1L,
                supportsThinking = supportsThinking,
                supportsTools = supportsTools,
                importedAt = importedAt,
            ),
        )
    }

    /** Loads [id]'s file the way `LoadModelViewModel` does — carrying the row's id on the config. */
    private suspend fun loadImportedModel(id: String) {
        val model = modelDao.getById(id) ?: error("no imported model $id")
        inferenceQueue.loadModel(ModelLoadConfig(path = model.filePath, modelId = model.id, nCtx = NCTX))
    }

    /**
     * Waits for whatever [ChatViewModel] launched — a [ChatViewModel.send], a
     * [ChatViewModel.regenerate] — to be over.
     *
     * Both are fire-and-forget: they start a coroutine and return, so an assertion written straight
     * after one is a race between the test thread and a generation, settled by nothing but which of
     * the two the machine happens to run first. Polling for the outcome under a budget does not
     * remove that race — it gives the generation a head start and calls a slow machine a bug.
     *
     * [viewModelJob] has no children but those coroutines, and each of them ends only once
     * `streamInto`'s `finally` has run to the end: the last (`NonCancellable`) write of the row,
     * with its stats and its [io.github.tensaiapp.engine.StopReason], and then
     * [ChatViewModel.error]. So joining them is the generation being *finished*, including the
     * parts that happen after [InferenceQueue.state] has already dropped back to
     * [QueueState.ModelReady] — and every assertion that follows one can be a single read.
     */
    private suspend fun awaitGeneration() {
        // Until none are left, not one snapshot: a long reply launches its warm-up (TEN-88) as a new child
        // from inside the generation being joined, after the snapshot was taken.
        while (true) {
            val running = viewModelJob.children.toList()
            if (running.isEmpty()) break
            running.forEach { it.join() }
        }
    }

    /**
     * [awaitGeneration], then [conversationId]'s messages read once.
     *
     * [expectedCount] is asserted rather than waited for, and that is the whole difference from the
     * loop this replaces: if the join were not a real happens-before, this would fail on every run
     * instead of one in two.
     *
     * There is no "settled" predicate any more and none is needed. After the join the last row is
     * whatever the generation left behind for good — with stats if it finished, without if it
     * failed (TEN-52) — so the tests that used to pass a predicate of their own simply assert on it.
     */
    private suspend fun awaitAssistantReply(conversationId: String, expectedCount: Int): List<MessageEntity> {
        awaitGeneration()
        val messages = chatRepository.observeMessages(conversationId).first()
        assertEquals("the generation is over, so this is the whole conversation", expectedCount, messages.size)
        return messages
    }

    /**
     * Waits for a reply to *start*: [conversationId] holding the user's message and an assistant row
     * with something in it.
     *
     * The one wait here that cannot be a join, because its two callers hold the decode open
     * ([FakeLlamaKtEngine.hangBeforeTerminal]) precisely so they can stop it mid-stream — the
     * coroutine to join is the one that must not have finished. A suspension on the message flow all
     * the same, not a poll: it resumes on the write that persists the first token, and the first
     * token is always persisted (the throttle window starts open). The budget bounds an event that
     * could genuinely never arrive — a decode that emits nothing at all — which is what a timeout is
     * for, rather than a stand-in for synchronisation.
     */
    private suspend fun awaitStreamingReply(conversationId: String) {
        withTimeout(5_000) {
            chatRepository.observeMessages(conversationId).first { it.size >= 2 && it.last().content.isNotEmpty() }
        }
    }

    /**
     * Waits for the queue to report the generation started — a suspension on its own [StateFlow]
     * where this was a poll. Not a join either, and for the same reason as [awaitStreamingReply]:
     * the tests that need it are holding the decode open on purpose.
     */
    private suspend fun awaitGenerating() {
        withTimeout(5_000) { inferenceQueue.state.first { it is QueueState.Generating } }
    }

    @Test
    fun switchingToADifferentConversationAgainstTheSameModelSavesOutgoingAndAttemptsToRestoreIncoming() = runBlocking {
        loadFakeModel()
        val conversationA = chatRepository.createConversation(modelId = null)
        val conversationB = chatRepository.createConversation(modelId = null)

        val viewModelA = newViewModel(conversationA)
        viewModelA.send("hi from A")
        awaitAssistantReply(conversationA, expectedCount = 2)

        assertNull("no switch has happened yet, nothing should be saved", sessionDao.getByConversation(conversationA))

        val viewModelB = newViewModel(conversationB)
        viewModelB.send("hi from B")
        awaitAssistantReply(conversationB, expectedCount = 2)

        assertTrue(
            "switching away from A while its cache was live must save A's session",
            sessionDao.getByConversation(conversationA) != null,
        )
        assertEquals(
            "reportActiveSession must move the tracker to B once B's turn runs",
            conversationB,
            ActiveSessionTracker.current.value?.first,
        )
    }

    /**
     * TEN-80, the defect itself. Two quantisations of one model — a Q4 and a Q8 imported to compare
     * them — are two files, two rows, and one GGUF display name. `resolveSessionKey` used to turn
     * the loaded model back into a row by that name, against a list ordered `importedAt DESC`, so
     * loading the *older* one produced the *newer* one's id and every session written under it was
     * stamped with a model that was never loaded.
     */
    @Test
    fun theSessionKeyCarriesTheLoadedQuantisationsIdNotTheNewestRowSharingItsName() = runBlocking {
        importModel(id = OLDER_MODEL_ID, fileName = "q4.gguf", importedAt = 0L)
        importModel(id = MODEL_ID, fileName = "q8.gguf", importedAt = 1L)
        loadImportedModel(OLDER_MODEL_ID)
        val conversationA = chatRepository.createConversation(modelId = null)
        val conversationB = chatRepository.createConversation(modelId = null)

        newViewModel(conversationA).send("hi from A")
        awaitAssistantReply(conversationA, expectedCount = 2)
        assertEquals(
            "the tracked session must name the quantisation that is loaded",
            OLDER_MODEL_ID,
            ActiveSessionTracker.current.value?.second?.modelId,
        )

        // Switching away is what commits that key to disk (TEN-62), which is where a wrong id
        // stops being a wrong number and becomes a session another model can be handed.
        newViewModel(conversationB).send("hi from B")
        awaitAssistantReply(conversationB, expectedCount = 2)

        assertEquals(
            "the saved session must be stamped with the loaded model's own id",
            OLDER_MODEL_ID,
            sessionDao.getByConversation(conversationA)?.modelId,
        )
    }

    /**
     * TEN-80's consequence, and the reason the id matters more than tidiness:
     * [SessionRepository.restore]'s mismatch check is the only thing standing between a session and
     * a *different* model's `loadSession`, and with the wrong id written down it had nothing left
     * to catch this with. `nCtx` and `kvCacheType` are the rest of the check, and two quantisations
     * of one model planned by the same deterministic [io.github.tensaiapp.runtime.LoadPolicy]
     * against the same device agree on both — as they do here.
     */
    @Test
    fun aSessionSavedUnderOneQuantisationIsNeverRestoredOntoTheOther() = runBlocking {
        importModel(id = OLDER_MODEL_ID, fileName = "q4.gguf", importedAt = 0L)
        importModel(id = MODEL_ID, fileName = "q8.gguf", importedAt = 1L)
        loadImportedModel(OLDER_MODEL_ID)
        val conversationA = chatRepository.createConversation(modelId = null)
        val conversationB = chatRepository.createConversation(modelId = null)

        newViewModel(conversationA).send("hi from A")
        awaitAssistantReply(conversationA, expectedCount = 2)
        newViewModel(conversationB).send("hi from B")
        awaitAssistantReply(conversationB, expectedCount = 2)
        assertTrue("precondition: A's session must be on disk to be restorable at all", sessionDao.getByConversation(conversationA) != null)

        // The swap the user makes to compare the two: same model, other file. The queue comes back
        // on an empty cache, so A's next turn takes the cold-start restore path.
        loadImportedModel(MODEL_ID)
        newViewModel(conversationA).send("back in A")
        awaitAssistantReply(conversationA, expectedCount = 4)

        assertEquals(
            "the other quantisation's KV state was handed to this model",
            0,
            engine.loadSessionCalls,
        )
        assertTrue(
            "a mismatch is not a broken session — the row must survive for the model it belongs to",
            sessionDao.getByConversation(conversationA) != null,
        )
    }

    @Test
    fun aSecondTurnInTheSameConversationTriggersNeitherASaveNorARestore() = runBlocking {
        loadFakeModel()
        val conversationA = chatRepository.createConversation(modelId = null)
        val viewModelA = newViewModel(conversationA)

        viewModelA.send("first turn")
        awaitAssistantReply(conversationA, expectedCount = 2)
        viewModelA.send("second turn")
        awaitAssistantReply(conversationA, expectedCount = 4)

        assertNull(
            "two turns in the same conversation must never trigger a save — the cache is already this conversation's own",
            sessionDao.getByConversation(conversationA),
        )
    }

    @Test
    fun aFailedSaveOfTheOutgoingConversationSkipsTheRestoreAndFallsBackToANormalPrefill() = runBlocking {
        loadFakeModel()
        val conversationA = chatRepository.createConversation(modelId = null)
        val conversationB = chatRepository.createConversation(modelId = null)

        val viewModelA = newViewModel(conversationA)
        viewModelA.send("hi from A")
        awaitAssistantReply(conversationA, expectedCount = 2)

        engine.saveShouldFail = true
        val viewModelB = newViewModel(conversationB)
        viewModelB.send("hi from B")
        val messagesB = awaitAssistantReply(conversationB, expectedCount = 2)

        assertNull(
            "a failed save must leave no session on disk for the outgoing conversation — TEN-34's guarantee",
            sessionDao.getByConversation(conversationA),
        )
        assertTrue(
            "generation for the incoming conversation must still complete despite the failed save",
            messagesB.last().content.isNotBlank(),
        )
    }

    /**
     * TEN-47. The raw stream carries `<|channel|>` markers the `<think>` tag parser cannot make
     * sense of — every character of it would have landed in `content`, markers included, which is
     * exactly what the bug looked like on device. The engine's own split must win instead.
     */
    @Test
    fun engineParsedEventsFeedTheThinkingBlockRatherThanTheRawChannelMarkers() = runBlocking {
        loadFakeModel()
        engine.scriptedEvents = listOf(
            DecodeEvent.Token("<|channel|>thought "),
            DecodeEvent.Token("let me count"),
            DecodeEvent.Parsed(content = "", reasoning = "let me count"),
            DecodeEvent.Token("<|channel|>final "),
            DecodeEvent.Token("four"),
            DecodeEvent.Parsed(content = "four", reasoning = "let me count"),
        )
        val conversation = chatRepository.createConversation(modelId = null)

        newViewModel(conversation).send("2+2?")
        val reply = awaitAssistantReply(conversation, expectedCount = 2).last()

        assertEquals("four", reply.content)
        assertEquals("let me count", reply.thinking)
    }

    /**
     * TEN-47's other half: an engine that reports no split at all (no chat parser for this
     * template, or a reply that didn't match it) must leave `ThinkingStreamParser`'s `<think>`
     * handling in charge, exactly as before.
     */
    @Test
    fun withoutAnyParsedEventTheThinkTagFallbackStillSplitsTheReply() = runBlocking {
        loadFakeModel()
        engine.scriptedEvents = listOf(
            DecodeEvent.Token("<think>step "),
            DecodeEvent.Token("one</think>"),
            DecodeEvent.Token("the answer"),
        )
        val conversation = chatRepository.createConversation(modelId = null)

        newViewModel(conversation).send("hi")
        val reply = awaitAssistantReply(conversation, expectedCount = 2).last()

        assertEquals("the answer", reply.content)
        assertEquals("step one", reply.thinking)
    }

    /**
     * TEN-52, first of the three outcomes: a decode that reached [DecodeEvent.Done] records the
     * engine's own [StopReason], and is the only one of the three that has stats.
     */
    @Test
    fun aGenerationThatFinishesRecordsItsStopReasonAlongsideItsStats() = runBlocking {
        loadFakeModel()
        val conversation = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversation)

        viewModel.send("hi")
        val reply = awaitAssistantReply(conversation, expectedCount = 2).last()

        assertEquals("hi", reply.content)
        assertEquals(StopReason.Complete.name, reply.stopReason)
        assertEquals(TOKENS_PER_TURN, reply.tokensGenerated)

        val ui = viewModel.messages.first().last()
        assertEquals("a reply that finished carries no ending chip at all", ReplyEnding.Normal, ui.ending)
    }

    /**
     * TEN-52's second outcome, as TEN-39 changed it: the user tapped stop. The partial reply stays
     * — that part is unchanged — but the row now records [StopReason.Cancelled] where it used to
     * record nothing.
     *
     * **Behaviour change, deliberate.** This test previously asserted `stopReason` was `null` and
     * that the UI showed nothing. `null` was indistinguishable from a reply still streaming and
     * from a complete short answer read back later, so a stopped reply was silently identical to
     * one the model had finished. It must still not read as a *failure*, and that is what the last
     * assertion pins.
     */
    @Test
    fun aGenerationTheUserStopsKeepsItsPartialReplyAndRecordsThatItWasStopped() = runBlocking {
        loadFakeModel()
        engine.scriptedEvents = listOf(DecodeEvent.Token("half an answer"))
        engine.hangBeforeTerminal = true
        val conversation = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversation)

        viewModel.send("hi")
        // The first token is always persisted (the throttle window starts open), so this lands
        // once the reply is streaming and before any terminal event can arrive.
        awaitStreamingReply(conversation)
        viewModel.stop()
        // Not the queue leaving `Generating`: it does that in `generate`'s own `finally`, which runs
        // *before* the collector's — the write that persists this partial reply and its
        // `StopReason.Cancelled` is still to come. Joining the generation is what waits for that.
        awaitGeneration()

        val reply = chatRepository.observeMessages(conversation).first().last()
        assertEquals("half an answer", reply.content)
        assertNull("a stopped reply never finished, so it has no stats", reply.tokensGenerated)
        assertEquals(
            "stopping is recorded, not inferred from three nulls — see StopReason.Cancelled",
            StopReason.Cancelled.name,
            reply.stopReason,
        )

        val ui = viewModel.messages.first().last()
        assertEquals(
            "stopping on purpose is its own ending, and must never be the failure one",
            ReplyEnding.Stopped,
            ui.ending,
        )
    }

    /**
     * The reason [ChatViewModel.stop]'s flag is not enough on its own (TEN-39): a stop landing just
     * after the engine's own terminal event cancels a generation that has already finished. The
     * reply is complete and reads as complete, so it must keep the engine's stop reason and its
     * stats rather than be relabelled "stopped" — a label the reader could catch out by simply
     * reading the sentence it ends.
     */
    @Test
    fun stoppingAfterTheReplyAlreadyFinishedLeavesItAFinishedReply() = runBlocking {
        loadFakeModel()
        val conversation = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversation)

        viewModel.send("hi")
        val reply = awaitAssistantReply(conversation, expectedCount = 2).last()
        // The generation is over by now; this is the late tap.
        viewModel.stop()

        assertEquals(StopReason.Complete.name, reply.stopReason)
        assertEquals(TOKENS_PER_TURN, reply.tokensGenerated)
        assertEquals(ReplyEnding.Normal, viewModel.messages.first().last().ending)
    }

    /**
     * TEN-79's third defect, and the reason TEN-39's fix did not hold: the queue is app-wide, so
     * whichever screen the stop button is pressed on, the generation it cancels is the one that is
     * running. Conversation A is streaming, the user opens B from the drawer, B's screen offers a
     * stop (there *is* a generation), and pressing it cuts A.
     *
     * With "the user stopped it" held as a flag on the `ChatViewModel`, that flag was B's while the
     * cancellation was A's: A's row was persisted truncated with no stats and no reason — which is
     * exactly what a complete short answer looks like on re-read, and what
     * [ChatRepository.toEngineMessages] replays into every later prompt.
     */
    @Test
    fun stoppingFromAnotherConversationsScreenLabelsTheReplyItActuallyCuts() = runBlocking {
        loadFakeModel()
        engine.scriptedEvents = listOf(DecodeEvent.Token("half an answer"))
        engine.hangBeforeTerminal = true
        val conversationA = chatRepository.createConversation(modelId = null)
        val conversationB = chatRepository.createConversation(modelId = null)
        val viewModelA = newViewModel(conversationA)
        val viewModelB = newViewModel(conversationB)

        viewModelA.send("hi")
        awaitStreamingReply(conversationA)
        // The user is looking at B and presses the only button on offer.
        viewModelB.stop()
        // A's generation, joined from B's screen — same reason as the test above.
        awaitGeneration()

        val reply = chatRepository.observeMessages(conversationA).first().last()
        assertEquals("half an answer", reply.content)
        assertEquals(
            "the row that was cut is the row that carries the reason, whichever screen the button was on",
            StopReason.Cancelled.name,
            reply.stopReason,
        )
        assertEquals(ReplyEnding.Stopped, viewModelA.messages.first().last().ending)
    }

    /**
     * The other half of the same defect: B should not have been offering that button at all.
     *
     * `ChatScreen` derived its stop affordance from [InferenceQueue.state] alone, which says
     * "generating" on every conversation's screen at once. [ChatViewModel.canStop] narrows it to
     * the conversation the generation belongs to, so B shows a busy pill rather than a control over
     * a reply the user cannot see.
     */
    @Test
    fun theStopButtonIsOnlyOfferedOnTheConversationThatIsGenerating() = runBlocking {
        loadFakeModel()
        engine.hangBeforeTerminal = true
        val conversationA = chatRepository.createConversation(modelId = null)
        val conversationB = chatRepository.createConversation(modelId = null)
        val viewModelA = newViewModel(conversationA)
        val viewModelB = newViewModel(conversationB)

        viewModelA.send("hi")
        awaitGenerating()

        assertTrue("A is generating, and A is where the stop belongs", viewModelA.canStop.first())
        assertFalse(
            "B has nothing of its own to stop — offering one here cuts A instead",
            viewModelB.canStop.first(),
        )

        viewModelA.stop()
    }

    /**
     * A `ChatViewModel` rebuilt mid-reply — `ChatScreen` `remember`s one per conversation id, so
     * navigating to another screen and back makes a new one while the generation, which runs on the
     * application scope, carries on — must still offer the stop for its own conversation. That is
     * what [ActiveSessionTracker] covers and an instance-scoped flag cannot.
     */
    @Test
    fun aScreenReopenedMidReplyStillOffersTheStop() = runBlocking {
        loadFakeModel()
        engine.hangBeforeTerminal = true
        val conversation = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversation)

        viewModel.send("hi")
        awaitGenerating()

        val reopened = newViewModel(conversation)
        assertTrue("the reply on screen is still this conversation's", reopened.canStop.first())

        viewModel.stop()
    }

    /**
     * TEN-79's second defect, end to end. TEN-65 clips `nPredict` to the room the context window
     * has left; when the reply then spends all of it, the engine used to report
     * [StopReason.ResponseBudgetExhausted] — "you hit your limit". The user raises max-tokens,
     * which does not enter into `available` at all, and the next reply stops at the same word.
     *
     * The row is marked for what happened to it, and the banner names the conversation as the
     * problem — the reply is on screen, so nothing else would.
     */
    @Test
    fun aReplyCutOffByTheContextWindowSaysSoRatherThanBlamingTheTokenLimit() = runBlocking {
        loadFakeModel()
        engine.scriptedEvents = listOf(
            DecodeEvent.Token("as far as the window would let me g"),
            DecodeEvent.Done(
                DecodeStats(
                    tokensGenerated = TOKENS_PER_TURN,
                    tokPerSec = 1f,
                    ttftMs = 1L,
                    stopReason = StopReason.ContextBudgetClipped,
                ),
            ),
        )
        val conversation = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversation)

        viewModel.send("hi")
        val reply = awaitAssistantReply(conversation, expectedCount = 2).last()

        assertEquals(StopReason.ContextBudgetClipped.name, reply.stopReason)
        assertEquals(
            "not the max-tokens truncation — that would send the user to a setting that cannot help",
            TruncationReason.Context,
            viewModel.messages.first().last().stats!!.truncation,
        )
        assertEquals(
            "only the banner can say the window is what ran out, and what to do about it",
            ChatError.ContextWindowFull,
            viewModel.error.value,
        )
    }

    /**
     * TEN-52's actual bug: a [DecodeEvent.Error] arriving *after* the first token used to be
     * swallowed whole. The partial reply was persisted with no stats and no stop reason, which is
     * byte for byte what a deliberate stop looks like, so a broken generation was presented as a
     * short answer the user had asked to cut short.
     */
    @Test
    fun anErrorAfterTheFirstTokenKeepsThePartialReplyAndRecordsTheFailure() = runBlocking {
        loadFakeModel()
        engine.scriptedEvents = listOf(
            DecodeEvent.Token("the answer is "),
            DecodeEvent.Error(RuntimeException("llama_decode failed")),
        )
        val conversation = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversation)

        viewModel.send("2+2?")
        val reply = awaitAssistantReply(conversation, expectedCount = 2).last()

        assertEquals("the partial reply must survive the failure", "the answer is ", reply.content)
        assertEquals(StopReason.Failed.name, reply.stopReason)
        assertNull("a failed decode reports no stats", reply.tokensGenerated)

        val ui = viewModel.messages.first().last()
        assertEquals("the failure has to reach the UI, not just the row", ReplyEnding.Failed, ui.ending)
        assertNull("no stats chip for a decode that never finished", ui.stats)
        assertNull("a reply that survived needs no banner — its own chip says it", viewModel.error.value)
    }

    /**
     * An error *before* the first token leaves nothing worth keeping, so TEN-64 drops the turn —
     * and TEN-39 reports the failure as a [ChatError.GenerationFailed] banner instead.
     *
     * **Behaviour change, deliberate.** This test previously asserted the reply's content was
     * `"Error: model gone"` — the exception's own message, promoted to the body of a chat bubble
     * and persisted there forever. That is a stack-trace sentence in a message bubble, and it was
     * also replayed into every later prompt by `toEngineMessages` as if the assistant had said it.
     * The user still hears that the generation failed; they hear it in the app's words, once, from
     * something they can dismiss.
     */
    @Test
    fun anErrorBeforeTheFirstTokenKeepsNoTurnAndRaisesTheFailureBanner() = runBlocking {
        loadFakeModel()
        engine.scriptedEvents = listOf(DecodeEvent.Error(RuntimeException("model gone")))
        val conversation = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversation)

        viewModel.send("hi")
        awaitGeneration()

        assertEquals(ChatError.GenerationFailed, viewModel.error.value)
        val messages = chatRepository.observeMessages(conversation).first()
        assertEquals("the empty assistant turn is dropped, not filled in", 1, messages.size)
        assertEquals("user", messages.single().role)
    }

    /** TEN-113: the blank-prompt error (InferenceException) takes the same road as any early failure. */
    @Test
    fun aBlankPromptErrorKeepsNoTurnAndRaisesTheFailureBanner() = runBlocking {
        loadFakeModel()
        engine.scriptedEvents = listOf(
            DecodeEvent.Error(InferenceException("Chat template rendering failed: the formatted prompt is empty")),
        )
        val conversation = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversation)

        viewModel.send("hi")
        awaitGeneration()

        assertEquals(ChatError.GenerationFailed, viewModel.error.value)
        assertEquals(1, chatRepository.observeMessages(conversation).first().size)
    }
    /**
     * The same, for a reply that opened and closed an empty `<think>` block before failing: the
     * splitter reports an empty (not absent) reasoning, which is still nothing worth keeping by
     * [hasPersistableContent]'s own reckoning, so the turn goes and the banner comes up.
     *
     * **Behaviour change, deliberate** — same one as the test above, which this one mirrors through
     * the splitter rather than around it.
     */
    @Test
    fun anErrorAfterAnEmptyThinkBlockAlsoKeepsNoTurn() = runBlocking {
        loadFakeModel()
        engine.scriptedEvents = listOf(
            DecodeEvent.Token("<think></think>"),
            DecodeEvent.Error(RuntimeException("out of memory")),
        )
        val conversation = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversation)

        viewModel.send("hi")
        awaitGeneration()

        assertEquals(ChatError.GenerationFailed, viewModel.error.value)
        assertEquals(1, chatRepository.observeMessages(conversation).first().size)
    }

    /**
     * TEN-110: stop tapped during prefill, before any token. The row used to be deleted as an empty
     * turn, leaving the user's message alone: no bubble, no "stopped" chip, no Regenerate. It is kept
     * now, empty and recorded as Cancelled, so it reads exactly like a stop a token later.
     */
    @Test
    fun stoppingBeforeTheFirstTokenKeepsAnEmptyStoppedTurn() = runBlocking {
        loadFakeModel()
        engine.scriptedEvents = emptyList()
        engine.hangBeforeTerminal = true
        val conversation = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversation)

        viewModel.send("hi")
        awaitGenerating()
        viewModel.stop()
        awaitGeneration()

        val messages = chatRepository.observeMessages(conversation).first()
        assertEquals(listOf("user", "assistant"), messages.map { it.role })
        val reply = messages.last()
        assertEquals("", reply.content)
        assertNull(reply.tokensGenerated)
        assertEquals(StopReason.Cancelled.name, reply.stopReason)
        assertEquals(ReplyEnding.Stopped, viewModel.messages.first().last().ending)
        assertNull("a stop is not a failure, so no banner", viewModel.error.value)
    }

    /** Regenerate on the empty stopped turn replaces it, as it does a partial reply. */
    @Test
    fun regeneratingAnEmptyStoppedTurnReplacesItWithARealReply() = runBlocking {
        loadFakeModel()
        engine.scriptedEvents = emptyList()
        engine.hangBeforeTerminal = true
        val conversation = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversation)
        viewModel.send("hi")
        awaitGenerating()
        viewModel.stop()
        awaitGeneration()
        val stoppedId = chatRepository.observeMessages(conversation).first().last().id

        engine.scriptedEvents = null
        engine.hangBeforeTerminal = false
        viewModel.regenerate()
        val messages = awaitAssistantReply(conversation, expectedCount = 2)

        val reply = messages.last()
        assertNotEquals("the stopped row is gone, not reused", stoppedId, reply.id)
        assertEquals("hi", reply.content)
        assertEquals(StopReason.Complete.name, reply.stopReason)
        assertEquals(listOf("hi"), engine.lastRequest!!.messages.map { it.content })
    }

    /**
     * The empty stopped turn is on screen but is not something the assistant said: the next prompt
     * has to be built as if it were not there.
     */
    @Test
    fun anEmptyStoppedTurnIsNotInTheNextPrompt() = runBlocking {
        loadFakeModel()
        engine.scriptedEvents = emptyList()
        engine.hangBeforeTerminal = true
        val conversation = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversation)
        viewModel.send("first")
        awaitGenerating()
        viewModel.stop()
        awaitGeneration()

        engine.scriptedEvents = null
        engine.hangBeforeTerminal = false
        viewModel.send("second")
        awaitAssistantReply(conversation, expectedCount = 4)

        val prompt = engine.lastRequest!!.messages
        assertEquals("one user turn, so strict templates see alternating roles", listOf("first\n\nsecond"), prompt.map { it.content })
        assertEquals(listOf(MessageRole.User), prompt.map { it.role })
    }

    /** A row a killed process left behind (empty, no stop reason) between two user turns. */
    @Test
    fun anOrphanEmptyAssistantRowIsMergedAwayLikeAStoppedOne() = runBlocking {
        loadFakeModel()
        val conversation = chatRepository.createConversation(modelId = null)
        chatRepository.appendUserMessage(conversation, "first")
        chatRepository.startAssistantMessage(conversation)
        val viewModel = newViewModel(conversation)

        viewModel.send("second")
        awaitAssistantReply(conversation, expectedCount = 4)

        assertEquals(listOf("first\n\nsecond"), engine.lastRequest!!.messages.map { it.content })
    }

    /** Normal alternation, system prompt first, is left exactly as it was. */
    @Test
    fun alternatingTurnsAreNotMerged() {
        fun row(id: String, role: String, content: String) = MessageEntity(
            id = id, conversationId = "c", role = role, content = content, thinking = null,
            toolCallsJson = null, tokensGenerated = null, tokPerSec = null, ttftMs = null,
            stopReason = null, createdAt = 0L,
        )
        val out = chatRepository.toEngineMessages(
            listOf(row("1", "user", "q1"), row("2", "assistant", "a1"), row("3", "user", "q2")),
            systemPrompt = "sys",
        )
        assertEquals(listOf("sys", "q1", "a1", "q2"), out.map { it.content })
        assertEquals(
            listOf(MessageRole.System, MessageRole.User, MessageRole.Assistant, MessageRole.User),
            out.map { it.role },
        )
    }

    /** The repository chokepoint itself, which the warm-up shares with a real turn. */
    @Test
    fun toEngineMessagesDropsEveryEmptyAssistantTurnEvenAThinkingOnlyOne() {
        fun row(id: String, role: String, content: String, thinking: String? = null) = MessageEntity(
            id = id, conversationId = "c", role = role, content = content, thinking = thinking,
            toolCallsJson = null, tokensGenerated = null, tokPerSec = null, ttftMs = null,
            stopReason = null, createdAt = 0L,
        )
        val out = chatRepository.toEngineMessages(
            listOf(
                row("1", "user", "q"),
                row("2", "assistant", ""),
                row("3", "assistant", "", thinking = "hmm"),
                row("4", "assistant", "a"),
            ),
            systemPrompt = null,
        )
        assertEquals("both empty rows go, the thinking one too, and the two users around them merge", listOf("q", "a"), out.map { it.content })
        assertEquals(listOf(MessageRole.User, MessageRole.Assistant), out.map { it.role })
    }

    @Test
    fun aThinkingOnlyAssistantTurnBetweenTwoUsersMergesThem() {
        fun row(id: String, role: String, content: String, thinking: String? = null) = MessageEntity(
            id = id, conversationId = "c", role = role, content = content, thinking = thinking,
            toolCallsJson = null, tokensGenerated = null, tokPerSec = null, ttftMs = null,
            stopReason = null, createdAt = 0L,
        )
        val out = chatRepository.toEngineMessages(
            listOf(row("1", "user", "q1"), row("2", "assistant", "", thinking = "hmm"), row("3", "user", "q2")),
            systemPrompt = null,
        )
        assertEquals(listOf("q1\n\nq2"), out.map { it.content })
    }

    @Test
    fun threeAdjacentUserTurnsMergeInOrder() {
        fun row(id: String, role: String, content: String) = MessageEntity(
            id = id, conversationId = "c", role = role, content = content, thinking = null,
            toolCallsJson = null, tokensGenerated = null, tokPerSec = null, ttftMs = null,
            stopReason = null, createdAt = 0L,
        )
        val out = chatRepository.toEngineMessages(
            listOf(
                row("1", "user", "a"), row("2", "assistant", ""), row("3", "user", "b"),
                row("4", "assistant", ""), row("5", "user", "c"),
            ),
            systemPrompt = null,
        )
        assertEquals(listOf("a\n\nb\n\nc"), out.map { it.content })
    }

    /**
     * TEN-84: an error banner describes one generation and has to die with it.
     *
     * Nothing cleared [ChatViewModel.error] except the user dismissing it by hand, so a failure
     * they ignored was still on screen — above a *later* reply that worked. That is worse than
     * untidy: the banner's Retry calls [ChatViewModel.regenerate], whose first act is to delete
     * the conversation's last assistant message. The stale banner's one control deletes the
     * successful reply that made it obsolete.
     */
    @Test
    fun aFailureBannerIsGoneOnceTheNextGenerationSucceeds() = runBlocking {
        loadFakeModel()
        engine.scriptedEvents = listOf(DecodeEvent.Error(RuntimeException("model gone")))
        val conversation = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversation)

        viewModel.send("hi")
        awaitGeneration()
        assertEquals(ChatError.GenerationFailed, viewModel.error.value)

        engine.scriptedEvents = null
        // Sent once, and that is an assertion now rather than a convenience. The loop this replaces
        // was there because the wait above returned while the failed generation's job was still
        // winding down — the banner is raised on that job's very last statement — so the first
        // `send` could land on `generationJob?.isActive == true` and be dropped on the floor. That
        // was the test compensating for its own wait, not for anything undecided in production:
        // `send` no-ops exactly while a generation is running, and [awaitGeneration] is that being
        // over. If it were ever dropped again, the count below says so instead of hiding it.
        viewModel.send("try again")
        val messages = awaitAssistantReply(conversation, expectedCount = 3)

        assertEquals("assistant", messages.last().role)
        assertEquals("hi", messages.last().content)
        assertNull(
            "the banner belonged to the generation that failed — this one succeeded, and its " +
                "Retry would have deleted this very reply",
            viewModel.error.value,
        )
    }

    /**
     * The same rule through the other door (TEN-84). [ChatViewModel.regenerate] is not only the
     * banner's own Retry — `MessageBubble` puts a regenerate button on the last assistant reply,
     * and that one does not call `clearError` first. A reply cut off by the context window leaves
     * a [ChatError.ContextWindowFull] banner *and* a bubble to regenerate from, so it is the one
     * path where a banner can outlive its generation without the user ever touching it.
     */
    @Test
    fun aContextWindowBannerIsGoneOnceARegeneratedReplySucceeds() = runBlocking {
        loadFakeModel()
        engine.scriptedEvents = listOf(
            DecodeEvent.Token("as far as the window would let me g"),
            DecodeEvent.Done(
                DecodeStats(
                    tokensGenerated = TOKENS_PER_TURN,
                    tokPerSec = 1f,
                    ttftMs = 1L,
                    stopReason = StopReason.ContextBudgetClipped,
                ),
            ),
        )
        val conversation = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversation)

        viewModel.send("hi")
        awaitAssistantReply(conversation, expectedCount = 2)
        assertEquals(ChatError.ContextWindowFull, viewModel.error.value)

        engine.scriptedEvents = null
        // Called once, for the reason the test above sends once — and the loop was covering a
        // second race of its own here: the clipped row is written with its stop reason *before*
        // [ChatViewModel.error] is raised, so the wait it used to follow could return with the
        // banner not yet up, and `regenerate` was then a no-op on a job still winding down.
        viewModel.regenerate()
        val messages = awaitAssistantReply(conversation, expectedCount = 2)

        assertEquals(
            "the redone reply replaces the clipped one rather than being appended to it",
            2,
            messages.size,
        )
        assertEquals(
            "and it is the redone one — the default script's \"hi\", not the clipped text",
            "hi",
            messages.last().content,
        )
        assertNull(
            "the window banner described the reply that was just thrown away",
            viewModel.error.value,
        )
    }

    /**
     * The acceptance criterion this ticket is most able to prove, taken through the whole chat
     * path: **no state the user can reach carries the exception's own words**.
     *
     * The cause below is a real-shaped JNI failure — a native function name, a return code, and a
     * stack frame. Before TEN-39 the first two of these assertions failed: `streamInto` wrote
     * `"Error: " + cause.message` into the message body whenever the decode produced nothing, so
     * every one of those tokens ended up on screen and in the database.
     *
     * Checked two ways. The [TechnicalJargon] sweep runs over the text a reader actually sees — the
     * message bodies and their reasoning — which is where the leak used to be. The
     * distinctive-token sweep then runs over the *whole* `ChatMessageUi` list and the surfaced
     * error, so a future field that starts carrying the throwable through fails this too, even
     * though nothing renders that field yet.
     */
    @Test
    fun noJargonFromAFailedDecodeReachesTheUserAnywhere() = runBlocking {
        loadFakeModel()
        val jargon = "llama_decode failed: rc=-1 at com.tensai.llamakt.LlamaEngine.decode(LlamaEngine.kt:214)"
        engine.scriptedEvents = listOf(
            DecodeEvent.Token("here is the "),
            DecodeEvent.Error(InferenceException(jargon)),
        )
        val conversation = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversation)

        viewModel.send("hi")
        val reply = awaitAssistantReply(conversation, expectedCount = 2).last()

        assertEquals("the partial reply survives, unchanged", "here is the ", reply.content)

        val messages = viewModel.messages.first()
        val readableText = messages.joinToString(" ") { it.content + " " + it.thinking.orEmpty() }
        assertEquals(
            "nothing a reader sees may come from the exception",
            emptyList<String>(),
            TechnicalJargon.offendersIn(readableText),
        )

        // Not TechnicalJargon here: a data class's toString() renders absent fields as `null`,
        // which the JVM-null rule would flag as a false positive. These are the exception's own
        // distinctive fragments instead — none of them may appear on any field, rendered or not.
        val everything = messages.toString() + " " + viewModel.error.value
        listOf("llama_decode", "rc=-1", "LlamaEngine", "InferenceException").forEach { fragment ->
            assertFalse(
                "\"$fragment\" survived from the exception into $everything",
                everything.contains(fragment),
            )
        }
    }

    /**
     * TEN-35's acceptance criterion, as far as it can be taken without a device: a conversation
     * carrying a sampling override produces a [DecodeRequest] built from that override, not from
     * the defaults. Everything downstream of here — llama.cpp actually honouring the temperature —
     * is the engine's contract, not this layer's.
     */
    @Test
    fun aConversationsSamplingOverrideIsWhatReachesTheDecode() = runBlocking {
        loadFakeModel()
        val conversation = chatRepository.createConversation(modelId = null)
        val custom = SamplingConfig(
            temperature = 0.1f,
            topP = 0.5f,
            topK = 3,
            minP = 0.2f,
            stopSequences = listOf("\nUser:", "END"),
            maxTokens = 256,
        )
        chatRepository.setSampling(conversation, custom)

        newViewModel(conversation).send("hi")
        awaitAssistantReply(conversation, expectedCount = 2)

        val sampling = engine.lastRequest!!.sampling
        assertEquals(0.1f, sampling.temperature, 1e-6f)
        assertEquals(0.5f, sampling.topP, 1e-6f)
        assertEquals(3, sampling.topK)
        assertEquals(0.2f, sampling.minP, 1e-6f)
        assertEquals(listOf("\nUser:", "END"), sampling.stopSequences)
        assertEquals(256, sampling.maxTokens)
    }

    /**
     * The other half of "applied to the next decode, without reloading the model": a change saved
     * between two turns takes effect on the second one. The request is rebuilt from a fresh read of
     * the conversation row each turn, so nothing has to be invalidated for this to hold — this test
     * is what says so.
     */
    @Test
    fun aSamplingChangeBetweenTwoTurnsAppliesToTheSecondOne() = runBlocking {
        loadFakeModel()
        val conversation = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversation)

        viewModel.send("first")
        awaitAssistantReply(conversation, expectedCount = 2)
        assertEquals(
            "an untouched conversation generates on the defaults",
            SamplingConfig().temperature,
            engine.lastRequest!!.sampling.temperature,
            1e-6f,
        )

        chatRepository.setSampling(conversation, SamplingConfig(temperature = 0.05f, stopSequences = listOf("STOP")))
        viewModel.send("second")
        awaitAssistantReply(conversation, expectedCount = 4)

        assertEquals(0.05f, engine.lastRequest!!.sampling.temperature, 1e-6f)
        assertEquals(listOf("STOP"), engine.lastRequest!!.sampling.stopSequences)
    }

    /** Resetting drops the override for good — the next decode is back on the defaults. */
    @Test
    fun resettingTheSamplingPutsTheNextDecodeBackOnTheDefaults() = runBlocking {
        loadFakeModel()
        val conversation = chatRepository.createConversation(modelId = null)
        chatRepository.setSampling(conversation, SamplingConfig(temperature = 0.05f, maxTokens = 128))
        val viewModel = newViewModel(conversation)

        chatRepository.resetSampling(conversation)
        viewModel.send("hi")
        awaitAssistantReply(conversation, expectedCount = 2)

        assertEquals(SamplingConfig().temperature, engine.lastRequest!!.sampling.temperature, 1e-6f)
        assertEquals(SamplingConfig().maxTokens, engine.lastRequest!!.sampling.maxTokens)
        assertNull("reset stores no blob at all, not an encoded copy of the defaults", chatDao.getConversation(conversation)!!.samplingJson)
    }

    /**
     * TEN-76, the chrome half: the model does not stop supporting thinking because it is busy
     * thinking. Derived from `ModelReady` alone, this flow went `false` the moment a reply started
     * streaming and the toggle vanished from `ChatScreen` mid-answer — the same blind spot that let
     * the Models screen offer to delete a mmapped `.gguf`. See `residentModel`'s KDoc.
     */
    @Test
    fun theThinkingToggleSurvivesTheGenerationItIsThinkingIn() = runBlocking {
        loadFakeModel(supportsThinking = true)
        engine.hangBeforeTerminal = true
        val conversation = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversation)
        assertTrue("precondition: the toggle shows while idle", viewModel.currentModelSupportsThinking.first())

        viewModel.send("hi")
        awaitGenerating()

        assertTrue(
            "the loaded model is still loaded while it generates — the toggle must not disappear",
            viewModel.currentModelSupportsThinking.first(),
        )

        viewModel.stop()
    }

    /**
     * TEN-35's hazard, end to end. A user-set `maxTokens` has to reach the reasoning split too:
     * were the split left at its old hardcoded 1024/1024, this decode would have generated up to
     * 2048 tokens while the user believed they had asked for 300. See
     * `ReasoningBudgetSplittingTest` for the invariant on its own.
     */
    @Test
    fun aCustomMaxTokensIsWhatTheReasoningBudgetIsSplitFrom() = runBlocking {
        loadFakeModel(supportsThinking = true)
        val conversation = chatRepository.createConversation(modelId = null)
        chatRepository.setSampling(conversation, SamplingConfig(maxTokens = 300))

        newViewModel(conversation).send("hi")
        awaitAssistantReply(conversation, expectedCount = 2)

        val sampling = engine.lastRequest!!.sampling
        val budget = sampling.reasoningBudget!!
        assertEquals(300, sampling.maxTokens)
        assertEquals(
            "the caps must sum to the max-tokens the user set — this sum is llama.cpp's nPredict",
            300,
            budget.reasoningMaxTokens + budget.responseMaxTokens,
        )
        assertEquals(ThinkingStreamParser.CloseTag, budget.endMarker)
    }

    /**
     * The same conversation with thinking off gets no split at all, and `maxTokens` is then the
     * single undivided cap llama.cpp is given directly — the pre-TEN-61 path, which the override
     * must reach just as well.
     */
    @Test
    fun withThinkingOffTheCustomMaxTokensIsTheUndividedCap() = runBlocking {
        loadFakeModel(supportsThinking = true)
        val conversation = chatRepository.createConversation(modelId = null)
        chatRepository.setSampling(conversation, SamplingConfig(maxTokens = 300))
        chatRepository.setEnableThinking(conversation, enabled = false)

        newViewModel(conversation).send("hi")
        awaitAssistantReply(conversation, expectedCount = 2)

        val sampling = engine.lastRequest!!.sampling
        assertNull("no split without thinking", sampling.reasoningBudget)
        assertEquals(300, sampling.maxTokens)
        assertFalse(sampling.enableThinking)
    }

    /**
     * TEN-166: a simple message reasons on 256 tokens, a multi-step problem on the whole split, the answer keeping the
     * rest either way; each reply logs which.
     */
    @Test
    fun theReasoningBudgetFollowsTheMessageRepliedToAndIsLogged() = runBlocking {
        loadFakeModel(supportsThinking = true)
        val conversation = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversation)

        viewModel.send("hi")
        awaitAssistantReply(conversation, expectedCount = 2)
        val simple = engine.lastRequest!!.sampling.reasoningBudget!!
        viewModel.send(
            "Tom is twice as old as Ana. In 5 years, the sum of their ages will be 34. How old is Ana now? " +
                "Work it out step by step and answer with the number only.",
        )
        awaitAssistantReply(conversation, expectedCount = 4)
        val problem = engine.lastRequest!!.sampling.reasoningBudget!!

        assertEquals(256 to 1792, simple.reasoningMaxTokens to simple.responseMaxTokens)
        assertEquals(1024 to 1024, problem.reasoningMaxTokens to problem.responseMaxTokens)
        assertEquals(listOf("reasoning budget: 256 (simple)", "reasoning budget: 1024 (problem)"), logLines.filter { it.startsWith("reasoning budget") })
    }

    /** TEN-166 on the agent path: every reply of a tool turn reasons on the budget its message chose, chosen and logged once. */
    @Test
    fun everyReplyOfAToolTurnKeepsTheBudgetOfItsMessageLoggedOnce() = runBlocking {
        agentRuntime = AgentRuntime(ToolRegistry(listOf(DateTimeTool())))
        loadFakeModel(supportsThinking = true, supportsTools = true)
        engine.scriptQueue += toolCallsReply(datetimeCall)
        engine.scriptQueue += answerReply("It is late.")
        val id = chatRepository.createConversation(modelId = null)
        engine.requests.clear()

        newViewModel(id).send("what time is it?")
        awaitGeneration()

        val replies = engine.requests.filter { it.messages.any { m -> m.role == MessageRole.User } }
        assertEquals(2, replies.size)
        assertEquals(listOf(256, 256), replies.map { it.sampling.reasoningBudget?.reasoningMaxTokens })
        assertEquals(listOf("reasoning budget: 256 (simple)"), logLines.filter { it.startsWith("reasoning budget") })
    }

    @Test
    fun withThinkingOffNoBudgetIsChosenOrLogged() = runBlocking {
        loadFakeModel(supportsThinking = true)
        val conversation = chatRepository.createConversation(modelId = null)
        chatRepository.setEnableThinking(conversation, enabled = false)

        newViewModel(conversation).send("hi")
        awaitAssistantReply(conversation, expectedCount = 2)

        assertNull(engine.lastRequest!!.sampling.reasoningBudget)
        assertTrue(logLines.none { it.startsWith("reasoning budget") })
    }

    /**
     * `enableThinking` lives in its own column and is deliberately absent from the sampling blob
     * (see `SamplingJson`), so the request is recombined from two rows. This pins that the column
     * wins over the blob's default rather than the other way round.
     */
    @Test
    fun theThinkingColumnAndTheSamplingBlobAreRecombinedIntoOneRequest() = runBlocking {
        loadFakeModel(supportsThinking = true)
        val conversation = chatRepository.createConversation(modelId = null)
        chatRepository.setSampling(conversation, SamplingConfig(temperature = 0.05f, enableThinking = false))
        chatRepository.setEnableThinking(conversation, enabled = true)

        newViewModel(conversation).send("hi")
        awaitAssistantReply(conversation, expectedCount = 2)

        val sampling = engine.lastRequest!!.sampling
        assertEquals("the blob still carries the knobs", 0.05f, sampling.temperature, 1e-6f)
        assertTrue("the column decides thinking, and the blob never stored it", sampling.enableThinking)
    }

    /**
     * A blob no version of the app could have written — the state a persisted setting ends up in
     * after a bad migration or a partial restore. The conversation must still generate, on the
     * defaults, rather than take the chat screen down with it.
     */
    @Test
    fun aCorruptedSamplingBlobStillGeneratesOnTheDefaults() = runBlocking {
        loadFakeModel()
        val conversation = chatRepository.createConversation(modelId = null)
        chatDao.updateConversation(chatDao.getConversation(conversation)!!.copy(samplingJson = "{\"temperature\":0.4,"))

        newViewModel(conversation).send("hi")
        val reply = awaitAssistantReply(conversation, expectedCount = 2).last()

        assertEquals("hi", reply.content)
        assertEquals(SamplingConfig().temperature, engine.lastRequest!!.sampling.temperature, 1e-6f)
    }

    /** The system prompt is edited from the same sheet, and has to reach the prompt as a system message. */
    @Test
    fun aSystemPromptSetForTheConversationIsPrependedToTheDecodesMessages() = runBlocking {
        loadFakeModel()
        val conversation = chatRepository.createConversation(modelId = null)
        chatRepository.setSystemPrompt(conversation, "You answer in haiku.")

        newViewModel(conversation).send("hi")
        awaitAssistantReply(conversation, expectedCount = 2)

        val first = engine.lastRequest!!.messages.first()
        assertEquals(MessageRole.System, first.role)
        assertEquals("You answer in haiku.", first.content)
    }

    /**
     * TEN-106: a new chat is a draft. Opening one, reading everything the screen reads, and leaving
     * it again, however many times, writes no conversation.
     */
    @Test
    fun openingAndLeavingADraftCreatesNoConversation() = runBlocking {
        loadFakeModel()

        repeat(3) {
            val viewModel = newViewModel(conversationId = null)
            assertEquals(emptyList<ChatMessageUi>(), viewModel.messages.first())
            assertEquals(SamplingConfig(), viewModel.sampling.first())
            assertEquals("", viewModel.systemPrompt.first())
            assertTrue(viewModel.enableThinking.first())
            // Nothing to redo in a draft, and it must not be the thing that creates a row either.
            viewModel.regenerate()
            awaitGeneration()
            assertNull(viewModel.currentConversationId.value)
        }

        assertEquals(0, chatDao.conversationCount())
        assertNull("no generation was ever started", engine.lastRequest)
    }

    /** A send with no model loaded fails before anything is written, so it creates no row either. */
    @Test
    fun aDraftSendWithNoModelLoadedCreatesNoConversation() = runBlocking {
        val viewModel = newViewModel(conversationId = null)

        viewModel.send("hello")
        awaitGeneration()

        assertEquals(ChatError.NoModelLoaded, viewModel.error.value)
        assertEquals(0, chatDao.conversationCount())
    }

    /** TEN-106: the first send creates exactly one row, bound to the loaded model, and the turn lands in it as before. */
    @Test
    fun theFirstSendOnADraftCreatesOneConversationHoldingTheTurn() = runBlocking {
        loadFakeModel()
        val viewModel = newViewModel(conversationId = null)

        viewModel.send("  hello there  ")
        awaitGeneration()

        assertEquals(1, chatDao.conversationCount())
        val id = viewModel.currentConversationId.value
        val conversation = chatRepository.getConversation(id!!)!!
        assertEquals("titled from the first message, as before", "hello there", conversation.title)
        assertEquals("stamped with the model that answered", MODEL_ID, conversation.modelId)
        val messages = awaitAssistantReply(id, expectedCount = 2)
        assertEquals("user", messages[0].role)
        assertEquals("hello there", messages[0].content)
        assertEquals("assistant", messages[1].role)
        assertEquals("the screen now follows the created row", 2, viewModel.messages.first().size)
        assertEquals("the turn is reported against the created row", id, ActiveSessionTracker.current.value?.first)
    }

    /**
     * Settings changed in the sheet before the first message are held by the draft, and the row the
     * first send creates is born with them, so the very first generation already uses them.
     */
    @Test
    fun settingsSetOnADraftSurviveIntoTheCreatedConversation() = runBlocking {
        loadFakeModel(supportsThinking = true)
        val viewModel = newViewModel(conversationId = null)

        viewModel.setSampling(SamplingConfig(temperature = 0.3f, maxTokens = 64))
        viewModel.setSystemPrompt("You answer in haiku.")
        viewModel.setEnableThinking(false)
        awaitGeneration()

        assertEquals("still a draft: settings alone create nothing", 0, chatDao.conversationCount())
        assertEquals(0.3f, viewModel.sampling.first().temperature, 1e-6f)
        assertEquals("You answer in haiku.", viewModel.systemPrompt.first())
        assertFalse(viewModel.enableThinking.first())

        viewModel.send("hi")
        awaitGeneration()

        val conversation = chatRepository.getConversation(viewModel.currentConversationId.value!!)!!
        assertEquals("You answer in haiku.", conversation.systemPrompt)
        assertFalse(conversation.enableThinking)
        val request = engine.lastRequest!!
        assertEquals(0.3f, request.sampling.temperature, 1e-6f)
        assertEquals(64, request.sampling.maxTokens)
        assertFalse(request.sampling.enableThinking)
        assertEquals(MessageRole.System, request.messages.first().role)
        assertEquals("You answer in haiku.", request.messages.first().content)

        // And once the row exists, a change goes to the row, not to a draft nobody reads.
        viewModel.setSystemPrompt("Be brief.")
        awaitGeneration()
        assertEquals("Be brief.", chatRepository.getConversation(conversation.id)!!.systemPrompt)
    }

    /** Two quick sends on a draft create one row, and a later turn reuses it. */
    @Test
    fun sendingTwiceQuicklyOnADraftCreatesOneConversation() = runBlocking {
        loadFakeModel()
        val viewModel = newViewModel(conversationId = null)

        viewModel.send("first")
        viewModel.send("second")
        awaitGeneration()

        assertEquals(1, chatDao.conversationCount())
        val id = viewModel.currentConversationId.value!!
        awaitAssistantReply(id, expectedCount = 2)

        viewModel.send("third")
        awaitGeneration()

        assertEquals(1, chatDao.conversationCount())
        assertEquals("third", awaitAssistantReply(id, expectedCount = 4)[2].content)
    }

    private val datetimeCall = ToolCall("call_1", "datetime", "{}")

    private fun toolCallsReply(vararg calls: ToolCall) =
        listOf(DecodeEvent.ToolTurn("", null, calls.toList(), toolsIgnored = false, toolParseFailed = false, truncated = false))

    /**
     * TEN-185: the raw call text streamed as tokens never reaches the stored reply (the engine held it back), and a
     * call the parser then rejects leaves the fallback message, not the JSON, and no stored calls.
     */
    @Test
    fun aMalformedCallLeavesNeitherTheRawTextNorStoredCalls() = runBlocking {
        agentRuntime = AgentRuntime(ToolRegistry(listOf(DateTimeTool())))
        loadFakeModel(supportsTools = true)
        engine.scriptQueue += listOf(
            // Production order: the token reaches the app before the parse that holds it back.
            DecodeEvent.Token("<tool_call>"),
            DecodeEvent.Parsed(content = "", reasoning = null),
            DecodeEvent.Token("{\"name\": \"datetime\", "),
            DecodeEvent.Parsed(content = "", reasoning = null, toolCalls = listOf("datetime")),
            DecodeEvent.ToolTurn("<tool_call>{\"name\": \"datetime\", ", null, emptyList(), toolsIgnored = false, toolParseFailed = true, truncated = false),
        )
        val id = chatRepository.createConversation(modelId = null)

        newViewModel(id).send("what time is it?")
        awaitGeneration()

        val reply = chatDao.observeMessages(id).first().last()
        assertEquals(toolFallbackMessage(java.util.Locale.getDefault().language), reply.content)
        assertNull(reply.toolCallsJson)
        assertTrue("no write ever held the raw call: ${chatDao.writtenContents}", chatDao.writtenContents.none { it.contains("<tool_call>") })
    }

    /** TEN-185: a parse that failed on something else than a call keeps the model's prose. */
    @Test
    fun aFailedParseWithNoCallSeenKeepsTheProse() = runBlocking {
        agentRuntime = AgentRuntime(ToolRegistry(listOf(DateTimeTool())))
        loadFakeModel(supportsTools = true)
        engine.scriptQueue += listOf(
            DecodeEvent.Token("Sure, "),
            DecodeEvent.Parsed(content = "", reasoning = null),
            DecodeEvent.Token("one moment."),
            DecodeEvent.ToolTurn("Sure, one moment.", null, emptyList(), toolsIgnored = false, toolParseFailed = true, truncated = false),
        )
        val id = chatRepository.createConversation(modelId = null)

        newViewModel(id).send("hi")
        awaitGeneration()

        assertEquals("Sure, one moment.", chatDao.observeMessages(id).first().last().content)
    }

    private fun answerReply(text: String) = listOf(
        DecodeEvent.Token(text),
        DecodeEvent.ToolTurn(text, null, emptyList(), toolsIgnored = false, toolParseFailed = false, truncated = false),
    )

    /**
     * TEN-172: a model that takes tools runs the agent loop. The calls, their results and the final
     * answer are all persisted (and replayed into the second generation), while the chat list shows
     * the question and the answer only, until TEN-173 renders the rest.
     */
    @Test
    fun aToolTurnPersistsTheCallsAndResultsButTheListShowsOnlyTheAnswer() = runBlocking {
        agentRuntime = AgentRuntime(ToolRegistry(listOf(DateTimeTool())))
        loadFakeModel(supportsTools = true)
        engine.scriptQueue += toolCallsReply(datetimeCall)
        engine.scriptQueue += answerReply("It is late.")
        val id = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(id)

        viewModel.send("what time is it?")
        awaitGeneration()

        val stored = chatDao.observeMessages(id).first()
        assertEquals(listOf("user", "assistant", "tool", "assistant"), stored.map { it.role })
        // The runtime gives every call an id of its own, the `tool` message answers by it.
        assertEquals(ToolCallsJson.decode(stored[1].toolCallsJson).single().id, stored[2].toolCallId)
        assertEquals("datetime", stored[2].toolName)
        assertTrue(stored[1].toolCallsJson!!.contains("datetime"))
        assertEquals("It is late.", stored[3].content)
        val row = chatDao.toolCalls.single()
        assertEquals(stored[1].id, row.messageId)
        assertEquals("ok", row.status)
        assertEquals("Local", row.tier)
        assertEquals(
            listOf(MessageRole.User, MessageRole.Assistant, MessageRole.Tool),
            engine.lastRequest!!.messages.map { it.role },
        )
        assertEquals(listOf("datetime"), engine.lastRequest!!.tools.map { it.name })
        assertEquals(listOf("what time is it?", "It is late."), viewModel.messages.first().map { it.content })
        assertTrue(QueueState.ModelReady::class.isInstance(inferenceQueue.state.value))
    }

    /** The conversation's switch is off: the plain path, no tool in the request, as before TEN-172. */
    @Test
    fun withToolsSwitchedOffTheTurnIsPlain() = runBlocking {
        agentRuntime = AgentRuntime(ToolRegistry(listOf(DateTimeTool())))
        loadFakeModel(supportsTools = true)
        val id = chatRepository.createConversation(modelId = null, toolsEnabled = false)

        newViewModel(id).send("hi")
        awaitGeneration()

        assertTrue(engine.lastRequest!!.tools.isEmpty())
        assertEquals(listOf("user", "assistant"), chatDao.observeMessages(id).first().map { it.role })
    }

    /** A model whose template takes no tools never goes through the loop, whatever the registry holds. */
    @Test
    fun aModelWithoutToolSupportTakesThePlainPath() = runBlocking {
        agentRuntime = AgentRuntime(ToolRegistry(listOf(DateTimeTool())))
        loadFakeModel(supportsTools = false)
        val id = chatRepository.createConversation(modelId = null)

        newViewModel(id).send("hi")
        awaitGeneration()

        assertTrue(engine.lastRequest!!.tools.isEmpty())
    }

    /**
     * Stop while a tool runs: the queue is idle then, so the turn is ended through its own job. The
     * running call and the one behind it never finished, and each still gets a `stopped` result so the
     * stored reply does not hang open.
     */
    @Test
    fun stoppingDuringAToolRecordsStoppedResultsForTheCallsThatDidNotFinish() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val hanging = object : Tool {
            override val name = "hang"
            override val description = "never returns"
            override val parametersSchema = """{"type":"object","properties":{}}"""
            override val tier = ToolTier.Local
            override suspend fun execute(argsJson: String): ToolResult {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        agentRuntime = AgentRuntime(ToolRegistry(listOf(hanging, DateTimeTool())))
        loadFakeModel(supportsTools = true)
        engine.scriptQueue += toolCallsReply(ToolCall("c1", "hang", "{}"), ToolCall("c2", "datetime", "{}"))
        val id = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(id)

        viewModel.send("go")
        entered.await()
        assertTrue(viewModel.canStop.first())
        viewModel.stop()
        awaitGeneration()

        val stored = chatDao.observeMessages(id).first()
        assertEquals(listOf("user", "assistant", "tool", "tool"), stored.map { it.role })
        assertEquals(ToolCallsJson.decode(stored[1].toolCallsJson).map { it.id }, stored.filter { it.role == "tool" }.map { it.toolCallId })
        assertEquals(listOf("stopped", "stopped"), chatDao.toolCalls.map { it.status })
        assertNull(viewModel.error.value)
        assertFalse(viewModel.canStop.first())
    }

    /** A tool that never returns, and the signal that it has started. */
    private fun hangingTool(entered: CompletableDeferred<Unit>) = object : Tool {
        override val name = "hang"
        override val description = "never returns"
        override val parametersSchema = """{"type":"object","properties":{}}"""
        override val tier = ToolTier.Local
        override suspend fun execute(argsJson: String): ToolResult {
            entered.complete(Unit)
            awaitCancellation()
        }
    }

    /**
     * The chat left and reopened while a tool runs is a new instance, and the queue reads idle. It
     * still offers Stop, its Stop ends the turn the first one started, and a send there is refused until then.
     */
    @Test
    fun aChatReopenedDuringAToolStopsTheTurnAndRefusesASend() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        agentRuntime = AgentRuntime(ToolRegistry(listOf(hangingTool(entered))))
        loadFakeModel(supportsTools = true)
        engine.scriptQueue += toolCallsReply(ToolCall("c1", "hang", "{}"))
        val id = chatRepository.createConversation(modelId = null)
        newViewModel(id).send("go")
        entered.await()

        val reopened = newViewModel(id)
        assertTrue("the turn is still this conversation's", reopened.canStop.first())
        assertTrue(reopened.turnRunning.first())
        reopened.send("again")
        reopened.regenerate()
        reopened.stop()
        awaitGeneration()

        val stored = chatDao.observeMessages(id).first()
        assertEquals(listOf("user", "assistant", "tool"), stored.map { it.role })
        assertEquals(listOf("stopped"), chatDao.toolCalls.map { it.status })
        assertTrue(chatTurns.running.value.isEmpty())
        assertFalse(reopened.canStop.first())
        assertFalse(reopened.turnRunning.first())
    }

    /** While a tool of A runs the engine is A's, a send in B would take it from under the turn. */
    @Test
    fun aSendInAnotherConversationIsRefusedWhileATurnRunsATool() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        agentRuntime = AgentRuntime(ToolRegistry(listOf(hangingTool(entered))))
        loadFakeModel(supportsTools = true)
        engine.scriptQueue += toolCallsReply(ToolCall("c1", "hang", "{}"))
        val a = chatRepository.createConversation(modelId = null)
        val b = chatRepository.createConversation(modelId = null)
        val viewModelA = newViewModel(a)
        val viewModelB = newViewModel(b)
        viewModelA.send("go")
        entered.await()

        viewModelB.send("hi")
        assertFalse("B has no turn of its own to stop", viewModelB.canStop.first())
        viewModelA.stop()
        awaitGeneration()

        assertTrue(chatDao.observeMessages(b).first().isEmpty())
        assertEquals(listOf("stopped"), chatDao.toolCalls.map { it.status })
    }

    /**
     * Stop then Send on the reopened chat: the turn leaves the registry only once the rows it writes on the way
     * out are written, so the new message lands after them and the history reads user, calls, results, user.
     */
    @Test
    fun aSendRightAfterAStopLandsAfterTheRowsTheCutTurnWrites() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        agentRuntime = AgentRuntime(ToolRegistry(listOf(hangingTool(entered))))
        loadFakeModel(supportsTools = true)
        engine.scriptQueue += toolCallsReply(ToolCall("c1", "hang", "{}"))
        val id = chatRepository.createConversation(modelId = null)
        newViewModel(id).send("go")
        entered.await()
        val reopened = newViewModel(id)

        // Unconfined: runs inside the write that empties the registry, before anything else can.
        var resultsWhenFreed: List<String>? = null
        val freed = launch(Dispatchers.Unconfined) {
            chatTurns.running.first { it.isEmpty() }
            resultsWhenFreed = chatDao.toolCalls.map { it.status }
            reopened.send("again")
        }
        reopened.stop()
        freed.join()
        awaitGeneration()

        assertEquals("the cut call was closed before the turn let go", listOf("stopped"), resultsWhenFreed)
        val stored = chatDao.observeMessages(id).first()
        assertEquals(listOf("user", "assistant", "tool", "user", "assistant"), stored.map { it.role })
        assertEquals("again", stored[3].content)
    }

    /**
     * An empty chat does not warm its tool prefix while another conversation's turn runs a tool: the cache holds
     * that turn, and the warm-up would save it and swap it out. It warms once the turn is over.
     */
    @Test
    fun noPrefixWarmUpWhileAnotherConversationsTurnRunsATool() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        agentRuntime = AgentRuntime(ToolRegistry(listOf(hangingTool(entered))))
        loadFakeModel(supportsTools = true)
        engine.scriptQueue += toolCallsReply(ToolCall("c1", "hang", "{}"))
        val a = chatRepository.createConversation(modelId = null)
        val viewModelA = newViewModel(a)
        viewModelA.send("go")
        entered.await()
        val warmUpsBefore = engine.toolPrefixWarmUps.size

        val warm = keepWarm(newViewModel(null))
        settle()
        assertEquals("nothing warmed while A's tool runs", warmUpsBefore, engine.toolPrefixWarmUps.size)

        viewModelA.stop()
        withTimeout(5_000) { engine.toolPrefixEntered.await() }
        warm.cancelAndJoin()
        awaitGeneration()
    }

    /** A question asked after the chat was reopened is shown there, and leaving that screen dismisses it. */
    @Test
    fun aChatReopenedMidTurnShowsItsQuestionAndLeavingDismissesIt() = runBlocking {
        askRuntime()
        loadFakeModel(supportsTools = true)
        engine.scriptQueue += toolCallsReply(askCall)
        engine.scriptQueue += answerReply("Okay.")
        val id = chatRepository.createConversation(modelId = null)
        newViewModel(id).send("text Jade")
        withTimeout(5_000) { askUserGate.pending.first { it.isNotEmpty() } }

        val reopened = newViewModel(id)
        assertNotNull(reopened.pendingQuestion.first())
        reopened.leave()
        awaitGeneration()

        assertEquals("""{"dismissed":true}""", chatRepository.observeMessages(id).first().single { it.role == "tool" }.content)
        assertTrue(chatTurns.running.value.isEmpty())
    }

    /** TEN-173: the Tools switch is stored per conversation, and a draft's choice lands in the row its first send creates. */
    @Test
    fun theToolsSwitchIsPersistedPerConversationAndCarriedByADraft() = runBlocking {
        val id = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(id)
        assertTrue(viewModel.toolsEnabled.first())

        viewModel.setToolsEnabled(false)
        awaitGeneration()

        assertFalse(chatRepository.getConversation(id)!!.toolsEnabled)
        assertFalse(viewModel.toolsEnabled.first())
        assertTrue(newViewModel(chatRepository.createConversation(modelId = null)).toolsEnabled.first())

        loadFakeModel()
        val draft = newViewModel(null)
        draft.setToolsEnabled(false)
        awaitGeneration()
        assertFalse(draft.toolsEnabled.first())
        draft.send("hi")
        awaitGeneration()

        val created = draft.currentConversationId.value!!
        assertFalse(chatRepository.getConversation(created)!!.toolsEnabled)
    }

    /** TEN-173: the stored rows of a tool turn come back as the question, one tool line, and the answer. */
    @Test
    fun aToolTurnShowsAsAMessageAToolLineAndTheAnswer() = runBlocking {
        agentRuntime = AgentRuntime(ToolRegistry(listOf(DateTimeTool())))
        loadFakeModel(supportsTools = true)
        engine.scriptQueue += toolCallsReply(datetimeCall)
        engine.scriptQueue += answerReply("It is late.")
        val id = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(id)

        viewModel.send("what time is it?")
        awaitGeneration()

        val items = viewModel.items.first()
        assertEquals(listOf("message", "line", "message"), items.map { if (it is MessageItem) "message" else "line" })
        val line = items[1] as ToolLineItem
        assertEquals(listOf("datetime"), line.calls.map { it.tool })
        assertEquals(ToolCallState.Ok, line.calls.single().state)
        assertEquals("It is late.", (items[2] as MessageItem).message.content)
    }

    /** A call that sends or deletes: the card is pending until answered, a Deny is a refusal the model is told about. */
    @Test
    fun denyingAConfirmationRefusesTheCallAndTheCardFoldsIntoDenied() = runBlocking {
        val eraser = eraser()
        agentRuntime = AgentRuntime(ToolRegistry(listOf(eraser)), gate = confirmationGate)
        loadFakeModel(supportsTools = true)
        engine.scriptQueue += toolCallsReply(ToolCall("c1", "erase", """{"id":1}"""))
        engine.scriptQueue += answerReply("Left it alone.")
        val id = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(id)

        viewModel.send("erase fact 1")
        val callId = withTimeout(5_000) { confirmationGate.pending.first { it.isNotEmpty() } }.keys.single()
        val pending = viewModel.items.first().filterIsInstance<ConfirmationItem>().single()
        assertEquals(ConfirmDecision.Pending, pending.decision)

        viewModel.answerConfirmation(callId, allow = false)
        awaitGeneration()

        assertEquals("refused", chatDao.toolCalls.single().status)
        assertEquals(0, eraser.runs)
        val items = viewModel.items.first()
        assertEquals(ConfirmDecision.Denied, items.filterIsInstance<ConfirmationItem>().single().decision)
        assertTrue(items.none { it is ToolLineItem })
    }

    @Test
    fun allowingAConfirmationRunsTheCall() = runBlocking {
        val eraser = eraser()
        agentRuntime = AgentRuntime(ToolRegistry(listOf(eraser)), gate = confirmationGate)
        loadFakeModel(supportsTools = true)
        engine.scriptQueue += toolCallsReply(ToolCall("c1", "erase", """{"id":1}"""))
        engine.scriptQueue += answerReply("Erased.")
        val id = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(id)

        viewModel.send("erase fact 1")
        val callId = withTimeout(5_000) { confirmationGate.pending.first { it.isNotEmpty() } }.keys.single()
        viewModel.answerConfirmation(callId, allow = true)
        awaitGeneration()

        assertEquals("ok", chatDao.toolCalls.single().status)
        assertEquals(1, eraser.runs)
        assertEquals(
            ConfirmDecision.Allowed,
            viewModel.items.first().filterIsInstance<ConfirmationItem>().single().decision,
        )
    }

    /** Leaving the chat answers a waiting card with a Deny: nobody is left to tap it. */
    @Test
    fun leavingTheChatDeniesAWaitingConfirmation() = runBlocking {
        val eraser = eraser()
        agentRuntime = AgentRuntime(ToolRegistry(listOf(eraser)), gate = confirmationGate)
        loadFakeModel(supportsTools = true)
        engine.scriptQueue += toolCallsReply(ToolCall("c1", "erase", """{"id":1}"""))
        engine.scriptQueue += answerReply("Okay, not erasing.")
        val id = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(id)

        viewModel.send("erase fact 1")
        val callId = withTimeout(5_000) { confirmationGate.pending.first { it.isNotEmpty() } }.keys.single()
        viewModel.leave()
        awaitGeneration()

        assertEquals("refused", chatDao.toolCalls.single().status)
        assertEquals(0, eraser.runs)
        assertTrue(confirmationGate.pending.value.isEmpty())
    }

    /** Stop while the card waits: the pending call is dropped, never runs, and is closed as stopped. */
    @Test
    fun stoppingWhileAConfirmationWaitsDropsItAndRecordsStopped() = runBlocking {
        val eraser = eraser()
        agentRuntime = AgentRuntime(ToolRegistry(listOf(eraser)), gate = confirmationGate)
        loadFakeModel(supportsTools = true)
        engine.scriptQueue += toolCallsReply(ToolCall("c1", "erase", """{"id":1}"""))
        val id = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(id)

        viewModel.send("erase fact 1")
        val callId = withTimeout(5_000) { confirmationGate.pending.first { it.isNotEmpty() } }.keys.single()
        viewModel.stop()
        awaitGeneration()

        assertTrue(confirmationGate.pending.value.isEmpty())
        assertEquals("stopped", chatDao.toolCalls.single().status)
        assertEquals(0, eraser.runs)
        val card = viewModel.items.first().filterIsInstance<ConfirmationItem>().single()
        assertEquals(ConfirmDecision.Stopped, card.decision)
    }

    // ask_user (TEN-187)

    private val askCall = ToolCall("c1", "ask_user", """{"question":"Which Jade?","options":["Jade Martin","Jade Leroy"]}""")

    private fun askRuntime() {
        agentRuntime = AgentRuntime(ToolRegistry(listOf(AskUserTool(askUserGate))))
    }

    /** The question is published while the turn waits, the answer goes back as the tool result and shows as a Q/A row. */
    @Test
    fun answeringAQuestionGivesTheModelTheResultAndShowsTheAnswerRow() = runBlocking {
        askRuntime()
        loadFakeModel(supportsTools = true)
        engine.scriptQueue += toolCallsReply(askCall)
        engine.scriptQueue += answerReply("Drafting to Jade Leroy.")
        val id = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(id)

        viewModel.send("text Jade")
        val asked = withTimeout(5_000) { viewModel.pendingQuestion.first { it != null } }!!
        assertEquals("Which Jade?", asked.question)
        assertEquals(listOf("Jade Martin", "Jade Leroy"), asked.options)
        assertEquals("running", (viewModel.items.first().filterIsInstance<ToolLineItem>().single().calls.single().state).name.lowercase())

        viewModel.answerQuestion(asked.callId, listOf("Jade Leroy"))
        awaitGeneration()

        assertEquals("ok", chatDao.toolCalls.single().status)
        assertNull(viewModel.pendingQuestion.first())
        val items = viewModel.items.first()
        assertEquals(listOf("message", "line", "answer", "message"), items.map { if (it is MessageItem) "message" else if (it is AskAnswerItem) "answer" else "line" })
        assertEquals(listOf("Jade Leroy"), (items[2] as AskAnswerItem).answers)
        // The model saw a tool result, and no user message was invented for the answer.
        assertEquals("""{"answer":"Jade Leroy"}""", chatRepository.observeMessages(id).first().single { it.role == "tool" }.content)
        assertEquals(1, chatRepository.observeMessages(id).first().count { it.role == "user" })
    }

    @Test
    fun dismissingAQuestionTellsTheModelAndDrawsNoAnswerRow() = runBlocking {
        askRuntime()
        loadFakeModel(supportsTools = true)
        engine.scriptQueue += toolCallsReply(askCall)
        engine.scriptQueue += answerReply("I'll leave it empty.")
        val id = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(id)

        viewModel.send("text Jade")
        val asked = withTimeout(5_000) { viewModel.pendingQuestion.first { it != null } }!!
        viewModel.dismissQuestion(asked.callId)
        awaitGeneration()

        assertEquals("""{"dismissed":true}""", chatRepository.observeMessages(id).first().single { it.role == "tool" }.content)
        val items = viewModel.items.first()
        assertTrue(items.none { it is AskAnswerItem })
        assertEquals(ToolCallState.Dismissed, items.filterIsInstance<ToolLineItem>().single().calls.single().state)
    }

    @Test
    fun leavingTheChatDismissesAWaitingQuestion() = runBlocking {
        askRuntime()
        loadFakeModel(supportsTools = true)
        engine.scriptQueue += toolCallsReply(askCall)
        engine.scriptQueue += answerReply("Okay.")
        val id = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(id)

        viewModel.send("text Jade")
        withTimeout(5_000) { viewModel.pendingQuestion.first { it != null } }
        viewModel.leave()
        awaitGeneration()

        assertEquals("""{"dismissed":true}""", chatRepository.observeMessages(id).first().single { it.role == "tool" }.content)
        assertTrue(askUserGate.pending.value.isEmpty())
    }

    @Test
    fun stoppingWhileAQuestionWaitsDropsItAndRecordsStopped() = runBlocking {
        askRuntime()
        loadFakeModel(supportsTools = true)
        engine.scriptQueue += toolCallsReply(askCall)
        val id = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(id)

        viewModel.send("text Jade")
        withTimeout(5_000) { viewModel.pendingQuestion.first { it != null } }
        viewModel.stop()
        awaitGeneration()

        // The tool is cancelled on its own scope, a moment after the turn ends.
        withTimeout(5_000) { askUserGate.pending.first { it.isEmpty() } }
        assertEquals("stopped", chatDao.toolCalls.single().status)
        assertNull(viewModel.pendingQuestion.first())
    }

    private class Eraser : Tool {
        var runs = 0
        override val name = "erase"
        override val description = "deletes a fact"
        override val parametersSchema = """{"type":"object","properties":{"id":{"type":"integer"}},"required":["id"]}"""
        override val tier = ToolTier.Local
        override val sendsOrDeletes = true
        override suspend fun execute(argsJson: String): ToolResult {
            runs++
            return ToolResult.ok("erased")
        }
    }

    private fun eraser() = Eraser()

    /** A reply that ended with [stopReason] after [tokens] tokens, as the engine would report it. */
    private fun finishedReply(tokens: Int, stopReason: StopReason = StopReason.Complete) = listOf(
        DecodeEvent.Token("a long answer"),
        DecodeEvent.Done(DecodeStats(tokensGenerated = tokens, tokPerSec = 1f, ttftMs = 1L, stopReason = stopReason)),
    )

    /**
     * TEN-205: a template that, like Qwen3's, opens the system message with the tool block whether or not the
     * conversation has a system prompt, and keeps the rest of what a request carries (choice, thinking flag)
     * in front of the user's turn.
     */
    private fun toolTemplate(messages: List<ChatMessage>, request: DecodeRequest): String {
        val system = messages.firstOrNull { it.role == MessageRole.System }?.content.orEmpty()
        val tools = request.tools.joinToString(";") { "${it.name}:${it.description}:${it.parametersJson}" }
        val rest = messages.filter { it.role != MessageRole.System }
            .joinToString("") { "<|im_start|>${it.role}\n${it.content}<|im_end|>\n" }
        val header = "<|im_start|>system\n$system\n#tools $tools\n#choice ${request.toolChoice}\n#think ${request.sampling.enableThinking}<|im_end|>\n"
        return header + rest + "<|im_start|>assistant\n"
    }

    /** The [PrefixCache] file name the engine would give [request]'s prefix, under one fixed model identity. */
    private fun prefixKey(request: DecodeRequest): String {
        val prefix = WarmUpPrompt.build(request.messages.takeWhile { it.role == MessageRole.System }) { rendered ->
            toolTemplate(rendered, request)
        } ?: error("no boundary in the fake template")
        return PrefixCache(File(storageRoot, "kv")).key("identity", prefix)
    }

    /** Lets everything that can run without waiting for the outside world run: the fakes never leave this thread. */
    private suspend fun settle() = repeat(100) { yield() }

    private fun CoroutineScope.keepWarm(viewModel: ChatViewModel) = launch { viewModel.keepPrefixWarm() }

    @Test
    fun theWarmedPrefixHasTheCacheKeyOfTheFirstRealRequestBuiltFromTheSameInputs() = runBlocking {
        agentRuntime = AgentRuntime(ToolRegistry(listOf(DateTimeTool())))
        loadFakeModel(supportsTools = true)
        val viewModel = newViewModel(null)
        viewModel.setSystemPrompt("be brief")
        viewModel.setEnableThinking(false)
        awaitGeneration()

        val warm = keepWarm(viewModel)
        withTimeout(5_000) { engine.toolPrefixEntered.await() }
        viewModel.send("hi")
        awaitGeneration()
        warm.cancelAndJoin()

        val (warmed, _) = engine.toolPrefixWarmUps.single()
        val real = engine.lastRequest!!
        assertTrue("the real request carries tools", real.tools.isNotEmpty())
        assertEquals(prefixKey(real), prefixKey(warmed))
        // The key tells requests apart at all, so the equality above is not a given.
        assertNotEquals(prefixKey(real), prefixKey(real.copy(messages = real.messages.drop(1))))
        assertNotEquals(prefixKey(real), prefixKey(real.copy(tools = emptyList())))
    }

    /** 2026-10-24 (a Saturday), noon in Paris. */
    private val fixedClock = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneId.of("Europe/Paris"))

    @Test
    fun anAgentRequestOpensWithTheBasePromptThenTheGlobalThenTheChatInstructions() = runBlocking {
        systemPrompts = SystemPrompt(clock = fixedClock)
        agentRuntime = AgentRuntime(ToolRegistry(listOf(DateTimeTool())))
        globalInstructions.value = "Use metric units."
        loadFakeModel(supportsTools = true)
        val viewModel = newViewModel(null)
        viewModel.setSystemPrompt("Be brief.")
        awaitGeneration()

        viewModel.send("hi")
        awaitGeneration()

        val request = engine.lastRequest!!
        assertTrue("the agent path ran", request.tools.isNotEmpty())
        val expected = SystemPrompt.assemble(BasePrompt.render(setOf("datetime"), LocalDate.of(2026, 10, 24)), "Use metric units.", "Be brief.")
        assertEquals(MessageRole.System, request.messages.first().role)
        assertEquals(expected, request.messages.first().content)
        val system = request.messages.first().content
        assertTrue(system.indexOf("call datetime") < system.indexOf("Use metric units."))
        assertTrue(system.indexOf("Use metric units.") < system.indexOf("Be brief."))
        assertEquals("one system message, then the user's", listOf(MessageRole.System, MessageRole.User), request.messages.map { it.role })
    }

    @Test
    fun aPlainChatGetsTheBasePromptWithoutToolRulesAndTheDayItStarted() = runBlocking {
        systemPrompts = SystemPrompt(clock = fixedClock)
        loadFakeModel(supportsTools = false)
        val viewModel = newViewModel(null)

        viewModel.send("hi")
        awaitGeneration()

        val system = engine.lastRequest!!.messages.first()
        assertEquals(MessageRole.System, system.role)
        assertEquals(BasePrompt.render(null, LocalDate.of(2026, 10, 24)), system.content)
        assertTrue(system.content.contains("started on Saturday, 24 October 2026"))
        assertFalse(system.content.contains("tool"))
    }

    @Test
    fun aConversationKeepsTheDayItStartedOnLaterTurns() = runBlocking {
        val createdAt = Instant.parse("2026-10-20T08:00:00Z").toEpochMilli()
        systemPrompts = SystemPrompt(clock = fixedClock)
        loadFakeModel(supportsTools = false)
        val id = chatRepository.createConversation(modelId = null)
        chatDao.patchCreatedAt(id, createdAt)
        val viewModel = newViewModel(id)

        viewModel.send("hi")
        awaitGeneration()

        assertTrue(engine.lastRequest!!.messages.first().content.contains("started on Tuesday, 20 October 2026"))
    }

    /** Without `datetime` the base prompt carries a date: the draft's (today) and the row's (its creation) must agree. */
    @Test
    fun withADatedBasePromptTheFirstSendFindsTheWarmedPrefixAndBuildsNoOther() = runBlocking {
        systemPrompts = SystemPrompt(clock = fixedClock)
        agentRuntime = AgentRuntime(ToolRegistry(listOf(CalculateTool())))
        loadFakeModel(supportsTools = true)
        val viewModel = newViewModel(null)
        val warm = keepWarm(viewModel)
        withTimeout(5_000) { engine.toolPrefixEntered.await() }
        settle()

        viewModel.send("hi")
        awaitGeneration()
        warm.cancelAndJoin()

        val warmed = engine.toolPrefixWarmUps.single().first
        val sent = engine.lastRequest!!
        assertTrue(warmed.messages.first().content.contains("started on Saturday, 24 October 2026"))
        assertEquals(warmed.messages.first(), sent.messages.first())
        assertEquals(prefixKey(warmed), prefixKey(sent))
        assertEquals("the prefix was resident, nothing of it left to prefill", listOf(PREFIX_TOKENS), engine.residentAtToolDecode)
    }

    @Test
    fun editingYourInstructionsWarmsTheNewPrefixUnderAnotherCacheKey() = runBlocking {
        systemPrompts = SystemPrompt(clock = fixedClock)
        agentRuntime = AgentRuntime(ToolRegistry(listOf(DateTimeTool())))
        loadFakeModel(supportsTools = true)
        val viewModel = newViewModel(null)
        val warm = keepWarm(viewModel)
        withTimeout(5_000) { engine.toolPrefixEntered.await() }
        settle()

        globalInstructions.value = "Use metric units."
        withTimeout(5_000) { while (engine.toolPrefixWarmUps.size < 2) yield() }
        warm.cancelAndJoin()

        val (before, after) = engine.toolPrefixWarmUps.map { it.first }
        assertFalse(before.messages.first().content.contains("Use metric units."))
        assertTrue(after.messages.first().content.endsWith("${SystemPrompt.GLOBAL_LABEL}\nUse metric units."))
        assertNotEquals(prefixKey(before), prefixKey(after))
    }

    @Test
    fun aSendAfterTheWarmUpFindsTheWholePrefixResidentAndAWarmUpIsNotRepeated() = runBlocking {
        agentRuntime = AgentRuntime(ToolRegistry(listOf(DateTimeTool())))
        loadFakeModel(supportsTools = true)
        val viewModel = newViewModel(null)
        val warm = keepWarm(viewModel)
        withTimeout(5_000) { engine.toolPrefixEntered.await() }
        settle()

        viewModel.send("hi")
        awaitGeneration()
        warm.cancelAndJoin()

        assertEquals("one warm-up, none when the message arrived", 1, engine.toolPrefixWarmUps.size)
        assertEquals("the prefix was resident, nothing of it left to prefill", listOf(PREFIX_TOKENS), engine.residentAtToolDecode)
    }

    @Test
    fun aSendDuringTheWarmUpStopsItKeepingWhatItDecodedAndDoesNotPrefillThePrefixTwice() = runBlocking {
        agentRuntime = AgentRuntime(ToolRegistry(listOf(DateTimeTool())))
        loadFakeModel(supportsTools = true)
        engine.toolPrefixHangs = true
        val viewModel = newViewModel(null)
        val warm = keepWarm(viewModel)
        withTimeout(5_000) { engine.toolPrefixEntered.await() }
        engine.toolPrefixHangs = false

        viewModel.send("hi")
        awaitGeneration()
        warm.cancelAndJoin()

        assertEquals("the send interrupted the warm-up", 1, engine.toolPrefixInterrupted)
        assertEquals(
            "the generation finished the prefix over what was resident, and nobody warmed it again",
            listOf(false, true),
            engine.toolPrefixWarmUps.map { it.second },
        )
        assertEquals(
            "the decode found the whole prefix resident",
            listOf(PREFIX_TOKENS),
            engine.residentAtToolDecode,
        )
        assertEquals("the reply came out", 2, chatRepository.observeMessages(viewModel.currentConversationId.value!!).first().size)
    }

    @Test
    fun noWarmUpWhileNoToolIsGrantedThenOneOnceATierIsGranted() = runBlocking {
        grantedTiers.value = emptySet()
        agentRuntime = AgentRuntime(ToolRegistry(listOf(DateTimeTool()), grants))
        loadFakeModel(supportsTools = true)
        val warm = keepWarm(newViewModel(null))
        settle()
        assertTrue("no tool is granted: a plain request has no prefix to warm", engine.toolPrefixWarmUps.isEmpty())

        grantedTiers.value = setOf(ToolTier.Local)
        withTimeout(5_000) { engine.toolPrefixEntered.await() }
        warm.cancelAndJoin()

        assertEquals(listOf("datetime"), engine.toolPrefixWarmUps.single().first.tools.map { it.name })
    }

    @Test
    fun noWarmUpForAConversationWithToolsSwitchedOff() = runBlocking {
        agentRuntime = AgentRuntime(ToolRegistry(listOf(DateTimeTool())))
        loadFakeModel(supportsTools = true)
        val viewModel = newViewModel(null)
        viewModel.setToolsEnabled(false)
        awaitGeneration()
        val warm = keepWarm(viewModel)
        settle()
        warm.cancelAndJoin()

        assertTrue(engine.toolPrefixWarmUps.isEmpty())
    }

    @Test
    fun noWarmUpForAModelWhoseTemplateTakesNoTools() = runBlocking {
        agentRuntime = AgentRuntime(ToolRegistry(listOf(DateTimeTool())))
        loadFakeModel(supportsTools = false)
        val warm = keepWarm(newViewModel(null))
        settle()
        warm.cancelAndJoin()

        assertTrue(engine.toolPrefixWarmUps.isEmpty())
    }

    @Test
    fun noWarmUpForAConversationThatAlreadyHasMessages() = runBlocking {
        agentRuntime = AgentRuntime(ToolRegistry(listOf(DateTimeTool())))
        loadFakeModel(supportsTools = true)
        val id = chatRepository.createConversation(modelId = null)
        chatRepository.appendUserMessage(id, "earlier")
        val warm = keepWarm(newViewModel(id))
        settle()
        warm.cancelAndJoin()

        assertTrue("its session is restored on the first send, a prefix would sit in the way", engine.toolPrefixWarmUps.isEmpty())
    }

    @Test
    fun aTierChangeReplacesTheWarmedPrefixAndStopsTheWarmUpInFlight() = runBlocking {
        agentRuntime = AgentRuntime(ToolRegistry(listOf(DateTimeTool(), phoneTool()), grants))
        loadFakeModel(supportsTools = true)
        engine.toolPrefixHangs = true
        val warm = keepWarm(newViewModel(null))
        withTimeout(5_000) { engine.toolPrefixEntered.await() }

        engine.toolPrefixHangs = false
        engine.toolPrefixEntered = CompletableDeferred()
        grantedTiers.value = setOf(ToolTier.Local, ToolTier.PhoneAction)
        withTimeout(5_000) { engine.toolPrefixEntered.await() }
        settle()
        warm.cancelAndJoin()

        assertEquals("the first one was stopped for the second", 1, engine.toolPrefixInterrupted)
        val (second, replacesResident) = engine.toolPrefixWarmUps.last()
        assertEquals(listOf("datetime", "phone"), second.tools.map { it.name })
        assertTrue("what is resident is the old prefix and nothing else: the engine may carry on over it", replacesResident)
    }

    @Test
    fun aConversationWithASavedSessionStillRestoresOverAWarmedPrefix() = runBlocking {
        agentRuntime = AgentRuntime(ToolRegistry(listOf(DateTimeTool())))
        loadFakeModel(supportsTools = true)
        val conversationA = chatRepository.createConversation(modelId = null)
        val conversationB = chatRepository.createConversation(modelId = null)
        newViewModel(conversationA).send("hi from A")
        awaitAssistantReply(conversationA, expectedCount = 2)
        newViewModel(conversationB).send("hi from B")
        awaitAssistantReply(conversationB, expectedCount = 2)
        assertTrue("precondition: A's session is on disk", sessionDao.getByConversation(conversationA) != null)

        // The model is reloaded (the swap hook clears the tracker), a new chat warms the prefix, then the user opens A.
        inferenceQueue.unloadModel()
        ActiveSessionTracker.clear()
        loadImportedModel(MODEL_ID)
        val warm = keepWarm(newViewModel(null))
        withTimeout(5_000) { engine.toolPrefixEntered.await() }
        settle()
        assertTrue("precondition: the cache holds the prefix", inferenceQueue.holdsOnlyWarmedPrefix())

        newViewModel(conversationA).send("back in A")
        awaitAssistantReply(conversationA, expectedCount = 4)
        warm.cancelAndJoin()

        assertEquals("A's session went back in over the prefix", 1, engine.loadSessionCalls)
    }

    /** A replied with only the local tools granted, then the user granted another tier: a new chat's prefix differs from A's. */
    private suspend fun CoroutineScope.conversationInCacheThenTierChange(): String {
        agentRuntime = AgentRuntime(ToolRegistry(listOf(DateTimeTool(), phoneTool()), grants))
        loadFakeModel(supportsTools = true)
        val conversationA = chatRepository.createConversation(modelId = null)
        newViewModel(conversationA).send("hi from A")
        awaitAssistantReply(conversationA, expectedCount = 2)
        grantedTiers.value = setOf(ToolTier.Local, ToolTier.PhoneAction)
        return conversationA
    }

    @Test
    fun aNewChatAfterATierChangeSavesTheConversationInTheCacheAndPutsThePrefixInItsPlace() = runBlocking {
        val conversationA = conversationInCacheThenTierChange()

        val warm = keepWarm(newViewModel(null))
        withTimeout(5_000) { engine.toolPrefixEntered.await() }
        settle()
        warm.cancelAndJoin()

        assertEquals("A's state is on disk, as it was in the cache", TOKENS_PER_TURN, sessionDao.getByConversation(conversationA)?.tokenCount)
        val (warmed, replacesResident) = engine.toolPrefixWarmUps.single()
        assertEquals(listOf("datetime", "phone"), warmed.tools.map { it.name })
        assertTrue("A is saved: the engine may replace it", replacesResident)
        assertTrue("A's session may restore over it", inferenceQueue.holdsOnlyWarmedPrefix())
        assertNull("the cache is not A's any more, nothing may save it as A's", ActiveSessionTracker.current.value)

        newViewModel(conversationA).send("back in A")
        awaitAssistantReply(conversationA, expectedCount = 4)

        assertEquals("A's session went back in over the prefix", 1, engine.loadSessionCalls)
    }

    @Test
    fun aNewChatWithTheToolSetOfTheConversationInTheCacheLeavesItThere() = runBlocking {
        agentRuntime = AgentRuntime(ToolRegistry(listOf(DateTimeTool(), phoneTool()), grants))
        loadFakeModel(supportsTools = true)
        val conversationA = chatRepository.createConversation(modelId = null)
        newViewModel(conversationA).send("hi from A")
        awaitAssistantReply(conversationA, expectedCount = 2)

        val warm = keepWarm(newViewModel(null))
        withTimeout(5_000) { engine.toolPrefixEntered.await() }
        settle()
        warm.cancelAndJoin()

        assertFalse("the first message reuses A's prefix as it is", engine.toolPrefixWarmUps.single().second)
        assertNull("so nothing had to be saved", sessionDao.getByConversation(conversationA))
        assertEquals(conversationA, ActiveSessionTracker.current.value?.first)
    }

    @Test
    fun aConversationReportedAfterTheSaveKeepsItsTrackerEntryThroughTheReplace() = runBlocking {
        conversationInCacheThenTierChange()
        // A send in another chat between the save and the warm-up taking the queue: it reports itself the owner.
        engine.duringSave = { ActiveSessionTracker.report("new-owner", ActiveSessionTracker.current.value!!.second) }

        val warm = keepWarm(newViewModel(null))
        withTimeout(5_000) { engine.toolPrefixEntered.await() }
        settle()
        warm.cancelAndJoin()

        assertTrue("precondition: the cache was replaced", engine.toolPrefixWarmUps.single().second)
        assertEquals("only A's entry may be cleared", "new-owner", ActiveSessionTracker.current.value?.first)
    }

    @Test
    fun aFailedSaveLeavesTheConversationInTheCache() = runBlocking {
        val conversationA = conversationInCacheThenTierChange()
        engine.saveShouldFail = true

        val warm = keepWarm(newViewModel(null))
        withTimeout(5_000) { engine.toolPrefixEntered.await() }
        settle()
        warm.cancelAndJoin()

        assertFalse("A is not on disk: the engine must leave it alone", engine.toolPrefixWarmUps.single().second)
        assertFalse(inferenceQueue.holdsOnlyWarmedPrefix())
        assertEquals(conversationA, ActiveSessionTracker.current.value?.first)
    }

    @Test
    fun aSendDuringTheReplaceFinishesThePrefixAndAStillRestores() = runBlocking {
        val conversationA = conversationInCacheThenTierChange()
        engine.toolPrefixHangs = true
        val viewModel = newViewModel(null)
        val warm = keepWarm(viewModel)
        withTimeout(5_000) { engine.toolPrefixEntered.await() }
        engine.toolPrefixHangs = false

        viewModel.send("hi")
        awaitGeneration()
        warm.cancelAndJoin()

        assertEquals("the send interrupted the warm-up", 1, engine.toolPrefixInterrupted)
        assertEquals("the generation finished the prefix over what was resident", listOf(true, true), engine.toolPrefixWarmUps.map { it.second })
        assertEquals("the decode found the whole prefix resident", PREFIX_TOKENS, engine.residentAtToolDecode.last())

        newViewModel(conversationA).send("back in A")
        awaitAssistantReply(conversationA, expectedCount = 4)

        assertEquals(TOKENS_PER_TURN, sessionDao.getByConversation(conversationA)?.tokenCount)
        assertEquals("A's session went back in over the new chat's cache", 1, engine.loadSessionCalls)
    }

    @Test
    fun aModelLoadedWhileTheAppIsInTheBackgroundIsWarmedWhenItComesBack() = runBlocking {
        agentRuntime = AgentRuntime(ToolRegistry(listOf(DateTimeTool())))
        AppForegroundTracker.onActivityStopped()
        loadFakeModel(supportsTools = true)
        val warm = keepWarm(newViewModel(null))
        settle()
        assertTrue("minutes of CPU nobody sees", engine.toolPrefixWarmUps.isEmpty())

        AppForegroundTracker.onActivityStarted()
        withTimeout(5_000) { engine.toolPrefixEntered.await() }
        warm.cancelAndJoin()

        assertEquals(1, engine.toolPrefixWarmUps.size)
    }

    @Test
    fun leavingTheAppStopsAWarmUpInFlightAndComingBackResumesIt() = runBlocking {
        agentRuntime = AgentRuntime(ToolRegistry(listOf(DateTimeTool())))
        loadFakeModel(supportsTools = true)
        engine.toolPrefixHangs = true
        val warm = keepWarm(newViewModel(null))
        withTimeout(5_000) { engine.toolPrefixEntered.await() }

        AppForegroundTracker.onActivityStopped()
        settle()
        assertEquals("stopped when the app went away", 1, engine.toolPrefixInterrupted)

        engine.toolPrefixHangs = false
        engine.toolPrefixEntered = CompletableDeferred()
        AppForegroundTracker.onActivityStarted()
        withTimeout(5_000) { engine.toolPrefixEntered.await() }
        settle()
        warm.cancelAndJoin()

        assertEquals(2, engine.toolPrefixWarmUps.size)
        assertTrue("over the partial prefix the first one left", engine.toolPrefixWarmUps.last().second)
    }

    private fun phoneTool() = object : Tool {
        override val name = "phone"
        override val description = "opens an app"
        override val parametersSchema = """{"type":"object","properties":{}}"""
        override val tier = ToolTier.PhoneAction
        override suspend fun execute(argsJson: String): ToolResult = ToolResult.ok("done")
    }

    /**
     * TEN-88: the trigger. A reply that ended on its own and reached the threshold is followed by a
     * warm-up of the conversation that produced it, built from the history *including* that reply
     * (the next prompt carries it) with the conversation's own thinking setting.
     */
    @Test
    fun aLongReplyThatEndedNormallyIsFollowedByAWarmUpOfItsConversation() = runBlocking {
        loadFakeModel()
        engine.scriptedEvents = finishedReply(WarmUpMinTokens)
        val conversation = chatRepository.createConversation(modelId = null, systemPrompt = "be brief", enableThinking = false)
        val viewModel = newViewModel(conversation)

        viewModel.send("hi")
        awaitGeneration()

        assertEquals("exactly one warm-up", 1, engine.warmUps.size)
        val (messages, enableThinking) = engine.warmUps.single()
        assertEquals(
            "system prompt, the user turn, and the reply that just finished, in the order the next prompt carries them",
            listOf(MessageRole.System, MessageRole.User, MessageRole.Assistant),
            messages.map { it.role },
        )
        assertEquals("a long answer", messages.last().content)
        assertFalse("the conversation's thinking setting, which is the next turn's", enableThinking)
    }

    /** The warm-up prefills what the next prompt will be: its system message has every layer the reply's had. */
    @Test
    fun theWarmUpCarriesTheSystemMessageTheReplyWasGeneratedWith() = runBlocking {
        systemPrompts = SystemPrompt(clock = fixedClock)
        globalInstructions.value = "Use metric units."
        loadFakeModel()
        engine.scriptedEvents = finishedReply(WarmUpMinTokens)
        val conversation = chatRepository.createConversation(modelId = null, systemPrompt = "be brief", enableThinking = false)
        val viewModel = newViewModel(conversation)

        viewModel.send("hi")
        awaitGeneration()

        val sent = engine.lastRequest!!.messages.first()
        assertTrue(sent.content.contains("Use metric units.") && sent.content.contains("be brief"))
        assertEquals(sent, engine.warmUps.single().first.first())
    }

    @Test
    fun aReplyThatEndsWhileTheAppIsInTheBackgroundIsNotWarmedUp() = runBlocking {
        loadFakeModel()
        engine.scriptedEvents = finishedReply(WarmUpMinTokens)
        val conversation = chatRepository.createConversation(modelId = null)

        val viewModel = newViewModel(conversation)
        AppForegroundTracker.onActivityStopped()
        viewModel.send("hi")
        awaitGeneration()

        assertTrue(engine.warmUps.isEmpty())
    }

    @Test
    fun aReplyShortOfTheThresholdIsNotWarmedUp() = runBlocking {
        loadFakeModel()
        engine.scriptedEvents = finishedReply(299)
        val conversation = chatRepository.createConversation(modelId = null)

        newViewModel(conversation).send("hi")
        awaitGeneration()

        assertTrue(engine.warmUps.isEmpty())
    }

    @Test
    fun aReplyCutAtItsTokenCapIsWarmedUpButOtherBudgetEndingsAreNot() = runBlocking {
        loadFakeModel()
        val conversation = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversation)

        engine.scriptedEvents = finishedReply(WarmUpMinTokens, StopReason.TokenBudget)
        viewModel.send("one")
        awaitGeneration()
        assertEquals("TokenBudget is a normal ending for this", 1, engine.warmUps.size)

        for (reason in listOf(
            StopReason.ResponseBudgetExhausted,
            StopReason.ReasoningBudgetExhausted,
            StopReason.ContextBudgetClipped,
        )) {
            engine.scriptedEvents = finishedReply(WarmUpMinTokens, reason)
            viewModel.send("again")
            awaitGeneration()
        }
        assertEquals("none of the others add one", 1, engine.warmUps.size)
    }

    @Test
    fun aFailedOrStoppedGenerationIsNotWarmedUp() = runBlocking {
        loadFakeModel()
        val failing = chatRepository.createConversation(modelId = null)
        engine.scriptedEvents = listOf(DecodeEvent.Token("half"), DecodeEvent.Error(InferenceException("boom")))
        newViewModel(failing).send("fails")
        awaitGeneration()

        // Its own conversation: [awaitStreamingReply] would otherwise return on the failed reply's
        // text, before this one has started.
        val stopped = chatRepository.createConversation(modelId = null)
        engine.scriptedEvents = listOf(DecodeEvent.Token("half an answer"))
        engine.hangBeforeTerminal = true
        val viewModel = newViewModel(stopped)
        viewModel.send("stopped")
        awaitStreamingReply(stopped)
        viewModel.stop()
        awaitGeneration()

        assertTrue(engine.warmUps.isEmpty())
    }

    /**
     * The warm-up is background work, so it must not stand between the user and their next message:
     * sending while it runs interrupts it, and the reply is generated as usual.
     */
    @Test
    fun sendingWhileTheWarmUpRunsInterruptsItAndGeneratesNormally() = runBlocking {
        loadFakeModel()
        engine.scriptedEvents = finishedReply(WarmUpMinTokens)
        engine.warmUpHangs = true
        val conversation = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversation)

        viewModel.send("first")
        withTimeout(5_000) { engine.warmUpEntered.await() }
        assertTrue(
            "a warm-up is not a generation: nothing may show or offer to stop one",
            inferenceQueue.state.value is QueueState.ModelReady,
        )

        engine.warmUpHangs = false
        viewModel.send("second")
        withTimeout(5_000) { awaitGeneration() }

        assertEquals("the warm-up was interrupted by the send", 1, engine.warmUpInterrupted)
        val messages = chatRepository.observeMessages(conversation).first()
        assertEquals(listOf("first", "a long answer", "second", "a long answer"), messages.map { it.content })
    }

    @Test
    fun leavingTheConversationInterruptsItsWarmUp() = runBlocking {
        loadFakeModel()
        engine.scriptedEvents = finishedReply(WarmUpMinTokens)
        engine.warmUpHangs = true
        val conversation = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversation)

        viewModel.send("hi")
        withTimeout(5_000) { engine.warmUpEntered.await() }
        viewModel.leave()
        withTimeout(5_000) { awaitGeneration() }

        assertEquals(1, engine.warmUpInterrupted)
    }

    @Test
    fun aDeletedConversationFallsBackToAFreshDraft() = runBlocking {
        loadFakeModel()
        engine.scriptedEvents = finishedReply(10)
        val conversation = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversation)
        viewModel.send("hi")
        withTimeout(5_000) { awaitGeneration() }
        assertEquals(conversation, ActiveSessionTracker.current.value?.first)

        val watcher = launch { viewModel.watchConversation() }
        chatRepository.deleteConversation(conversation)
        withTimeout(5_000) { viewModel.currentConversationId.first { it == null } }

        assertNull("no pointer left on the deleted id", ActiveSessionTracker.current.value)
        assertTrue(viewModel.messages.first().isEmpty())

        viewModel.send("again")
        withTimeout(5_000) { awaitGeneration() }
        val fresh = viewModel.currentConversationId.value
        watcher.cancel()

        assertNotNull(fresh)
        assertNotEquals("a new row, not the deleted one", conversation, fresh)
        assertTrue("nothing written under the deleted id", chatRepository.observeMessages(conversation).first().isEmpty())
    }

    @Test
    fun aModelSwapInterruptsTheWarmUpAndCompletes() = runBlocking {
        loadFakeModel()
        engine.scriptedEvents = finishedReply(WarmUpMinTokens)
        engine.warmUpHangs = true
        val conversation = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversation)

        viewModel.send("hi")
        withTimeout(5_000) { engine.warmUpEntered.await() }
        withTimeout(5_000) { inferenceQueue.unloadModel() }
        withTimeout(5_000) { awaitGeneration() }

        assertEquals(1, engine.warmUpInterrupted)
        assertTrue(inferenceQueue.state.value is QueueState.Idle)
    }

    @Test
    fun theWarmUpThresholdAndStopReasonsAreWhatTheTicketSays() {
        fun stats(tokens: Int, reason: StopReason) = DecodeStats(tokens, 1f, 1L, reason)
        assertEquals(300, WarmUpMinTokens)
        assertTrue(worthWarmingUp(stats(300, StopReason.Complete)))
        assertTrue(worthWarmingUp(stats(2048, StopReason.TokenBudget)))
        assertFalse(worthWarmingUp(stats(299, StopReason.Complete)))
        assertFalse(worthWarmingUp(stats(2048, StopReason.ContextBudgetExhausted)))
    }

    /** In-memory [ChatDao] fake — no Room/Android needed, same convention as `SessionRepositoryTest`'s `FakeSessionDao`. */
    private class FakeChatDao : ChatDao {
        private val conversations = mutableMapOf<String, ConversationEntity>()
        private val messages = mutableMapOf<String, MutableList<MessageEntity>>()
        private val conversationsFlow = MutableStateFlow<List<ConversationEntity>>(emptyList())
        private val messagesFlow = MutableStateFlow<Map<String, List<MessageEntity>>>(emptyMap())

        override fun observeConversations(): Flow<List<ConversationEntity>> = conversationsFlow

        override fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
            messagesFlow.map { it[conversationId].orEmpty() }

        override suspend fun getConversation(id: String): ConversationEntity? = conversations[id]

        override fun observeConversation(id: String): Flow<ConversationEntity?> =
            conversationsFlow.map { list -> list.find { it.id == id } }

        override suspend fun messageCount(conversationId: String): Int = messages[conversationId]?.size ?: 0

        override suspend fun insertConversation(conversation: ConversationEntity) {
            conversations[conversation.id] = conversation
            conversationsFlow.value = conversations.values.sortedByDescending { it.updatedAt }
        }

        override suspend fun updateConversation(conversation: ConversationEntity) {
            conversations[conversation.id] = conversation
            conversationsFlow.value = conversations.values.sortedByDescending { it.updatedAt }
        }

        override suspend fun updateSamplingJson(id: String, samplingJson: String?) = patch(id) { it.copy(samplingJson = samplingJson) }

        override suspend fun updateSystemPrompt(id: String, systemPrompt: String?) = patch(id) { it.copy(systemPrompt = systemPrompt) }

        override suspend fun updateEnableThinking(id: String, enableThinking: Boolean) = patch(id) { it.copy(enableThinking = enableThinking) }

        override suspend fun resetSamplingAndThinking(id: String) = patch(id) { it.copy(samplingJson = null, enableThinking = true) }

        /** One atomic column update, like the SQL it stands in for: no suspension point between read and write. */
        /** A row created earlier than now, which no DAO call does. */
        fun patchCreatedAt(id: String, createdAt: Long) = patch(id) { it.copy(createdAt = createdAt) }

        private fun patch(id: String, change: (ConversationEntity) -> ConversationEntity) {
            val current = conversations[id] ?: return
            conversations[id] = change(current)
            conversationsFlow.value = conversations.values.sortedByDescending { it.updatedAt }
        }

        override suspend fun insertMessage(message: MessageEntity) {
            messages.getOrPut(message.conversationId) { mutableListOf() }.add(message)
            messagesFlow.value = messages.mapValues { it.value.toList() }
        }

        /** Every content `updateMessage` was handed, in order: what the screen could have shown. */
        val writtenContents = mutableListOf<String>()

        override suspend fun updateMessage(
            id: String,
            content: String,
            thinking: String?,
            tokensGenerated: Int?,
            tokPerSec: Float?,
            ttftMs: Long?,
            stopReason: String?,
        ) {
            writtenContents += content
            for (list in messages.values) {
                val index = list.indexOfFirst { it.id == id }
                if (index >= 0) {
                    list[index] = list[index].copy(
                        content = content,
                        thinking = thinking,
                        tokensGenerated = tokensGenerated,
                        tokPerSec = tokPerSec,
                        ttftMs = ttftMs,
                        stopReason = stopReason,
                    )
                    messagesFlow.value = messages.mapValues { it.value.toList() }
                    return
                }
            }
        }

        override suspend fun updateToolsEnabled(id: String, toolsEnabled: Boolean) = patch(id) { it.copy(toolsEnabled = toolsEnabled) }

        val toolCalls = mutableListOf<ToolCallEntity>()
        private val toolCallsFlow = MutableStateFlow<List<ToolCallEntity>>(emptyList())

        override fun observeToolCalls(conversationId: String): Flow<List<ToolCallEntity>> = combine(messagesFlow, toolCallsFlow) { byConversation, rows ->
            val ids = byConversation[conversationId].orEmpty().map { it.id }.toSet()
            rows.filter { it.messageId in ids }
        }

        override suspend fun toolActivity(conversationId: String): List<ToolActivityRow> {
            val ids = messages[conversationId].orEmpty().map { it.id }.toSet()
            return toolCalls.filter { it.messageId in ids }.groupBy { it.tool }.map { (tool, rows) ->
                ToolActivityRow(tool, rows.size, rows.sumOf { it.resultTokens }, rows.sumOf { it.durationMs })
            }.sortedWith(compareByDescending<ToolActivityRow> { it.calls }.thenBy { it.tool })
        }

        override suspend fun updateToolCalls(id: String, toolCallsJson: String?) {
            for (list in messages.values) {
                val index = list.indexOfFirst { it.id == id }
                if (index >= 0) list[index] = list[index].copy(toolCallsJson = toolCallsJson)
            }
            messagesFlow.value = messages.mapValues { it.value.toList() }
        }

        override suspend fun insertToolCall(toolCall: ToolCallEntity) {
            toolCalls += toolCall
            toolCallsFlow.value = toolCalls.toList()
        }

        override suspend fun toolCallCount(messageId: String, callId: String): Int =
            toolCalls.count { it.messageId == messageId && it.callId == callId }

        override suspend fun deleteToolCallsForMessage(messageId: String) {
            toolCalls.removeAll { it.messageId == messageId }
            toolCallsFlow.value = toolCalls.toList()
        }

        override suspend fun deleteToolCallsForConversation(conversationId: String) {
            val ids = messages[conversationId].orEmpty().map { it.id }.toSet()
            toolCalls.removeAll { it.messageId in ids }
            toolCallsFlow.value = toolCalls.toList()
        }

        override suspend fun updateTokenCount(id: String, tokenCount: Int, modelId: String) {
            for (list in messages.values) {
                val index = list.indexOfFirst { it.id == id }
                if (index >= 0) {
                    list[index] = list[index].copy(tokenCount = tokenCount, tokenCountModelId = modelId)
                    messagesFlow.value = messages.mapValues { it.value.toList() }
                    return
                }
            }
        }

        override suspend fun deleteMessagesForConversation(conversationId: String) {
            messages.remove(conversationId)
            messagesFlow.value = messages.mapValues { it.value.toList() }
        }

        override suspend fun deleteMessage(id: String) {
            for (list in messages.values) {
                if (list.removeIf { it.id == id }) break
            }
            messagesFlow.value = messages.mapValues { it.value.toList() }
        }

        override suspend fun deleteConversationById(id: String) {
            conversations.remove(id)
            conversationsFlow.value = conversations.values.sortedByDescending { it.updatedAt }
        }

        override suspend fun conversationCount(): Int = conversations.size
    }

    /** In-memory [ModelDao] fake — mirrors [FakeChatDao]'s conventions. */
    private class FakeModelDao : ModelDao {
        private val models = mutableMapOf<String, ModelEntity>()
        private val flow = MutableStateFlow<List<ModelEntity>>(emptyList())

        /**
         * Ordered `importedAt` descending, like [ModelDao.observeAll]'s own query. Insertion order
         * would do for every other test here, but not for TEN-80: the defect *is* which of two
         * rows sharing a display name that ordering puts first, so a fake answering in insertion
         * order could not reproduce it.
         */
        private fun publish() {
            flow.value = models.values.sortedByDescending { it.importedAt }
        }

        override fun observeAll(): Flow<List<ModelEntity>> = flow

        override suspend fun getById(id: String): ModelEntity? = models[id]

        override suspend fun setContextOverride(id: String, contextOverride: String?) = Unit

        override suspend fun setChatTemplateOverride(id: String, chatTemplateOverride: String?) = Unit

        override suspend fun withStaleKvCacheShape(kvShapeVersion: Long): List<ModelEntity> =
            models.values.filter { it.kvShapeVersion != kvShapeVersion }

        override suspend fun updateKvCacheShape(
            id: String,
            kvFullElementsPerToken: Long,
            kvSwaElementsPerToken: Long,
            slidingWindow: Long,
            kvShapeVersion: Long,
            supportsThinking: Boolean,
            supportsTools: Boolean,
            hasChatTemplate: Boolean,
        ) {
            val existing = models[id] ?: return
            models[id] = existing.copy(
                kvFullElementsPerToken = kvFullElementsPerToken,
                kvSwaElementsPerToken = kvSwaElementsPerToken,
                slidingWindow = slidingWindow,
                kvShapeVersion = kvShapeVersion,
            )
            publish()
        }

        override suspend fun insert(model: ModelEntity) {
            models[model.id] = model
            publish()
        }

        override suspend fun delete(model: ModelEntity) {
            models.remove(model.id)
            publish()
        }
    }

    /** In-memory [SessionDao] fake — same shape as `SessionRepositoryTest`'s own private copy. */
    private class FakeSessionDao : SessionDao {
        private val rows = mutableMapOf<String, SessionEntity>()
        private val flow = MutableStateFlow<List<SessionEntity>>(emptyList())

        override suspend fun getByConversation(conversationId: String): SessionEntity? = rows[conversationId]

        override fun observeAll(): Flow<List<SessionEntity>> = flow

        override suspend fun allSessions(): List<SessionEntity> = rows.values.toList()

        override suspend fun upsert(session: SessionEntity) {
            rows[session.conversationId] = session
            flow.value = rows.values.toList()
        }

        override suspend fun delete(conversationId: String) {
            rows.remove(conversationId)
            flow.value = rows.values.toList()
        }

        override suspend fun deleteAll() {
            rows.clear()
            flow.value = emptyList()
        }
    }

    @Test
    fun theContextRingReadsNoModelUntilOneIsLoaded() = runBlocking {
        val viewModel = newViewModel(chatRepository.createConversation(modelId = null))
        assertEquals(ContextUsageState.NoModel, viewModel.contextUsage.value)

        loadFakeModel()
        viewModel.refreshContextUsage()

        assertEquals(ContextUsageState.Loaded(used = 0, length = NCTX), viewModel.contextUsage.value)
    }

    @Test
    fun theContextRingIsRefreshedWhenAGenerationEnds() = runBlocking {
        loadFakeModel()
        val conversationId = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversationId)

        viewModel.send("hi")
        awaitAssistantReply(conversationId, expectedCount = 2)

        assertEquals(ContextUsageState.Loaded(used = TOKENS_PER_TURN, length = NCTX), viewModel.contextUsage.value)
    }

    @Test
    fun aConversationDoesNotShowTheCacheAnotherOneOwns() = runBlocking {
        loadFakeModel()
        val conversationA = chatRepository.createConversation(modelId = null)
        val conversationB = chatRepository.createConversation(modelId = null)
        newViewModel(conversationA).send("hi from A")
        awaitAssistantReply(conversationA, expectedCount = 2)

        val viewModelB = newViewModel(conversationB)

        assertEquals(ContextUsageState.Loaded(used = 0, length = NCTX), viewModelB.contextUsage.value)
    }

    private suspend fun ChatViewModel.openSheet(): ContextSheetState {
        openContextSheet()
        awaitGeneration()
        return checkNotNull(contextSheet.value) { "the sheet was not built" }
    }

    @Test
    fun theContextSheetHasNoActivityWhenNoToolRan() = runBlocking {
        loadFakeModel()
        val conversationId = chatRepository.createConversation(modelId = null, systemPrompt = null)
        val viewModel = newViewModel(conversationId)
        viewModel.send("hello")
        awaitAssistantReply(conversationId, expectedCount = 2)

        assertTrue(viewModel.openSheet().activity.isEmpty())
    }

    @Test
    fun theContextSheetSumsEachToolsCallsOverEveryStatus() = runBlocking {
        loadFakeModel()
        val conversationId = chatRepository.createConversation(modelId = null, systemPrompt = null)
        val viewModel = newViewModel(conversationId)
        viewModel.send("hello")
        awaitAssistantReply(conversationId, expectedCount = 2)
        val assistantId = chatRepository.observeMessages(conversationId).first().last().id
        fun outcome(id: String, tool: String, status: ToolCallStatus, durationMs: Long, tokens: Int) = ToolCallOutcome(
            call = ToolCall(id, tool, "{}"), tier = null, status = status, startedAt = 0, durationMs = durationMs,
            output = "x", resultTokens = tokens, destination = null,
        )
        chatRepository.recordToolCall(conversationId, assistantId, outcome("1", "datetime", ToolCallStatus.Ok, 40, 12))
        chatRepository.recordToolCall(conversationId, assistantId, outcome("2", "datetime", ToolCallStatus.Error, 60, 8))
        chatRepository.recordToolCall(conversationId, assistantId, outcome("3", "ask_user", ToolCallStatus.Refused, 0, 3))

        val activity = viewModel.openSheet().activity

        assertEquals(
            listOf(ToolActivityRow("datetime", 2, 20, 100), ToolActivityRow("ask_user", 1, 3, 0)),
            activity,
        )
    }

    @Test
    fun theContextSheetIsBuiltWhenItOpensAndItsCountsAreKept() = runBlocking {
        loadFakeModel()
        val conversationId = chatRepository.createConversation(modelId = null, systemPrompt = "be brief")
        val viewModel = newViewModel(conversationId)
        viewModel.send("hello")
        awaitAssistantReply(conversationId, expectedCount = 2)
        assertNull(viewModel.contextSheet.value)

        val state = viewModel.openSheet()

        assertTrue(viewModel.contextSheetOpen.value)
        val breakdown = state.breakdown
        assertEquals(NCTX, breakdown.length)
        // One token per character in the fake: "be brief", "hello" and the reply's "hi".
        val tokens = breakdown.rows.associate { it.part to it.tokens }
        assertEquals(8, tokens[ContextPart.System])
        assertEquals(5, tokens[ContextPart.User])
        assertEquals(2, tokens[ContextPart.Assistant])
        // The cache holds 10, the counted parts 15: not this conversation's cache yet, so an estimate.
        assertTrue(breakdown.estimate)
        assertEquals(15, breakdown.used)
        val stored = chatRepository.observeMessages(conversationId).first()
        assertEquals(listOf(5, 2), stored.map { it.tokenCount })
        assertEquals(listOf(MODEL_ID, MODEL_ID), stored.map { it.tokenCountModelId })
        val runtime = state.runtime as ContextRuntime.Loaded
        assertEquals("$MODEL_NAME · F16", runtime.model)
        assertEquals(NCTX, runtime.nCtx)

        viewModel.closeContextSheet()
        assertFalse(viewModel.contextSheetOpen.value)
        assertNull(viewModel.contextSheet.value)
    }

    @Test
    fun theSheetSplitsTheCacheIntoPartsAndOverhead() = runBlocking {
        loadFakeModel()
        val conversationId = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversationId)
        engine.tokenizer = { 1 }
        viewModel.send("hello")
        awaitAssistantReply(conversationId, expectedCount = 2)

        val breakdown = viewModel.openSheet().breakdown

        assertFalse(breakdown.estimate)
        val tokens = breakdown.rows.associate { it.part to it.tokens }
        assertEquals(1, tokens[ContextPart.User])
        assertEquals(1, tokens[ContextPart.Assistant])
        assertEquals(TOKENS_PER_TURN - 2, tokens[ContextPart.Overhead])
        assertEquals(16, tokens[ContextPart.Reserved])
        assertEquals(NCTX, breakdown.rows.sumOf { it.tokens })
        assertNull("reasoning is not replayed into the prompt", tokens[ContextPart.Thinking])
    }

    /** TEN-189: the saved facts lead the first user message of every turn, and never reach what is stored. */
    @Test
    fun theSavedFactsLeadTheFirstUserMessageOfEveryTurn() = runBlocking {
        memoryDao.insert(MemoryFactEntity(text = "copy every calculation result to the clipboard", createdAt = 1))
        loadFakeModel()
        val conversationId = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversationId)
        val block = "${MemoryContext.HEADER}\n- copy every calculation result to the clipboard\n[End of memory]"

        viewModel.send("2+2")
        awaitAssistantReply(conversationId, expectedCount = 2)
        assertEquals("$block\n\n2+2", engine.lastRequest!!.messages.first().content)

        // A fact saved meanwhile does not rewrite the message the cache already holds.
        memoryDao.insert(MemoryFactEntity(text = "likes tea", createdAt = 2))
        viewModel.send("and 3+3")
        awaitAssistantReply(conversationId, expectedCount = 4)
        val sent = engine.lastRequest!!.messages
        assertEquals("$block\n\n2+2", sent.first().content)
        assertEquals("and 3+3", sent.last().content)
        assertEquals(listOf("2+2", "hi", "and 3+3", "hi"), chatRepository.observeMessages(conversationId).first().map { it.content })
    }

    @Test
    fun noFactsInThePromptWhenMemoryIsOffOrEmpty() = runBlocking {
        loadFakeModel()
        val empty = chatRepository.createConversation(modelId = null)
        newViewModel(empty).send("hello")
        awaitAssistantReply(empty, expectedCount = 2)
        assertEquals("hello", engine.lastRequest!!.messages.first().content)

        memoryDao.insert(MemoryFactEntity(text = "likes tea", createdAt = 1))
        memoryEnabled = false
        val off = chatRepository.createConversation(modelId = null)
        newViewModel(off).send("hello")
        awaitAssistantReply(off, expectedCount = 2)
        assertEquals("hello", engine.lastRequest!!.messages.first().content)
    }

    @Test
    fun theSheetCountsTheFactsAsTheirOwnPart() = runBlocking {
        memoryDao.insert(MemoryFactEntity(text = "likes tea", createdAt = 1))
        loadFakeModel()
        val conversationId = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversationId)
        engine.tokenizer = { 1 }
        viewModel.send("hello")
        awaitAssistantReply(conversationId, expectedCount = 2)

        val tokens = viewModel.openSheet().breakdown.rows.associate { it.part to it.tokens }

        assertEquals(1, tokens[ContextPart.Memory])
        assertEquals(1, tokens[ContextPart.User])
    }

    /**
     * TEN-190: a sheet opened while the reply is generating cannot count (the engine is busy), and used
     * to read the tool definitions of the cache's shared prefix as template overhead. The counts made
     * before the decode answer instead, from another screen too.
     */
    @Test
    fun toolDefinitionsAreNotTemplateOverheadWhileTheFirstReplyGenerates() = runBlocking {
        agentRuntime = AgentRuntime(ToolRegistry(listOf(DateTimeTool())))
        loadFakeModel(supportsTools = true)
        engine.tokenizer = { 1 }
        engine.scriptedEvents = listOf(DecodeEvent.Token("hi"))
        engine.hangBeforeTerminal = true
        val conversationId = chatRepository.createConversation(modelId = null)
        newViewModel(conversationId).send("what time is it?")
        awaitGenerating()

        val other = newViewModel(conversationId)
        other.openContextSheet()
        val breakdown = withTimeout(5_000) { other.contextSheet.first { it != null } }!!.breakdown

        val tokens = breakdown.rows.associate { it.part to it.tokens }
        assertEquals(1, tokens[ContextPart.ToolDefinitions])
        assertEquals(TOKENS_PER_TURN - 1, tokens[ContextPart.Overhead])
        other.stop()
        awaitGeneration()
    }

    @Test
    fun theCountsAreInvalidatedWhenTheModelChanges() = runBlocking {
        loadFakeModel()
        val conversationId = chatRepository.createConversation(modelId = null)
        val viewModel = newViewModel(conversationId)
        viewModel.send("hello")
        awaitAssistantReply(conversationId, expectedCount = 2)
        viewModel.openSheet()
        viewModel.closeContextSheet()
        assertEquals(listOf(MODEL_ID, MODEL_ID), chatRepository.observeMessages(conversationId).first().map { it.tokenCountModelId })

        // Another tokenizer, another model.
        engine.tokenizer = { it.length * 2 }
        importModel(id = OLDER_MODEL_ID, fileName = "older.gguf", importedAt = -1L)
        loadImportedModel(OLDER_MODEL_ID)
        viewModel.openSheet()

        val stored = chatRepository.observeMessages(conversationId).first()
        assertEquals(listOf(10, 4), stored.map { it.tokenCount })
        assertEquals(listOf(OLDER_MODEL_ID, OLDER_MODEL_ID), stored.map { it.tokenCountModelId })
    }

    @Test
    fun withoutAModelTheSheetIsAnEstimateFromTheStoredCounts() = runBlocking {
        loadFakeModel()
        val conversationId = chatRepository.createConversation(modelId = MODEL_ID)
        val viewModel = newViewModel(conversationId)
        engine.tokenizer = { 3 }
        viewModel.send("hello")
        awaitAssistantReply(conversationId, expectedCount = 2)
        viewModel.openSheet()
        viewModel.closeContextSheet()

        inferenceQueue.unloadModel()
        val state = viewModel.openSheet()

        assertEquals(ContextRuntime.NoModel(MODEL_ID), state.runtime)
        val breakdown = state.breakdown
        assertTrue(breakdown.estimate)
        assertEquals(NCTX, breakdown.length)
        val tokens = breakdown.rows.associate { it.part to it.tokens }
        assertEquals(3, tokens[ContextPart.User])
        assertEquals(3, tokens[ContextPart.Assistant])
        // The overhead measured while the model was there.
        assertEquals(TOKENS_PER_TURN - 6, tokens[ContextPart.Overhead])
        assertEquals(TOKENS_PER_TURN, breakdown.used)
    }

    /**
     * A [LlamaKtEngine] fake whose [kvCacheUsedCells] grows with every completed [decode] — lets
     * tests drive [io.github.tensaiapp.runtime.InferenceQueue.kvUsedPercent] from empty to
     * non-empty exactly the way a real generation would, without any native code. [saveShouldFail]
     * lets a test force [SaveResult.Failed][io.github.tensaiapp.data.session.SaveResult.Failed]
     * on demand, and [scriptedEvents] lets one dictate the exact [DecodeEvent] stream a decode
     * produces (a `DecodeEvent.Done` is appended unless the script already ends on a terminal event
     * of its own, so token accounting stays consistent with the default reply while a script can
     * still end on a `DecodeEvent.Error`: `LoadedModel.decode`'s contract is exactly one terminal
     * event, not two). [hangBeforeTerminal] holds a decode open just short of that terminal event,
     * so a test can stop a generation that would otherwise have finished instantly.
     */
    private class FakeLlamaKtEngine : LlamaKtEngine {
        private var usedCells = 0
        var saveShouldFail = false
        var scriptedEvents: List<DecodeEvent>? = null

        /** One scripted stream per decode, in order, taken before [scriptedEvents]: an agent turn decodes several times. */
        val scriptQueue = ArrayDeque<List<DecodeEvent>>()
        var hangBeforeTerminal = false

        /** What [LoadedModel.tokenize] answers, one token per character by default. */
        var tokenizer: (String) -> Int = { it.length }

        /**
         * How many times a session file was actually handed to the engine — the only way to tell a
         * restore that was declined upstream from one that went through, since [loadSession] itself
         * always succeeds here. TEN-80 asserts on it being zero.
         */
        var loadSessionCalls = 0
            private set

        /** The last [DecodeRequest] handed to [LoadedModel.decode] — TEN-35 asserts on its sampling. */
        var lastRequest: DecodeRequest? = null

        /** Every [DecodeRequest] handed to [LoadedModel.decode], in order. */
        val requests = mutableListOf<DecodeRequest>()

        /** TEN-88: every [LoadedModel.warmUp] the engine was asked for, with its thinking flag. */
        val warmUps = mutableListOf<Pair<List<ChatMessage>, Boolean>>()

        /** Completed when a warm-up is inside the engine; reassign for each wait. */
        var warmUpEntered = CompletableDeferred<Unit>()

        /** Holds a warm-up open until it is cancelled, the only way to interrupt one that is running. */
        var warmUpHangs = false

        /** How many warm-ups ended by being cancelled while they ran. */
        var warmUpInterrupted = 0
            private set

        /** TEN-205: every [LoadedModel.warmToolPrefix] the engine was asked for, with its `replaceResident`. */
        val toolPrefixWarmUps = mutableListOf<Pair<DecodeRequest, Boolean>>()

        /** Completed when a tool prefix warm-up is inside the engine; reassign for each wait. */
        var toolPrefixEntered = CompletableDeferred<Unit>()

        /** Holds a tool prefix warm-up open half way through the prefix until it is cancelled. */
        var toolPrefixHangs = false

        var toolPrefixInterrupted = 0
            private set

        /**
         * What the cache held when each decode that carried tools began: the prefix a real engine would not
         * have to prefill again is [PREFIX_TOKENS] minus this.
         */
        val residentAtToolDecode = mutableListOf<Int>()

        override suspend fun loadModel(config: ModelLoadConfig): LoadedModel = object : LoadedModel {
            // A load gets a fresh llama.cpp context, so the cache it reports starts empty — which
            // is what makes `ChatViewModel`'s cold-start restore path reachable after a model swap.
            init {
                usedCells = 0
            }

            override val metadata = LoadedModelInfo(
                name = MODEL_NAME,
                nCtxEffective = config.nCtx,
                backend = "CPU",
                kvCacheType = config.kvCacheType,
                modelId = config.modelId,
            )

            override suspend fun decode(request: DecodeRequest): Flow<DecodeEvent> = flow {
                lastRequest = request
                requests += request
                if (request.tools.isNotEmpty()) residentAtToolDecode += usedCells
                val scripted = scriptQueue.removeFirstOrNull() ?: scriptedEvents
                (scripted ?: listOf(DecodeEvent.Token("hi"))).forEach { emit(it) }
                usedCells += TOKENS_PER_TURN
                if (hangBeforeTerminal) awaitCancellation()
                val endsOnItsOwnTerminal = scripted?.lastOrNull().let { it is DecodeEvent.Done || it is DecodeEvent.Error }
                if (!endsOnItsOwnTerminal) {
                    emit(DecodeEvent.Done(DecodeStats(tokensGenerated = TOKENS_PER_TURN, tokPerSec = 1f, ttftMs = 1L)))
                }
            }

            override suspend fun warmUp(messages: List<ChatMessage>, enableThinking: Boolean): Boolean {
                warmUps += messages to enableThinking
                warmUpEntered.complete(Unit)
                if (warmUpHangs) {
                    try {
                        awaitCancellation()
                    } catch (e: CancellationException) {
                        warmUpInterrupted++
                        throw e
                    }
                }
                return true
            }

            override suspend fun warmToolPrefix(request: DecodeRequest, replaceResident: Boolean): Boolean {
                toolPrefixWarmUps += request to replaceResident
                toolPrefixEntered.complete(Unit)
                if (toolPrefixHangs) {
                    usedCells = PREFIX_TOKENS / 2
                    try {
                        awaitCancellation()
                    } catch (e: CancellationException) {
                        toolPrefixInterrupted++
                        throw e
                    }
                }
                usedCells = PREFIX_TOKENS
                return true
            }

            override suspend fun tokenize(text: String): Int = tokenizer(text)

            override suspend fun free() = Unit
        }

        override fun kvCacheUsedCells(): Int = usedCells

        /** Runs inside every [saveSession], once the state is written: what happens while a save is in flight. */
        var duringSave: (() -> Unit)? = null

        override suspend fun saveSession(path: String): Int {
            if (saveShouldFail) throw RuntimeException("save failed (test)")
            File(path).writeText("state")
            duringSave?.invoke()
            return usedCells
        }

        override suspend fun loadSession(path: String): Boolean {
            loadSessionCalls++
            return true
        }

        override suspend fun readMetadata(path: String): GgufInfo =
            throw UnsupportedOperationException("not needed by these tests")
    }

    private companion object {
        const val MODEL_ID = "model-1"

        /** A second import of the same model at a different quantisation — same [MODEL_NAME], older. */
        const val OLDER_MODEL_ID = "model-0"
        const val MODEL_NAME = "fake-model"
        const val NCTX = 100
        const val TOKENS_PER_TURN = 10

        /** Tokens in the fake's whole tool prefix (TEN-205). */
        const val PREFIX_TOKENS = 40
    }
}
