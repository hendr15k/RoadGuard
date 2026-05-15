package com.roadguard.app.data.ml

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.roadguard.app.domain.model.LaneInfo
import com.roadguard.app.domain.model.VehicleDistance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MlDetectionAnalyzer(
    private val vehicleThreshold: Float = 20f,
    private val laneSensitivity: Float = 0.5f
) : ImageAnalysis.Analyzer {

    private val objectDetector: ObjectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .build()
    )

    private val _laneInfo = MutableStateFlow<LaneInfo?>(null)
    val laneInfo: StateFlow<LaneInfo?> = _laneInfo.asStateFlow()

    private val _vehicleDistance = MutableStateFlow<VehicleDistance?>(null)
    val vehicleDistance: StateFlow<VehicleDistance?> = _vehicleDistance.asStateFlow()

    private var lastProcessTime = 0L
    private val processInterval = 200L

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessTime < processInterval) {
            imageProxy.close()
            return
        }
        lastProcessTime = currentTime

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val inputImage = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )

            detectLanesFromImage(mediaImage, imageProxy.width, imageProxy.height)
            detectVehicles(inputImage)

            imageProxy.close()
        } else {
            imageProxy.close()
        }
    }

    private fun detectLanesFromImage(image: android.media.Image, width: Int, height: Int) {
        try {
            val yBuffer = image.planes[0].buffer
            val yData = ByteArray(yBuffer.remaining())
            yBuffer.get(yData)

            val centerY = height / 2

            var leftLaneScore = 0f
            var rightLaneScore = 0f

            val step = 20
            for (row in (centerY until height).step(step)) {
                val rowOffset = row * width

                var leftMax = 0
                var rightMax = 0
                for (col in (width / 4 until width / 2).step(10)) {
                    val pixelValue = yData[rowOffset + col].toInt() and 0xFF
                    if (pixelValue > leftMax) leftMax = pixelValue
                }
                for (col in (width / 2 until 3 * width / 4).step(10)) {
                    val pixelValue = yData[rowOffset + col].toInt() and 0xFF
                    if (pixelValue > rightMax) rightMax = pixelValue
                }

                if (leftMax > 150) leftLaneScore += 1f
                if (rightMax > 150) rightLaneScore += 1f
            }

            val totalRows = (height - centerY) / step
            leftLaneScore /= totalRows
            rightLaneScore /= totalRows

            val threshold = 1f - laneSensitivity

            _laneInfo.value = LaneInfo(
                isDriftingLeft = leftLaneScore < threshold && leftLaneScore > 0.1f,
                isDriftingRight = rightLaneScore < threshold && rightLaneScore > 0.1f,
                confidence = maxOf(leftLaneScore, rightLaneScore)
            )
        } catch (e: Exception) {
            _laneInfo.value = null
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun detectVehicles(inputImage: InputImage) {
        objectDetector.process(inputImage)
            .addOnSuccessListener { detectedObjects ->
                processVehicleResult(detectedObjects)
            }
            .addOnFailureListener {
                _vehicleDistance.value = null
            }
    }

    private fun processVehicleResult(
        detectedObjects: List<com.google.mlkit.vision.objects.DetectedObject>
    ) {
        val vehicleCategories = setOf(
            "Vehicle",
            "Car",
            "Truck",
            "Bus",
            "Motorcycle",
            "Bicycle"
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
    }
}
