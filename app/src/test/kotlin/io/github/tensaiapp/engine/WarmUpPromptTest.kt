package io.github.tensaiapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WarmUpPrompt] against a fake template that has the one property that makes the placeholder
 * necessary (TEN-88): like Qwen3's, it renders an assistant turn *after the last user message*
 * differently (reasoning block kept) from one *before* it. A real native template is device-only;
 * this pins the arithmetic around it.
 */
class WarmUpPromptTest {

    private fun user(text: String) = ChatMessage(MessageRole.User, text)
    private fun assistant(text: String) = ChatMessage(MessageRole.Assistant, text)
    private fun system(text: String) = ChatMessage(MessageRole.System, text)

    /**
     * ChatML shaped like Qwen3's: an assistant turn after the last user query gets a think block in
     * front of its content, an earlier one is plain, and the generation prompt carries the empty
     * think block thinking-off asks for.
     */
    private fun qwen3Like(messages: List<ChatMessage>): String {
        val lastUser = messages.indexOfLast { it.role == MessageRole.User }
        val out = StringBuilder()
        messages.forEachIndexed { i, m ->
            when (m.role) {
                MessageRole.System -> out.append("<|im_start|>system\n${m.content}<|im_end|>\n")
                MessageRole.User -> out.append("<|im_start|>user\n${m.content}<|im_end|>\n")
                MessageRole.Assistant -> {
                    val think = if (i > lastUser) "<think>\n\n</think>\n\n" else ""
                    out.append("<|im_start|>assistant\n$think${m.content}<|im_end|>\n")
                }
                MessageRole.Tool -> out.append("<|im_start|>tool\n${m.content}<|im_end|>\n")
            }
        }
        out.append("<|im_start|>assistant\n<think>\n\n</think>\n\n")
        return out.toString()
    }

    private val history = listOf(
        system("be brief"),
        user("first question"),
        assistant("first answer"),
        user("second question"),
        assistant("the long answer that just finished"),
    )

    @Test
    fun theWarmUpPromptStopsWhereTheUsersTextWouldStart() {
        val prompt = WarmUpPrompt.build(history, ::qwen3Like)

        assertNotNull(prompt)
        assertTrue(
            "ends on the user turn's header, nothing of the placeholder, nothing of the generation prompt",
            prompt!!.endsWith("<|im_end|>\n<|im_start|>user\n"),
        )
        assertFalse(prompt.endsWith("</think>\n\n"))
    }

    /**
     * The point of it all: whatever the user types next, the real prompt starts with exactly this
     * string, byte for byte, so llama.cpp's prefix reuse keeps all of it.
     */
    @Test
    fun theRealNextPromptStartsWithTheWarmUpPromptForAnyUserText() {
        val prompt = WarmUpPrompt.build(history, ::qwen3Like)!!

        for (text in listOf("hello", "b", "a", "multi\nline", "  spaced")) {
            val next = qwen3Like(history + user(text))
            assertTrue("next prompt for \"$text\" must extend the warm-up prompt", next.startsWith(prompt))
        }
    }

    /**
     * The trap the placeholder exists for. Formatting the history as it stands renders the last
     * reply as "after the last user query" (think block kept), a form the next prompt will not have
     * once a user message follows it, so it is *not* a prefix of that prompt.
     */
    @Test
    fun formattingTheHistoryAsItStandsWouldNotBeAPrefixOfTheNextPrompt() {
        val naive = qwen3Like(history)
        val next = qwen3Like(history + user("hello"))

        assertFalse(next.startsWith(naive.removeSuffix("<|im_start|>assistant\n<think>\n\n</think>\n\n")))
        val prompt = WarmUpPrompt.build(history, ::qwen3Like)!!
        assertTrue(
            "the last reply is rendered in its final, plain form",
            prompt.contains("<|im_start|>assistant\nthe long answer that just finished<|im_end|>\n"),
        )
        assertFalse("and without the think block the generation would have left in the cache", prompt.contains("</think>\n\nthe long answer"))
    }

    @Test
    fun withoutASystemPromptTheHistoryStillEndsOnTheReply() {
        val prompt = WarmUpPrompt.build(listOf(user("q"), assistant("long")), ::qwen3Like)!!

        assertEquals(
            "<|im_start|>user\nq<|im_end|>\n<|im_start|>assistant\nlong<|im_end|>\n<|im_start|>user\n",
            prompt,
        )
    }

    @Test
    fun aTemplateThatNeverRendersTheUsersContentHasNoBoundaryToCutAt() {
        assertNull(WarmUpPrompt.build(history) { "constant prompt" })
    }

    @Test
    fun twoRendersSharingNothingGiveNothing() {
        assertNull(WarmUpPrompt.build(history) { messages -> messages.last().content + "!" })
    }
}
