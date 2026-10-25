package io.github.tensaiapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `debug cpu load takes the knobs`() {
        val knobs = CpuLoadKnobs(decodeThreads = 3, kv = KvKnob.Q8_0).toTuning(batchThreads = 5)
        assertEquals(knobs, VulkanTuning.forLoad(0, tuned, cpuOverride = knobs, debug = true))
    }

    @Test
    fun `release cpu load never takes the knobs`() {
        val knobs = CpuLoadKnobs(decodeThreads = 3, kv = KvKnob.Q8_0).toTuning(batchThreads = 5)
        assertEquals(VulkanLoadTuning.NONE, VulkanTuning.forLoad(0, tuned, cpuOverride = knobs, debug = false))
    }

    @Test
    fun `gpu load ignores the cpu knobs`() {
        val knobs = CpuLoadKnobs(decodeThreads = 3).toTuning(batchThreads = 5)
        assertEquals(tuned, VulkanTuning.forLoad(99, tuned, cpuOverride = knobs, debug = true))
    }

    @Test
    fun `cpu load defaults to a q8_0 cache with flash attention on`() {
        assertEquals(
            LoadKv(KvCacheType.Q8_0, VulkanLoadTuning(flashAttn = FlashAttnMode.On)),
            VulkanTuning.kvForLoad(0, KvCacheType.F16, VulkanLoadTuning.NONE),
        )
    }

    @Test
    fun `cpu load keeps a cache already quantized, flash attention on`() {
        assertEquals(
            LoadKv(KvCacheType.Q8_0, VulkanLoadTuning(flashAttn = FlashAttnMode.On)),
            VulkanTuning.kvForLoad(0, KvCacheType.Q8_0, VulkanLoadTuning.NONE),
        )
    }

    @Test
    fun `gpu load keeps the requested cache`() {
        assertEquals(LoadKv(KvCacheType.F16, VulkanTuning.CURRENT), VulkanTuning.kvForLoad(99, KvCacheType.F16, VulkanTuning.CURRENT))
    }

    @Test
    fun `a cache type forced by the knobs wins on the cpu`() {
        val f16 = CpuLoadKnobs(kv = KvKnob.F16).toTuning(batchThreads = 5)
        assertEquals(LoadKv(KvCacheType.F16, f16), VulkanTuning.kvForLoad(0, KvCacheType.F16, f16))
        val q8 = CpuLoadKnobs(kv = KvKnob.Q8_0).toTuning(batchThreads = 5)
        assertEquals(LoadKv(KvCacheType.Q8_0, q8), VulkanTuning.kvForLoad(0, KvCacheType.F16, q8))
    }

    @Test
    fun `flash attention forced off keeps the f16 cache`() {
        val off = CpuLoadKnobs(flashAttn = FlashAttnMode.Off).toTuning(batchThreads = 5)
        assertEquals(LoadKv(KvCacheType.F16, off), VulkanTuning.kvForLoad(0, KvCacheType.F16, off))
    }

    @Test
    fun `flash attention forced off drops a quantized request to f16`() {
        val off = CpuLoadKnobs(flashAttn = FlashAttnMode.Off).toTuning(batchThreads = 5)
        assertEquals(LoadKv(KvCacheType.F16, off), VulkanTuning.kvForLoad(0, KvCacheType.Q8_0, off))
    }

    @Test
    fun `a model the quantized block does not fit keeps f16 and auto flash attention`() {
        assertEquals(
            LoadKv(KvCacheType.F16, VulkanLoadTuning.NONE),
            VulkanTuning.kvForLoad(0, KvCacheType.F16, VulkanLoadTuning.NONE, quantizedFits = false),
        )
        assertEquals(
            LoadKv(KvCacheType.F16, VulkanLoadTuning.NONE),
            VulkanTuning.kvForLoad(0, KvCacheType.Q8_0, VulkanLoadTuning.NONE, quantizedFits = false),
        )
    }

    @Test
    fun `catalog head dimensions take a quantized cache`() {
        // Llama 3.2 1B: 2048 / 32 heads = 64, 8 KV heads, 16 layers.
        assertTrue(VulkanTuning.quantizedKvFits(2048, 32, 8, 16, kvElementsPerToken = 16L * 8 * 2 * 64))
        // Qwen3 1.7B: head_dim 128.
        assertTrue(VulkanTuning.quantizedKvFits(2048, 16, 8, 28, kvElementsPerToken = 28L * 8 * 2 * 128))
        // Qwen3 0.6B: 1024 / 16 = 64 by default, key_length 128.
        assertTrue(VulkanTuning.quantizedKvFits(1024, 16, 8, 28, kvElementsPerToken = 28L * 8 * 2 * 128))
    }

    @Test
    fun `a head dimension of 80 does not take a quantized cache`() {
        // Phi-2: 2560 / 32 = 80.
        assertFalse(VulkanTuning.quantizedKvFits(2560, 32, 32, 32, kvElementsPerToken = 32L * 32 * 2 * 80))
        // 2048 / 16 = 128 by default, but key_length 80.
        assertFalse(VulkanTuning.quantizedKvFits(2048, 16, 8, 24, kvElementsPerToken = 24L * 8 * 2 * 80))
    }

    @Test
    fun `an unreadable header does not take a quantized cache`() {
        assertFalse(VulkanTuning.quantizedKvFits(0, 0, 0, 0, 0))
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
