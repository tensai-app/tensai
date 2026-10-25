package io.github.tensaiapp.engine

import com.tensai.llamakt.LlamaEngine
import io.github.tensaiapp.BuildConfig

/** Flash attention request passed to llama.cpp. [Auto] leaves the decision to it. */
enum class FlashAttnMode(val arg: String?) {
    Auto(null),
    On("on"),
    Off("off"),
}

/**
 * Load parameters tuned for a GPU load. `null`, [FlashAttnMode.Auto] and an empty [env] all mean
 * "unchanged", so [NONE] is exactly today's behaviour.
 */
data class VulkanLoadTuning(
    val nBatch: Int? = null,
    val nUbatch: Int? = null,
    val flashAttn: FlashAttnMode = FlashAttnMode.Auto,
    val typeK: String? = null,
    val typeV: String? = null,
    val nThreads: Int? = null,
    val nThreadsBatch: Int? = null,
    val env: Map<String, String> = emptyMap(),
) {
    /**
     * What of this tuning shapes the KV cells (flash attention transposes V or not, the cache types), for a
     * saved prefix's identity. `null` when nothing is forced (a GPU load), so its identity is unchanged.
     */
    fun kvLayoutKey(): String? =
        if (flashAttn == FlashAttnMode.Auto && typeK == null && typeV == null) null else "fa=${flashAttn.name}|k=$typeK|v=$typeV"

    companion object {
        val NONE = VulkanLoadTuning()
    }
}

/** What [VulkanTuning.kvForLoad] settles on: the cache type to load and report, and the tuning to load with. */
data class LoadKv(val kvCacheType: KvCacheType, val tuning: VulkanLoadTuning)

/**
 * The one place to tune Vulkan loads (TEN-197). Edit [CURRENT] between builds: a Kotlin-only
 * rebuild, no native one.
 *
 * Everything except [VulkanLoadTuning.env] is read on each model load and only applies when the
 * load offloads to the GPU (n_gpu_layers > 0): a CPU load gets [VulkanLoadTuning.NONE], except in a debug
 * build, where the bench panel's [CpuLoadKnobs] apply (TEN-188), and then its KV cache from [kvForLoad].
 *
 * [VulkanLoadTuning.env] is different: `LM_GGML_VK_*` variables are setenv()'d once per process by
 * [applyEnv], called from `TensaiApp.onCreate` and, as a guard, before every other path that can
 * touch the ggml backend registry (`DeviceProfiler`, `DeviceInfoViewModel`, `LlamaKtEngineImpl`).
 * Changing one needs a process restart (force-stop the app), not just a model reload. They are set
 * whatever the backend of the first load. ggml-vulkan reads them at three moments, all after the
 * first registry touch, so setting them before that covers every one:
 *  - instance init (first backend listing): `LM_GGML_VK_VISIBLE_DEVICES`, the perf/memory loggers;
 *  - device creation (first device query or backend init, at the latest the first load):
 *    `LM_GGML_VK_QUEUE_PRIORITY`, `LM_GGML_VK_MAX_NODES_PER_SUBMIT`, `LM_GGML_VK_DISABLE_*`,
 *    `LM_GGML_VK_FORCE_MMVQ`, `LM_GGML_VK_PREFER_HOST_MEMORY`, ...;
 *  - the first graph compute: `LM_GGML_VK_SUBMIT_MFLOPS` and `LM_GGML_VK_MATMUL_SPLIT_MFLOPS`, `LM_GGML_VK_MATMUL_SPLIT_SUBMIT` (read once, then cached).
 *
 * Experiments (all defaults null/Auto/empty):
 *  - smaller prefill batches:   nUbatch = 128 or 256, nBatch = 256 or 512
 *  - flash attention:           flashAttn = FlashAttnMode.Off (or On)
 *  - quantized KV:              typeK = "q8_0", typeV = "q8_0" (V quantized needs flash attn)
 *  - threads:                   nThreads / nThreadsBatch (they drive the CPU side of the graph)
 *  - shorter GPU submissions:   env = mapOf("LM_GGML_VK_SUBMIT_MFLOPS" to "2000")
 *                               and/or "LM_GGML_VK_MAX_NODES_PER_SUBMIT" to "20"
 *  - split big matmuls (M axis) into shorter dispatches with a submit between them, MFLOP per
 *    dispatch (unset or 0 = stock): env = mapOf("LM_GGML_VK_MATMUL_SPLIT_MFLOPS" to "4000")
 *    (also the submit budget unless LM_GGML_VK_SUBMIT_MFLOPS is set, which wins;
 *    at most 64 chunks per matmul)
 *  - same, without the submit and barrier between chunks (one command buffer; does the GPU preempt
 *    between dispatches?): also "LM_GGML_VK_MATMUL_SPLIT_SUBMIT" to "0" (default 1)
 *  - other backend knobs:       LM_GGML_VK_DISABLE_FUSION, LM_GGML_VK_DISABLE_INTEGER_DOT_PRODUCT,
 *                               LM_GGML_VK_FORCE_MMVQ / LM_GGML_VK_DISABLE_MMVQ, ...
 */
object VulkanTuning {
    /**
     * Measured on a Mali-G68 MC4 (TwIL-LM3 Q4_K_M, 308-token prompt, UI frames from FrameStatsLogger):
     *  - n_ubatch 512 (stock): prefill 3.0 tok/s, ~8.6 fps, frames frozen up to 1 s;
     *  - n_ubatch 128: prefill 3.7 tok/s, ~12 fps, worst frame ~600 ms;
     *  - n_ubatch 128 + a LOW global priority queue: prefill 3.7 tok/s, ~20 fps, worst frame 360-650 ms.
     * The Mali preempts the low priority compute queue for the compositor between dispatches, at no
     * cost in speed. The matmul split (TEN-198) is left off: smaller dispatches cost speed in
     * themselves (287-token prefill, same settings):
     *  - split 1000 MFLOP, one command buffer: 2.7 tok/s, ~32 fps, worst frame 210-430 ms;
     *  - split 1000 MFLOP, submit between chunks: 2.0 tok/s, ~32 fps, worst frame ~230 ms;
     *  - split 4000 MFLOP, submit between chunks: 3.0 tok/s, 22-36 fps.
     * Set LM_GGML_VK_MATMUL_SPLIT_MFLOPS=1000 with LM_GGML_VK_MATMUL_SPLIT_SUBMIT=0 to favour the UI.
     */
    val CURRENT = VulkanLoadTuning(
        nUbatch = 128,
        env = mapOf(
            "LM_GGML_VK_SUBMIT_MFLOPS" to "200",
            "LM_GGML_VK_QUEUE_PRIORITY" to "low",
        ),
    )

    /**
     * What a load with [nGpuLayers] layers on the GPU should apply. A CPU load gets [cpuOverride] (the debug
     * panel's knobs, `CpuLoadOverride`) only when [debug]: a release never does, whatever it is handed.
     */
    fun forLoad(
        nGpuLayers: Int,
        tuning: VulkanLoadTuning = CURRENT,
        cpuOverride: VulkanLoadTuning? = null,
        debug: Boolean = BuildConfig.DEBUG,
    ): VulkanLoadTuning = when {
        nGpuLayers > 0 -> tuning
        debug && cpuOverride != null -> cpuOverride
        else -> VulkanLoadTuning.NONE
    }

    /**
     * TEN-188: the KV cache a load runs with. On the CPU decode reads the whole cache for every token, and a q8_0
     * cache (half the bytes, flash attention on, which a quantized V needs) decodes faster past a few hundred tokens
     * of context. Measured on a Dimensity 900 (Qwen3-1.7B Q4_K_M, 2 threads, decode tok/s at 0/512/1K/2K/4K tokens):
     * f16 10.2/7.6/6.6/5.2/3.8, q8_0 with flash attention 10.1/8.7/7.9/6.5/4.7; the prefill is 15 to 49% slower.
     *
     * So a CPU load ([nGpuLayers] 0) gets q8_0 and flash attention on, and returns that cache type: it is the one
     * [LoadedModelInfo], the session key and the prefix identity carry, so state saved from an f16 cache is never
     * read back into this one. Except:
     *  - a cache type forced by [tuning] (the debug knobs): it wins, flash attention as it says;
     *  - flash attention forced off by [tuning]: F16, a quantized V could not load without it;
     *  - [quantizedFits] false (a head dimension the q8_0 block does not divide, see [quantizedKvFits]): F16, flash
     *    attention left to llama.cpp, which would refuse the context otherwise;
     *  - [requested] already quantized ([LoadPolicy][io.github.tensaiapp.runtime.LoadPolicy] short of memory): kept,
     *    with flash attention on.
     * A GPU load is unchanged.
     */
    fun kvForLoad(nGpuLayers: Int, requested: KvCacheType, tuning: VulkanLoadTuning, quantizedFits: Boolean = true): LoadKv {
        if (nGpuLayers > 0) return LoadKv(requested, tuning)
        tuning.typeK?.let { forced ->
            return LoadKv(KvCacheType.entries.firstOrNull { it.name.lowercase() == forced } ?: requested, tuning)
        }
        if (tuning.flashAttn == FlashAttnMode.Off || !quantizedFits) return LoadKv(KvCacheType.F16, tuning)
        val kv = if (requested == KvCacheType.F16) KvCacheType.Q8_0 else requested
        return LoadKv(kv, tuning.copy(flashAttn = FlashAttnMode.On))
    }

    /** Elements per block of the quantized cache types (q8_0 and q4_0 alike). */
    private const val QuantBlock = 32L

    /**
     * Whether a quantized KV cache can hold this model: llama.cpp refuses a q8_0 cache with flash attention when the
     * block does not divide a head dimension (`n_embd_head_k % 32`). The GGUF header ([com.tensai.llamakt.GgufMetadata]) gives
     * the head dimension two ways, both checked: llama.cpp's default `embedding_length / head_count`, and the K+V
     * elements per token over `2 * block_count * head_count_kv`, which follows `attention.key_length` when the file
     * sets one (Qwen3: 1024 / 16 = 64, but 128). The second is skipped when it does not divide evenly (shared or
     * windowed layers). `false` when neither can be read: unknown is not worth a refused load.
     */
    fun quantizedKvFits(
        embeddingLength: Long,
        headCount: Long,
        headCountKv: Long,
        blockCount: Long,
        kvElementsPerToken: Long,
    ): Boolean {
        val dims = buildList {
            if (embeddingLength > 0 && headCount > 0) add(embeddingLength / headCount)
            val perHeadPair = 2 * blockCount * headCountKv
            if (perHeadPair > 0 && kvElementsPerToken > 0 && kvElementsPerToken % perHeadPair == 0L) add(kvElementsPerToken / perHeadPair)
        }
        return dims.isNotEmpty() && dims.all { it > 0 && it % QuantBlock == 0L }
    }

    /** "KEY=VALUE" entries for [com.tensai.llamakt.LlamaEngine.setEnv]. */
    fun envEntries(tuning: VulkanLoadTuning = CURRENT): List<String> =
        tuning.env.map { (k, v) -> "$k=$v" }

    private val envLock = Any()
    private var envApplied = false

    /**
     * setenv()s [VulkanLoadTuning.env] once per process; later calls return at once. Blocking:
     * a caller racing the first one waits until the variables are set, so nothing can reach the
     * backend registry ahead of them. Nothing is loaded when the map is empty. The native side
     * logs each entry as `vk env: K=V`. [setter] is a seam for JVM tests.
     */
    fun applyEnv(
        tuning: VulkanLoadTuning = CURRENT,
        setter: (List<String>) -> Unit = { LlamaEngine.setEnv(it) },
    ) {
        synchronized(envLock) {
            if (envApplied) return
            val entries = envEntries(tuning)
            if (entries.isNotEmpty()) setter(entries)
            envApplied = true
        }
    }

    internal fun resetEnvAppliedForTest() {
        synchronized(envLock) { envApplied = false }
    }
}
