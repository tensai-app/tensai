package io.github.tensaiapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The debug knobs' record of what the resident model was loaded with (TEN-190): only a load that succeeded changes it. */
class CpuLoadOverrideTest {

    @Test
    fun aLoadIsRecordedOnlyOnceItSucceeds() {
        val first = CpuLoadKnobs(decodeThreads = 2)
        CpuLoadOverride.set(first)
        CpuLoadOverride.forLoad(nGpuLayers = 0)
        CpuLoadOverride.loaded()
        assertEquals(first, CpuLoadOverride.applied)

        // A second load with other knobs that fails: the resident model is still described by the first.
        CpuLoadOverride.set(CpuLoadKnobs(decodeThreads = 4, kv = KvKnob.F16))
        CpuLoadOverride.forLoad(nGpuLayers = 0)
        assertEquals(first, CpuLoadOverride.applied)

        // A GPU load that succeeds takes no knobs.
        CpuLoadOverride.forLoad(nGpuLayers = 99)
        CpuLoadOverride.loaded()
        assertNull(CpuLoadOverride.applied)
        CpuLoadOverride.set(CpuLoadKnobs())
    }
}
