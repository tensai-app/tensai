package io.github.tensaiapp.compat

import io.github.tensaiapp.data.AutoMemoryShare
import io.github.tensaiapp.data.ContextSetting
import io.github.tensaiapp.data.models.ModelEntity
import io.github.tensaiapp.engine.BackendHint
import io.github.tensaiapp.engine.KvCacheType
import io.github.tensaiapp.runtime.LoadPlan
import io.github.tensaiapp.runtime.LoadPolicy

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

    /** This build's llama.cpp does not know the model's architecture (TEN-167): not a memory question, it cannot load at all. */
    Unsupported,

    /** A speculative-decoding draft head (DFlash, EAGLE, MTP): not loadable on its own (TEN-167). */
    DraftModel,
    ;

    /** The model cannot be loaded whatever the memory: [Unsupported] and [DraftModel], which have no "Load anyway". */
    val isBlocked: Boolean get() = this == Unsupported || this == DraftModel
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
 * @param estimatedBytes what this model is expected to cost to load: weights + KV cache + compute
 *   buffers at the context [io.github.tensaiapp.runtime.LoadPolicy] plans for it (TEN-150),
 *   [io.github.tensaiapp.runtime.LoadPolicy.estimate]. This is the number the UI prints; the verdict's
 *   RunsWell/TightFit split uses [verdictBytes].
 * @param deviceRamBytes [DeviceProfile.totalRamBytes] — total physical RAM, not what happens to be
 *   free right now. The comparison the user is shown is against the phone they own, which does not
 *   change while they read it; [DeviceProfile.availableRamBytes] only feeds the
 *   [Verdict.TightFit] judgement.
 * @param recommendedBackend backend this model's architecture is expected to run best on for
 *   *this* device (see [CompatibilityChecker]'s per-architecture table) — a hint for the loader's
 *   default, not a guarantee; the user can still override it ("load anyway" is a UI decision, not
 *   this checker's).
 * @param verdictBytes what [verdict]'s RunsWell/TightFit split was decided from, against free RAM. Not
 *   always [estimatedBytes]: under [ContextSetting.Auto] the context is as large as the memory share
 *   allows, which is always more than free RAM and says nothing about whether the model runs well, so
 *   the split is priced at `min(trained, 4096)` at F16 instead. A manual size or Max is judged at the
 *   real plan, and can legitimately be tight.
 * @param plannedCtx the context of the plan [estimatedBytes] was priced at (the floor when nothing
 *   fits), so a screen needs no second [LoadPolicy.compute] for it.
 */
data class Compatibility(
    val verdict: Verdict,
    val estimatedBytes: Long,
    val deviceRamBytes: Long,
    val recommendedBackend: BackendHint,
    val verdictBytes: Long = estimatedBytes,
    val plannedCtx: Int = 0,
    /** The model's architecture, for the "can't run the <arch> architecture" line of [Verdict.Unsupported]. */
    val architecture: String = "",
)

/**
 * What [CompatibilityChecker] needs to know about a model, and nothing about its file: the figures
 * [ModelEntity] carries, minus the bookkeeping. Lets a model that is not on the device yet (the
 * onboarding catalog) be judged by the same rules as an imported one. Field meanings are
 * [ModelEntity]'s.
 */
data class ModelFacts(
    val sizeBytes: Long,
    val paramCount: Long,
    val architecture: String,
    val contextLength: Long,
    val embeddingLength: Long,
    val blockCount: Long,
    val kvFullElementsPerToken: Long = 0,
    val kvSwaElementsPerToken: Long = 0,
    val slidingWindow: Long = 0,
    /** Display name, file name and repo, for [ModelSupport.isDraftOnly]; empty when unknown. */
    val names: List<String> = emptyList(),
)

/**
 * A [ModelEntity] stand-in for [LoadPolicy], which plans on one: a catalog entry has no file, no id and
 * no override, only the figures.
 */
private fun ModelFacts.toEntity() = ModelEntity(
    id = "",
    displayName = "",
    fileName = "",
    filePath = "",
    sizeBytes = sizeBytes,
    architecture = architecture,
    quantLabel = "",
    paramCount = paramCount,
    contextLength = contextLength,
    embeddingLength = embeddingLength,
    blockCount = blockCount,
    supportsThinking = false,
    importedAt = 0L,
    kvFullElementsPerToken = kvFullElementsPerToken,
    kvSwaElementsPerToken = kvSwaElementsPerToken,
    slidingWindow = slidingWindow,
)

private fun ModelEntity.facts() = ModelFacts(
    sizeBytes = sizeBytes,
    paramCount = paramCount,
    architecture = architecture,
    contextLength = contextLength,
    embeddingLength = embeddingLength,
    blockCount = blockCount,
    kvFullElementsPerToken = kvFullElementsPerToken,
    kvSwaElementsPerToken = kvSwaElementsPerToken,
    slidingWindow = slidingWindow,
    names = listOf(displayName, fileName, sourceRepo.orEmpty()),
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
 *   [io.github.tensaiapp.runtime.LoadPolicy] since TEN-77. **This paragraph used to
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

    /** The context the verdict split is priced at under Auto: the 4096 every verdict was priced at before TEN-150. */
    private const val TYPICAL_CTX = 4_096L

    /**
     * Architectures whose llama.cpp CPU path is disproportionately slow, so GPU offload is preferred.
     * nemotron_h (TEN-151, Nemotron-3-Nano-4B, hybrid Mamba-2): no first token after 3 min on CPU
     * for a 170-token prompt, 1.7 tok/s on Vulkan. "nemotron_h", not "nemotron": the plain
     * architecture is a dense transformer.
     */
    private val GPU_PREFERRED_ARCH_MARKERS = listOf("gemma3n", "gemma4", "phi", "nemotron_h")

    /**
     * Architectures fastest on the big CPU cores: the dense transformers (see this object's KDoc),
     * and lfm2 (TEN-151, LFM2.5-2.6B: 3.6 tok/s on CPU against 3.1 on Vulkan, same time to first
     * token).
     */
    private val DENSE_ARCH_MARKERS = listOf("qwen", "llama", "gemma", "lfm2")

    /**
     * The verdict for [model], from the very plan [LoadPolicy.compute] would load it with (TEN-150):
     * [global] and [autoShare] are the user's context preferences, a model's own override wins as it
     * does at load. One source of truth, so the status, the "Estimated memory" row and the picker
     * never show two totals for one model.
     */
    fun check(
        model: ModelEntity,
        profile: DeviceProfile,
        global: ContextSetting = ContextSetting.Auto,
        autoShare: AutoMemoryShare = AutoMemoryShare.P50,
    ): Compatibility = check(model, model.facts(), profile, global, autoShare, catalog = false)

    /**
     * Same verdict as [check] for a model known only by its facts: a catalog entry with no file on
     * the device yet (the onboarding recommendation). One implementation behind both.
     *
     * Callers use the default [global] and [autoShare] on purpose: a catalog entry has no model of its own
     * to carry an override and the discovery screens are not about the user's context choice. One
     * difference from the entity overload, to keep what is *recommended* as before TEN-150: an entry is
     * also [Verdict.WontFit] when it does not fit at the typical 4096 context, not only at the 1024 floor
     * the load would shrink to.
     */
    fun check(
        facts: ModelFacts,
        profile: DeviceProfile,
        global: ContextSetting = ContextSetting.Auto,
        autoShare: AutoMemoryShare = AutoMemoryShare.P50,
    ): Compatibility = check(facts.toEntity(), facts, profile, global, autoShare, catalog = true)

    private fun check(
        model: ModelEntity,
        facts: ModelFacts,
        profile: DeviceProfile,
        global: ContextSetting,
        autoShare: AutoMemoryShare,
        catalog: Boolean,
    ): Compatibility {
        // The plan the load runs: what it would cost, or what its floor costs when nothing fits.
        val plan = LoadPolicy.compute(model, profile, global, autoShare)
        val estimatedBytes = when (plan) {
            is LoadPlan.Feasible -> LoadPolicy.estimate(model, plan.nCtx.toLong(), plan.kvCacheType)
            is LoadPlan.Infeasible -> plan.requiredBytes
        }

        // Not loadable at any context worth having, on the same [DeviceProfile.usableRamBytes] the
        // plan budgets against (TEN-77). A verdict decided from one reading and a context planned
        // from another is how a model passed this check and was then planned at 256 tokens.
        // A catalog entry is a recommendation, not a file the user already holds: it also has to fit at the
        // typical context, as before TEN-150, so nothing is offered that only loads at the floor.
        val typical = LoadPolicy.estimate(model, minOf(LoadPolicy.trainedContext(model), TYPICAL_CTX), KvCacheType.F16)
        val wontFit = plan is LoadPlan.Infeasible || (catalog && typical > profile.usableRamBytes)

        // Auto asks for as much context as its share allows, so pricing the split at it would flag every
        // model tight; it is priced at the typical context instead. A manual size or Max is the real plan.
        val verdictBytes = if (LoadPolicy.effectiveSetting(model, global) == ContextSetting.Auto) typical else estimatedBytes
        // availableRamBytes only ever softens a verdict here, never decides fit: "the system is
        // busy right now" is the one question a free-memory reading answers honestly.
        val blocking = ModelSupport.blockingVerdict(facts.architecture, facts.names)
        val tightFit = !wontFit && (verdictBytes > profile.availableRamBytes || isLikelySlow(facts))

        val verdict = when {
            blocking != null -> blocking
            wontFit -> Verdict.WontFit
            tightFit -> Verdict.TightFit
            else -> Verdict.RunsWell
        }

        return Compatibility(
            verdict = verdict,
            estimatedBytes = estimatedBytes,
            deviceRamBytes = profile.totalRamBytes,
            recommendedBackend = recommendedBackend(facts.architecture, profile.hasVulkan),
            verdictBytes = verdictBytes,
            plannedCtx = when (plan) {
                is LoadPlan.Feasible -> plan.nCtx
                is LoadPlan.Infeasible -> plan.floor.nCtx
            },
            architecture = facts.architecture,
        )
    }

    private fun isLikelySlow(facts: ModelFacts): Boolean {
        val arch = facts.architecture.lowercase()
        return facts.paramCount >= TIGHT_PARAM_THRESHOLD || TIGHT_ARCHITECTURE_MARKERS.any { arch.contains(it) }
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
            // An architecture measured nowhere: CPU, not Auto. Auto offloads to any Vulkan device,
            // and on Mali that lost everywhere it was measured (TEN-197, TwIL-LM3 Q4_K_M: prefill
            // 3.9 tok/s on Vulkan against 14 on CPU, decode 2.7 against 2.9, and the UI dropping
            // frames while the GPU computes). Only the architectures above earn the GPU.
            else -> BackendHint.Cpu
        }
    }
}
