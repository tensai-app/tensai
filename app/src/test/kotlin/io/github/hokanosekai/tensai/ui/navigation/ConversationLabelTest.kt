package io.github.hokanosekai.tensai.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM tests for [conversationLabel], the drawer "Recents" label (TEN-105). */
class ConversationLabelTest {

    @Test
    fun emptyTitleShowsTheLocalizedUntitledLabel() {
        assertEquals("Nouvelle discussion", conversationLabel("", "Nouvelle discussion"))
    }

    @Test
    fun blankTitleShowsTheLocalizedUntitledLabel() {
        assertEquals("Nouvelle discussion", conversationLabel("   ", "Nouvelle discussion"))
    }

    @Test
    fun realTitleIsShownAsIs() {
        assertEquals("How do I parse a GGUF header?", conversationLabel("How do I parse a GGUF header?", "Nouvelle discussion"))
    }
}
