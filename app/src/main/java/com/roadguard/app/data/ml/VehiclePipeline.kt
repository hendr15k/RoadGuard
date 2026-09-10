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
    private val vehicleHeightMeters = 1.5f

    companion object {
        /**
         * Focal length in pixels at the reference width below. A pixel focal
         * length is resolution dependent, so it must be rescaled to the frame
         * actually being analysed: the old fixed 1500 px was calibrated for
         * ~1080p, while the video path feeds 640x360 frames and CameraX feeds
         * whatever the device negotiated. The same car therefore read ~3x
         * farther in video mode than in camera mode, and `isTooClose` (a meter
         * threshold) never fired at the right distance.
         */
        const val FOCAL_LENGTH_PX_AT_REFERENCE = 1500f
        const val REFERENCE_WIDTH_PX = 1920f

        fun focalLengthPixels(imageWidth: Int): Float =
            FOCAL_LENGTH_PX_AT_REFERENCE * (imageWidth.toFloat().coerceAtLeast(1f) / REFERENCE_WIDTH_PX)
    }

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
            val dist = estimateDistance(d.boundingBox, imageHeight, imageWidth)
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
            val dist = estimateDistance(d.boundingBox, imageHeight, imageWidth)
            // Geometric fallback is lower confidence: require plausible distance.
            if (dist > 80f) continue
            if (dist < bestDist) { bestDist = dist; best = Candidate(d.boundingBox, "Vehicle") }
        }
        return best
    }

    fun estimateDistance(boundingBox: VehicleBox, imageHeight: Int, imageWidth: Int = imageHeight): Float {
        val boxHeight = boundingBox.height.toFloat()
        val boxBottom = boundingBox.bottom.toFloat()
        if (boxHeight <= 0) return 100f
        val distanceByHeight = (focalLengthPixels(imageWidth) * vehicleHeightMeters) / boxHeight
        val horizonY = imageHeight * 0.4f
        val groundY = imageHeight.toFloat()
        val normalizedBottom = (boxBottom - horizonY) / (groundY - horizonY)
        if (normalizedBottom < 0.1f) return 100f
        val distanceByPosition = 5f / maxOf(normalizedBottom, 0.05f)
        val combinedDistance = if (distanceByHeight in 0f..200f) distanceByHeight * 0.6f + distanceByPosition * 0.4f else distanceByPosition
        return combinedDistance.coerceIn(3f, 150f)
    }
}
