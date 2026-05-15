package com.roadguard.app.data.ml

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
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
        val length: Float
    )

    data class LaneDetectionResult(
        val leftLane: LaneLine?,
        val rightLane: LaneLine?,
        val isDriftingLeft: Boolean,
        val isDriftingRight: Boolean,
        val confidence: Float
    )

    private val cannyLowThreshold = 50
    private val cannyHighThreshold = 150
    private val houghThreshold = 30
    private val minLineLength = 50f
    private val maxLineGap = 100f

    fun detectLanes(bitmap: Bitmap, imageWidth: Int, imageHeight: Int): LaneDetectionResult {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val grayscale = toGrayscale(pixels, width, height)
        val edges = cannyEdgeDetection(grayscale, width, height)

        val lines = probabilisticHoughLine(edges, width, height)

        val (leftLines, rightLines) = classifyLines(lines, width, height)

        val leftLane = leftLines.maxByOrNull { it.length }
        val rightLane = rightLines.maxByOrNull { it.length }

        val vanishingY = height * 0.4f
        val carCenterX = width / 2f

        var isDriftingLeft = false
        var isDriftingRight = false

        if (leftLane != null) {
            val leftXAtVanish = interpolateX(leftLane, vanishingY)
            if (leftXAtVanish < carCenterX - width * 0.15f * (1f - sensitivity)) {
                isDriftingLeft = true
            }
        }

        if (rightLane != null) {
            val rightXAtVanish = interpolateX(rightLane, vanishingY)
            if (rightXAtVanish > carCenterX + width * 0.15f * (1f - sensitivity)) {
                isDriftingRight = true
            }
        }

        val confidence = when {
            leftLane == null && rightLane == null -> 0.1f
            leftLane == null || rightLane == null -> 0.5f
            else -> minOf(leftLane.length, rightLane.length) / height
        }

        return LaneDetectionResult(
            leftLane = leftLane,
            rightLane = rightLane,
            isDriftingLeft = isDriftingLeft,
            isDriftingRight = isDriftingRight,
            confidence = confidence
        )
    }

    fun detectLanesFromYUV(yData: ByteArray, width: Int, height: Int): LaneDetectionResult {
        val grayscale = ByteArray(width * height)
        for (i in yData.indices) {
            grayscale[i] = yData[i]
        }

        val edges = cannyEdgeDetection(grayscale, width, height)
        val lines = probabilisticHoughLine(edges, width, height)

        val (leftLines, rightLines) = classifyLines(lines, width, height)

        val leftLane = leftLines.maxByOrNull { it.length }
        val rightLane = rightLines.maxByOrNull { it.length }

        val vanishingY = height * 0.4f
        val carCenterX = width / 2f

        var isDriftingLeft = false
        var isDriftingRight = false

        if (leftLane != null) {
            val leftXAtVanish = interpolateX(leftLane, vanishingY)
            if (leftXAtVanish < carCenterX - width * 0.15f * (1f - sensitivity)) {
                isDriftingLeft = true
            }
        }

        if (rightLane != null) {
            val rightXAtVanish = interpolateX(rightLane, vanishingY)
            if (rightXAtVanish > carCenterX + width * 0.15f * (1f - sensitivity)) {
                isDriftingRight = true
            }
        }

        val confidence = when {
            leftLane == null && rightLane == null -> 0.1f
            leftLane == null || rightLane == null -> 0.5f
            else -> minOf(leftLane.length, rightLane.length) / height
        }

        return LaneDetectionResult(
            leftLane = leftLane,
            rightLane = rightLane,
            isDriftingLeft = isDriftingLeft,
            isDriftingRight = isDriftingRight,
            confidence = confidence
        )
    }

    private fun toGrayscale(pixels: IntArray, width: Int, height: Int): ByteArray {
        val grayscale = ByteArray(width * height)
        for (i in pixels.indices) {
            val r = Color.red(pixels[i])
            val g = Color.green(pixels[i])
            val b = Color.blue(pixels[i])
            grayscale[i] = ((0.299 * r + 0.587 * g + 0.114 * b).toInt()).toByte()
        }
        return grayscale
    }

    private fun cannyEdgeDetection(grayscale: ByteArray, width: Int, height: Int): ByteArray {
        val sobelX = arrayOf(
            intArrayOf(-1, 0, 1),
            intArrayOf(-2, 0, 2),
            intArrayOf(-1, 0, 1)
        )
        val sobelY = arrayOf(
            intArrayOf(-1, -2, -1),
            intArrayOf(0, 0, 0),
            intArrayOf(1, 2, 1)
        )

        val gradientMagnitude = IntArray(width * height)
        val gradientDirection = FloatArray(width * height)

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var gx = 0
                var gy = 0

                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val pixel = grayscale[(y + ky) * width + (x + kx)].toInt() and 0xFF
                        gx += pixel * sobelX[ky + 1][kx + 1]
                        gy += pixel * sobelY[ky + 1][kx + 1]
                    }
                }

                gradientMagnitude[y * width + x] = sqrt(gx.toDouble().pow(2) + gy.toDouble().pow(2)).toInt()
                gradientDirection[y * width + x] = atan2(gy.toFloat(), gx.toFloat())
            }
        }

        val edges = ByteArray(width * height)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val magnitude = gradientMagnitude[y * width + x]
                val angle = gradientDirection[y * width + x]

                val neighbor1: Int
                val neighbor2: Int

                val acuteAngle = (angle % Math.PI).toFloat()
                if (acuteAngle < Math.PI / 4) {
                    neighbor1 = gradientMagnitude[y * width + x - 1]
                    neighbor2 = gradientMagnitude[y * width + x + 1]
                } else if (acuteAngle < Math.PI / 2) {
                    neighbor1 = gradientMagnitude[(y - 1) * width + x]
                    neighbor2 = gradientMagnitude[(y + 1) * width + x]
                } else if (acuteAngle < 3 * Math.PI / 4) {
                    neighbor1 = gradientMagnitude[(y - 1) * width + x]
                    neighbor2 = gradientMagnitude[(y + 1) * width + x]
                } else {
                    neighbor1 = gradientMagnitude[y * width + x - 1]
                    neighbor2 = gradientMagnitude[y * width + x + 1]
                }

                if (magnitude > neighbor1 && magnitude > neighbor2 && magnitude > cannyLowThreshold) {
                    edges[y * width + x] = if (magnitude > cannyHighThreshold) 255.toByte() else 128.toByte()
                }
            }
        }

        return edges
    }

    private fun probabilisticHoughLine(edges: ByteArray, width: Int, height: Int): List<LaneLine> {
        val maxRho = sqrt(width.toDouble().pow(2) + height.toDouble().pow(2)).toInt()
        val accumulator = Array(2 * maxRho) { IntArray(180) { 0 } }

        val cosTable = DoubleArray(180) { cos(Math.toRadians(it.toDouble())) }
        val sinTable = DoubleArray(180) { sin(Math.toRadians(it.toDouble())) }

        for (y in 0 until height) {
            for (x in 0 until width) {
                if (edges[y * width + x] > 0) {
                    for (theta in 0 until 180) {
                        val rho = (x * cosTable[theta] + y * sinTable[theta]).toInt() + maxRho
                        if (rho in 0 until 2 * maxRho) {
                            accumulator[rho][theta]++
                        }
                    }
                }
            }
        }

        val lines = mutableListOf<LaneLine>()

        for (rho in accumulator.indices) {
            for (theta in 0 until 180) {
                if (accumulator[rho][theta] > houghThreshold) {
                    val r = rho - maxRho
                    val rad = Math.toRadians(theta.toDouble())

                    var x1 = 0
                    var y1 = 0
                    var x2 = 0
                    var y2 = 0

                    if (theta in 45..135) {
                        x1 = ((r - (height / 2) * sin(rad)) / cos(rad)).toInt()
                        y1 = 0
                        x2 = ((r - ((height - 1) / 2) * sin(rad)) / cos(rad)).toInt()
                        y2 = height - 1
                    } else {
                        y1 = ((r - (width / 2) * cos(rad)) / sin(rad)).toInt()
                        x1 = 0
                        y2 = ((r - ((width - 1) / 2) * cos(rad)) / sin(rad)).toInt()
                        x2 = width - 1
                    }

                    x1 = x1.coerceIn(0, width - 1)
                    y1 = y1.coerceIn(0, height - 1)
                    x2 = x2.coerceIn(0, width - 1)
                    y2 = y2.coerceIn(0, height - 1)

                    val length = sqrt((x2 - x1).toDouble().pow(2) + (y2 - y1).toDouble().pow(2)).toFloat()
                    if (length >= minLineLength) {
                        val angle = atan2((y2 - y1).toFloat(), (x2 - x1).toFloat())
                        lines.add(LaneLine(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), angle, length))
                    }
                }
            }
        }

        return lines
    }

    private fun classifyLines(lines: List<LaneLine>, width: Int, height: Int): Pair<List<LaneLine>, List<LaneLine>> {
        val centerX = width / 2f
        val horizonY = height * 0.4f

        val leftLines = mutableListOf<LaneLine>()
        val rightLines = mutableListOf<LaneLine>()

        for (line in lines) {
            val lineCenterX = (line.x1 + line.x2) / 2
            val lineCenterY = (line.y1 + line.y2) / 2

            val isLeftSide = lineCenterX < centerX
            val isRightSide = lineCenterX >= centerX

            val isSlantedCorrectly = abs(line.angle) > Math.PI / 6 && abs(line.angle) < Math.PI * 5 / 6

            val isBelowHorizon = lineCenterY > horizonY

            if (isSlantedCorrectly && isBelowHorizon) {
                if (isLeftSide) {
                    leftLines.add(line)
                } else if (isRightSide) {
                    rightLines.add(line)
                }
            }
        }

        return Pair(leftLines, rightLines)
    }

    private fun interpolateX(line: LaneLine, y: Float): Float {
        if (abs(line.y2 - line.y1) < 0.001f) return line.x1
        return line.x1 + (line.x2 - line.x1) * (y - line.y1) / (line.y2 - line.y1)
    }
}
