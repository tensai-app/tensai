package io.github.hokanosekai.tensai.hf

import io.github.hokanosekai.tensai.compat.Compatibility
import io.github.hokanosekai.tensai.compat.CompatibilityChecker
import io.github.hokanosekai.tensai.compat.DeviceProfile
import io.github.hokanosekai.tensai.compat.ModelFacts
import io.github.hokanosekai.tensai.compat.Verdict
import io.github.hokanosekai.tensai.ui.screens.onboarding.OnboardingCatalog

/** A [quant] with the verdict it gets on this device. */
data class RatedQuant(val quant: HfQuantFile, val compatibility: Compatibility)

/**
 * Judges the quants of a Hugging Face repo with the app's one [CompatibilityChecker], before any
 * file exists on the device.
 *
 * **What the API does not give, and what is assumed instead.** The Hub's `gguf` block has the
 * parameter count, architecture and trained context, but not the attention shape (embedding
 * length, layer count, KV heads). [CompatibilityChecker] prices the KV cache from that shape, and
 * [io.github.hokanosekai.tensai.data.models.KvCacheSize] falls back to
 * `2 * embeddingLength * blockCount` per token when the exact per-token element count is 0. That
 * fallback is exact for plain multi-head attention and an over-estimate for GQA models (Qwen3-1.7B
 * measures half of it), which is the safe direction. But with embedding length and block count at 0
 * it would price the KV cache at **zero**, the one direction that must never happen, so this file
 * supplies both from the parameter count ([estimateShape]) and leaves the exact KV columns at 0 to
 * take that fallback path.
 *
 * [estimateShape] is an upper envelope of real models per size class, never a typical value: for
 * a given parameter count, a deeper and narrower network has a larger `embedding * layers` product,
 * so each bucket takes the deepest published layout of its class. The result is a KV estimate that
 * is at or above the real one (ballpark +2x to +4x on GQA models, a few hundred MB at 4096
 * tokens), so a quant can be shown as "tight" when it would have run, never the reverse. When the
 * repo has no `gguf` block at all, the parameter count is derived from the file size at 4.5 bits
 * per weight of the quant's own family ([bitsPerWeight]: Q2 2.6, Q3 3.5, Q4 4.5, Q5 5.5, Q6 6.6, Q8
 * 8.5, F16 16), so a low-bit file is not read as a small model; only used for that rare case.
 */
object HfCompatibility {

    private data class Shape(val maxParams: Long, val embedding: Long, val blocks: Long)

    // Deepest known layouts per class: <=0.8B Qwen3-0.6B, <=2.5B Qwen3-1.7B, <=5B Qwen3-4B,
    // <=10B Gemma-2-9B-class depth, above that a 14B-class guess (WontFit by size on phones anyway).
    private val SHAPES = listOf(
        Shape(800_000_000, 1024, 28),
        Shape(2_500_000_000, 2048, 28),
        Shape(5_000_000_000, 3072, 36),
        Shape(10_000_000_000, 4096, 42),
        Shape(Long.MAX_VALUE, 5120, 64),
    )

    /** Approximate bits per weight of a quant family, from its label; Q4 class for unknown labels. */
    internal fun bitsPerWeight(label: String): Double = when {
        label.startsWith("BF16") || label.startsWith("F16") -> 16.0
        label.startsWith("F32") -> 32.0
        label.startsWith("Q8") -> 8.5
        label.startsWith("Q6") -> 6.6
        label.startsWith("Q5") -> 5.5
        label.startsWith("Q4") || label.startsWith("IQ4") || label.startsWith("MXFP4") -> 4.5
        label.startsWith("Q3") || label.startsWith("IQ3") -> 3.5
        label.startsWith("Q2") || label.startsWith("IQ2") || label.startsWith("IQ1") || label.startsWith("TQ") -> 2.6
        else -> 4.5
    }

    /** Stand-in `(embeddingLength, blockCount)` for [paramCount], see the class KDoc. */
    fun estimateShape(paramCount: Long): Pair<Long, Long> {
        val s = SHAPES.first { paramCount <= it.maxParams }
        return s.embedding to s.blocks
    }

    /** [ModelFacts] of [quant] in [detail], for [CompatibilityChecker.check]. */
    fun factsFor(detail: HfRepoDetail, quant: HfQuantFile): ModelFacts {
        val params = if (detail.paramCount > 0) detail.paramCount
        else (quant.sizeBytes * 8 / bitsPerWeight(quant.quant)).toLong()
        val (embedding, blocks) = estimateShape(params)
        return ModelFacts(
            sizeBytes = quant.sizeBytes,
            paramCount = params,
            architecture = detail.architecture,
            contextLength = detail.contextLength,
            embeddingLength = embedding,
            blockCount = blocks,
            names = listOf(detail.summary.id, quant.fileName),
        )
    }

    /**
     * Every quant of [detail] with its verdict, in [HfRepoDetail.quants] order (smallest first).
     * Judged on the device class, not the moment: [OnboardingCatalog.steadyProfile], the same basis
     * as the onboarding recommendation, so a repo does not flip verdict with the apps open.
     */
    fun rate(detail: HfRepoDetail, profile: DeviceProfile): List<RatedQuant> {
        val steady = OnboardingCatalog.steadyProfile(profile)
        return detail.quants.map { RatedQuant(it, CompatibilityChecker.check(factsFor(detail, it), steady)) }
    }

    /**
     * The quant to preselect, with its verdict: see [pick]. Null when the repo has no usable quant.
     * [HfRecommendation.isRecommended] is false when nothing runs well and the quant is only the
     * nearest to fitting: show it, but not with a "Recommended" tag.
     */
    fun recommendation(detail: HfRepoDetail, profile: DeviceProfile): HfRecommendation? {
        val rated = rate(detail, profile)
        val quant = pick(rated) ?: return null
        val verdict = rated.first { it.quant === quant }.compatibility
        return HfRecommendation(
            quant,
            verdict,
            isRecommended = verdict.verdict == Verdict.RunsWell && quant.quant !in NON_PRESELECTABLE && !QuantLabel.isVeryLowQuality(quant.quant),
        )
    }

    /** [pick] without the verdict. Prefer [recommendation] in UI code, this one cannot tell "fits" from "nearest". */
    fun recommendedQuant(detail: HfRepoDetail, profile: DeviceProfile): HfQuantFile? =
        pick(rate(detail, profile))

    private val NON_PRESELECTABLE = setOf("F16", "BF16", "F32", QuantLabel.UNKNOWN)
    private val PREFERRED_FAMILY = Regex("^(Q[4-6]_K(_[A-Z]+)?|Q[4-6]_[01]|IQ4_[A-Z]+)$")

    /**
     * Q4_K_M if it runs well; else, among the quants that run well, the biggest of the mainstream
     * family (K-quants and Q4 to Q6), else the biggest of any labelled quantized one (Q8_0 is the
     * ceiling: F16, BF16, F32 and unlabelled files are never preselected, however much RAM there is);
     * else, when nothing runs well, the smallest overall (the nearest to fitting, which the caller
     * must not call "recommended", see [recommendation]). The very low quality quants ([QuantLabel.isVeryLowQuality])
     * are skipped by every step, unless a repo has nothing else (TEN-159).
     */
    internal fun pick(rated: List<RatedQuant>): HfQuantFile? {
        val ok = rated.filter { it.compatibility.verdict == Verdict.RunsWell }
        val candidates = ok.filter { it.quant.quant !in NON_PRESELECTABLE && !QuantLabel.isVeryLowQuality(it.quant.quant) }
        return candidates.firstOrNull { it.quant.quant == "Q4_K_M" }?.quant
            ?: candidates.filter { PREFERRED_FAMILY.matches(it.quant.quant) }.maxByOrNull { it.quant.sizeBytes }?.quant
            ?: candidates.maxByOrNull { it.quant.sizeBytes }?.quant
            // Nearest to fitting: a very low quality quant is the smallest of any repo, so it only gets here as the last resort.
            ?: rated.filter { !QuantLabel.isVeryLowQuality(it.quant.quant) }.minByOrNull { it.quant.sizeBytes }?.quant
            ?: rated.minByOrNull { it.quant.sizeBytes }?.quant
    }
}

/** A preselected quant: [isRecommended] is true only when it [Verdict.RunsWell]. */
data class HfRecommendation(val quant: HfQuantFile, val compatibility: Compatibility, val isRecommended: Boolean)
