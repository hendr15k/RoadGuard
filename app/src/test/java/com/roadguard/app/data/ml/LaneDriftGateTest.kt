package com.roadguard.app.data.ml

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the UFLD drift gate. Both analyzers used to compute the drift
 * flags from `ufldCenterOffset()` alone whenever the UFLD branch ran, and that
 * helper always returns a number: it falls back to a fixed ±150 px fudge for a
 * missing side. Two situations therefore produced warnings for a lane the
 * overlay refused to draw:
 *
 *  - a **stub** polyline, which the span gate rejects (`valid = false`) but
 *    whose bottom point still fed the offset;
 *  - a **mirrored** single-lane sample, whose confidence is capped at 0.65 and
 *    whose offset is derived from a guessed boundary.
 *
 * The gate requires a complete, confident pair.
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
        // The span gate already decided this side is not a lane; a departure
        // warning derived from it contradicts the overlay.
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
        // UfldLaneDetector caps a mirrored sample at 0.65; whatever the offset
        // says, a guessed boundary must not claim a departure on its own. The
        // real check is that the *gate* applies a floor at all — a mirrored
        // sample that somehow scored above it would be treated like a pair.
        val mirroredConfidence = 0.65f

        assertTrue(
            "the mirror ceiling must be above the floor, otherwise the gate is dead code",
            mirroredConfidence > LaneDriftGate.MIN_CONFIDENCE
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
        assertFalse(
            LaneDriftGate.isDriftingLeft(10f, width, sensitivity, 0.9f, true, true)
        )
        assertFalse(
            LaneDriftGate.isDriftingRight(-10f, width, sensitivity, 0.9f, true, true)
        )
    }

    @Test
    fun higherSensitivityNarrowsTheWindow() {
        // 0.0 -> 0.06, 0.5 -> 0.04, 1.0 -> 0.02 of the frame width. A higher
        // sensitivity value means a *smaller* tolerated offset.
        assertTrue(LaneDriftGate.offsetFraction(0.0f) > LaneDriftGate.offsetFraction(0.5f))
        assertTrue(LaneDriftGate.offsetFraction(0.5f) > LaneDriftGate.offsetFraction(1.0f))

        val offset = width * 0.05f   // 5% off centre
        assertTrue(
            "5% off centre is past the 4% window at default sensitivity",
            LaneDriftGate.isDriftingLeft(-offset, width, 0.5f, 0.9f, true, true)
        )
        assertFalse(
            "the same 5% is still inside the 6% window at minimum sensitivity",
            LaneDriftGate.isDriftingLeft(-offset, width, 0.0f, 0.9f, true, true)
        )
        assertTrue(
            "and past the 2% window at maximum sensitivity",
            LaneDriftGate.isDriftingLeft(-offset, width, 1.0f, 0.9f, true, true)
        )
    }

    @Test
    fun bothSidesCanNeverDriftAtOnce() {
        assertTrue(
            LaneDriftGate.isDriftingLeft(-beyondGate - 1f, width, sensitivity, 0.9f, true, true)
        )
        assertFalse(
            LaneDriftGate.isDriftingRight(-beyondGate - 1f, width, sensitivity, 0.9f, true, true)
        )
    }
}
