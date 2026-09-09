package io.github.hokanosekai.tensai.ui.screens.chat

import android.content.ContextWrapper
import io.github.hokanosekai.tensai.data.chat.ChatDao
import io.github.hokanosekai.tensai.data.chat.ChatRepository
import io.github.hokanosekai.tensai.data.chat.ConversationEntity
import io.github.hokanosekai.tensai.data.chat.MessageEntity
import io.github.hokanosekai.tensai.data.chat.ThinkingStreamParser
import io.github.hokanosekai.tensai.data.models.ModelDao
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.data.models.ModelRepository
import io.github.hokanosekai.tensai.data.session.SessionDao
import io.github.hokanosekai.tensai.data.session.SessionEntity
import io.github.hokanosekai.tensai.data.session.SessionRepository
import io.github.hokanosekai.tensai.engine.DecodeEvent
import io.github.hokanosekai.tensai.engine.DecodeRequest
import io.github.hokanosekai.tensai.engine.DecodeStats
import io.github.hokanosekai.tensai.engine.GgufInfo
import io.github.hokanosekai.tensai.engine.InferenceException
import io.github.hokanosekai.tensai.engine.LlamaKtEngine
import io.github.hokanosekai.tensai.engine.LoadedModel
import io.github.hokanosekai.tensai.engine.LoadedModelInfo
import io.github.hokanosekai.tensai.engine.MessageRole
import io.github.hokanosekai.tensai.engine.ModelLoadConfig
import io.github.hokanosekai.tensai.engine.SamplingConfig
import io.github.hokanosekai.tensai.engine.StopReason
import io.github.hokanosekai.tensai.runtime.ActiveSessionTracker
import io.github.hokanosekai.tensai.runtime.InferenceQueue
import io.github.hokanosekai.tensai.runtime.QueueState
import io.github.hokanosekai.tensai.runtime.residentModelId
import io.github.hokanosekai.tensai.ui.TechnicalJargon
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        chatRepository = ChatRepository(chatDao)
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
    }

    @After
    fun tearDown() {
        // Cancelled rather than joined: anything still running is dispatched on the event loop of
        // the `runBlocking` that has just returned, so nothing is left to resume it and a join here
        // would wait for good. Every test that has something to wait for has already waited for it
        // — see [awaitGeneration].
        viewModelJob.cancel()
        storageRoot.deleteRecursively()
        ActiveSessionTracker.clear()
    }

    /**
     * Launches on the test's own thread — [runBlocking]'s event loop, which is what keeps the
     * in-memory fakes below confined to one thread and free of locking — but under [viewModelJob]
     * rather than the receiver's job, so [awaitGeneration] has something to join that holds the
     * generations and nothing else.
     */
    private fun CoroutineScope.newViewModel(conversationId: String) = ChatViewModel(
        conversationId = conversationId,
        chatRepository = chatRepository,
        inferenceQueue = inferenceQueue,
        modelRepository = modelRepository,
        sessionRepository = sessionRepository,
        scope = CoroutineScope(this.coroutineContext + viewModelJob),
    )

    private suspend fun loadFakeModel(supportsThinking: Boolean = false) {
        importModel(supportsThinking = supportsThinking)
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
     * with its stats and its [io.github.hokanosekai.tensai.engine.StopReason], and then
     * [ChatViewModel.error]. So joining them is the generation being *finished*, including the
     * parts that happen after [InferenceQueue.state] has already dropped back to
     * [QueueState.ModelReady] — and every assertion that follows one can be a single read.
     */
    private suspend fun awaitGeneration() {
        viewModelJob.children.toList().forEach { it.join() }
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
     * of one model planned by the same deterministic [io.github.hokanosekai.tensai.runtime.LoadPolicy]
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
     * navigating to Home and back makes a new one while the generation, which runs on the
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

        override suspend fun insertMessage(message: MessageEntity) {
            messages.getOrPut(message.conversationId) { mutableListOf() }.add(message)
            messagesFlow.value = messages.mapValues { it.value.toList() }
        }

        override suspend fun updateMessage(
            id: String,
            content: String,
            thinking: String?,
            tokensGenerated: Int?,
            tokPerSec: Float?,
            ttftMs: Long?,
            stopReason: String?,
        ) {
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

        override suspend fun withStaleKvCacheShape(kvShapeVersion: Long): List<ModelEntity> =
            models.values.filter { it.kvShapeVersion != kvShapeVersion }

        override suspend fun updateKvCacheShape(
            id: String,
            kvFullElementsPerToken: Long,
            kvSwaElementsPerToken: Long,
            slidingWindow: Long,
            kvShapeVersion: Long,
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

    /**
     * A [LlamaKtEngine] fake whose [kvCacheUsedCells] grows with every completed [decode] — lets
     * tests drive [io.github.hokanosekai.tensai.runtime.InferenceQueue.kvUsedPercent] from empty to
     * non-empty exactly the way a real generation would, without any native code. [saveShouldFail]
     * lets a test force [SaveResult.Failed][io.github.hokanosekai.tensai.data.session.SaveResult.Failed]
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
        var hangBeforeTerminal = false

        /**
         * How many times a session file was actually handed to the engine — the only way to tell a
         * restore that was declined upstream from one that went through, since [loadSession] itself
         * always succeeds here. TEN-80 asserts on it being zero.
         */
        var loadSessionCalls = 0
            private set

        /** The last [DecodeRequest] handed to [LoadedModel.decode] — TEN-35 asserts on its sampling. */
        var lastRequest: DecodeRequest? = null

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
                val scripted = scriptedEvents
                (scripted ?: listOf(DecodeEvent.Token("hi"))).forEach { emit(it) }
                usedCells += TOKENS_PER_TURN
                if (hangBeforeTerminal) awaitCancellation()
                val endsOnItsOwnTerminal = scripted?.lastOrNull().let { it is DecodeEvent.Done || it is DecodeEvent.Error }
                if (!endsOnItsOwnTerminal) {
                    emit(DecodeEvent.Done(DecodeStats(tokensGenerated = TOKENS_PER_TURN, tokPerSec = 1f, ttftMs = 1L)))
                }
            }

            override suspend fun tokenize(text: String): Int = 0

            override suspend fun free() = Unit
        }

        override fun kvCacheUsedCells(): Int = usedCells

        override suspend fun saveSession(path: String): Int {
            if (saveShouldFail) throw RuntimeException("save failed (test)")
            File(path).writeText("state")
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
    }
}
