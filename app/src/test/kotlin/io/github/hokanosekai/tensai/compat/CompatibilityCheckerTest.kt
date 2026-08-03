package io.github.hokanosekai.tensai.compat

import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.engine.BackendHint
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Calibration set drawn from `third_party/llama.kt/README.md`'s "Measured performance" table —
 * an OPPO CPH2251 (MediaTek Dimensity 900, 8GB RAM, Mali-G68, Vulkan but no OpenCL). Model
 * dimensions (embeddingLength/blockCount) are each model family's published config, sizes are
 * approximate Q4_K_M file sizes for that model — exact enough to exercise the thresholds in
 * [CompatibilityChecker], not meant to byte-match a real GGUF.
 */
class CompatibilityCheckerTest {

    private val dimensity900_8gb = DeviceProfile(
        totalRamBytes = 8L * GB,
        availableRamBytes = 4L * GB,
        socModel = "MT6789 (Dimensity 900)",
        bigCoreCount = 2,
        hasVulkan = true,
        hasOpenCl = false,
    )

    @Test
    fun qwen2_5_0_5b_runsWell() {
        val model = model(
            architecture = "qwen2",
            paramCount = 494_000_000,
            contextLength = 32_768,
            embeddingLength = 896,
            blockCount = 24,
            sizeBytes = 380_000_000,
        )

        val result = CompatibilityChecker.check(model, dimensity900_8gb)

        assertEquals(Verdict.RunsWell, result.verdict)
        assertEquals(BackendHint.Cpu, result.recommendedBackend)
    }

    @Test
    fun qwen2_5_3b_runsWell() {
        val model = model(
            architecture = "qwen2",
            paramCount = 3_090_000_000,
            contextLength = 32_768,
            embeddingLength = 2048,
            blockCount = 36,
            sizeBytes = 1_930_000_000,
        )

        val result = CompatibilityChecker.check(model, dimensity900_8gb)

        assertEquals(Verdict.RunsWell, result.verdict)
        assertEquals(BackendHint.Cpu, result.recommendedBackend)
    }

    @Test
    fun phi4_mini_3_8b_tightFit() {
        val model = model(
            architecture = "phi3",
            paramCount = 3_800_000_000,
            contextLength = 131_072,
            embeddingLength = 3072,
            blockCount = 32,
            sizeBytes = 2_300_000_000,
        )

        val result = CompatibilityChecker.check(model, dimensity900_8gb)

        assertEquals(Verdict.TightFit, result.verdict)
        // Measured table: Phi-4-mini runs better on the GPU (2.3 vs 1.7 tok/s) — Vulkan is available.
        assertEquals(BackendHint.Gpu, result.recommendedBackend)
    }

    @Test
    fun gemma2_9b_wontFit() {
        val model = model(
            architecture = "gemma2",
            paramCount = 9_000_000_000,
            contextLength = 8_192,
            embeddingLength = 3584,
            blockCount = 42,
            sizeBytes = 5_800_000_000,
        )

        val result = CompatibilityChecker.check(model, dimensity900_8gb)

        assertEquals(Verdict.WontFit, result.verdict)
        assertEquals(BackendHint.Cpu, result.recommendedBackend)
    }

    @Test
    fun gemma3n_isTightRegardlessOfSize_andPrefersGpuWhenVulkanAvailable() {
        // Gemma-3n-E2B-it: ~2B params, measured ~1.1-2.1 tok/s on every backend — small enough
        // to comfortably fit in RAM, but never "runs well" on this device class.
        val model = model(
            architecture = "gemma3n",
            paramCount = 2_000_000_000,
            contextLength = 32_768,
            embeddingLength = 2048,
            blockCount = 30,
            sizeBytes = 1_200_000_000,
        )

        val result = CompatibilityChecker.check(model, dimensity900_8gb)

        assertEquals(Verdict.TightFit, result.verdict)
        assertEquals(BackendHint.Gpu, result.recommendedBackend)
    }

    @Test
    fun gemma4_fallsBackToCpuWithoutVulkan() {
        val model = model(
            architecture = "gemma4",
            paramCount = 2_000_000_000,
            contextLength = 32_768,
            embeddingLength = 2048,
            blockCount = 30,
            sizeBytes = 1_200_000_000,
        )
        val noVulkanProfile = dimensity900_8gb.copy(hasVulkan = false)

        val result = CompatibilityChecker.check(model, noVulkanProfile)

        // No GPU device to offload to — CPU is the only option left, even though it's the slow
        // path for this architecture (see the measured table: Gemma-4-E2B is unusable on CPU).
        assertEquals(BackendHint.Cpu, result.recommendedBackend)
    }

    @Test
    fun unknownArchitectureDefaultsToAuto() {
        val model = model(
            architecture = "mamba2",
            paramCount = 1_000_000_000,
            contextLength = 4096,
            embeddingLength = 2048,
            blockCount = 24,
            sizeBytes = 700_000_000,
        )

        val result = CompatibilityChecker.check(model, dimensity900_8gb)

        assertEquals(BackendHint.Auto, result.recommendedBackend)
    }

    @Test
    fun explanationReportsGigabytes() {
        val model = model(
            architecture = "qwen2",
            paramCount = 494_000_000,
            contextLength = 32_768,
            embeddingLength = 896,
            blockCount = 24,
            sizeBytes = 380_000_000,
        )

        val result = CompatibilityChecker.check(model, dimensity900_8gb)

        assertEquals("needs ~1.0 GB, you have 8.0 GB", result.explanation)
    }

    private fun model(
        architecture: String,
        paramCount: Long,
        contextLength: Long,
        embeddingLength: Long,
        blockCount: Long,
        sizeBytes: Long,
    ) = ModelEntity(
        id = "test-$architecture",
        displayName = "Test model ($architecture)",
        fileName = "test.gguf",
        filePath = "/nonexistent/test.gguf",
        sizeBytes = sizeBytes,
        architecture = architecture,
        quantLabel = "Q4_K_M",
        paramCount = paramCount,
        contextLength = contextLength,
        embeddingLength = embeddingLength,
        blockCount = blockCount,
        supportsThinking = false,
        importedAt = 0L,
    )

    private companion object {
        const val GB = 1024L * 1024 * 1024
    }
}
