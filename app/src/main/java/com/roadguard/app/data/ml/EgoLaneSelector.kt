package com.roadguard.app.data.ml

/**
 * Pure-JVM ego-lane pair scoring shared with [LaneDetector]. Extracted so the
 * multi-candidate selection is unit-testable without Bitmap/Camera classes:
 * the scoring decides which left/right candidate pair counts as the ego lane.
 *
 * Contract (verified on 3 dashcam clips):
 * - width/center are judged at the NEAR field (bottom of frame = at the
 *   vehicle); curve-averaged widths let far-field perspective fake plausibility
 * - edge-glued traces (xBottom at the image border) are excluded when any
 *   non-glued candidate exists — they mark a trace that ran off the marking
 * - the width prior is an online median: one wrong pair must not shift it
 */
class EgoLaneSelector {

    data class Candidate(val xTop: Float, val xBottom: Float)

    private val widthBuffer = ArrayDeque<Float>()
    var expectedWidth: Float = 0f
        private set

    fun reset() {
        widthBuffer.clear()
        expectedWidth = 0f
    }

    fun scorePair(
        left: Candidate, right: Candidate,
        frameWidth: Int, midX: Float, expWidth: Float,
        prevLeftX: Float = 0f, prevRightX: Float = 0f
    ): Float? {
        val width = right.xBottom - left.xBottom
        if (width < frameWidth * 0.10f || width > frameWidth * 0.75f) return null
        val center = (left.xBottom + right.xBottom) / 2f
        val centerPenalty = kotlin.math.abs(center - midX) / (frameWidth * 0.5f)
        val safeExpWidth = if (expWidth > 1f) expWidth else frameWidth * 0.35f
        val widthPenalty = kotlin.math.abs(width - safeExpWidth) / safeExpWidth
        var score = -(centerPenalty * 1.5f + widthPenalty)
        if (prevLeftX > 0f) score -= kotlin.math.abs(left.xBottom - prevLeftX) / (frameWidth * 0.5f) * 0.5f
        if (prevRightX > 0f) score -= kotlin.math.abs(right.xBottom - prevRightX) / (frameWidth * 0.5f) * 0.5f
        return score
    }

    fun isGlued(c: Candidate, frameWidth: Int): Boolean =
        c.xBottom <= frameWidth * 0.02f || c.xBottom >= frameWidth * 0.98f

    fun selectPair(
        left: List<Candidate>, right: List<Candidate>,
        frameWidth: Int, prevLeftX: Float = 0f, prevRightX: Float = 0f
    ): Pair<Candidate, Candidate>? {
        if (left.isEmpty() || right.isEmpty()) return null
        val midX = frameWidth / 2f
        val expW = if (expectedWidth > 0f) expectedWidth else frameWidth * 0.35f
        val poolL = left.filter { !isGlued(it, frameWidth) }.ifEmpty { left }
        val poolR = right.filter { !isGlued(it, frameWidth) }.ifEmpty { right }
        var best: Pair<Candidate, Candidate>? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (l in poolL) {
            for (r in poolR) {
                val score = scorePair(l, r, frameWidth, midX, expW, prevLeftX, prevRightX) ?: continue
                if (score > bestScore) {
                    bestScore = score
                    best = Pair(l, r)
                }
            }
        }
        return best
    }

    /**
     * Observe an accepted pair width. Returns the updated prior. A single
     * outlier cannot shift the median; only consistent widths move the prior.
     */
    fun observeWidth(pairWidth: Float, frameWidth: Int): Float {
        if (pairWidth < frameWidth * 0.10f || pairWidth > frameWidth * 0.75f) return expectedWidth
        widthBuffer.addLast(pairWidth)
        if (widthBuffer.size > 15) widthBuffer.removeFirst()
        if (widthBuffer.size < 6) return expectedWidth
        val sorted = widthBuffer.sorted()
        val median = sorted[sorted.size / 2]
        if (kotlin.math.abs(pairWidth - median) < 0.25f * median) {
            expectedWidth = if (expectedWidth > 0f) expectedWidth * 0.7f + median * 0.3f else median
        }
        return expectedWidth
    }
}
