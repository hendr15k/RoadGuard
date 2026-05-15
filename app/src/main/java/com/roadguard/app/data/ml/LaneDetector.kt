package com.roadguard.app.data.ml

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
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
        val length: Float
    )

    data class LaneDetectionResult(
        val leftLane: LaneLine?,
        val rightLane: LaneLine?,
        val isDriftingLeft: Boolean,
        val isDriftingRight: Boolean,
        val confidence: Float
    )

    private val gaussianKernel = arrayOf(
        intArrayOf(1, 2, 1),
        intArrayOf(2, 4, 2),
        intArrayOf(1, 2, 1)
    )
    private val gaussianDivisor = 16

    private val sobelX = arrayOf(
        intArrayOf(-1, 0, 1),
        intArrayOf(-2, 0, 2),
        intArrayOf(-1, 0, 1)
    )
    private val sobelY = arrayOf(
        intArrayOf(-1, -2, -1),
        intArrayOf(0, 0, 0),
        intArrayOf(1, 2, 1)
    )

    private val cannyLowThreshold = 30
    private val cannyHighThreshold = 100
    private val houghThreshold = 20
    private val minLineLength = 40f

    fun detectLanes(bitmap: Bitmap, imageWidth: Int, imageHeight: Int): LaneDetectionResult {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val grayscale = toGrayscale(pixels, width, height)
        val blurred = applyGaussianBlur(grayscale, width, height)
        val edges = cannyEdgeDetection(blurred, width, height)

        val roi = computeROI(width, height)
        val maskedEdges = applyROIMask(edges, roi, width, height)

        val lines = probabilisticHoughLine(maskedEdges, width, height)
        val (leftLines, rightLines) = classifyAndFilterLines(lines, width, height)

        val leftLane = fitLaneLine(leftLines, width, height)
        val rightLane = fitLaneLine(rightLines, width, height)

        val isDriftingLeft = checkDrifting(leftLane, width, isLeft = true)
        val isDriftingRight = checkDrifting(rightLane, width, isLeft = false)

        val confidence = calculateConfidence(leftLane, rightLane, leftLines, rightLines, height)

        return LaneDetectionResult(
            leftLane = leftLane,
            rightLane = rightLane,
            isDriftingLeft = isDriftingLeft,
            isDriftingRight = isDriftingRight,
            confidence = confidence
        )
    }

    fun detectLanesFromYUV(yData: ByteArray, width: Int, height: Int): LaneDetectionResult {
        val grayscale = yData.copyOf()

        val blurred = applyGaussianBlur(grayscale, width, height)
        val edges = cannyEdgeDetection(blurred, width, height)

        val roi = computeROI(width, height)
        val maskedEdges = applyROIMask(edges, roi, width, height)

        val lines = probabilisticHoughLine(maskedEdges, width, height)
        val (leftLines, rightLines) = classifyAndFilterLines(lines, width, height)

        val leftLane = fitLaneLine(leftLines, width, height)
        val rightLane = fitLaneLine(rightLines, width, height)

        val isDriftingLeft = checkDrifting(leftLane, width, isLeft = true)
        val isDriftingRight = checkDrifting(rightLane, width, isLeft = false)

        val confidence = calculateConfidence(leftLane, rightLane, leftLines, rightLines, height)

        return LaneDetectionResult(
            leftLane = leftLane,
            rightLane = rightLane,
            isDriftingLeft = isDriftingLeft,
            isDriftingRight = isDriftingRight,
            confidence = confidence
        )
    }

    private fun computeROI(width: Int, height: Int): IntArray {
        val topY = (height * 0.45).toInt()
        val bottomY = height

        val topWidth = width * 0.15f
        val bottomWidth = width * 0.6f

        val leftTopX = (width / 2f - topWidth).toInt()
        val leftBottomX = (width / 2f - bottomWidth).toInt()
        val rightTopX = (width / 2f + topWidth).toInt()
        val rightBottomX = (width / 2f + bottomWidth).toInt()

        val roi = IntArray(width * height) { 0 }
        for (y in topY until bottomY) {
            val ratio = (y - topY).toFloat() / (bottomY - topY)
            val leftEdge = (leftBottomX + ratio * (leftTopX - leftBottomX)).toInt()
            val rightEdge = (rightBottomX + ratio * (rightTopX - rightBottomX)).toInt()

            for (x in leftEdge until rightEdge) {
                if (x in 0 until width) {
                    roi[y * width + x] = 255
                }
            }
        }
        return roi
    }

    private fun applyROIMask(edges: ByteArray, roi: IntArray, width: Int, height: Int): ByteArray {
        val masked = ByteArray(width * height)
        for (i in edges.indices) {
            if (roi[i] > 0 && edges[i] > 0) {
                masked[i] = edges[i]
            }
        }
        return masked
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

    private fun applyGaussianBlur(image: ByteArray, width: Int, height: Int): ByteArray {
        val blurred = ByteArray(width * height)

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var sum = 0
                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val pixel = image[(y + ky) * width + (x + kx)].toInt() and 0xFF
                        sum += pixel * gaussianKernel[ky + 1][kx + 1]
                    }
                }
                blurred[y * width + x] = (sum / gaussianDivisor).toByte()
            }
        }
        return blurred
    }

    private fun cannyEdgeDetection(grayscale: ByteArray, width: Int, height: Int): ByteArray {
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

                val normalizedAngle = ((angle + Math.PI) % Math.PI).toFloat()

                when {
                    normalizedAngle < Math.PI / 8 || normalizedAngle > 7 * Math.PI / 8 -> {
                        neighbor1 = gradientMagnitude[y * width + x - 1]
                        neighbor2 = gradientMagnitude[y * width + x + 1]
                    }
                    normalizedAngle < 3 * Math.PI / 8 -> {
                        neighbor1 = gradientMagnitude[(y - 1) * width + x + 1]
                        neighbor2 = gradientMagnitude[(y + 1) * width + x - 1]
                    }
                    normalizedAngle < 5 * Math.PI / 8 -> {
                        neighbor1 = gradientMagnitude[(y - 1) * width + x]
                        neighbor2 = gradientMagnitude[(y + 1) * width + x]
                    }
                    else -> {
                        neighbor1 = gradientMagnitude[(y - 1) * width + x - 1]
                        neighbor2 = gradientMagnitude[(y + 1) * width + x + 1]
                    }
                }

                if (magnitude > neighbor1 && magnitude > neighbor2 && magnitude > cannyLowThreshold) {
                    if (magnitude > cannyHighThreshold) {
                        edges[y * width + x] = 255.toByte()
                    } else if (edges[(y - 1) * width + x - 1] == 255.toByte() ||
                        edges[(y - 1) * width + x] == 255.toByte() ||
                        edges[(y - 1) * width + x + 1] == 255.toByte() ||
                        edges[y * width + x - 1] == 255.toByte() ||
                        edges[y * width + x + 1] == 255.toByte() ||
                        edges[(y + 1) * width + x - 1] == 255.toByte() ||
                        edges[(y + 1) * width + x] == 255.toByte() ||
                        edges[(y + 1) * width + x + 1] == 255.toByte()) {
                        edges[y * width + x] = 255.toByte()
                    }
                }
            }
        }

        return edges
    }

    private fun probabilisticHoughLine(edges: ByteArray, width: Int, height: Int): List<LaneLine> {
        val maxRho = sqrt(width.toDouble().pow(2) + height.toDouble().pow(2)).toInt()
        val numRho = 2 * maxRho
        val numTheta = 180

        val accumulator = Array(numRho) { IntArray(numTheta) { 0 } }

        val cosTable = DoubleArray(numTheta) { cos(Math.toRadians(it.toDouble())) }
        val sinTable = DoubleArray(numTheta) { sin(Math.toRadians(it.toDouble())) }

        for (y in 0 until height) {
            for (x in 0 until width) {
                if (edges[y * width + x] > 0) {
                    for (theta in 0 until numTheta) {
                        val rho = (x * cosTable[theta] + y * sinTable[theta]).toInt() + maxRho
                        if (rho in 0 until numRho) {
                            accumulator[rho][theta]++
                        }
                    }
                }
            }
        }

        val lines = mutableListOf<LaneLine>()
        val localMaxima = mutableSetOf<Pair<Int, Int>>()

        val neighborhoodSize = 10

        for (rho in accumulator.indices) {
            for (theta in 0 until numTheta) {
                if (accumulator[rho][theta] > houghThreshold) {
                    var isLocalMax = true
                    for (dr in -neighborhoodSize..neighborhoodSize) {
                        for (dt in -5..5) {
                            val nr = rho + dr
                            val nt = theta + dt
                            if (nr in 0 until numRho && nt in 0 until numTheta) {
                                if (accumulator[nr][nt] > accumulator[rho][theta]) {
                                    isLocalMax = false
                                    break
                                }
                            }
                        }
                        if (!isLocalMax) break
                    }

                    if (isLocalMax && !localMaxima.contains(Pair(rho, theta))) {
                        localMaxima.add(Pair(rho, theta))

                        val r = rho - maxRho
                        val rad = Math.toRadians(theta.toDouble())

                        var x1 = 0.0
                        var y1 = 0.0
                        var x2 = 0.0
                        var y2 = 0.0

                        if (theta in 30..150) {
                            x1 = ((r - (height * 0.5) * sin(rad)) / cos(rad))
                            y1 = (height * 0.5).toDouble()
                            x2 = x1 + (-height * sin(rad))
                            y2 = y1 + (height * cos(rad))
                        } else {
                            y1 = ((r - (width * 0.5) * cos(rad)) / sin(rad))
                            x1 = (width * 0.5).toDouble()
                            y2 = y1 + (-width * cos(rad))
                            x2 = x1 + (width * sin(rad))
                        }

                        x1 = x1.coerceIn(0.0, (width - 1).toDouble())
                        y1 = y1.coerceIn(0.0, (height - 1).toDouble())
                        x2 = x2.coerceIn(0.0, (width - 1).toDouble())
                        y2 = y2.coerceIn(0.0, (height - 1).toDouble())

                        val length = sqrt((x2 - x1).pow(2) + (y2 - y1).pow(2)).toFloat()
                        if (length >= minLineLength) {
                            val angle = atan2((y2 - y1).toFloat(), (x2 - x1).toFloat())
                            lines.add(LaneLine(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), angle, length))
                        }
                    }
                }
            }
        }

        return lines
    }

    private fun classifyAndFilterLines(lines: List<LaneLine>, width: Int, height: Int): Pair<List<LaneLine>, List<LaneLine>> {
        val centerX = width / 2f
        val horizonY = height * 0.45f

        val leftLines = mutableListOf<LaneLine>()
        val rightLines = mutableListOf<LaneLine>()

        for (line in lines) {
            val lineCenterX = (line.x1 + line.x2) / 2
            val lineCenterY = (line.y1 + line.y2) / 2
            val lineAngle = abs(line.angle)

            val isValidAngle = lineAngle > Math.PI / 6 && lineAngle < Math.PI * 5 / 6
            val isBelowHorizon = lineCenterY > horizonY
            val isLeftSide = lineCenterX < centerX
            val isRightSide = lineCenterX >= centerX

            val isSlopeCorrect = if (isLeftSide) {
                line.angle < 0
            } else {
                line.angle > 0
            }

            if (isValidAngle && isBelowHorizon && isSlopeCorrect) {
                if (isLeftSide) {
                    leftLines.add(line)
                } else if (isRightSide) {
                    rightLines.add(line)
                }
            }
        }

        return Pair(leftLines.sortedByDescending { it.length }, rightLines.sortedByDescending { it.length })
    }

    private fun fitLaneLine(lines: List<LaneLine>, width: Int, height: Int): LaneLine? {
        if (lines.isEmpty()) return null

        if (lines.size == 1) return lines[0]

        var sumX1 = 0f
        var sumY1 = 0f
        var sumX2 = 0f
        var sumY2 = 0f
        var totalWeight = 0f

        for (line in lines) {
            val weight = line.length
            sumX1 += line.x1 * weight
            sumY1 += line.y1 * weight
            sumX2 += line.x2 * weight
            sumY2 += line.y2 * weight
            totalWeight += weight
        }

        if (totalWeight == 0f) return null

        val avgX1 = sumX1 / totalWeight
        val avgY1 = sumY1 / totalWeight
        val avgX2 = sumX2 / totalWeight
        val avgY2 = sumY2 / totalWeight

        val bottomY = (height * 0.95).toFloat()
        val topY = (height * 0.5).toFloat()

        val angle = atan2(avgY2 - avgY1, avgX2 - avgX1)
        val length = sqrt((avgX2 - avgX1).pow(2) + (avgY2 - avgY1).pow(2))

        val extrapolatedX1 = avgX1 + (topY - avgY1) / tan(angle.toDouble())
        val extrapolatedY1 = topY
        val extrapolatedX2 = avgX2 + (bottomY - avgY2) / tan(angle.toDouble())
        val extrapolatedY2 = bottomY

        return LaneLine(
            extrapolatedX1.toFloat(), extrapolatedY1,
            extrapolatedX2.toFloat(), extrapolatedY2,
            angle, length
        )
    }

    private fun checkDrifting(lane: LaneLine?, width: Int, isLeft: Boolean): Boolean {
        if (lane == null) return false

        val checkY = (width * 0.5f)
        val laneXAtCheckY = lane.x1 + (lane.x2 - lane.x1) * (checkY - lane.y1) / (lane.y2 - lane.y1)

        val centerX = width / 2f
        val driftThreshold = width * (0.12f - sensitivity * 0.08f)

        return if (isLeft) {
            laneXAtCheckY < centerX - driftThreshold
        } else {
            laneXAtCheckY > centerX + driftThreshold
        }
    }

    private fun calculateConfidence(
        leftLane: LaneLine?,
        rightLane: LaneLine?,
        leftLines: List<LaneLine>,
        rightLines: List<LaneLine>,
        height: Int
    ): Float {
        if (leftLane == null && rightLane == null) return 0.05f

        val lineCount = leftLines.size + rightLines.size
        val lineCountScore = (lineCount.coerceIn(0, 10) / 10f) * 0.3f

        val leftScore = leftLane?.length?.div(height) ?: 0f
        val rightScore = rightLane?.length?.div(height) ?: 0f
        val lengthScore = (leftScore + rightScore) * 0.7f

        return (lineCountScore + lengthScore).coerceIn(0f, 1f)
    }
}
