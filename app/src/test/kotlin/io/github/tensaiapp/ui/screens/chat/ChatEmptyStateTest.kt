package io.github.tensaiapp.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The empty state's rules (TEN-169); the greeting by hour is covered by `HomeGreetingTest`. */
class ChatEmptyStateTest {

    @Test
    fun aConversationWithNoMessageShowsTheEmptyState() {
        assertTrue(showsEmptyState(0, isDraft = false))
    }

    @Test
    fun theFirstMessageMakesItGo() {
        assertFalse(showsEmptyState(1, isDraft = true))
        assertFalse(showsEmptyState(12, isDraft = false))
    }

    @Test
    fun aSavedConversationStillLoadingIsNotEmptyButADraftIs() {
        assertFalse(showsEmptyState(null, isDraft = false))
        assertTrue(showsEmptyState(null, isDraft = true))
    }

    @Test
    fun withoutAModelTheChipSaysLoadAModel() {
        assertEquals("Load a model", modelChipLabel(residentModelName = null, loadLabel = "Load a model"))
    }

    @Test
    fun withAModelTheChipNamesIt() {
        assertEquals("Qwen3 4B", modelChipLabel(residentModelName = "Qwen3 4B", loadLabel = "Load a model"))
    }
}
