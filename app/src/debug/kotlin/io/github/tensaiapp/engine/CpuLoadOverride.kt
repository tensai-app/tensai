package io.github.tensaiapp.engine

import com.tensai.llamakt.ThreadPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Debug builds only (the release source set has a stub that never overrides): the [CpuLoadKnobs] the bench panel
 * sets (TEN-188), read by each load in [LlamaKtEngineImpl.loadModel]. A change applies on the next load. In
 * memory only: a new process starts from the defaults, today's load.
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

    /** The override for a load with [nGpuLayers] layers on the GPU, `null` for a GPU load. Records [applied]. */
    fun forLoad(nGpuLayers: Int): VulkanLoadTuning? {
        if (nGpuLayers > 0) {
            applied = null
            return null
        }
        val knobs = _knobs.value
        applied = knobs
        return knobs.toTuning(batchThreads = ThreadPolicy.resolve(ThreadPolicy.readCoreSpeeds()).batch)
    }
}
