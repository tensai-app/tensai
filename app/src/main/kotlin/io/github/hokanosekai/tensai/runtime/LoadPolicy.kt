package io.github.hokanosekai.tensai.runtime

import io.github.hokanosekai.tensai.compat.DeviceProfile
import io.github.hokanosekai.tensai.data.AutoMemoryShare
import io.github.hokanosekai.tensai.data.ContextSetting
import io.github.hokanosekai.tensai.data.models.KvCacheSize
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.engine.KvCacheType

/**
 * Adaptive `n_ctx` + KV-cache-precision plan for a single [InferenceQueue.loadModel] call — see
 * [LoadPolicy.compute].
 *
 * A plan is allowed to *fail*. Before TEN-77 it wasn't: when nothing fit, [compute] returned the
 * smallest context llama.cpp will allocate and said nothing, and the user got a chat that accepted
 * a prompt and never answered — at `n_ctx` 256 a 31-token prompt leaves 225 tokens for a reply,
 * which is not a reply. [Infeasible] is that outcome made speakable.
 */
sealed interface LoadPlan {

    /**
     * A context and precision that fit the device's budget.
     *
     * @param nCtx effective context window to request.
     * @param kvCacheType KV-cache precision to request.
     */
    data class Feasible(val nCtx: Int, val kvCacheType: KvCacheType) : LoadPlan

    /**
     * Not even [floor] fits — there is no context worth loading this model at on this device.
     *
     * Carries the figures rather than a sentence, like
     * [io.github.hokanosekai.tensai.compat.Compatibility] does, so the caller decides what to say.
     *
     * @param requiredBytes what [floor] would cost: weights + its KV cache + compute overhead.
     * @param budgetBytes [DeviceProfile.usableRamBytes] this was measured against.
     * @param floor the smallest plan still worth loading — what an explicit "load anyway" gets
     *   tried with, so an override that the device really can't honour fails loudly at the engine
     *   ("Not enough memory") instead of quietly succeeding into a mute chat.
     */
    data class Infeasible(
        val requiredBytes: Long,
        val budgetBytes: Long,
        val floor: Feasible,
    ) : LoadPlan
}


/**
 * What a [ContextSetting] would cost for one model on one device, the figures the context pickers
 * print (TEN-150). Built by [LoadPolicy.preview] from the same arithmetic as [LoadPolicy.compute],
 * so the picker cannot promise a context the load then refuses.
 *
 * @param requestedCtx the context the setting asks for, after clamping to the trained context. For
 *   [ContextSetting.Auto] it is what Auto resolves to.
 * @param plannedCtx what a load would actually request: [requestedCtx], or less when the RAM budget
 *   forced [LoadPolicy.compute] down its ladder.
 * @param kvCacheType precision [kvBytes] is measured at: the one the plan uses, or
 *   [KvCacheType.Q8_0] (the cheapest there is) when [fits] is false.
 * @param kvBytes KV cache at [requestedCtx] and [kvCacheType].
 * @param totalBytes [LoadPolicy.estimate] at [requestedCtx]: weights + [kvBytes] + compute buffers,
 *   the same sum the compatibility verdict is decided from.
 * @param budgetBytes [DeviceProfile.usableRamBytes], what [totalBytes] is held against.
 * @param fits whether [requestedCtx] is what a load would get. `false` is the picker's warning: the
 *   load will start lower, see [plannedCtx], or not at all, see [feasible].
 * @param feasible whether any context at all can be loaded, `false` for [LoadPlan.Infeasible].
 */
data class ContextPreview(
    val requestedCtx: Int,
    val plannedCtx: Int,
    val kvCacheType: KvCacheType,
    val kvBytes: Long,
    val totalBytes: Long,
    val budgetBytes: Long,
    val fits: Boolean,
    val feasible: Boolean,
)

/**
 * Derives the [LoadPlan] the TEN-30 loading flow requests for a given [ModelEntity] on a given
 * [DeviceProfile] — see [compute]'s KDoc for the full policy.
 */
object LoadPolicy {

    /**
     * The smallest context this policy will ever plan for a model that could do better — below it
     * a load is not a degraded chat, it is a broken one, and TEN-77 would rather say so than ship
     * it. Measured on the reference device: at 256 tokens a 31-token prompt left 225 for the
     * answer and the model returned nothing at all, with no error anywhere in the UI.
     *
     * 1024 because it is the bottom rung of [CTX_LADDER] and because that same 31-token prompt
     * still leaves ~1000 tokens of reply — short, but a conversation.
     *
     * A model whose *trained* context is below this is not held to it: it can't do better, so its
     * own maximum is its floor (see [compute]).
     */
    const val MIN_USABLE_CTX = 1024

    /**
     * The context assumed for a model whose GGUF records none, and what the engine loads in that
     * case: `tensai_jni.cpp` turns a non-positive `n_ctx` into 4096. Before TEN-150 this value
     * doubled as a latency cap on every model; it is only a fallback now.
     */
    private const val UNKNOWN_TRAINED_CTX = 4_096L

    /**
     * Step [ContextSetting.Auto] rounds down to when RAM, and not the trained context, is what
     * limits it. A finer answer moves with anything feeding it (this app's overhead constant, a
     * re-derived KV shape); rounding to 1024 means those have to move a whole step before the
     * planned `n_ctx` does, and `n_ctx` is part of
     * [io.github.hokanosekai.tensai.data.session.SessionModelKey]. A multiple of 256, so
     * [KvCacheSize]'s cell padding leaves it alone, and 1024 is [MIN_USABLE_CTX].
     */
    private const val AUTO_STEP = 1_024L

    /**
     * The contexts a RAM-reduced *manual* plan is allowed to land on, largest first.
     *
     * Snapping down to coarse rungs means anything feeding the plan has to move a lot before the
     * planned `n_ctx` moves at all, and `n_ctx` is part of
     * [io.github.hokanosekai.tensai.data.session.SessionModelKey]: a plan that drifts by one
     * 256-cell block invalidates every persisted session on the device, discarding a measured
     * 23.5x time-to-first-token win (see
     * [io.github.hokanosekai.tensai.data.session.SessionStoragePolicy]).
     *
     * The cost is bounded by one rung: a device that could hold 2816 tokens is planned at 2048.
     * That is the trade: up to half a rung of context, against a context that means the same
     * thing tomorrow.
     *
     * TEN-150 added the rungs above 4096, so that a manual 32K the device cannot back steps down
     * through 16K and 8K rather than falling straight to 4K, and so that an out-of-memory load can
     * step down one rung ([stepDown]). Rungs are multiples of 256 so [KvCacheSize]'s cell padding
     * leaves them alone.
     */
    private val CTX_LADDER = listOf(
        131_072L, 65_536L, 32_768L, 16_384L, 8_192L, 4_096L, 3_072L, 2_048L, MIN_USABLE_CTX.toLong(),
    )

    /**
     * Base compute-buffer/graph-overhead allowance, whatever the context. What is left of the old flat
     * allowance now that [computeBufferBytes] adds the part that grows with `n_ctx`.
     */
    private const val OVERHEAD_BYTES = 300L * 1024 * 1024

    /**
     * `n_ubatch` of a load: llama.cpp's own 512 (`common_params` and `llama_context_default_params`),
     * which `tensai_jni.cpp` does not override (it only sets `n_batch`, to 512 when unset, and
     * `common_params` already holds 2048). The attention mask is `n_kv x n_ubatch`.
     */
    private const val UBATCH_TOKENS = 512L

    /**
     * Bytes per mask element: `llama_kv_cache` builds the KQ mask as F32 and, with flash attention,
     * a F16 copy of it, so 4 + 2.
     */
    private const val MASK_BYTES_PER_ELEMENT = 6L

    /**
     * Compute-buffer bytes that grow with [nCtx], on top of [OVERHEAD_BYTES].
     *
     * What this build does by default: `tensai_jni.cpp` only sets `flash_attn_type` when Kotlin
     * passes `"on"` or `"off"`, and `LlamaKtEngineImpl` passes nothing, so it stays
     * `LLAMA_FLASH_ATTN_TYPE_AUTO` (the default of both `common_params` and
     * `llama_context_default_params`). AUTO turns flash attention on once the backend can run the
     * op, which the CPU backend always can. With it the `n_ctx x n_ubatch x n_head` score matrix is
     * never materialised and the only buffer growing with the context is the mask, `n_kv x
     * n_ubatch`, modelled here at [MASK_BYTES_PER_ELEMENT] per element over the padded cells.
     * Roughly 1.5 MiB per 512 tokens, 96 MiB at 32K.
     *
     * Not modelled: a backend that rejects the flash-attention probe and falls back to the
     * unfused path, which costs about `n_ctx * n_ubatch * n_head * 4` bytes more. `n_head` is not
     * stored on [ModelEntity], and no backend this app runs on is known to fall back.
     */
    fun computeBufferBytes(nCtx: Long): Long {
        val cells = if (nCtx <= 0) 0L else (nCtx + 255) / 256 * 256
        return cells * UBATCH_TOKENS * MASK_BYTES_PER_ELEMENT
    }

    /**
     * What loading [model] at [nCtx] and [kvCacheType] is expected to cost: weights + KV cache
     * ([KvCacheSize]) + [OVERHEAD_BYTES] + [computeBufferBytes]. The one total behind the plan, the
     * pickers and [io.github.hokanosekai.tensai.compat.CompatibilityChecker]'s estimate.
     */
    fun estimate(model: ModelEntity, nCtx: Long, kvCacheType: KvCacheType): Long =
        model.sizeBytes + OVERHEAD_BYTES + computeBufferBytes(nCtx) + KvCacheSize.bytesAt(model, nCtx, kvCacheType)

    /**
     * The setting a load of [model] follows: its own [ModelEntity.contextSettingOverride] when it
     * has one, [global] otherwise.
     */
    fun effectiveSetting(model: ModelEntity, global: ContextSetting): ContextSetting =
        model.contextSettingOverride ?: global

    /**
     * Computes the [LoadPlan] to load [model] on [profile], for the setting [effectiveSetting]
     * picks out of the model's override and [global]. [autoShare] only matters to Auto.
     *
     * `trained` is [ModelEntity.contextLength], or 4096 when the header recorded none. Every "fits"
     * below is [estimate] against a limit, so the compute buffers grow with the context being tried.
     *
     * **Manual sizes and Max** (a fixed size is clamped down to `trained`, so a global setting never
     * asks a small model for a context it was not trained on; Max is `trained`). Limit: the full
     * [DeviceProfile.usableRamBytes].
     * 1. `wanted` at [KvCacheType.F16] if it fits.
     * 2. Else `wanted` at [KvCacheType.Q8_0] (~53% of the bytes/token of F16).
     * 3. Else the first [CTX_LADDER] rung below `wanted` that fits at Q8_0: the safety net the
     *    picker's warning refers to. It only goes down from the choice.
     * 4. Else [LoadPlan.Infeasible], floor = the lowest rung.
     *
     * **Auto** has no latency cap and does not try to fill the phone: its limit is [autoShare] of
     * [DeviceProfile.usableRamBytes] (the rest stays for Android and other apps), and its context
     * is the largest multiple of [AUTO_STEP] that fits, or `trained` itself when that fits.
     * 1. At F16 within the share. Kept when it reaches `min(trained, 4096)`.
     * 2. Else at Q8_0 within the share, when that buys more context than F16 managed.
     * 3. Else, when not even [MIN_USABLE_CTX] fits the share, the floor at Q8_0 within the *full*
     *    budget: Auto never refuses a model the compatibility check lets through.
     * 4. Else [LoadPlan.Infeasible].
     *
     * Nothing below the floor is ever planned; see [MIN_USABLE_CTX].
     *
     * The budget is [DeviceProfile.usableRamBytes], total RAM less the system margin, **not** free
     * RAM. That property's KDoc has the measurements; the consequence here is that the answer is a
     * pure function of the model, the setting and the phone, so the same model plans the same
     * `n_ctx` every time it is loaded.
     *
     * KV size comes from [KvCacheSize], which reads the model's real attention shape out of the
     * GGUF header and falls back to a deliberate over-estimate when that shape is unknown.
     */
    fun compute(
        model: ModelEntity,
        profile: DeviceProfile,
        global: ContextSetting = ContextSetting.Auto,
        autoShare: AutoMemoryShare = AutoMemoryShare.P50,
    ): LoadPlan = computeFor(model, profile, effectiveSetting(model, global), autoShare)

    /**
     * [compute] for an explicit [setting], ignoring [ModelEntity.contextOverride], what a picker
     * needs to price each of its options.
     */
    fun computeFor(
        model: ModelEntity,
        profile: DeviceProfile,
        setting: ContextSetting,
        autoShare: AutoMemoryShare = AutoMemoryShare.P50,
    ): LoadPlan {
        val trained = trainedContextOf(model)
        val full = profile.usableRamBytes
        val floorCtx = minOf(MIN_USABLE_CTX.toLong(), trained)

        fun fits(ctx: Long, type: KvCacheType, limit: Long) = estimate(model, ctx, type) <= limit

        fun infeasible(floor: Long) = LoadPlan.Infeasible(
            requiredBytes = estimate(model, floor, KvCacheType.Q8_0),
            budgetBytes = full,
            floor = LoadPlan.Feasible(floor.toInt(), KvCacheType.Q8_0),
        )

        if (setting == ContextSetting.Auto) {
            val limit = full * autoShare.percent / 100

            // Largest multiple of AUTO_STEP within [limit], `trained` itself when it fits. Binary
            // search over the multiples: the estimate only grows with the context.
            fun largest(type: KvCacheType): Long? {
                if (fits(trained, type, limit)) return trained
                var low = 0L
                var high = trained / AUTO_STEP
                while (low < high) {
                    val mid = low + (high - low + 1) / 2
                    if (fits(mid * AUTO_STEP, type, limit)) low = mid else high = mid - 1
                }
                return (low * AUTO_STEP).takeIf { it >= floorCtx && it > 0 }
            }

            val f16 = largest(KvCacheType.F16)
            if (f16 != null && f16 >= minOf(trained, UNKNOWN_TRAINED_CTX)) {
                return LoadPlan.Feasible(f16.toInt(), KvCacheType.F16)
            }
            val q8 = largest(KvCacheType.Q8_0)
            if (q8 != null && (f16 == null || q8 > f16)) return LoadPlan.Feasible(q8.toInt(), KvCacheType.Q8_0)
            if (f16 != null) return LoadPlan.Feasible(f16.toInt(), KvCacheType.F16)
            return if (fits(floorCtx, KvCacheType.Q8_0, full)) {
                LoadPlan.Feasible(floorCtx.toInt(), KvCacheType.Q8_0)
            } else {
                infeasible(floorCtx)
            }
        }

        val wanted = requestedContext(setting, trained)
        if (fits(wanted, KvCacheType.F16, full)) return LoadPlan.Feasible(wanted.toInt(), KvCacheType.F16)

        // `wanted` heads the list even when it isn't a rung: a model asking for 1536 gets 1536 if
        // it fits, and only a RAM-forced reduction snaps to the ladder.
        val steps = listOf(wanted) + CTX_LADDER.filter { it < wanted }
        steps.firstOrNull { fits(it, KvCacheType.Q8_0, full) }
            ?.let { return LoadPlan.Feasible(it.toInt(), KvCacheType.Q8_0) }
        return infeasible(steps.last())
    }

    /**
     * The context one rung below [nCtx], for retrying a load that ran out of memory, or `null` when
     * [nCtx] is already the floor (`min(`[MIN_USABLE_CTX]`, trained)`). The next [CTX_LADDER] rung
     * under [nCtx], or the floor when that is all that is left.
     */
    fun stepDown(model: ModelEntity, nCtx: Int): Int? {
        val floor = minOf(MIN_USABLE_CTX.toLong(), trainedContextOf(model))
        if (nCtx <= floor) return null
        return (CTX_LADDER.firstOrNull { it < nCtx && it >= floor } ?: floor).toInt()
    }

    /**
     * What [setting] would cost [model] on [profile], for a picker to print next to the option. See
     * [ContextPreview] and [compute]: the figures come from the plan, not from a second formula.
     */
    fun preview(
        model: ModelEntity,
        profile: DeviceProfile,
        setting: ContextSetting,
        autoShare: AutoMemoryShare = AutoMemoryShare.P50,
    ): ContextPreview {
        val plan = computeFor(model, profile, setting, autoShare)
        val planned = when (plan) {
            is LoadPlan.Feasible -> plan
            is LoadPlan.Infeasible -> plan.floor
        }
        // Auto asks for whatever it resolves to, so it can only "not fit" by being infeasible.
        val requested = when (setting) {
            ContextSetting.Auto -> planned.nCtx.toLong()
            else -> requestedContext(setting, trainedContextOf(model))
        }
        val fits = plan is LoadPlan.Feasible && plan.nCtx.toLong() == requested
        val kvCacheType = if (fits) planned.kvCacheType else KvCacheType.Q8_0
        return ContextPreview(
            requestedCtx = requested.toInt(),
            plannedCtx = planned.nCtx,
            kvCacheType = kvCacheType,
            kvBytes = KvCacheSize.bytesAt(model, requested, kvCacheType),
            totalBytes = estimate(model, requested, kvCacheType),
            budgetBytes = profile.usableRamBytes,
            fits = fits,
            feasible = plan is LoadPlan.Feasible,
        )
    }

    /** What [setting] asks for before RAM gets a say: `trained` for Auto and Max, else the size clamped to it. */
    private fun requestedContext(setting: ContextSetting, trained: Long): Long =
        setting.tokens?.toLong()?.coerceAtMost(trained) ?: trained

    /**
     * [ModelEntity.contextLength] when the header actually yielded one, [UNKNOWN_TRAINED_CTX]
     * otherwise.
     *
     * A model whose GGUF has no `context_length` key records 0, and 0 is not a context the engine
     * will honour: `tensai_jni.cpp` turns a non-positive `n_ctx` into 4096 at load time. Passing
     * the 0 through would budget for an empty cache and then load a 4096-cell one — the exact
     * under-estimate this policy exists to avoid — so the unknown case is planned as the same
     * 4096 the engine is going to use.
     */
    fun trainedContext(model: ModelEntity): Long = trainedContextOf(model)

    private fun trainedContextOf(model: ModelEntity): Long =
        if (model.contextLength > 0) model.contextLength else UNKNOWN_TRAINED_CTX
}
