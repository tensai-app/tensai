package io.github.tensaiapp.engine

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** [startInterruptible] against a fake native engine with llama.kt's sticky interrupt. */
class StartInterruptibleTest {

    /** `nativeInterrupt` / `nativeResetInterrupt` / `nativeCompletion`: only the reset clears the flag. */
    private class FakeNative {
        private val interrupted = AtomicBoolean(false)

        fun interrupt() = interrupted.set(true)

        fun resetInterrupt() = interrupted.set(false)

        /** Samples until interrupted or [nPredict], and returns the count, like `completion`. */
        fun completion(nPredict: Int): Int {
            var n = 0
            while (n < nPredict && !interrupted.get()) n++
            return n
        }
    }

    @Test
    fun aStopLandingAfterTheLastCheckStopsTheDecode() {
        val native = FakeNative()

        val sampled = startInterruptible(
            resetInterrupt = native::resetInterrupt,
            // The check passes, then the stop lands: the window before the native call starts.
            ensureActive = { native.interrupt() },
        ) { native.completion(nPredict = 100) }

        assertEquals("the stop must reach the decode, not be cleared on the way in", 0, sampled)
    }

    @Test
    fun aCallerCancelledBeforeTheResetNeverStartsTheDecode() {
        val native = FakeNative()
        var started = false
        val job = Job().apply { cancel() }

        assertThrows(CancellationException::class.java) {
            startInterruptible(native::resetInterrupt, ensureActive = { job.ensureActive() }) {
                started = true
                native.completion(nPredict = 100)
            }
        }

        assertFalse("the reset must not let a cancelled caller through", started)
    }

    @Test
    fun anInterruptLeftByThePreviousDecodeDoesNotStopTheNextOne() {
        val native = FakeNative()
        // What `awaitClose` leaves behind when a decode's flow closes.
        native.interrupt()

        val sampled = startInterruptible(native::resetInterrupt, ensureActive = {}) { native.completion(nPredict = 100) }

        assertEquals(100, sampled)
    }
}
