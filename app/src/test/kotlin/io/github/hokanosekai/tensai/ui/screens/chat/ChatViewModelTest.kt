package io.github.hokanosekai.tensai.ui.screens.chat

import android.content.ContextWrapper
import io.github.hokanosekai.tensai.data.chat.ChatDao
import io.github.hokanosekai.tensai.data.chat.ChatRepository
import io.github.hokanosekai.tensai.data.chat.ConversationEntity
import io.github.hokanosekai.tensai.data.chat.MessageEntity
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
import io.github.hokanosekai.tensai.engine.LlamaKtEngine
import io.github.hokanosekai.tensai.engine.LoadedModel
import io.github.hokanosekai.tensai.engine.LoadedModelInfo
import io.github.hokanosekai.tensai.engine.ModelLoadConfig
import io.github.hokanosekai.tensai.runtime.ActiveSessionTracker
import io.github.hokanosekai.tensai.runtime.InferenceQueue
import io.github.hokanosekai.tensai.runtime.QueueState
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Before
    fun setUp() {
        storageRoot = Files.createTempDirectory("tensai-chatviewmodel-test").toFile()
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
            currentLoadedModelName = { (inferenceQueue.state.value as? QueueState.ModelReady)?.info?.name },
            storageRoot = storageRoot,
        )
        sessionRepository = SessionRepository(
            dao = sessionDao,
            storageRoot = storageRoot,
            restoreOnEngine = { path -> inferenceQueue.restoreSession(path) },
            currentTokenCount = { inferenceQueue.currentKvTokenCount() },
        )
        // ActiveSessionTracker is process-wide, mutable state — clear it so no leftover value from
        // a previous test (or from ChatViewModel's real usage elsewhere) leaks into this one.
        ActiveSessionTracker.clear()
    }

    @After
    fun tearDown() {
        storageRoot.deleteRecursively()
        ActiveSessionTracker.clear()
    }

    private fun CoroutineScope.newViewModel(conversationId: String) = ChatViewModel(
        conversationId = conversationId,
        chatRepository = chatRepository,
        inferenceQueue = inferenceQueue,
        modelRepository = modelRepository,
        sessionRepository = sessionRepository,
        scope = this,
    )

    private suspend fun loadFakeModel() {
        inferenceQueue.loadModel(ModelLoadConfig(path = "/models/fake.gguf", nCtx = NCTX))
        modelDao.insert(
            ModelEntity(
                id = MODEL_ID,
                displayName = MODEL_NAME,
                fileName = "fake.gguf",
                filePath = "/models/fake.gguf",
                sizeBytes = 1L,
                architecture = "test",
                quantLabel = "F16",
                paramCount = 1L,
                contextLength = NCTX.toLong(),
                embeddingLength = 1L,
                blockCount = 1L,
                supportsThinking = false,
                importedAt = 0L,
            ),
        )
    }

    /** Polls (like the `androidTest` suite's own tests) until [conversationId] has [expectedCount] messages and the last one has finished generating. */
    private suspend fun waitForAssistantReply(conversationId: String, expectedCount: Int): List<MessageEntity> =
        withTimeout(5_000) {
            var current = chatRepository.observeMessages(conversationId).first()
            while (current.size < expectedCount || current.last().tokensGenerated == null) {
                delay(10)
                current = chatRepository.observeMessages(conversationId).first()
            }
            current
        }

    @Test
    fun switchingToADifferentConversationAgainstTheSameModelSavesOutgoingAndAttemptsToRestoreIncoming() = runBlocking {
        loadFakeModel()
        val conversationA = chatRepository.createConversation(modelId = null)
        val conversationB = chatRepository.createConversation(modelId = null)

        val viewModelA = newViewModel(conversationA)
        viewModelA.send("hi from A")
        waitForAssistantReply(conversationA, expectedCount = 2)

        assertNull("no switch has happened yet, nothing should be saved", sessionDao.getByConversation(conversationA))

        val viewModelB = newViewModel(conversationB)
        viewModelB.send("hi from B")
        waitForAssistantReply(conversationB, expectedCount = 2)

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

    @Test
    fun aSecondTurnInTheSameConversationTriggersNeitherASaveNorARestore() = runBlocking {
        loadFakeModel()
        val conversationA = chatRepository.createConversation(modelId = null)
        val viewModelA = newViewModel(conversationA)

        viewModelA.send("first turn")
        waitForAssistantReply(conversationA, expectedCount = 2)
        viewModelA.send("second turn")
        waitForAssistantReply(conversationA, expectedCount = 4)

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
        waitForAssistantReply(conversationA, expectedCount = 2)

        engine.saveShouldFail = true
        val viewModelB = newViewModel(conversationB)
        viewModelB.send("hi from B")
        val messagesB = waitForAssistantReply(conversationB, expectedCount = 2)

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
        val reply = waitForAssistantReply(conversation, expectedCount = 2).last()

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
        val reply = waitForAssistantReply(conversation, expectedCount = 2).last()

        assertEquals("the answer", reply.content)
        assertEquals("step one", reply.thinking)
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

        override fun observeAll(): Flow<List<ModelEntity>> = flow

        override suspend fun getById(id: String): ModelEntity? = models[id]

        override suspend fun insert(model: ModelEntity) {
            models[model.id] = model
            flow.value = models.values.toList()
        }

        override suspend fun delete(model: ModelEntity) {
            models.remove(model.id)
            flow.value = models.values.toList()
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
     * produces (the `DecodeEvent.Done` is still appended automatically, so token accounting stays
     * consistent with the default reply).
     */
    private class FakeLlamaKtEngine : LlamaKtEngine {
        private var usedCells = 0
        var saveShouldFail = false
        var scriptedEvents: List<DecodeEvent>? = null

        override suspend fun loadModel(config: ModelLoadConfig): LoadedModel = object : LoadedModel {
            override val metadata = LoadedModelInfo(
                name = MODEL_NAME,
                nCtxEffective = config.nCtx,
                backend = "CPU",
                kvCacheType = config.kvCacheType,
            )

            override suspend fun decode(request: DecodeRequest): Flow<DecodeEvent> = flow {
                (scriptedEvents ?: listOf(DecodeEvent.Token("hi"))).forEach { emit(it) }
                usedCells += TOKENS_PER_TURN
                emit(DecodeEvent.Done(DecodeStats(tokensGenerated = TOKENS_PER_TURN, tokPerSec = 1f, ttftMs = 1L)))
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

        override suspend fun loadSession(path: String): Boolean = true

        override suspend fun readMetadata(path: String): GgufInfo =
            throw UnsupportedOperationException("not needed by these tests")
    }

    private companion object {
        const val MODEL_ID = "model-1"
        const val MODEL_NAME = "fake-model"
        const val NCTX = 100
        const val TOKENS_PER_TURN = 10
    }
}
