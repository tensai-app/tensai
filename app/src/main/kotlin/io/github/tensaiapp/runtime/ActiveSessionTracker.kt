package io.github.tensaiapp.runtime

import io.github.tensaiapp.data.session.SessionModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
 */
object ActiveSessionTracker {
    private val _current = MutableStateFlow<Pair<String, SessionModelKey>?>(null)

    /** `(conversationId, modelKey)` of whatever conversation last reported itself active, or `null`. */
    val current: StateFlow<Pair<String, SessionModelKey>?> = _current.asStateFlow()

    /** Called whenever a `ChatViewModel` can resolve both its own conversation id and the model currently backing it. */
    fun report(conversationId: String, modelKey: SessionModelKey) {
        _current.value = conversationId to modelKey
    }

    fun clear() {
        _current.value = null
    }

    /** [clear], only while [conversationId] is the one reported: a report that came in since names the cache's new owner. */
    fun clearIf(conversationId: String) {
        _current.update { if (it?.first == conversationId) null else it }
    }
}
