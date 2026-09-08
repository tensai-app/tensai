package io.github.hokanosekai.tensai.compat

import io.github.hokanosekai.tensai.data.models.KvCacheSize
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.engine.BackendHint
import io.github.hokanosekai.tensai.engine.KvCacheType

/** Traffic-light verdict for whether a [ModelEntity] is a good idea to load on this device. */
enum class Verdict {
    /** Comfortably fits in RAM at a usable generation speed. */
    RunsWell,

    /** Loads, but is close to the RAM budget or expected to generate slowly (~2 tok/s or below). */
    TightFit,

    /**
     * Expected not to survive a load: thrashed into uselessness on CPU, hard-killed on GPU — two
     * mechanisms, one RAM ceiling, see this file's KDoc for why the verdict doesn't split by
     * backend.
     */
    WontFit,
}

/**
 * Result of [CompatibilityChecker.check].
 *
 * Carries figures, not sentences. The label and the "needs ~X GB, your phone has Y GB" line used
 * to be formatted here; they are user-facing copy and now live in `strings.xml`, rendered by
 * `ModelFormatting`'s `compatLabel`/`compatExplanation` (TEN-39). The point is not only that they
 * are translatable: it is that there is exactly **one** place a screen can get those two numbers
 * from, so nothing can quietly grow a second estimate that disagrees with the verdict shown next
 * to it.
 *
 * @param estimatedBytes what this model is expected to cost to load: weights + KV cache at
 *   [CompatibilityChecker]'s planned context (through [KvCacheSize], the one KV formula in the
 *   app) + compute-buffer overhead. This is the number the verdict is decided from, and the same
 *   one the UI prints.
 * @param deviceRamBytes [DeviceProfile.totalRamBytes] — total physical RAM, not what happens to be
 *   free right now. The comparison the user is shown is against the phone they own, which does not
 *   change while they read it; [DeviceProfile.availableRamBytes] only feeds the
 *   [Verdict.TightFit] judgement.
 * @param recommendedBackend backend this model's architecture is expected to run best on for
 *   *this* device (see [CompatibilityChecker]'s per-architecture table) — a hint for the loader's
 *   default, not a guarantee; the user can still override it ("load anyway" is a UI decision, not
 *   this checker's).
 */
data class Compatibility(
    val verdict: Verdict,
    val estimatedBytes: Long,
    val deviceRamBytes: Long,
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
 * - **The practical size ceiling is memory pressure, not OOM.** A CPU load mmaps weights
 *   (file-backed, evictable) — it survives past its comfortable size but thrashes (e.g.
 *   Qwen2.5-7B Q4 measured at 0.2 tok/s). A GPU load copies weights into unevictable Vulkan
 *   buffers on the same physical (UMA) RAM — going over doesn't thrash, it gets hard-killed.
 *
 *   Two failure *modes*, one ceiling: [check] prices them the same, against
 *   [DeviceProfile.usableRamBytes] — the single memory basis it shares with
 *   [io.github.hokanosekai.tensai.runtime.LoadPolicy] since TEN-77. **This paragraph used to
 *   claim the GPU ceiling was the stricter of two, and no code here has ever had two** (TEN-84).
 *   That is left as it is, deliberately, and not implemented as the KDoc used to promise, for
 *   three reasons: the measured table this object calibrates against holds no GPU OOM figure to
 *   set a stricter ceiling *from*, and a margin invented here would be exactly the guess the
 *   "source of truth" sentence above forbids; [recommendedBackend] is a hint the user is free to
 *   override, so a verdict computed from it would describe a backend the model may not be loaded
 *   on; and the layer that actually sizes a load, `LoadPolicy`, is backend-blind too — splitting
 *   the ceiling in one of the two and not the other reopens TEN-77's defect, a model passing the
 *   check and then being planned against a budget the check had ruled out. Measure the GPU kill
 *   point on a device that has one, then give both layers the same two ceilings, or neither.
 * - **Backend preference is per-architecture, not a blanket rule.** Dense transformers (llama,
 *   qwen, plain gemma) run fastest on the big CPU cores — Vulkan loses on both prefill and decode
 *   because Mali has no cooperative-matrix path. Gemma 3n/4 and Phi are the exceptions: their
 *   llama.cpp CPU path is disproportionately slow (Gemma 4 E2B is outright unusable on CPU), so
 *   GPU offload wins when a Vulkan device is available.
 */
object CompatibilityChecker {

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

        // [DeviceProfile.usableRamBytes], the same basis LoadPolicy budgets its context against —
        // see that property's KDoc. A verdict decided from one memory reading and a context
        // planned from another is how a model passed this check and was then planned at 256
        // tokens (TEN-77).
        val wontFit = estimatedBytes > profile.usableRamBytes
        // availableRamBytes only ever softens a verdict here, never decides fit: "the system is
        // busy right now" is the one question a free-memory reading answers honestly.
        val tightFit = !wontFit && (estimatedBytes > profile.availableRamBytes || isLikelySlow(model))

        val verdict = when {
            wontFit -> Verdict.WontFit
            tightFit -> Verdict.TightFit
            else -> Verdict.RunsWell
        }

        return Compatibility(
            verdict = verdict,
            estimatedBytes = estimatedBytes,
            deviceRamBytes = profile.totalRamBytes,
            recommendedBackend = recommendedBackend(model.architecture, profile.hasVulkan),
        )
    }

    /**
     * KV-cache bytes at [PLANNED_CTX_TOKENS], from the model's real attention shape — see
     * [KvCacheSize], which also holds the fallback for models whose shape is unknown (imported
     * before TEN-18, or a header that didn't yield one).
     */
    private fun kvCacheBytes(model: ModelEntity): Long {
        // A header with no context_length key records 0, which isn't a context anything will be
        // loaded at — the engine turns a non-positive n_ctx into 4096 (tensai_jni.cpp), which is
        // PLANNED_CTX_TOKENS. Taking the min with 0 would report a model as needing no KV cache
        // at all. Same reasoning as LoadPolicy.trainedContextOf.
        val trainedCtx = if (model.contextLength > 0) model.contextLength else PLANNED_CTX_TOKENS
        val plannedCtx = minOf(trainedCtx, PLANNED_CTX_TOKENS)
        return KvCacheSize.bytesAt(model, plannedCtx, KvCacheType.F16)
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
}
