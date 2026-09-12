package com.roadguard.app.data.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the hood/bonnet exclusion: the bottom band of the camera frame
 * is the car's own hood, not road, and must be removed before a lane point can
 * win the pair, shift the offset or be measured against paint.
 */
class LaneGeometryHoodTest {

    private fun points(ys: FloatArray): UfldLaneDetector.LanePoints =
        UfldLaneDetector.LanePoints(FloatArray(ys.size) { 500f }, ys)

    @Test
    fun hoodTopSitsAtTheFrameBottomByDefault() {
        // 8 % of a 720 px frame is hood; road ends at row 662.4.
        assertEquals(662.4f, LaneGeometry.hoodTop(720), 0.01f)
        // No hood configured: the road reaches the frame bottom.
        assertEquals(720f, LaneGeometry.hoodTop(720, 0f), 0.01f)
    }

    @Test
    fun aboveHoodDropsPointsOnTheHood() {
        // 720 px frame, road ends at 662. Points at 700/680 are hood.
        val lane = points(floatArrayOf(300f, 400f, 500f, 680f, 700f))
        val clipped = LaneGeometry.aboveHood(lane, 720)
        assertNotNull(clipped)
        assertEquals(3, clipped!!.size)
        assertTrue(clipped.y.all { it < LaneGeometry.hoodTop(720) })
    }

    @Test
    fun aboveHoodKeepsEverythingWhenNoPointIsOnTheHood() {
        val lane = points(floatArrayOf(200f, 300f, 400f))
        val clipped = LaneGeometry.aboveHood(lane, 720)
        assertTrue(clipped === lane)
    }

    @Test
    fun aHoodOnlyFragmentIsNotALane() {
        val lane = points(floatArrayOf(700f, 710f, 715f, 700f))
        assertNull(LaneGeometry.aboveHood(lane, 720))
    }

    @Test
    fun zeroFractionDisablesTheClip() {
        val lane = points(floatArrayOf(300f, 700f, 715f))
        val clipped = LaneGeometry.aboveHood(lane, 720, 0f)
        assertTrue(clipped === lane)
    }

    @Test
    fun evalRowNeverLandsOnTheHood() {
        // Both boundaries decode down to the hood; the shared row must stop
        // above it instead of reading a hood reflection as the ground contact.
        val row = LaneGeometry.evalRow(720f, 720f, 720)
        assertTrue(row <= LaneGeometry.hoodTop(720) + 0.01f)
    }

    @Test
    fun evalRowStillUsesTheLowestSupportedRowOnTheRoad() {
        // A real lane that ends at 600 must still be evaluated at 600, not
        // dragged down to the hood edge.
        val row = LaneGeometry.evalRow(600f, 600f, 720)
        assertEquals(600f, row, 0.01f)
    }
}
