package com.roadguard.app.data.ml

/**
 * Thin adapters so the JVM tests can stay free of ML Kit. These used to live in
 * app/src/main (shipping test doubles in the released APK); they are test-only.
 */
data class FakeLabel(val text: String, val confidence: Float)
data class FakeDetectedObject(val boundingBox: VehicleBox, val labels: List<FakeLabel> = emptyList())
fun fakeObject(box: VehicleBox, labels: List<FakeLabel> = emptyList()) = FakeDetectedObject(boundingBox = box, labels = labels)

fun VehiclePipeline.selectClosestVehicle(objects: List<FakeDetectedObject>, imageHeight: Int, imageWidth: Int = imageHeight): VehiclePipeline.Candidate? =
    selectClosestVehicle(objects.map { VehiclePipeline.Detection(it.boundingBox, it.labels.map { l -> VehiclePipeline.Label(l.text, l.confidence) }) }, imageHeight, imageWidth)
