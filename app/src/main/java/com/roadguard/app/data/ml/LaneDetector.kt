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
import kotlin.math.tan

class LaneDetector(
    private val sensitivity: Float = 0.5f
) {
    data class LaneLine(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val angle: Float,
        val length: Float,
        val curvature: Float = 0f
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
    
    // Vehicle position (center of image)
    private val vehicleCenterRatio = 0.5f
    
    // Expected lane width in pixels at bottom of image
    private var expectedLaneWidth: Float = 300f

    fun detectLanes(bitmap: Bitmap, imageWidth: Int, imageHeight: Int): LaneDetectionResult {
        frameCounter++
        val startTime = System.currentTimeMillis()
        
        val targetW = min(bitmap.width, 640)
        val targetH = min(bitmap.height, 480)
        val scaleX = bitmap.width.toFloat() / targetW
        val scaleY = bitmap.height.toFloat() / targetH
        
        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        val pixels = IntArray(targetW * targetH)
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        if (scaled != bitmap) scaled.recycle()

        // Convert to grayscale
        val gray = IntArray(targetW * targetH)
        for (i in pixels.indices) {
            val r = Color.red(pixels[i])
            val g = Color.green(pixels[i])
            val b = Color.blue(pixels[i])
            gray[i] = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
        }

        // Apply perspective transform to get bird's eye view
        val birdEye = applyPerspectiveTransform(gray, targetW, targetH)
        val birdW = targetW
        val birdH = targetH

        // Detect edges
        val edges = detectEdges(birdEye, birdW, birdH)
        
        // Create ROI mask (bottom 60% of image)
        val roiTop = (birdH * 0.4).toInt()
        
        // Sliding window lane detection
        val leftLane = detectLaneWithSlidingWindow(edges, birdW, birdH, roiTop, isLeft = true)
        val rightLane = detectLaneWithSlidingWindow(edges, birdW, birdH, roiTop, isLeft = false)

        // Calculate confidence
        val leftValid = leftLane != null
        val rightValid = rightLane != null
        val confidence = calculateConfidence(leftValid, rightValid, edges, birdW, birdH)

        // Transform back to original image coordinates
        val leftOrig = leftLane?.let { transformToOriginal(it, scaleX, scaleY, bitmap.width, bitmap.height) }
        val rightOrig = rightLane?.let { transformToOriginal(it, scaleX, scaleY, bitmap.width, bitmap.height) }

        // Calculate center offset and lane width
        val centerOffset = calculateCenterOffset(leftOrig, rightOrig, bitmap.width)
        val laneWidth = calculateLaneWidth(leftOrig, rightOrig, bitmap.height)

        // Drift detection based on center offset
        val driftThreshold = bitmap.width * 0.08f * (1.5f - sensitivity)
        val isDriftingLeft = centerOffset < -driftThreshold
        val isDriftingRight = centerOffset > driftThreshold

        // Update expected lane width
        if (laneWidth > 100f) {
            expectedLaneWidth = expectedLaneWidth * 0.9f + laneWidth * 0.1f
        }

        // Temporal smoothing
        val smoothedLeft = smoothLane(leftOrig, prevLeftLine, scaleX, scaleY)
        val smoothedRight = smoothLane(rightOrig, prevRightLine, scaleX, scaleY)
        
        prevLeftLine = smoothedLeft
        prevRightLine = smoothedRight

        val elapsed = System.currentTimeMillis() - startTime
        if (frameCounter % 30 == 0) {
            Log.d("LaneDetector", "Frame $frameCounter: ${bitmap.width}x${bitmap.height} -> ${targetW}x${targetH}, " +
                    "left=${leftValid}, right=${rightValid}, conf=${"%.2f".format(confidence)}, " +
                    "offset=${"%.1f".format(centerOffset)}, width=${"%.0f".format(laneWidth)}, " +
                    "driftL=$isDriftingLeft, driftR=$isDriftingRight, ${elapsed}ms")
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
        
        val targetW = min(width, 640)
        val targetH = min(height, 480)
        val scaleX = width.toFloat() / targetW
        val scaleY = height.toFloat() / targetH
        val stepX = width / targetW
        val stepY = height / targetH

        // Convert YUV to grayscale
        val gray = IntArray(targetW * targetH)
        for (y in 0 until targetH) {
            for (x in 0 until targetW) {
                gray[y * targetW + x] = yData[(y * stepY) * width + (x * stepX)].toInt() and 0xFF
            }
        }

        // Apply perspective transform
        val birdEye = applyPerspectiveTransform(gray, targetW, targetH)
        val birdW = targetW
        val birdH = targetH

        // Detect edges
        val edges = detectEdges(birdEye, birdW, birdH)
        
        // ROI
        val roiTop = (birdH * 0.4).toInt()
        
        // Sliding window lane detection
        val leftLane = detectLaneWithSlidingWindow(edges, birdW, birdH, roiTop, isLeft = true)
        val rightLane = detectLaneWithSlidingWindow(edges, birdW, birdH, roiTop, isLeft = false)

        // Calculate confidence
        val leftValid = leftLane != null
        val rightValid = rightLane != null
        val confidence = calculateConfidence(leftValid, rightValid, edges, birdW, birdH)

        // Transform back
        val leftOrig = leftLane?.let { transformToOriginal(it, scaleX, scaleY, width, height) }
        val rightOrig = rightLane?.let { transformToOriginal(it, scaleX, scaleY, width, height) }

        // Calculate metrics
        val centerOffset = calculateCenterOffset(leftOrig, rightOrig, width)
        val laneWidth = calculateLaneWidth(leftOrig, rightOrig, height)

        // Drift detection
        val driftThreshold = width * 0.08f * (1.5f - sensitivity)
        val isDriftingLeft = centerOffset < -driftThreshold
        val isDriftingRight = centerOffset > driftThreshold

        if (laneWidth > 100f) {
            expectedLaneWidth = expectedLaneWidth * 0.9f + laneWidth * 0.1f
        }

        // Temporal smoothing
        val smoothedLeft = smoothLane(leftOrig, prevLeftLine, scaleX, scaleY)
        val smoothedRight = smoothLane(rightOrig, prevRightLine, scaleX, scaleY)
        
        prevLeftLine = smoothedLeft
        prevRightLine = smoothedRight

        val elapsed = System.currentTimeMillis() - startTime
        if (frameCounter % 30 == 0) {
            Log.d("LaneDetector", "Frame $frameCounter (YUV): ${width}x${height} -> ${targetW}x${targetH}, " +
                    "left=${leftValid}, right=${rightValid}, conf=${"%.2f".format(confidence)}, " +
                    "offset=${"%.1f".format(centerOffset)}, width=${"%.0f".format(laneWidth)}, " +
                    "driftL=$isDriftingLeft, driftR=$isDriftingRight, ${elapsed}ms")
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
    }

    private fun applyPerspectiveTransform(src: IntArray, w: Int, h: Int): IntArray {
        val dst = IntArray(w * h)
        
        // Define source points (trapezoid in original image)
        val srcPoints = arrayOf(
            floatArrayOf(w * 0.45f, h * 0.6f),  // Top-left
            floatArrayOf(w * 0.55f, h * 0.6f),  // Top-right
            floatArrayOf(w * 0.9f, h * 0.95f),  // Bottom-right
            floatArrayOf(w * 0.1f, h * 0.95f)   // Bottom-left
        )
        
        // Define destination points (rectangle)
        val dstPoints = arrayOf(
            floatArrayOf(w * 0.2f, 0f),
            floatArrayOf(w * 0.8f, 0f),
            floatArrayOf(w * 0.8f, h.toFloat()),
            floatArrayOf(w * 0.2f, h.toFloat())
        )
        
        // Compute perspective transform matrix (simplified)
        // For performance, use bilinear interpolation
        val matrix = computePerspectiveMatrix(srcPoints, dstPoints)
        
        for (y in 0 until h) {
            for (x in 0 until w) {
                // Apply inverse transform to find source pixel
                val denom = matrix[6] * x + matrix[7] * y + 1f
                val srcX = (matrix[0] * x + matrix[1] * y + matrix[2]) / denom
                val srcY = (matrix[3] * x + matrix[4] * y + matrix[5]) / denom
                
                if (srcX >= 0 && srcX < w - 1 && srcY >= 0 && srcY < h - 1) {
                    val x0 = srcX.toInt()
                    val y0 = srcY.toInt()
                    val fx = srcX - x0
                    val fy = srcY - y0
                    
                    val idx = y0 * w + x0
                    val val00 = src[idx]
                    val val01 = src[min(idx + 1, src.size - 1)]
                    val val10 = src[min(idx + w, src.size - 1)]
                    val val11 = src[min(idx + w + 1, src.size - 1)]
                    
                    dst[y * w + x] = (
                        val00 * (1 - fx) * (1 - fy) +
                        val01 * fx * (1 - fy) +
                        val10 * (1 - fx) * fy +
                        val11 * fx * fy
                    ).toInt().coerceIn(0, 255)
                }
            }
        }
        
        return dst
    }

    private fun computePerspectiveMatrix(src: Array<FloatArray>, dst: Array<FloatArray>): FloatArray {
        // Simplified perspective matrix calculation
        // Returns 8-element array [a,b,c,d,e,f,g,h] for:
        // x' = (ax + by + c) / (gx + hy + 1)
        // y' = (dx + ey + f) / (gx + hy + 1)
        
        val sx = (dst[1][0] - dst[0][0]) / (src[1][0] - src[0][0])
        val sy = (dst[3][1] - dst[0][1]) / (src[3][1] - src[0][1])
        
        return floatArrayOf(
            sx, 0f, dst[0][0] - src[0][0] * sx,
            0f, sy, dst[0][1] - src[0][1] * sy,
            0f, 0f
        )
    }

    private fun detectEdges(src: IntArray, w: Int, h: Int): IntArray {
        val edges = IntArray(w * h)
        val lowThresh = (20 + sensitivity * 20).toInt().coerceIn(15, 50)
        val highThresh = lowThresh * 2

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val gx = -src[(y-1)*w + (x-1)] + src[(y-1)*w + (x+1)]
                       -2*src[y*w + (x-1)]       + 2*src[y*w + (x+1)]
                       -src[(y+1)*w + (x-1)] + src[(y+1)*w + (x+1)]
                val gy = -src[(y-1)*w + (x-1)] - 2*src[(y-1)*w + x] - src[(y-1)*w + (x+1)]
                       +src[(y+1)*w + (x-1)] + 2*src[(y+1)*w + x] + src[(y+1)*w + (x+1)]
                val mag = sqrt((gx * gx + gy * gy).toDouble()).toInt()
                edges[y * w + x] = if (mag > highThresh) 255 else if (mag > lowThresh) 128 else 0
            }
        }
        return edges
    }

    private fun detectLaneWithSlidingWindow(
        edges: IntArray, w: Int, h: Int, 
        roiTop: Int, isLeft: Boolean
    ): LaneLine? {
        val numWindows = 12
        val windowHeight = (h - roiTop) / numWindows
        val margin = (w * 0.12f).toInt().coerceIn(30, 80)
        
        val points = mutableListOf<Pair<Int, Int>>()
        
        // Find starting point using histogram
        val hist = IntArray(w)
        for (y in h - windowHeight until h) {
            for (x in 0 until w) {
                if (edges[y * w + x] > 0) {
                    hist[x]++
                }
            }
        }
        
        val midX = w / 2
        val searchRange = if (isLeft) 0 until midX else midX until w
        var currentX = hist.slice(searchRange).indices.maxByOrNull { hist[if (isLeft) it else midX + it] } 
            ?: (if (isLeft) w / 4 else w * 3 / 4)
        
        if (!isLeft) currentX += midX
        
        // Sliding window
        for (win in 0 until numWindows) {
            val winYLow = h - (win + 1) * windowHeight
            val winYHigh = h - win * windowHeight
            
            val winXLow = max(0, currentX - margin)
            val winXHigh = min(w, currentX + margin)
            
            // Find non-zero pixels in window
            var sumX = 0
            var count = 0
            
            for (y in winYLow until winYHigh) {
                for (x in winXLow until winXHigh) {
                    if (edges[y * w + x] > 0) {
                        sumX += x
                        count++
                    }
                }
            }
            
            if (count > 3) {
                currentX = sumX / count
                points.add(Pair(currentX, (winYLow + winYHigh) / 2))
            }
        }
        
        if (points.size < 4) return null
        
        // Fit polynomial (2nd degree)
        val fit = fitPolynomial(points)
        
        // Calculate line endpoints
        val y1 = roiTop.toFloat()
        val y2 = h.toFloat()
        val x1 = evaluatePolynomial(fit, y1)
        val x2 = evaluatePolynomial(fit, y2)
        
        val dx = x2 - x1
        val dy = y2 - y1
        val angle = atan2(dy, dx) * 180f / kotlin.math.PI.toFloat()
        val length = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        
        return LaneLine(x1, y1, x2, y2, angle, length, curvature = fit.second)
    }

    private fun fitPolynomial(points: List<Pair<Int, Int>>): Triple<Float, Float, Float> {
        // Fit quadratic: x = a*y^2 + b*y + c
        val n = points.size.toFloat()
        val sumY = points.sumOf { it.second.toDouble() }
        val sumY2 = points.sumOf { (it.second * it.second).toDouble() }
        val sumY3 = points.sumOf { (it.second.toFloat().pow(3)).toDouble() }
        val sumY4 = points.sumOf { (it.second.toFloat().pow(4)).toDouble() }
        val sumX = points.sumOf { it.first.toDouble() }
        val sumXY = points.sumOf { (it.first * it.second).toDouble() }
        val sumXY2 = points.sumOf { (it.first * it.second * it.second).toDouble() }
        
        // Normal equations for least squares
        val det = n * sumY2 * sumY4 + sumY * sumY3 * sumY2 + sumY2 * sumY * sumY3 -
                  sumY2 * sumY2 * sumY2 - sumY * sumY * sumY4 - n * sumY3 * sumY3
        
        if (abs(det) < 1e-6) {
            // Linear fit if determinant is too small
            val b = (n * sumXY - sumY * sumX) / (n * sumY2 - sumY * sumY)
            val c = (sumX - b * sumY) / n
            return Triple(0f, b.toFloat(), c.toFloat())
        }
        
        val a = (sumX * sumY2 * sumY4 + sumXY * sumY3 * sumY2 + sumXY2 * sumY * sumY3 -
                 sumXY2 * sumY2 * sumY2 - sumXY * sumY * sumY4 - sumX * sumY3 * sumY3) / det
        
        val b = (n * sumXY * sumY4 + sumX * sumY3 * sumY2 + sumY2 * sumY * sumXY2 -
                 sumY2 * sumXY * sumY2 - sumX * sumY * sumY4 - n * sumY3 * sumXY2) / det
        
        val c = (n * sumY2 * sumXY2 + sumY * sumXY * sumY2 + sumY2 * sumX * sumY3 -
                 sumY2 * sumY2 * sumX - sumY * sumY * sumXY2 - n * sumXY * sumY3) / det
        
        return Triple(a.toFloat(), b.toFloat(), c.toFloat())
    }

    private fun evaluatePolynomial(coeffs: Triple<Float, Float, Float>, y: Float): Float {
        return coeffs.first * y * y + coeffs.second * y + coeffs.third
    }

    private fun transformToOriginal(
        line: LaneLine, scaleX: Float, scaleY: Float, origW: Int, origH: Int
    ): LaneLine {
        return LaneLine(
            x1 = line.x1 * scaleX,
            y1 = line.y1 * scaleY,
            x2 = line.x2 * scaleX,
            y2 = line.y2 * scaleY,
            angle = line.angle,
            length = line.length * scaleX,
            curvature = line.curvature / scaleX
        )
    }

    private fun calculateCenterOffset(leftLane: LaneLine?, rightLane: LaneLine?, imgWidth: Int): Float {
        if (leftLane == null && rightLane == null) return 0f
        
        val vehicleCenter = imgWidth * vehicleCenterRatio
        
        val leftX = leftLane?.let { (it.x1 + it.x2) / 2f } ?: (vehicleCenter - expectedLaneWidth / 2)
        val rightX = rightLane?.let { (it.x1 + it.x2) / 2f } ?: (vehicleCenter + expectedLaneWidth / 2)
        
        val laneCenter = (leftX + rightX) / 2f
        return vehicleCenter - laneCenter
    }

    private fun calculateLaneWidth(leftLane: LaneLine?, rightLane: LaneLine?, imgHeight: Int): Float {
        if (leftLane == null || rightLane == null) return expectedLaneWidth
        
        val y = imgHeight * 0.7f
        val leftX = (leftLane.x1 + (leftLane.x2 - leftLane.x1) * 0.7f)
        val rightX = (rightLane.x1 + (rightLane.x2 - rightLane.x1) * 0.7f)
        
        return rightX - leftX
    }

    private fun calculateConfidence(
        leftValid: Boolean, rightValid: Boolean, 
        edges: IntArray, w: Int, h: Int
    ): Float {
        if (!leftValid && !rightValid) return 0.15f
        
        val baseConf = when {
            leftValid && rightValid -> 0.85f
            leftValid || rightValid -> 0.5f
            else -> 0.15f
        }
        
        // Check edge density in ROI
        val roiTop = h * 0.5f
        var edgeCount = 0
        var totalPixels = 0
        
        for (y in roiTop.toInt() until h) {
            for (x in 0 until w) {
                totalPixels++
                if (edges[y * w + x] > 0) edgeCount++
            }
        }
        
        val edgeRatio = edgeCount.toFloat() / totalPixels
        val edgeFactor = (edgeRatio * 5f).coerceIn(0f, 1f)
        
        return (baseConf * 0.7f + edgeFactor * 0.3f).coerceIn(0.15f, 0.98f)
    }

    private fun smoothLane(
        current: LaneLine?, 
        previous: LaneLine?,
        scaleX: Float, 
        scaleY: Float
    ): LaneLine? {
        if (current == null) return previous
        if (previous == null) return current
        
        val alpha = 0.7f // Smoothing factor
        return LaneLine(
            x1 = current.x1 * alpha + previous.x1 * (1 - alpha),
            y1 = current.y1 * alpha + previous.y1 * (1 - alpha),
            x2 = current.x2 * alpha + previous.x2 * (1 - alpha),
            y2 = current.y2 * alpha + previous.y2 * (1 - alpha),
            angle = current.angle,
            length = current.length,
            curvature = current.curvature
        )
    }
}
