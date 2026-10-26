package io.github.tensaiapp.engine

/** KV cache type the debug knobs can force, [arg] as llama.cpp names it. */
enum class KvKnob(val arg: String) {
    F16("f16"),
    Q8_0("q8_0"),
}

/**
 * The CPU load knobs of the debug bench panel (TEN-188), applied by the next CPU load of a debug build only
 * ([VulkanTuning.forLoad]). `null` and [FlashAttnMode.Auto] mean "unchanged", so the default is today's load.
 *
 * @param decodeThreads llama.cpp's `n_threads` (token generation), `null` for the big cores ([com.tensai.llamakt.ThreadPolicy]).
 *   The prefill keeps its own count: see [toTuning].
 * @param kv both K and V, `null` for the default ([VulkanTuning.kvForLoad]: q8_0 with flash attention on). A quantized V
 *   needs flash attention, so [Q8_0][KvKnob.Q8_0] forces it on ([effectiveFlashAttn]).
 */
data class CpuLoadKnobs(
    val decodeThreads: Int? = null,
    val flashAttn: FlashAttnMode = FlashAttnMode.Auto,
    val kv: KvKnob? = null,
) {
    /** What the load asks for: on with a quantized KV, whatever [flashAttn] says. */
    val effectiveFlashAttn: FlashAttnMode get() = if (kv == KvKnob.Q8_0) FlashAttnMode.On else flashAttn

    /** [kv] picked: a quantized cache turns flash attention on, and leaving it later keeps it on. */
    fun withKv(kv: KvKnob?): CpuLoadKnobs = copy(kv = kv, flashAttn = if (kv == KvKnob.Q8_0) FlashAttnMode.On else flashAttn)

    /**
     * The load tuning, [VulkanLoadTuning.NONE] for the default knobs. [batchThreads] is the prefill's count when
     * nothing is forced (`ThreadPolicy`'s, 0 when unknown): with an explicit `n_threads` llama.kt would otherwise use
     * it for the prefill too, and a decode experiment must not move the prefill.
     */
    fun toTuning(batchThreads: Int): VulkanLoadTuning = VulkanLoadTuning(
        flashAttn = effectiveFlashAttn,
        typeK = kv?.arg,
        typeV = kv?.arg,
        nThreads = decodeThreads,
        nThreadsBatch = decodeThreads?.let { batchThreads.takeIf { it > 0 } },
    )

    companion object {
        /** The panel's choices, `null` being auto. */
        val DecodeThreadChoices = listOf(null, 2, 3, 4, 6, 8)
    }
}
