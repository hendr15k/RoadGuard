package com.roadguard.app.data.ml

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow
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
        val width = bitmap.width.coerceAtMost(320)
        val height = bitmap.height.coerceAtMost(240)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled != bitmap) scaled.recycle()

        val gray = ByteArray(width * height)
        for (i in pixels.indices) {
            val r = Color.red(pixels[i])
            val g = Color.green(pixels[i])
            val b = Color.blue(pixels[i])
            gray[i] = ((0.299f * r + 0.587f * g + 0.114f * b).toInt()).coerceIn(0, 255).toByte()
        }

        val mask = ByteArray(width * height)
        val thresh = (180 - sensitivity * 60).toInt()
        for (y in (height * 0.5).toInt() until height) {
            for (x in 0 until width) {
                val g = gray[y * width + x].toInt() and 0xFF
                if (g > thresh) {
                    mask[y * width + x] = 1
                }
            }
        }

        val hist = IntArray(width)
        for (y in height / 2 until height) {
            for (x in 0 until width) {
                hist[x] += mask[y * width + x].toInt()
            }
        }

        val midX = width / 2
        var leftX = hist.slice(0 until midX).indices.maxByOrNull { hist[it] }?.let { it } ?: (midX / 2)
        var rightX = hist.slice(midX until width).indices.maxByOrNull { hist[midX + it] }?.let { midX + it } ?: (midX + midX / 2)

        val leftSum = hist[leftX]
        val rightSum = hist[rightX]

        if (leftSum < 10 && rightSum < 10) {
            val conf = 0.1f
            return LaneDetectionResult(
                prevLeftX?.let { LaneLine(it * bitmap.width / width, 0f, it * bitmap.width / width, bitmap.height - 1f, 90f, bitmap.height.toFloat()) },
                prevRightX?.let { LaneLine(it * bitmap.width / width, 0f, it * bitmap.width / width, bitmap.height - 1f, 90f, bitmap.height.toFloat()) },
                false, false, conf)
        }

        if (prevLeftX != null) {
            leftX = ((leftX * 0.6f + prevLeftX!! * 0.4f)).toInt().coerceIn(0, width - 1)
        }
        if (prevRightX != null) {
            rightX = ((rightX * 0.6f + prevRightX!! * 0.4f)).toInt().coerceIn(0, width - 1)
        }

        val leftLaneX = leftX * bitmap.width / width
        val rightLaneX = rightX * bitmap.width / width

        prevLeftX = leftX.toFloat()
        prevRightX = rightX.toFloat()

        val leftLane = LaneLine(leftLaneX.toFloat(), 0f, leftLaneX.toFloat(), bitmap.height - 1f, 90f, bitmap.height.toFloat())
        val rightLane = LaneLine(rightLaneX.toFloat(), 0f, rightLaneX.toFloat(), bitmap.height - 1f, 90f, bitmap.height.toFloat())

        val conf = ((leftSum + rightSum).coerceIn(0, 200) / 200f * 0.8f + 0.1f).coerceIn(0.15f, 0.9f)

        return LaneDetectionResult(leftLane, rightLane, leftLaneX < bitmap.width * 0.15f, rightLaneX > bitmap.width * 0.85f, conf)
    }

    fun detectLanesFromYUV(yData: ByteArray, width: Int, height: Int): LaneDetectionResult {
        val targetW = width.coerceAtMost(320)
        val targetH = height.coerceAtMost(240)
        val stepX = width / targetW
        val stepY = height / targetH

        val mask = ByteArray(targetW * targetH)
        val thresh = (180 - sensitivity * 60).toInt()
        for (y in (targetH * 0.5).toInt() until targetH) {
            for (x in 0 until targetW) {
                val g = yData[(y * stepY) * width + (x * stepX)].toInt() and 0xFF
                if (g > thresh) {
                    mask[y * targetW + x] = 1
                }
            }
        }

        val hist = IntArray(targetW)
        for (y in targetH / 2 until targetH) {
            for (x in 0 until targetW) {
                hist[x] += mask[y * targetW + x].toInt()
            }
        }

        val midX = targetW / 2
        var leftX = hist.slice(0 until midX).indices.maxByOrNull { hist[it] } ?: (midX / 2)
        var rightX = hist.slice(midX until targetW).indices.maxByOrNull { hist[midX + it] }?.let { midX + it } ?: (midX + midX / 2)

        val leftSum = hist[leftX]
        val rightSum = hist[rightX]

        if (leftSum < 10 && rightSum < 10) {
            val conf = 0.1f
            return LaneDetectionResult(
                prevLeftX?.let { LaneLine(it, 0f, it, height - 1f, 90f, height.toFloat()) },
                prevRightX?.let { LaneLine(it, 0f, it, height - 1f, 90f, height.toFloat()) },
                false, false, conf)
        }

        if (prevLeftX != null) {
            leftX = ((leftX * 0.7f + prevLeftX!! * 0.3f)).toInt().coerceIn(0, targetW - 1)
        }
        if (prevRightX != null) {
            rightX = ((rightX * 0.7f + prevRightX!! * 0.3f)).toInt().coerceIn(0, targetW - 1)
        }

        prevLeftX = leftX.toFloat()
        prevRightX = rightX.toFloat()

        val leftLane = LaneLine(leftX.toFloat(), 0f, leftX.toFloat(), height - 1f, 90f, height.toFloat())
        val rightLane = LaneLine(rightX.toFloat(), 0f, rightX.toFloat(), height - 1f, 90f, height.toFloat())

        val conf = ((leftSum + rightSum).coerceIn(0, 200) / 200f * 0.8f + 0.1f).coerceIn(0.15f, 0.9f)

        return LaneDetectionResult(leftLane, rightLane, leftX < targetW * 0.15f, rightX > targetW * 0.85f, conf)
    }

    fun reset() {
        prevLeftX = null
        prevRightX = null
    }
}
