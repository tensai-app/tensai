package io.github.hokanosekai.tensai.engine

import com.tensai.llamakt.LlamaEngine

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
    companion object {
        val NONE = VulkanLoadTuning()
    }
}

/**
 * The one place to tune Vulkan loads (TEN-197). Edit [CURRENT] between builds: a Kotlin-only
 * rebuild, no native one.
 *
 * Everything except [VulkanLoadTuning.env] is read on each model load and only applies when the
 * load offloads to the GPU (n_gpu_layers > 0): a CPU load always gets [VulkanLoadTuning.NONE].
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

    /** What a load with [nGpuLayers] layers on the GPU should apply. */
    fun forLoad(nGpuLayers: Int, tuning: VulkanLoadTuning = CURRENT): VulkanLoadTuning =
        if (nGpuLayers > 0) tuning else VulkanLoadTuning.NONE

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
