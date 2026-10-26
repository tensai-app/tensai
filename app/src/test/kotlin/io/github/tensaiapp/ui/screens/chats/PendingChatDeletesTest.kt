package io.github.tensaiapp.ui.screens.chats

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the swipe delete's undo window (TEN-193). */
class PendingChatDeletesTest {

    private val timeout = 5_000L

    private class Harness(test: TestScope) {
        val committed = mutableListOf<String>()
        val page = CoroutineScope(test.coroutineContext + Job())
        val deletes = PendingChatDeletes(page) { committed += it }
    }

    private fun Harness.swipe(id: String) = deletes.delete(id, timeout)

    @Test
    fun swipeHidesAtOnceShowsTheBarAndDeletesNothingYet() = runTest {
        val h = Harness(this)
        h.swipe("a")
        runCurrent()
        assertEquals(setOf("a"), h.deletes.hidden)
        assertEquals("a", h.deletes.undoable)
        advanceTimeBy(timeout - 1)
        // The bar stays up for the whole window.
        assertEquals("a", h.deletes.undoable)
        assertEquals(emptyList<String>(), h.committed)
        h.page.cancel()
    }

    @Test
    fun undoShowsTheRowAgainAndNeverCommits() = runTest {
        val h = Harness(this)
        h.swipe("a")
        runCurrent()
        h.deletes.undo()
        assertEquals(emptySet<String>(), h.deletes.hidden)
        assertNull(h.deletes.undoable)
        advanceTimeBy(timeout * 2)
        h.page.cancel()
        runCurrent()
        assertEquals(emptyList<String>(), h.committed)
    }

    @Test
    fun undoTappedInTheSameFrameAsANewSwipeStillUndoes() = runTest {
        val h = Harness(this)
        h.swipe("a")
        runCurrent()
        // The tap and the next swipe land before the cancelled window has run.
        h.deletes.undo()
        h.swipe("b")
        runCurrent()
        assertEquals(emptyList<String>(), h.committed)
        assertEquals(setOf("b"), h.deletes.hidden)
        assertEquals("b", h.deletes.undoable)
        h.page.cancel()
    }

    @Test
    fun aRowSwipedAgainRightAfterUndoIsCommittedOnce() = runTest {
        val h = Harness(this)
        h.swipe("a")
        runCurrent()
        h.deletes.undo()
        h.swipe("a")
        advanceTimeBy(timeout + 1)
        assertEquals(listOf("a"), h.committed)
        h.page.cancel()
    }

    @Test
    fun timeoutCommitsOnceAndKeepsTheRowHidden() = runTest {
        val h = Harness(this)
        h.swipe("a")
        advanceTimeBy(timeout + 1)
        assertEquals(listOf("a"), h.committed)
        assertNull(h.deletes.undoable)
        // Still hidden until Room drops it, and leaving afterwards does not commit it again.
        assertEquals(setOf("a"), h.deletes.hidden)
        h.page.cancel()
        runCurrent()
        assertEquals(listOf("a"), h.committed)
    }

    @Test
    fun aSecondSwipeCommitsTheFirstAtOnce() = runTest {
        val h = Harness(this)
        h.swipe("a")
        runCurrent()
        h.swipe("b")
        runCurrent()
        assertEquals(listOf("a"), h.committed)
        assertEquals(setOf("a", "b"), h.deletes.hidden)
        assertEquals("b", h.deletes.undoable)
        // Undo now only concerns b.
        h.deletes.undo()
        runCurrent()
        assertEquals(setOf("a"), h.deletes.hidden)
        advanceTimeBy(timeout * 2)
        assertEquals(listOf("a"), h.committed)
        h.page.cancel()
    }

    @Test
    fun quickSwipesBeforeAnyWindowRunsLoseNone() = runTest {
        val h = Harness(this)
        h.swipe("a")
        h.swipe("b")
        h.swipe("c")
        advanceTimeBy(timeout + 1)
        assertEquals(listOf("a", "b", "c"), h.committed)
        h.page.cancel()
    }

    @Test
    fun swipingAnAlreadyHiddenChatIsIgnored() = runTest {
        val h = Harness(this)
        h.swipe("a")
        runCurrent()
        h.swipe("a")
        advanceTimeBy(timeout + 1)
        assertEquals(listOf("a"), h.committed)
        h.page.cancel()
    }

    @Test
    fun leavingThePageCommitsThePendingDelete() = runTest {
        val h = Harness(this)
        h.swipe("a")
        runCurrent()
        h.page.cancel()
        runCurrent()
        assertEquals(listOf("a"), h.committed)
        assertNull(h.deletes.undoable)
    }

    @Test
    fun leavingBeforeTheWindowEvenStartedStillCommits() = runTest {
        val h = Harness(this)
        h.swipe("a")
        h.page.cancel()
        runCurrent()
        assertEquals(listOf("a"), h.committed)
    }

    @Test
    fun flushCommitsAndDropsTheBar() = runTest {
        val h = Harness(this)
        h.swipe("a")
        runCurrent()
        h.deletes.flush()
        runCurrent()
        assertEquals(listOf("a"), h.committed)
        assertNull(h.deletes.undoable)
        assertTrue("a" in h.deletes.hidden)
        h.page.cancel()
    }
}
