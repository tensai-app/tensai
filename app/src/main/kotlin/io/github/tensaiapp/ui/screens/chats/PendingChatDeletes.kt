package io.github.tensaiapp.ui.screens.chats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The undo window of a swipe delete on the Chats page (TEN-193). [delete] hides the chat at once and
 * puts it in [undoable], which the page shows as a "Chat deleted" bar with Undo; the real delete
 * ([onCommit]) only runs once the window times out. [undo] shows the row again: nothing was touched
 * yet, so a generation running for it goes on.
 *
 * A new delete ends the previous window early and commits it, so a later swipe never leaves an
 * earlier one waiting behind it. [flush] (the app leaving the foreground) and the cancellation of
 * [scope] (the page leaving) commit the same way. Each chat is committed at most once.
 */
internal class PendingChatDeletes(
    private val scope: CoroutineScope,
    private val onCommit: (String) -> Unit,
) {
    /** Chats not to show: in their undo window, or committed and not yet gone from the database. */
    var hidden by mutableStateOf(emptySet<String>())
        private set

    /** The chat whose undo window is open, null when none: the page shows the bar while set. */
    var undoable by mutableStateOf<String?>(null)
        private set

    private val undone = HashSet<String>()
    private var window: Job? = null

    fun delete(id: String, timeoutMillis: Long) {
        // One window per chat: a second report of the same swipe is moot.
        if (id in hidden) return
        window?.cancel()
        hidden = hidden + id
        undoable = id
        // Atomic: even with [scope] already cancelled the body runs, so the finally still commits.
        window = scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                delay(timeoutMillis)
            } finally {
                if (!undone.remove(id)) onCommit(id)
                if (undoable == id) undoable = null
            }
        }
    }

    /**
     * Undoes the open window. On record from the tap on: a timeout or a new swipe landing in the same
     * frame cannot turn it into a commit.
     */
    fun undo() {
        val id = undoable ?: return
        undoable = null
        undone += id
        hidden = hidden - id
        window?.cancel()
    }

    /** Commits the chat in its undo window now and drops the bar. */
    fun flush() {
        window?.cancel()
    }
}
