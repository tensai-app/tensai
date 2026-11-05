package io.github.tensaiapp.runtime

import io.github.tensaiapp.data.session.SessionModelKey
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One report to [ActiveSessionTracker]: [conversationId] owns the KV cache on [key]. [generation]
 * tells two reports of the same pair apart, so a value read before the cache went to another
 * conversation and came back (A, then B, then A again) is not equal to the one current after it.
 */
data class ActiveSession(val conversationId: String, val key: SessionModelKey, val generation: Long)

/**
 * The conversation currently bound to whatever model [InferenceQueue] has loaded, as last reported
 * by whichever `ChatViewModel` is generating (see `ChatViewModel.reportActiveSession`) — the two
 * TEN-34 save triggers (`RuntimeGraph`'s [InferenceQueue.onBeforeModelSwap] wiring, and the app
 * going to background) both read [current] to know *what* to save.
 *
 * This is deliberately a plain, always-overwritable "last known" pointer rather than a
 * precisely-scoped enter/leave registration: both triggers fire from code with no direct line to
 * whatever `ChatScreen` is actually composed right now. A slightly stale value here (e.g. the user
 * already navigated elsewhere) is harmless, at worst it saves a session for a conversation nobody's
 * looking at anymore, which `SessionStoragePolicy`'s LRU cap cleans up on its own. Missing a save
 * entirely would not be harmless, so this favours "usually right" over strictly correct.
 *
 * [clear] is called from [RuntimeGraph]'s [InferenceQueue.onBeforeModelSwap] wiring the moment a
 * swap/unload starts, specifically so a stale pointer can never mislabel a *different* model's KV
 * state as belonging to the conversation/model recorded here — see that wiring's KDoc.
 *
 * A save's `ownsCache` compares the whole [ActiveSession] it started from with [current], generation
 * included: the conversation and key alone pass again once the cache went elsewhere and came back.
 */
object ActiveSessionTracker {
    private val _current = MutableStateFlow<ActiveSession?>(null)
    private val generations = AtomicLong()

    /** Whatever conversation last reported itself active, or `null`. */
    val current: StateFlow<ActiveSession?> = _current.asStateFlow()

    /**
     * Called by a `ChatViewModel` generation as it takes the queue (`InferenceQueue.generate`'s `onStart`), with the
     * key of the model it runs on. A report naming the current owner again keeps its generation: the cache did not
     * change hands.
     */
    fun report(conversationId: String, modelKey: SessionModelKey) {
        // A retried update skips a number, which only has to differ from the last one.
        _current.update {
            if (it?.conversationId == conversationId && it.key == modelKey) it else ActiveSession(conversationId, modelKey, generations.incrementAndGet())
        }
    }

    fun clear() {
        _current.value = null
    }

    /** [clear], only while [conversationId] is the one reported: a report that came in since names the cache's new owner. */
    fun clearIf(conversationId: String) {
        _current.update { if (it?.conversationId == conversationId) null else it }
    }
}
