package com.roadguard.app.data.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneOffsetWindowTest {

    @Test
    fun medianIgnoresASingleOutlier() {
        val window = LaneOffsetWindow()
        window.add(10f)
        window.add(11f)
        window.add(12f)
        // Four samples [-100, 10, 11, 12]: the median is (10 + 11) / 2 = 10.5.
        // The old implementation returned sorted[2] = 11 — the upper middle
        // order statistic — and this test pinned that artifact. The standard
        // median averages the two middle samples.
        assertEquals(10.5f, window.add(-100f), 0.01f)
    }

    @Test
    fun anEvenWindowAveragesTheTwoMiddleSamples() {
        // Four samples [-100, 10, 11, 12]: the median is (10 + 11) / 2 = 10.5.
        // The implementation returned sorted[2] = 11, i.e. the upper middle.
        // The default window is odd, but the class is parametrised and an even
        // size silently dropped half a sample of bias into every reading.
        val window = LaneOffsetWindow(windowSize = 4)
        val values = listOf(10f, 11f, 12f, -100f)
        var last = 0f
        for (v in values) last = window.add(v)
        assertEquals(10.5f, last, 0.01f)
    }

    @Test
    fun aNonFiniteSampleIsIgnoredByTheWindow() {
        val window = LaneOffsetWindow()
        window.add(10f)
        window.add(12f)
        // A NaN must not become the median (Kotlin sorts NaN as the largest
        // element) and must not evict a real sample either. Two real samples
        // [10, 12] average out at 11.
        val after = window.add(Float.NaN)
        assertEquals(11f, after, 0.01f)
        assertTrue("the window must keep its real samples", window.size in 2..7)
    }

    @Test
    fun aNonPositiveWindowSizeIsRejected() {
        // A windowSize <= 0 deleted every sample as it arrived, so the window
        // could never fill and the gate's history floor was unreachable.
        try {
            LaneOffsetWindow(windowSize = 0)
            throw AssertionError("windowSize 0 must be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
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
