package io.github.hokanosekai.tensai.ui.screens.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [hasPersistableContent] (TEN-64) — the decision [ChatViewModel.streamInto]'s
 * `finally` makes between persisting a finished/stopped assistant turn and dropping it instead of
 * leaving an empty row behind.
 */
class HasPersistableContentTest {

    @Test
    fun emptyContentAndNoThinkingIsNotPersistable() {
        assertFalse(hasPersistableContent(content = "", thinking = null))
    }

    @Test
    fun emptyContentAndEmptyThinkingIsNotPersistable() {
        // <think></think> immediately closed with nothing inside — still nothing worth keeping.
        assertFalse(hasPersistableContent(content = "", thinking = ""))
    }

    @Test
    fun nonEmptyContentIsPersistableEvenWithNoThinking() {
        assertTrue(hasPersistableContent(content = "Hi there", thinking = null))
    }

    @Test
    fun emptyContentWithNonEmptyThinkingIsStillPersistable() {
        // A <think> block cut off before it ever closed (ReasoningBudgetExhausted) has no answer
        // yet, but the reasoning itself is real partial content, not nothing.
        assertTrue(hasPersistableContent(content = "", thinking = "still reasoning..."))
    }

    @Test
    fun nonEmptyContentAndNonEmptyThinkingIsPersistable() {
        assertTrue(hasPersistableContent(content = "The answer is 4", thinking = "let me think"))
    }
}
