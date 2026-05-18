package com.roadguard.app.data.ml

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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

    private var prevLeftX: Float? = null
    private var prevRightX: Float? = null

    fun detectLanes(bitmap: Bitmap, imageWidth: Int, imageHeight: Int): LaneDetectionResult {
        val targetW = min(bitmap.width, 480)
        val targetH = min(bitmap.height, 320)
        val scaleX = bitmap.width.toFloat() / targetW
        val scaleY = bitmap.height.toFloat() / targetH
        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        val pixels = IntArray(targetW * targetH)
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        if (scaled != bitmap) scaled.recycle()

        val gray = IntArray(targetW * targetH)
        for (i in pixels.indices) {
            val r = Color.red(pixels[i])
            val g = Color.green(pixels[i])
            val b = Color.blue(pixels[i])
            gray[i] = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
        }

        val edges = detectEdges(gray, targetW, targetH)
        val mask = createLaneMask(gray, edges, targetW, targetH)

        val hist = IntArray(targetW)
        var maskSum = 0
        for (y in targetH / 2 until targetH) {
            for (x in 0 until targetW) {
                hist[x] += mask[y * targetW + x]
                maskSum += mask[y * targetW + x]
            }
        }

        val midX = targetW / 2
        val leftHistPeak = hist.slice(0 until midX).indices.maxByOrNull { hist[it] } ?: (midX / 3)
        val rightHistPeak = hist.slice(midX until targetW).indices.maxByOrNull { hist[midX + it] }?.let { midX + it } ?: (midX * 5 / 3)

        val leftSum = hist[leftHistPeak]
        val rightSum = hist[rightHistPeak]

        if (maskSum > 0) Log.d("LaneDetector", "target=${targetW}x${targetH} maskSum=$maskSum leftPeak=$leftHistPeak(${hist[leftHistPeak]}) rightPeak=$rightHistPeak(${hist[rightHistPeak]})")

        if (leftSum < 5 && rightSum < 5) {
            return LaneDetectionResult(
                prevLeftX?.let { LaneLine(it, 0f, it, bitmap.height - 1f, 90f, bitmap.height.toFloat()) },
                prevRightX?.let { LaneLine(it, 0f, it, bitmap.height - 1f, 90f, bitmap.height.toFloat()) },
                false, false, 0.1f)
        }

        var leftX = leftHistPeak
        var rightX = rightHistPeak

        if (leftSum >= 5 && prevLeftX != null) {
            leftX = ((leftX * 0.6f + prevLeftX!! / scaleX * 0.4f)).toInt().coerceIn(0, midX - 1)
        }
        if (rightSum >= 5 && prevRightX != null) {
            rightX = ((rightX * 0.6f + prevRightX!! / scaleX * 0.4f)).toInt().coerceIn(midX, targetW - 1)
        }

        val leftXImg = leftX * scaleX
        val rightXImg = rightX * scaleX

        prevLeftX = leftXImg
        prevRightX = rightXImg

        val leftLane = LaneLine(leftXImg, 0f, leftXImg, bitmap.height - 1f, 90f, bitmap.height.toFloat())
        val rightLane = LaneLine(rightXImg, 0f, rightXImg, bitmap.height - 1f, 90f, bitmap.height.toFloat())

        val totalScore = (leftSum + rightSum).coerceIn(0, 400)
        val conf = (totalScore / 400f * 0.85f + 0.12f).coerceIn(0.15f, 0.95f)

        val leftDrift = leftXImg < bitmap.width * 0.18f
        val rightDrift = rightXImg > bitmap.width * 0.82f

        return LaneDetectionResult(leftLane, rightLane, leftDrift, rightDrift, conf)
    }

    fun detectLanesFromYUV(yData: ByteArray, width: Int, height: Int): LaneDetectionResult {
        val targetW = min(width, 480)
        val targetH = min(height, 320)
        val scaleX = width.toFloat() / targetW
        val scaleY = height.toFloat() / targetH
        val stepX = width / targetW
        val stepY = height / targetH

        val gray = IntArray(targetW * targetH)
        for (y in 0 until targetH) {
            for (x in 0 until targetW) {
                gray[y * targetW + x] = yData[(y * stepY) * width + (x * stepX)].toInt() and 0xFF
            }
        }

        val edges = detectEdges(gray, targetW, targetH)
        val mask = createLaneMask(gray, edges, targetW, targetH)

        val hist = IntArray(targetW)
        for (y in targetH / 2 until targetH) {
            for (x in 0 until targetW) {
                hist[x] += mask[y * targetW + x]
            }
        }

        val midX = targetW / 2
        val leftHistPeak = hist.slice(0 until midX).indices.maxByOrNull { hist[it] } ?: (midX / 3)
        val rightHistPeak = hist.slice(midX until targetW).indices.maxByOrNull { hist[midX + it] }?.let { midX + it } ?: (midX * 5 / 3)

        val leftSum = hist[leftHistPeak]
        val rightSum = hist[rightHistPeak]

        if (leftSum < 5 && rightSum < 5) {
            return LaneDetectionResult(
                prevLeftX?.let { LaneLine(it, 0f, it, height - 1f, 90f, height.toFloat()) },
                prevRightX?.let { LaneLine(it, 0f, it, height - 1f, 90f, height.toFloat()) },
                false, false, 0.1f)
        }

        var leftX = leftHistPeak
        var rightX = rightHistPeak

        if (leftSum >= 5 && prevLeftX != null) {
            leftX = ((leftX * 0.6f + prevLeftX!! / scaleX * 0.4f)).toInt().coerceIn(0, midX - 1)
        }
        if (rightSum >= 5 && prevRightX != null) {
            rightX = ((rightX * 0.6f + prevRightX!! / scaleX * 0.4f)).toInt().coerceIn(midX, targetW - 1)
        }

        val leftXImg = leftX * scaleX
        val rightXImg = rightX * scaleX

        prevLeftX = leftXImg
        prevRightX = rightXImg

        val leftLane = LaneLine(leftXImg, 0f, leftXImg, height - 1f, 90f, height.toFloat())
        val rightLane = LaneLine(rightXImg, 0f, rightXImg, height - 1f, 90f, height.toFloat())

        val totalScore = (leftSum + rightSum).coerceIn(0, 400)
        val conf = (totalScore / 400f * 0.85f + 0.12f).coerceIn(0.15f, 0.95f)

        val leftDrift = leftXImg < width * 0.18f
        val rightDrift = rightXImg > width * 0.82f

        return LaneDetectionResult(leftLane, rightLane, leftDrift, rightDrift, conf)
    }

    fun reset() {
        prevLeftX = null
        prevRightX = null
    }

    private fun detectEdges(src: IntArray, w: Int, h: Int): IntArray {
        val edges = IntArray(w * h)
        val lowThresh = (25 * sensitivity).toInt().coerceIn(15, 50)
        val highThresh = lowThresh * 2

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val gx = -src[(y-1)*w + (x-1)] + src[(y-1)*w + (x+1)]
                       -2*src[y*w + (x-1)]       + 2*src[y*w + (x+1)]
                       -src[(y+1)*w + (x-1)] + src[(y+1)*w + (x+1)]
                val gy = -src[(y-1)*w + (x-1)] - 2*src[(y-1)*w + x] - src[(y-1)*w + (x+1)]
                       +src[(y+1)*w + (x-1)] + 2*src[(y+1)*w + x] + src[(y+1)*w + (x+1)]
                val mag = sqrt((gx * gx + gy * gy).toDouble()).toInt()
                edges[y * w + x] = if (mag > highThresh) 255 else 0
            }
        }
        return edges
    }

    private fun createLaneMask(gray: IntArray, edges: IntArray, w: Int, h: Int): IntArray {
        val mask = IntArray(w * h)
        val topY = (h * 0.45).toInt()

        val grayMean = gray.filterIndexed { i, _ -> i >= topY * w }.average().toInt()
        val brightnessThresh = max(grayMean + 20, (140 - sensitivity * 40).toInt())

        for (y in topY until h) {
            val progress = (y - topY).toFloat() / (h - topY)
            val leftBound = (w * 0.05f + w * 0.25f * progress).toInt()
            val rightBound = (w * 0.95f - w * 0.25f * progress).toInt()

            for (x in leftBound until rightBound) {
                val idx = y * w + x
                val bright = gray[idx] > brightnessThresh
                val edge = edges[idx] > 0

                if (bright || edge) {
                    mask[idx] = 1
                    if (y < h - 1) mask[(y + 1) * w + x] = max(mask[(y + 1) * w + x], 1)
                    if (y > topY) mask[(y - 1) * w + x] = max(mask[(y - 1) * w + x], 1)
                }
            }
        }
        return mask
    }
}
