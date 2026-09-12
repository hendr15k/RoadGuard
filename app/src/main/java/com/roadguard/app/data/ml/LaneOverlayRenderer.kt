package com.roadguard.app.data.ml

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lane-marking measurement: how well a detected curve sits on the actual
 * paint, and the signed correction that would put it there.
 *
 * Pure JVM (no Bitmap/Canvas) so the measurement is unit-testable, and
 * allocation-free in the hot path: the per-pixel "is this paint" decision is
 * baked once per frame into a reusable boolean mask instead of running the
 * HSV test per curve sample. That matters — the analyzer runs at ~5 Hz on
 * phone-class hardware and the surrounding code goes out of its way to avoid
 * per-frame garbage.
 *
 * A marking is "paint": a bright, low-saturation pixel (white) or a saturated
 * yellow one. Thresholds match the Python validation harness so offline and
 * on-device numbers are comparable.
 */
class LaneOverlayRenderer {

    companion object {
        /** Vertical band searched for paint around each curve sample. */
        const val BAND_ROWS = 10

        /** +/- tolerance (relative to frame width) for the paint search. */
        const val SEARCH_FRAC = 0.035f

        /** A sample within this distance of paint counts as "on the marking". */
        const val HIT_PX = 25f

        /** Number of samples taken along the curve for the measurement. */
        const val SAMPLE_COUNT = 12

        /** Fraction of the curve (from mid-frame downwards) that is measured. */
        const val SAMPLE_FROM = 0.5f
        const val SAMPLE_TO = 0.90f
    }

    private var mask = BooleanArray(0)
    private var maskW = 0
    private var maskH = 0

    /**
     * Bake the marking mask for one frame. Reuses the buffer across frames;
     * the returned array is only valid until the next call.
     */
    fun buildMarkingMask(pixels: IntArray, width: Int, height: Int): BooleanArray {
        val need = width * height
        if (mask.size < need) {
            mask = BooleanArray(need)
            this.maskW = width
            this.maskH = height
        } else if (this.maskW != width || this.maskH != height) {
            this.maskW = width
            this.maskH = height
        }
        val m = mask
        var i = 0
        val n = need.coerceAtMost(pixels.size)
        while (i < n) {
            val argb = pixels[i]
            m[i] = isMarkingPixel(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF)
            i++
        }
        return m
    }

    /**
     * Median signed deviation (curve minus paint, in the units of xs/ys) and
     * paint support (fraction of samples within [HIT_PX]) for one curve.
     *
     * Returns (0, 0) when the curve cannot be measured, which callers treat as
     * "no evidence" rather than "perfectly aligned".
     */
    fun measure(
        xs: FloatArray,
        ys: FloatArray,
        frameWidth: Int,
        frameHeight: Int,
        hoodFraction: Float = 0.08f
    ): Pair<Float, Float> {
        if (xs.size < 4 || xs.size != ys.size || maskW <= 0 || maskH <= 0) return Pair(0f, 0f)
        val sx = maskW.toFloat() / frameWidth
        val sy = maskH.toFloat() / frameHeight
        val window = max(6f, SEARCH_FRAC * frameWidth)
        // Samples spread between mid-frame and the hood edge: clamping each
        // target to the hood top would pile them onto one row instead.
        val from = SAMPLE_FROM * frameHeight.toFloat()
        val to = min(SAMPLE_TO * frameHeight.toFloat(), frameHeight * (1f - hoodFraction.coerceIn(0f, 0.5f)) - 2f)
        if (to <= from) return Pair(0f, 0f)
        val deviations = ArrayList<Float>(SAMPLE_COUNT)
        var samples = 0
        var hits = 0
        for (s in 0 until SAMPLE_COUNT) {
            val targetY = from + (to - from) * s / (SAMPLE_COUNT - 1)
            var bestIdx = 0
            var bestDist = Float.MAX_VALUE
            for (j in ys.indices) {
                val d = abs(ys[j] - targetY)
                if (d < bestDist) { bestDist = d; bestIdx = j }
            }
            val x = xs[bestIdx]
            val y = ys[bestIdx]
            val my = (y * sy).toInt()
            val mxCenter = (x * sx).toInt()
            if (my < BAND_ROWS || my >= maskH - BAND_ROWS) continue
            samples++
            val windowMask = (window * sx).toInt()
            var nearest = Float.NaN
            var nearestDist = Int.MAX_VALUE
            for (dy in -BAND_ROWS..BAND_ROWS) {
                val row = (my + dy) * maskW
                for (dx in -windowMask..windowMask) {
                    val col = mxCenter + dx
                    if (col < 0 || col >= maskW) continue
                    if (!mask[row + col]) continue
                    val d = abs(dx)
                    if (d < nearestDist) { nearestDist = d; nearest = col.toFloat() }
                }
            }
            if (nearest.isNaN()) continue
            val dev = (mxCenter - nearest) / sx
            deviations.add(dev)
            if (abs(dev) <= HIT_PX) hits++
        }
        if (samples == 0) return Pair(0f, 0f)
        val dev = if (deviations.isEmpty()) 0f else median(deviations)
        return Pair(dev, hits.toFloat() / samples)
    }

    /** True when a pixel looks like lane paint. */
    fun isMarkingPixel(r: Int, g: Int, b: Int): Boolean {
        val maxV = maxOf(r, g, b)
        val minV = minOf(r, g, b)
        val delta = (maxV - minV).toFloat()
        val sat = if (maxV > 0) delta / maxV else 0f
        if (maxV > 150 && sat < 0.31f) return true           // white
        if (delta < 1f) return false
        val hue = when (maxV) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return hue > 30f && hue < 70f && sat > 0.2f && maxV > 100
    }

    /** Mask dimensions currently in use (0 until the first frame is baked). */
    val width: Int get() = maskW
    val height: Int get() = maskH

    private fun median(values: List<Float>): Float {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }
}
