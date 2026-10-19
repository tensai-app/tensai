package io.github.tensaiapp.data.models

import io.github.tensaiapp.engine.KvCacheType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks [KvCacheSize] against what llama.cpp actually allocated on the test device (OPPO CPH2251,
 * 8 GB, 2026-09-04), measured from the byte size of saved session files.
 *
 * The rule these exist to defend: the estimate may sit above the real allocation, never below.
 * Above costs context; below costs an out-of-memory kill on a device that had none to spare.
 *
 * Two of the shapes below are measured, one is synthetic and says so. Nothing here is read from a
 * GGUF header at test time.
 */
class KvCacheSizeTest {

    private companion object {
        val F16 = KvCacheType.F16
        val Q8_0 = KvCacheType.Q8_0

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

        /**
         * Gemma-4-E2B, back-derived from the one measurement taken of it: a Q8_0 session of 249
         * tokens weighing 2 435 778 bytes, i.e. 9 781 bytes per token, i.e. `9781 * 32 / 34` =
         * 9 206 elements per token, rounded up to ggml's 32-element block.
         *
         * **This is not read from the model's header** — nobody has had the file off the device —
         * and a session file cannot say how the total splits between the full-attention and
         * sliding-window buckets. So the whole of it is booked in the full-attention bucket, which
         * is the bucket that keeps growing with `n_ctx`: if the real model puts some of it behind
         * a window, this over-estimates, which is the safe direction. The sliding-window branch is
         * exercised separately, by [SYNTHETIC_SWA_FULL_ELEMENTS] and friends.
         */
        const val GEMMA4_KV_ELEMENTS = 9_216L
        const val GEMMA4_MEASURED_BYTES_PER_TOKEN_Q8_0 = 9_781L

        /**
         * A made-up sliding-window shape, not any real model: 30 layers, 25 of them windowed at
         * `8 * (256 + 256)` = 4 096 elements each and 5 full-attention at `2 * (512 + 512)` = 2 048
         * each, window 1 024. Chosen so the two terms are far apart and the arithmetic in each case
         * below can be checked by hand — the numbers are an order of magnitude above anything
         * measured on the test device, and are not a claim about Gemma or anything else.
         */
        const val SYNTHETIC_SWA_FULL_ELEMENTS = 10_240L
        const val SYNTHETIC_SWA_WINDOWED_ELEMENTS = 102_400L
        const val SYNTHETIC_SWA_WINDOW = 1_024L
    }

    @Test
    fun qwen3EstimateMatchesTheCacheMeasuredOnDevice() {
        val qwen3 = model(embeddingLength = 2_048, blockCount = 28, kvFullElementsPerToken = QWEN3_KV_ELEMENTS)

        // Per-token cost is read as the growth across one 256-cell block rather than at nCtx = 1:
        // llama.cpp allocates in whole blocks of 256, so a single token and 256 of them cost the
        // same and only the slope between two blocks is a per-token rate.
        val estimated = (KvCacheSize.bytesAt(qwen3, nCtx = 512, F16) - KvCacheSize.bytesAt(qwen3, nCtx = 256, F16)) / 256

        assertEquals(114_688L, estimated)
        // Within 0.1% of the measured per-token growth, and on the high side of it.
        assertTrue(
            "estimate $estimated must not fall under the measured $QWEN3_MEASURED_BYTES_PER_TOKEN_F16",
            estimated >= QWEN3_MEASURED_BYTES_PER_TOKEN_F16,
        )
        assertTrue(estimated <= QWEN3_MEASURED_BYTES_PER_TOKEN_F16 * 1001 / 1000)
    }

    /**
     * The Q8_0 counterpart of the check above, and the one that catches "Q8_0 costs one byte per
     * element": it costs 34 bytes per 32 (`block_q8_0` in `ggml-common.h` is an f16 scale plus 32
     * int8 quants), so a naive byte-per-element reading lands 6.25% under what is allocated.
     */
    @Test
    fun qwen3Q8_0PaysForTheBlockScaleToo() {
        val qwen3 = model(embeddingLength = 2_048, blockCount = 28, kvFullElementsPerToken = QWEN3_KV_ELEMENTS)

        // 57 344 / 32 * 34 = 60 928, against the 57 344 a byte-per-element reading would give.
        assertEquals(
            60_928L,
            (KvCacheSize.bytesAt(qwen3, nCtx = 512, Q8_0) - KvCacheSize.bytesAt(qwen3, nCtx = 256, Q8_0)) / 256,
        )
        // The whole 4096-cell cache, which is the number llama.cpp logs on device.
        assertEquals(249_561_088L, KvCacheSize.bytesAt(qwen3, nCtx = 4_096, Q8_0))
    }

    /**
     * Same check against the only Gemma-4-E2B measurement there is — see [GEMMA4_KV_ELEMENTS] for
     * what that measurement does and doesn't pin down. `embeddingLength`/`blockCount` below are
     * the model's published dimensions and are unused here (the fallback only runs when
     * `kvFullElementsPerToken` is 0); nothing in this case depends on them.
     */
    @Test
    fun gemma4EstimateStaysAboveTheCacheMeasuredOnDevice() {
        val gemma = model(embeddingLength = 2_048, blockCount = 30, kvFullElementsPerToken = GEMMA4_KV_ELEMENTS)

        val estimated = (KvCacheSize.bytesAt(gemma, nCtx = 512, Q8_0) - KvCacheSize.bytesAt(gemma, nCtx = 256, Q8_0)) / 256

        assertEquals(9_792L, estimated)
        assertTrue(
            "estimate $estimated must not fall under the measured $GEMMA4_MEASURED_BYTES_PER_TOKEN_Q8_0",
            estimated >= GEMMA4_MEASURED_BYTES_PER_TOKEN_Q8_0,
        )
        assertTrue(estimated <= GEMMA4_MEASURED_BYTES_PER_TOKEN_Q8_0 * 1002 / 1000)
    }

    /** The whole point of the ticket: the old formula was exactly 2x out on this model. */
    @Test
    fun qwen3FallbackIsTwiceTheRealCache() {
        val known = model(embeddingLength = 2_048, blockCount = 28, kvFullElementsPerToken = QWEN3_KV_ELEMENTS)
        val unknown = model(embeddingLength = 2_048, blockCount = 28)

        assertEquals(
            2 * KvCacheSize.bytesAt(known, nCtx = 4_096, F16),
            KvCacheSize.bytesAt(unknown, nCtx = 4_096, F16),
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
            KvCacheSize.bytesAt(unknown, nCtx = 4_096, F16),
            KvCacheSize.bytesAt(known, nCtx = 4_096, F16),
        )
    }

    /**
     * `llama_kv_cache_iswa` sizes the windowed cache at `pad256(min(n_ctx, 1024 + 512))`, so past
     * 1536 tokens only the 5 full-attention layers of the [SYNTHETIC_SWA_FULL_ELEMENTS] shape keep
     * growing.
     */
    @Test
    fun slidingWindowLayersStopGrowingPastTheWindow() {
        val swaModel = syntheticSlidingWindowModel()

        // 2 * (10 240 * 4096 + 102 400 * 1536)
        assertEquals(398_458_880L, KvCacheSize.bytesAt(swaModel, nCtx = 4_096, F16))

        // Doubling the context past the window only doubles the full-attention term.
        val at8192 = KvCacheSize.bytesAt(swaModel, nCtx = 8_192, F16)
        assertEquals(398_458_880L + 2 * SYNTHETIC_SWA_FULL_ELEMENTS * 4_096, at8192)
    }

    /** Below the window every layer is still growing, so the two terms are simply summed. */
    @Test
    fun belowTheWindowEveryLayerCostsFullPrice() {
        val swaModel = syntheticSlidingWindowModel()

        assertEquals(
            2 * (SYNTHETIC_SWA_FULL_ELEMENTS + SYNTHETIC_SWA_WINDOWED_ELEMENTS) * 512,
            KvCacheSize.bytesAt(swaModel, nCtx = 512, F16),
        )
    }

    /**
     * `llama_context` rounds `n_ctx` up to 256 cells before any cache is built, so an odd request
     * is charged what the next multiple of 256 costs. This is the rounding the old 2x margin used
     * to swallow: asking for 1250 and being billed for 1250 is 30 cells of cache nobody budgeted.
     */
    @Test
    fun anUnalignedContextCostsWhatItsPaddingCosts() {
        val qwen3 = model(embeddingLength = 2_048, blockCount = 28, kvFullElementsPerToken = QWEN3_KV_ELEMENTS)

        assertEquals(
            KvCacheSize.bytesAt(qwen3, nCtx = 1_280, F16),
            KvCacheSize.bytesAt(qwen3, nCtx = 1_250, F16),
        )
        assertEquals(
            KvCacheSize.bytesAt(qwen3, nCtx = 256, F16),
            KvCacheSize.bytesAt(qwen3, nCtx = 1, F16),
        )
    }

    /**
     * The windowed cache is padded *after* the min against `n_ctx`, with no clamp back down
     * (`llama-kv-cache-iswa.cpp`), so a window that isn't block-aligned rounds up on its own.
     */
    @Test
    fun theWindowedCacheRoundsUpOnItsOwn() {
        // Window 1000 + 512 ubatch = 1512, which pads to 1536 cells, not 1512.
        val swaModel = syntheticSlidingWindowModel(window = 1_000)

        assertEquals(
            2 * (SYNTHETIC_SWA_FULL_ELEMENTS * 4_096 + SYNTHETIC_SWA_WINDOWED_ELEMENTS * 1_536),
            KvCacheSize.bytesAt(swaModel, nCtx = 4_096, F16),
        )
    }

    /**
     * A row claiming windowed layers but carrying no window is nonsense the JNI never writes — it
     * only ever fills `kvSwaElementsPerToken` and `slidingWindow` together. They are still two
     * independent columns, and the one thing this class may never do is answer *low*, so an
     * uncappable windowed cache is charged at the full context rather than dropped.
     */
    @Test
    fun windowedLayersWithNoWindowAreChargedAtFullContext() {
        val noWindow = syntheticSlidingWindowModel(window = 0)

        assertEquals(
            2 * (SYNTHETIC_SWA_FULL_ELEMENTS + SYNTHETIC_SWA_WINDOWED_ELEMENTS) * 4_096,
            KvCacheSize.bytesAt(noWindow, nCtx = 4_096, F16),
        )
    }

    @Test
    fun maxCtxFittingIsTheLargestContextThatStillFits() {
        val qwen3 = model(embeddingLength = 2_048, blockCount = 28, kvFullElementsPerToken = QWEN3_KV_ELEMENTS)
        val budget = 300_000_000L

        val fitting = KvCacheSize.maxCtxFitting(qwen3, budget, Q8_0, maxCtx = 32_768)

        // 60 928 bytes per cell, so 4923 cells' worth of budget — but only whole blocks of 256 are
        // answers the loader can act on, so 19 blocks: 4864 cells for 296 353 792 bytes.
        assertEquals(4_864L, fitting)
        assertTrue(KvCacheSize.bytesAt(qwen3, fitting, Q8_0) <= budget)
        assertTrue(KvCacheSize.bytesAt(qwen3, fitting + 1, Q8_0) > budget)
    }

    /**
     * The answer has to be a context whose *own* padding still fits, otherwise the caller spends
     * more than the budget it just checked.
     */
    @Test
    fun maxCtxFittingNeverReturnsAContextItsPaddingBusts() {
        val qwen3 = model(embeddingLength = 2_048, blockCount = 28, kvFullElementsPerToken = QWEN3_KV_ELEMENTS)

        for (budget in listOf(1L, 60_928L, 300_000_000L, 1_000_000_000L)) {
            val fitting = KvCacheSize.maxCtxFitting(qwen3, budget, Q8_0, maxCtx = 32_768)
            assertTrue(
                "n_ctx $fitting must fit in $budget once padded to 256",
                KvCacheSize.bytesAt(qwen3, fitting, Q8_0) <= budget,
            )
        }
    }

    /**
     * The search has to stay correct across the kink where the windowed term flattens — a plain
     * bytes-per-token division doesn't.
     */
    @Test
    fun maxCtxFittingHandlesTheSlidingWindowKink() {
        val swaModel = syntheticSlidingWindowModel()
        val budget = 398_458_880L

        assertEquals(4_096L, KvCacheSize.maxCtxFitting(swaModel, budget, F16, maxCtx = 32_768))
        assertTrue(KvCacheSize.bytesAt(swaModel, 4_097, F16) > budget)
    }

    @Test
    fun anEmptyBudgetFitsNoContextAtAll() {
        val qwen3 = model(embeddingLength = 2_048, blockCount = 28, kvFullElementsPerToken = QWEN3_KV_ELEMENTS)

        assertEquals(0L, KvCacheSize.maxCtxFitting(qwen3, availableBytes = 0, kvCacheType = F16, maxCtx = 4_096))
    }

    private fun syntheticSlidingWindowModel(window: Long = SYNTHETIC_SWA_WINDOW) = model(
        embeddingLength = 2_816,
        blockCount = 30,
        kvFullElementsPerToken = SYNTHETIC_SWA_FULL_ELEMENTS,
        kvSwaElementsPerToken = SYNTHETIC_SWA_WINDOWED_ELEMENTS,
        slidingWindow = window,
    )

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
