package io.github.tensaiapp.runtime

import io.github.tensaiapp.engine.KvCacheType
import io.github.tensaiapp.engine.LoadedModelInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
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

    /**
     * A send queued for a model that is no longer the resident one (a switch landing while it was being stored) is
     * dropped to be marked not sent, never started; one for the resident model is started (TEN-224).
     */
    @Test
    fun aSendQueuedForAnotherModelIsDroppedAndOneForTheResidentIsStarted() = runBlocking {
        val x = LoadedModelInfo(name = "x", nCtxEffective = 4096, backend = "CPU", kvCacheType = KvCacheType.F16, modelId = "x")
        val y = x.copy(name = "y", modelId = "y")
        val turns = ChatTurns()
        val dropped = mutableListOf<String>()
        val started = mutableListOf<String>()
        turns.queue("b", ChatTurns.PendingSend("mb", "hi", x) { started += "mb" })
        turns.queue("c", ChatTurns.PendingSend("mc", "hi", y) { started += "mc" })

        val dispatcher = launch {
            turns.dispatchPending(MutableStateFlow<QueueState>(QueueState.ModelReady(y)), dropped = { sends -> dropped += sends.map { it.messageId } }) {}
        }
        withTimeout(5_000) { turns.pending.first { it.isEmpty() } }
        dispatcher.cancel()

        assertEquals(listOf("mb"), dropped)
        assertEquals(listOf("mc"), started)
    }
}
