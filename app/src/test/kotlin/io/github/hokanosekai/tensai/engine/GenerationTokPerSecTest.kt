package io.github.hokanosekai.tensai.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** TEN-109: tok/s is generation speed, prefill excluded. */
class GenerationTokPerSecTest {
    @Test
    fun `prefill is excluded from the time base`() {
        // 232 tok, 92 567 ms TTFT, 138 567 ms total: 46 000 ms of decode, 5.04 tok/s.
        // The old definition (232 / 138.567 s) gave 1.67.
        assertEquals(5.043f, generationTokPerSec(232, 138_567L, 92_567L), 0.001f)
    }

    @Test
    fun `no prefill gives the plain ratio`() {
        assertEquals(10f, generationTokPerSec(10, 1_000L, 0L), 0.0001f)
    }

    @Test
    fun `ttft at or past total is not measurable`() {
        assertEquals(0f, generationTokPerSec(5, 1_000L, 1_000L), 0f)
        assertEquals(0f, generationTokPerSec(5, 1_000L, 1_500L), 0f)
    }

    @Test
    fun `zero or one token is not measurable`() {
        assertEquals(0f, generationTokPerSec(0, 1_000L, 0L), 0f)
        assertEquals(0f, generationTokPerSec(1, 1_000L, 990L), 0f)
    }
}
