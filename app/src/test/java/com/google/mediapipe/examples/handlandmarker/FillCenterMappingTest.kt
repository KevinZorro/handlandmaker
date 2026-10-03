package com.google.mediapipe.examples.handlandmarker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FillCenterMappingTest {

    @Test
    fun tallViewCropsTheFrameSidesEvenly() {
        // 480x640 frame on a 300x650 view: scaled to 487.5x650, 93.75 px cropped per side.
        val mapping = FillCenterMapping(480, 640, 300f, 650f)
        assertEquals(-93.75f, mapping.x(0f), 1e-3f)
        assertEquals(150f, mapping.x(.5f), 1e-3f)
        assertEquals(393.75f, mapping.x(1f), 1e-3f)
        assertEquals(0f, mapping.y(0f), 1e-3f)
        assertEquals(650f, mapping.y(1f), 1e-3f)
    }

    @Test
    fun squareViewCropsTopAndBottom() {
        val mapping = FillCenterMapping(480, 640, 276f, 276f)
        assertEquals(0f, mapping.x(0f), 1e-3f)
        assertEquals(276f, mapping.x(1f), 1e-3f)
        assertEquals(138f, mapping.y(.5f), 1e-3f)
        assertEquals(-46f, mapping.y(0f), 1e-3f)
    }

    @Test
    fun matchingAspectRatioMapsWithoutCrop() {
        val mapping = FillCenterMapping(480, 640, 960f, 1280f)
        assertEquals(240f, mapping.x(.25f), 1e-3f)
        assertEquals(960f, mapping.y(.75f), 1e-3f)
    }

    @Test
    fun rejectsEmptyImage() {
        assertThrows(IllegalArgumentException::class.java) { FillCenterMapping(0, 640, 300f, 650f) }
    }

    @Test
    fun focusSquareCentersOnTheHandWithMargin() {
        val square = FocusSquare.around(floatArrayOf(100f, 200f, 180f, 260f, 140f, 320f), minSide = 0f)
        assertEquals(140f, square.centerX, 1e-3f)
        assertEquals(260f, square.centerY, 1e-3f)
        assertEquals(120f * 1.3f, square.side, 1e-3f)
    }

    @Test
    fun focusSquareNeverGetsSmallerThanTheMinimum() {
        val square = FocusSquare.around(floatArrayOf(10f, 10f, 12f, 11f), minSide = 160f)
        assertEquals(160f, square.side, 0f)
    }
}
