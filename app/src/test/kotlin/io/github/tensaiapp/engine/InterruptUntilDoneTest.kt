package io.github.tensaiapp.engine

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [interruptUntilDone] against a fake native call whose first interrupt is erased, like `rewind()` does (TEN-88). */
class InterruptUntilDoneTest {

    /** Blocks like `nativePrefill`: only an interrupt that arrives *after* its entry reset ends it. */
    private class FakeNativePrefill {
        val calls = AtomicInteger(0)
        private val flag = AtomicBoolean(false)

        fun interrupt() {
            // The first one lands before the call "clears the flag on entry": lost.
            if (calls.incrementAndGet() > 1) flag.set(true)
        }

        // Bounded so a regression is a red test, not a thread that never ends.
        fun run(): Boolean {
            val deadline = System.nanoTime() + 3_000_000_000L
            while (!flag.get() && System.nanoTime() < deadline) Thread.sleep(2)
            return flag.get()
        }
    }

    @Test
    fun aLostFirstInterruptIsRepeatedUntilThePrefillReturns() = runBlocking {
        val native = FakeNativePrefill()
        val work = async(Dispatchers.Default) { native.run() }

        withTimeout(5_000) { interruptUntilDone(work, intervalMs = 10) { native.interrupt() } }

        assertTrue("the prefill ended because an interrupt reached it", work.await())
        assertTrue("the first interrupt was lost, so at least a second was needed", native.calls.get() >= 2)
    }

    @Test
    fun noInterruptFiresOnceThePrefillHasReturned() = runBlocking {
        val native = FakeNativePrefill()
        val work = launch(Dispatchers.Default) { native.run() }
        withTimeout(5_000) { interruptUntilDone(work, intervalMs = 10) { native.interrupt() } }
        val atReturn = native.calls.get()

        delay(200)

        assertEquals("a ticker left running would hit the next generation", atReturn, native.calls.get())
    }
}
