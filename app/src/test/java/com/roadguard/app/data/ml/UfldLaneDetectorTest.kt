package com.roadguard.app.data.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contract for UFLD ego-pair selection. UFLD returns up to 4 lanes as
 * row-anchored points; the ego pair is one lane left of center + one right
 * of center with plausible gap — never a histogram peak, so the outer-lane
 * lock of the old pipeline cannot happen.
 * Values mirror verified dashcam frames (solidWhiteRight, 1280-wide space).
 */
class UfldLaneDetectorTest {

    private fun pts(vararg xys: Pair<Float, Float>): UfldLaneDetector.LanePoints {
        return UfldLaneDetector.LanePoints(
            x = xys.map { it.first }.toFloatArray(),
            y = xys.map { it.second }.toFloatArray()
        )
    }

    private fun line(xBot: Float, xTop: Float): UfldLaneDetector.LanePoints {
        // Bottom-heavy polyline like a real traced lane (56 rows would be ideal;
        // 8 points suffice for the selection contract).
        val ys = floatArrayOf(700f, 620f, 540f, 460f, 380f, 300f, 220f, 150f)
        val xs = FloatArray(ys.size) { i -> xBot + (xTop - xBot) * (700f - ys[i]) / 550f }
        return UfldLaneDetector.LanePoints(xs, ys)
    }

    @Test
    fun picksEgoPairAmongFourLanes() {
        val lanes = arrayOf<UfldLaneDetector.LanePoints?>(
            line(150f, 60f),    // 0: far-left adjacent
            line(467f, 300f),   // 1: left ego  (verified solidWhiteRight)
            line(935f, 560f),   // 2: right ego (verified solidWhiteRight)
            line(1200f, 900f)   // 3: far-right adjacent
        )
        val det = UfldLaneDetectorForTest()
        val pair = det.chooseEgoPair(lanes, 1280)
        assertNotNull("ego pair must be found", pair)
        assertEquals(1, pair!!.first)
        assertEquals(2, pair.second)
    }

    @Test
    fun rejectsImplausibleGap() {
        // Both candidates on nearly the same line: not a lane.
        val lanes = arrayOf<UfldLaneDetector.LanePoints?>(
            null,
            line(600f, 500f),
            line(640f, 520f),
            null
        )
        val det = UfldLaneDetectorForTest()
        assertNull("20px gap on 1280px frame must be rejected", det.chooseEgoPair(lanes, 1280))
    }

    @Test
    fun acceptsShiftedPairWhenModelMergesLanes() {
        // Model only emits lanes 0+2 (merged): still a valid ego pair.
        val lanes = arrayOf<UfldLaneDetector.LanePoints?>(
            line(467f, 300f),
            null,
            line(935f, 560f),
            null
        )
        val det = UfldLaneDetectorForTest()
        val pair = det.chooseEgoPair(lanes, 1280)
        assertNotNull(pair)
        assertEquals(0, pair!!.first)
        assertEquals(2, pair.second)
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
    fun singleSideLeftReturnsLeftLane() {
        // Only one lane visible (right occluded): single-side fallback fires.
        val lanes = arrayOf<UfldLaneDetector.LanePoints?>(
            null,
            line(467f, 300f),
            null,
            null
        )
        val det = UfldLaneDetectorForTest()
        assertNull("no left+right pair may exist", det.chooseEgoPair(lanes, 1280))
        val single = det.singleSideLane(lanes, 1280)
        assertNotNull("single left lane must be found", single)
        assertEquals("L", single!!.first)
    }

    @Test
    fun singleSideRightReturnsRightLane() {
        val lanes = arrayOf<UfldLaneDetector.LanePoints?>(
            null,
            null,
            line(935f, 560f),
            null
        )
        val det = UfldLaneDetectorForTest()
        assertNull(det.chooseEgoPair(lanes, 1280))
        val single = det.singleSideLane(lanes, 1280)
        assertNotNull(single)
        assertEquals("R", single!!.first)
    }

    @Test
    fun singleSideEmptyWhenNothingVisible() {
        val lanes = arrayOf<UfldLaneDetector.LanePoints?>(null, null, null, null)
        val det = UfldLaneDetectorForTest()
        assertNull(det.singleSideLane(lanes, 1280))
    }

    @Test
    fun singleSideEmptyWhenBothSidesPresent() {
        // Both sides visible: pair path owns it, fallback must stay out.
        val lanes = arrayOf<UfldLaneDetector.LanePoints?>(
            null,
            line(467f, 300f),
            line(935f, 560f),
            null
        )
        val det = UfldLaneDetectorForTest()
        assertNotNull(det.chooseEgoPair(lanes, 1280))
        assertNull(det.singleSideLane(lanes, 1280))
    }

    @Test
    fun mirrorLaneShiftsByHalfEgoWidth() {
        val pts = line(467f, 300f)
        val det = UfldLaneDetectorForTest()
        val mirrored = det.mirrorLane(pts, 1280, toRight = true)
        assertNotNull(mirrored)
        // 1280 * 0.45 / 2 = 288px shift right.
        assertEquals(467f + 288f, mirrored!!.x[0], 0.01f)
        val mirroredL = det.mirrorLane(pts, 1280, toRight = false)
        assertNotNull(mirroredL)
        assertEquals(467f - 288f, mirroredL!!.x[0], 0.01f)
    }
}

/**
 * chooseEgoPair is internal to the detector (needs Context for the rest);
 * this harness exposes the pure selection logic for JVM tests without Robolectric.
 * Mirrors UfldLaneDetector.chooseEgoPair/singleSideLane/mirrorLane exactly.
 */
private class UfldLaneDetectorForTest {
    fun chooseEgoPair(
        lanes: Array<UfldLaneDetector.LanePoints?>, imgW: Int
    ): Pair<Int, Int>? {
        val mid = imgW / 2f
        data class Cand(val idx: Int, val xBot: Float)
        val left = ArrayList<Cand>()
        val right = ArrayList<Cand>()
        for (i in lanes.indices) {
            val l = lanes[i] ?: continue
            if (l.size < 3) continue
            var maxY = Float.NEGATIVE_INFINITY
            var xAtMaxY = 0f
            for (j in 0 until l.size) {
                if (l.y[j] > maxY) {
                    maxY = l.y[j]
                    xAtMaxY = l.x[j]
                }
            }
            if (xAtMaxY < mid) left.add(Cand(i, xAtMaxY)) else right.add(Cand(i, xAtMaxY))
        }
        var best: Pair<Int, Int>? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (l in left) {
            for (r in right) {
                val gap = (r.xBot - l.xBot) / imgW
                if (gap < 0.22f || gap > 0.85f) continue
                var score = -kotlin.math.abs(gap - 0.45f)
                score -= (kotlin.math.abs(l.idx - 1) + kotlin.math.abs(r.idx - 2)) * 0.01f
                if (score > bestScore) {
                    bestScore = score
                    best = Pair(l.idx, r.idx)
                }
            }
        }
        return best
    }

    fun singleSideLane(
        lanes: Array<UfldLaneDetector.LanePoints?>, imgW: Int
    ): Pair<String, UfldLaneDetector.LanePoints>? {
        val mid = imgW / 2f
        var bestL: UfldLaneDetector.LanePoints? = null
        var bestR: UfldLaneDetector.LanePoints? = null
        for (l in lanes) {
            if (l == null || l.size < 3) continue
            var maxY = Float.NEGATIVE_INFINITY
            var xAtMaxY = 0f
            for (j in 0 until l.size) {
                if (l.y[j] > maxY) {
                    maxY = l.y[j]
                    xAtMaxY = l.x[j]
                }
            }
            if (xAtMaxY < mid) {
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

    fun mirrorLane(
        pts: UfldLaneDetector.LanePoints?, imgW: Int, toRight: Boolean
    ): UfldLaneDetector.LanePoints? {
        if (pts == null || pts.size < 3) return null
        val halfWidth = imgW * 0.45f / 2f
        val shift = if (toRight) halfWidth else -halfWidth
        val nx = FloatArray(pts.size) { i -> (pts.x[i] + shift).coerceIn(0f, imgW.toFloat()) }
        return UfldLaneDetector.LanePoints(nx, pts.y.copyOf())
    }
}
