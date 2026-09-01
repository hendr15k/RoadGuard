package com.roadguard.app.data.ml

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class LaneDetector(
    private var sensitivity: Float = 0.5f
) {

    fun updateSensitivity(value: Float) {
        sensitivity = value.coerceIn(0f, 1f)
    }
    /** 4-tuple of floats — used for (a, b, c, points) when returning a fitted polynomial. */
    private data class Quad(
        val a: Float,
        val b: Float,
        val c: Float,
        val points: List<Pair<Float, Float>>
    )

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
    private var expectedLeftX: Float = 0f
    private var expectedRightX: Float = 0f
    
    private val leftXHistory = ArrayDeque<Float>(8)
    private val rightXHistory = ArrayDeque<Float>(8)
    
    private var lastLeftValid = false
    private var lastRightValid = false
    
    private val perspectiveMatrix = FloatArray(9)
    private val inversePerspectiveMatrix = FloatArray(9)
    private var inverseMatrixValid = false

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

    private val leftCoeffA = CoeffKalman(0f)
    private val leftCoeffB = CoeffKalman(0f)
    private val leftCoeffC = CoeffKalman(0f)
    private val rightCoeffA = CoeffKalman(0f)
    private val rightCoeffB = CoeffKalman(0f)
    private val rightCoeffC = CoeffKalman(0f)
    private var kalmanPrimed = false
    private var leftPrimed = false
    private var rightPrimed = false

    fun detectLanes(bitmap: Bitmap, imageWidth: Int, imageHeight: Int): LaneDetectionResult {
        frameCounter++
        val startTime = System.currentTimeMillis()

        val targetW = min(bitmap.width, 800)
        val targetH = min(bitmap.height, 600)
        val scaleX = bitmap.width.toFloat() / targetW
        val scaleY = bitmap.height.toFloat() / targetH

        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        val pixels = IntArray(targetW * targetH)
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        if (scaled != bitmap) scaled.recycle()

        val rawGray = IntArray(targetW * targetH)
        val hsv = Array(targetH) { FloatArray(targetW * 3) }

        for (i in pixels.indices) {
            val r = Color.red(pixels[i])
            val g = Color.green(pixels[i])
            val b = Color.blue(pixels[i])
            rawGray[i] = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)

            val maxVal = max(r.toFloat(), max(g.toFloat(), b.toFloat()))
            val minVal = min(r.toFloat(), min(g.toFloat(), b.toFloat()))
            val delta = maxVal - minVal

            hsv[i / targetW][(i % targetW) * 3] = when {
                delta < 1f -> 0f
                maxVal == r.toFloat() -> 60f * (((g - b) / delta) % 6f)
                maxVal == g.toFloat() -> 60f * ((b - r) / delta + 2f)
                else -> 60f * ((r - g) / delta + 4f)
            }
            if (hsv[i / targetW][(i % targetW) * 3] < 0) hsv[i / targetW][(i % targetW) * 3] += 360f
            hsv[i / targetW][(i % targetW) * 3 + 1] = if (maxVal > 0) delta / maxVal else 0f
            hsv[i / targetW][(i % targetW) * 3 + 2] = maxVal
        }

        val gray = gaussianBlur5x5(rawGray, targetW, targetH)
        lastBirdGray = gray  // Stash for adaptive VP detection in next computePerspectiveMatrix

        computePerspectiveMatrix(targetW, targetH)
        
        val birdEye = applyWarpPerspective(gray, targetW, targetH)
        val birdHsv = applyWarpPerspectiveHsv(hsv, targetW, targetH)
        
        val birdW = targetW
        val birdH = targetH

        val edges = detectEdges(birdEye, birdW, birdH)
        val roadMask = createRoadMask(birdEye, birdHsv, birdW, birdH)
        
        val roiTop = (birdH * 0.15).toInt()
        val roiBottom = birdH
        
        val leftLane = detectLaneWithHough(edges, roadMask, birdHsv, birdW, birdH, roiTop, roiBottom, isLeft = true)
        val rightLane = detectLaneWithHough(edges, roadMask, birdHsv, birdW, birdH, roiTop, roiBottom, isLeft = false)

        val leftValid = leftLane?.valid == true
        val rightValid = rightLane?.valid == true
        val confidence = calculateConfidence(leftValid, rightValid, roadMask, birdW, birdH, roiTop)

        val leftOrig = leftLane?.let { scaleLine(it, scaleX, scaleY) }
        val rightOrig = rightLane?.let { scaleLine(it, scaleX, scaleY) }

        val centerOffset = calculateCenterOffset(leftOrig, rightOrig, bitmap.width)
        val laneWidth = calculateLaneWidth(leftOrig, rightOrig, bitmap.width)

        val isDriftingLeft = centerOffset < -bitmap.width * 0.04f * (1.5f - sensitivity) && confidence > 0.4f
        val isDriftingRight = centerOffset > bitmap.width * 0.04f * (1.5f - sensitivity) && confidence > 0.4f

        if (laneWidth > 100f && laneWidth < 600f) {
            expectedLaneWidth = expectedLaneWidth * 0.9f + laneWidth * 0.1f
        }
        
        leftOrig?.let {
            val avgX = (it.x1 + it.x2) / 2f
            expectedLeftX = if (expectedLeftX > 0) expectedLeftX * 0.9f + avgX * 0.1f else avgX
        }
        rightOrig?.let {
            val avgX = (it.x1 + it.x2) / 2f
            expectedRightX = if (expectedRightX > 0) expectedRightX * 0.9f + avgX * 0.1f else avgX
        }

        val smoothedLeft = smoothLane(leftOrig, prevLeftLine, leftXHistory, isLeft = true)
        val smoothedRight = smoothLane(rightOrig, prevRightLine, rightXHistory, isLeft = false)
        
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

    fun detectLanesFromYUV(yData: ByteArray, width: Int, height: Int): LaneDetectionResult {
        frameCounter++
        val startTime = System.currentTimeMillis()

        val targetW = min(width, 800)
        val targetH = min(height, 600)
        val scaleX = width.toFloat() / targetW
        val scaleY = height.toFloat() / targetH
        val stepX = width / targetW
        val stepY = height / targetH

        val rawGray = IntArray(targetW * targetH)
        val hsv = Array(targetH) { FloatArray(targetW * 3) }

        for (y in 0 until targetH) {
            for (x in 0 until targetW) {
                val srcIdx = (y * stepY) * width + (x * stepX)
                val grayVal = yData[srcIdx].toInt() and 0xFF
                rawGray[y * targetW + x] = grayVal

                hsv[y][x * 3 + 2] = grayVal.toFloat()
                hsv[y][x * 3 + 1] = 0f
                hsv[y][x * 3] = 0f
            }
        }

        val gray = gaussianBlur5x5(rawGray, targetW, targetH)
        lastBirdGray = gray

        if (targetH >= 3 && targetW >= 3) {
            for (y in 1 until targetH - 1) {
                for (x in 1 until targetW - 1) {
                    val center = gray[y * targetW + x]
                    val right = gray[y * targetW + x + 1]
                    val down = gray[(y + 1) * targetW + x]
                    val diff = (abs(right - center) + abs(down - center)) / 2f
                    val localSat = (diff / 32f).coerceAtMost(1f)
                    if (localSat > hsv[y][x * 3 + 1]) {
                        hsv[y][x * 3 + 1] = localSat
                    }
                }
            }
        }

        computePerspectiveMatrix(targetW, targetH)
        
        val birdEye = applyWarpPerspective(gray, targetW, targetH)
        
        val birdW = targetW
        val birdH = targetH

        val edges = detectEdges(birdEye, birdW, birdH)
        val roadMask = createRoadMask(birdEye, null, birdW, birdH)
        
        val roiTop = (birdH * 0.15).toInt()
        val roiBottom = birdH
        
        val leftLane = detectLaneWithHough(edges, roadMask, null, birdW, birdH, roiTop, roiBottom, isLeft = true)
        val rightLane = detectLaneWithHough(edges, roadMask, null, birdW, birdH, roiTop, roiBottom, isLeft = false)

        val leftValid = leftLane?.valid == true
        val rightValid = rightLane?.valid == true
        val confidence = calculateConfidence(leftValid, rightValid, roadMask, birdW, birdH, roiTop)

        val leftOrig = leftLane?.let { scaleLine(it, scaleX, scaleY) }
        val rightOrig = rightLane?.let { scaleLine(it, scaleX, scaleY) }

        val centerOffset = calculateCenterOffset(leftOrig, rightOrig, width)
        val laneWidth = calculateLaneWidth(leftOrig, rightOrig, width)

        val isDriftingLeft = centerOffset < -width * 0.04f * (1.5f - sensitivity) && confidence > 0.4f
        val isDriftingRight = centerOffset > width * 0.04f * (1.5f - sensitivity) && confidence > 0.4f

        if (laneWidth > 100f && laneWidth < 600f) {
            expectedLaneWidth = expectedLaneWidth * 0.9f + laneWidth * 0.1f
        }
        
        leftOrig?.let {
            val avgX = (it.x1 + it.x2) / 2f
            expectedLeftX = if (expectedLeftX > 0) expectedLeftX * 0.9f + avgX * 0.1f else avgX
        }
        rightOrig?.let {
            val avgX = (it.x1 + it.x2) / 2f
            expectedRightX = if (expectedRightX > 0) expectedRightX * 0.9f + avgX * 0.1f else avgX
        }

        val smoothedLeft = smoothLane(leftOrig, prevLeftLine, leftXHistory, isLeft = true)
        val smoothedRight = smoothLane(rightOrig, prevRightLine, rightXHistory, isLeft = false)
        
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
            imageWidth = width,
            imageHeight = height
        )
    }

    fun reset() {
        prevLeftLine = null
        prevRightLine = null
        frameCounter = 0
        expectedLaneWidth = 300f
        expectedLeftX = 0f
        expectedRightX = 0f
        leftXHistory.clear()
        rightXHistory.clear()
        lastLeftValid = false
        lastRightValid = false
        leftCoeffA.reset(); leftCoeffB.reset(); leftCoeffC.reset()
        rightCoeffA.reset(); rightCoeffB.reset(); rightCoeffC.reset()
        kalmanPrimed = false
        leftPrimed = false
        rightPrimed = false
    }

    private fun computePerspectiveMatrix(w: Int, h: Int) {
        // Source points: trapezoid in the camera image defining the road region
        // Top edge is the vanishing-point horizon, bottom edge is the hood
        val vpY = detectVanishingPoint(w, h)
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
     * Estimate the vanishing-point y-coordinate by sampling horizontal gradient
     * distribution across image rows. The row with the most symmetric gradient
     * pattern is likely the horizon. Clamped to [0.35h, 0.65h] to avoid nonsense
     * on noisy frames.
     */
    private fun detectVanishingPoint(w: Int, h: Int): Float {
        val gray = lastBirdGray ?: return (h * 0.5f).coerceIn(h * 0.35f, h * 0.65f)
        val lo = (h * 0.35f).toInt()
        val hi = (h * 0.65f).toInt()
        val midX = w / 2
        var bestRow = (h * 0.5f).toInt()
        var bestScore = Float.MIN_VALUE

        for (y in lo until hi step 3) {
            var leftCount = 0
            var rightCount = 0
            for (x in 1 until w - 1) {
                val idx = y * w + x
                if (idx >= gray.size) continue
                if (gray[idx] != 0) {
                    if (x < midX) leftCount++ else rightCount++
                }
            }
            val symmetry = 1f - abs(leftCount - rightCount).toFloat() /
                max(leftCount + rightCount, 1)
            val density = (leftCount + rightCount).toFloat() / w
            val score = symmetry * density

            if (score > bestScore) {
                bestScore = score
                bestRow = y
            }
        }
        return bestRow.toFloat().coerceIn(h * 0.35f, h * 0.65f)
    }

    private var lastBirdGray: IntArray? = null

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
        val aug = Array(n) { i -> FloatArray(n + 1) }
        
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

    private fun applyWarpPerspective(src: IntArray, w: Int, h: Int): IntArray {
        val dst = IntArray(w * h)
        val m = perspectiveMatrix
        
        for (y in 0 until h) {
            for (x in 0 until w) {
                val wInv = m[6] * x + m[7] * y + m[8]
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

    private fun applyWarpPerspectiveHsv(src: Array<FloatArray>, w: Int, h: Int): Array<FloatArray> {
        val dst = Array(h) { FloatArray(w * 3) }
        val m = perspectiveMatrix
        
        for (y in 0 until h) {
            for (x in 0 until w) {
                val wInv = m[6] * x + m[7] * y + m[8]
                val srcX = (m[0] * x + m[1] * y + m[2]) / wInv
                val srcY = (m[3] * x + m[4] * y + m[5]) / wInv
                
                val x0 = srcX.toInt()
                val y0 = srcY.toInt()
                val fx = srcX - x0
                val fy = srcY - y0
                
                if (x0 >= 0 && x0 < w - 1 && y0 >= 0 && y0 < h - 1) {
                    for (c in 0 until 3) {
                        val idx00 = y0 * w * 3 + x0 * 3 + c
                        val idx10 = y0 * w * 3 + (x0 + 1) * 3 + c
                        val idx01 = (y0 + 1) * w * 3 + x0 * 3 + c
                        val idx11 = (y0 + 1) * w * 3 + (x0 + 1) * 3 + c
                        
                        val v00 = src[y0][x0 * 3 + c]
                        val v10 = src[y0][(x0 + 1) * 3 + c]
                        val v01 = src[y0 + 1][x0 * 3 + c]
                        val v11 = src[y0 + 1][(x0 + 1) * 3 + c]
                        
                        dst[y][x * 3 + c] = v00 * (1 - fx) * (1 - fy) +
                                            v10 * fx * (1 - fy) +
                                            v01 * (1 - fx) * fy +
                                            v11 * fx * fy
                    }
                }
            }
        }
        
        return dst
    }

    private fun detectEdges(src: IntArray, w: Int, h: Int): IntArray {
        val edges = IntArray(w * h)
        val lowThresh = (20 + sensitivity * 25).toInt().coerceIn(15, 50)
        val highThresh = lowThresh * 2

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val gx = -src[idx - w - 1] + src[idx - w + 1] - 2*src[idx - 1] + 2*src[idx + 1] - src[idx + w - 1] + src[idx + w + 1]
                val gy = -src[idx - w - 1] - 2*src[idx - w] - src[idx - w + 1] + src[idx + w - 1] + 2*src[idx + w] + src[idx + w + 1]
                val magSq = (gx * gx + gy * gy).toDouble()
                edges[idx] = when {
                    magSq > (highThresh * highThresh).toDouble() -> 255
                    magSq > (lowThresh * lowThresh).toDouble() -> 128
                    else -> 0
                }
            }
        }
        return edges
    }

    private fun createRoadMask(gray: IntArray, hsv: Array<FloatArray>?, w: Int, h: Int): IntArray {
        val mask = IntArray(w * h)

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

                var isLaneMarking = brightness > brightnessThresh

                if (hsv != null) {
                    val hue = hsv[y][x * 3]
                    val sat = hsv[y][x * 3 + 1]
                    val v = hsv[y][x * 3 + 2]

                    val isWhite = v > 150f && sat < 0.3f
                    val isYellow = hue > 30f && hue < 70f && sat > 0.2f && v > 100f
                    if (isWhite || isYellow) isLaneMarking = true
                }

                if (isLaneMarking) {
                    mask[idx] = 1
                    if (y > roiTop) mask[(y - 1) * w + x] = max(mask[(y - 1) * w + x], 1)
                    if (y < h - 1) mask[(y + 1) * w + x] = max(mask[(y + 1) * w + x], 1)
                }
            }
        }

        return mask
    }

    private fun detectLaneWithHough(
        edges: IntArray,
        mask: IntArray,
        hsv: Array<FloatArray>?,
        w: Int, h: Int,
        roiTop: Int, roiBottom: Int,
        isLeft: Boolean
    ): LaneLine? {
        val numWindows = 14
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

        val expected = if (isLeft) expectedLeftX else expectedRightX
        if (expected > 0) {
            peakX = expected.toInt().coerceIn(searchStart, searchEnd - 1)
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
        if (expected > 0) {
            val blended = peakX * 0.6f + expected * 0.4f
            peakX = blended.toInt().coerceIn(searchStart, searchEnd - 1)
        }

        var currentX = peakX.toFloat()

        for (win in 0 until numWindows) {
            val winYLow = roiBottom - (win + 1) * windowHeight
            val winYHigh = roiBottom - win * windowHeight

            val margin = (windowWidth * (1.0f + win * 0.1f)).toInt().coerceIn(windowWidth / 2, windowWidth * 2)
            val winXLow = (currentX - margin).toInt().coerceIn(0, w - 1)
            val winXHigh = (currentX + margin).toInt().coerceIn(1, w)

            var sumX = 0
            var sumY = 0
            var count = 0

            for (y in winYLow until winYHigh) {
                for (x in winXLow until winXHigh) {
                    if (mask[y * w + x] > 0 || edges[y * w + x] > 0) {
                        sumX += x
                        sumY += y
                        count++
                    }
                }
            }

            if (count > 8) {
                val newX = sumX.toFloat() / count
                val newY = sumY.toFloat() / count

                val maxJump = windowWidth * 0.7f
                if (abs(newX - currentX) < maxJump || points.isEmpty()) {
                    currentX = currentX * 0.3f + newX * 0.7f
                    points.add(Pair(currentX.toInt(), newY.toInt()))
                }
            }
        }

        if (points.size < 4) {
            val fallbackX = if (isLeft) {
                expectedLeftX.takeIf { it > 0 } ?: (w * 0.2f)
            } else {
                expectedRightX.takeIf { it > 0 } ?: (w * 0.8f)
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

        // Take the 25 most representative points (closest to bottom for ground calibration)
        val validPoints = if (points.size > 25) {
            points.sortedBy { it.second }.take(25)
        } else points

        // === Fit polynomial in BEV space (RANSAC-robust) ===
        val (aBev, bBev, cBev) = fitPolynomialRansac(validPoints) ?: Triple(0f, 0f, 0f)

        // === Project BEV curve back into camera/perspective space ===
        // Sample N points along the BEV curve, apply the inverse homography to each,
        // refit a polynomial in perspective coordinates. This is what the AR overlay
        // needs to render lanes *on the road* instead of as a top-down projection.
        val perspectiveSamples = sampleAndProjectToPerspective(
            aBev, bBev, cBev, yStartF = roiTop.toFloat(), yEndF = roiBottom.toFloat()
        )

        // Fall back to BEV-space points only when projection completely failed.
        val (aRaw, bRaw, cRaw) = if (perspectiveSamples.size >= 4) {
            fitLinear(perspectiveSamples) ?: Triple(0f, 0f, 0f)
        } else {
            Triple(aBev, bBev, cBev)
        }

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

        val yStartF = roiTop.toFloat()
        val yEndF = roiBottom.toFloat()
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
            curvature = bCoef,
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
            curvature = line.curvature / scaleX,
            valid = line.valid,
            polyA = line.polyA * scaleX / sYs,
            polyB = line.polyB * scaleX / scaleY,
            polyC = line.polyC * scaleX,
            yStart = line.yStart * scaleY,
            yEnd = line.yEnd * scaleY
        )
    }

    private fun gaussianBlur5x5(src: IntArray, w: Int, h: Int): IntArray {
        val dst = IntArray(w * h)
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

    private fun fitPolynomial(points: List<Pair<Int, Int>>, w: Int): Triple<Float, Float, Float>? {
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

        val n = points.size
        val maxIterations = 25
        val inlierThreshold = 6f  // pixels
        val subsetSize = 4
        val rng = java.util.Random()  // NOT seeded — jitter helps avoid degenerate draws

        var bestInliers: List<Pair<Float, Float>> = emptyList()
        var bestCoef: Triple<Float, Float, Float>? = null

        repeat(maxIterations) {
            // Sample subset
            val subset = if (n <= subsetSize) {
                points.map { Pair(it.first.toFloat(), it.second.toFloat()) }
            } else {
                val picked = HashSet<Int>(subsetSize)
                while (picked.size < subsetSize) picked.add(rng.nextInt(n))
                picked.map { points[it] }.map { Pair(it.first.toFloat(), it.second.toFloat()) }
            }

            val candidate = fitLinear(subset) ?: return@repeat

            // Count inliers across all points
            val inliers = points.mapNotNull { (x, y) ->
                val px = candidate.first * y * y + candidate.second * y + candidate.third
                if (abs(px - x) < inlierThreshold) Pair(x.toFloat(), y.toFloat()) else null
            }

            if (inliers.size > bestInliers.size) {
                bestInliers = inliers
                bestCoef = candidate
            }
        }

        // Refit using all inliers; fall back to first subset if RANSAC didn't help
        return if (bestInliers.size >= 4) fitLinear(bestInliers) else bestCoef
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
            // Reject points that fell outside the source image bounds
            if (px.isFinite() && py.isFinite() && px > -1e3f && py > -1e3f) {
                out.add(Pair(px, py))
            }
        }
        return out
    }

    private fun solve3x3(m: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = 3
        val aug = Array(n) { i -> DoubleArray(n + 1) }
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

    private fun calculateLaneWidth(leftLane: LaneLine?, rightLane: LaneLine?, imgWidth: Int): Float {
        if (leftLane == null || rightLane == null) return expectedLaneWidth
        
        val yRatio = 0.75f
        val leftX = leftLane.x1 + (leftLane.x2 - leftLane.x1) * yRatio
        val rightX = rightLane.x1 + (rightLane.x2 - rightLane.x1) * yRatio
        
        return (rightX - leftX).coerceIn(80f, 500f)
    }

    private fun smoothLane(
        current: LaneLine?,
        previous: LaneLine?,
        history: ArrayDeque<Float>,
        isLeft: Boolean
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

        val historyAvgX = if (history.size >= 3) history.average().toFloat() else currentX
        val historyWeight = if (history.size >= 3) 0.3f else 0f
        val baseAlpha = 0.7f
        val effectiveAlpha = baseAlpha * (1f - historyWeight) + historyWeight * 0.5f

        val smoothedX1 = current.x1 * effectiveAlpha + previous.x1 * (1f - effectiveAlpha)
        val smoothedX2 = current.x2 * effectiveAlpha + previous.x2 * (1f - effectiveAlpha)
        val x1 = smoothedX1 * (1f - historyWeight) + historyAvgX * historyWeight
        val x2 = smoothedX2 * (1f - historyWeight) + historyAvgX * historyWeight

        return LaneLine(
            x1 = x1,
            y1 = current.y1 * baseAlpha + previous.y1 * (1 - baseAlpha),
            x2 = x2,
            y2 = current.y2 * baseAlpha + previous.y2 * (1 - baseAlpha),
            angle = current.angle,
            length = current.length,
            curvature = current.curvature,
            valid = current.valid,
            polyA = current.polyA, polyB = current.polyB, polyC = current.polyC,
            yStart = current.yStart, yEnd = current.yEnd
        )
    }
}
