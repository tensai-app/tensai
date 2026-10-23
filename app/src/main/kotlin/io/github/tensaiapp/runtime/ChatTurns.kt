package io.github.tensaiapp.runtime

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The chat turns running in this process, by conversation: a send or a regenerate, from the moment its
 * conversation is known until everything it writes is written, plain replies and agent turns alike. A
 * turn outlives the chat screen that started it (it runs on [RuntimeGraph.applicationScope]), and a
 * screen reopened on its conversation is a new `ChatViewModel`: what controls the turn lives here, not
 * on the instance, so the new one can stop it and refuses to start a second one beside it.
 *
 * The queue alone cannot tell that the engine is spoken for: it reads [QueueState.ModelReady] while a
 * turn restores its session, while an agent turn runs a tool between two generations, and while a cut
 * turn writes what it leaves behind.
 */
class ChatTurns {
    /**
     * One running turn: the [job] to cancel to end it, tool execution included, the [stopSignal] its
     * generations carry, marked first so what it cuts is recorded as stopped, and the ids of the calls it
     * made ([callIds]), so leaving the chat answers this turn's cards and no other.
     */
    class Turn(val job: Job, val stopSignal: GenerationStopSignal = GenerationStopSignal()) {
        @Volatile
        var callIds: Set<String> = emptySet()
    }

    private val _running = MutableStateFlow<Map<String, Turn>>(emptyMap())

    /** The running turns by conversation id. */
    val running: StateFlow<Map<String, Turn>> = _running.asStateFlow()

    fun start(conversationId: String, turn: Turn) {
        _running.update { it + (conversationId to turn) }
    }

    /** Drops [turn], and only it: a turn that started since under the same id keeps its entry. */
    fun finish(conversationId: String, turn: Turn) {
        _running.update { if (it[conversationId] === turn) it - conversationId else it }
    }

    /**
     * Ends [conversationId]'s turn, if one runs, and answers its job (to join for the rows it writes on
     * the way out). Marked before the cancel, for the reason [InferenceQueue.cancelActive] is.
     */
    fun stop(conversationId: String): Job? =
        _running.value[conversationId]?.also(::stop)?.job

    /** Ends every running turn: the notification's Stop, which knows no conversation. */
    fun stopAll() {
        _running.value.values.forEach(::stop)
    }

    private fun stop(turn: Turn) {
        turn.stopSignal.markRequested()
        turn.job.cancel()
    }
}
