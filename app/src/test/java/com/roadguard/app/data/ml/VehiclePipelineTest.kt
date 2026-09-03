package com.roadguard.app.data.ml


import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
