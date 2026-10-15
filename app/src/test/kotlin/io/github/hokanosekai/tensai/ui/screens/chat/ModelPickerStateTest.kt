package io.github.hokanosekai.tensai.ui.screens.chat

import io.github.hokanosekai.tensai.compat.Verdict
import io.github.hokanosekai.tensai.engine.KvCacheType
import io.github.hokanosekai.tensai.engine.LoadedModelInfo
import io.github.hokanosekai.tensai.runtime.QueueState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPickerStateTest {

    private fun row(name: String, verdict: Verdict?, loaded: Boolean = false) =
        PickerRow(id = name, name = name, facts = null, verdict = verdict, loaded = loaded)

    private val info = LoadedModelInfo("m", 4096, "CPU", KvCacheType.F16, modelId = "m")

    @Test
    fun loadedFirstThenByCompatibilityThenByName() {
        val ordered = orderPickerRows(
            listOf(
                row("zeta", Verdict.WontFit),
                row("beta", Verdict.TightFit),
                row("alpha", Verdict.RunsWell),
                row("gamma", Verdict.RunsWell),
                row("loaded", Verdict.WontFit, loaded = true),
            ),
        )
        assertEquals(listOf("loaded", "alpha", "gamma", "beta", "zeta"), ordered.map { it.name })
    }

    @Test
    fun anUnknownVerdictSortsLast() {
        val ordered = orderPickerRows(listOf(row("a", null), row("b", Verdict.WontFit)))
        assertEquals(listOf("b", "a"), ordered.map { it.name })
    }

    @Test
    fun busyReasonFollowsTheQueue() {
        assertNull(pickerBusy(QueueState.Idle))
        assertNull(pickerBusy(QueueState.ModelReady(info)))
        assertEquals(PickerBusy.Reply, pickerBusy(QueueState.Generating(info)))
        assertEquals(PickerBusy.Loading, pickerBusy(QueueState.Loading(0.2f)))
    }

    @Test
    fun busyRowsIgnoreEveryTap() {
        val fine = row("fine", Verdict.RunsWell)
        val loaded = row("loaded", Verdict.RunsWell, loaded = true)
        val wont = row("wont", Verdict.WontFit)
        for (busy in PickerBusy.entries) {
            assertEquals(PickerTap.Ignore, pickerTap(fine, busy))
            assertEquals(PickerTap.Ignore, pickerTap(loaded, busy))
            assertEquals(PickerTap.Ignore, pickerTap(wont, busy))
            assertEquals(PickerTap.Ignore, pickerLoadAnyway(wont, busy))
        }
    }

    @Test
    fun tappingTheLoadedModelJustCloses() {
        assertEquals(PickerTap.Close, pickerTap(row("m", Verdict.RunsWell, loaded = true), null))
        // Even a loaded model the verdict calls too big: it is running, there is nothing to confirm.
        val big = row("big", Verdict.WontFit, loaded = true)
        assertFalse(big.wontFit)
        assertEquals(PickerTap.Close, pickerTap(big, null))
    }

    @Test
    fun aModelThatWontFitNeedsTheConfirmStep() {
        val wont = row("wont", Verdict.WontFit)
        assertTrue(wont.wontFit)
        assertEquals(PickerTap.ToggleConfirm, pickerTap(wont, null))
        assertEquals(PickerTap.Pick, pickerLoadAnyway(wont, null))
    }

    @Test
    fun otherModelsAreLoadedStraightAway() {
        assertEquals(PickerTap.Pick, pickerTap(row("a", Verdict.RunsWell), null))
        assertEquals(PickerTap.Pick, pickerTap(row("b", Verdict.TightFit), null))
        assertEquals(PickerTap.Ignore, pickerLoadAnyway(row("b", Verdict.TightFit), null))
    }
}
