package io.github.tensaiapp.ui.screens.chat

import io.github.tensaiapp.data.chat.ToolActivityRow
import org.junit.Assert.assertEquals
import org.junit.Test

/** TEN-162: the "Agent activity" table's 10-row cap. */
class ContextSheetActivityTest {

    private fun rows(count: Int) = List(count) { ToolActivityRow("tool$it", calls = 1, resultTokens = 10, durationMs = 5) }

    @Test
    fun theTableStopsAtTenRowsUntilShowMore() {
        assertEquals(10, rows(14).visibleActivity(showAll = false).size)
        assertEquals(14, rows(14).visibleActivity(showAll = true).size)
    }

    @Test
    fun aShortTableIsShownWhole() {
        assertEquals(3, rows(3).visibleActivity(showAll = false).size)
        assertEquals(10, rows(10).visibleActivity(showAll = false).size)
    }
}
