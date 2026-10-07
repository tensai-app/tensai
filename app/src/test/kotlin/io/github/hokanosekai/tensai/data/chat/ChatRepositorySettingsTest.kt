package io.github.hokanosekai.tensai.data.chat

import io.github.hokanosekai.tensai.engine.SamplingConfig
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
