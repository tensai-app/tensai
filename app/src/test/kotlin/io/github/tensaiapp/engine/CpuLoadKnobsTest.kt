package io.github.tensaiapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CpuLoadKnobsTest {

    @Test
    fun `default knobs are today's load`() {
        assertEquals(VulkanLoadTuning.NONE, CpuLoadKnobs().toTuning(batchThreads = 5))
    }

    @Test
    fun `decode threads keep the prefill's own count`() {
        val tuning = CpuLoadKnobs(decodeThreads = 4).toTuning(batchThreads = 5)
        assertEquals(4, tuning.nThreads)
        assertEquals(5, tuning.nThreadsBatch)
    }

    @Test
    fun `unknown topology leaves the prefill count to llama-kt`() {
        assertNull(CpuLoadKnobs(decodeThreads = 4).toTuning(batchThreads = 0).nThreadsBatch)
    }

    @Test
    fun `auto threads force no prefill count`() {
        assertNull(CpuLoadKnobs(flashAttn = FlashAttnMode.On).toTuning(batchThreads = 5).nThreadsBatch)
    }

    @Test
    fun `q8_0 forces flash attention on`() {
        for (asked in FlashAttnMode.entries) {
            val knobs = CpuLoadKnobs(flashAttn = asked, kv = KvKnob.Q8_0)
            assertEquals(FlashAttnMode.On, knobs.effectiveFlashAttn)
            val tuning = knobs.toTuning(batchThreads = 5)
            assertEquals(FlashAttnMode.On, tuning.flashAttn)
            assertEquals("q8_0", tuning.typeK)
            assertEquals("q8_0", tuning.typeV)
        }
    }

    @Test
    fun `f16 is forced as f16 and leaves flash attention as asked`() {
        val tuning = CpuLoadKnobs(flashAttn = FlashAttnMode.Off, kv = KvKnob.F16).toTuning(batchThreads = 5)
        assertEquals(FlashAttnMode.Off, tuning.flashAttn)
        assertEquals("f16", tuning.typeK)
        assertEquals("f16", tuning.typeV)
    }

    @Test
    fun `only a forced layout changes a prefix identity`() {
        assertNull(VulkanLoadTuning.NONE.kvLayoutKey())
        assertNull(VulkanTuning.CURRENT.kvLayoutKey())
        assertNull(CpuLoadKnobs(decodeThreads = 3).toTuning(batchThreads = 5).kvLayoutKey())
        val q8 = CpuLoadKnobs(kv = KvKnob.Q8_0).toTuning(batchThreads = 5).kvLayoutKey()
        val faOff = CpuLoadKnobs(flashAttn = FlashAttnMode.Off).toTuning(batchThreads = 5).kvLayoutKey()
        assertNotEquals(null, q8)
        assertNotEquals(null, faOff)
        assertNotEquals(q8, faOff)
    }
}
