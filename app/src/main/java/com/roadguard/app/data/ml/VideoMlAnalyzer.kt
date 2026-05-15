package com.roadguard.app.data.ml

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.roadguard.app.domain.model.LaneInfo
import com.roadguard.app.domain.model.VehicleDistance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VideoMlAnalyzer(
    private val vehicleThreshold: Float = 20f,
    private val laneSensitivity: Float = 0.5f,
    private val appContext: Context? = null
) {
    private val objectDetector: ObjectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .build()
    )

    private val laneDetector = LaneDetector(laneSensitivity)
    private var tfliteRunner: TfliteModelRunner? = null

    private val _laneInfo = MutableStateFlow<LaneInfo?>(null)
    val laneInfo: StateFlow<LaneInfo?> = _laneInfo.asStateFlow()

    private val _vehicleDistance = MutableStateFlow<VehicleDistance?>(null)
    val vehicleDistance: StateFlow<VehicleDistance?> = _vehicleDistance.asStateFlow()

    private var lastProcessTime = 0L
    private val processInterval = 300L

    init {
        appContext?.let { ctx ->
            try {
                tfliteRunner = TfliteModelRunner(ctx)
                tfliteRunner?.loadModel()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun analyzeFrame(bitmap: Bitmap, width: Int, height: Int) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessTime < processInterval) return
        lastProcessTime = currentTime

        try {
            val swResult = laneDetector.detectLanes(bitmap, width, height)

            var tfliteDriftL = false
            var tfliteDriftR = false
            var tfliteConf = 0.05f

            if (tfliteRunner?.isLoaded() == true) {
                try {
                    val segmentation = tfliteRunner!!.runSegmentation(bitmap)
                    val segResult = tfliteRunner!!.detectLanesFromSegmentation(
                        segmentation[0], width, height
                    )
                    tfliteDriftL = segResult.isDriftingLeft
                    tfliteDriftR = segResult.isDriftingRight
                    tfliteConf = segResult.confidence
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val finalIsDriftingLeft = if (swResult.confidence > 0.3f) swResult.isDriftingLeft else tfliteDriftL
            val finalIsDriftingRight = if (swResult.confidence > 0.3f) swResult.isDriftingRight else tfliteDriftR
            val finalConfidence = maxOf(swResult.confidence, tfliteConf)

            val leftMark = if (swResult.leftLane != null) "L" else "-"
            val rightMark = if (swResult.rightLane != null) "R" else "-"
            android.util.Log.d("LaneTracking",
                "sw=%.2f tf=%.2f cf=%.2f dL=%b dR=%b lanes=%s".format(
                    swResult.confidence, tfliteConf, finalConfidence,
                    finalIsDriftingLeft, finalIsDriftingRight, leftMark + rightMark
                )
            )

            _laneInfo.value = LaneInfo(
                isDriftingLeft = finalIsDriftingLeft,
                isDriftingRight = finalIsDriftingRight,
                confidence = finalConfidence
            )

            val inputImage = InputImage.fromBitmap(bitmap, 0)
            detectVehicles(inputImage, bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun detectVehicles(inputImage: InputImage, bitmapToRecycle: Bitmap) {
        objectDetector.process(inputImage)
            .addOnSuccessListener { detectedObjects ->
                processVehicleResult(detectedObjects)
                bitmapToRecycle.recycle()
            }
            .addOnFailureListener {
                _vehicleDistance.value = null
                bitmapToRecycle.recycle()
            }
    }

    private fun processVehicleResult(
        detectedObjects: List<com.google.mlkit.vision.objects.DetectedObject>
    ) {
        val vehicleCategories = setOf(
            "Vehicle", "Car", "Truck", "Bus", "Motorcycle", "Bicycle"
        )

        var minDistance = Float.MAX_VALUE

        for (obj in detectedObjects) {
            if (obj.labels.any { label ->
                    vehicleCategories.any { cat ->
                        label.text.contains(cat, ignoreCase = true)
                    } && label.confidence > 0.5f
                }
            ) {
                val boundingBox = obj.boundingBox
                val area = boundingBox.width().toFloat() * boundingBox.height().toFloat()
                val distance = estimateDistance(area)
                if (distance < minDistance) {
                    minDistance = distance
                }
            }
        }

        if (minDistance < Float.MAX_VALUE) {
            _vehicleDistance.value = VehicleDistance(
                distanceMeters = minDistance,
                isTooClose = minDistance < vehicleThreshold,
                timestamp = System.currentTimeMillis()
            )
        }
    }

    private fun estimateDistance(boundingBoxArea: Float): Float {
        val baseArea = 50000f
        val ratio = baseArea / boundingBoxArea.coerceAtLeast(1f)
        return (5f + ratio * 25f).coerceIn(5f, 100f)
    }

    fun close() {
        objectDetector.close()
        tfliteRunner?.close()
    }
}
