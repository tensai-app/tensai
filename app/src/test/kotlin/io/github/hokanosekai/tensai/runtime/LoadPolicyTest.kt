package io.github.hokanosekai.tensai.runtime

import io.github.hokanosekai.tensai.compat.DeviceProfile
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.engine.KvCacheType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every case here shares one synthetic model shape (embeddingLength=1000, blockCount=20,
 * sizeBytes=1GB, trained contextLength=32k) and varies only [DeviceProfile.availableRamBytes], so
 * the three [LoadPolicy.compute] branches (cap, Q8_0 fallback, RAM-forced reduction) are reachable
 * with hand-checkable arithmetic rather than a real GGUF's numbers.
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

    private fun model(contextLength: Long) = ModelEntity(
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
