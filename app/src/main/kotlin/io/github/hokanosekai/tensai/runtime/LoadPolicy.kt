package io.github.hokanosekai.tensai.runtime

import io.github.hokanosekai.tensai.compat.DeviceProfile
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.engine.KvCacheType

/**
 * Adaptive `n_ctx` + KV-cache-precision plan for a single [InferenceQueue.loadModel] call — see
 * [LoadPolicy.compute].
 *
 * @param nCtx effective context window to request.
 * @param kvCacheType KV-cache precision to request.
 */
data class LoadPlan(val nCtx: Int, val kvCacheType: KvCacheType)

/**
 * Derives the [LoadPlan] the TEN-30 loading flow requests for a given [ModelEntity] on a given
 * [DeviceProfile] — see [compute]'s KDoc for the full policy.
 */
object LoadPolicy {

    /**
     * Ceiling on the context window we'll ever request, independent of how much RAM is available.
     * Past this point, prefill latency — not memory — is the binding constraint for on-device
     * chat: a bigger context just means a longer wait before the first token, regardless of
     * whether it fits in RAM. Coincidentally the same value as
     * [io.github.hokanosekai.tensai.compat.CompatibilityChecker]'s own 4096 constant, but that one
     * exists for an unrelated reason (a fixed assumption for its KV-cache *estimate*) — the two
     * are not the same knob and are kept as separate constants deliberately.
     */
    const val CTX_LATENCY_CAP = 4096

    /** Never request less than this — a degenerate context isn't useful even on the tightest device. */
    private const val MIN_CTX_TOKENS = 256

    /** K and V caches are each sized the same. */
    private const val KV_CACHE_TENSORS = 2L
    private const val KV_BYTES_PER_ELEMENT_F16 = 2L
    private const val KV_BYTES_PER_ELEMENT_Q8_0 = 1L

    /**
     * Same compute-buffer/graph-overhead allowance
     * [io.github.hokanosekai.tensai.compat.CompatibilityChecker] reserves.
     */
    private const val OVERHEAD_BYTES = 300L * 1024 * 1024

    /**
     * Computes the [LoadPlan] to load [model] on [profile].
     *
     * 1. `wanted` is the context we'd like, independent of RAM: the model's trained max
     *    ([ModelEntity.contextLength]), capped at [CTX_LATENCY_CAP].
     * 2. If `wanted` fits in the RAM left after weights + [OVERHEAD_BYTES] at F16 precision, use
     *    it as-is with [KvCacheType.F16].
     * 3. Otherwise, try [KvCacheType.Q8_0] (half the bytes/token of F16) at the same `wanted` — if
     *    that fits, use it: trading KV precision at the context the user could plausibly want
     *    beats silently shrinking the context instead.
     * 4. If even Q8_0 can't fit `wanted`, fall back to whatever context Q8_0 *does* fit (never
     *    below [MIN_CTX_TOKENS]) — Q8_0 over F16 here because it buys more headroom per byte in an
     *    already RAM-constrained load.
     *
     * This assumes `n_head_kv == n_head` (no grouped-query attention), the same documented
     * over-estimate as
     * [io.github.hokanosekai.tensai.compat.CompatibilityChecker.check]'s own KV-cache math —
     * safer to over-estimate KV size (and so under-estimate the context we can afford) than the
     * reverse.
     */
    fun compute(model: ModelEntity, profile: DeviceProfile): LoadPlan {
        val wanted = minOf(model.contextLength, CTX_LATENCY_CAP.toLong())
        val availableForKv = (profile.availableRamBytes - model.sizeBytes - OVERHEAD_BYTES).coerceAtLeast(0)

        val bytesPerTokenF16 = KV_CACHE_TENSORS * model.embeddingLength * model.blockCount * KV_BYTES_PER_ELEMENT_F16
        val bytesPerTokenQ8 = KV_CACHE_TENSORS * model.embeddingLength * model.blockCount * KV_BYTES_PER_ELEMENT_Q8_0

        val ctxFaisableF16 = ctxFor(availableForKv, bytesPerTokenF16)
        if (wanted <= ctxFaisableF16) {
            return LoadPlan(wanted.toInt(), KvCacheType.F16)
        }

        val ctxFaisableQ8 = ctxFor(availableForKv, bytesPerTokenQ8)
        if (wanted <= ctxFaisableQ8) {
            return LoadPlan(wanted.toInt(), KvCacheType.Q8_0)
        }

        val reduced = ctxFaisableQ8.coerceAtLeast(MIN_CTX_TOKENS.toLong())
        return LoadPlan(reduced.toInt(), KvCacheType.Q8_0)
    }

    private fun ctxFor(availableBytes: Long, bytesPerToken: Long): Long =
        if (bytesPerToken <= 0) Long.MAX_VALUE else availableBytes / bytesPerToken
}
