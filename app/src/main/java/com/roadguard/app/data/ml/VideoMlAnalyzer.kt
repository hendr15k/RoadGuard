package com.roadguard.app.data.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
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
    private val processInterval = 200L

    // For distance smoothing and TTC calculation
    private var prevDistance: Float? = null
    private var prevTime: Long = 0
    private val distanceHistory = ArrayDeque<Float>(5)

    // Camera parameters (approximate for typical smartphone)
    private val focalLengthPixels = 1000f
    private val vehicleHeightMeters = 1.5f

    @Volatile
    private var closed = false

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
        if (closed) {
            bitmap.recycle()
            return
        }
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessTime < processInterval) {
            bitmap.recycle()
            return
        }
        lastProcessTime = currentTime

        try {
            val swResult = laneDetector.detectLanes(bitmap, width, height)

            var tfliteDriftL = false
            var tfliteDriftR = false
            var tfliteConf = 0.05f
            var tfliteCenterOffset = 0f

            val runner = tfliteRunner
            if (runner != null && runner.isLoaded()) {
                try {
                    val segmentation = runner.runSegmentation(bitmap)
                    val segResult = runner.detectLanesFromSegmentation(
                        segmentation[0], width, height
                    )
                    tfliteDriftL = segResult.isDriftingLeft
                    tfliteDriftR = segResult.isDriftingRight
                    tfliteConf = segResult.confidence
                    tfliteCenterOffset = segResult.centerOffset
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val finalIsDriftingLeft = if (swResult.confidence > 0.3f) swResult.isDriftingLeft else tfliteDriftL
            val finalIsDriftingRight = if (swResult.confidence > 0.3f) swResult.isDriftingRight else tfliteDriftR
            val finalConfidence = maxOf(swResult.confidence, tfliteConf)
            val finalCenterOffset = if (swResult.confidence > 0.3f) swResult.centerOffset else tfliteCenterOffset

            val leftMark = if (swResult.leftLane != null) "L" else "-"
            val rightMark = if (swResult.rightLane != null) "R" else "-"
            android.util.Log.d("LaneTracking",
                "sw=%.2f tf=%.2f cf=%.2f dL=%b dR=%b lanes=%s offset=%.1f width=%.0f".format(
                    swResult.confidence, tfliteConf, finalConfidence,
                    finalIsDriftingLeft, finalIsDriftingRight, 
                    leftMark + rightMark, finalCenterOffset, swResult.laneWidth
                )
            )

            _laneInfo.value = LaneInfo(
                isDriftingLeft = finalIsDriftingLeft,
                isDriftingRight = finalIsDriftingRight,
                confidence = finalConfidence,
                centerOffset = finalCenterOffset,
                laneWidth = swResult.laneWidth,
                leftLaneVisible = swResult.leftLane != null,
                rightLaneVisible = swResult.rightLane != null
            )

            val inputImage = InputImage.fromBitmap(bitmap, 0)
            detectVehicles(inputImage, bitmap, height)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun detectVehicles(inputImage: InputImage, bitmapToRecycle: Bitmap, imageHeight: Int) {
        objectDetector.process(inputImage)
            .addOnSuccessListener { detectedObjects ->
                processVehicleResult(detectedObjects, imageHeight)
                bitmapToRecycle.recycle()
            }
            .addOnFailureListener {
                _vehicleDistance.value = null
                bitmapToRecycle.recycle()
            }
    }

    private fun processVehicleResult(
        detectedObjects: List<com.google.mlkit.vision.objects.DetectedObject>,
        imageHeight: Int
    ) {
        val vehicleCategories = setOf(
            "Vehicle", "Car", "Truck", "Bus", "Motorcycle", "Bicycle"
        )

        var closestVehicle: DetectedVehicle? = null
        var minDistance = Float.MAX_VALUE

        for (obj in detectedObjects) {
            if (obj.labels.any { label ->
                    vehicleCategories.any { cat ->
                        label.text.contains(cat, ignoreCase = true)
                    } && label.confidence > 0.4f
                }
            ) {
                val boundingBox = obj.boundingBox
                val distance = estimateDistance(
                    boundingBox = boundingBox,
                    imageHeight = imageHeight
                )
                
                if (distance < minDistance) {
                    minDistance = distance
                    closestVehicle = DetectedVehicle(
                        boundingBox = boundingBox,
                        distance = distance,
                        label = obj.labels.firstOrNull()?.text ?: "Vehicle"
                    )
                }
            }
        }

        if (closestVehicle != null) {
            // Smooth distance
            distanceHistory.addLast(closestVehicle.distance)
            if (distanceHistory.size > 5) distanceHistory.removeFirst()

            val smoothedDistance = distanceHistory.average().toFloat()

            // Calculate time to collision
            val currentTime = System.currentTimeMillis()
            val ttc = calculateTimeToCollision(smoothedDistance, currentTime)
            val relativeSpeed = calculateRelativeSpeed(smoothedDistance, currentTime)

            prevDistance = smoothedDistance
            prevTime = currentTime

            _vehicleDistance.value = VehicleDistance(
                distanceMeters = smoothedDistance,
                isTooClose = smoothedDistance < vehicleThreshold || ttc < 2.5f,
                timeToCollision = ttc,
                relativeSpeed = relativeSpeed,
                timestamp = currentTime
            )
        } else {
            val currentTime = System.currentTimeMillis()
            val gapSec = if (prevTime > 0) (currentTime - prevTime) / 1000f else 0f
            if (gapSec > 1.5f) {
                distanceHistory.clear()
                prevDistance = null
            }
            if (gapSec > 3f) {
                _vehicleDistance.value = null
            }
        }
    }

    private fun estimateDistance(boundingBox: Rect, imageHeight: Int): Float {
        val boxHeight = boundingBox.height().toFloat()
        val boxBottom = boundingBox.bottom.toFloat()
        
        if (boxHeight <= 0) return 100f
        
        // Method 1: Using known object height and camera focal length
        val distanceByHeight = (focalLengthPixels * vehicleHeightMeters) / boxHeight
        
        // Method 2: Using position in image (lower in image = closer)
        val horizonY = imageHeight * 0.4f
        val groundY = imageHeight.toFloat()
        val normalizedBottom = (boxBottom - horizonY) / (groundY - horizonY)
        
        if (normalizedBottom < 0.1f) return 100f
        
        val distanceByPosition = 5f / kotlin.math.max(normalizedBottom, 0.05f)
        
        // Combine both methods
        val combinedDistance = if (distanceByHeight > 0 && distanceByHeight < 200f) {
            distanceByHeight * 0.6f + distanceByPosition * 0.4f
        } else {
            distanceByPosition
        }
        
        return combinedDistance.coerceIn(3f, 150f)
    }

    private fun calculateTimeToCollision(currentDistance: Float, currentTime: Long): Float {
        val prevDist = prevDistance
        val prevT = prevTime
        
        if (prevDist == null || prevT == 0L || currentTime <= prevT) {
            return Float.MAX_VALUE
        }
        
        val dt = (currentTime - prevT) / 1000f
        val distanceDelta = prevDist - currentDistance
        
        if (distanceDelta <= 0.1f || dt <= 0f) {
            return Float.MAX_VALUE
        }
        
        val relativeSpeed = distanceDelta / dt
        val ttc = currentDistance / relativeSpeed
        
        return if (ttc > 0 && ttc < 60f) ttc else Float.MAX_VALUE
    }

    private fun calculateRelativeSpeed(currentDistance: Float, currentTime: Long): Float {
        val prevDist = prevDistance
        val prevT = prevTime
        
        if (prevDist == null || prevT == 0L || currentTime <= prevT) {
            return 0f
        }
        
        val dt = (currentTime - prevT) / 1000f
        return (prevDist - currentDistance) / dt
    }

    fun close() {
        closed = true
        try {
            objectDetector.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            tfliteRunner?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private data class DetectedVehicle(
        val boundingBox: Rect,
        val distance: Float,
        val label: String
    )
}
