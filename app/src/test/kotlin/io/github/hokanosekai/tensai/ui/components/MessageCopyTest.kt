package io.github.hokanosekai.tensai.ui.components

import io.github.hokanosekai.tensai.ui.screens.chat.ChatMessageUi
import io.github.hokanosekai.tensai.ui.screens.chat.ReplyEnding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageCopyTest {
    private fun msg(isUser: Boolean, content: String, thinking: String? = null) =
        ChatMessageUi("id", isUser, content, thinking, stats = null, ending = ReplyEnding.Normal)

    @Test
    fun `user message copies its text`() {
        assertEquals("hello *there*", copyableText(msg(true, "hello *there*")))
    }

    @Test
    fun `reply copies the content and never the reasoning`() {
        assertEquals("**answer**", copyableText(msg(false, "**answer**", thinking = "let me think")))
    }

    @Test
    fun `call-only message has nothing to copy`() {
        val callOnly = msg(false, "", thinking = "i should call a tool")
        assertNull(copyableText(callOnly))
        assertFalse(showsCopyButton(callOnly, isGenerating = false))
    }

    @Test
    fun `button shows on a finished reply even without stats`() {
        assertTrue(showsCopyButton(msg(false, "answer"), isGenerating = false))
    }

    @Test
    fun `button hidden while generating and under user messages`() {
        assertFalse(showsCopyButton(msg(false, "partial"), isGenerating = true))
        assertFalse(showsCopyButton(msg(true, "question"), isGenerating = false))
    }
}
