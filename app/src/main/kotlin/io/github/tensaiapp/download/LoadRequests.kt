package io.github.tensaiapp.download

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The "Load" action of the "<model> is ready" notification, handed from `MainActivity` (which
 * receives the intent, cold start or not) to `TensaiShell` (which owns the NavController).
 *
 * A model id waits here until the shell consumes it and navigates to the existing load screen
 * (`TensaiDestination.Loading`), the same destination every other "Load" button uses. Nothing is
 * loaded from the notification itself: it opens the load flow, the user sees its progress.
 */
object LoadRequests {
    const val ACTION_LOAD_MODEL = "io.github.tensaiapp.action.LOAD_DOWNLOADED_MODEL"
    const val EXTRA_MODEL_ID = "modelId"

    private val _pending = MutableStateFlow<String?>(null)

    /** Model id to load, or null. */
    val pending: StateFlow<String?> = _pending

    /** Reads [intent] from `onCreate` / `onNewIntent`; ignores anything that is not the Load action. */
    fun offer(intent: Intent?) {
        if (intent?.action != ACTION_LOAD_MODEL) return
        // Reopened from the recents list: a stale tap, not a new one.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        _pending.value = intent.getStringExtra(EXTRA_MODEL_ID)
        // Consumed once: a recreation must not replay it.
        intent.action = null
    }

    /** The shell took [modelId]; clears it unless a newer one arrived meanwhile. */
    fun consume(modelId: String) {
        _pending.compareAndSet(modelId, null)
    }
}
