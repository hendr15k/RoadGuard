package com.roadguard.app.data.ml

import kotlin.math.abs
import kotlin.math.max

/**
 * Ego-lane geometry: which lanes form the driver's own lane, how wide it is,
 * and where the car sits inside it.
 *
 * Pure JVM so every decision is unit-testable without a Bitmap. Extracted from
 * [UfldLaneDetector] because three separate mistakes lived in the inline code:
 *
 *  - the width prior was the median over the bottom x of ALL decoded lanes,
 *    which mixes a genuine lane gap with the gap across a lane the model
 *    missed. Measured on solidWhiteRight: the prior collapsed from 387 px to
 *    the 0.15*W clamp. It now only uses INDEX-ADJACENT lanes (TuSimple lane
 *    ids are consecutive markings) and a single frame may not move it more
 *    than [WIDTH_MAX_STEP];
 *  - a left/right pair only had to be left and right OF THE IMAGE CENTRE,
 *    with no guarantee that the left one is really further left (index order).
 *    A model that labels the same marking twice could therefore pair a lane
 *    with itself;
 *  - the mirror fallback shifted the visible boundary by a hard-coded
 *    0.45 * frameWidth / 2 and clamped it into the frame, which is wrong for
 *    every camera with a different field of view. It now uses the measured
 *    ego width.
 */
class EgoLaneGeometry {

    companion object {
        /** Physical band for an ego-lane gap, as a fraction of the frame width. */
        const val MIN_GAP_FRAC = 0.20f
        const val MAX_GAP_FRAC = 0.90f

        /** Used until a width has been measured. */
        const val DEFAULT_WIDTH_FRAC = 0.45f

        /** EMA weight of a new width observation. */
        const val WIDTH_EMA = 0.25f

        /** A single frame may not move the prior more than this (relative). */
        const val WIDTH_MAX_STEP = 0.25f

        /** Sanity clamp of the measured width. */
        const val PRIOR_MIN_FRAC = 0.15f
        const val PRIOR_MAX_FRAC = 0.90f

        /** Score bonus for keeping the pair chosen in the previous frame. */
        const val PAIR_HYSTERESIS = 0.05f
    }

    /** One decoded lane, reduced to what pair selection needs. */
    data class Lane(val index: Int, val xBottom: Float, val points: Int)

    private var widthPrior = 0f

    /** Pair held from the previous frame, for hysteresis. */
    var heldPair: Pair<Int, Int>? = null
        private set

    val measuredWidth: Float get() = widthPrior

    fun reset() {
        widthPrior = 0f
        heldPair = null
    }

    fun widthOr(frameWidth: Int): Float =
        if (widthPrior > 1f) widthPrior else frameWidth * DEFAULT_WIDTH_FRAC

    /**
     * Learn the ego width from this frame's decoded lanes.
     *
     * TuSimple's four lane slots are consecutive markings, so the gap between
     * lane i and lane i+1 IS a lane width - but only while both were decoded.
     * A jump over a missing id is not a lane width and must not enter the
     * median (that is what collapsed the prior on solidWhiteRight).
     */
    fun observeWidth(lanes: List<Lane>, frameWidth: Int): Float {
        if (frameWidth <= 0) return widthPrior
        val sorted = lanes.sortedBy { it.index }
        val gaps = ArrayList<Float>(3)
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (b.index != a.index + 1) continue
            val gap = b.xBottom - a.xBottom
            if (gap > MIN_GAP_FRAC * frameWidth && gap < MAX_GAP_FRAC * frameWidth) gaps.add(gap)
        }
        if (gaps.isEmpty()) return widthPrior
        val median = median(gaps).coerceIn(PRIOR_MIN_FRAC * frameWidth, PRIOR_MAX_FRAC * frameWidth)
        widthPrior = when {
            widthPrior <= 1f -> median
            // Reject a single frame that disagrees wildly (a mis-paired frame
            // must not re-calibrate the prior).
            abs(median - widthPrior) > WIDTH_MAX_STEP * widthPrior -> widthPrior
            else -> widthPrior + WIDTH_EMA * (median - widthPrior)
        }
        return widthPrior
    }

    /**
     * The ego pair, or null when this frame offers none.
     *
     * Candidates are separated by the IMAGE centre (that is what defines left
     * and right for the driver), then a left candidate must additionally carry
     * the lower lane index - the model's slot order is across the road, so a
     * pair with left.index >= right.index is the same marking counted twice.
     */
    fun choosePair(lanes: List<Lane>, frameWidth: Int): Pair<Int, Int>? {
        if (frameWidth <= 0) return null
        val mid = frameWidth / 2f
        val prior = widthOr(frameWidth)
        var best: Pair<Int, Int>? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (l in lanes) {
            if (l.xBottom >= mid) continue
            for (r in lanes) {
                if (r.xBottom <= mid) continue
                if (l.index >= r.index) continue
                val gap = r.xBottom - l.xBottom
                if (gap < MIN_GAP_FRAC * frameWidth || gap > MAX_GAP_FRAC * frameWidth) continue
                val center = (l.xBottom + r.xBottom) / 2f
                var score = -abs(center - mid) / (0.5f * frameWidth)
                score -= abs(gap - prior) / prior
                score -= (abs(l.index - 1) + abs(r.index - 2)) * 0.15f
                if (heldPair == Pair(l.index, r.index)) score += PAIR_HYSTERESIS
                if (score > bestScore) {
                    bestScore = score
                    best = Pair(l.index, r.index)
                }
            }
        }
        heldPair = best
        return best
    }

    /** Half the ego width, for the single-side fallback. Never the raw frame fraction. */
    fun halfWidth(frameWidth: Int): Float = widthOr(frameWidth) / 2f

    /** Offset of the vehicle centre from the midpoint of the two boundaries. */
    fun centerOffset(leftX: Float, rightX: Float, frameWidth: Int): Float =
        frameWidth * 0.5f - (leftX + rightX) / 2f

    /**
     * Offset from a single visible boundary: the vehicle centre sits half an
     * ego width inside it. Replaces the fixed +/-150 px guess, which ignored
     * both the frame size and the measured lane.
     */
    fun singleSideOffset(sideX: Float, isLeft: Boolean, frameWidth: Int): Float {
        val half = halfWidth(frameWidth)
        val center = if (isLeft) sideX + half else sideX - half
        return frameWidth * 0.5f - center
    }

    private fun median(values: List<Float>): Float {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }
}
