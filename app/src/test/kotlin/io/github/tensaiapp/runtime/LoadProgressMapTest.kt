package io.github.tensaiapp.runtime

import io.github.tensaiapp.engine.BackendHint
import io.github.tensaiapp.engine.LoadStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TEN-123: the stage + weights progress to overall percent mapping. Pure, no device needed. */
class LoadProgressMapTest {

    @Test
    fun weightsProgressDrivesTheFirstEightyPercent() {
        assertEquals(0, LoadProgressMap.percent(LoadStage.Weights, 0f))
        assertEquals(40, LoadProgressMap.percent(LoadStage.Weights, 0.5f))
        assertEquals(80, LoadProgressMap.percent(LoadStage.Weights, 1f))
    }

    @Test
    fun laterStagesSitAtTheirFloorWhateverTheWeightsValueIs() {
        assertEquals(80, LoadProgressMap.percent(LoadStage.Context, 1f))
        assertEquals(92, LoadProgressMap.percent(LoadStage.Warmup, 1f))
        assertEquals(100, LoadProgressMap.percent(LoadStage.Ready, 1f))
        // A stage never reads the weights value, so a stale one cannot move the bar.
        assertEquals(80, LoadProgressMap.percent(LoadStage.Context, 0f))
    }

    @Test
    fun overallNeverGoesBackwardsAcrossTheStageOrder() {
        val seq = listOf(
            LoadStage.Weights to 0f, LoadStage.Weights to 0.7f, LoadStage.Weights to 1f,
            LoadStage.Context to 1f, LoadStage.Warmup to 1f, LoadStage.Ready to 1f,
        ).map { (s, w) -> LoadProgressMap.overall(s, w) }
        assertEquals(seq.sorted(), seq)
    }

    @Test
    fun preparingIsZeroPercent() {
        assertEquals(0, LoadProgressMap.percent(LoadStage.Preparing, 0.9f))
    }

    @Test
    fun outOfRangeWeightsProgressIsClamped() {
        assertEquals(0f, LoadProgressMap.overall(LoadStage.Weights, -1f), 0f)
        assertEquals(0.8f, LoadProgressMap.overall(LoadStage.Weights, 7f), 0f)
    }

    @Test
    fun onlyTheStagesWithoutInnerProgressAreIndeterminate() {
        assertTrue(LoadProgressMap.isIndeterminate(LoadStage.Preparing, 0f))
        assertTrue("no progress value yet", LoadProgressMap.isIndeterminate(LoadStage.Weights, 0f))
        assertFalse(LoadProgressMap.isIndeterminate(LoadStage.Weights, 0.01f))
        assertTrue(LoadProgressMap.isIndeterminate(LoadStage.Context))
        assertTrue(LoadProgressMap.isIndeterminate(LoadStage.Warmup))
        assertFalse(LoadProgressMap.isIndeterminate(LoadStage.Ready))
    }

    @Test
    fun backendLabelFollowsTheHintThenWhatTheDeviceExposes() {
        assertEquals("CPU", LoadProgressMap.backendLabel(BackendHint.Cpu, hasVulkan = true, hasOpenCl = true))
        assertEquals("Vulkan", LoadProgressMap.backendLabel(BackendHint.Auto, hasVulkan = true, hasOpenCl = true))
        assertEquals("OpenCL", LoadProgressMap.backendLabel(BackendHint.Gpu, hasVulkan = false, hasOpenCl = true))
        assertEquals("CPU", LoadProgressMap.backendLabel(BackendHint.Gpu, hasVulkan = false, hasOpenCl = false))
    }
}
