package io.github.tensaiapp.agent

import io.github.tensaiapp.engine.ToolCall
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [UiConfirmationGate] (TEN-174): the call waits for the card's answer, and Stop or leaving never lets it through. */
class UiConfirmationGateTest {

    private class StubTool : Tool {
        override val name = "erase"
        override val description = "stub"
        override val parametersSchema = """{"type":"object","properties":{}}"""
        override val tier = ToolTier.Local
        override val sendsOrDeletes = true
        override suspend fun execute(argsJson: String) = ToolResult.ok("")
    }

    private val call = ToolCall("c1", "erase", """{"id":1}""")

    /** Names what the call acts on, as `memory` does for a `forget`. */
    private class NamingTool : Tool {
        override val name = "erase"
        override val description = "stub"
        override val parametersSchema = """{"type":"object","properties":{}}"""
        override val tier = ToolTier.Local
        override val sendsOrDeletes = true
        override suspend fun confirmTarget(callId: String, argsJson: String) = ConfirmTarget("1", "Likes tea")
        override suspend fun execute(argsJson: String) = ToolResult.ok("")
    }

    /** Its lookup fails, the store being unreadable. */
    private class FailingLookupTool : Tool {
        override val name = "erase"
        override val description = "stub"
        override val parametersSchema = """{"type":"object","properties":{}}"""
        override val tier = ToolTier.Local
        override val sendsOrDeletes = true
        override suspend fun confirmTarget(callId: String, argsJson: String): ConfirmTarget? = error("database closed")
        override suspend fun execute(argsJson: String) = ToolResult.ok("")
    }

    @Test
    fun allowLetsTheCallRunAndNotesIt() = runBlocking {
        val gate = UiConfirmationGate()
        val answer = async(start = CoroutineStart.UNDISPATCHED) { gate.confirm(call, StubTool()) }

        assertEquals("c1", gate.pending.first { it.isNotEmpty() }.getValue("c1").call.id)
        gate.answer("c1", allow = true)

        assertTrue(answer.await())
        assertTrue(gate.pending.value.isEmpty())
        assertEquals(listOf("c1"), gate.allowed.value)
    }

    @Test
    fun theTargetsAreReadBeforeTheCardShowsAndOutliveTheAnswer() = runBlocking {
        val gate = UiConfirmationGate()
        val answer = async(start = CoroutineStart.UNDISPATCHED) { gate.confirm(call, NamingTool()) }

        gate.pending.first { it.isNotEmpty() }
        assertEquals(ConfirmTarget("1", "Likes tea"), gate.targets.value["c1"])
        gate.answer("c1", allow = true)

        assertTrue(answer.await())
        assertEquals(ConfirmTarget("1", "Likes tea"), gate.targets.value["c1"])
    }

    @Test
    fun aFailingLookupStillAsksTheUser() = runBlocking {
        val gate = UiConfirmationGate()
        val answer = async(start = CoroutineStart.UNDISPATCHED) { gate.confirm(call, FailingLookupTool()) }

        withTimeout(5_000) { gate.pending.first { it.isNotEmpty() } }
        assertTrue(gate.targets.value.isEmpty())
        gate.answer("c1", allow = true)

        assertTrue(answer.await())
    }

    @Test
    fun denyRefusesTheCall() = runBlocking {
        val gate = UiConfirmationGate()
        val answer = async(start = CoroutineStart.UNDISPATCHED) { gate.confirm(call, StubTool()) }

        gate.answer("c1", allow = false)

        assertFalse(answer.await())
        assertTrue(gate.pending.value.isEmpty())
        assertTrue(gate.allowed.value.isEmpty())
    }

    @Test
    fun anAnswerForAnotherCallIsIgnored() = runBlocking {
        val gate = UiConfirmationGate()
        val answer = async(start = CoroutineStart.UNDISPATCHED) { gate.confirm(call, StubTool()) }

        gate.answer("other", allow = true)

        assertEquals(setOf("c1"), gate.pending.value.keys)
        gate.answer("c1", allow = false)
        assertFalse(answer.await())
    }

    @Test
    fun stopDropsThePendingCallWithoutAllowingIt() = runBlocking {
        val gate = UiConfirmationGate()
        val answer = async(start = CoroutineStart.UNDISPATCHED) { gate.confirm(call, StubTool()) }
        withTimeout(5_000) { gate.pending.first { it.isNotEmpty() } }

        answer.cancelAndJoin()

        assertTrue(gate.pending.value.isEmpty())
        assertTrue(gate.allowed.value.isEmpty())
    }

    @Test
    fun leavingTheChatDeniesThePendingCall() = runBlocking {
        val gate = UiConfirmationGate()
        val answer = async(start = CoroutineStart.UNDISPATCHED) { gate.confirm(call, StubTool()) }

        gate.deny(listOf("c1"))

        assertFalse(answer.await())
        assertTrue(gate.pending.value.isEmpty())
    }

    @Test
    fun nobodyAnsweringIsADenyAfterTheTimeout() = runBlocking {
        val gate = UiConfirmationGate(timeoutMs = 50)

        val allowed = gate.confirm(call, StubTool())

        assertFalse(allowed)
        assertTrue(gate.pending.value.isEmpty())
        assertTrue(gate.allowed.value.isEmpty())
    }

    @Test
    fun callsOfTwoTurnsWaitIndependently() = runBlocking {
        val gate = UiConfirmationGate()
        val other = ToolCall("c2", "erase", "{}")
        val first = async(start = CoroutineStart.UNDISPATCHED) { gate.confirm(call, StubTool()) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { gate.confirm(other, StubTool()) }

        assertEquals(setOf("c1", "c2"), gate.pending.value.keys)
        // Leaving one chat denies its own call only.
        gate.deny(listOf("c1"))
        assertFalse(first.await())
        assertEquals(setOf("c2"), gate.pending.value.keys)
        gate.answer("c2", allow = true)
        assertTrue(second.await())
    }

    @Test
    fun denyWithNothingPendingIsANoOp() {
        val gate = UiConfirmationGate()

        gate.deny(listOf("c1"))
        gate.answer("c1", allow = true)

        assertTrue(gate.pending.value.isEmpty())
    }
}
