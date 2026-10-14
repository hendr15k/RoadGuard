package com.roadguard.app.data.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the UFLD drift gate.
 *
 * Two generations of bug are encoded here:
 *
 *  - the original flags were computed from `ufldCenterOffset()` alone, which
 *    always returns a number (a fixed ±150 px fudge for a missing side), so a
 *    **stub** polyline or a **mirrored** sample could raise a warning for a
 *    lane the overlay refused to draw;
 *  - the v1 gate required a complete, confident pair — and still fired on
 *    straight roads, because the raw per-frame offset jitters by several
 *    pixels and the window sat right on that noise. Measured on 11 clips:
 *    16-53 % of frames on clips where the car never leaves the lane.
 */
class LaneDriftGateTest {

    private val width = 1280
    private val sensitivity = 0.5f           // default -> offset fraction 0.04
    private val beyondGate = width * 0.04f   // 51.2 px

    @Test
    fun aCompleteConfidentPairLeavingTheLaneToTheLeftIsADrift() {
        assertTrue(
            LaneDriftGate.isDriftingLeft(
                centerOffset = -beyondGate - 10f,
                frameWidth = width,
                sensitivity = sensitivity,
                confidence = 0.8f,
                leftCurveValid = true,
                rightCurveValid = true
            )
        )
    }

    @Test
    fun aStubSideSuppressesTheWarningInBothDirections() {
        assertFalse(
            "stub on the left must not raise a left drift",
            LaneDriftGate.isDriftingLeft(
                centerOffset = -beyondGate - 10f,
                frameWidth = width,
                sensitivity = sensitivity,
                confidence = 0.9f,
                leftCurveValid = false,
                rightCurveValid = true
            )
        )
        assertFalse(
            "stub on the right must not raise a right drift",
            LaneDriftGate.isDriftingRight(
                centerOffset = beyondGate + 10f,
                frameWidth = width,
                sensitivity = sensitivity,
                confidence = 0.9f,
                leftCurveValid = true,
                rightCurveValid = false
            )
        )
    }

    @Test
    fun theMirrorFallbacksCappedConfidenceStaysUnderTheFloor() {
        val mirroredConfidence = UfldLaneDetector.MIRROR_CONFIDENCE_CAP
        assertTrue(
            "the mirror ceiling must be above zero, otherwise the cap is meaningless",
            mirroredConfidence > 0f
        )
        assertFalse(
            "a sample below the floor never warns, however large the offset",
            LaneDriftGate.isDriftingLeft(
                centerOffset = -width.toFloat(),
                frameWidth = width,
                sensitivity = sensitivity,
                confidence = LaneDriftGate.MIN_CONFIDENCE - 0.01f,
                leftCurveValid = true,
                rightCurveValid = true
            )
        )
    }

    @Test
    fun stayingInsideTheWindowIsNotADrift() {
        assertFalse(LaneDriftGate.isDriftingLeft(10f, width, sensitivity, 0.9f, true, true))
        assertFalse(LaneDriftGate.isDriftingRight(-10f, width, sensitivity, 0.9f, true, true))
    }

    @Test
    fun higherSensitivityNarrowsTheWindow() {
        // 0.0 -> 0.06, 0.5 -> 0.04, 1.0 -> 0.02 of the frame width. A higher
        // sensitivity value means a *smaller* tolerated offset.
        assertTrue(LaneDriftGate.offsetFraction(0.0f) > LaneDriftGate.offsetFraction(0.5f))
        assertTrue(LaneDriftGate.offsetFraction(0.5f) > LaneDriftGate.offsetFraction(1.0f))

        val offset = width * 0.05f   // 5 % off centre
        assertTrue(
            "5 % off centre is past the 4 % window at default sensitivity",
            LaneDriftGate.isDriftingLeft(-offset, width, 0.5f, 0.9f, true, true)
        )
        assertFalse(
            "the same 5 % is still inside the 6 % window at minimum sensitivity",
            LaneDriftGate.isDriftingLeft(-offset, width, 0.0f, 0.9f, true, true)
        )
        assertTrue(
            "and past the 2 % window at maximum sensitivity",
            LaneDriftGate.isDriftingLeft(-offset, width, 1.0f, 0.9f, true, true)
        )
    }

    @Test
    fun bothSidesCanNeverDriftAtOnce() {
        assertTrue(LaneDriftGate.isDriftingLeft(-beyondGate - 1f, width, sensitivity, 0.9f, true, true))
        assertFalse(LaneDriftGate.isDriftingRight(-beyondGate - 1f, width, sensitivity, 0.9f, true, true))
    }

    // --- v2: history, relative window, plausibility ---

    @Test
    fun aSingleSampleNeverWarnsHoweverLargeTheOffset() {
        // The per-frame offset jitters by more than the window; without a
        // temporal median a single bad frame is an alarm.
        assertFalse(
            LaneDriftGate.isDriftingLeft(
                centerOffset = -width.toFloat(),
                frameWidth = width,
                sensitivity = sensitivity,
                confidence = 0.9f,
                leftCurveValid = true,
                rightCurveValid = true,
                historySize = LaneDriftGate.MIN_HISTORY - 1
            )
        )
        assertTrue(
            LaneDriftGate.isDriftingLeft(
                centerOffset = -width.toFloat(),
                frameWidth = width,
                sensitivity = sensitivity,
                confidence = 0.9f,
                leftCurveValid = true,
                rightCurveValid = true,
                historySize = LaneDriftGate.MIN_HISTORY
            )
        )
    }

    @Test
    fun aNarrowLaneRaisesTheWindowAboveTheFrameFraction() {
        // 640 px frame, 1088 px measured lane: 4 % of the frame is only 11 %
        // of the lane, and a deviation that small is not a departure.
        val narrowFrame = 640
        val laneWidth = 1088f
        val frameRelative = narrowFrame * 0.04f          // 25.6 px
        val laneRelative = LaneDriftGate.RELATIVE_HALF_WIDTH_FRACTION * laneWidth / 2f  // 272 px
        assertTrue(
            "the lane-relative floor must dominate here",
            LaneDriftGate.thresholdPx(narrowFrame, 0.5f, laneWidth) > frameRelative
        )
        assertFalse(
            "30 px off centre is not a departure in a 1088 px lane",
            LaneDriftGate.isDriftingRight(30f, narrowFrame, 0.5f, 0.9f, true, true, laneWidth = laneWidth)
        )
        assertTrue(
            "but it would have been under the frame-relative window alone",
            30f > frameRelative
        )
        assertEquals(laneRelative, LaneDriftGate.thresholdPx(narrowFrame, 0.5f, laneWidth), 1f)
    }

    @Test
    fun withoutAMeasuredLaneTheFrameFractionStillApplies() {
        assertEquals(
            width * 0.04f,
            LaneDriftGate.thresholdPx(width, 0.5f, laneWidth = 0f),
            0.01f
        )
    }

    @Test
    fun aStepLargerThanHalfAnEgoLaneIsImplausible() {
        val laneWidth = 500f
        assertTrue(LaneDriftGate.isImplausibleStep(0f, 0.5f * laneWidth + 1f, laneWidth))
        assertFalse(LaneDriftGate.isImplausibleStep(0f, 0.5f * laneWidth - 1f, laneWidth))
        // Without a measured lane there is nothing to judge against.
        assertFalse(LaneDriftGate.isImplausibleStep(0f, 5000f, 0f))
    }

    // --- hardening: non-finite / out-of-range inputs -------------------------

    @Test
    fun anOutOfRangeSensitivityCannotInvertTheWindow() {
        // The settings clamp 0..1, but a hand-edited prefs value or a raw
        // caller could pass more, and 0.04 * (1.5 - sensitivity) went negative
        // — a negative threshold, i.e. a gate that silently never fires.
        assertTrue(
            "sensitivity above the UI max must clamp, not invert",
            LaneDriftGate.offsetFraction(2.0f) > 0f
        )
        assertTrue(
            "a wildly out-of-range sensitivity must not disable the gate",
            LaneDriftGate.isDriftingLeft(
                centerOffset = -beyondGate - 10f,
                frameWidth = width,
                sensitivity = 5f,
                confidence = 0.8f,
                leftCurveValid = true,
                rightCurveValid = true
            )
        )
        // A non-finite sensitivity must not poison the threshold either.
        assertTrue(LaneDriftGate.thresholdPx(width, Float.NaN).isFinite())
        assertTrue(LaneDriftGate.thresholdPx(width, Float.POSITIVE_INFINITY).isFinite())
    }

    @Test
    fun aNonFiniteOffsetOrWidthNeverWarns() {
        assertFalse(
            "NaN offset must not be a drift",
            LaneDriftGate.isDriftingLeft(
                centerOffset = Float.NaN,
                frameWidth = width,
                sensitivity = sensitivity,
                confidence = 0.8f,
                leftCurveValid = true,
                rightCurveValid = true
            )
        )
        assertFalse(
            "NaN offset must not be a drift to the right either",
            LaneDriftGate.isDriftingRight(
                centerOffset = Float.NaN,
                frameWidth = width,
                sensitivity = sensitivity,
                confidence = 0.9f,
                leftCurveValid = true,
                rightCurveValid = true
            )
        )
        assertFalse(
            "an infinite confidence is not a valid confidence",
            LaneDriftGate.isDriftingRight(
                centerOffset = beyondGate + 10f,
                frameWidth = width,
                sensitivity = sensitivity,
                confidence = Float.POSITIVE_INFINITY,
                leftCurveValid = true,
                rightCurveValid = true
            )
        )
        // A NaN lane width must not turn the threshold into NaN (every
        // comparison would then be false and the gate silently inert).
        assertTrue(
            LaneDriftGate.thresholdPx(width, sensitivity, Float.NaN).isFinite()
        )
        assertFalse(
            LaneDriftGate.isDriftingRight(
                centerOffset = beyondGate + 10f,
                frameWidth = width,
                sensitivity = sensitivity,
                confidence = 0.8f,
                leftCurveValid = true,
                rightCurveValid = true,
                laneWidth = Float.NaN
            )
        )
    }

    @Test
    fun anImplausibleStepCheckSurvivesNonFiniteOffsets() {
        assertFalse(
            "a NaN previous offset is not proof of an implausible step",
            LaneDriftGate.isImplausibleStep(Float.NaN, 0f, 500f)
        )
        assertFalse(
            "a NaN current offset is not proof of an implausible step",
            LaneDriftGate.isImplausibleStep(0f, Float.NaN, 500f)
        )
        assertFalse(
            "a NaN lane width cannot judge a step",
            LaneDriftGate.isImplausibleStep(0f, 5000f, Float.NaN)
        )
    }

    @Test
    fun aNonPositiveFrameWidthNeverWarns() {
        assertFalse(
            LaneDriftGate.isDriftingLeft(-beyondGate - 10f, 0, sensitivity, 0.9f, true, true)
        )
        assertFalse(
            LaneDriftGate.isDriftingRight(beyondGate + 10f, -1, sensitivity, 0.9f, true, true)
        )
    }
}
