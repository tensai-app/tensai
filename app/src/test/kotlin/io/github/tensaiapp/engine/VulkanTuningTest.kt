package io.github.tensaiapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanTuningTest {

    private val tuned = VulkanLoadTuning(
        nBatch = 512,
        nUbatch = 128,
        flashAttn = FlashAttnMode.Off,
        typeK = "q8_0",
        typeV = "q8_0",
        nThreads = 3,
        nThreadsBatch = 4,
        env = mapOf("LM_GGML_VK_SUBMIT_MFLOPS" to "2000"),
    )

    @Test
    fun `none changes nothing`() {
        assertEquals(VulkanLoadTuning(), VulkanLoadTuning.NONE)
        assertTrue(VulkanTuning.envEntries(VulkanLoadTuning.NONE).isEmpty())
        assertEquals(null, FlashAttnMode.Auto.arg)
        assertEquals(VulkanLoadTuning.NONE, VulkanTuning.forLoad(0))
    }

    @Test
    fun `gpu load applies the overrides`() {
        assertEquals(tuned, VulkanTuning.forLoad(99, tuned))
        assertEquals(tuned, VulkanTuning.forLoad(1, tuned))
    }

    @Test
    fun `cpu load ignores the overrides`() {
        assertEquals(VulkanLoadTuning.NONE, VulkanTuning.forLoad(0, tuned))
    }

    @Test
    fun `env entries are KEY=VALUE`() {
        assertEquals(listOf("LM_GGML_VK_SUBMIT_MFLOPS=2000"), VulkanTuning.envEntries(tuned))
    }

    @Test
    fun `flash attn args`() {
        assertEquals("on", FlashAttnMode.On.arg)
        assertEquals("off", FlashAttnMode.Off.arg)
    }

    @Test
    fun `current tuning is the measured one`() {
        assertEquals(128, VulkanTuning.CURRENT.nUbatch)
        assertEquals(
            mapOf(
                "LM_GGML_VK_SUBMIT_MFLOPS" to "200",
                "LM_GGML_VK_QUEUE_PRIORITY" to "low",
            ),
            VulkanTuning.CURRENT.env,
        )
    }

    @Test
    fun `env entries keep every pair`() {
        val two = VulkanLoadTuning(env = mapOf("A" to "1", "B" to "2"))
        assertEquals(listOf("A=1", "B=2"), VulkanTuning.envEntries(two))
    }

    @Test
    fun `applyEnv sets once`() {
        VulkanTuning.resetEnvAppliedForTest()
        val calls = mutableListOf<List<String>>()
        VulkanTuning.applyEnv(tuned) { calls += it }
        VulkanTuning.applyEnv(tuned) { calls += it }
        assertEquals(listOf(listOf("LM_GGML_VK_SUBMIT_MFLOPS=2000")), calls)
        VulkanTuning.resetEnvAppliedForTest()
    }

    @Test
    fun `applyEnv with an empty map calls nothing`() {
        VulkanTuning.resetEnvAppliedForTest()
        var called = false
        VulkanTuning.applyEnv(VulkanLoadTuning.NONE) { called = true }
        assertTrue(!called)
        VulkanTuning.resetEnvAppliedForTest()
    }
}
