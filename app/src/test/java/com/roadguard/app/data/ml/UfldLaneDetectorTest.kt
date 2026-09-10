package com.roadguard.app.data.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for UFLD ego-pair selection on the detector itself.
 *
 * The scoring/width logic moved to [EgoLaneGeometry] (covered by
 * [EgoLaneGeometryTest]); this file keeps the detector-level contract: which
 * lanes are candidates at all, and the single-side fallback.
 */
class UfldLaneDetectorTest {

    private fun line(xBot: Float, xTop: Float): UfldLaneDetector.LanePoints {
        // Bottom-heavy polyline like a real traced lane (56 rows would be ideal;
        // 8 points suffice for the selection contract).
        val ys = floatArrayOf(700f, 620f, 540f, 460f, 380f, 300f, 220f, 150f)
        val xs = FloatArray(ys.size) { i -> xBot + (xTop - xBot) * (700f - ys[i]) / 550f }
        return UfldLaneDetector.LanePoints(xs, ys)
    }

    @Test
    fun rowAnchorTableHas56Entries() {
        assertEquals(56, UfldLaneDetector.ROW_ANCHORS.size)
        assertEquals(64, UfldLaneDetector.ROW_ANCHORS[0])
        assertEquals(284, UfldLaneDetector.ROW_ANCHORS[55])
    }

    @Test
    fun tensorConstantsMatchValidatedModel() {
        assertEquals(800, UfldLaneDetector.INPUT_W)
        assertEquals(288, UfldLaneDetector.INPUT_H)
        assertEquals(100, UfldLaneDetector.GRIDING_NUM)
        assertEquals(56, UfldLaneDetector.NUM_ROWS)
        assertEquals(4, UfldLaneDetector.NUM_LANES)
    }

    @Test
    fun lanePointsExposeTheLowestDecodedRow() {
        val pts = line(467f, 300f)
        assertEquals(700f, pts.yBottom, 0.01f)
        assertEquals(150f, pts.yTop, 0.01f)
        // xBottom is the x ON the lowest row, not the smallest x.
        assertEquals(467f, pts.xBottom, 0.01f)
    }

    @Test
    fun singleSideLeftReturnsLeftLane() {
        // Only one lane visible (right occluded): single-side fallback fires.
        val lanes = arrayOf<UfldLaneDetector.LanePoints?>(null, line(467f, 300f), null, null)
        assertNotNull("single left lane must be found", UfldLaneDetectorForTest().singleSideLane(lanes, 1280))
        assertEquals("L", UfldLaneDetectorForTest().singleSideLane(lanes, 1280)!!.first)
    }

    @Test
    fun singleSideRightReturnsRightLane() {
        val lanes = arrayOf<UfldLaneDetector.LanePoints?>(null, null, line(935f, 560f), null)
        assertNotNull(UfldLaneDetectorForTest().singleSideLane(lanes, 1280))
        assertEquals("R", UfldLaneDetectorForTest().singleSideLane(lanes, 1280)!!.first)
    }

    @Test
    fun singleSideEmptyWhenNothingVisible() {
        val lanes = arrayOf<UfldLaneDetector.LanePoints?>(null, null, null, null)
        assertNull(UfldLaneDetectorForTest().singleSideLane(lanes, 1280))
    }

    @Test
    fun singleSideEmptyWhenBothSidesPresent() {
        // Both sides visible: the pair path owns it, the fallback must stay out.
        val lanes = arrayOf<UfldLaneDetector.LanePoints?>(null, line(467f, 300f), line(935f, 560f), null)
        assertNull(UfldLaneDetectorForTest().singleSideLane(lanes, 1280))
    }

    @Test
    fun singleSidePicksTheLongerLane() {
        val short = UfldLaneDetector.LanePoints(floatArrayOf(400f, 410f, 420f), floatArrayOf(500f, 560f, 620f))
        val long = line(430f, 380f)
        val lanes = arrayOf<UfldLaneDetector.LanePoints?>(short, long, null, null)
        assertEquals(long.size, UfldLaneDetectorForTest().singleSideLane(lanes, 1280)!!.second.size)
    }

    @Test
    fun mirrorCapStaysBelowTheAlertFloor() {
        // A guessed boundary may be drawn but must never raise a warning, so the
        // mirror ceiling has to stay under the gate's confidence floor.
        assertTrue(
            UfldLaneDetector.MIRROR_CONFIDENCE_CAP < LaneDriftGate.MIN_CONFIDENCE
        )
    }
}

/**
 * singleSideLane is internal to a class that needs a Context; this harness
 * mirrors it exactly so the pure selection is testable on the JVM.
 */
private class UfldLaneDetectorForTest {
    fun singleSideLane(
        lanes: Array<UfldLaneDetector.LanePoints?>, imgW: Int
    ): Pair<String, UfldLaneDetector.LanePoints>? {
        val mid = imgW / 2f
        var bestL: UfldLaneDetector.LanePoints? = null
        var bestR: UfldLaneDetector.LanePoints? = null
        for (l in lanes) {
            if (l == null || l.size < 3) continue
            if (l.xBottom < mid) {
                if (bestL == null || l.size > bestL.size) bestL = l
            } else {
                if (bestR == null || l.size > bestR.size) bestR = l
            }
        }
        return when {
            bestL != null && bestR != null -> null
            bestL != null -> Pair("L", bestL)
            bestR != null -> Pair("R", bestR)
            else -> null
        }
    }
}
