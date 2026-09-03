package com.roadguard.app.data.ml

data class VehicleBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun toRect() = android.graphics.Rect(left, top, right, bottom)
}
/**
 * Pure-JVM vehicle candidate logic shared by [MlDetectionAnalyzer] and
 * [VideoMlAnalyzer]. Extracted so the fallback for ML Kit's label-less
 * base model is unit-testable: the base model never emits Car/Truck etc.
 * (only fashion/food/home/plants/places), so unlabeled, vehicle-sized
 * boxes must be accepted by geometry instead of label text.
 */
class VehiclePipeline {

    data class Candidate(val boundingBox: VehicleBox, val label: String) { fun toRect() = boundingBox.toRect() }
    data class Label(val text: String, val confidence: Float)
    data class Detection(val boundingBox: VehicleBox, val labels: List<Label>)

    // Keep in sync with analyzers' real heuristics.
    private val vehicleCategories = setOf("Vehicle", "Car", "Truck", "Bus", "Motorcycle", "Bicycle")
    private val focalLengthPixels = 1500f
    private val vehicleHeightMeters = 1.5f

    fun isExplicitVehicle(labels: List<Label>): Boolean =
        labels.any { label ->
            vehicleCategories.any { cat -> label.text.contains(cat, ignoreCase = true) } && label.confidence > 0.4f
        }

    fun isVehicleSized(box: VehicleBox, imageHeight: Int, imageWidth: Int = imageHeight): Boolean {
        if (box.width <= 0 || box.height <= 0) return false
        val area = box.width * box.height.toFloat()
        val imageArea = maxOf(1, imageWidth) * maxOf(1, imageHeight).toFloat()
        // Reject tiny specks (<0.1% of frame) and huge full-frame blobs (>40%).
        if (area / imageArea < 0.001f) return false
        if (area / imageArea > 0.4f) return false
        // Vehicles on road are not extremely thin slivers.
        val aspect = box.width.toFloat() / box.height.toFloat()
        if (aspect < 0.35f || aspect > 3.5f) return false
        // Must not be high above horizon (sky/other).
        val horizonY = imageHeight * 0.35f
        if (box.bottom.toFloat() < horizonY) return false
        return true
    }

    fun selectClosestVehicle(detections: List<Detection>, imageHeight: Int, imageWidth: Int = imageHeight): Candidate? {
        // Prefer explicitly labeled vehicles, but fall back to geometry.
        var best: Candidate? = null
        var bestDist = Float.MAX_VALUE
        for (d in detections) {
            if (!isExplicitVehicle(d.labels)) continue
            val dist = estimateDistance(d.boundingBox, imageHeight)
            if (dist < bestDist) { bestDist = dist; best = Candidate(d.boundingBox, d.labels.firstOrNull()?.text ?: "Vehicle") }
        }
        if (best != null) return best
        for (d in detections) {
            // A low-confidence explicit label still rules out the geometry
            // fallback via the non-empty check — but it must NOT be accepted
            // as a tracking target. Skip it explicitly instead of silently
            // treating it as "no vehicle".
            if (d.labels.any { l -> vehicleCategories.any { cat -> l.text.contains(cat, ignoreCase = true) } }) continue
            if (!isVehicleSized(d.boundingBox, imageHeight, imageWidth)) continue
            val dist = estimateDistance(d.boundingBox, imageHeight)
            // Geometric fallback is lower confidence: require plausible distance.
            if (dist > 80f) continue
            if (dist < bestDist) { bestDist = dist; best = Candidate(d.boundingBox, "Vehicle") }
        }
        return best
    }

    fun estimateDistance(boundingBox: VehicleBox, imageHeight: Int): Float {
        val boxHeight = boundingBox.height.toFloat()
        val boxBottom = boundingBox.bottom.toFloat()
        if (boxHeight <= 0) return 100f
        val distanceByHeight = (focalLengthPixels * vehicleHeightMeters) / boxHeight
        val horizonY = imageHeight * 0.4f
        val groundY = imageHeight.toFloat()
        val normalizedBottom = (boxBottom - horizonY) / (groundY - horizonY)
        if (normalizedBottom < 0.1f) return 100f
        val distanceByPosition = 5f / maxOf(normalizedBottom, 0.05f)
        val combinedDistance = if (distanceByHeight in 0f..200f) distanceByHeight * 0.6f + distanceByPosition * 0.4f else distanceByPosition
        return combinedDistance.coerceIn(3f, 150f)
    }
}

// Thin adapters so the JVM test can stay free of ML Kit.
// The test uses `box =` as a short alias for `boundingBox`.
data class FakeLabel(val text: String, val confidence: Float)
data class FakeDetectedObject(val boundingBox: VehicleBox, val labels: List<FakeLabel> = emptyList())
fun VehiclePipeline.selectClosestVehicleRect(objects: List<android.graphics.Rect>, labels: List<List<FakeLabel>>, imageHeight: Int, imageWidth: Int = imageHeight): VehiclePipeline.Candidate? =
    selectClosestVehicle(objects.mapIndexed { i, r -> VehiclePipeline.Detection(VehicleBox(r.left,r.top,r.right,r.bottom), labels.getOrNull(i)?.map { VehiclePipeline.Label(it.text,it.confidence)} ?: emptyList()) }, imageHeight, imageWidth)

fun fakeObject(box: VehicleBox, labels: List<FakeLabel> = emptyList()) = FakeDetectedObject(boundingBox = box, labels = labels)
fun VehiclePipeline.selectClosestVehicle(objects: List<FakeDetectedObject>, imageHeight: Int, imageWidth: Int = imageHeight): VehiclePipeline.Candidate? =
    selectClosestVehicle(objects.map { VehiclePipeline.Detection(it.boundingBox, it.labels.map { l -> VehiclePipeline.Label(l.text, l.confidence) }) }, imageHeight, imageWidth)
