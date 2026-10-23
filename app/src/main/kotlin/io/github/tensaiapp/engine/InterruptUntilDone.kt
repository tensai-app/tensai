package io.github.tensaiapp.engine

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Calls [interrupt] every [intervalMs] until [work] ends, uncancellably: a backstop now that the interrupt is sticky, see [startInterruptible]. */
internal suspend fun interruptUntilDone(work: Job, intervalMs: Long = 50L, interrupt: () -> Unit) {
    withContext(NonCancellable) {
        // isCompleted, not isActive: a cancelled job that is still inside its native call is not active.
        while (!work.isCompleted) {
            interrupt()
            withTimeoutOrNull(intervalMs) { work.join() }
        }
    }
}
