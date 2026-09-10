package com.roadguard.app.data.ml

/**
 * Pure-JVM decision whether a UFLD ego-pair sample may raise a lane-departure
 * warning, shared by [MlDetectionAnalyzer] and [VideoMlAnalyzer].
 *
 * Both analyzers used to derive the drift flags straight from
 * `ufldCenterOffset()` whenever the UFLD branch ran. That helper measures the
 * bottom-most point of each returned polyline and, for a missing side, invents
 * the offset from a fixed ±150 px fudge — so it produced a number either way:
 *
 *  - **stub curves** — UFLD occasionally returns a short stub (a curb fragment
 *    a few rows tall). `ufldPointsToCurve` rejects it via the span gate and
 *    reports `valid = false`, but the drift flags were still computed from the
 *    stub's sky-high bottom point and fired a departure warning for a lane the
 *    overlay refused to draw.
 *  - **mirrored samples** — the single-lane fallback mirrors the one visible
 *    boundary at the expected ego width. The mirrored side is a guess, so any
 *    offset derived from it is fabricated; the detector already caps its
 *    confidence below [com.roadguard.app.domain.model.AlertPolicy.MIN_LANE_CONFIDENCE]
 *    for exactly that reason (see `UfldLaneDetector.detect`). The gate below
 *    enforces the same rule for the overlay/drift flags rather than relying on
 *    the caller to remember.
 *
 * A departure warning therefore requires a *complete, confident* pair: both
 * curves fitted (not stubs) and the sample over the confidence floor.
 */
object LaneDriftGate {

    /** Same floor the alert gate uses; below it a sample is not trustworthy. */
    const val MIN_CONFIDENCE = 0.4f

    /** Fraction of the frame width the centre must leave before this is a drift. */
    const val BASE_OFFSET_FRACTION = 0.04f

    /** Higher sensitivity narrows the window (1.5 - sensitivity, so 0.5 → 1.0). */
    fun offsetFraction(sensitivity: Float): Float =
        BASE_OFFSET_FRACTION * (1.5f - sensitivity)

    /**
     * @param leftCurveValid  the left UFLD polyline passed the span gate and was fitted
     * @param rightCurveValid same for the right side
     */
    fun isDriftingLeft(
        centerOffset: Float,
        frameWidth: Int,
        sensitivity: Float,
        confidence: Float,
        leftCurveValid: Boolean,
        rightCurveValid: Boolean
    ): Boolean = usablePair(confidence, leftCurveValid, rightCurveValid) &&
        centerOffset < -frameWidth * offsetFraction(sensitivity)

    fun isDriftingRight(
        centerOffset: Float,
        frameWidth: Int,
        sensitivity: Float,
        confidence: Float,
        leftCurveValid: Boolean,
        rightCurveValid: Boolean
    ): Boolean = usablePair(confidence, leftCurveValid, rightCurveValid) &&
        centerOffset > frameWidth * offsetFraction(sensitivity)

    private fun usablePair(
        confidence: Float,
        leftCurveValid: Boolean,
        rightCurveValid: Boolean
    ): Boolean = confidence > MIN_CONFIDENCE && leftCurveValid && rightCurveValid
}
