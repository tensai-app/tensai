package io.github.hokanosekai.tensai.ui.screens

import io.github.hokanosekai.tensai.data.chat.ChatDao
import io.github.hokanosekai.tensai.data.chat.ChatRepository
import io.github.hokanosekai.tensai.data.chat.ConversationEntity
import io.github.hokanosekai.tensai.data.chat.MessageEntity
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.engine.KvCacheType
import io.github.hokanosekai.tensai.engine.LoadedModelInfo
import io.github.hokanosekai.tensai.runtime.QueueState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [HomeViewModel]'s two jobs, without a device: what the pill state resolves to once the queue and
 * the model list are wired to it, and what sending from Home actually does before it navigates.
 *
 * The state matrix itself lives in `HomePillTest` — what is proven here is the wiring around it
 * (that a generation still reads as "a model is loaded") and the send path's ordering: the
 * conversation exists, and the id handed to navigation is that conversation's.
 */
class HomeViewModelTest {

    private lateinit var chatDao: FakeChatDao
    private lateinit var chatRepository: ChatRepository

    @Before
    fun setUp() {
        chatDao = FakeChatDao()
        chatRepository = ChatRepository(chatDao)
    }

    private fun CoroutineScope.newViewModel(
        queueState: Flow<QueueState> = flowOf(QueueState.Idle),
        models: Flow<List<ModelEntity>> = flowOf(emptyList()),
        fileExists: (String) -> Boolean = { true },
    ) = HomeViewModel(
        scope = this,
        chatRepository = chatRepository,
        queueState = queueState,
        models = models,
        fileExists = fileExists,
    )

    @Test
    fun anEmptyLibraryAndAnIdleQueueMakeThePillACallToAction() = runBlocking {
        val state = newViewModel().pillState.first()

        assertEquals(HomePillState.NoModel, state)
    }

    @Test
    fun aModelOnDiskButNothingLoadedOffersTheLoad() = runBlocking {
        val state = newViewModel(models = flowOf(listOf(model("a")))).pillState.first()

        assertEquals(HomePillState.LoadNeeded(modelId = "a", displayName = "a"), state)
    }

    @Test
    fun aGenerationStillCountsAsAModelBeingLoaded() = runBlocking {
        // A model stays resident across a generation (see `residentModel`, TEN-46/TEN-58). Reading
        // only ModelReady here would flip Home back to "load a model" for the whole duration of a
        // reply running in some other conversation.
        val state = newViewModel(queueState = flowOf(QueueState.Generating(loadedInfo()))).pillState.first()

        assertEquals(HomePillState.Ready("Qwen3-4B"), state)
    }

    @Test
    fun aLoadInProgressReadsAsNoModelYet() = runBlocking {
        val state = newViewModel(
            queueState = flowOf(QueueState.Loading(progress = 0.4f)),
            models = flowOf(listOf(model("a"))),
        ).pillState.first()

        assertEquals(
            "Loading carries no resident model, so Home offers the load it is already doing — " +
                "transient, and tapping it just returns to the loading screen",
            HomePillState.LoadNeeded(modelId = "a", displayName = "a"),
            state,
        )
    }

    @Test
    fun thePillFollowsTheQueueAsAModelFinishesLoading() = runBlocking {
        val queueState = MutableStateFlow<QueueState>(QueueState.Idle)
        val viewModel = newViewModel(queueState = queueState, models = flowOf(listOf(model("a"))))

        assertEquals(HomePillState.LoadNeeded(modelId = "a", displayName = "a"), viewModel.pillState.first())

        queueState.value = QueueState.ModelReady(loadedInfo())

        assertEquals(HomePillState.Ready("Qwen3-4B"), viewModel.pillState.first())
    }

    @Test
    fun sendingFromHomeCreatesTheConversationItNavigatesTo() {
        var navigatedTo: String? = null
        var carriedPrompt: String? = null

        // runBlocking joins the coroutine `start` launched before it returns, so the assertions
        // below see the finished state rather than racing it.
        runBlocking {
            newViewModel().start("  Summarize a log  ") { conversationId, prompt ->
                navigatedTo = conversationId
                carriedPrompt = prompt
            }
        }

        assertEquals("exactly one conversation, not zero and not two", 1, chatDao.conversations.size)
        assertEquals(
            "the id handed to navigation is the conversation that was just persisted",
            chatDao.conversations.single().id,
            navigatedTo,
        )
        assertEquals("the prompt travels trimmed", "Summarize a log", carriedPrompt)
    }

    @Test
    fun theConversationIsCreatedEmptySoTheFirstTurnRunsOnTheChatScreen() {
        var conversationId: String? = null

        runBlocking {
            newViewModel().start("Summarize a log") { id, _ -> conversationId = id }
        }

        assertTrue(
            "Home must not append the message itself — ChatScreen sends it, so the reply streams " +
                "where the user can watch it",
            chatDao.messages.isEmpty(),
        )
        assertEquals(
            "and the conversation is bound to no model: the resident one is picked at generation time",
            null,
            chatDao.conversations.single { it.id == conversationId }.modelId,
        )
    }

    @Test
    fun blankTextSendsNothingAndNavigatesNowhere() {
        var navigatedTo: String? = null

        runBlocking {
            newViewModel().start("   \n  ") { conversationId, _ -> navigatedTo = conversationId }
        }

        assertTrue("no conversation for a blank send", chatDao.conversations.isEmpty())
        assertNull("and nowhere to navigate to", navigatedTo)
    }

    @Test
    fun aSecondTapWhileTheFirstIsStillRunningDoesNotOpenASecondConversation() {
        val navigatedTo = mutableListOf<String>()

        runBlocking {
            val viewModel = newViewModel()
            // Both calls happen before this block suspends, so the first `start`'s coroutine is
            // still Active when the second one asks — which is exactly the double-tap it guards.
            viewModel.start("first") { conversationId, _ -> navigatedTo += conversationId }
            viewModel.start("second") { conversationId, _ -> navigatedTo += conversationId }
        }

        assertEquals(1, chatDao.conversations.size)
        assertEquals(listOf(chatDao.conversations.single().id), navigatedTo)
    }

    private fun loadedInfo() = LoadedModelInfo(
        name = "Qwen3-4B",
        nCtxEffective = 4096,
        backend = "cpu",
        kvCacheType = KvCacheType.F16,
    )

    private fun model(name: String) = ModelEntity(
        id = name,
        displayName = name,
        fileName = "$name.gguf",
        filePath = "/models/$name.gguf",
        sizeBytes = 1_000_000,
        architecture = "qwen2",
        quantLabel = "Q4_K_M",
        paramCount = 1_500_000_000,
        contextLength = 4096,
        embeddingLength = 1536,
        blockCount = 28,
        supportsThinking = false,
        importedAt = 0L,
    )
}

/**
 * In-memory [ChatDao] covering what [ChatRepository.createConversation] touches. Everything Home
 * never reaches throws rather than pretending — a fake that silently answers a question this
 * screen does not ask would only hide a future miswiring.
 */
private class FakeChatDao : ChatDao {

    private val state = MutableStateFlow<List<ConversationEntity>>(emptyList())

    val conversations: List<ConversationEntity> get() = state.value
    val messages = mutableListOf<MessageEntity>()

    override fun observeConversations(): Flow<List<ConversationEntity>> =
        state.map { list -> list.sortedByDescending { it.updatedAt } }

    override suspend fun insertConversation(conversation: ConversationEntity) {
        require(state.value.none { it.id == conversation.id }) { "duplicate conversation ${conversation.id}" }
        state.value = state.value + conversation
    }

    override suspend fun getConversation(id: String): ConversationEntity? = state.value.firstOrNull { it.id == id }

    override fun observeConversation(id: String): Flow<ConversationEntity?> =
        state.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun updateConversation(conversation: ConversationEntity) {
        state.value = state.value.map { if (it.id == conversation.id) conversation else it }
    }

    override suspend fun conversationCount(): Int = state.value.size

    override suspend fun insertMessage(message: MessageEntity) {
        messages += message
    }

    override fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        unsupported("observeMessages")

    override suspend fun messageCount(conversationId: String): Int = messages.count { it.conversationId == conversationId }

    override suspend fun updateMessage(
        id: String,
        content: String,
        thinking: String?,
        tokensGenerated: Int?,
        tokPerSec: Float?,
        ttftMs: Long?,
        stopReason: String?,
    ) = unsupported("updateMessage")

    override suspend fun deleteMessagesForConversation(conversationId: String) = unsupported("deleteMessagesForConversation")

    override suspend fun deleteMessage(id: String) = unsupported("deleteMessage")

    override suspend fun deleteConversationById(id: String) = unsupported("deleteConversationById")

    private fun unsupported(name: String): Nothing =
        throw UnsupportedOperationException("$name is not reached from Home")
}
