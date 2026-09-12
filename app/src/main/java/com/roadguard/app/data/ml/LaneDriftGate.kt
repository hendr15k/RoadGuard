package com.roadguard.app.data.ml

import kotlin.math.abs

/**
 * Pure-JVM decision whether a lane sample may raise a lane-departure warning,
 * shared by [MlDetectionAnalyzer] and [VideoMlAnalyzer].
 *
 * ## History
 *
 * Both analyzers used to derive the drift flags straight from
 * `ufldCenterOffset()` whenever the UFLD branch ran. That helper measures a
 * point of each returned polyline and, for a missing side, invents the offset
 * from a fixed ±150 px fudge — so it produced a number either way:
 *
 *  - **stub curves** — UFLD occasionally returns a short fragment (a curb a
 *    few rows tall). The span gate rejects it, but the drift flags were still
 *    computed from the stub's bottom point and fired a warning for a lane the
 *    overlay refused to draw.
 *  - **mirrored samples** — the single-lane fallback mirrors the one visible
 *    boundary. Any offset derived from it is fabricated; the detector caps its
 *    confidence below the floor for exactly that reason, and the gate below
 *    enforces the same rule rather than trusting the caller to remember.
 *
 * ## v2
 *
 * A warning now additionally requires:
 *
 *  - **enough history.** The per-frame offset jitters by several pixels even
 *    on a straight road (measured std 13-27 px across 11 clips), so a single
 *    sample is not a departure. The caller feeds the temporal median and this
 *    gate refuses until [MIN_HISTORY] samples exist.
 *  - **physical plausibility.** An offset step larger than half an ego lane
 *    between two samples is not a lateral movement, it is a bad detection.
 *  - **a lane-relative window.** 4 % of the frame width means a different
 *    thing for every field of view: on a 640 px wide, 1088 px lane it is 11 %
 *    of the lane, on a 1280 px frame with a 900 px lane it is 6 %. The window
 *    is therefore the larger of the user-facing fraction and a fixed share of
 *    the ego lane half-width, so a wide-angle camera no longer produces
 *    warnings for a deviation that is barely a tenth of the lane.
 */
object LaneDriftGate {

    /** Same floor the alert gate uses; below it a sample is not trustworthy. */
    const val MIN_CONFIDENCE = 0.4f

    /** Fraction of the frame width the centre must leave before this is a drift. */
    const val BASE_OFFSET_FRACTION = 0.04f

    /** Additional lane-relative floor: this share of the ego half-width. */
    const val RELATIVE_HALF_WIDTH_FRACTION = 0.5f

    /** Samples required before an offset may warn (~0.6 s at the 5 Hz rate). */
    const val MIN_HISTORY = 3

    /**
     * Offset step between two samples that cannot be a real lane movement,
     * as a fraction of the ego lane width.
     */
    const val MAX_STEP_FRACTION = 0.5f

    /** Higher sensitivity narrows the window (1.5 - sensitivity, so 0.5 → 1.0). */
    fun offsetFraction(sensitivity: Float): Float =
        BASE_OFFSET_FRACTION * (1.5f - sensitivity)

    /**
     * The offset a departure must exceed, in pixels.
     *
     * @param laneWidth measured ego lane width (px); <= 0 falls back to the
     *   frame-relative window alone.
     */
    fun thresholdPx(
        frameWidth: Int,
        sensitivity: Float,
        laneWidth: Float = 0f
    ): Float {
        val frameRelative = frameWidth * offsetFraction(sensitivity)
        if (laneWidth <= 0f) return frameRelative
        val laneRelative = RELATIVE_HALF_WIDTH_FRACTION * laneWidth / 2f
        return maxOf(frameRelative, laneRelative)
    }

    /** True when the offset jumped further than a vehicle can move between samples. */
    fun isImplausibleStep(
        previousOffset: Float,
        currentOffset: Float,
        laneWidth: Float
    ): Boolean {
        if (laneWidth <= 0f) return false
        return abs(currentOffset - previousOffset) > MAX_STEP_FRACTION * laneWidth
    }

    /**
     * @param leftCurveValid  the left polyline passed the span gate and was fitted
     * @param rightCurveValid same for the right side
     * @param historySize     number of offset samples collected so far
     * @param laneWidth       measured ego lane width, for the relative window
     */
    fun isDriftingLeft(
        centerOffset: Float,
        frameWidth: Int,
        sensitivity: Float,
        confidence: Float,
        leftCurveValid: Boolean,
        rightCurveValid: Boolean,
        historySize: Int = MIN_HISTORY,
        laneWidth: Float = 0f
    ): Boolean = usablePair(confidence, leftCurveValid, rightCurveValid, historySize) &&
        centerOffset < -thresholdPx(frameWidth, sensitivity, laneWidth)

    fun isDriftingRight(
        centerOffset: Float,
        frameWidth: Int,
        sensitivity: Float,
        confidence: Float,
        leftCurveValid: Boolean,
        rightCurveValid: Boolean,
        historySize: Int = MIN_HISTORY,
        laneWidth: Float = 0f
    ): Boolean = usablePair(confidence, leftCurveValid, rightCurveValid, historySize) &&
        centerOffset > thresholdPx(frameWidth, sensitivity, laneWidth)

    private fun usablePair(
        confidence: Float,
        leftCurveValid: Boolean,
        rightCurveValid: Boolean,
        historySize: Int
    ): Boolean = confidence > MIN_CONFIDENCE && leftCurveValid && rightCurveValid &&
        historySize >= MIN_HISTORY
}

/**
 * Temporal median window for a stream of centre offsets.
 *
 * Shared by both analyzers for the classic-CV fallback, which previously used
 * [LaneDetector]'s per-frame threshold and could warn on a single noisy frame —
 * the exact failure the [LaneDriftGate] history floor exists to prevent. Keeping
 * the window here (rather than inline in each analyzer) gives the camera and
 * video paths identical behaviour.
 */
class LaneOffsetWindow(private val windowSize: Int = DEFAULT_WINDOW) {

    companion object {
        /** ~1.4 s at the 5 Hz analyzer rate, matching the UFLD offset median. */
        const val DEFAULT_WINDOW = 7
    }

    private val history = ArrayDeque<Float>()

    /** Number of samples collected so far, for the gate's history floor. */
    val size: Int get() = history.size

    fun reset() {
        history.clear()
    }

    /** Adds a raw offset and returns the current temporal median (or the sample). */
    fun add(rawOffset: Float): Float {
        if (!rawOffset.isFinite()) return median()
        history.addLast(rawOffset)
        while (history.size > windowSize) history.removeFirst()
        return median()
    }

    private fun median(): Float {
        if (history.isEmpty()) return 0f
        val sorted = history.sorted()
        return sorted[sorted.size / 2]
    }
}
