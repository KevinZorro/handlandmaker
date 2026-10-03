package com.google.mediapipe.examples.handlandmarker

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker

/** One detected hand: x, y pairs normalized to the analysed frame, plus that frame's size in pixels. */
class HandFrame(val normalizedXY: FloatArray, val imageWidth: Int, val imageHeight: Int)

private val MIN_FOCUS_SIDE = 160.dp
private const val IDLE_CENTER_Y = .42f   // where the hand should go: upper middle, clear of the result card
private const val IDLE_SIDE = .62f       // idle square, as a fraction of the screen width
private const val CORNER_ARM = .2f       // corner length, as a fraction of the square side
private const val FOCUS_MOTION_MS = 120

/**
 * Corner brackets that frame the hand (or wait in the middle when there is none) and the hand
 * skeleton drawn as points of light. The camera stays undimmed.
 */
@Composable
fun HandOverlay(frame: HandFrame?, isSignConfirmed: Boolean, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val minSide = with(LocalDensity.current) { MIN_FOCUS_SIDE.toPx() }
        val points = remember(frame, width, height) { frame?.let { mapToView(it, width, height) } }
        val target = points?.let { FocusSquare.around(it, minSide) }
            ?: FocusSquare(width / 2f, height * IDLE_CENTER_Y, width * IDLE_SIDE)

        val motion = tween<Float>(FOCUS_MOTION_MS)
        val centerX by animateFloatAsState(target.centerX, motion, label = "focusX")
        val centerY by animateFloatAsState(target.centerY, motion, label = "focusY")
        val side by animateFloatAsState(target.side, motion, label = "focusSide")
        val cornerColor by animateColorAsState(
            if (isSignConfirmed) KioskColors.Confirmed else Color.White, tween(FOCUS_MOTION_MS), label = "focusColor",
        )
        val cornerAlpha by animateFloatAsState(if (points == null) .7f else 1f, tween(FOCUS_MOTION_MS), label = "focusAlpha")

        Canvas(Modifier.fillMaxSize()) {
            drawFocusCorners(centerX, centerY, side, cornerColor.copy(alpha = cornerAlpha))
            points?.let { drawSkeleton(it) }
        }
    }
}

private fun mapToView(frame: HandFrame, width: Float, height: Float): FloatArray {
    val mapping = FillCenterMapping(frame.imageWidth, frame.imageHeight, width, height)
    val xy = frame.normalizedXY
    return FloatArray(xy.size) { i -> if (i % 2 == 0) mapping.x(xy[i]) else mapping.y(xy[i]) }
}

private fun DrawScope.drawFocusCorners(centerX: Float, centerY: Float, side: Float, color: Color) {
    val half = side / 2f
    val arm = side * CORNER_ARM
    val radius = arm * .35f
    val left = centerX - half
    val right = centerX + half
    val top = centerY - half
    val bottom = centerY + half
    val corners = Path().apply {
        moveTo(left, top + arm); lineTo(left, top + radius); quadraticBezierTo(left, top, left + radius, top); lineTo(left + arm, top)
        moveTo(right - arm, top); lineTo(right - radius, top); quadraticBezierTo(right, top, right, top + radius); lineTo(right, top + arm)
        moveTo(right, bottom - arm); lineTo(right, bottom - radius); quadraticBezierTo(right, bottom, right - radius, bottom); lineTo(right - arm, bottom)
        moveTo(left + arm, bottom); lineTo(left + radius, bottom); quadraticBezierTo(left, bottom, left, bottom - radius); lineTo(left, bottom - arm)
    }
    val width = 4.dp.toPx()
    // A soft dark edge keeps the white corners visible over bright backgrounds.
    drawPath(corners, KioskColors.Shadow, style = Stroke(width + 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(corners, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawSkeleton(points: FloatArray) {
    fun at(index: Int) = Offset(points[index * 2], points[index * 2 + 1])
    val shadowWidth = 7.dp.toPx()
    val boneWidth = 3.dp.toPx()
    HandLandmarker.HAND_CONNECTIONS.forEach {
        drawLine(KioskColors.Shadow, at(it.start()), at(it.end()), shadowWidth, StrokeCap.Round)
    }
    HandLandmarker.HAND_CONNECTIONS.forEach {
        drawLine(Color.White, at(it.start()), at(it.end()), boneWidth, StrokeCap.Round)
    }
    val haloRadius = 11.dp.toPx()
    val edgeRadius = 5.5.dp.toPx()
    val pointRadius = 4.dp.toPx()
    for (i in 0 until points.size / 2) {
        val center = at(i)
        drawCircle(KioskColors.JointGlow, haloRadius, center)
        drawCircle(KioskColors.Shadow, edgeRadius, center)
        drawCircle(Color.White, pointRadius, center)
    }
}
