package io.github.hokanosekai.tensai.runtime

import io.github.hokanosekai.tensai.compat.DeviceProfile
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
     * The contexts a RAM-reduced plan is allowed to land on, largest first.
     *
     * [KvCacheSize.maxCtxFitting] can answer with any multiple of 256, and it used to: whatever
     * the binary search happened to reach. That answer moves whenever anything feeding it moves —
     * the memory reading (fixed by [DeviceProfile.usableRamBytes]), but also this app's own
     * overhead constant and the model's recorded KV shape, which TEN-72 re-derives whenever the
     * formula that produced it changes. Snapping down to four coarse rungs means those all have to
     * move a lot before the planned `n_ctx` moves at all, and `n_ctx` is part of
     * [io.github.hokanosekai.tensai.data.session.SessionModelKey]: a plan that drifts by one
     * 256-cell block invalidates every persisted session on the device, discarding a measured
     * 23.5x time-to-first-token win (see
     * [io.github.hokanosekai.tensai.data.session.SessionStoragePolicy]).
     *
     * The cost is bounded by one rung: a device that could hold 2816 tokens is planned at 2048.
     * That is the trade — up to 1023 tokens of context, against a context that means the same
     * thing tomorrow.
     *
     * Rungs are multiples of 256 so [KvCacheSize]'s cell padding leaves them alone.
     */
    private val CTX_LADDER = listOf(4_096L, 3_072L, 2_048L, MIN_USABLE_CTX.toLong())

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
     * 3. Otherwise, drop to [KvCacheType.Q8_0] (~53% of the bytes/token of F16) and take the
     *    largest [CTX_LADDER] rung that fits — trading KV precision at the context the user could
     *    plausibly want beats shrinking the context, so `wanted` itself is the first candidate.
     * 4. If not even the floor rung fits, return [LoadPlan.Infeasible]. Nothing below the floor is
     *    ever planned; see [MIN_USABLE_CTX].
     *
     * The budget is [DeviceProfile.usableRamBytes] — total RAM less the system margin, **not**
     * free RAM. That property's KDoc has the measurements; the consequence here is that the answer
     * is a pure function of the model and the phone, so the same model plans the same `n_ctx`
     * every time it is loaded.
     *
     * KV size comes from [KvCacheSize], which reads the model's real attention shape out of the
     * GGUF header and falls back to a deliberate over-estimate when that shape is unknown — see
     * its KDoc for why every uncertainty resolves upwards.
     */
    fun compute(model: ModelEntity, profile: DeviceProfile): LoadPlan {
        val wanted = trainedContextOf(model).coerceAtMost(CTX_LATENCY_CAP.toLong())
        val budget = (profile.usableRamBytes - model.sizeBytes - OVERHEAD_BYTES).coerceAtLeast(0)

        if (KvCacheSize.bytesAt(model, wanted, KvCacheType.F16) <= budget) {
            return LoadPlan.Feasible(wanted.toInt(), KvCacheType.F16)
        }

        // `wanted` heads the list even when it isn't a rung: a model asking for 1536 gets 1536 if
        // it fits, and only a RAM-forced reduction snaps to the ladder. `wanted` comes from the
        // GGUF header, so it is as reproducible as the rungs are.
        val steps = listOf(wanted) + CTX_LADDER.filter { it < wanted }
        val fits = KvCacheSize.maxCtxFitting(model, budget, KvCacheType.Q8_0, wanted)
        steps.firstOrNull { it <= fits }?.let { return LoadPlan.Feasible(it.toInt(), KvCacheType.Q8_0) }

        val floor = steps.last()
        return LoadPlan.Infeasible(
            requiredBytes = model.sizeBytes + OVERHEAD_BYTES +
                KvCacheSize.bytesAt(model, floor, KvCacheType.Q8_0),
            budgetBytes = profile.usableRamBytes,
            floor = LoadPlan.Feasible(floor.toInt(), KvCacheType.Q8_0),
        )
    }

    /**
     * [ModelEntity.contextLength] when the header actually yielded one, [CTX_LATENCY_CAP]
     * otherwise.
     *
     * A model whose GGUF has no `context_length` key records 0, and 0 is not a context the engine
     * will honour: `tensai_jni.cpp` turns a non-positive `n_ctx` into 4096 at load time. Passing
     * the 0 through would budget for an empty cache and then load a 4096-cell one — the exact
     * under-estimate this policy exists to avoid — so the unknown case is planned as the same
     * 4096 the engine is going to use.
     */
    private fun trainedContextOf(model: ModelEntity): Long =
        if (model.contextLength > 0) model.contextLength else CTX_LATENCY_CAP.toLong()
}
