package io.github.tensaiapp.data.models

import io.github.tensaiapp.engine.KvCacheType

/**
 * How many bytes of KV cache a [ModelEntity] costs at a given context length — the one place that
 * answers it, shared by [io.github.tensaiapp.compat.CompatibilityChecker] (does this model
 * fit at all?) and [io.github.tensaiapp.runtime.LoadPolicy] (how much context can we
 * afford?), which used to carry two copies of the same formula.
 *
 * TEN-18 replaced that formula. It assumed `n_head_kv == n_head` and a per-layer footprint of
 * `embeddingLength`, which is only true for plain multi-head attention — measured on device
 * (OPPO CPH2251, 2026-09-04) it over-estimated Qwen3-1.7B by exactly 2x (229 376 vs 114 704
 * bytes/token) and Gemma-4-E2B by more than an order of magnitude, which is what pinned Gemma-4 to
 * a context it never needed. Both measurements, and how far each can honestly be pushed, are
 * spelled out in `KvCacheSizeTest`. The real shape now comes out of the GGUF header — see
 * [io.github.tensaiapp.engine.GgufInfo.kvFullElementsPerToken] and the "KV-cache shape"
 * block in `tensai_jni.cpp` for how it's derived and where it deliberately rounds up.
 *
 * **The estimate must never come in under what llama.cpp will actually allocate.** Over-estimating
 * costs context the device could have afforded; under-estimating gets the load OOM-killed on a
 * device that is already tight. Every fallback below therefore lands on the *larger* number, and
 * every rounding goes up: the 2x slack the old formula carried is gone, so the arithmetic it used
 * to hide (cell padding, quantized block overhead) now has to be exact.
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

    /**
     * Every cache is sized in whole blocks of this many cells, so a requested `n_ctx` costs what
     * the next multiple of 256 costs, not what it asks for. Two independent roundings, both
     * `LM_GGML_PAD(x, 256)` in llama.cpp:
     * - `llama_context` pads `cparams.n_ctx` itself (`llama-context.cpp`, "ref: PR 17046"), and
     *   the caches are then built at that padded size — asking for 1250 allocates 1280 cells.
     * - `llama_kv_cache_iswa` pads the windowed cache on top of that (`llama-kv-cache-iswa.cpp`),
     *   *after* taking `min(n_ctx, n_swa + n_ubatch)` and with no clamp back down afterwards.
     */
    private const val CELL_PADDING = 256L

    /**
     * Bytes a quantized KV cache spends per [CELL_PADDING]-aligned row of `elements`, as
     * `elements / blockElements * blockBytes` — ggml stores quantized tensors in fixed-size blocks
     * carrying a scale alongside the quants, so a "1 byte per element" reading of Q8_0 is 6.25%
     * short of what it allocates. Sizes from the `block_*` structs in `ggml-common.h`:
     * `block_q8_0` is 34 bytes per 32 elements (one f16 scale + 32 int8), `block_q4_0` is 18
     * (one f16 scale + 32 nibbles). F16 is unquantized: one element, two bytes.
     */
    private data class ElementCost(val blockElements: Long, val blockBytes: Long)

    // `when` rather than a map, so a new KvCacheType can't reach this silently.
    private fun elementCost(kvCacheType: KvCacheType): ElementCost = when (kvCacheType) {
        KvCacheType.F16 -> ElementCost(blockElements = 1, blockBytes = 2)
        KvCacheType.Q8_0 -> ElementCost(blockElements = 32, blockBytes = 34)
        KvCacheType.Q4_0 -> ElementCost(blockElements = 32, blockBytes = 18)
    }

    /**
     * KV-cache bytes [model] needs when asked for [nCtx] tokens at [kvCacheType] precision.
     *
     * Two terms, because llama.cpp allocates two caches for a sliding-window model: the layers
     * sized by the padded `n_ctx`, plus the windowed layers capped at
     * `pad256(min(paddedNCtx, slidingWindow + n_ubatch))`. A model without windowed layers has the
     * second term at 0 and the whole thing is a step function of [nCtx] with [CELL_PADDING]-wide
     * steps.
     *
     * Falls back to the pre-TEN-18 `2 * embeddingLength * blockCount` per token when
     * [ModelEntity.kvFullElementsPerToken] is 0 — a model imported before the migration, or one
     * whose header didn't yield an attention shape. That formula is exact for multi-head attention
     * whose head dimension is `n_embd / n_head`, and an over-estimate for everything else — which
     * covers every model this app targets, but is not unconditional: a header that hides its
     * `head_count_kv` while declaring a `key_length` + `value_length` above `2 * n_embd / n_head`
     * would come out under. No shipped GGUF does that, and llama.cpp would read such a model as
     * `n_head_kv == n_head` anyway; stated here so the claim isn't mistaken for a proof.
     */
    fun bytesAt(model: ModelEntity, nCtx: Long, kvCacheType: KvCacheType): Long = bytesAt(
        embeddingLength = model.embeddingLength,
        blockCount = model.blockCount,
        kvFullElementsPerToken = model.kvFullElementsPerToken,
        kvSwaElementsPerToken = model.kvSwaElementsPerToken,
        slidingWindow = model.slidingWindow,
        nCtx = nCtx,
        kvCacheType = kvCacheType,
    )

    /** [bytesAt] from the raw attention shape, for a model that is not a [ModelEntity] (a catalog entry with no file yet). */
    fun bytesAt(
        embeddingLength: Long,
        blockCount: Long,
        kvFullElementsPerToken: Long,
        kvSwaElementsPerToken: Long,
        slidingWindow: Long,
        nCtx: Long,
        kvCacheType: KvCacheType,
    ): Long {
        val cells = padToCells(nCtx)

        if (kvFullElementsPerToken <= 0) {
            val elements = KV_CACHE_TENSORS * cells * embeddingLength * blockCount
            return bytesForElements(elements, kvCacheType)
        }

        val swaCells = when {
            kvSwaElementsPerToken <= 0 -> 0L

            // Padded after the min and not clamped back to `cells`, exactly as
            // llama_kv_cache_iswa does it. `cells` is already a whole number of blocks, so the
            // windowed cache can never come out larger than the full one anyway.
            slidingWindow > 0 -> padToCells(minOf(cells, slidingWindow + UBATCH_TOKENS))

            // Windowed layers with no window to cap them: the row says the model has a windowed
            // cache but not how small it gets. Charging 0 would silently drop that whole cache
            // from the estimate, which is the one direction this class must never go, so charge
            // the uncapped size instead. The JNI only ever writes these two columns together
            // (see `tensai_jni.cpp`), but they are two independent columns and nothing at the
            // database level enforces the pairing — safe by construction beats safe by
            // coincidence.
            else -> cells
        }

        val elements = kvFullElementsPerToken * cells + kvSwaElementsPerToken * swaCells
        return bytesForElements(elements, kvCacheType)
    }

    /**
     * Largest `n_ctx` in `0..`[maxCtx] whose [bytesAt] fits in [availableBytes], searched over
     * multiples of [CELL_PADDING] (plus [maxCtx] itself).
     *
     * Only multiples of 256, because any other answer is a lie: a returned 1250 is loaded as 1280
     * cells, so the caller would spend more than the budget it just checked. [maxCtx] is exempt
     * because a caller asking for it has already had its padded cost measured by the first
     * [bytesAt] below.
     *
     * Binary search rather than `availableBytes / bytesPerToken`: with sliding-window layers
     * [bytesAt] is piecewise linear (the windowed term stops growing once `n_ctx` passes the
     * window), so there is no single bytes-per-token to divide by. It stays monotonic, which is
     * all the search needs.
     */
    fun maxCtxFitting(model: ModelEntity, availableBytes: Long, kvCacheType: KvCacheType, maxCtx: Long): Long {
        if (maxCtx <= 0) return 0
        if (bytesAt(model, maxCtx, kvCacheType) <= availableBytes) return maxCtx

        // Search in whole blocks of CELL_PADDING cells, so `low * CELL_PADDING` pads to itself.
        var low = 0L
        var high = maxCtx / CELL_PADDING
        while (low < high) {
            val mid = low + (high - low + 1) / 2
            if (bytesAt(model, mid * CELL_PADDING, kvCacheType) <= availableBytes) low = mid else high = mid - 1
        }
        return low * CELL_PADDING
    }

    private fun padToCells(nCtx: Long): Long =
        if (nCtx <= 0) 0 else (nCtx + CELL_PADDING - 1) / CELL_PADDING * CELL_PADDING

    /**
     * Rounds up: a partial block still costs a whole one. In practice every per-layer row is a
     * multiple of 32 elements (ggml refuses to quantize a row that isn't), so the ceiling is a
     * guard against a shape that couldn't be loaded anyway rather than a routine correction.
     */
    private fun bytesForElements(elements: Long, kvCacheType: KvCacheType): Long {
        val cost = elementCost(kvCacheType)
        val blocks = (elements + cost.blockElements - 1) / cost.blockElements
        return blocks * cost.blockBytes
    }
}
