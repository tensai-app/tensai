package io.github.hokanosekai.tensai.agent

import io.github.hokanosekai.tensai.engine.ToolCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/** A call waiting for the user's Allow or Deny, see [UiConfirmationGate.pending]. */
class PendingConfirmation internal constructor(
    val call: ToolCall,
    internal val decision: CompletableDeferred<Boolean>,
)

/**
 * The [ConfirmationGate] of the app (TEN-174): [confirm] publishes the call in [pending], the chat draws
 * its "tensai wants to ..." card from it, and the call suspends until [answer] (the card's buttons), [deny]
 * (the user left the chat) or [timeoutMs] (nobody answered: counted as a Deny, the model is told it was refused).
 *
 * Stop is not an answer: it cancels the turn, which cancels the suspended [confirm]. The pending call is
 * dropped then and never runs, and whoever records the turn closes the call as stopped, see [AgentRuntime].
 *
 * [pending] is keyed by call id, which the runtime makes unique, so turns running for different conversations
 * never answer or replace each other's cards.
 *
 * @param timeoutMs how long a card waits. Backgrounding the app does not shorten it.
 */
class UiConfirmationGate(private val timeoutMs: Long = DefaultTimeoutMs) : ConfirmationGate {
    private val _pending = MutableStateFlow<Map<String, PendingConfirmation>>(emptyMap())

    /** The calls the user is asked about, by call id. */
    val pending: StateFlow<Map<String, PendingConfirmation>> = _pending.asStateFlow()

    private val _allowed = MutableStateFlow<List<String>>(emptyList())

    /**
     * Ids of the calls the user allowed, most recent last, bounded: between the tap and the stored result
     * the call is neither pending nor recorded, and the card must read "Allowed" in that gap.
     */
    val allowed: StateFlow<List<String>> = _allowed.asStateFlow()

    override suspend fun confirm(call: ToolCall, tool: Tool): Boolean {
        val request = PendingConfirmation(call, CompletableDeferred())
        var replaced: PendingConfirmation? = null
        _pending.update { current ->
            replaced = current[call.id]
            current + (call.id to request)
        }
        replaced?.decision?.complete(false)
        try {
            val allow = withTimeoutOrNull(timeoutMs) { request.decision.await() } ?: false
            if (allow) _allowed.value = (_allowed.value + call.id).takeLast(MaxAllowedIds)
            return allow
        } finally {
            _pending.update { current -> if (current[call.id] === request) current - call.id else current }
        }
    }

    /** The user's answer for the call [callId]; ignored when that call is not pending. */
    fun answer(callId: String, allow: Boolean) {
        _pending.value[callId]?.decision?.complete(allow)
    }

    /** Denies the pending calls among [callIds]: nobody is left to answer them. */
    fun deny(callIds: Collection<String>) {
        val current = _pending.value
        callIds.forEach { current[it]?.decision?.complete(false) }
    }

    companion object {
        /** Five minutes. */
        const val DefaultTimeoutMs = 5 * 60_000L
        private const val MaxAllowedIds = 32
    }
}
