package io.github.tensaiapp.runtime

import io.github.tensaiapp.agent.AskUserGate
import io.github.tensaiapp.agent.UiConfirmationGate
import io.github.tensaiapp.engine.LoadedModelInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
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
 *
 * A send in one chat while another chat's turn runs waits here too, one per conversation, until [dispatchPending]
 * starts it (TEN-224).
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

    // The conversation each chat screen shows, by screen (`null` for a draft): see [show].
    private val shown = MutableStateFlow<Map<Any, String?>>(emptyMap())

    /**
     * [screen] shows [conversationId] (`null` for a draft) until [hide]. Keyed by screen, not counted by
     * conversation: a screen leaving after another one on the same conversation came up leaves that one shown.
     */
    fun show(screen: Any, conversationId: String?) {
        shown.update { it + (screen to conversationId) }
    }

    /** The draft [screen] shows got its row. A no-op once [hide] has run: the turn it starts is nobody's to watch. */
    fun bind(screen: Any, conversationId: String) {
        shown.update { if (screen in it) it + (screen to conversationId) else it }
    }

    fun hide(screen: Any) {
        shown.update { it - screen }
    }

    /**
     * Denies the cards and dismisses the questions of every turn whose conversation no screen shows (TEN-174,
     * TEN-187): nobody is there to answer them, a card would wait out its five minutes and a question for good,
     * and every chat's Send waits for the turn to end. Pending ones as the screen leaves, and the ones the turn
     * raises later (a model calling again after a Deny), until a screen shows the conversation again.
     * Never returns: collected for the life of the process. [log] gets how many calls it answered.
     */
    suspend fun answerUnattended(confirmations: UiConfirmationGate, questions: AskUserGate, log: (String) -> Unit) {
        combine(_running, shown, confirmations.pending, questions.pending) { running, shown, cards, asked ->
            val watched = shown.values.toSet()
            running.filterKeys { it !in watched }.values.flatMap { it.callIds }.filter { it in cards || it in asked }
        }.collect { ids ->
            val answered = confirmations.deny(ids) + questions.dismiss(ids)
            if (answered > 0) log("chat left: answered $answered call(s) for nobody")
        }
    }

    /**
     * A message sent while another chat's turn held the engine (TEN-224): stored as [messageId],
     * [io.github.tensaiapp.data.chat.SendState.Pending] in the store, and [start]ed by [dispatchPending] once the
     * engine is free. [start] is the send path of the screen that queued it, which runs whether that screen is still
     * open or not, and registers the turn before it returns. [model] is the one resident when it was sent: the
     * message was written for it, and goes out on no other.
     */
    class PendingSend(val messageId: String, val text: String, val model: LoadedModelInfo, val start: () -> Unit)

    private val _pending = MutableStateFlow<Map<String, PendingSend>>(emptyMap())

    /** The sends waiting for the engine by conversation id, at most one per conversation, oldest first. */
    val pending: StateFlow<Map<String, PendingSend>> = _pending.asStateFlow()

    /** Queues [send] for [conversationId], `false` when that conversation already has one waiting. */
    fun queue(conversationId: String, send: PendingSend): Boolean {
        var queued = false
        _pending.update {
            queued = conversationId !in it
            if (queued) it + (conversationId to send) else it
        }
        return queued
    }

    /** Takes [conversationId]'s waiting send back, `null` when it has none (already started, or never queued). */
    fun unqueue(conversationId: String): PendingSend? {
        var taken: PendingSend? = null
        _pending.update {
            taken = it[conversationId]
            it - conversationId
        }
        return taken
    }

    /**
     * Starts the oldest waiting send whenever no turn runs and the engine is idle, one at a time: the turn it starts
     * is registered before the next look, so the next one waits for it to end. A send whose [PendingSend.model] is no
     * longer the resident one (a switch, an unload, a load in progress, one landing while the send was being stored) is
     * dropped instead, and [dropped] gets it to mark not sent. Never returns: collected for the life of the process.
     * [log] gets a line per start and drop.
     */
    suspend fun dispatchPending(queueState: StateFlow<QueueState>, dropped: suspend (Collection<PendingSend>) -> Unit, log: (String) -> Unit) {
        // A trigger only: the values are read again below, a combined snapshot can predate the turn just started.
        combine(_running, _pending, queueState) { _, _, _ -> }.collect {
            val resident = queueState.value.residentModel
            val stale = _pending.value.filterValues { it.model != resident }
            if (stale.isNotEmpty()) {
                // Only these very sends: one queued since under the same id stays.
                _pending.update { all -> all.filter { (id, send) -> stale[id] !== send } }
                log("queued send: model changed, ${stale.size} marked not sent")
                dropped(stale.values)
            }
            if (_running.value.isNotEmpty() || queueState.value !is QueueState.ModelReady) return@collect
            val (conversationId, send) = _pending.value.entries.firstOrNull() ?: return@collect
            var taken = false
            _pending.update {
                taken = it[conversationId] === send
                if (taken) it - conversationId else it
            }
            if (!taken) return@collect
            log("queued send: started, ${_pending.value.size} still waiting")
            send.start()
        }
    }

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

    /**
     * Ends every running turn: the notification's Stop, which knows no conversation. The waiting sends go too, taken
     * before the turns end so none starts in their place: Stop means the engine stops (TEN-224). Answers them, for the
     * caller to mark not sent as [dispatchPending]'s `dropped` does. A chat's own Stop ([stop]) leaves them waiting.
     */
    fun stopAll(): Collection<PendingSend> {
        val drained = _pending.getAndUpdate { emptyMap() }.values
        _running.value.values.forEach(::stop)
        return drained
    }

    private fun stop(turn: Turn) {
        turn.stopSignal.markRequested()
        turn.job.cancel()
    }
}
