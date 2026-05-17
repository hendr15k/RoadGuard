package com.roadguard.app.data.ml

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class LaneDetector(
    private val sensitivity: Float = 0.5f
) {
    data class LaneLine(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val angle: Float,
        val length: Float
    )

    data class LaneDetectionResult(
        val leftLane: LaneLine?,
        val rightLane: LaneLine?,
        val isDriftingLeft: Boolean,
        val isDriftingRight: Boolean,
        val confidence: Float
    )

    private val gaussKernel = intArrayOf(1, 2, 1, 2, 4, 2, 1, 2, 1)
    private val gaussDiv = 16

    private var lastLeftBaseX: Int? = null
    private var lastRightBaseX: Int? = null
    private var lastDrawInfo: MutableMap<String, Any>? = null

    fun detectLanes(bitmap: Bitmap, imageWidth: Int, imageHeight: Int): LaneDetectionResult {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val gray = IntArray(width * height)
        val rVec = FloatArray(width * height)
        val gVec = FloatArray(width * height)
        val bVec = FloatArray(width * height)
        for (i in pixels.indices) {
            val r = Color.red(pixels[i])
            val g = Color.green(pixels[i])
            val b = Color.blue(pixels[i])
            gray[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
            rVec[i] = r / 255f
            gVec[i] = g / 255f
            bVec[i] = b / 255f
        }

        val blurred = gaussianBlur(gray, width, height)

        val sobelX = sobelX(blurred, width, height)
        val edgeMag = gradientMagnitude(sobelX, width, height)
        val edgeBinary = adaptiveEdgeBinary(edgeMag, width, height)

        val whiteBinary = whiteLaneBinary(rVec, gVec, bVec, gray, width, height)

        val combined = IntArray(width * height) { if (edgeBinary[it] > 0 || whiteBinary[it] > 0) 255 else 0 }
        val dilated = dilate(combined, width, height)

        val roiMask = createRoiMask(width, height)
        val masked = IntArray(width * height) { if (roiMask[it] > 0) dilated[it] else 0 }

        val hist = computeHistogram(masked, width, height)
        val midX = width / 2

        val leftBaseX: Int
        val rightBaseX: Int
        val leftPeak = argMax(hist, 0, midX)
        val rightPeak = argMax(hist, midX, width - 1)

        if (hist[leftPeak] < 5 || hist[rightPeak] < 5) {
            return lastDrawInfo?.let { info ->
                val ll = info["leftLane"] as? LaneLine
                val rl = info["rightLane"] as? LaneLine
                LaneDetectionResult(ll, rl, false, false, 0.05f)
            } ?: LaneDetectionResult(null, null, false, false, 0.05f)
        }

        if (lastLeftBaseX != null) {
            val searchRange = 100
            val searchStart = (lastLeftBaseX!! - searchRange).coerceIn(0, midX - 1)
            val searchEnd = (lastLeftBaseX!! + searchRange).coerceIn(0, width - 1)
            leftBaseX = argMax(hist, searchStart, searchEnd) + searchStart
            lastLeftBaseX = null
        } else {
            leftBaseX = leftPeak
        }

        if (lastRightBaseX != null) {
            val searchRange = 100
            val searchStart = (lastRightBaseX!! - searchRange).coerceIn(midX, 0)
            val searchEnd = (lastRightBaseX!! + searchRange).coerceIn(midX, width - 1)
            rightBaseX = argMax(hist, searchStart, searchEnd) + searchStart
            lastRightBaseX = null
        } else {
            rightBaseX = rightPeak
        }

        val nWindows = 10
        val windowHeight = height / nWindows
        val margin = 60
        val minPix = 15
        val scaling = 15

        val nonzero = mutableListOf<Int>()
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (masked[y * width + x] > 0) {
                    nonzero.add(y * width + x)
                }
            }
        }

        if (nonzero.size < 100) {
            return lastDrawInfo?.let { info ->
                val ll = info["leftLane"] as? LaneLine
                val rl = info["rightLane"] as? LaneLine
                LaneDetectionResult(ll, rl, false, false, 0.05f)
            } ?: LaneDetectionResult(null, null, false, false, 0.05f)
        }

        val nonzeroy = nonzero.map { it / width }.toIntArray()
        val nonzerox = nonzero.map { it % width }.toIntArray()

        var currentLeftX = leftBaseX
        var currentRightX = rightBaseX

        val leftLaneInds = mutableListOf<IntArray>()
        val rightLaneInds = mutableListOf<IntArray>()

        for (iWindow in 0 until nWindows) {
            val winYLow = height - (iWindow + 1) * windowHeight
            val winYHigh = height - iWindow * windowHeight
            val boxMargin = margin + iWindow * scaling
            val winXLeftLow = currentLeftX - boxMargin
            val winXLeftHigh = currentLeftX + boxMargin
            val winXRightLow = currentRightX - boxMargin
            val winXRightHigh = currentRightX + boxMargin

            val leftIndices = mutableListOf<Int>()
            val rightIndices = mutableListOf<Int>()

            for (i in nonzeroy.indices) {
                val y = nonzeroy[i]
                val x = nonzerox[i]
                if (y in winYLow until winYHigh) {
                    if (x >= winXLeftLow && x < winXLeftHigh) leftIndices.add(i)
                    if (x >= winXRightLow && x < winXRightHigh) rightIndices.add(i)
                }
            }

            leftLaneInds.add(leftIndices.toIntArray())
            rightLaneInds.add(rightIndices.toIntArray())

            if (leftIndices.size > minPix) {
                currentLeftX = leftIndices.map { nonzerox[it] }.average().toInt()
                if (iWindow == 0) lastLeftBaseX = currentLeftX
            }
            if (rightIndices.size > minPix) {
                currentRightX = rightIndices.map { nonzerox[it] }.average().toInt()
                if (iWindow == 0) lastRightBaseX = currentRightX
            }
        }

        val flatLeftIndices = leftLaneInds.flatMap { it.toList() }.distinct()
        val flatRightIndices = rightLaneInds.flatMap { it.toList() }.distinct()

        val minLanePixels = max(height * 2, 100)
        if (flatLeftIndices.size < minLanePixels || flatRightIndices.size < minLanePixels) {
            return lastDrawInfo?.let { info ->
                val ll = info["leftLane"] as? LaneLine
                val rl = info["rightLane"] as? LaneLine
                LaneDetectionResult(ll, rl, false, false, 0.05f)
            } ?: LaneDetectionResult(null, null, false, false, 0.05f)
        }

        val leftXs = flatLeftIndices.map { nonzerox[it].toFloat() }
        val leftYs = flatLeftIndices.map { nonzeroy[it].toFloat() }
        val rightXs = flatRightIndices.map { nonzerox[it].toFloat() }
        val rightYs = flatRightIndices.map { nonzeroy[it].toFloat() }

        val leftFit = polyFit2D(leftYs.map { it.toInt() }.toIntArray(), leftXs.map { it.toInt() }.toIntArray())
        val rightFit = polyFit2D(rightYs.map { it.toInt() }.toIntArray(), rightXs.map { it.toInt() }.toIntArray())

        if (leftFit == null || rightFit == null) {
            return lastDrawInfo?.let { info ->
                val ll = info["leftLane"] as? LaneLine
                val rl = info["rightLane"] as? LaneLine
                LaneDetectionResult(ll, rl, false, false, 0.05f)
            } ?: LaneDetectionResult(null, null, false, false, 0.05f)
        }

        val plotY = (0 until height).map { it.toFloat() }
        val leftFitX = plotY.map { leftFit[0] * it * it + leftFit[1] * it + leftFit[2] }
        val rightFitX = plotY.map { rightFit[0] * it * it + rightFit[1] * it + rightFit[2] }

        val notCrossing = plotY.indices.all { leftFitX[it] <= rightFitX[it] }
        if (!notCrossing) {
            return lastDrawInfo?.let { info ->
                val ll = info["leftLane"] as? LaneLine
                val rl = info["rightLane"] as? LaneLine
                LaneDetectionResult(ll, rl, false, false, 0.05f)
            } ?: LaneDetectionResult(null, null, false, false, 0.05f)
        }

        val leftBottomX = leftFitX.last().coerceIn(0f, width.toFloat())
        val leftTopX = leftFitX.first().coerceIn(0f, width.toFloat())
        val rightBottomX = rightFitX.last().coerceIn(0f, width.toFloat())
        val rightTopX = rightFitX.first().coerceIn(0f, width.toFloat())

        val plausibleLeft = leftBottomX > width * 0.02f && leftBottomX < width * 0.5f
        val plausibleRight = rightBottomX < width * 0.98f && rightBottomX > width * 0.5f
        val laneWidth = rightBottomX - leftBottomX
        val plausibleWidth = laneWidth > width * 0.2f && laneWidth < width * 0.9f

        if (!plausibleLeft || !plausibleRight || !plausibleWidth) {
            return lastDrawInfo?.let { info ->
                val ll = info["leftLane"] as? LaneLine
                val rl = info["rightLane"] as? LaneLine
                LaneDetectionResult(ll, rl, false, false, 0.05f)
            } ?: LaneDetectionResult(null, null, false, false, 0.05f)
        }

        val leftLen = sqrt((leftBottomX - leftTopX).pow(2) + height.toFloat().pow(2))
        val leftAngle = atan2(height.toFloat(), leftBottomX - leftTopX)
        val rightLen = sqrt((rightBottomX - rightTopX).pow(2) + height.toFloat().pow(2))
        val rightAngle = atan2(height.toFloat(), rightBottomX - rightTopX)

        val leftLane = LaneLine(leftTopX, 0f, leftBottomX, height - 1f, leftAngle, leftLen)
        val rightLane = LaneLine(rightTopX, 0f, rightBottomX, height - 1f, rightAngle, rightLen)

        val driftThreshold = width * (0.15f - sensitivity * 0.1f)
        val centerX = width / 2f

        val isDriftingLeft = leftBottomX < centerX - driftThreshold
        val isDriftingRight = rightBottomX > centerX + driftThreshold

        val pxValidity = (flatLeftIndices.size + flatRightIndices.size).toFloat() / (nonzero.size + 1)
        val laneFitScore = min(laneWidth / (width * 0.5f), 1f)
        val confidence = (min(pxValidity * 1.5f, 1f) * 0.7f + laneFitScore * 0.3f).coerceIn(0.1f, 0.98f)

        val info = mutableMapOf<String, Any>()
        info["leftLane"] = leftLane
        info["rightLane"] = rightLane
        lastDrawInfo = info

        return LaneDetectionResult(leftLane, rightLane, isDriftingLeft, isDriftingRight, confidence)
    }

    fun detectLanesFromYUV(yData: ByteArray, width: Int, height: Int): LaneDetectionResult {
        val frame = IntArray(width * height) { yData[it].toInt() and 0xFF }
        return detectLanesFromFrame(frame, width, height)
    }

    private fun detectLanesFromFrame(frame: IntArray, width: Int, height: Int): LaneDetectionResult {
        val blurred = gaussianBlur(frame, width, height)
        val sobelX = sobelX(blurred, width, height)
        val edgeMag = gradientMagnitude(sobelX, width, height)
        val edgeBinary = adaptiveEdgeBinary(edgeMag, width, height)

        val gray = frame
        val rVec = FloatArray(width * height) { gray[it] / 255f }
        val gVec = FloatArray(width * height) { gray[it] / 255f }
        val bVec = FloatArray(width * height) { gray[it] / 255f }

        val whiteBinary = whiteLaneBinary(rVec, gVec, bVec, gray, width, height)
        val combined = IntArray(width * height) { if (edgeBinary[it] > 0 || whiteBinary[it] > 0) 255 else 0 }
        val dilated = dilate(combined, width, height)

        val roiMask = createRoiMask(width, height)
        val masked = IntArray(width * height) { if (roiMask[it] > 0) dilated[it] else 0 }

        val hist = computeHistogram(masked, width, height)
        val midX = width / 2
        val leftPeak = argMax(hist, 0, midX)
        val rightPeak = argMax(hist, midX, width - 1)

        if (hist[leftPeak] < 3 || hist[rightPeak] < 3) {
            return lastDrawInfo?.let { info ->
                val ll = info["leftLane"] as? LaneLine
                val rl = info["rightLane"] as? LaneLine
                LaneDetectionResult(ll, rl, false, false, 0.05f)
            } ?: LaneDetectionResult(null, null, false, false, 0.05f)
        }

        val leftBaseX: Int
        val rightBaseX: Int

        if (lastLeftBaseX != null) {
            val range = 100
            val s = (lastLeftBaseX!! - range).coerceIn(0, midX - 1)
            val e = (lastLeftBaseX!! + range).coerceIn(0, width - 1)
            leftBaseX = argMax(hist, s, e) + s
            lastLeftBaseX = null
        } else {
            leftBaseX = leftPeak
        }

        if (lastRightBaseX != null) {
            val range = 100
            val s = (lastRightBaseX!! - range).coerceIn(midX, 0)
            val e = (lastRightBaseX!! + range).coerceIn(midX, width - 1)
            rightBaseX = argMax(hist, s, e) + s
            lastRightBaseX = null
        } else {
            rightBaseX = rightPeak
        }

        val nWindows = 10
        val windowHeight = height / nWindows
        val margin = 60
        val minPix = 15
        val scaling = 15

        val nonzero = mutableListOf<Int>()
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (masked[y * width + x] > 0) nonzero.add(y * width + x)
            }
        }
        if (nonzero.size < 50) {
            return lastDrawInfo?.let { info ->
                val ll = info["leftLane"] as? LaneLine
                val rl = info["rightLane"] as? LaneLine
                LaneDetectionResult(ll, rl, false, false, 0.05f)
            } ?: LaneDetectionResult(null, null, false, false, 0.05f)
        }

        val nonzeroy = nonzero.map { it / width }.toIntArray()
        val nonzerox = nonzero.map { it % width }.toIntArray()

        var currentLeftX = leftBaseX
        var currentRightX = rightBaseX

        val leftLaneInds = mutableListOf<IntArray>()
        val rightLaneInds = mutableListOf<IntArray>()

        for (iWindow in 0 until nWindows) {
            val winYLow = height - (iWindow + 1) * windowHeight
            val winYHigh = height - iWindow * windowHeight
            val boxMargin = margin + iWindow * scaling
            val winXLeftLow = currentLeftX - boxMargin
            val winXLeftHigh = currentLeftX + boxMargin
            val winXRightLow = currentRightX - boxMargin
            val winXRightHigh = currentRightX + boxMargin

            val leftIndices = mutableListOf<Int>()
            val rightIndices = mutableListOf<Int>()

            for (i in nonzeroy.indices) {
                val y = nonzeroy[i]
                val x = nonzerox[i]
                if (y in winYLow until winYHigh) {
                    if (x >= winXLeftLow && x < winXLeftHigh) leftIndices.add(i)
                    if (x >= winXRightLow && x < winXRightHigh) rightIndices.add(i)
                }
            }

            leftLaneInds.add(leftIndices.toIntArray())
            rightLaneInds.add(rightIndices.toIntArray())

            if (leftIndices.size > minPix) {
                currentLeftX = leftIndices.map { nonzerox[it] }.average().toInt()
                if (iWindow == 0) lastLeftBaseX = currentLeftX
            }
            if (rightIndices.size > minPix) {
                currentRightX = rightIndices.map { nonzerox[it] }.average().toInt()
                if (iWindow == 0) lastRightBaseX = currentRightX
            }
        }

        val flatLeftIndices = leftLaneInds.flatMap { it.toList() }.distinct()
        val flatRightIndices = rightLaneInds.flatMap { it.toList() }.distinct()

        if (flatLeftIndices.size < 50 || flatRightIndices.size < 50) {
            return lastDrawInfo?.let { info ->
                val ll = info["leftLane"] as? LaneLine
                val rl = info["rightLane"] as? LaneLine
                LaneDetectionResult(ll, rl, false, false, 0.05f)
            } ?: LaneDetectionResult(null, null, false, false, 0.05f)
        }

        val leftXs = flatLeftIndices.map { nonzerox[it].toFloat() }
        val leftYs = flatLeftIndices.map { nonzeroy[it].toFloat() }
        val rightXs = flatRightIndices.map { nonzerox[it].toFloat() }
        val rightYs = flatRightIndices.map { nonzeroy[it].toFloat() }

        val leftFit = polyFit2D(leftYs.map { it.toInt() }.toIntArray(), leftXs.map { it.toInt() }.toIntArray())
        val rightFit = polyFit2D(rightYs.map { it.toInt() }.toIntArray(), rightXs.map { it.toInt() }.toIntArray())

        if (leftFit == null || rightFit == null) {
            return LaneDetectionResult(null, null, false, false, 0.05f)
        }

        val plotY = (0 until height).map { it.toFloat() }
        val leftFitX = plotY.map { leftFit[0] * it * it + leftFit[1] * it + leftFit[2] }
        val rightFitX = plotY.map { rightFit[0] * it * it + rightFit[1] * it + rightFit[2] }

        val notCrossing = plotY.indices.all { leftFitX[it] <= rightFitX[it] }
        if (!notCrossing) {
            return lastDrawInfo?.let { info ->
                val ll = info["leftLane"] as? LaneLine
                val rl = info["rightLane"] as? LaneLine
                LaneDetectionResult(ll, rl, false, false, 0.05f)
            } ?: LaneDetectionResult(null, null, false, false, 0.05f)
        }

        val leftBottomX = leftFitX.last().coerceIn(0f, width.toFloat())
        val leftTopX = leftFitX.first().coerceIn(0f, width.toFloat())
        val rightBottomX = rightFitX.last().coerceIn(0f, width.toFloat())
        val rightTopX = rightFitX.first().coerceIn(0f, width.toFloat())

        val leftLen = sqrt((leftBottomX - leftTopX).pow(2) + height.toFloat().pow(2))
        val leftAngle = atan2(height.toFloat(), leftBottomX - leftTopX)
        val rightLen = sqrt((rightBottomX - rightTopX).pow(2) + height.toFloat().pow(2))
        val rightAngle = atan2(height.toFloat(), rightBottomX - rightTopX)

        val leftLane = LaneLine(leftTopX, 0f, leftBottomX, height - 1f, leftAngle, leftLen)
        val rightLane = LaneLine(rightTopX, 0f, rightBottomX, height - 1f, rightAngle, rightLen)

        val driftThreshold = width * (0.15f - sensitivity * 0.1f)
        val centerX = width / 2f

        val isDriftingLeft = leftBottomX < centerX - driftThreshold
        val isDriftingRight = rightBottomX > centerX + driftThreshold

        val totalPixels = flatLeftIndices.size + flatRightIndices.size
        val confidence = (totalPixels.coerceIn(0, 800) / 800f).coerceIn(0.05f, 0.95f)

        val info = mutableMapOf<String, Any>()
        info["leftLane"] = leftLane
        info["rightLane"] = rightLane
        lastDrawInfo = info

        return LaneDetectionResult(leftLane, rightLane, isDriftingLeft, isDriftingRight, confidence)
    }

    private fun sobelX(src: IntArray, w: Int, h: Int): FloatArray {
        val result = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val gx = (-1 * src[(y - 1) * w + (x - 1)] + 1 * src[(y - 1) * w + (x + 1)]
                        + -2 * src[y * w + (x - 1)] + 2 * src[y * w + (x + 1)]
                        + -1 * src[(y + 1) * w + (x - 1)] + 1 * src[(y + 1) * w + (x + 1)]).toFloat()
                result[y * w + x] = gx
            }
        }
        return result
    }

    private fun gradientMagnitude(gx: FloatArray, w: Int, h: Int): FloatArray {
        val result = FloatArray(w * h)
        for (i in gx.indices) {
            result[i] = abs(gx[i])
        }
        return result
    }

    private fun adaptiveEdgeBinary(mag: FloatArray, w: Int, h: Int): ByteArray {
        val sorted = mag.sortedDescending()
        val threshold = sorted[min(sorted.size * 5 / 100, sorted.size - 1)]
        val thresh = max(threshold, 30f)
        return ByteArray(w * h) { if (mag[it] > thresh) 255.toByte() else 0.toByte() }
    }

    private fun whiteLaneBinary(r: FloatArray, g: FloatArray, b: FloatArray, gray: IntArray, w: Int, h: Int): ByteArray {
        val bg = ByteArray(w * h)
        val grayMean = gray.average().toFloat()
        val grayStd = sqrt(gray.map { (it - grayMean).pow(2) }.average().toFloat())
        val minBright = (grayMean + grayStd * 0.3f).coerceIn(120f, 200f)
        for (i in 0 until w * h) {
            val maxRgb = maxOf(r[i], g[i], b[i])
            val minRgb = minOf(r[i], g[i], b[i])
            val saturation = if (maxRgb > 0f) (maxRgb - minRgb) / maxRgb else 0f
            if (gray[i] > minBright && saturation < 0.3f) {
                bg[i] = 255.toByte()
            }
        }
        return bg
    }

    private fun dilate(src: IntArray, w: Int, h: Int): IntArray {
        val result = IntArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var maxVal = 0
                for (ky in -1..1) {
                    for (kx in -1..1) {
                        if (src[(y + ky) * w + (x + kx)] > maxVal) maxVal = src[(y + ky) * w + (x + kx)]
                    }
                }
                result[y * w + x] = maxVal
            }
        }
        return result
    }

    private fun createRoiMask(w: Int, h: Int): IntArray {
        val mask = IntArray(w * h)
        val bottomHalf = h
        val leftX = (w * 0.05f).toInt()
        val rightX = (w * 0.95f).toInt()
        val topY = (h * 0.45f).toInt()
        val leftSlope = (w * 0.40f).toInt()
        val rightSlope = (w * 0.60f).toInt()
        for (y in topY until bottomHalf) {
            val progress = (y - topY).toFloat() / (bottomHalf - topY)
            val lx = (leftX + (leftSlope - leftX) * progress).toInt()
            val rx = (rightX - (rightX - rightSlope) * progress).toInt()
            for (x in lx until rx) {
                mask[y * w + x] = 255
            }
        }
        return mask
    }

    private fun gaussianBlur(image: IntArray, width: Int, height: Int): IntArray {
        val result = IntArray(width * height)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var sum = 0
                var ki = 0
                for (ky in -1..1) {
                    for (kx in -1..1) {
                        sum += image[(y + ky) * width + (x + kx)] * gaussKernel[ki]
                        ki++
                    }
                }
                result[y * width + x] = sum / gaussDiv
            }
        }
        return result
    }

    private fun applyThreshold(image: IntArray, width: Int, height: Int): IntArray {
        val thresh = (100 * sensitivity).toInt().coerceIn(80, 180)
        return IntArray(width * height) { if (image[it] > thresh) 255 else 0 }
    }

    private fun computeHistogram(binary: IntArray, width: Int, height: Int): IntArray {
        val hist = IntArray(width)
        for (x in 0 until width) {
            var sum = 0
            for (y in height / 2 until height) {
                sum += binary[y * width + x]
            }
            hist[x] = sum
        }
        return hist
    }

    private fun argMax(arr: IntArray, start: Int, end: Int): Int {
        val s = start.coerceIn(0, arr.size - 1)
        val e = end.coerceIn(0, arr.size - 1)
        val realStart = minOf(s, e)
        val realEnd = maxOf(s, e)
        var maxIdx = realStart
        var maxVal = arr[realStart]
        for (i in realStart + 1..realEnd) {
            if (arr[i] > maxVal) {
                maxVal = arr[i]
                maxIdx = i
            }
        }
        return maxIdx
    }

    private fun polyFit2D(y: IntArray, x: IntArray): FloatArray? {
        if (y.size < 3 || x.size < 3) return null
        val n = y.size.coerceAtMost(x.size).toFloat()
        var sumX = 0f; var sumX2 = 0f; var sumX3 = 0f; var sumX4 = 0f
        var sumY = 0f; var sumXY = 0f; var sumX2Y = 0f

        for (i in 0 until n.toInt()) {
            val xi = x[i].toFloat()
            val yi = y[i].toFloat()
            val xi2 = xi * xi
            val xi3 = xi2 * xi
            val xi4 = xi3 * xi
            sumX += xi; sumX2 += xi2; sumX3 += xi3; sumX4 += xi4
            sumY += yi; sumXY += xi * yi; sumX2Y += xi2 * yi
        }

        val det = n * (sumX2 * sumX4 - sumX3 * sumX3) -
                  sumX * (sumX * sumX4 - sumX2 * sumX3) +
                  sumX2 * (sumX * sumX3 - sumX2 * sumX2)

        if (abs(det) < 0.0001f) return null

        val a = (sumY * (sumX2 * sumX4 - sumX3 * sumX3) -
                sumX * (sumXY * sumX4 - sumX3 * sumX2Y) +
                sumX2 * (sumXY * sumX3 - sumX2 * sumX2Y)) / det

        val b = (n * (sumXY * sumX4 - sumX3 * sumX2Y) -
                sumY * (sumX * sumX4 - sumX2 * sumX3) +
                sumX2 * (sumX * sumX2Y - sumXY * sumX2)) / det

        val c = (n * (sumX2 * sumX2Y - sumXY * sumX3) -
                sumX * (sumX * sumX2Y - sumXY * sumX2) +
                sumY * (sumX * sumX3 - sumX2 * sumX2)) / det

        return floatArrayOf(a, b, c)
    }

    private fun warpPerspective(src: IntArray, w: Int, h: Int): IntArray {
        val dst = IntArray(w * h)
        val srcPts = floatArrayOf(
            w * 0.15f, h.toFloat(),
            w * 0.45f, h * 0.6f,
            w * 0.55f, h * 0.6f,
            w * 0.85f, h.toFloat()
        )
        val dstPts = floatArrayOf(
            0f, h.toFloat(),
            0f, 0f,
            w.toFloat(), 0f,
            w.toFloat(), h.toFloat()
        )

        val matrix = android.graphics.Matrix()
        matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)
        val m = FloatArray(9)
        matrix.getValues(m)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val denom = m[6] * x + m[7] * y + m[8]
                if (denom == 0f) continue
                val srcX = ((m[0] * x + m[1] * y + m[2]) / denom).toInt()
                val srcY = ((m[3] * x + m[4] * y + m[5]) / denom).toInt()
                if (srcX in 0 until w && srcY in 0 until h) {
                    dst[y * w + x] = src[srcY * w + srcX]
                }
            }
        }
        return dst
    }

    fun reset() {
        lastLeftBaseX = null
        lastRightBaseX = null
        lastDrawInfo = null
    }
}
