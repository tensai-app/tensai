package io.github.hokanosekai.tensai.data.models

/**
 * How many bytes of KV cache a [ModelEntity] costs at a given context length — the one place that
 * answers it, shared by [io.github.hokanosekai.tensai.compat.CompatibilityChecker] (does this model
 * fit at all?) and [io.github.hokanosekai.tensai.runtime.LoadPolicy] (how much context can we
 * afford?), which used to carry two copies of the same formula.
 *
 * TEN-18 replaced that formula. It assumed `n_head_kv == n_head` and a per-layer footprint of
 * `embeddingLength`, which is only true for plain multi-head attention — measured on device
 * (OPPO CPH2251, 2026-09-04) it over-estimated Qwen3-1.7B by exactly 2x (229 376 vs 114 704
 * bytes/token) and Gemma-4-E2B by roughly 10x, which pinned Gemma-4 at `n_ctx = 256`. The real
 * shape now comes out of the GGUF header — see
 * [io.github.hokanosekai.tensai.engine.GgufInfo.kvFullElementsPerToken] and the "KV-cache shape"
 * block in `tensai_jni.cpp` for how it's derived and where it deliberately rounds up.
 *
 * **The estimate must never come in under what llama.cpp will actually allocate.** Over-estimating
 * costs context the device could have afforded; under-estimating gets the load OOM-killed on a
 * device that is already tight. Every fallback below therefore lands on the *larger* number.
 */
object KvCacheSize {

    /** K and V caches are each sized the same — used by the [ModelEntity.embeddingLength] fallback. */
    private const val KV_CACHE_TENSORS = 2L

    /**
     * `n_ubatch`, added to the sliding window when sizing the windowed cache. `llama_kv_cache_iswa`
     * uses `min(n_ctx, n_swa + n_ubatch)` for it; nothing in the load path overrides `n_ubatch`, so
     * it's llama.cpp's own 512 default (`common/common.h`). A load that did raise it would make
     * this an under-estimate, which is why it's pinned to a named constant rather than inlined.
     */
    private const val UBATCH_TOKENS = 512L

    /** `llama_kv_cache_iswa` pads the windowed cache to 256 cells for performance. */
    private const val SWA_CELL_PADDING = 256L

    /**
     * KV-cache bytes [model] needs at [nCtx] tokens, at [bytesPerElement] per cache element (see
     * [io.github.hokanosekai.tensai.engine.KvCacheType] — 2 for F16, 1 for Q8_0).
     *
     * Two terms, because llama.cpp allocates two caches for a sliding-window model: the layers
     * sized by `n_ctx`, plus the windowed layers capped at `min(nCtx, slidingWindow + n_ubatch)`
     * rounded up to [SWA_CELL_PADDING]. A model without windowed layers has the second term at 0
     * and the whole thing is linear in [nCtx].
     *
     * Falls back to the pre-TEN-18 `2 * embeddingLength * blockCount` per token when
     * [ModelEntity.kvFullElementsPerToken] is 0 — a model imported before the migration, or one
     * whose header didn't yield an attention shape. That formula is exact for multi-head attention
     * and an over-estimate for everything else, so the fallback is safe by construction.
     */
    fun bytesAt(model: ModelEntity, nCtx: Long, bytesPerElement: Long): Long {
        if (model.kvFullElementsPerToken <= 0) {
            return KV_CACHE_TENSORS * nCtx * model.embeddingLength * model.blockCount * bytesPerElement
        }

        val swaCells = if (model.kvSwaElementsPerToken > 0 && model.slidingWindow > 0) {
            val window = minOf(nCtx, model.slidingWindow + UBATCH_TOKENS)
            val padded = (window + SWA_CELL_PADDING - 1) / SWA_CELL_PADDING * SWA_CELL_PADDING
            minOf(padded, nCtx)
        } else {
            0L
        }

        val elements = model.kvFullElementsPerToken * nCtx + model.kvSwaElementsPerToken * swaCells
        return elements * bytesPerElement
    }

    /**
     * Largest `n_ctx` in `0..`[maxCtx] whose [bytesAt] fits in [availableBytes].
     *
     * Binary search rather than `availableBytes / bytesPerToken`: with sliding-window layers
     * [bytesAt] is piecewise linear (the windowed term stops growing once `n_ctx` passes the
     * window), so there is no single bytes-per-token to divide by. It stays monotonic, which is
     * all the search needs, and for a model without windowed layers it lands on exactly the same
     * answer the division used to give.
     */
    fun maxCtxFitting(model: ModelEntity, availableBytes: Long, bytesPerElement: Long, maxCtx: Long): Long {
        if (maxCtx <= 0) return 0
        if (bytesAt(model, maxCtx, bytesPerElement) <= availableBytes) return maxCtx

        var low = 0L
        var high = maxCtx
        while (low < high) {
            val mid = low + (high - low + 1) / 2
            if (bytesAt(model, mid, bytesPerElement) <= availableBytes) low = mid else high = mid - 1
        }
        return low
    }
}
