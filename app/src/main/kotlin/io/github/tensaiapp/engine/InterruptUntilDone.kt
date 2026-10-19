package io.github.tensaiapp.engine

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Calls [interrupt] every [intervalMs] until [work] has finished, then stops (TEN-88).
 *
 * One interrupt is not enough for a native call that clears the flag on entry: `nativePrefill`
 * tokenizes and converts the prompt *before* its `rewind()` resets `is_interrupted`, so a cancel
 * landing between the caller's last `ensureActive()` and that reset is erased, and the prefill
 * would run to the end while whoever cancelled it blocks on a join. Repeating makes the window a
 * matter of one interval instead of the whole prefill.
 *
 * No interrupt can follow the return of the native call: the loop checks [work] before each one and
 * ends the moment it completes, and the caller only releases the engine (a generation, a free)
 * after this returns. The one stray that can still slip in, between that check and a [work] that
 * finishes in the same instant, lands on an idle flag, which every completion and prefill clears
 * with its own `rewind()` before reading it.
 *
 * Uncancellable: it is called from a coroutine that is already being cancelled.
 */
internal suspend fun interruptUntilDone(work: Job, intervalMs: Long = 50L, interrupt: () -> Unit) {
    withContext(NonCancellable) {
        // isCompleted, not isActive: a cancelled job that is still inside its native call is not active.
        while (!work.isCompleted) {
            interrupt()
            withTimeoutOrNull(intervalMs) { work.join() }
        }
    }
}
