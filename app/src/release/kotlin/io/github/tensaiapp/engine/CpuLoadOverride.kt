package io.github.tensaiapp.engine

/** The CPU load knobs are debug-only (see the debug source set): a release load never takes an override. */
object CpuLoadOverride {
    /** No knobs in a release: the load took none. */
    val applied: CpuLoadKnobs? get() = null

    @Suppress("UNUSED_PARAMETER")
    fun forLoad(nGpuLayers: Int): VulkanLoadTuning? = null

    fun loaded() = Unit
}
