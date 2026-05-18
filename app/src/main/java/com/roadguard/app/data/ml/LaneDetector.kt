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
    private val sensitivity: Float = 0.5f
) {
    data class LaneLine(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val angle: Float,
        val length: Float,
        val curvature: Float = 0f,
        val valid: Boolean = true
    )

    data class LaneDetectionResult(
        val leftLane: LaneLine?,
        val rightLane: LaneLine?,
        val centerOffset: Float,
        val isDriftingLeft: Boolean,
        val isDriftingRight: Boolean,
        val confidence: Float,
        val laneWidth: Float
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

        val gray = IntArray(targetW * targetH)
        val hsv = Array(targetH) { FloatArray(targetW * 3) }
        
        for (i in pixels.indices) {
            val r = Color.red(pixels[i])
            val g = Color.green(pixels[i])
            val b = Color.blue(pixels[i])
            gray[i] = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
            
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
            laneWidth = laneWidth
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

        val gray = IntArray(targetW * targetH)
        val hsv = Array(targetH) { FloatArray(targetW * 3) }
        
        for (y in 0 until targetH) {
            for (x in 0 until targetW) {
                val srcIdx = (y * stepY) * width + (x * stepX)
                val grayVal = yData[srcIdx].toInt() and 0xFF
                gray[y * targetW + x] = grayVal
                
                hsv[y][x * 3 + 2] = grayVal.toFloat()
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
            laneWidth = laneWidth
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
    }

    private fun computePerspectiveMatrix(w: Int, h: Int) {
        val srcPoints = floatArrayOf(
            w * 0.42f, h * 0.55f,
            w * 0.58f, h * 0.55f,
            w * 0.95f, h * 0.98f,
            w * 0.05f, h * 0.98f
        )
        
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
                val mag = sqrt((gx * gx + gy * gy).toDouble()).toInt()
                edges[idx] = if (mag > highThresh) 255 else if (mag > lowThresh) 128 else 0
            }
        }
        return edges
    }

    private fun createRoadMask(gray: IntArray, hsv: Array<FloatArray>?, w: Int, h: Int): IntArray {
        val mask = IntArray(w * h)
        
        val roiTop = (h * 0.05).toInt()
        
        val roadPixels = mutableListOf<Int>()
        for (y in roiTop until h) {
            for (x in 0 until w) {
                roadPixels.add(gray[y * w + x])
            }
        }
        
        if (roadPixels.isEmpty()) return mask
        
        val sorted = roadPixels.sorted()
        val median = sorted[sorted.size / 2]
        val p95 = sorted[(sorted.size * 0.95).toInt()]
        
        val brightnessThresh = ((median + p95) / 2).toInt().coerceIn(100, 200)
        
        for (y in roiTop until h) {
            val rowProgress = (y - roiTop).toFloat() / (h - roiTop)
            
            val leftEdge = (w * (0.08f + 0.1f * rowProgress)).toInt()
            val rightEdge = (w * (0.92f - 0.1f * rowProgress)).toInt()
            
            for (x in leftEdge until rightEdge) {
                val idx = y * w + x
                val brightness = gray[idx]
                
                var isLaneMarking = brightness > brightnessThresh
                
                if (hsv != null) {
                    val h = hsv[y][x * 3]
                    val s = hsv[y][x * 3 + 1]
                    val v = hsv[y][x * 3 + 2]
                    
                    val isWhiteOrYellow = (v > 150f && s < 0.3f) || (h > 30f && h < 70f && s > 0.2f && v > 100f)
                    if (isWhiteOrYellow) isLaneMarking = true
                }
                
                if (brightness > brightnessThresh) {
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
        val numWindows = 12
        val windowHeight = (roiBottom - roiTop) / numWindows
        
        val points = mutableListOf<Pair<Int, Int>>()
        
        val histogram = IntArray(w)
        for (y in roiBottom - windowHeight * 2 until roiBottom) {
            for (x in 0 until w) {
                if (mask[y * w + x] > 0 || edges[y * w + x] > 0) {
                    histogram[x] += 2
                }
            }
        }
        
        val midX = w / 2
        val searchStart = if (isLeft) 0 else midX
        val searchEnd = if (isLeft) midX else w
        
        var peakX = if (isLeft) w / 4 else w * 3 / 4
        
        if (expectedLeftX > 0 || expectedRightX > 0) {
            val expected = if (isLeft) expectedLeftX else expectedRightX
            if (expected > 0) {
                peakX = expected.toInt().coerceIn(searchStart, searchEnd - 1)
            }
        }
        
        val windowWidth = w / 6
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
        
        var currentX = peakX.toFloat()
        
        for (win in 0 until numWindows) {
            val winYLow = roiBottom - (win + 1) * windowHeight
            val winYHigh = roiBottom - win * windowHeight
            val winYCenter = (winYLow + winYHigh) / 2
            
            val margin = (windowWidth * (1.2f + win * 0.15f)).toInt().coerceIn(windowWidth / 2, windowWidth * 2)
            val winXLow = (currentX - margin).toInt().coerceIn(0, w - 1)
            val winXHigh = (currentX + margin).toInt().coerceIn(1, w)
            
            var sumX = 0
            var sumY = 0
            var count = 0
            
            for (y in winYLow until winYHigh) {
                for (x in winXLow until winXHigh) {
                    if (mask[y * w + x] > 0) {
                        sumX += x
                        sumY += y
                        count++
                    }
                }
            }
            
            if (count > 10) {
                val newX = sumX.toFloat() / count
                val newY = sumY.toFloat() / count
                
                val maxJump = windowWidth * 0.8f
                if (abs(newX - currentX) < maxJump || points.isEmpty()) {
                    currentX = currentX * 0.2f + newX * 0.8f
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
            
            return if (lastLeftValid || lastRightValid) {
                LaneLine(fallbackX, roiTop.toFloat(), fallbackX, roiBottom.toFloat(), 90f, (roiBottom - roiTop).toFloat())
            } else null
        }
        
        val validPoints = if (points.size > 20) {
            points.sortedByDescending { it.second }.take(20)
        } else points
        
        val n = validPoints.size
        var sumX = 0.0
        var sumY = 0.0
        var sumX2 = 0.0
        var sumXY = 0.0
        var sumY2 = 0.0
        
        for ((x, y) in validPoints) {
            sumX += x
            sumY += y
            sumX2 += x * x.toDouble()
            sumXY += x * y.toDouble()
            sumY2 += y * y.toDouble()
        }
        
        val denom = n * sumY2 - sumY * sumY
        if (abs(denom) < 1.0) return null
        
        val b = ((n * sumXY - sumX * sumY) / denom).toFloat()
        val c = ((sumX - b * sumY) / n).toFloat()
        
        val xAtTop = (b * roiTop + c).coerceIn(0f, w.toFloat())
        val xAtBottom = (b * roiBottom + c).coerceIn(0f, w.toFloat())
        
        val isValidLane = when {
            isLeft -> xAtBottom < midX - windowWidth / 3 && xAtTop < midX
            else -> xAtBottom > midX + windowWidth / 3 && xAtTop > midX
        }
        
        if (!isValidLane) {
            return if (lastLeftValid || lastRightValid) {
                val fallbackX = if (isLeft) w * 0.25f else w * 0.75f
                LaneLine(fallbackX, roiTop.toFloat(), fallbackX, roiBottom.toFloat(), 90f, (roiBottom - roiTop).toFloat(), valid = false)
            } else null
        }
        
        val dx = xAtBottom - xAtTop
        val dy = (roiBottom - roiTop).toFloat()
        val angle = atan2(dy.toDouble(), dx.toDouble()).toFloat() * 180f / kotlin.math.PI.toFloat()
        val length = sqrt(dx * dx + dy * dy)
        
        return LaneLine(xAtTop, roiTop.toFloat(), xAtBottom, roiBottom.toFloat(), angle, length, curvature = b)
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
        return LaneLine(
            x1 = line.x1 * scaleX,
            y1 = line.y1 * scaleY,
            x2 = line.x2 * scaleX,
            y2 = line.y2 * scaleY,
            angle = line.angle,
            length = line.length * scaleX,
            curvature = line.curvature / scaleX,
            valid = line.valid
        )
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
                    valid = false
                )
            }
            return null
        }
        
        val currentX = (current.x1 + current.x2) / 2f
        history.addLast(currentX)
        if (history.size > 8) history.removeFirst()
        
        if (previous == null) return current
        
        val alpha = 0.7f
        return LaneLine(
            x1 = current.x1 * alpha + previous.x1 * (1 - alpha),
            y1 = current.y1 * alpha + previous.y1 * (1 - alpha),
            x2 = current.x2 * alpha + previous.x2 * (1 - alpha),
            y2 = current.y2 * alpha + previous.y2 * (1 - alpha),
            angle = current.angle,
            length = current.length,
            curvature = current.curvature,
            valid = current.valid
        )
    }
}
