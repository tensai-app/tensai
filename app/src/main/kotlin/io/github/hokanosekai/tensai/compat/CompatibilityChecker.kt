package io.github.hokanosekai.tensai.compat

import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.engine.BackendHint
import java.util.Locale

/** Traffic-light verdict for whether a [ModelEntity] is a good idea to load on this device. */
enum class Verdict {
    /** Comfortably fits in RAM at a usable generation speed. */
    RunsWell,

    /** Loads, but is close to the RAM budget or expected to generate slowly (~2 tok/s or below). */
    TightFit,

    /** Expected to be killed at load time (OOM on CPU, or hard-killed on GPU — see this file's KDoc). */
    WontFit,
}

/**
 * Result of [CompatibilityChecker.check].
 *
 * @param label short, human-readable verdict ("Runs well", "Tight fit", "Won't fit").
 * @param explanation one-line, numbers-first justification, e.g. "needs ~4.1 GB, you have 8.0 GB".
 * @param recommendedBackend backend this model's architecture is expected to run best on for
 *   *this* device (see [CompatibilityChecker]'s per-architecture table) — a hint for the loader's
 *   default, not a guarantee; the user can still override it ("load anyway" is a UI decision, not
 *   this checker's).
 */
data class Compatibility(
    val verdict: Verdict,
    val label: String,
    val explanation: String,
    val recommendedBackend: BackendHint,
)

/**
 * Estimates whether a [ModelEntity] will run acceptably on this device and which backend it
 * should prefer, calibrated against the measured pp/tg table in
 * `third_party/llama.kt/README.md` ("Status" + "Measured performance" sections) for an
 * OPPO CPH2251 (MediaTek Dimensity 900, 8GB RAM, Mali-G68 — no cooperative-matrix GPU path).
 * That table is this object's source of truth; re-calibrate the constants below if it changes.
 *
 * Two device-class facts drive every threshold here:
 * - **The practical size ceiling is memory pressure, not OOM, and it is backend-dependent.**
 *   A CPU load mmaps weights (file-backed, evictable) — it survives past its comfortable size but
 *   thrashes (e.g. Qwen2.5-7B Q4 measured at 0.2 tok/s). A GPU load copies weights into
 *   unevictable Vulkan buffers on the same physical (UMA) RAM — it gets hard-killed instead of
 *   thrashing. So the GPU ceiling is *stricter* than the CPU one, and going over it isn't a slow
 *   "tight fit", it's a kill.
 * - **Backend preference is per-architecture, not a blanket rule.** Dense transformers (llama,
 *   qwen, plain gemma) run fastest on the big CPU cores — Vulkan loses on both prefill and decode
 *   because Mali has no cooperative-matrix path. Gemma 3n/4 and Phi are the exceptions: their
 *   llama.cpp CPU path is disproportionately slow (Gemma 4 E2B is outright unusable on CPU), so
 *   GPU offload wins when a Vulkan device is available.
 */
object CompatibilityChecker {

    /** K and V caches are each sized the same — see [kvCacheBytes]. */
    private const val KV_CACHE_TENSORS = 2L

    /** f16 KV cache (the engine's default, [io.github.hokanosekai.tensai.engine.KvCacheType.F16]). */
    private const val KV_BYTES_PER_ELEMENT_F16 = 2L

    /**
     * Context length assumed for the KV-cache estimate — matches
     * [io.github.hokanosekai.tensai.engine.ModelLoadConfig]'s default `nCtx`, not
     * [ModelEntity.contextLength] (the model's *trained* max, which for e.g. Qwen2.5 is 32k+ and
     * would wildly overestimate KV memory for a model that will actually be loaded at 4096).
     * [kvCacheBytes] takes the smaller of the two, so a model whose trained context is below this
     * isn't overestimated either.
     */
    private const val PLANNED_CTX_TOKENS = 4096L

    /** Rough allowance for compute buffers/graph overhead beyond weights + KV cache. */
    private const val OVERHEAD_BYTES = 300L * 1024 * 1024

    /** Reserved for the OS and the rest of the running system — not available to the model. */
    private const val SYSTEM_RAM_MARGIN_BYTES = 1_536L * 1024 * 1024

    private const val BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0

    /**
     * Dense models at or above this size sit at or below the ~2 tok/s usable floor on this device
     * class per the measured table (Phi-4-mini, 3.8B: 1.7-2.3 tok/s; Qwen2.5-7B, 7B: 0.2 tok/s),
     * while everything measured below it (Qwen2.5-0.5B/3B, Llama-3.2-3B) stays comfortably above.
     * No per-device tok/s benchmark is available at check time, so param count is used as the
     * proxy for size class — matches the ~4B "usable ceiling" called out in the table's analysis.
     */
    private const val TIGHT_PARAM_THRESHOLD = 3_500_000_000L

    /**
     * Architectures measured at ~1-2 tok/s on *every* backend regardless of size (Gemma-3n-E2B,
     * Gemma-4-E2B, both ~2B) — never "runs well" on this device class, independent of RAM fit.
     */
    private val TIGHT_ARCHITECTURE_MARKERS = listOf("gemma3n", "gemma4")

    /** Architectures whose llama.cpp CPU path is disproportionately slow — prefer GPU offload. */
    private val GPU_PREFERRED_ARCH_MARKERS = listOf("gemma3n", "gemma4", "phi")

    /** Dense architectures — fastest on the big CPU cores (see this object's KDoc). */
    private val DENSE_ARCH_MARKERS = listOf("qwen", "llama", "gemma")

    fun check(model: ModelEntity, profile: DeviceProfile): Compatibility {
        val estimatedBytes = model.sizeBytes + kvCacheBytes(model) + OVERHEAD_BYTES

        val wontFit = estimatedBytes > profile.totalRamBytes - SYSTEM_RAM_MARGIN_BYTES
        val tightFit = !wontFit && (estimatedBytes > profile.availableRamBytes || isLikelySlow(model))

        val verdict = when {
            wontFit -> Verdict.WontFit
            tightFit -> Verdict.TightFit
            else -> Verdict.RunsWell
        }

        // Locale.US, not the device locale: this is a fixed "1.2" not "1,2" regardless of where
        // the app runs — matches how ModelFormatting's formatFileSize/formatParamCount read too.
        val explanation = String.format(
            Locale.US,
            "needs ~%.1f GB, you have %.1f GB",
            estimatedBytes / BYTES_PER_GB,
            profile.totalRamBytes / BYTES_PER_GB,
        )

        return Compatibility(
            verdict = verdict,
            label = labelFor(verdict),
            explanation = explanation,
            recommendedBackend = recommendedBackend(model.architecture, profile.hasVulkan),
        )
    }

    /**
     * `KV bytes ~= 2 (K+V) * min(contextLength, plannedCtx) * embeddingLength * blockCount * 2
     * (f16 bytes)`. This assumes `n_head_kv == n_head` (no grouped-query attention) since
     * [ModelEntity] doesn't carry `n_head`/`n_head_kv` yet (blocked on TEN-18 exposing them from
     * llama.kt) — GQA models (most current ones) will have a real KV cache smaller than this
     * estimate, so this formula is a documented over-estimate, not exact. Safer to overestimate
     * for a memory-pressure check than to underestimate into a device that gets OOM-killed.
     */
    private fun kvCacheBytes(model: ModelEntity): Long {
        val plannedCtx = minOf(model.contextLength, PLANNED_CTX_TOKENS)
        return KV_CACHE_TENSORS * plannedCtx * model.embeddingLength * model.blockCount * KV_BYTES_PER_ELEMENT_F16
    }

    private fun isLikelySlow(model: ModelEntity): Boolean {
        val arch = model.architecture.lowercase()
        return model.paramCount >= TIGHT_PARAM_THRESHOLD || TIGHT_ARCHITECTURE_MARKERS.any { arch.contains(it) }
    }

    private fun recommendedBackend(architecture: String, hasVulkan: Boolean): BackendHint {
        val arch = architecture.lowercase()
        val prefersGpu = GPU_PREFERRED_ARCH_MARKERS.any { arch.contains(it) }
        return when {
            prefersGpu && hasVulkan -> BackendHint.Gpu
            // No Vulkan device to offload to — CPU is still the only option, even though this
            // architecture's CPU path is the slow one (see Gemma 4 E2B in the measured table).
            prefersGpu -> BackendHint.Cpu
            DENSE_ARCH_MARKERS.any { arch.contains(it) } -> BackendHint.Cpu
            else -> BackendHint.Auto
        }
    }

    private fun labelFor(verdict: Verdict): String = when (verdict) {
        Verdict.RunsWell -> "Runs well"
        Verdict.TightFit -> "Tight fit"
        Verdict.WontFit -> "Won't fit"
    }
}
