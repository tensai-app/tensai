package io.github.tensaiapp.engine

import com.tensai.llamakt.ThreadPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Debug builds only (the release source set has a stub that never overrides): the [CpuLoadKnobs] that Settings > Bench >
 * Decode sets (TEN-188, TEN-190), read by each load in [LlamaKtEngineImpl.loadModel]. A change applies on the next
 * load. In memory only: a new process starts from the defaults, today's load.
 */
object CpuLoadOverride {
    private val _knobs = MutableStateFlow(CpuLoadKnobs())

    /** What the next CPU load takes. */
    val knobs: StateFlow<CpuLoadKnobs> = _knobs.asStateFlow()

    /** The knobs the model in memory was loaded with, `null` before any load and after a GPU one. */
    @Volatile
    var applied: CpuLoadKnobs? = null
        private set

    fun set(knobs: CpuLoadKnobs) {
        _knobs.value = knobs
    }

    /** What the load going on took, [applied] once it succeeds ([loaded]). */
    @Volatile
    private var pending: CpuLoadKnobs? = null

    /** The override for a load with [nGpuLayers] layers on the GPU, `null` for a GPU load. [loaded] records it. */
    fun forLoad(nGpuLayers: Int): VulkanLoadTuning? {
        if (nGpuLayers > 0) {
            pending = null
            return null
        }
        val knobs = _knobs.value
        pending = knobs
        return knobs.toTuning(batchThreads = ThreadPolicy.resolve(ThreadPolicy.readCoreSpeeds()).batch)
    }

    /** The load [forLoad] was asked for succeeded: its knobs now describe the resident model. A failed one changes nothing. */
    fun loaded() {
        applied = pending
    }
}
