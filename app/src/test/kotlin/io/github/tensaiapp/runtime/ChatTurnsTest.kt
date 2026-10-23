package io.github.tensaiapp.runtime

import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTurnsTest {

    /** A turn that ends after a newer one took its conversation's entry leaves the newer one in place. */
    @Test
    fun finishingAnOlderTurnKeepsTheNewerOne() {
        val turns = ChatTurns()
        val older = ChatTurns.Turn(Job())
        val newer = ChatTurns.Turn(Job())
        turns.start("c", older)
        turns.start("c", newer)

        turns.finish("c", older)
        assertSame(newer, turns.running.value["c"])

        turns.finish("c", newer)
        assertTrue(turns.running.value.isEmpty())
    }

    @Test
    fun stopMarksTheSignalAndCancelsTheJobOfThatConversationOnly() {
        val turns = ChatTurns()
        val a = ChatTurns.Turn(Job())
        val b = ChatTurns.Turn(Job())
        turns.start("a", a)
        turns.start("b", b)

        assertSame(a.job, turns.stop("a"))

        assertTrue(a.stopSignal.wasRequested)
        assertTrue(a.job.isCancelled)
        assertFalse(b.stopSignal.wasRequested)
        assertFalse(b.job.isCancelled)
    }
}
