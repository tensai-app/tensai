package io.github.hokanosekai.tensai.runtime

import io.github.hokanosekai.tensai.compat.DeviceProfile
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.engine.KvCacheType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The first four cases share one synthetic model shape (embeddingLength=1000, blockCount=20,
 * sizeBytes=1GB, trained contextLength=32k) with no KV shape recorded, and vary only
 * [DeviceProfile.availableRamBytes], so the three [LoadPolicy.compute] branches (cap, Q8_0
 * fallback, RAM-forced reduction) are reachable with hand-checkable arithmetic rather than a real
 * GGUF's numbers. They also pin the pre-TEN-18 behaviour, which is what
 * [io.github.hokanosekai.tensai.data.models.KvCacheSize] still falls back to when a model's shape
 * is unknown.
 *
 * The cases after them use the real shapes of the two models TEN-18 was measured against on device
 * (OPPO CPH2251, 2026-09-04) to pin what those shapes buy in planned context. The estimate itself
 * is confronted with the measurements in
 * [io.github.hokanosekai.tensai.data.models.KvCacheSizeTest].
 */
class LoadPolicyTest {

    @Test
    fun largeRam_capsAtLatencyCeiling_usingF16() {
        // 8GB available leaves ~6.8GB for KV after weights + overhead — comfortably more than the
        // ~336MB an F16 KV cache needs at the 4096 cap, so the trained 32k context is capped, not
        // RAM-reduced.
        val model = model(contextLength = 32_768)
        val profile = deviceProfile(availableRamBytes = 8L * GB)

        val plan = LoadPolicy.compute(model, profile)

        assertEquals(LoadPolicy.CTX_LATENCY_CAP, plan.nCtx)
        assertEquals(KvCacheType.F16, plan.kvCacheType)
    }

    @Test
    fun tightRam_fallsBackToQ8_0AtTheWantedContext() {
        // availableForKv = 200MB: below the ~328MB F16 needs for 4096 tokens, but above the
        // ~164MB Q8_0 needs for the same 4096 — so Q8_0 kicks in without reducing the context.
        val model = model(contextLength = 32_768)
        val profile = deviceProfile(availableRamBytes = 200_000_000L + model.sizeBytes + OVERHEAD_BYTES)

        val plan = LoadPolicy.compute(model, profile)

        assertEquals(LoadPolicy.CTX_LATENCY_CAP, plan.nCtx)
        assertEquals(KvCacheType.Q8_0, plan.kvCacheType)
    }

    @Test
    fun veryTightRam_reducesContextEvenAtQ8_0() {
        // availableForKv = 50MB: below the ~40MB-per-1000-tokens Q8_0 rate too, so the context
        // itself is reduced (to 1250 tokens) rather than staying at the 4096 cap.
        val model = model(contextLength = 32_768)
        val profile = deviceProfile(availableRamBytes = 50_000_000L + model.sizeBytes + OVERHEAD_BYTES)

        val plan = LoadPolicy.compute(model, profile)

        assertEquals(1_250, plan.nCtx)
        assertEquals(KvCacheType.Q8_0, plan.kvCacheType)
    }

    @Test
    fun trainedContextBelowCap_isNeverExceeded() {
        val model = model(contextLength = 2_048)
        val profile = deviceProfile(availableRamBytes = 8L * GB)

        val plan = LoadPolicy.compute(model, profile)

        assertEquals(2_048, plan.nCtx)
        assertEquals(KvCacheType.F16, plan.kvCacheType)
    }

    /**
     * The measured shape of the Qwen3-1.7B on the test device: 28 layers, 8 KV heads of 128 (16
     * query heads, so grouped-query 2:1), no sliding window. `kvFullElementsPerToken` is
     * `28 * 8 * (128 + 128)` = 57 344, half of what `2 * embeddingLength * blockCount` = 114 688
     * assumed — the exact factor the old estimate was off by. Same RAM, and the corrected shape
     * buys the full 4096 where the old one had to cut the context to fit.
     */
    @Test
    fun groupedQueryShape_buysTheContextTheOldEstimateHadToCut() {
        val availableForKv = 300_000_000L
        val gqa = qwen3(kvFullElementsPerToken = 57_344L)
        val unknownShape = qwen3()
        val profile = deviceProfile(availableRamBytes = availableForKv + gqa.sizeBytes + OVERHEAD_BYTES)

        // 57 344 * 4096 * 1 byte (Q8_0) = 234 881 024, inside the budget.
        assertEquals(LoadPolicy.CTX_LATENCY_CAP, LoadPolicy.compute(gqa, profile).nCtx)
        assertEquals(KvCacheType.Q8_0, LoadPolicy.compute(gqa, profile).kvCacheType)

        // Same device, same model, shape unknown: 114 688 bytes/token at Q8_0 forces a cut to
        // 300 000 000 / 114 688 = 2615.
        assertEquals(2_615, LoadPolicy.compute(unknownShape, profile).nCtx)
    }

    /**
     * The sentinel path: a model imported before TEN-18 has `kvFullElementsPerToken = 0`, and must
     * plan exactly as it did before — never larger. Pinned by giving a second model the shape the
     * old formula assumed (`2 * 1000 * 20` elements per token) and requiring identical plans.
     */
    @Test
    fun unknownKvShape_plansExactlyAsThePreTen18EstimateDid() {
        val unknownShape = model(contextLength = 32_768)
        val spelledOut = model(contextLength = 32_768, kvFullElementsPerToken = 40_000L)

        for (ram in listOf(8L * GB, 200_000_000L, 50_000_000L)) {
            val profile = deviceProfile(availableRamBytes = ram + unknownShape.sizeBytes + OVERHEAD_BYTES)
            assertEquals(
                "unknown shape must not plan differently from the estimate it falls back to",
                LoadPolicy.compute(spelledOut, profile),
                LoadPolicy.compute(unknownShape, profile),
            )
        }
    }

    /**
     * Sliding-window layers stop growing past the window, so the plan shouldn't pay for them at
     * full context. Shape taken from a real `gemma4` header: 30 layers, 25 windowed (8 KV heads of
     * 256) and 5 full-attention (2 KV heads of 512), window 1024.
     *
     * At 4096 the windowed cache is `min(4096, 1024 + 512)` = 1536 cells, so F16 costs
     * `2 * (10 240 * 4096 + 102 400 * 1536)` = 398 458 880 bytes — against 1 761 607 680 if those
     * 25 layers were charged at full context, which is the budget this device doesn't have.
     */
    @Test
    fun slidingWindowLayers_arentChargedAtFullContext() {
        val gemma = model(
            contextLength = 32_768,
            kvFullElementsPerToken = 10_240L,
            kvSwaElementsPerToken = 102_400L,
            slidingWindow = 1_024L,
        )
        val profile = deviceProfile(availableRamBytes = 400_000_000L + gemma.sizeBytes + OVERHEAD_BYTES)

        val plan = LoadPolicy.compute(gemma, profile)

        assertEquals(LoadPolicy.CTX_LATENCY_CAP, plan.nCtx)
        assertEquals(KvCacheType.F16, plan.kvCacheType)
    }

    /**
     * The Qwen3-1.7B's own dimensions, so the fallback branch computes the estimate this model
     * really used to get (`2 * 2048 * 28` elements per token) rather than the synthetic shape the
     * branch tests share.
     */
    private fun qwen3(kvFullElementsPerToken: Long = 0L) = model(
        contextLength = 32_768,
        kvFullElementsPerToken = kvFullElementsPerToken,
    ).copy(embeddingLength = 2_048L, blockCount = 28L)

    private fun model(
        contextLength: Long,
        kvFullElementsPerToken: Long = 0L,
        kvSwaElementsPerToken: Long = 0L,
        slidingWindow: Long = 0L,
    ) = ModelEntity(
        id = "test-model",
        displayName = "Test model",
        fileName = "test.gguf",
        filePath = "/nonexistent/test.gguf",
        sizeBytes = 1_000_000_000L,
        architecture = "qwen2",
        quantLabel = "Q4_K_M",
        paramCount = 1_000_000_000L,
        contextLength = contextLength,
        embeddingLength = 1_000L,
        blockCount = 20L,
        supportsThinking = false,
        importedAt = 0L,
        kvFullElementsPerToken = kvFullElementsPerToken,
        kvSwaElementsPerToken = kvSwaElementsPerToken,
        slidingWindow = slidingWindow,
    )

    private fun deviceProfile(availableRamBytes: Long) = DeviceProfile(
        totalRamBytes = 8L * GB,
        availableRamBytes = availableRamBytes,
        socModel = "test-soc",
        bigCoreCount = 4,
        hasVulkan = false,
        hasOpenCl = false,
    )

    private companion object {
        const val GB = 1024L * 1024 * 1024
        const val OVERHEAD_BYTES = 300L * 1024 * 1024
    }
}
