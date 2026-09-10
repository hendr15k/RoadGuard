package com.roadguard.app.data.ml


import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contract for the vehicle pipeline. ML Kit base model only knows
 * fashion/food/home/plants/places - never Car/Truck. Without fallback
 * labels.isEmpty() meant processVehicleResult never produced a distance.
 * RED first: unlabeled vehicle-sized boxes must still yield a candidate.
 */
class VehiclePipelineTest {

    private lateinit var pipeline: VehiclePipeline

    @Before
    fun setUp() {
        pipeline = VehiclePipeline()
    }

    private fun box(left: Int = 100, top: Int = 200, right: Int = 300, bottom: Int = 400): VehicleBox =
        VehicleBox(left, top, right, bottom)

    @Test
    fun unlabeledVehicleSizedDetectionsAreCandidates() {
        val objects = listOf(fakeObject(box = box(), labels = emptyList()))
        val closest = pipeline.selectClosestVehicle(objects, imageHeight = 600)
        assertNotNull("unlabeled box sized like a car must not be dropped", closest)
    }

    @Test
    fun tinyDetectionsAreNotVehicles() {
        val tiny = box(left = 0, top = 0, right = 20, bottom = 12)
        val objects = listOf(fakeObject(box = tiny, labels = emptyList()))
        val closest = pipeline.selectClosestVehicle(objects, imageHeight = 600)
        assertNull("tiny patch must not count as vehicle", closest)
    }

    @Test
    fun explicitLabelsStillWinOverUnlabeledFallback() {
        val vehicle = FakeDetectedObject(boundingBox = box(left = 0, top = 100, right = 200, bottom = 300),
            labels = listOf(FakeLabel("Car", confidence = 0.9f))
        )
        val unlabeledFarAway = FakeDetectedObject(boundingBox = box(left = 200, top = 300, right = 220, bottom = 315),
            labels = emptyList()
        )
        val closest = pipeline.selectClosestVehicle(
            listOf(vehicle, unlabeledFarAway), imageHeight = 600
        )
        assertEquals(vehicle.boundingBox, closest?.boundingBox)
    }

    @Test
    fun producesDistanceForFallbackDetection() {
        val objects = listOf(fakeObject(box = box(), labels = emptyList()))
        val closest = pipeline.selectClosestVehicle(objects, imageHeight = 600)
        val distance = pipeline.estimateDistance(closest!!.boundingBox, imageHeight = 600)
        assertEquals(true, distance in 3f..150f)
        assertEquals(false, distance == 100f)
    }

    @Test
    fun areaGateUsesRealFrameAspectNotSquare() {
        // 640x360 frame: the old imageHeight² normalization overestimated the
        // ratio ~1.8x and rejected plausible boxes. A box covering 30% of the
        // real frame must be accepted regardless of aspect.
        val wide = box(left = 0, top = 100, right = 384, bottom = 292) // 384x192 = 30.9% of 640x360
        val wideObj = listOf(fakeObject(box = wide, labels = emptyList()))
        val closestWide = pipeline.selectClosestVehicle(wideObj, imageHeight = 360, imageWidth = 640)
        assertNotNull("30% of a 640x360 frame is a plausible vehicle", closestWide)

        // Same pixel box on a square frame is 1.1% — a speck, must be rejected.
        val closestSquare = pipeline.selectClosestVehicle(wideObj, imageHeight = 1920, imageWidth = 1920)
        assertNull("same box on a huge square frame is a speck", closestSquare)
    }

    @Test
    fun theDistanceIsPinnedToAKnownGeometry() {
        // 1920-wide reference frame, 1.5 m vehicle, box 200 px tall -> the
        // height term alone is 1500*1.5/200 = 11.25 m; a 600 px tall frame with
        // the box bottom on the ground line adds the position term 5/1.0 = 5 m.
        // Pinning the number is the point: the previous assertion only locked
        // the clamp at the end of the function, so any wrong formula passed.
        val distance = pipeline.estimateDistance(box(left = 100, top = 400, right = 300, bottom = 600), imageHeight = 600, imageWidth = 1920)
        assertEquals(11.25f * 0.6f + 5f * 0.4f, distance, 0.05f)
    }

    @Test
    fun theSameBoxReadsCloserInASmallerFrame() {
        // A pixel focal length is resolution dependent. The old constant ignored
        // that, so a 640x360 video frame read ~3x farther than the same scene in
        // a 1920-wide camera frame and the meter-based alarm never fired.
        val wide = pipeline.estimateDistance(box(left = 100, top = 400, right = 300, bottom = 600), imageHeight = 600, imageWidth = 1920)
        val narrow = pipeline.estimateDistance(box(left = 100, top = 400, right = 300, bottom = 600), imageHeight = 600, imageWidth = 640)
        assertTrue("scaling the frame must not change the real-world estimate by 3x", narrow < wide * 1.5f)
    }

    @Test
    fun frameWidthScalingIsMonotonic() {
        val at640 = VehiclePipeline.focalLengthPixels(640)
        val at1920 = VehiclePipeline.focalLengthPixels(1920)
        assertEquals(500f, at640, 0.5f)
        assertEquals(1500f, at1920, 0.5f)
        assertTrue(at640 < at1920)
    }
}
