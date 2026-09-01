package com.roadguard.app.data.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the upright→sensor-buffer coordinate mapping used by both
 * `LaneDetector.detectLanesFromYUV` and `TfliteModelRunner`. An earlier version
 * swapped width/height for the 90° branch, producing out-of-range indexes and a
 * mirrored frame on every portrait capture.
 */
class UprightToBufferIndexTest {

    private fun unpack(packed: Long): Pair<Int, Int> =
        (packed shr 32).toInt() to packed.toInt()

    @Test
    fun identityRotationKeepsCoordinates() {
        val packed = uprightToBufferIndex(3, 7, width = 10, height = 6, rotationDegrees = 0)
        assertEquals(3 to 7, unpack(packed))
    }

    @Test
    fun normalizesOutOfRangeRotations() {
        val normalized = uprightToBufferIndex(3, 7, width = 10, height = 6, rotationDegrees = 360)
        val negative = uprightToBufferIndex(3, 7, width = 10, height = 6, rotationDegrees = -270)
        assertEquals(uprightToBufferIndex(3, 7, 10, 6, 0), normalized)
        assertEquals(uprightToBufferIndex(3, 7, 10, 6, 90), negative)
    }

    @Test
    fun allRotationsStayInsideBufferBounds() {
        val sizes = listOf(1920 to 1080, 1280 to 720, 1080 to 1920, 640 to 480)
        for ((width, height) in sizes) {
            for (rotation in listOf(0, 90, 180, 270, -90, 450)) {
                val uprightW = if (rotation % 180 == 0) width else height
                val uprightH = if (rotation % 180 == 0) height else width
                for (uy in 0 until uprightH step 17) {
                    for (ux in 0 until uprightW step 19) {
                        val (bx, by) = unpack(
                            uprightToBufferIndex(ux, uy, width, height, rotation)
                        )
                        assertTrue(
                            "bx=$bx out of range for $width x $height rot=$rotation",
                            bx in 0 until width
                        )
                        assertTrue(
                            "by=$by out of range for $width x $height rot=$rotation",
                            by in 0 until height
                        )
                    }
                }
            }
        }
    }

    @Test
    fun mappingIsBijectiveForEveryRotation() {
        val width = 8
        val height = 5
        for (rotation in listOf(0, 90, 180, 270)) {
            val uprightW = if (rotation % 180 == 0) width else height
            val uprightH = if (rotation % 180 == 0) height else width
            val seen = mutableSetOf<Pair<Int, Int>>()
            for (uy in 0 until uprightH) {
                for (ux in 0 until uprightW) {
                    seen += unpack(uprightToBufferIndex(ux, uy, width, height, rotation))
                }
            }
            assertEquals(
                "mapping must cover every buffer pixel exactly once (rot=$rotation)",
                width * height,
                seen.size
            )
        }
    }

    @Test
    fun ninetyDegreesRotatesCornerCorrectly() {
        // Buffer (0,0) is the top-left of the sensor frame. Rotating it 90° CW to
        // become upright puts it in the top-right corner, i.e. upright
        // (uprightW-1, 0) must map back to buffer (0, 0).
        val width = 6
        val height = 4
        val uprightW = height
        val mapped = unpack(
            uprightToBufferIndex(uprightW - 1, 0, width, height, 90)
        )
        assertEquals(0 to 0, mapped)
    }

    @Test
    fun mappingInvertsTheDocumentedBufferRotation() {
        // rotationDegrees is the clockwise rotation that turns the buffer into the
        // upright frame. Feeding the mapping an upright coordinate produced by
        // that rotation must return the original buffer pixel.
        val width = 7
        val height = 5
        for (rotation in listOf(0, 90, 180, 270)) {
            for (by in 0 until height) {
                for (bx in 0 until width) {
                    val upright = when (rotation) {
                        90 -> (height - 1 - by) to bx
                        180 -> (width - 1 - bx) to (height - 1 - by)
                        270 -> by to (width - 1 - bx)
                        else -> bx to by
                    }
                    val mapped = unpack(
                        uprightToBufferIndex(upright.first, upright.second, width, height, rotation)
                    )
                    assertEquals(
                        "rot=$rotation must round-trip buffer pixel ($bx, $by)",
                        bx to by,
                        mapped
                    )
                }
            }
        }
    }
}
