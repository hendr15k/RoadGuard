package com.roadguard.app.data.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneOffsetWindowTest {

    @Test
    fun medianIgnoresASingleOutlier() {
        val window = LaneOffsetWindow()
        assertEquals(10f, window.add(10f), 0.01f)
        assertEquals(11f, window.add(11f), 0.01f)
        assertEquals(11f, window.add(12f), 0.01f)
        // The outlier is outvoted; the median stays at 11.
        assertEquals(11f, window.add(-100f), 0.01f)
    }

    @Test
    fun windowResets() {
        val window = LaneOffsetWindow()
        window.add(10f)
        window.reset()
        assertEquals(0, window.size)
        assertEquals(5f, window.add(5f), 0.01f)
    }
}
