package com.roadguard.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import com.roadguard.app.domain.model.LaneCurve
import com.roadguard.app.domain.model.LaneInfo
import com.roadguard.app.ui.theme.DangerRed
import com.roadguard.app.ui.theme.SafeGreen
import com.roadguard.app.ui.theme.WarningYellow
import kotlin.math.max
import kotlin.math.min

private data class CanvasTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float
)

/**
 * @param fillCenter matches how the surface underneath scales the source frame:
 *   - `true`  → PreviewView FILL_CENTER (camera): uniform scale + centre crop.
 *   - `false` → PlayerView default RESIZE_MODE_FIT (video): uniform scale +
 *     letterbox, so the overlay must use fit semantics or it is drawn offset.
 */
@Composable
fun LaneOverlay(
    laneInfo: LaneInfo?,
    modifier: Modifier = Modifier,
    fillCenter: Boolean = true
) {
    Canvas(modifier = modifier) {
        laneInfo?.let { drawLaneOverlay(it, fillCenter) }
    }
}

private fun DrawScope.drawLaneOverlay(laneInfo: LaneInfo, fillCenter: Boolean) {
    val canvasW = size.width
    val canvasH = size.height
    if (canvasW <= 0f || canvasH <= 0f) return
    // Do not draw into the letterbox bars of PlayerView FIT. Clipping here
    // fixes the "lane trails into black UI" artifact that the 6-clip
    // real-road retest proved. Without clip, yStart/yEnd extending beyond
    // the video rect drew far below the frame.
    val transformEarly = computeCanvasTransform(laneInfo, canvasW, canvasH, fillCenter)
    val videoLeft = transformEarly.offsetX
    val videoTop = transformEarly.offsetY
    val videoRight = videoLeft + laneInfo.imageWidth.toFloat() * transformEarly.scale
    val videoBottom = videoTop + laneInfo.imageHeight.toFloat() * transformEarly.scale
    clipRect(left = videoLeft, top = videoTop, right = videoRight, bottom = videoBottom) {
        val leftCurve = laneInfo.leftCurve
        val rightCurve = laneInfo.rightCurve
    val hasLeft = leftCurve.valid && laneInfo.leftLaneVisible
    val hasRight = rightCurve.valid && laneInfo.rightLaneVisible

    val transform = computeCanvasTransform(laneInfo, canvasW, canvasH, fillCenter)

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
            transform = transform,
            color = baseColor
        )
    }

    if (hasLeft) {
        drawLaneLine(
            curve = leftCurve,
            transform = transform,
            color = baseColor,
            isDrifting = laneInfo.isDriftingLeft
        )
    }

    if (hasRight) {
        drawLaneLine(
            curve = rightCurve,
            transform = transform,
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
            transform = transform,
            canvasW = canvasW,
            canvasH = canvasH,
            color = baseColor
        )
    }

        if (laneInfo.confidence < 0.4f && (hasLeft || hasRight)) {
            drawUncertaintyIndicator(canvasW, canvasH, baseColor)
        }
    }
}

private fun computeCanvasTransform(
    laneInfo: LaneInfo,
    canvasW: Float,
    canvasH: Float,
    fillCenter: Boolean
): CanvasTransform {
    if (laneInfo.imageWidth <= 0 || laneInfo.imageHeight <= 0) {
        return CanvasTransform(1f, 0f, 0f)
    }
    val refW = laneInfo.imageWidth.toFloat()
    val refH = laneInfo.imageHeight.toFloat()
    // Uniform scale either way: independent scaleX/scaleY distorted the curves
    // whenever canvas and source aspect ratios differed. Fill crops the source,
    // fit letterboxes it inside the canvas.
    val scale = if (fillCenter) {
        max(canvasW / refW, canvasH / refH)
    } else {
        min(canvasW / refW, canvasH / refH)
    }
    return CanvasTransform(
        scale = scale,
        offsetX = (canvasW - refW * scale) / 2f,
        offsetY = (canvasH - refH * scale) / 2f
    )
}

private fun curveToCanvasPath(
    curve: LaneCurve,
    transform: CanvasTransform
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
        val px = xSrc * transform.scale + transform.offsetX
        val py = y * transform.scale + transform.offsetY
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
    transform: CanvasTransform,
    color: Color,
    isDrifting: Boolean
) {
    val path = curveToCanvasPath(curve, transform)
    val mainStroke = Stroke(width = 10f, cap = StrokeCap.Round)
    drawPath(path = path, color = color.copy(alpha = 0.95f), style = mainStroke)

    if (isDrifting) {
        drawPath(
            path = path,
            color = DangerRed.copy(alpha = 0.45f),
            style = Stroke(width = 26f, cap = StrokeCap.Round)
        )
    }

    val glowPath = curveToCanvasPath(curve, transform)
    drawPath(path = glowPath, color = color.copy(alpha = 0.25f), style = Stroke(width = 18f, cap = StrokeCap.Round))
}

private fun DrawScope.drawLaneArea(
    leftCurve: LaneCurve,
    rightCurve: LaneCurve,
    transform: CanvasTransform,
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
        val px = xSrc * transform.scale + transform.offsetX
        val py = y * transform.scale + transform.offsetY
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
        val px = xSrc * transform.scale + transform.offsetX
        val py = y * transform.scale + transform.offsetY
        path.lineTo(px, py)
        y -= dy
    }
    path.close()

    val brush = Brush.verticalGradient(
        colors = listOf(
            color.copy(alpha = 0.32f),
            color.copy(alpha = 0.08f)
        ),
        startY = yStart * transform.scale + transform.offsetY,
        endY = yEnd * transform.scale + transform.offsetY
    )
    drawPath(path = path, brush = brush)
}

private fun DrawScope.drawEgoVehicle(
    leftCurve: LaneCurve,
    rightCurve: LaneCurve,
    hasLeft: Boolean,
    hasRight: Boolean,
    transform: CanvasTransform,
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
            val px = mx * transform.scale + transform.offsetX
            val py = y * transform.scale + transform.offsetY
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
