package com.roadguard.app.data.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Contract for UFLD ego-pair selection and ego-width learning.
 *
 * The selection itself moved from [UfldLaneDetector] into [EgoLaneGeometry] so
 * it is testable without a Context; the values below mirror verified dashcam
 * frames (solidWhiteRight, 1280-wide space, ego pair 1+2).
 */
class EgoLaneGeometryTest {

    private fun lanes(vararg xBottoms: Float?): List<EgoLaneGeometry.Lane> =
        xBottoms.mapIndexedNotNull { i, x ->
            x?.let { EgoLaneGeometry.Lane(i, it, 40) }
        }

    @Test
    fun picksEgoPairAmongFourLanes() {
        val g = EgoLaneGeometry()
        // 0: far-left adjacent, 1: left ego, 2: right ego, 3: far-right adjacent
        val pair = g.choosePair(lanes(150f, 467f, 935f, 1200f), 1280)
        assertNotNull("ego pair must be found", pair)
        assertEquals(1, pair!!.first)
        assertEquals(2, pair.second)
    }

    @Test
    fun rejectsImplausibleGap() {
        val g = EgoLaneGeometry()
        // 20 px apart on a 1280 px frame: not a lane.
        assertNull(g.choosePair(lanes(600f, 620f), 1280))
    }

    @Test
    fun acceptsShiftedPairWhenModelMergesLanes() {
        // Model only emits lanes 0+2 (merged): still a valid ego pair.
        val g = EgoLaneGeometry()
        val pair = g.choosePair(lanes(467f, null, 935f, null), 1280)
        assertNotNull(pair)
        assertEquals(0, pair!!.first)
        assertEquals(2, pair.second)
    }

    @Test
    fun aPairWithInvertedIndicesIsRejected() {
        // Lane slot 0 sits right of the centre, slot 1 left of it: the model
        // assigned the slots inconsistently, and the image-centre rule alone
        // would happily pair them into an "ego lane" that runs backwards.
        val g = EgoLaneGeometry()
        assertNull(g.choosePair(lanes(900f, 600f), 1280))
    }

    @Test
    fun aPairOnOneSideOfTheCentreIsRejected() {
        val g = EgoLaneGeometry()
        // Both boundaries right of centre: no ego lane.
        assertNull(g.choosePair(lanes(800f, 1100f), 1280))
    }

    @Test
    fun widthPriorUsesOnlyIndexAdjacentLanes() {
        val g = EgoLaneGeometry()
        // Lanes 0 and 2 are present, 1 is missing: the 800 px gap spans a whole
        // missing lane and must NOT become the width prior. The default stands.
        g.observeWidth(lanes(200f, null, 1000f), 1280)
        assertEquals(1280 * EgoLaneGeometry.DEFAULT_WIDTH_FRAC, g.widthOr(1280), 1f)
    }

    @Test
    fun widthPriorLearnsFromAdjacentLanes() {
        val g = EgoLaneGeometry()
        repeat(10) { g.observeWidth(lanes(300f, 700f, 1100f), 1280) }
        // Two 400 px gaps -> the prior settles there instead of on the default.
        assertEquals(400f, g.widthOr(1280), 25f)
    }

    @Test
    fun aSingleOutlierFrameDoesNotMoveThePrior() {
        val g = EgoLaneGeometry()
        repeat(10) { g.observeWidth(lanes(300f, 700f, 1100f), 1280) }
        val learned = g.widthOr(1280)
        // A mis-paired frame reporting a 900 px "lane" must not re-calibrate.
        g.observeWidth(lanes(100f, 1000f), 1280)
        assertEquals(learned, g.widthOr(1280), 1f)
    }

    @Test
    fun mirrorUsesTheMeasuredWidthNotAFrameFraction() {
        val g = EgoLaneGeometry()
        repeat(10) { g.observeWidth(lanes(300f, 700f, 1100f), 1280) }
        // 400 px lane -> 200 px half width, not the old fixed 1280*0.45/2 = 288.
        assertEquals(200f, g.halfWidth(1280), 15f)
    }

    @Test
    fun mirrorFallsBackToTheFrameFractionWithoutMeasurement() {
        val g = EgoLaneGeometry()
        assertEquals(1280 * EgoLaneGeometry.DEFAULT_WIDTH_FRAC / 2f, g.halfWidth(1280), 0.01f)
    }

    @Test
    fun singleSideOffsetUsesHalfAnEgoWidth() {
        val g = EgoLaneGeometry()
        repeat(10) { g.observeWidth(lanes(300f, 700f, 1100f), 1280) }
        // Left boundary at 300, measured lane 400 -> vehicle centre 500 -> the
        // car is 140 px right of the frame centre. The old code used a fixed
        // 150 px fudge that ignored both the frame size and the lane.
        assertEquals(140f, g.singleSideOffset(300f, isLeft = true, frameWidth = 1280), 15f)
        // Mirrored: a right boundary at 980 puts the centre at 780 -> -140 px.
        assertEquals(-140f, g.singleSideOffset(980f, isLeft = false, frameWidth = 1280), 15f)
    }

    @Test
    fun centerOffsetIsZeroWhenCentred() {
        val g = EgoLaneGeometry()
        assertEquals(0f, g.centerOffset(440f, 840f, 1280), 0.001f)
    }

    @Test
    fun resetForgetsEverything() {
        val g = EgoLaneGeometry()
        repeat(10) { g.observeWidth(lanes(300f, 700f, 1100f), 1280) }
        assertTrue(g.measuredWidth > 1f)
        g.reset()
        assertEquals(0f, g.measuredWidth, 0.001f)
    }

    @Test
    fun hysteresisKeepsTheHeldPairWhenScoresAreClose() {
        val g = EgoLaneGeometry()
        val first = g.choosePair(lanes(300f, 700f, 1100f), 1280)
        assertNotNull(first)
        // Slightly shifted frame: the same pair must survive the jitter.
        val second = g.choosePair(lanes(310f, 705f, 1098f), 1280)
        assertEquals(first, second)
    }

    // --- LaneGeometry: the shared curve math ---

    @Test
    fun quadraticFitRecoversAKnownCurve() {
        // x = 0.0002*y^2 + 0.1*y + 100
        val ys = FloatArray(20) { 300f + it * 15f }
        val xs = FloatArray(20) { i -> 0.0002f * ys[i] * ys[i] + 0.1f * ys[i] + 100f }
        val q = LaneGeometry.fitQuadratic(xs, ys)
        assertNotNull(q)
        assertEquals(100f, q!!.x(0f), 2f)
        assertTrue(abs(q.x(600f) - (0.0002f * 600f * 600f + 0.1f * 600f + 100f)) < 5f)
    }

    @Test
    fun quadraticFitIgnoresASingleOutlierRow() {
        val ys = FloatArray(20) { 300f + it * 15f }
        val xs = FloatArray(20) { i -> 0.1f * ys[i] + 100f }
        xs[7] += 400f                       // one badly decoded row
        val q = LaneGeometry.fitQuadratic(xs, ys)
        assertNotNull(q)
        assertEquals(100f, q!!.x(0f), 5f)
    }

    @Test
    fun spanGateRejectsAStub() {
        val stub = UfldLaneDetector.LanePoints(
            floatArrayOf(100f, 102f, 104f),
            floatArrayOf(400f, 410f, 420f)
        )
        // 20 px of a 720 px frame: a curb fragment, not a lane.
        assertFalse(LaneGeometry.passesSpanGate(stub, 720))
        val full = UfldLaneDetector.LanePoints(
            floatArrayOf(100f, 150f, 200f),
            floatArrayOf(100f, 450f, 700f)
        )
        assertTrue(LaneGeometry.passesSpanGate(full, 720))
    }

    @Test
    fun coverageFallsWithExtrapolationBelowTheLastDecodedRow() {
        // A curve that stops at 70 % of the frame but is evaluated at 98 %:
        // half the span is invented and the confidence must say so.
        val stops70 = UfldLaneDetector.LanePoints(
            floatArrayOf(200f, 300f, 400f),
            floatArrayOf(100f, 350f, 504f)      // 0.7 * 720
        )
        val reachesBottom = UfldLaneDetector.LanePoints(
            floatArrayOf(200f, 300f, 400f),
            floatArrayOf(100f, 400f, 700f)
        )
        val evalRow = 0.98f * 720f
        val cLate = LaneGeometry.coverage(stops70, evalRow, 720)
        val cEarly = LaneGeometry.coverage(reachesBottom, evalRow, 720)
        assertTrue("extrapolated bottom must score lower", cLate < cEarly)
        assertTrue("and must not collapse to zero", cLate > 0.1f)
    }

    @Test
    fun evalRowIsTheLowestSupportedRow() {
        // Both curves decoded down to 500: evaluate there, not at 98 % of 720.
        assertEquals(500f, LaneGeometry.evalRow(500f, 480f, 720), 0.01f)
        // A curve that reaches the frame bottom: evaluate at 98 %.
        assertEquals(0.98f * 720f, LaneGeometry.evalRow(719f, 710f, 720), 0.01f)
        // Never above half the frame.
        assertEquals(360f, LaneGeometry.evalRow(10f, 20f, 720), 0.01f)
    }
}
