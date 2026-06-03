package com.roadguard.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.roadguard.app.domain.model.LaneCurve
import com.roadguard.app.domain.model.LaneInfo
import com.roadguard.app.ui.theme.DangerRed
import com.roadguard.app.ui.theme.SafeGreen
import com.roadguard.app.ui.theme.WarningYellow
import kotlin.math.max
import kotlin.math.min

private const val REFERENCE_W = 800f
private const val REFERENCE_H = 600f

@Composable
fun LaneOverlay(
    laneInfo: LaneInfo?,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        laneInfo?.let { drawLaneOverlay(it) }
    }
}

private fun DrawScope.drawLaneOverlay(laneInfo: LaneInfo) {
    val canvasW = size.width
    val canvasH = size.height
    if (canvasW <= 0f || canvasH <= 0f) return

    val leftCurve = laneInfo.leftCurve
    val rightCurve = laneInfo.rightCurve
    val hasLeft = leftCurve.valid && laneInfo.leftLaneVisible
    val hasRight = rightCurve.valid && laneInfo.rightLaneVisible

    val (scaleX, scaleY) = computeCanvasScale(laneInfo, canvasW, canvasH)

    val isDrifting = laneInfo.isDriftingLeft || laneInfo.isDriftingRight
    val baseColor = when {
        isDrifting -> DangerRed
        hasLeft && hasRight -> SafeGreen
        hasLeft || hasRight -> WarningYellow
        else -> Color.Gray
    }

    drawHorizon(canvasW, canvasH, baseColor.copy(alpha = 0.25f))

    if (hasLeft && hasRight) {
        drawLaneArea(
            leftCurve = leftCurve,
            rightCurve = rightCurve,
            scaleX = scaleX,
            scaleY = scaleY,
            canvasW = canvasW,
            canvasH = canvasH,
            color = baseColor
        )
    }

    if (hasLeft) {
        drawLaneLine(
            curve = leftCurve,
            scaleX = scaleX,
            scaleY = scaleY,
            canvasW = canvasW,
            canvasH = canvasH,
            color = baseColor,
            isDrifting = laneInfo.isDriftingLeft
        )
    }

    if (hasRight) {
        drawLaneLine(
            curve = rightCurve,
            scaleX = scaleX,
            scaleY = scaleY,
            canvasW = canvasW,
            canvasH = canvasH,
            color = baseColor,
            isDrifting = laneInfo.isDriftingRight
        )
    }

    if (hasLeft || hasRight) {
        drawEgoVehicle(
            leftCurve = leftCurve,
            rightCurve = rightCurve,
            hasLeft = hasLeft,
            hasRight = hasRight,
            scaleX = scaleX,
            scaleY = scaleY,
            canvasW = canvasW,
            canvasH = canvasH,
            color = baseColor
        )
    }

    if (laneInfo.confidence < 0.4f && (hasLeft || hasRight)) {
        drawUncertaintyIndicator(canvasW, canvasH, baseColor)
    }
}

private fun computeCanvasScale(laneInfo: LaneInfo, canvasW: Float, canvasH: Float): Pair<Float, Float> {
    val refW = if (laneInfo.imageWidth > 0) laneInfo.imageWidth.toFloat() else REFERENCE_W
    val refH = if (laneInfo.imageHeight > 0) laneInfo.imageHeight.toFloat() else REFERENCE_H
    val scaleX = canvasW / refW
    val scaleY = canvasH / refH
    return Pair(scaleX, scaleY)
}

private fun curveToCanvasPath(
    curve: LaneCurve,
    scaleX: Float,
    scaleY: Float
): Path {
    val path = Path()
    val yStart = curve.yStart
    val yEnd = curve.yEnd
    if (yEnd <= yStart) return path

    val steps = 30
    val dy = (yEnd - yStart) / steps
    var y = yStart
    var first = true
    while (y <= yEnd) {
        val xSrc = curve.a * y * y + curve.b * y + curve.c
        val px = xSrc * scaleX
        val py = y * scaleY
        if (first) {
            path.moveTo(px, py)
            first = false
        } else {
            path.lineTo(px, py)
        }
        y += dy
    }
    return path
}

private fun DrawScope.drawLaneLine(
    curve: LaneCurve,
    scaleX: Float,
    scaleY: Float,
    canvasW: Float,
    canvasH: Float,
    color: Color,
    isDrifting: Boolean
) {
    val path = curveToCanvasPath(curve, scaleX, scaleY)
    val mainStroke = Stroke(width = 10f, cap = StrokeCap.Round)
    drawPath(path = path, color = color.copy(alpha = 0.95f), style = mainStroke)

    if (isDrifting) {
        drawPath(
            path = path,
            color = DangerRed.copy(alpha = 0.45f),
            style = Stroke(width = 26f, cap = StrokeCap.Round)
        )
    }

    val glowPath = curveToCanvasPath(curve, scaleX, scaleY)
    drawPath(path = glowPath, color = color.copy(alpha = 0.25f), style = Stroke(width = 18f, cap = StrokeCap.Round))
}

private fun DrawScope.drawLaneArea(
    leftCurve: LaneCurve,
    rightCurve: LaneCurve,
    scaleX: Float,
    scaleY: Float,
    canvasW: Float,
    canvasH: Float,
    color: Color
) {
    val yStart = max(leftCurve.yStart, rightCurve.yStart)
    val yEnd = min(leftCurve.yEnd, rightCurve.yEnd)
    if (yEnd <= yStart) return

    val steps = 30
    val dy = (yEnd - yStart) / steps
    val path = Path()

    var y = yStart
    var first = true
    while (y <= yEnd) {
        val xSrc = leftCurve.a * y * y + leftCurve.b * y + leftCurve.c
        val px = xSrc * scaleX
        val py = y * scaleY
        if (first) {
            path.moveTo(px, py)
            first = false
        } else {
            path.lineTo(px, py)
        }
        y += dy
    }

    y = yEnd
    while (y >= yStart) {
        val xSrc = rightCurve.a * y * y + rightCurve.b * y + rightCurve.c
        val px = xSrc * scaleX
        val py = y * scaleY
        path.lineTo(px, py)
        y -= dy
    }
    path.close()

    val brush = Brush.verticalGradient(
        colors = listOf(
            color.copy(alpha = 0.32f),
            color.copy(alpha = 0.08f)
        ),
        startY = yStart * scaleY,
        endY = yEnd * scaleY
    )
    drawPath(path = path, brush = brush)
}

private fun DrawScope.drawEgoVehicle(
    leftCurve: LaneCurve,
    rightCurve: LaneCurve,
    hasLeft: Boolean,
    hasRight: Boolean,
    scaleX: Float,
    scaleY: Float,
    canvasW: Float,
    canvasH: Float,
    color: Color
) {
    val midX = canvasW / 2f
    val bottomY = canvasH
    val hoodY = canvasH * 0.92f

    val trapezoid = Path().apply {
        moveTo(midX - 40f, hoodY)
        lineTo(midX - 18f, bottomY)
        lineTo(midX + 18f, bottomY)
        lineTo(midX + 40f, hoodY)
        close()
    }
    drawPath(path = trapezoid, color = color.copy(alpha = 0.18f))
    drawPath(
        path = trapezoid,
        color = color.copy(alpha = 0.6f),
        style = Stroke(width = 2.5f, cap = StrokeCap.Round)
    )

    if (hasLeft && hasRight) {
        val centerPath = Path()
        val yStart = leftCurve.yStart
        val yEnd = leftCurve.yEnd
        val steps = 30
        val dy = (yEnd - yStart) / steps
        var y = yStart
        var first = true
        while (y <= yEnd) {
            val lx = leftCurve.a * y * y + leftCurve.b * y + leftCurve.c
            val rx = rightCurve.a * y * y + rightCurve.b * y + rightCurve.c
            val mx = (lx + rx) / 2f
            val px = mx * scaleX
            val py = y * scaleY
            if (first) {
                centerPath.moveTo(px, py)
                first = false
            } else {
                centerPath.lineTo(px, py)
            }
            y += dy
        }
        drawPath(
            path = centerPath,
            color = color.copy(alpha = 0.55f),
            style = Stroke(width = 2f, cap = StrokeCap.Round)
        )
    }
}

private fun DrawScope.drawHorizon(canvasW: Float, canvasH: Float, color: Color) {
    val y = canvasH * 0.48f
    drawLine(
        color = color,
        start = Offset(0f, y),
        end = Offset(canvasW, y),
        strokeWidth = 1.5f
    )
}

private fun DrawScope.drawUncertaintyIndicator(canvasW: Float, canvasH: Float, color: Color) {
    val cornerSize = 32f
    val margin = 24f
    val topY = margin
    val bottomY = canvasH - margin
    val leftX = margin
    val rightX = canvasW - margin

    drawLine(color, Offset(leftX, topY), Offset(leftX + cornerSize, topY), 3f)
    drawLine(color, Offset(leftX, topY), Offset(leftX, topY + cornerSize), 3f)

    drawLine(color, Offset(rightX, topY), Offset(rightX - cornerSize, topY), 3f)
    drawLine(color, Offset(rightX, topY), Offset(rightX, topY + cornerSize), 3f)

    drawLine(color, Offset(leftX, bottomY), Offset(leftX + cornerSize, bottomY), 3f)
    drawLine(color, Offset(leftX, bottomY), Offset(leftX, bottomY - cornerSize), 3f)

    drawLine(color, Offset(rightX, bottomY), Offset(rightX - cornerSize, bottomY), 3f)
    drawLine(color, Offset(rightX, bottomY), Offset(rightX, bottomY - cornerSize), 3f)
}
