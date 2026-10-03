package com.google.mediapipe.examples.handlandmarker

import kotlin.math.max

/**
 * Maps normalized landmark coordinates of the analysed frame onto a view that shows the camera
 * with `PreviewView.ScaleType.FILL_CENTER`: scaled to cover the view and cropped evenly on the
 * overflowing axis. Mapping straight to the view size instead stretches the skeleton away from
 * the hand whenever the view and the frame have different aspect ratios.
 */
class FillCenterMapping(imageWidth: Int, imageHeight: Int, viewWidth: Float, viewHeight: Float) {
    private val scaledWidth: Float
    private val scaledHeight: Float
    private val offsetX: Float
    private val offsetY: Float

    init {
        require(imageWidth > 0 && imageHeight > 0) { "Image size must be positive: ${imageWidth}x$imageHeight" }
        val scale = max(viewWidth / imageWidth, viewHeight / imageHeight)
        scaledWidth = imageWidth * scale
        scaledHeight = imageHeight * scale
        offsetX = (viewWidth - scaledWidth) / 2f
        offsetY = (viewHeight - scaledHeight) / 2f
    }

    fun x(normalizedX: Float): Float = normalizedX * scaledWidth + offsetX

    fun y(normalizedY: Float): Float = normalizedY * scaledHeight + offsetY
}

/** Square that frames a hand: centered on its landmarks' bounding box, with a margin around them. */
data class FocusSquare(val centerX: Float, val centerY: Float, val side: Float) {
    companion object {
        private const val MARGIN = 1.3f

        /** [points] holds x, y pairs in view pixels. */
        fun around(points: FloatArray, minSide: Float): FocusSquare {
            require(points.size >= 2 && points.size % 2 == 0) { "Expected x, y pairs, got ${points.size} values" }
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (i in points.indices step 2) {
                minX = minOf(minX, points[i]); maxX = maxOf(maxX, points[i])
                minY = minOf(minY, points[i + 1]); maxY = maxOf(maxY, points[i + 1])
            }
            val side = max(max(maxX - minX, maxY - minY) * MARGIN, minSide)
            return FocusSquare((minX + maxX) / 2f, (minY + maxY) / 2f, side)
        }
    }
}
