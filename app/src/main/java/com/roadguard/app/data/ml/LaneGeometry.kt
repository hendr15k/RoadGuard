package com.roadguard.app.data.ml

import com.roadguard.app.domain.model.LaneCurve
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Geometry + plausibility of one decoded lane polyline, shared by
 * [UfldLaneDetector] and the two analyzers.
 *
 * The pipeline used to reason about a lane as "the point with the largest y of
 * the fitted curve" and to derive the ego-lane offset from whatever rows the
 * two sides happened to end at. Both are wrong on real footage:
 *
 *  - the largest y of a polyline is the lowest row the MODEL decoded, which on
 *    a curve sits well above the ground contact the camera actually sees at
 *    the bottom of the frame;
 *  - evaluating two boundaries at two different rows mixes a near-field x with
 *    a far-field x, which shows up as a lateral bias that grows with the curve
 *    (measured ~10 px on project_video, ~20 px on harder_challenge).
 *
 * Everything here is pure JVM: no Android types, fully unit testable.
 */
object LaneGeometry {

    /** Minimum y-span for a polyline to count as a lane rather than a stub. */
    const val SPAN_FRACTION = 0.35f

    /** Y-span at which the far-field coverage credit is full. */
    const val FULL_SPAN_FRACTION = 0.55f

    /** Extrapolating up to this share of the span below the last decoded row is free. */
    const val FREE_EXTRAPOLATION = 0.10f

    /** Coverage multiplier at the maximum tolerated extrapolation. */
    const val EXTRAPOLATION_FLOOR = 0.5f

    /** Row the ego-lane offset is evaluated at (bottom of the frame). */
    const val EVAL_ROW_FRACTION = 0.98f

    /**
     * Bottom of the frame covered by the car's own hood/bonnet. The camera
     * sits behind the windshield, so the lowest band of the image is the
     * hood, not the road: lane points down there are reflections or the
     * hood edge, and the offset must never be measured on them. The value
     * is user-configurable (Settings → Hood zone) and defaults to 8 %.
     */
    const val HOOD_EXCLUSION_FRACTION = 0.08f

    /** First row that is still road: everything at or below is hood. */
    fun hoodTop(frameHeight: Int, fraction: Float = HOOD_EXCLUSION_FRACTION): Float =
        frameHeight * (1f - fraction.coerceIn(0f, 0.5f))

    /**
     * Drop decoded points sitting on the hood. Returns null when fewer than
     * three road points survive — a hood-only fragment is not a lane.
     */
    fun aboveHood(
        pts: UfldLaneDetector.LanePoints?,
        frameHeight: Int,
        fraction: Float = HOOD_EXCLUSION_FRACTION
    ): UfldLaneDetector.LanePoints? {
        if (pts == null || frameHeight <= 0) return pts
        if (fraction <= 0f) return pts
        val top = hoodTop(frameHeight, fraction)
        var keep = 0
        for (y in pts.y) if (y < top) keep++
        if (keep == pts.size) return pts
        if (keep < 3) return null
        val nx = FloatArray(keep)
        val ny = FloatArray(keep)
        var k = 0
        for (i in 0 until pts.size) {
            if (pts.y[i] < top) {
                nx[k] = pts.x[i]
                ny[k] = pts.y[i]
                k++
            }
        }
        return UfldLaneDetector.LanePoints(nx, ny, pts.index)
    }

    /** Fit residual above this (px) is an outlier row, not lane detail. */
    const val FIT_OUTLIER_PX = 25f

    /** Signed quadratic x(y) = a*y^2 + b*y + c. */
    data class Quadratic(val a: Float, val b: Float, val c: Float) {
        fun x(y: Float): Float = a * y * y + b * y + c
    }

    /**
     * Least-squares quadratic x(y) with two outlier-rejection passes.
     *
     * A plain fit is dragged by a single bad row (a shadow or a crossing
     * marking), and UFLD emits one sample per row, so one mis-decoded row has
     * the same weight as fifteen good ones. Returns null when fewer than three
     * points survive or the normal equations are singular.
     */
    fun fitQuadratic(xs: FloatArray, ys: FloatArray): Quadratic? {
        if (xs.size != ys.size || xs.size < 3) return null
        var keep = BooleanArray(xs.size) { true }
        var result: Quadratic? = null
        repeat(3) {
            val fit = leastSquares(xs, ys, keep) ?: return result
            result = fit
            var changed = false
            var survivors = 0
            val next = BooleanArray(xs.size)
            for (i in xs.indices) {
                if (!keep[i]) continue
                if (abs(fit.x(ys[i]) - xs[i]) <= FIT_OUTLIER_PX) {
                    next[i] = true
                    survivors++
                } else {
                    changed = true
                }
            }
            if (!changed || survivors < 3) return result
            keep = next
        }
        return result
    }

    private fun leastSquares(xs: FloatArray, ys: FloatArray, keep: BooleanArray): Quadratic? {
        var n = 0.0
        var sY = 0.0; var sY2 = 0.0; var sY3 = 0.0; var sY4 = 0.0
        var sX = 0.0; var sXY = 0.0; var sXY2 = 0.0
        for (i in xs.indices) {
            if (!keep[i]) continue
            val x = xs[i].toDouble()
            val y = ys[i].toDouble()
            val y2 = y * y
            n += 1.0
            sY += y; sY2 += y2; sY3 += y2 * y; sY4 += y2 * y2
            sX += x; sXY += x * y; sXY2 += x * y2
        }
        if (n < 3.0) return null
        // Normal equations for [a, b, c], ordered by descending power.
        val m = arrayOf(
            doubleArrayOf(sY4, sY3, sY2),
            doubleArrayOf(sY3, sY2, sY),
            doubleArrayOf(sY2, sY, n)
        )
        val rhs = doubleArrayOf(sXY2, sXY, sX)
        val sol = solve3(m, rhs) ?: return null
        val a = sol[0].toFloat()
        if (!a.isFinite() || abs(a) > 0.5f) return null
        return Quadratic(a, sol[1].toFloat(), sol[2].toFloat())
    }

    private fun solve3(m: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val aug = Array(3) { i -> DoubleArray(4) { j -> if (j < 3) m[i][j] else b[i] } }
        for (col in 0 until 3) {
            var pivot = col
            for (row in col + 1 until 3) if (abs(aug[row][col]) > abs(aug[pivot][col])) pivot = row
            val tmp = aug[col]; aug[col] = aug[pivot]; aug[pivot] = tmp
            if (abs(aug[col][col]) < 1e-9) return null
            for (row in col + 1 until 3) {
                val f = aug[row][col] / aug[col][col]
                for (j in col until 4) aug[row][j] -= f * aug[col][j]
            }
        }
        val x = DoubleArray(3)
        for (i in 2 downTo 0) {
            var v = aug[i][3]
            for (j in i + 1 until 3) v -= aug[i][j] * x[j]
            x[i] = v / aug[i][i]
        }
        return if (x.all { it.isFinite() }) x else null
    }

    /** The lowest row the model actually decoded, and the x on it. */
    fun bottom(pts: UfldLaneDetector.LanePoints): Pair<Float, Float> {
        var yMax = Float.NEGATIVE_INFINITY
        var xAt = 0f
        for (i in 0 until pts.size) {
            if (pts.y[i] > yMax) {
                yMax = pts.y[i]
                xAt = pts.x[i]
            }
        }
        return Pair(xAt, yMax)
    }

    fun top(pts: UfldLaneDetector.LanePoints): Pair<Float, Float> {
        var yMin = Float.POSITIVE_INFINITY
        var xAt = 0f
        for (i in 0 until pts.size) {
            if (pts.y[i] < yMin) {
                yMin = pts.y[i]
                xAt = pts.x[i]
            }
        }
        return Pair(xAt, yMin)
    }

    /** A real boundary spans most of the frame; a curb fragment does not. */
    fun passesSpanGate(
        pts: UfldLaneDetector.LanePoints?,
        frameHeight: Int,
        hoodFraction: Float
    ): Boolean {
        if (pts == null || pts.size < 3 || frameHeight <= 0) return false
        val (_, topY) = top(pts)
        val (_, botY) = bottom(pts)
        // The hood clip shortens every polyline BEFORE this gate runs, so the
        // requirement must shrink with the visible road: a full-frame span is
        // unreachable once the bottom band is excluded, and real boundaries
        // would be rejected as stubs.
        //
        // hoodFraction has NO default on purpose. It did for one revision, and
        // that made the defect this gate just lost silently reachable again:
        // a caller that omits it would measure hood-clipped points against the
        // full frame. Pass 0f to get the historic full-frame behaviour
        // explicitly.
        val roadHeight = hoodTop(frameHeight, hoodFraction)
        return (botY - topY) >= SPAN_FRACTION * roadHeight
    }

    /**
     * Evidence that this side is a lane, in 0..1: far-field coverage (how much
     * of the frame the marking occupies) times a penalty for how far below the
     * last decoded row the evaluation row lies, times marking support when the
     * caller has a paint measurement.
     *
     * The extrapolation term is what used to be missing entirely: a polyline
     * that stops at 70% of the frame still produced an offset at 98%, and the
     * invented bottom was treated as a measurement. Confidence that rewards
     * point count cannot see that; this can.
     */
    fun coverage(
        pts: UfldLaneDetector.LanePoints?,
        evalRow: Float,
        frameHeight: Int,
        markingSupport: Float = 1f
    ): Float {
        if (pts == null || pts.size < 3 || frameHeight <= 0) return 0f
        val (_, topY) = top(pts)
        val (_, botY) = bottom(pts)
        val span = botY - topY
        if (span <= 1f) return 0f
        val cov = min(1f, span / (FULL_SPAN_FRACTION * frameHeight))
        val extra = max(0f, (evalRow - botY) / span)
        val penalty = (1f - (extra - FREE_EXTRAPOLATION) / 0.6f)
            .coerceIn(EXTRAPOLATION_FLOOR, 1f)
        return cov * penalty * (0.6f + 0.4f * markingSupport.coerceIn(0f, 1f))
    }

    /**
     * Row both boundaries are evaluated at: as low as the data supports, never
     * on the hood. [fraction] is the configurable hood band.
     */
    fun evalRow(
        leftBotY: Float,
        rightBotY: Float,
        frameHeight: Int,
        fraction: Float = HOOD_EXCLUSION_FRACTION
    ): Float {
        val lo = min(
            min(EVAL_ROW_FRACTION * frameHeight, hoodTop(frameHeight, fraction)),
            max(leftBotY, rightBotY)
        )
        return max(lo, 0.5f * frameHeight)
    }

    /**
     * Fit a domain [LaneCurve] through a decoded UFLD polyline.
     *
     * Uses the robust [fitQuadratic] (outlier-rejection passes) rather than the
     * old per-analyzer Cramer fit that had no rejection at all. The offset and
     * confidence path already used the robust fit, so the overlay and the
     * warning used to disagree about where the lane was whenever one row was
     * mis-decoded by a shadow or a crossing marking. The span gate rejects
     * short stubs (curb fragments) whose extrapolation would float in the sky.
     */
    fun curveOf(
        pts: UfldLaneDetector.LanePoints?,
        frameHeight: Int,
        hoodFraction: Float
    ): LaneCurve {
        if (pts == null || pts.size < 3) return LaneCurve()
        if (!passesSpanGate(pts, frameHeight, hoodFraction)) return LaneCurve()
        val q = fitQuadratic(pts.x, pts.y) ?: return LaneCurve()
        val (_, topY) = top(pts)
        val (_, botY) = bottom(pts)
        return LaneCurve(
            a = q.a,
            b = q.b,
            c = q.c,
            yStart = topY,
            yEnd = botY,
            valid = true
        )
    }
}
