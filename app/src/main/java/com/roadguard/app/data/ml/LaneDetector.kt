package com.roadguard.app.data.ml

import android.graphics.Bitmap
import android.util.Log
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Map an upright (display) coordinate to the sensor buffer coordinate it was
 * sampled from, packed as (bx shl 32) or by — packing keeps the per-pixel call
 * allocation-free.
 *
 * `rotationDegrees` is the clockwise rotation that turns the buffer into the
 * upright frame, so this is the inverse mapping:
 *   rot=90  → bx = uy,              by = height - 1 - ux
 *   rot=270 → bx = width - 1 - uy,  by = ux
 * Upright dimensions are (height, width) for 90/270, so both branches stay
 * inside [0, width) x [0, height).
 */
internal fun uprightToBufferIndex(
    ux: Int,
    uy: Int,
    width: Int,
    height: Int,
    rotationDegrees: Int
): Long {
    val rot = ((rotationDegrees % 360) + 360) % 360
    val bx: Int
    val by: Int
    when (rot) {
        90 -> { bx = uy; by = height - 1 - ux }
        180 -> { bx = width - 1 - ux; by = height - 1 - uy }
        270 -> { bx = width - 1 - uy; by = ux }
        else -> { bx = ux; by = uy }
    }
    return (bx.toLong() shl 32) or (by.toLong() and 0xFFFFFFFFL)
}

class LaneDetector(
    @Volatile private var sensitivity: Float = 0.5f
) {

    private companion object {
        const val MAX_WORK_WIDTH = 800
        const val MAX_WORK_HEIGHT = 600
        // Sliding windows per lane. 14 windows could never supply the 5+ points a
        // quadratic fit needs, so curvature was effectively never fitted.
        const val LANE_WINDOWS = 18
        const val HSV_HUE_YELLOW_LOW = 30f
        const val HSV_HUE_YELLOW_HIGH = 70f
    }

    fun updateSensitivity(value: Float) {
        sensitivity = value.coerceIn(0f, 1f)
    }
    data class LaneLine(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val angle: Float,
        val length: Float,
        val curvature: Float = 0f,
        val valid: Boolean = true,
        val polyA: Float = 0f,
        val polyB: Float = 0f,
        val polyC: Float = 0f,
        val yStart: Float = 0f,
        val yEnd: Float = 0f
    )

    data class LaneDetectionResult(
        val leftLane: LaneLine?,
        val rightLane: LaneLine?,
        val centerOffset: Float,
        val isDriftingLeft: Boolean,
        val isDriftingRight: Boolean,
        val confidence: Float,
        val laneWidth: Float,
        val imageWidth: Int = 0,
        val imageHeight: Int = 0
    )

    private var prevLeftLine: LaneLine? = null
    private var prevRightLine: LaneLine? = null
    private var frameCounter = 0
    
    private val vehicleCenterRatio = 0.5f
    private var expectedLaneWidth: Float = 300f

    /** Expected lane X in ORIGINAL image coordinates (center-offset fallback). */
    private var expectedLeftX: Float = 0f
    private var expectedRightX: Float = 0f

    /**
     * Expected lane X in the WORKING (BEV) coordinate space. Stored separately:
     * the Hough prior is used against `w = targetW`, but the old code fed the
     * original-resolution value in there — for a 1920px stream that prior was
     * coerced onto the wrong side of the search window every frame.
     */
    private var expectedLeftXBev: Float = 0f
    private var expectedRightXBev: Float = 0f
    
    private val leftXHistory = ArrayDeque<Float>(8)
    private val rightXHistory = ArrayDeque<Float>(8)
    
    private var lastLeftValid = false
    private var lastRightValid = false
    
    private val perspectiveMatrix = FloatArray(9)
    private val inversePerspectiveMatrix = FloatArray(9)
    private var inverseMatrixValid = false

    /** Smoothed vanishing-point row as a fraction of the frame height. */
    private var vpYRatio: Float = 0.5f

    // === Scratch buffers (reused across frames) ===
    // A single frame previously allocated ~25 MB (gray, bird's-eye, edges, mask,
    // HSV arrays) — at 5 fps that is >100 MB/s of garbage, which causes heavy GC
    // churn on mid-range devices. All hot buffers are now allocated once and
    // sized on demand. The detector is driven from a single analyzer thread, so
    // no synchronization is needed.
    private var scratchPixels = IntArray(0)
    private var scratchRawGray = IntArray(0)
    private var scratchColorMask = IntArray(0)
    private var scratchGray = IntArray(0)
    private var scratchBirdEye = IntArray(0)
    private var scratchBirdColor = IntArray(0)
    private var scratchEdges = IntArray(0)
    private var scratchRoadMask = IntArray(0)

    /**
     * Kalman-filtered smoothing for polynomial coefficients. Each coefficient (a, b, c)
     * is tracked as a scalar with constant-velocity model. Better than EMA because it
     * adapts to measurement noise and rejects outliers.
     */
    private class CoeffKalman(initial: Float) {
        var value: Float = initial
        private var velocity: Float = 0f
        private var p: Float = 1f
        private val q: Float = 0.002f   // process noise
        private val r: Float = 8f       // measurement noise (pixel units)

        fun update(measurement: Float): Float {
            // Predict
            value += velocity
            p += q
            // Update
            val k = p / (p + r)
            val innovation = measurement - value
            value += k * innovation
            velocity = 0.7f * velocity + 0.3f * k * innovation
            p *= (1f - k)
            return value
        }

        fun reset(v: Float = 0f) { value = v; velocity = 0f; p = 1f }
    }

    // Reused across frames: RANSAC only needs jitter, not a per-call allocation.
    private val ransacRandom = java.util.Random()

    private val leftCoeffA = CoeffKalman(0f)
    private val leftCoeffB = CoeffKalman(0f)
    private val leftCoeffC = CoeffKalman(0f)
    private val rightCoeffA = CoeffKalman(0f)
    private val rightCoeffB = CoeffKalman(0f)
    private val rightCoeffC = CoeffKalman(0f)
    private var kalmanPrimed = false
    private var leftPrimed = false
    private var rightPrimed = false

    /**
     * Ego-lane width prior in working (BEV-ish) coordinates: an online median
     * of recently accepted pair widths. Median is robust to single wrong
     * pairs; the default stays until consecutive consistent widths confirm a
     * real change. Prevents one early wrong pair from biasing selection.
     */
    private var expectedBevWidth: Float = 0f
    private var lastChosenLeftX: Float = 0f
    private var lastChosenRightX: Float = 0f
    /** Shared, unit-tested scoring + width-prior state. */
    private val egoLaneSelector = EgoLaneSelector()

    /** Raw (unsmoothed) lane candidate: pure measurement, no filter state. */
    private data class RawLane(
        val a: Float, val b: Float, val c: Float,
        val yStart: Float, val yEnd: Float,
        val xTop: Float, val xBottom: Float
    )

    fun detectLanes(bitmap: Bitmap): LaneDetectionResult {
        frameCounter++
        val startTime = System.currentTimeMillis()

        val srcW = bitmap.width
        val srcH = bitmap.height
        val targetW = min(srcW, MAX_WORK_WIDTH)
        val targetH = min(srcH, MAX_WORK_HEIGHT)
        val scaleX = srcW.toFloat() / targetW
        val scaleY = srcH.toFloat() / targetH

        // createScaledBitmap() allocates a *new* Bitmap even when the dimensions
        // already match, so skip it when the source is already at working size.
        val scaled = if (srcW != targetW || srcH != targetH) {
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        } else null

        val pixelCount = targetW * targetH
        val pixels = ensureCapacity(scratchPixels, pixelCount).also { scratchPixels = it }
        (scaled ?: bitmap).getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        scaled?.recycle()

        val rawGray = ensureCapacity(scratchRawGray, pixelCount).also { scratchRawGray = it }
        // Binary "looks like a lane marking" mask (white / yellow) computed in the
        // camera frame and later warped into BEV. Previously the full HSV volume
        // (3 float channels = ~11 MB) was allocated and interpolated per frame —
        // interpolating hue across edges is meaningless anyway.
        val colorMask = ensureCapacity(scratchColorMask, pixelCount).also { scratchColorMask = it }

        for (i in 0 until pixelCount) {
            val argb = pixels[i]
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            rawGray[i] = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)

            val maxVal = maxOf(r, g, b).toFloat()
            val minVal = minOf(r, g, b).toFloat()
            val delta = maxVal - minVal
            val sat = if (maxVal > 0f) delta / maxVal else 0f

            var hue = when {
                delta < 1f -> 0f
                maxVal == r.toFloat() -> 60f * (((g - b) / delta) % 6f)
                maxVal == g.toFloat() -> 60f * ((b - r) / delta + 2f)
                else -> 60f * ((r - g) / delta + 4f)
            }
            if (hue < 0f) hue += 360f

            val isWhite = maxVal > 150f && sat < 0.3f
            val isYellow = hue > HSV_HUE_YELLOW_LOW && hue < HSV_HUE_YELLOW_HIGH &&
                sat > 0.2f && maxVal > 100f
            colorMask[i] = if (isWhite || isYellow) 1 else 0
        }

        val gray = gaussianBlur5x5(
            rawGray,
            ensureCapacity(scratchGray, pixelCount).also { scratchGray = it },
            targetW, targetH
        )
        lastGray = gray  // Stash for adaptive VP detection in computePerspectiveMatrix

        computePerspectiveMatrix(targetW, targetH)

        val birdEye = applyWarpPerspective(
            gray,
            ensureCapacity(scratchBirdEye, pixelCount).also { scratchBirdEye = it },
            targetW, targetH
        )
        val birdColor = applyWarpPerspectiveMask(
            colorMask,
            ensureCapacity(scratchBirdColor, pixelCount).also { scratchBirdColor = it },
            targetW, targetH
        )
        
        val birdW = targetW
        val birdH = targetH

        val edges = detectEdges(
            birdEye,
            ensureCapacity(scratchEdges, pixelCount).also { scratchEdges = it },
            birdW, birdH
        )
        val roadMask = createRoadMask(
            birdEye, birdColor,
            ensureCapacity(scratchRoadMask, pixelCount).also { scratchRoadMask = it },
            birdW, birdH
        )
        
        val roiTop = (birdH * 0.15).toInt()
        val roiBottom = birdH

        // Ego-lane pair selection: evaluate all left/right candidates and pick
        // the pair straddling the vehicle center with plausible width. Falls
        // back to single-side detection when no pair passes validation.
        val pair = detectEgoLanePair(edges, roadMask, birdW, birdH, roiTop, roiBottom)
        val leftLane: LaneLine?
        val rightLane: LaneLine?
        if (pair != null) {
            val (rawL, rawR) = pair
            lastChosenLeftX = rawL.xBottom
            lastChosenRightX = rawR.xBottom
            val abcL = applyKalman(rawL, isLeft = true, birdW)
            val abcR = applyKalman(rawR, isLeft = false, birdW)
            leftLane = buildLaneLine(rawL, abcL, birdW)
            rightLane = buildLaneLine(rawR, abcR, birdW)
            val pairWidth = rawR.xBottom - rawL.xBottom
            if (pairWidth in birdW * 0.10f..birdW * 0.75f) {
                observeBevWidth(pairWidth, birdW)
            }
        } else {
            leftLane = detectLaneWithHough(edges, roadMask, birdW, birdH, roiTop, roiBottom, isLeft = true)
            rightLane = detectLaneWithHough(edges, roadMask, birdW, birdH, roiTop, roiBottom, isLeft = false)
        }

        val leftValid = leftLane?.valid == true
        val rightValid = rightLane?.valid == true
        val confidence = calculateConfidence(leftValid, rightValid, roadMask, birdW, birdH, roiTop)

        val leftOrig = leftLane?.let { scaleLine(it, scaleX, scaleY) }
        val rightOrig = rightLane?.let { scaleLine(it, scaleX, scaleY) }

        val centerOffset = calculateCenterOffset(leftOrig, rightOrig, bitmap.width)
        val laneWidth = calculateLaneWidth(leftOrig, rightOrig)

        val isDriftingLeft = centerOffset < -bitmap.width * 0.04f * (1.5f - sensitivity) && confidence > 0.4f
        val isDriftingRight = centerOffset > bitmap.width * 0.04f * (1.5f - sensitivity) && confidence > 0.4f

        if (laneWidth in 100f..600f) {
            expectedLaneWidth = expectedLaneWidth * 0.9f + laneWidth * 0.1f
        }

        updateLanePriors(leftLane, rightLane, leftOrig, rightOrig)

        val smoothedLeft = smoothLane(leftOrig, prevLeftLine, leftXHistory)
        val smoothedRight = smoothLane(rightOrig, prevRightLine, rightXHistory)
        
        prevLeftLine = smoothedLeft
        prevRightLine = smoothedRight
        lastLeftValid = leftValid
        lastRightValid = rightValid

        val elapsed = System.currentTimeMillis() - startTime
        if (frameCounter % 30 == 0) {
            Log.d("LaneDetector", "Frame $frameCounter: ${bitmap.width}x${bitmap.height} " +
                    "L=${if (leftValid) "OK" else "--"} R=${if (rightValid) "OK" else "--"} " +
                    "conf=${"%.2f".format(confidence)} offset=${"%.0f".format(centerOffset)} " +
                    "width=${"%.0f".format(laneWidth)} ${elapsed}ms")
        }

        return LaneDetectionResult(
            leftLane = smoothedLeft,
            rightLane = smoothedRight,
            centerOffset = centerOffset,
            isDriftingLeft = isDriftingLeft,
            isDriftingRight = isDriftingRight,
            confidence = confidence,
            laneWidth = laneWidth,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height
        )
    }

    fun detectLanesFromYUV(
        yData: ByteArray,
        width: Int, height: Int,
        rotationDegrees: Int = 0
    ): LaneDetectionResult {
        frameCounter++
        val startTime = System.currentTimeMillis()

        // The camera buffer is delivered in sensor coordinates; the model and
        // the UI work in the upright (display) frame. Without de-rotation the
        // detector ran on landscape in portrait, which flips left/right and
        // collapses the trapezoid.
        val rot = ((rotationDegrees % 360) + 360) % 360
        val landscapeUpright = rot == 0 || rot == 180
        val uprightW = if (landscapeUpright) width else height
        val uprightH = if (landscapeUpright) height else width
        val targetW = min(uprightW, MAX_WORK_WIDTH)
        val targetH = min(uprightH, MAX_WORK_HEIGHT)
        val scaleX = uprightW.toFloat() / targetW
        val scaleY = uprightH.toFloat() / targetH

        val pixelCount = targetW * targetH
        val rawGray = ensureCapacity(scratchRawGray, pixelCount).also { scratchRawGray = it }

        for (y in 0 until targetH) {
            val dstRow = y * targetW
            val uy = ((y + 0.5f) * scaleY).toInt().coerceIn(0, uprightH - 1)
            for (x in 0 until targetW) {
                val ux = ((x + 0.5f) * scaleX).toInt().coerceIn(0, uprightW - 1)
                val packed = uprightToBufferIndex(ux, uy, width, height, rot)
                val bx = (packed shr 32).toInt()
                val by = packed.toInt()
                rawGray[dstRow + x] = if (bx in 0 until width && by in 0 until height) {
                    yData[by * width + bx].toInt() and 0xFF
                } else 0
            }
        }

        val gray = gaussianBlur5x5(
            rawGray,
            ensureCapacity(scratchGray, pixelCount).also { scratchGray = it },
            targetW, targetH
        )
        lastGray = gray

        computePerspectiveMatrix(targetW, targetH)

        val birdEye = applyWarpPerspective(
            gray,
            ensureCapacity(scratchBirdEye, pixelCount).also { scratchBirdEye = it },
            targetW, targetH
        )
        // YUV frames already collapsed gradients into the HSV "pseudo-saturation"
        // heuristic via `localSat`. That fill made every edge look like a marking;
        // in the Y path the mask is now a pure luma threshold without extras.
        
        val birdW = targetW
        val birdH = targetH

        val edges = detectEdges(
            birdEye,
            ensureCapacity(scratchEdges, pixelCount).also { scratchEdges = it },
            birdW, birdH
        )
        val roadMask = createRoadMask(
            birdEye, null,
            ensureCapacity(scratchRoadMask, pixelCount).also { scratchRoadMask = it },
            birdW, birdH
        )
        
        val roiTop = (birdH * 0.15).toInt()
        val roiBottom = birdH

        val pair = detectEgoLanePair(edges, roadMask, birdW, birdH, roiTop, roiBottom)
        val leftLane: LaneLine?
        val rightLane: LaneLine?
        if (pair != null) {
            val (rawL, rawR) = pair
            lastChosenLeftX = rawL.xBottom
            lastChosenRightX = rawR.xBottom
            val abcL = applyKalman(rawL, isLeft = true, birdW)
            val abcR = applyKalman(rawR, isLeft = false, birdW)
            leftLane = buildLaneLine(rawL, abcL, birdW)
            rightLane = buildLaneLine(rawR, abcR, birdW)
            val pairWidth = rawR.xBottom - rawL.xBottom
            if (pairWidth in birdW * 0.10f..birdW * 0.75f) {
                observeBevWidth(pairWidth, birdW)
            }
        } else {
            leftLane = detectLaneWithHough(edges, roadMask, birdW, birdH, roiTop, roiBottom, isLeft = true)
            rightLane = detectLaneWithHough(edges, roadMask, birdW, birdH, roiTop, roiBottom, isLeft = false)
        }

        val leftValid = leftLane?.valid == true
        val rightValid = rightLane?.valid == true
        val confidence = calculateConfidence(leftValid, rightValid, roadMask, birdW, birdH, roiTop)

        val leftOrig = leftLane?.let { scaleLine(it, scaleX, scaleY) }
        val rightOrig = rightLane?.let { scaleLine(it, scaleX, scaleY) }

        // centerOffset/laneWidth are computed in upright display coordinates
        val centerOffset = calculateCenterOffset(leftOrig, rightOrig, uprightW)
        val laneWidth = calculateLaneWidth(leftOrig, rightOrig)

        val isDriftingLeft = centerOffset < -uprightW * 0.04f * (1.5f - sensitivity) && confidence > 0.4f
        val isDriftingRight = centerOffset > uprightW * 0.04f * (1.5f - sensitivity) && confidence > 0.4f

        if (laneWidth in 100f..600f) {
            expectedLaneWidth = expectedLaneWidth * 0.9f + laneWidth * 0.1f
        }

        updateLanePriors(leftLane, rightLane, leftOrig, rightOrig)

        val smoothedLeft = smoothLane(leftOrig, prevLeftLine, leftXHistory)
        val smoothedRight = smoothLane(rightOrig, prevRightLine, rightXHistory)
        
        prevLeftLine = smoothedLeft
        prevRightLine = smoothedRight
        lastLeftValid = leftValid
        lastRightValid = rightValid

        val elapsed = System.currentTimeMillis() - startTime
        if (frameCounter % 30 == 0) {
            Log.d("LaneDetector", "Frame $frameCounter (YUV): ${width}x${height} " +
                    "L=${if (leftValid) "OK" else "--"} R=${if (rightValid) "OK" else "--"} " +
                    "conf=${"%.2f".format(confidence)} offset=${"%.0f".format(centerOffset)} " +
                    "width=${"%.0f".format(laneWidth)} ${elapsed}ms")
        }

        return LaneDetectionResult(
            leftLane = smoothedLeft,
            rightLane = smoothedRight,
            centerOffset = centerOffset,
            isDriftingLeft = isDriftingLeft,
            isDriftingRight = isDriftingRight,
            confidence = confidence,
            laneWidth = laneWidth,
            imageWidth = uprightW,
            imageHeight = uprightH
        )
    }

    fun reset() {
        prevLeftLine = null
        prevRightLine = null
        frameCounter = 0
        expectedLaneWidth = 300f
        expectedLeftX = 0f
        expectedRightX = 0f
        expectedLeftXBev = 0f
        expectedRightXBev = 0f
        expectedBevWidth = 0f
        egoLaneSelector.reset()
        lastChosenLeftX = 0f
        lastChosenRightX = 0f
        leftXHistory.clear()
        rightXHistory.clear()
        lastLeftValid = false
        lastRightValid = false
        vpYRatio = 0.5f
        lastGray = null
        leftCoeffA.reset(); leftCoeffB.reset(); leftCoeffC.reset()
        rightCoeffA.reset(); rightCoeffB.reset(); rightCoeffC.reset()
        kalmanPrimed = false
        leftPrimed = false
        rightPrimed = false
    }

    private fun computePerspectiveMatrix(w: Int, h: Int) {
        // Temporal smoothing keeps the horizon from jumping frame-to-frame on
        // noisy inputs. Old code used the raw per-frame estimate directly.
        val rawVpY = detectVanishingPoint(w, h)
        vpYRatio = vpYRatio * 0.85f + (rawVpY / h) * 0.15f
        val vpY = (vpYRatio * h).coerceIn(h * 0.35f, h * 0.65f)
        val srcPoints = floatArrayOf(
            w * 0.42f, vpY,
            w * 0.58f, vpY,
            w * 0.95f, h * 0.98f,
            w * 0.05f, h * 0.98f
        )

        // Destination: rectangle in bird's-eye view (top-down)
        val dstPoints = floatArrayOf(
            w * 0.15f, h * 0.0f,
            w * 0.85f, h * 0.0f,
            w * 0.85f, h * 1.0f,
            w * 0.15f, h * 1.0f
        )

        val (matrix, _) = computeHomography(srcPoints, dstPoints)
        for (i in matrix.indices) {
            perspectiveMatrix[i] = matrix[i]
        }

        // Compute and cache the inverse — needed to project detected BEV lanes
        // back into the camera frame for AR overlay.
        val inv = invert3x3(perspectiveMatrix)
        if (inv != null) {
            System.arraycopy(inv, 0, inversePerspectiveMatrix, 0, 9)
            inverseMatrixValid = true
        } else {
            // Forward matrix is singular; AR projection disabled (overlay will fall back)
            inverseMatrixValid = false
        }
    }

    /**
     * Horizon-row estimate from the luma frame's left/right edge balance.
     * The old `!= 0` test counted every non-black pixel and degenerated to
     * `density ~ 1`, so the "best" row was simply the first one scanned.
     */
    private fun detectVanishingPoint(w: Int, h: Int): Float {
        val gray = lastGray ?: return h * 0.5f
        val lo = (h * 0.35f).toInt()
        val hi = (h * 0.65f).toInt()
        val midX = w / 2
        var bestRow = (h * 0.5f).toInt()
        var bestScore = -1f

        var rowGradPrev = 0
        for (y in lo until hi step 3) {
            var leftEdges = 0
            var rightEdges = 0
            // Horizontal-gradient energy accumulated over mid-luma pixels only:
            // crushed blacks and blown highlights carry no structure.
            var rowGrad = 0
            for (x in 1 until w - 1) {
                val idx = y * w + x
                if (idx >= gray.size) continue
                val v = gray[idx]
                if (v < 40 || v > 220) continue
                rowGrad += abs(v - gray[idx - 1])
                val edge = (abs(v - gray[idx - 1]) + abs(v - gray[idx + 1])) / 2
                if (edge < 12) continue
                if (x < midX) leftEdges++ else rightEdges++
            }
            val total = leftEdges + rightEdges
            if (total < 4) continue
            val balance = 1f - abs(leftEdges - rightEdges) / total.toFloat()
            val density = total.toFloat() / w
            // Averaged with the previous sampled row to damp single-row noise.
            val gradFactor = (rowGrad + rowGradPrev).toFloat() / (2 * w)
            val score = balance * 0.5f + density * 0.3f + gradFactor * 0.01f
            rowGradPrev = rowGrad
            if (score > bestScore) {
                bestScore = score
                bestRow = y
            }
        }
        return if (bestScore < 0f) h * 0.5f else bestRow.toFloat().coerceIn(h * 0.35f, h * 0.65f)
    }

    private var lastGray: IntArray? = null

    /**
     * Invert a 3x3 row-major matrix encoded as FloatArray(9).
     * Returns null if the determinant is too close to zero (singular).
     */
    private fun invert3x3(m: FloatArray): FloatArray? {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (abs(det) < 1e-6f) return null
        val invDet = 1f / det
        return floatArrayOf(
            (e * i - f * h) * invDet,
            (c * h - b * i) * invDet,
            (b * f - c * e) * invDet,
            (f * g - d * i) * invDet,
            (a * i - c * g) * invDet,
            (c * d - a * f) * invDet,
            (d * h - e * g) * invDet,
            (b * g - a * h) * invDet,
            (a * e - b * d) * invDet
        )
    }

    private fun updateLanePriors(
        leftPerspective: LaneLine?,
        rightPerspective: LaneLine?,
        leftOriginal: LaneLine?,
        rightOriginal: LaneLine?
    ) {
        leftPerspective?.let {
            mapPerspectiveToBev(it.x2, it.y2)?.first?.takeIf(Float::isFinite)?.let { bevX ->
                expectedLeftXBev = if (expectedLeftXBev > 0f) expectedLeftXBev * 0.9f + bevX * 0.1f else bevX
            }
        }
        rightPerspective?.let {
            mapPerspectiveToBev(it.x2, it.y2)?.first?.takeIf(Float::isFinite)?.let { bevX ->
                expectedRightXBev = if (expectedRightXBev > 0f) expectedRightXBev * 0.9f + bevX * 0.1f else bevX
            }
        }
        leftOriginal?.let {
            val avgX = (it.x1 + it.x2) / 2f
            expectedLeftX = if (expectedLeftX > 0f) expectedLeftX * 0.9f + avgX * 0.1f else avgX
        }
        rightOriginal?.let {
            val avgX = (it.x1 + it.x2) / 2f
            expectedRightX = if (expectedRightX > 0f) expectedRightX * 0.9f + avgX * 0.1f else avgX
        }
    }

    private fun mapPerspectiveToBev(px: Float, py: Float): Pair<Float, Float>? {
        val m = perspectiveMatrix
        val denom = m[6] * px + m[7] * py + m[8]
        if (abs(denom) < 1e-6f) return null
        val x = (m[0] * px + m[1] * py + m[2]) / denom
        val y = (m[3] * px + m[4] * py + m[5]) / denom
        return if (x.isFinite() && y.isFinite()) Pair(x, y) else null
    }

    /**
     * Map a single point from BEV (bird's-eye-view) coordinates back to the
     * original camera/perspective coordinates. Used to draw lanes in AR.
     */
    private fun mapBevToPerspective(bevX: Float, bevY: Float): Pair<Float, Float> {
        if (!inverseMatrixValid) return Pair(bevX, bevY)
        val m = inversePerspectiveMatrix
        val w = m[6] * bevX + m[7] * bevY + m[8]
        if (abs(w) < 1e-6f) return Pair(bevX, bevY)
        val px = (m[0] * bevX + m[1] * bevY + m[2]) / w
        val py = (m[3] * bevX + m[4] * bevY + m[5]) / w
        return Pair(px, py)
    }

    private fun computeHomography(src: FloatArray, dst: FloatArray): Pair<FloatArray, Boolean> {
        val a = Array(8) { FloatArray(8) }
        val b = FloatArray(8)
        
        for (i in 0 until 4) {
            val sx = src[i * 2]
            val sy = src[i * 2 + 1]
            val dx = dst[i * 2]
            val dy = dst[i * 2 + 1]
            
            a[i * 2][0] = sx
            a[i * 2][1] = sy
            a[i * 2][2] = 1f
            a[i * 2][3] = 0f
            a[i * 2][4] = 0f
            a[i * 2][5] = 0f
            a[i * 2][6] = -sx * dx
            a[i * 2][7] = -sy * dx
            b[i * 2] = dx
            
            a[i * 2 + 1][0] = 0f
            a[i * 2 + 1][1] = 0f
            a[i * 2 + 1][2] = 0f
            a[i * 2 + 1][3] = sx
            a[i * 2 + 1][4] = sy
            a[i * 2 + 1][5] = 1f
            a[i * 2 + 1][6] = -sx * dy
            a[i * 2 + 1][7] = -sy * dy
            b[i * 2 + 1] = dy
        }
        
        val h = solveLinearSystem(a, b)
        return if (h != null) {
            Pair(floatArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1f), true)
        } else {
            Pair(FloatArray(9) { if (it < 8) 0f else 1f }, false)
        }
    }

    private fun solveLinearSystem(a: Array<FloatArray>, b: FloatArray): FloatArray? {
        val n = 8
        val aug = Array(n) { FloatArray(n + 1) }
        
        for (i in 0 until n) {
            for (j in 0 until n) {
                aug[i][j] = a[i][j]
            }
            aug[i][n] = b[i]
        }
        
        for (col in 0 until n) {
            var maxRow = col
            for (row in col + 1 until n) {
                if (abs(aug[row][col]) > abs(aug[maxRow][col])) {
                    maxRow = row
                }
            }
            
            val temp = aug[col]
            aug[col] = aug[maxRow]
            aug[maxRow] = temp
            
            if (abs(aug[col][col]) < 1e-6f) return null
            
            for (row in col + 1 until n) {
                val factor = aug[row][col] / aug[col][col]
                for (j in col until n + 1) {
                    aug[row][j] -= factor * aug[col][j]
                }
            }
        }
        
        val x = FloatArray(n)
        for (i in n - 1 downTo 0) {
            x[i] = aug[i][n]
            for (j in i + 1 until n) {
                x[i] -= aug[i][j] * x[j]
            }
            x[i] /= aug[i][i]
        }
        
        return x
    }

    /**
     * Sample the camera image at every BEV pixel. `perspectiveMatrix` maps
     * camera→BEV, so warping BEV←camera must use its inverse. Using the forward
     * matrix sampled far outside the frame (and flipped vertically), which left
     * the whole far field black and made lane detection unusable.
     */
    private fun applyWarpPerspective(src: IntArray, dst: IntArray, w: Int, h: Int): IntArray {
        Arrays.fill(dst, 0, w * h, 0)
        if (!inverseMatrixValid) return dst
        val m = inversePerspectiveMatrix
        
        for (y in 0 until h) {
            for (x in 0 until w) {
                val wInv = m[6] * x + m[7] * y + m[8]
                // Degenerate homography / points on the vanishing line: skip
                // instead of producing Infinity or NaN source coordinates.
                if (abs(wInv) < 1e-6f) continue
                val srcX = (m[0] * x + m[1] * y + m[2]) / wInv
                val srcY = (m[3] * x + m[4] * y + m[5]) / wInv
                
                val x0 = srcX.toInt()
                val y0 = srcY.toInt()
                val fx = srcX - x0
                val fy = srcY - y0
                
                if (x0 >= 0 && x0 < w - 1 && y0 >= 0 && y0 < h - 1) {
                    val idx00 = y0 * w + x0
                    val idx10 = y0 * w + x0 + 1
                    val idx01 = (y0 + 1) * w + x0
                    val idx11 = (y0 + 1) * w + x0 + 1
                    
                    val v00 = src[idx00].toFloat()
                    val v10 = src[idx10].toFloat()
                    val v01 = src[idx01].toFloat()
                    val v11 = src[idx11].toFloat()
                    
                    val interpolated = v00 * (1 - fx) * (1 - fy) +
                                     v10 * fx * (1 - fy) +
                                     v01 * (1 - fx) * fy +
                                     v11 * fx * fy
                    
                    dst[y * w + x] = interpolated.toInt().coerceIn(0, 255)
                }
            }
        }
        
        return dst
    }

    /**
     * Warp the binary "lane-marking color" mask into BEV. Bilinear output is
     * thresholded at 0.5 so a marking survives the interpolation instead of
     * vanishing (the gray path truncates, which would drop every sample where
     * not all four taps are set).
     */
    private fun applyWarpPerspectiveMask(src: IntArray, dst: IntArray, w: Int, h: Int): IntArray {
        Arrays.fill(dst, 0, w * h, 0)
        if (!inverseMatrixValid) return dst
        val m = inversePerspectiveMatrix

        for (y in 0 until h) {
            for (x in 0 until w) {
                val wInv = m[6] * x + m[7] * y + m[8]
                if (abs(wInv) < 1e-6f) continue
                val srcX = (m[0] * x + m[1] * y + m[2]) / wInv
                val srcY = (m[3] * x + m[4] * y + m[5]) / wInv
                if (!srcX.isFinite() || !srcY.isFinite()) continue

                val x0 = srcX.toInt()
                val y0 = srcY.toInt()
                if (x0 < 0 || x0 >= w - 1 || y0 < 0 || y0 >= h - 1) continue
                val fx = srcX - x0
                val fy = srcY - y0

                val v00 = src[y0 * w + x0].toFloat()
                val v10 = src[y0 * w + x0 + 1].toFloat()
                val v01 = src[(y0 + 1) * w + x0].toFloat()
                val v11 = src[(y0 + 1) * w + x0 + 1].toFloat()

                val interpolated = v00 * (1 - fx) * (1 - fy) +
                    v10 * fx * (1 - fy) +
                    v01 * (1 - fx) * fy +
                    v11 * fx * fy

                dst[y * w + x] = if (interpolated >= 0.5f) 1 else 0
            }
        }

        return dst
    }

    private fun detectEdges(src: IntArray, dst: IntArray, w: Int, h: Int): IntArray {
        Arrays.fill(dst, 0, w * h, 0)
        val lowThresh = (20 + sensitivity * 25).toInt().coerceIn(15, 50)
        val highThresh = lowThresh * 2

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val gx = -src[idx - w - 1] + src[idx - w + 1] - 2*src[idx - 1] + 2*src[idx + 1] - src[idx + w - 1] + src[idx + w + 1]
                val gy = -src[idx - w - 1] - 2*src[idx - w] - src[idx - w + 1] + src[idx + w - 1] + 2*src[idx + w] + src[idx + w + 1]
                val magSq = (gx * gx + gy * gy).toDouble()
                dst[idx] = when {
                    magSq > (highThresh * highThresh).toDouble() -> 255
                    magSq > (lowThresh * lowThresh).toDouble() -> 128
                    else -> 0
                }
            }
        }
        return dst
    }

    private fun createRoadMask(gray: IntArray, colorMaskBev: IntArray?, mask: IntArray, w: Int, h: Int): IntArray {
        Arrays.fill(mask, 0, w * h, 0)

        val roiTop = (h * 0.05).toInt()

        if (roiTop >= h) return mask

        val sampleStep = max(1, ((h - roiTop) * w) / 8000)
        var sumBright = 0L
        var countSample = 0
        var i = 0
        val totalRoadPixels = (h - roiTop) * w
        while (i < totalRoadPixels) {
            val y = roiTop + i / w
            val x = i % w
            sumBright += gray[y * w + x]
            countSample++
            i += sampleStep
        }

        if (countSample == 0) return mask
        val meanBright = (sumBright / countSample).toInt()

        var sumSq = 0L
        i = 0
        while (i < totalRoadPixels) {
            val y = roiTop + i / w
            val x = i % w
            val diff = gray[y * w + x] - meanBright
            sumSq += diff * diff
            i += sampleStep
        }
        val stdDev = sqrt((sumSq.toDouble() / countSample)).toInt()
        val brightnessThresh = (meanBright + stdDev * 2).coerceIn(100, 200)

        for (y in roiTop until h) {
            val rowProgress = (y - roiTop).toFloat() / (h - roiTop)

            val leftEdge = (w * (0.08f + 0.1f * rowProgress)).toInt()
            val rightEdge = (w * (0.92f - 0.1f * rowProgress)).toInt()

            for (x in leftEdge until rightEdge) {
                val idx = y * w + x
                val brightness = gray[idx]

                if (appearanceGate(brightness, brightnessThresh, colorMaskBev, w, y, x)) {
                    mask[idx] = 1
                    // Dilate the mask by one row so a single-pixel thinning gap
                    // (and breaking the sliding-window centroid up) disappears.
                    if (y > roiTop) mask[(y - 1) * w + x] = max(mask[(y - 1) * w + x], 1)
                    if (y < h - 1) mask[(y + 1) * w + x] = max(mask[(y + 1) * w + x], 1)
                }
            }
        }

        return mask
    }

    private fun appearanceGate(brightness: Int, thresh: Int, colorMaskBev: IntArray?, w: Int, y: Int, x: Int): Boolean {
        if (colorMaskBev != null && colorMaskBev[y * w + x] > 0) return true
        return brightness > thresh
    }

    // === Ego-lane pair selection (multi-candidate) ===
    // The old code took the single strongest histogram peak per half-frame.
    // On multi-lane roads that is the OUTER marking (adjacent lane / shoulder
    // edge), so the overlay spanned two lanes and drift warnings fired on
    // straight driving. Now: collect up to 4 local-maximum peaks per side,
    // trace each without touching filter state, and pick the pair straddling
    // the vehicle center with plausible width.

    private fun computeLaneHistogram(
        edges: IntArray, mask: IntArray, w: Int, roiTop: Int, roiBottom: Int
    ): IntArray {
        val histogram = IntArray(w)
        val windowHeight = max((roiBottom - roiTop) / LANE_WINDOWS, 1)
        val histStart = (roiBottom - windowHeight * 3).coerceAtLeast(roiTop)
        for (y in histStart until roiBottom) {
            val row = y * w
            for (x in 0 until w) {
                val idx = row + x
                if (mask[idx] > 0) histogram[x] += 3
                else if (edges[idx] > 0) histogram[x] += 1
            }
        }
        return histogram
    }

    private fun findLanePeaks(
        histogram: IntArray, searchStart: Int, searchEnd: Int,
        windowWidth: Int, seeds: FloatArray = floatArrayOf()
    ): List<Int> {
        val sums = IntArray(histogram.size)
        var best = 0
        val last = (searchEnd - windowWidth).coerceAtMost(histogram.size - windowWidth - 1)
        for (i in searchStart..last.coerceAtLeast(searchStart)) {
            var sum = 0
            for (j in 0 until windowWidth) sum += histogram[i + j]
            sums[i] = sum
            if (sum > best) best = sum
        }
        val floor = max(best * 0.25f, 12f)
        val local = ArrayList<Int>()
        for (i in searchStart..last.coerceAtLeast(searchStart)) {
            val s = sums[i].toFloat()
            if (s < floor) continue
            val prev = if (i > 0) sums[i - 1] else -1
            val next = if (i + 1 < sums.size) sums[i + 1] else -1
            if (sums[i] >= prev && sums[i] >= next) local.add(i)
        }
        local.sortByDescending { sums[it] }
        val picked = ArrayList<Int>()
        for (i in local) {
            val px = i + windowWidth / 2
            if (picked.all { abs(px - it) > windowWidth / 2 }) picked.add(px)
            if (picked.size >= 4) break
        }
        for (seed in seeds) {
            if (seed > 0f && seed >= searchStart && seed < searchEnd &&
                picked.all { abs(seed - it) > windowWidth / 4 }) {
                picked.add(seed.toInt())
            }
        }
        return picked
    }

    /**
     * Trace + fit for ONE peak candidate. Pure measurement: no Kalman update,
     * no prior mutation — rejected candidates must leave no state behind.
     * Narrow fixed window (0.75x) with mask-weighted centroid: the old growing
     * margin (up to 2x) let the centroid get pulled onto the neighbouring
     * lane's marking on dashed/gapped lines.
     */
    private fun traceRawLane(
        edges: IntArray, mask: IntArray, w: Int, h: Int,
        roiTop: Int, roiBottom: Int, peakX: Int, isLeft: Boolean
    ): RawLane? {
        val windowWidth = w / 8
        val midX = w / 2
        val windowHeight = max((roiBottom - roiTop) / LANE_WINDOWS, 1)
        var currentX = peakX.toFloat()
        val points = ArrayList<Pair<Int, Int>>(LANE_WINDOWS)
        val margin = max(8, (windowWidth * 0.75f).toInt())
        for (win in 0 until LANE_WINDOWS) {
            val winYLow = roiBottom - (win + 1) * windowHeight
            val winYHigh = roiBottom - win * windowHeight
            val winXLow = (currentX - margin).toInt().coerceIn(0, w - 1)
            val winXHigh = (currentX + margin).toInt().coerceIn(1, w)
            var sumW = 0.0
            var sumXW = 0.0
            var sumY = 0
            var count = 0
            for (y in winYLow until winYHigh) {
                val row = y * w
                for (x in winXLow until winXHigh) {
                    val idx = row + x
                    val weight = when {
                        mask[idx] > 0 -> 3
                        edges[idx] > 0 -> 1
                        else -> 0
                    }
                    if (weight > 0) {
                        sumW += weight
                        sumXW += x * weight
                        sumY += y
                        count++
                    }
                }
            }
            if (count > 8) {
                val newX = (sumXW / sumW).toFloat()
                val newY = (sumY.toFloat() / count).toInt()
                if (abs(newX - currentX) < windowWidth * 0.35f || points.isEmpty()) {
                    currentX = currentX * 0.3f + newX * 0.7f
                    points.add(Pair(currentX.toInt(), newY))
                }
            }
        }
        if (points.size < 4) return null
        val (aBev, bBev, cBev) = fitPolynomialRansac(points) ?: return null
        val perspectiveSamples = sampleAndProjectToPerspective(
            aBev, bBev, cBev, w, h, yStartF = roiTop.toFloat(), yEndF = roiBottom.toFloat()
        )
        if (perspectiveSamples.size < 3) return null
        val (aRaw, bRaw, cRaw) = if (perspectiveSamples.size >= 5) {
            solvePolyFromFloat(perspectiveSamples) ?: fitLinear(perspectiveSamples) ?: return null
        } else {
            fitLinear(perspectiveSamples) ?: return null
        }
        val yStartF = perspectiveSamples.first().second.coerceIn(0f, h.toFloat())
        val yEndF = perspectiveSamples.last().second.coerceIn(0f, h.toFloat())
        if (yEndF - yStartF < 2f) return null
        fun polyX(y: Float): Float = aRaw * y * y + bRaw * y + cRaw
        val xAtTop = polyX(yStartF).coerceIn(0f, w.toFloat())
        val xAtBottom = polyX(yEndF).coerceIn(0f, w.toFloat())
        val onSide = if (isLeft) {
            xAtBottom < midX - windowWidth / 4 && xAtTop < midX
        } else {
            xAtBottom > midX + windowWidth / 4 && xAtTop > midX
        }
        if (!onSide) return null
        return RawLane(aRaw, bRaw, cRaw, yStartF, yEndF, xAtTop, xAtBottom)
    }

    /**
     * Pick the ego-lane pair: all left/right candidates traced, then the pair
     * straddling the center with plausible width wins. Edge-glued traces
     * (ran off to the image border) are excluded first. Continuity with the
     * previous frame breaks ties. Scoring lives in [EgoLaneSelector] (unit
     * tested); this only adapts RawLane to its Candidate type.
     */
    private fun detectEgoLanePair(
        edges: IntArray, mask: IntArray, w: Int, h: Int,
        roiTop: Int, roiBottom: Int
    ): Pair<RawLane, RawLane>? {
        val histogram = computeLaneHistogram(edges, mask, w, roiTop, roiBottom)
        val midX = w / 2
        val windowWidth = w / 8
        val leftPeaks = findLanePeaks(histogram, 0, midX, windowWidth, floatArrayOf(expectedLeftXBev))
        val rightPeaks = findLanePeaks(histogram, midX, w, windowWidth, floatArrayOf(expectedRightXBev))
        val candsL = leftPeaks.mapNotNull { p ->
            traceRawLane(edges, mask, w, h, roiTop, roiBottom, p, isLeft = true)
        }
        val candsR = rightPeaks.mapNotNull { p ->
            traceRawLane(edges, mask, w, h, roiTop, roiBottom, p, isLeft = false)
        }
        if (candsL.isEmpty() || candsR.isEmpty()) return null
        fun glued(r: RawLane) = r.xBottom <= w * 0.02f || r.xBottom >= w * 0.98f
        val poolL = candsL.filter { !glued(it) }.ifEmpty { candsL }
            .map { EgoLaneSelector.Candidate(it.xTop, it.xBottom) to it }
        val poolR = candsR.filter { !glued(it) }.ifEmpty { candsR }
            .map { EgoLaneSelector.Candidate(it.xTop, it.xBottom) to it }
        val expW = if (expectedBevWidth > 0f) expectedBevWidth else w * 0.35f
        var best: Pair<RawLane, RawLane>? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for ((cl, rl) in poolL) {
            for ((cr, rr) in poolR) {
                val score = egoLaneSelector.scorePair(
                    cl, cr, w, midX.toFloat(), expW, lastChosenLeftX, lastChosenRightX
                ) ?: continue
                if (score > bestScore) {
                    bestScore = score
                    best = Pair(rl, rr)
                }
            }
        }
        return best
    }

    /**
     * Kalman update with jump detection: a measurement the tracker could never
     * drift to in one frame is a DIFFERENT marking (lane change / corrected
     * candidate) — re-prime instead of blending two lanes into a phantom
     * middle line. This was the main false-drift source: the old filter
     * dragged a stale outer-lane prior across 10+ frames.
     */
    private fun applyKalman(raw: RawLane, isLeft: Boolean, w: Int): Triple<Float, Float, Float> {
        val kfA = if (isLeft) leftCoeffA else rightCoeffA
        val kfB = if (isLeft) leftCoeffB else rightCoeffB
        val kfC = if (isLeft) leftCoeffC else rightCoeffC
        val primed = if (isLeft) leftPrimed else rightPrimed
        val jumpThreshold = w / 8 * 0.75f
        if (!primed || abs(raw.c - kfC.value) > jumpThreshold) {
            kfA.reset(raw.a); kfB.reset(raw.b); kfC.reset(raw.c)
            if (isLeft) leftPrimed = true else rightPrimed = true
            if (leftPrimed && rightPrimed) kalmanPrimed = true
            return Triple(raw.a, raw.b, raw.c)
        }
        return Triple(kfA.update(raw.a), kfB.update(raw.b), kfC.update(raw.c))
    }

    private fun buildLaneLine(raw: RawLane, abc: Triple<Float, Float, Float>, w: Int): LaneLine {
        val (a, b, c) = abc
        val xAtTop = (a * raw.yStart * raw.yStart + b * raw.yStart + c).coerceIn(0f, w.toFloat())
        val xAtBottom = (a * raw.yEnd * raw.yEnd + b * raw.yEnd + c).coerceIn(0f, w.toFloat())
        val dx = xAtBottom - xAtTop
        val dy = raw.yEnd - raw.yStart
        val angle = atan2(dy.toDouble(), dx.toDouble()).toFloat() * 180f / kotlin.math.PI.toFloat()
        val length = sqrt(dx * dx + dy * dy)
        return LaneLine(
            x1 = xAtTop, y1 = raw.yStart,
            x2 = xAtBottom, y2 = raw.yEnd,
            angle = angle, length = length,
            curvature = 2f * a, valid = true,
            polyA = a, polyB = b, polyC = c,
            yStart = raw.yStart, yEnd = raw.yEnd
        )
    }

    private fun observeBevWidth(pairWidth: Float, w: Int) {
        // Delegates to the unit-tested median logic; only mirrors the result
        // into the detector's own prior field.
        expectedBevWidth = egoLaneSelector.observeWidth(pairWidth, w)
    }

    private fun detectLaneWithHough(
        edges: IntArray,
        mask: IntArray,
        w: Int, h: Int,
        roiTop: Int, roiBottom: Int,
        isLeft: Boolean
    ): LaneLine? {
        val numWindows = LANE_WINDOWS
        val windowHeight = (roiBottom - roiTop) / numWindows

        val points = mutableListOf<Pair<Int, Int>>()

        val histogram = IntArray(w)
        val histStart = (roiBottom - windowHeight * 3).coerceAtLeast(roiTop)
        for (y in histStart until roiBottom) {
            for (x in 0 until w) {
                val e = edges[y * w + x]
                val m = mask[y * w + x]
                if (m > 0) histogram[x] += 3
                else if (e > 0) histogram[x] += 1
            }
        }

        val midX = w / 2
        val searchStart = if (isLeft) 0 else midX
        val searchEnd = if (isLeft) midX else w

        var peakX = if (isLeft) w / 4 else w * 3 / 4

        val expectedBev = if (isLeft) expectedLeftXBev else expectedRightXBev
        if (expectedBev > 0f) {
            peakX = expectedBev.toInt().coerceIn(searchStart, searchEnd - 1)
        }

        val windowWidth = w / 8
        var maxSum = 0
        for (i in searchStart until searchEnd - windowWidth) {
            var sum = 0
            for (j in 0 until windowWidth) {
                sum += histogram[i + j]
            }
            if (sum > maxSum) {
                maxSum = sum
                peakX = i + windowWidth / 2
            }
        }
        if (expectedBev > 0f) {
            val blended = peakX * 0.6f + expectedBev * 0.4f
            peakX = blended.toInt().coerceIn(searchStart, searchEnd - 1)
        }

        var currentX = peakX.toFloat()

        for (win in 0 until numWindows) {
            val winYLow = roiBottom - (win + 1) * windowHeight
            val winYHigh = roiBottom - win * windowHeight

            // Narrow fixed window with mask-weighted centroid (same as the
            // multi-candidate path): the old growing margin (up to 2x) let the
            // centroid get pulled onto the neighbouring lane's marking.
            val margin = max(8, (windowWidth * 0.75f).toInt())
            val winXLow = (currentX - margin).toInt().coerceIn(0, w - 1)
            val winXHigh = (currentX + margin).toInt().coerceIn(1, w)

            var sumW = 0.0
            var sumXW = 0.0
            var sumY = 0
            var count = 0

            for (y in winYLow until winYHigh) {
                for (x in winXLow until winXHigh) {
                    val weight = when {
                        mask[y * w + x] > 0 -> 3
                        edges[y * w + x] > 0 -> 1
                        else -> 0
                    }
                    if (weight > 0) {
                        sumW += weight
                        sumXW += x * weight
                        sumY += y
                        count++
                    }
                }
            }

            if (count > 8) {
                val newX = (sumXW / sumW).toFloat()
                val newY = (sumY.toFloat() / count).toInt()

                val maxJump = windowWidth * 0.35f
                if (abs(newX - currentX) < maxJump || points.isEmpty()) {
                    currentX = currentX * 0.3f + newX * 0.7f
                    points.add(Pair(currentX.toInt(), newY))
                }
            }
        }

        if (points.size < 4) {
            val fallbackX = if (isLeft) {
                expectedLeftXBev.takeIf { it > 0f } ?: (w * 0.2f)
            } else {
                expectedRightXBev.takeIf { it > 0f } ?: (w * 0.8f)
            }

            val lastValid = if (isLeft) lastLeftValid else lastRightValid
            return if (lastValid) {
                LaneLine(
                    x1 = fallbackX, y1 = roiTop.toFloat(),
                    x2 = fallbackX, y2 = roiBottom.toFloat(),
                    angle = 90f, length = (roiBottom - roiTop).toFloat(),
                    valid = false
                )
            } else null
        }

        // One centroid per window, so at most LANE_WINDOWS points — all of them
        // are fed to the fit (the previous 25-point cap was unreachable dead code).
        val validPoints = points

        // === Fit polynomial in BEV space (RANSAC-robust) ===
        // A failed fit must not fall back to Triple(0,0,0): that curve is x ≡ 0,
        // which still passes the "left lane" sanity check and produced a phantom
        // border-hugging lane plus bogus departure warnings.
        val bevFit = fitPolynomialRansac(validPoints) ?: return null
        val (aBev, bBev, cBev) = bevFit

        // === Project BEV curve back into camera/perspective space ===
        // Sample N points along the BEV curve, apply the inverse homography to each,
        // refit a polynomial in perspective coordinates. This is what the AR overlay
        // needs to render lanes *on the road* instead of as a top-down projection.
        val perspectiveSamples = sampleAndProjectToPerspective(
            aBev, bBev, cBev, w, h, yStartF = roiTop.toFloat(), yEndF = roiBottom.toFloat()
        )

        // Never use BEV coefficients as camera-space coefficients. If the inverse
        // projection did not provide enough usable points, this detection is not
        // safe to render or use for departure decisions.
        if (perspectiveSamples.size < 3) return null
        val (aRaw, bRaw, cRaw) = if (perspectiveSamples.size >= 5) {
            solvePolyFromFloat(perspectiveSamples) ?: fitLinear(perspectiveSamples) ?: return null
        } else {
            fitLinear(perspectiveSamples) ?: return null
        }

        val perspectiveYStart = perspectiveSamples.first().second.coerceIn(0f, h.toFloat())
        val perspectiveYEnd = perspectiveSamples.last().second.coerceIn(0f, h.toFloat())
        if (perspectiveYEnd - perspectiveYStart < 2f) return null

        // === Kalman temporal smoothing ===
        // First valid frame on each side primes the filter; subsequent frames update.
        // Smoothing on polynomial coefficients (a, b, c) is more stable than smoothing
        // the line endpoints because coefficients are invariant to vertical translation
        // of the curve.
        val kfA = if (isLeft) leftCoeffA else rightCoeffA
        val kfB = if (isLeft) leftCoeffB else rightCoeffB
        val kfC = if (isLeft) leftCoeffC else rightCoeffC
        val sidePrimed = if (isLeft) leftPrimed else rightPrimed
        val a: Float
        val bCoef: Float
        val cCoef: Float
        if (!sidePrimed) {
            kfA.reset(aRaw); kfB.reset(bRaw); kfC.reset(cRaw)
            a = aRaw; bCoef = bRaw; cCoef = cRaw
            if (isLeft) leftPrimed = true else rightPrimed = true
            if (leftPrimed && rightPrimed) kalmanPrimed = true
        } else {
            a = kfA.update(aRaw)
            bCoef = kfB.update(bRaw)
            cCoef = kfC.update(cRaw)
        }

        val yStartF = perspectiveYStart
        val yEndF = perspectiveYEnd
        fun polyX(y: Float): Float = a * y * y + bCoef * y + cCoef

        val xAtTop = polyX(yStartF).coerceIn(0f, w.toFloat())
        val xAtBottom = polyX(yEndF).coerceIn(0f, w.toFloat())

        val isValidLane = when {
            isLeft -> xAtBottom < midX - windowWidth / 4 && xAtTop < midX
            else -> xAtBottom > midX + windowWidth / 4 && xAtTop > midX
        }

        if (!isValidLane) {
            val lastValid = if (isLeft) lastLeftValid else lastRightValid
            return if (lastValid) {
                val fallbackX = if (isLeft) w * 0.25f else w * 0.75f
                LaneLine(
                    x1 = fallbackX, y1 = yStartF,
                    x2 = fallbackX, y2 = yEndF,
                    angle = 90f, length = yEndF - yStartF,
                    valid = false
                )
            } else null
        }

        val dx = xAtBottom - xAtTop
        val dy = yEndF - yStartF
        val angle = atan2(dy.toDouble(), dx.toDouble()).toFloat() * 180f / kotlin.math.PI.toFloat()
        val length = sqrt(dx * dx + dy * dy)

        return LaneLine(
            x1 = xAtTop, y1 = yStartF,
            x2 = xAtBottom, y2 = yEndF,
            angle = angle, length = length,
            curvature = 2f * a,
            valid = true,
            polyA = a, polyB = bCoef, polyC = cCoef,
            yStart = yStartF, yEnd = yEndF
        )
    }

    private fun calculateConfidence(
        leftValid: Boolean, rightValid: Boolean,
        mask: IntArray, w: Int, h: Int, roiTop: Int
    ): Float {
        if (!leftValid && !rightValid) return 0.15f
        
        var maskCount = 0
        var totalPixels = 0
        
        for (y in roiTop until h) {
            for (x in 0 until w) {
                totalPixels++
                if (mask[y * w + x] > 0) maskCount++
            }
        }
        
        val baseConf = when {
            leftValid && rightValid -> 0.88f
            leftValid || rightValid -> 0.6f
            else -> 0.15f
        }
        
        val maskRatio = maskCount.toFloat() / totalPixels.coerceAtLeast(1)
        val maskFactor = (maskRatio * 6f).coerceIn(0f, 1f)
        
        return (baseConf * 0.8f + maskFactor * 0.2f).coerceIn(0.15f, 0.95f)
    }

    private fun scaleLine(line: LaneLine, scaleX: Float, scaleY: Float): LaneLine {
        val nx1 = line.x1 * scaleX
        val ny1 = line.y1 * scaleY
        val nx2 = line.x2 * scaleX
        val ny2 = line.y2 * scaleY
        val dx = nx2 - nx1
        val dy = ny2 - ny1
        val newLength = sqrt(dx * dx + dy * dy)
        // For the polynomial x = a·y² + b·y + c, when we scale (x,y) → (x·sX, y·sY),
        // substituting y = y'/sY and multiplying through by sX gives:
        //   x' = (a·sX/sY²)·y'² + (b·sX/sY)·y' + c·sX
        val sYs = scaleY * scaleY
        return LaneLine(
            x1 = nx1,
            y1 = ny1,
            x2 = nx2,
            y2 = ny2,
            angle = line.angle,
            length = newLength,
            // curvature ≈ 2·a; under x → sX·x, y → sY·y it scales as (sX / sY²)
            curvature = line.curvature * scaleX / sYs,
            valid = line.valid,
            polyA = line.polyA * scaleX / sYs,
            polyB = line.polyB * scaleX / scaleY,
            polyC = line.polyC * scaleX,
            yStart = line.yStart * scaleY,
            yEnd = line.yEnd * scaleY
        )
    }

    private fun gaussianBlur5x5(src: IntArray, dst: IntArray, w: Int, h: Int): IntArray {
        val kernel = intArrayOf(1, 4, 6, 4, 1, 4, 16, 24, 16, 4, 6, 24, 36, 24, 6, 4, 16, 24, 16, 4, 1, 4, 6, 4, 1)
        val kernelSum = 256

        for (y in 0 until h) {
            for (x in 0 until w) {
                if (y < 2 || y >= h - 2 || x < 2 || x >= w - 2) {
                    dst[y * w + x] = src[y * w + x]
                    continue
                }
                var sum = 0
                var ki = 0
                for (ky in -2..2) {
                    for (kx in -2..2) {
                        val srcIdx = (y + ky) * w + (x + kx)
                        sum += src[srcIdx] * kernel[ki]
                        ki++
                    }
                }
                dst[y * w + x] = (sum / kernelSum).coerceIn(0, 255)
            }
        }
        return dst
    }

    private fun fitPolynomial(points: List<Pair<Int, Int>>): Triple<Float, Float, Float>? {
        if (points.size < 5) return null
        val n = points.size
        var sumY = 0.0
        var sumY2 = 0.0
        var sumY3 = 0.0
        var sumY4 = 0.0
        var sumX = 0.0
        var sumXY = 0.0
        var sumXY2 = 0.0
        for ((x, y) in points) {
            val yd = y.toDouble()
            val xd = x.toDouble()
            sumY += yd
            sumY2 += yd * yd
            sumY3 += yd * yd * yd
            sumY4 += yd * yd * yd * yd
            sumX += xd
            sumXY += xd * yd
            sumXY2 += xd * yd * yd
        }
        val m = Array(3) { DoubleArray(3) }
        m[0][0] = n.toDouble();    m[0][1] = sumY;      m[0][2] = sumY2
        m[1][0] = sumY;           m[1][1] = sumY2;     m[1][2] = sumY3
        m[2][0] = sumY2;          m[2][1] = sumY3;     m[2][2] = sumY4
        val b = doubleArrayOf(sumX, sumXY, sumXY2)
        return solve3x3(m, b)?.let { sol ->
            val a = sol[2].toFloat()
            val bcoef = sol[1].toFloat()
            val ccoef = sol[0].toFloat()
            if (abs(a) > 0.5f) null else Triple(a, bcoef, ccoef)
        }
    }

    /**
     * RANSAC-style robust polynomial fit: x = a·y² + b·y + c, with y as independent
     * variable (lane pixels stack vertically in the image).
     *
     * Outliers (shadows, road markings, vehicles partially occluding the lane) are
     * rejected by sampling subsets, fitting, and keeping the model with the most
     * inliers. Final coefficients are re-estimated from the inliers via least squares.
     */
    private fun fitPolynomialRansac(points: List<Pair<Int, Int>>): Triple<Float, Float, Float>? {
        if (points.size < 4) return null

        val quadFull = fitPolynomial(points)
        // A quadratic needs at least 5 sample points; with fewer candidates the
        // 5-point draws degenerate (and solvePolyFromFloat then returns null).
        val quadSupported = quadFull != null && abs(quadFull.first) > 1e-6f && points.size >= 10
        val subsetSize = if (quadSupported) 5 else 4
        val n = points.size
        val maxIterations = 40
        val inlierThreshold = 6f  // pixels
        val rng = ransacRandom

        var bestInliers: List<Pair<Float, Float>> = emptyList()
        var bestCoef: Triple<Float, Float, Float>? = null

        repeat(maxIterations) {
            val subset = if (n <= subsetSize) {
                points.map { Pair(it.first.toFloat(), it.second.toFloat()) }
            } else {
                val picked = HashSet<Int>(subsetSize)
                while (picked.size < subsetSize) picked.add(rng.nextInt(n))
                picked.map { points[it] }.map { Pair(it.first.toFloat(), it.second.toFloat()) }
            }

            val candidate: Triple<Float, Float, Float>? = if (quadSupported) {
                solvePolyFromFloat(subset)
            } else {
                fitLinear(subset)
            }
            if (candidate == null) return@repeat

            val inliers = points.mapNotNull { (x, y) ->
                val px = candidate.first * y * y + candidate.second * y + candidate.third
                if (abs(px - x) < inlierThreshold) Pair(x.toFloat(), y.toFloat()) else null
            }

            if (inliers.size > bestInliers.size) {
                bestInliers = inliers
                bestCoef = candidate
            }
        }

        return if (bestInliers.size >= 4) {
            if (quadSupported) solvePolyFromFloat(bestInliers) else fitLinear(bestInliers)
        } else bestCoef
    }

    private fun solvePolyFromFloat(points: List<Pair<Float, Float>>): Triple<Float, Float, Float>? {
        if (points.size < 4) return null
        val ints = points.map { Pair(it.first.toInt(), it.second.toInt()) }
        return fitPolynomial(ints)
    }

    /**
     * Simple linear regression: x = b·y + c (a=0). Solved via the normal equations.
     * Used as a building block for RANSAC and for refitting after inverse projection.
     */
    private fun fitLinear(points: List<Pair<Float, Float>>): Triple<Float, Float, Float>? {
        if (points.size < 3) return null
        val n = points.size
        var sumX = 0.0
        var sumY = 0.0
        var sumY2 = 0.0
        var sumXY = 0.0
        for ((x, y) in points) {
            sumX += x
            sumY += y
            sumY2 += y * y
            sumXY += x * y
        }
        val denom = n * sumY2 - sumY * sumY
        if (abs(denom) < 1.0) return null
        val b = ((n * sumXY - sumX * sumY) / denom).toFloat()
        val c = ((sumX - b * sumY) / n).toFloat()
        return Triple(0f, b, c)
    }

    /**
     * Sample N evenly-spaced points along the BEV curve x = a·y² + b·y + c, then
     * apply the inverse homography to each. The result is the same lane shape,
     * expressed in the original camera/perspective frame — exactly what the AR
     * overlay needs to draw the lane *on the road*.
     */
    private fun sampleAndProjectToPerspective(
        a: Float, b: Float, c: Float,
        w: Int, h: Int,
        yStartF: Float, yEndF: Float,
        samples: Int = 24
    ): List<Pair<Float, Float>> {
        if (yEndF <= yStartF) return emptyList()
        if (!inverseMatrixValid) return emptyList()
        val out = ArrayList<Pair<Float, Float>>(samples)
        val dy = (yEndF - yStartF) / (samples - 1).coerceAtLeast(1)
        for (i in 0 until samples) {
            val y = yStartF + i * dy
            val xBev = a * y * y + b * y + c
            val (px, py) = mapBevToPerspective(xBev, y)
            // Reject points that fell outside the source image bounds. The old
            // ±1000 px guard let wildly off-frame points through, where the later
            // coerceIn(0, w) clamped them onto a border and faked a lane.
            if (px.isFinite() && py.isFinite() &&
                px >= -0.25f * w && px <= 1.25f * w &&
                py >= -0.25f * h && py <= 1.25f * h
            ) {
                out.add(Pair(px, py))
            }
        }
        return out
    }

    private fun solve3x3(m: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = 3
        val aug = Array(n) { DoubleArray(n + 1) }
        for (i in 0 until n) {
            for (j in 0 until n) aug[i][j] = m[i][j]
            aug[i][n] = b[i]
        }
        for (col in 0 until n) {
            var maxRow = col
            for (row in col + 1 until n) {
                if (abs(aug[row][col]) > abs(aug[maxRow][col])) maxRow = row
            }
            val temp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = temp
            if (abs(aug[col][col]) < 1e-9) return null
            for (row in col + 1 until n) {
                val factor = aug[row][col] / aug[col][col]
                for (j in col until n + 1) aug[row][j] -= factor * aug[col][j]
            }
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            x[i] = aug[i][n]
            for (j in i + 1 until n) x[i] -= aug[i][j] * x[j]
            x[i] /= aug[i][i]
        }
        return x
    }

    private fun calculateCenterOffset(leftLane: LaneLine?, rightLane: LaneLine?, imgWidth: Int): Float {
        val vehicleCenter = imgWidth * vehicleCenterRatio
        
        val leftX = leftLane?.let { (it.x1 + it.x2) / 2f } 
            ?: (vehicleCenter - expectedLaneWidth / 2).takeIf { expectedLeftX <= 0 } ?: expectedLeftX
        val rightX = rightLane?.let { (it.x1 + it.x2) / 2f } 
            ?: (vehicleCenter + expectedLaneWidth / 2).takeIf { expectedRightX <= 0 } ?: expectedRightX
        
        val laneCenter = (leftX + rightX) / 2f
        return vehicleCenter - laneCenter
    }

    private fun calculateLaneWidth(leftLane: LaneLine?, rightLane: LaneLine?): Float {
        if (leftLane == null || rightLane == null) return expectedLaneWidth
        
        val yRatio = 0.75f
        val leftX = leftLane.x1 + (leftLane.x2 - leftLane.x1) * yRatio
        val rightX = rightLane.x1 + (rightLane.x2 - rightLane.x1) * yRatio
        
        return (rightX - leftX).coerceIn(80f, 500f)
    }

    private fun smoothLane(
        current: LaneLine?,
        previous: LaneLine?,
        history: ArrayDeque<Float>
    ): LaneLine? {
        if (current == null) {
            if (previous != null && history.isNotEmpty()) {
                val avgX = history.average().toFloat()
                return LaneLine(
                    x1 = avgX, y1 = previous.y1,
                    x2 = avgX, y2 = previous.y2,
                    angle = previous.angle,
                    length = previous.length,
                    curvature = previous.curvature,
                    polyA = previous.polyA, polyB = previous.polyB, polyC = previous.polyC,
                    yStart = previous.yStart, yEnd = previous.yEnd,
                    valid = false
                )
            }
            return null
        }

        val currentX = (current.x1 + current.x2) / 2f
        history.addLast(currentX)
        if (history.size > 8) history.removeFirst()

        if (previous == null) return current

        val baseAlpha = 0.7f
        // Anchor the span to the CURRENT measurement: the old code blended y1/y2
        // across frames, so a short curve evaluated at a stale longer span
        // extrapolated x = a·y²+b·y+c far off-frame (±1000px+) and the HUD /
        // offset followed the extrapolation instead of the visible marking.
        val y1 = current.y1
        val y2 = current.y2

        // Smooth the fitted curve itself, then derive the endpoints from that one
        // polynomial. Smoothing endpoints and coefficients separately (as before)
        // left the overlay drawing a different lane than the offset logic used,
        // and the discarded polyA threw away all curvature.
        val polyA = current.polyA * baseAlpha + previous.polyA * (1f - baseAlpha)
        val polyB = current.polyB * baseAlpha + previous.polyB * (1f - baseAlpha)
        val polyC = current.polyC * baseAlpha + previous.polyC * (1f - baseAlpha)
        val x1 = polyA * y1 * y1 + polyB * y1 + polyC
        val x2 = polyA * y2 * y2 + polyB * y2 + polyC

        return LaneLine(
            x1 = x1,
            y1 = y1,
            x2 = x2,
            y2 = y2,
            angle = current.angle,
            length = current.length,
            curvature = 2f * polyA,
            valid = current.valid,
            polyA = polyA, polyB = polyB, polyC = polyC,
            yStart = y1, yEnd = y2
        )
    }

    private fun ensureCapacity(array: IntArray, size: Int): IntArray {
        return if (array.size >= size) array else IntArray(size)
    }
}
