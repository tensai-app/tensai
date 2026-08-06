package io.github.hokanosekai.tensai.data.chat

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hokanosekai.tensai.data.models.TensaiDb
import io.github.hokanosekai.tensai.engine.DecodeStats
import io.github.hokanosekai.tensai.engine.MessageRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [ChatRepository] against an in-memory [TensaiDb] — same convention as
 * `ModelRepositoryTest`, but this one needs no real GGUF file (no engine involved), so it never
 * skips.
 */
@RunWith(AndroidJUnit4::class)
class ChatRepositoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: TensaiDb
    private lateinit var repository: ChatRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, TensaiDb::class.java).build()
        repository = ChatRepository(db.chatDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun createConversationIsObservable() = runBlocking {
        val id = repository.createConversation(modelId = "model-1")

        val conversations = repository.observeConversations().first()
        assertEquals(1, conversations.size)
        assertEquals(id, conversations.single().id)
        assertEquals("model-1", conversations.single().modelId)
    }

    @Test
    fun firstUserMessageSetsTruncatedTitle() = runBlocking {
        val id = repository.createConversation(modelId = null)
        val longMessage = "a".repeat(60)

        repository.appendUserMessage(id, longMessage)

        val conversation = repository.observeConversations().first().single()
        assertEquals(40 + 1, conversation.title.length) // 40 chars + the ellipsis char.
        assertTrue(conversation.title.endsWith("…"))

        // A second message must not overwrite the title set by the first.
        repository.appendUserMessage(id, "a much shorter follow-up")
        val afterSecond = repository.observeConversations().first().single()
        assertEquals(conversation.title, afterSecond.title)
    }

    @Test
    fun startAndUpdateAssistantMessagePersistsContentAndStats() = runBlocking {
        val id = repository.createConversation(modelId = null)

        val messageId = repository.startAssistantMessage(id)
        var stored = repository.observeMessages(id).first().single()
        assertEquals("", stored.content)
        assertNull(stored.tokensGenerated)

        repository.updateAssistantMessage(
            messageId,
            content = "hello there",
            thinking = "reasoning about it",
            stats = DecodeStats(tokensGenerated = 3, tokPerSec = 12.5f, ttftMs = 80L),
        )

        stored = repository.observeMessages(id).first().single()
        assertEquals("hello there", stored.content)
        assertEquals("reasoning about it", stored.thinking)
        assertEquals(3, stored.tokensGenerated)
        assertEquals(12.5f, stored.tokPerSec)
        assertEquals(80L, stored.ttftMs)
    }

    @Test
    fun toEngineMessagesOrdersAndMapsRolesWithSystemPromptFirst() = runBlocking {
        val id = repository.createConversation(modelId = null)
        repository.appendUserMessage(id, "hi")
        val assistantId = repository.startAssistantMessage(id)
        repository.updateAssistantMessage(assistantId, content = "hello")

        val stored = repository.observeMessages(id).first()
        val engineMessages = repository.toEngineMessages(stored, systemPrompt = "be concise")

        assertEquals(3, engineMessages.size)
        assertEquals(MessageRole.System, engineMessages[0].role)
        assertEquals("be concise", engineMessages[0].content)
        assertEquals(MessageRole.User, engineMessages[1].role)
        assertEquals("hi", engineMessages[1].content)
        assertEquals(MessageRole.Assistant, engineMessages[2].role)
        assertEquals("hello", engineMessages[2].content)
        engineMessages.forEach { assertNull(it.toolCalls) }
    }

    @Test
    fun toEngineMessagesWithoutSystemPromptOmitsIt() = runBlocking {
        val id = repository.createConversation(modelId = null)
        repository.appendUserMessage(id, "hi")

        val stored = repository.observeMessages(id).first()
        val engineMessages = repository.toEngineMessages(stored, systemPrompt = null)

        assertEquals(1, engineMessages.size)
        assertEquals(MessageRole.User, engineMessages.single().role)
    }

    @Test
    fun deleteConversationCascadesMessages() = runBlocking {
        val id = repository.createConversation(modelId = null)
        repository.appendUserMessage(id, "hi")
        repository.startAssistantMessage(id)

        repository.deleteConversation(id)

        assertTrue(repository.observeConversations().first().isEmpty())
        assertTrue(repository.observeMessages(id).first().isEmpty())
    }

    @Test
    fun observeConversationsSortsByUpdatedAtDescending() = runBlocking {
        val older = repository.createConversation(modelId = null)
        Thread.sleep(5) // ensure a distinct System.currentTimeMillis() for updatedAt ordering.
        val newer = repository.createConversation(modelId = null)

        val conversations = repository.observeConversations().first()
        assertEquals(listOf(newer, older), conversations.map { it.id })

        // Touching the older conversation should move it back to the front.
        Thread.sleep(5)
        repository.appendUserMessage(older, "hi")
        val afterTouch = repository.observeConversations().first()
        assertEquals(listOf(older, newer), afterTouch.map { it.id })
    }
}
