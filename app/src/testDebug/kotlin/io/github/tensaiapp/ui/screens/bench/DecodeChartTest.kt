package io.github.tensaiapp.ui.screens.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decode chart's y axis (TEN-190): a round top just over the data, so the curve fills the chart. */
class DecodeChartTest {

    @Test
    fun theTopIsARoundValueJustOverTheData() {
        assertEquals(YAxis(12.0, 6), yAxis(9.8))
        assertEquals(YAxis(45.0, 3), yAxis(40.27))
        assertEquals(YAxis(6.0, 6), yAxis(5.21))
        assertEquals(YAxis(1.0, 4), yAxis(0.0))
    }

    @Test
    fun theDataNeverReachesTheTopAndNeverSitsInItsLowerHalf() {
        listOf(0.4, 3.0, 7.7, 9.8, 10.19, 18.0, 30.86, 40.27, 99.0, 250.0).forEach { v ->
            val axis = yAxis(v)
            assertTrue("$v -> $axis", axis.max > v && v > axis.max / 2)
            assertTrue("$v -> $axis", axis.lines in 2..9)
        }
    }
}
