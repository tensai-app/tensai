package io.github.hokanosekai.tensai.data.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks [KvCacheSize] against what llama.cpp actually allocated on the test device (OPPO CPH2251,
 * 8 GB, 2026-09-04), measured from the byte size of saved session files.
 *
 * The rule these exist to defend: the estimate may sit above the real allocation, never below.
 * Above costs context; below costs an out-of-memory kill on a device that had none to spare.
 */
class KvCacheSizeTest {

    private companion object {
        const val F16 = 2L
        const val Q8_0 = 1L

        /**
         * Qwen3-1.7B: 28 layers, 16 query heads, 8 KV heads, `key_length` = `value_length` = 128
         * (read from the model's own GGUF header). `28 * 8 * (128 + 128)` = 57 344 elements per
         * token, so 114 688 bytes at F16.
         */
        const val QWEN3_KV_ELEMENTS = 57_344L

        /**
         * Two F16 sessions saved on device for that model: 762 tokens in 87 405 157 bytes and 2879
         * tokens in 330 118 825 bytes. The growth between them is
         * `(330 118 825 - 87 405 157) / (2879 - 762)` = 114 650 bytes per token — a session file
         * also carries token ids and its own header on top of the cache, so this is the closest
         * measurable proxy for the KV cache rather than the cache itself.
         */
        const val QWEN3_MEASURED_BYTES_PER_TOKEN_F16 = 114_650L
    }

    @Test
    fun qwen3EstimateMatchesTheCacheMeasuredOnDevice() {
        val qwen3 = model(embeddingLength = 2_048, blockCount = 28, kvFullElementsPerToken = QWEN3_KV_ELEMENTS)

        val estimated = KvCacheSize.bytesAt(qwen3, nCtx = 1, bytesPerElement = F16)

        assertEquals(114_688L, estimated)
        // Within 0.1% of the measured per-token growth, and on the high side of it.
        assertTrue(
            "estimate $estimated must not fall under the measured $QWEN3_MEASURED_BYTES_PER_TOKEN_F16",
            estimated >= QWEN3_MEASURED_BYTES_PER_TOKEN_F16,
        )
        assertTrue(estimated <= QWEN3_MEASURED_BYTES_PER_TOKEN_F16 * 1001 / 1000)
    }

    /** The whole point of the ticket: the old formula was exactly 2x out on this model. */
    @Test
    fun qwen3FallbackIsTwiceTheRealCache() {
        val known = model(embeddingLength = 2_048, blockCount = 28, kvFullElementsPerToken = QWEN3_KV_ELEMENTS)
        val unknown = model(embeddingLength = 2_048, blockCount = 28)

        assertEquals(
            2 * KvCacheSize.bytesAt(known, nCtx = 4_096, bytesPerElement = F16),
            KvCacheSize.bytesAt(unknown, nCtx = 4_096, bytesPerElement = F16),
        )
    }

    /**
     * A multi-head model (`n_head_kv == n_head`, `key_length` = `embeddingLength / n_head`) is the
     * one case the old formula got right, so the fallback has to agree with the real shape there —
     * that's what makes it safe to keep for models whose header didn't yield one.
     */
    @Test
    fun fallbackIsExactForMultiHeadAttention() {
        // 20 layers, 8 heads of 125 = embeddingLength 1000, no grouping: 20 * 8 * (125 + 125).
        val known = model(embeddingLength = 1_000, blockCount = 20, kvFullElementsPerToken = 40_000)
        val unknown = model(embeddingLength = 1_000, blockCount = 20)

        assertEquals(
            KvCacheSize.bytesAt(unknown, nCtx = 4_096, bytesPerElement = F16),
            KvCacheSize.bytesAt(known, nCtx = 4_096, bytesPerElement = F16),
        )
    }

    /**
     * Shape of a real `gemma4` header: 25 windowed layers (8 KV heads of 256) and 5 full-attention
     * ones (2 KV heads of 512), window 1024. `llama_kv_cache_iswa` sizes the windowed cache at
     * `pad256(min(n_ctx, 1024 + 512))`, so past 1536 tokens only the 5 full-attention layers keep
     * growing.
     */
    @Test
    fun slidingWindowLayersStopGrowingPastTheWindow() {
        val gemma = model(
            embeddingLength = 2_816,
            blockCount = 30,
            kvFullElementsPerToken = 10_240,
            kvSwaElementsPerToken = 102_400,
            slidingWindow = 1_024,
        )

        // 2 * (10 240 * 4096 + 102 400 * 1536)
        assertEquals(398_458_880L, KvCacheSize.bytesAt(gemma, nCtx = 4_096, bytesPerElement = F16))

        // Doubling the context past the window only doubles the full-attention term.
        val at8192 = KvCacheSize.bytesAt(gemma, nCtx = 8_192, bytesPerElement = F16)
        assertEquals(398_458_880L + 2 * 10_240L * 4_096, at8192)
    }

    /** Below the window every layer is still growing, so the two terms are simply summed. */
    @Test
    fun belowTheWindowEveryLayerCostsFullPrice() {
        val gemma = model(
            embeddingLength = 2_816,
            blockCount = 30,
            kvFullElementsPerToken = 10_240,
            kvSwaElementsPerToken = 102_400,
            slidingWindow = 1_024,
        )

        assertEquals(
            2 * (10_240L + 102_400L) * 512,
            KvCacheSize.bytesAt(gemma, nCtx = 512, bytesPerElement = F16),
        )
    }

    @Test
    fun maxCtxFittingIsTheLargestContextThatStillFits() {
        val qwen3 = model(embeddingLength = 2_048, blockCount = 28, kvFullElementsPerToken = QWEN3_KV_ELEMENTS)
        val budget = 300_000_000L

        val fitting = KvCacheSize.maxCtxFitting(qwen3, budget, Q8_0, maxCtx = 32_768)

        assertEquals(300_000_000L / QWEN3_KV_ELEMENTS, fitting)
        assertTrue(KvCacheSize.bytesAt(qwen3, fitting, Q8_0) <= budget)
        assertTrue(KvCacheSize.bytesAt(qwen3, fitting + 1, Q8_0) > budget)
    }

    /**
     * The search has to stay correct across the kink where the windowed term flattens — a plain
     * bytes-per-token division doesn't.
     */
    @Test
    fun maxCtxFittingHandlesTheSlidingWindowKink() {
        val gemma = model(
            embeddingLength = 2_816,
            blockCount = 30,
            kvFullElementsPerToken = 10_240,
            kvSwaElementsPerToken = 102_400,
            slidingWindow = 1_024,
        )
        val budget = 398_458_880L

        assertEquals(4_096L, KvCacheSize.maxCtxFitting(gemma, budget, F16, maxCtx = 32_768))
        assertTrue(KvCacheSize.bytesAt(gemma, 4_097, F16) > budget)
    }

    @Test
    fun anEmptyBudgetFitsNoContextAtAll() {
        val qwen3 = model(embeddingLength = 2_048, blockCount = 28, kvFullElementsPerToken = QWEN3_KV_ELEMENTS)

        assertEquals(0L, KvCacheSize.maxCtxFitting(qwen3, availableBytes = 0, bytesPerElement = F16, maxCtx = 4_096))
    }

    private fun model(
        embeddingLength: Long,
        blockCount: Long,
        kvFullElementsPerToken: Long = 0L,
        kvSwaElementsPerToken: Long = 0L,
        slidingWindow: Long = 0L,
    ) = ModelEntity(
        id = "test-model",
        displayName = "Test model",
        fileName = "test.gguf",
        filePath = "/nonexistent/test.gguf",
        sizeBytes = 1_000_000_000L,
        architecture = "test",
        quantLabel = "Q8_0",
        paramCount = 1_000_000_000L,
        contextLength = 32_768L,
        embeddingLength = embeddingLength,
        blockCount = blockCount,
        supportsThinking = false,
        importedAt = 0L,
        kvFullElementsPerToken = kvFullElementsPerToken,
        kvSwaElementsPerToken = kvSwaElementsPerToken,
        slidingWindow = slidingWindow,
    )
}
