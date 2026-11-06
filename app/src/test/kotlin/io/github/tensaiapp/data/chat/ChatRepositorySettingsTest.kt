package io.github.tensaiapp.data.chat

import io.github.tensaiapp.engine.SamplingConfig
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings writes from the conversation sheet (TEN-119). They come from concurrent coroutines, so
 * each must touch only its own column: a whole-row get-copy-update loses the other write.
 */
class ChatRepositorySettingsTest {

    private val dao = ChatRepositoryTitleTest.FakeChatDao().apply { yieldOnRead = true }
    private val repository = ChatRepository(dao)

    @Test
    fun concurrentSettersOnDifferentColumnsBothPersist() = runBlocking {
        val id = repository.createConversation(modelId = null)
        val config = SamplingConfig(temperature = 0.3f, maxTokens = 512)

        val jobs = listOf(
            launch { repository.setSampling(id, config) },
            launch { repository.setEnableThinking(id, false) },
            launch { repository.setSystemPrompt(id, "be terse") },
        )
        jobs.forEach { it.join() }

        val row = repository.getConversation(id)!!
        assertEquals(config, SamplingJson.decode(row.samplingJson))
        assertFalse(row.enableThinking)
        assertEquals("be terse", row.systemPrompt)
    }

    /**
     * A reply starting while the sheet saves (TEN-179): [ChatRepository.startAssistantMessage] runs on
     * every ReplyStarted and bumps `updatedAt`, which must not write back the settings it would have
     * read before the save.
     */
    @Test
    fun replyStartingDuringASettingsSaveKeepsTheSetting() = runBlocking {
        val id = repository.createConversation(modelId = null)

        val jobs = listOf(
            launch { repository.startAssistantMessage(id) },
            launch { repository.setSystemPrompt(id, "be terse") },
            launch { repository.setToolsEnabled(id, false) },
        )
        jobs.forEach { it.join() }

        val row = repository.getConversation(id)!!
        assertEquals("be terse", row.systemPrompt)
        assertFalse(row.toolsEnabled)
    }

    /** Same race through the first message, which also writes the title. */
    @Test
    fun firstMessageDuringASettingsSaveKeepsTheSettingAndTitlesTheConversation() = runBlocking {
        val id = repository.createConversation(modelId = null)

        val jobs = listOf(
            launch { repository.appendUserMessage(id, "hello there") },
            launch { repository.setEnableThinking(id, false) },
        )
        jobs.forEach { it.join() }

        val row = repository.getConversation(id)!!
        assertFalse(row.enableThinking)
        assertEquals("hello there", row.title)
    }

    @Test
    fun resetTuningPersistsBothFieldsAndKeepsThePrompt() = runBlocking {
        val id = repository.createConversation(modelId = null)
        repository.setSampling(id, SamplingConfig(maxTokens = 512))
        repository.setEnableThinking(id, false)
        repository.setSystemPrompt(id, "keep me")

        repository.resetTuning(id, resetThinking = true)

        val row = repository.getConversation(id)!!
        assertNull(row.samplingJson)
        assertTrue(row.enableThinking)
        assertEquals("keep me", row.systemPrompt)
    }

    @Test
    fun resetTuningLeavesThinkingAloneWhenAskedTo() = runBlocking {
        val id = repository.createConversation(modelId = null)
        repository.setSampling(id, SamplingConfig(maxTokens = 512))
        repository.setEnableThinking(id, false)

        repository.resetTuning(id, resetThinking = false)

        val row = repository.getConversation(id)!!
        assertNull(row.samplingJson)
        assertFalse(row.enableThinking)
    }

    @Test
    fun settersOnAMissingConversationAreNoOps() = runBlocking {
        repository.setSampling("nope", SamplingConfig())
        repository.resetTuning("nope", resetThinking = true)
        assertNull(repository.getConversation("nope"))
    }
}
