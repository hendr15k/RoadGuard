package com.roadguard.app.data.ml

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
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
import kotlin.math.max
import kotlin.math.min

class MlDetectionAnalyzer(
    private var vehicleThreshold: Float = 20f,
    private var laneSensitivity: Float = 0.5f,
    private val appContext: Context? = null
) : ImageAnalysis.Analyzer {

    fun updateVehicleThreshold(value: Float) {
        vehicleThreshold = value
    }

    fun updateLaneSensitivity(value: Float) {
        laneSensitivity = value
        laneDetector.updateSensitivity(value)
    }

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
    private var focalLengthPixels = 1500f // More accurate default for ~1080p smartphones
    private val vehicleHeightMeters = 1.5f // Average car height

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

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        if (closed) {
            imageProxy.close()
            return
        }
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessTime < processInterval) {
            imageProxy.close()
            return
        }
        lastProcessTime = currentTime

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        // === ImageProxy-Lifecycle: WICHTIG ===
        // ML Kit ObjectDetector ist async. Wenn wir imageProxy.close() synchron
        // aufrufen würden, wäre die darunterliegende native Memory weg, bevor
        // ML Kit seine Task abschließt → "trying to use closed ImageProxy" Crash.
        // Daher: close() in onCompleteListener, NACHDEM die Task gelaufen ist.
        try {
            val inputImage = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )

            val yBuffer = mediaImage.planes[0].buffer
            val uBuffer = mediaImage.planes[1].buffer
            val vBuffer = mediaImage.planes[2].buffer

            // Stride-Safety: CameraX nutzt fast nie rowStride==width (Padding,
            // z.B. 1280 bei 1080-Breite). Ohne Stride-Respekt werden Zeilen
            // verschoben eingelesen → Lane-Erkennung zeigt auf realen Geräten
            // komplett falsche Resultate.
            val yPlane = mediaImage.planes[0]
            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            val yBufferSize = yBuffer.remaining()
            val yData = ByteArray(imageProxy.width * imageProxy.height)
            if (yPixelStride == 1 && yRowStride == imageProxy.width) {
                yBuffer.rewind()
                yBuffer.get(yData)
            } else {
                for (row in 0 until imageProxy.height) {
                    val srcStart = row * yRowStride
                    val dstStart = row * imageProxy.width
                    if (srcStart >= yBufferSize) break
                    val copyLen = minOf(imageProxy.width, yBufferSize - srcStart)
                    for (col in 0 until copyLen) {
                        yData[dstStart + col] = yBuffer.get(srcStart + col * yPixelStride)
                    }
                }
            }

            val swResult = laneDetector.detectLanesFromYUV(yData, imageProxy.width, imageProxy.height)

            var tfliteDriftL = false
            var tfliteDriftR = false
            var tfliteConf = 0.05f
            var tfliteCenterOffset = 0f

            val runner = tfliteRunner
            if (runner != null && runner.isLoaded()) {
                try {
                    yBuffer.rewind()
                    uBuffer.rewind()
                    vBuffer.rewind()
                    val segmentation = runner.runSegmentationYUV(yBuffer, uBuffer, vBuffer, imageProxy.width, imageProxy.height)
                    val segResult = runner.detectLanesFromSegmentation(
                        segmentation[0],
                        imageProxy.width,
                        imageProxy.height
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

            _laneInfo.value = LaneInfo(
                isDriftingLeft = finalIsDriftingLeft,
                isDriftingRight = finalIsDriftingRight,
                confidence = finalConfidence,
                centerOffset = finalCenterOffset,
                laneWidth = swResult.laneWidth,
                leftLaneVisible = swResult.leftLane != null,
                rightLaneVisible = swResult.rightLane != null,
                leftCurve = toDomainCurve(swResult.leftLane),
                rightCurve = toDomainCurve(swResult.rightLane),
                imageWidth = swResult.imageWidth,
                imageHeight = swResult.imageHeight
            )

            detectVehicles(inputImage, imageProxy)
        } catch (e: Exception) {
            // Wenn der synchrone Teil (LaneDetector etc.) crasht, müssen wir
            // trotzdem close() aufrufen, sonst hängt der CameraX-Frame-Queue.
            e.printStackTrace()
            imageProxy.close()
        }
    }

    private fun toDomainCurve(lane: LaneDetector.LaneLine?) =
        lane?.let {
            com.roadguard.app.domain.model.LaneCurve(
                a = it.polyA, b = it.polyB, c = it.polyC,
                yStart = it.yStart, yEnd = it.yEnd, valid = it.valid
            )
        } ?: com.roadguard.app.domain.model.LaneCurve()

    @SuppressLint("UnsafeOptInUsageError")
    private fun detectVehicles(inputImage: InputImage, imageProxy: ImageProxy) {
        // close() läuft IMMER im onCompleteListener, nie synchron davor.
        // So vermeiden wir "trying to use closed ImageProxy"-Crashes, die
        // auftreten, wenn ML Kit noch auf die underlying mediaImage-Buffer
        // zugreift während wir sie schon zurückgeben.
        objectDetector.process(inputImage)
            .addOnSuccessListener { detectedObjects ->
                processVehicleResult(detectedObjects, imageProxy.height)
            }
            .addOnFailureListener {
                _vehicleDistance.value = null
            }
            .addOnCompleteListener {
                try {
                    if (!closed) imageProxy.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
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
            // Kein Vehicle in diesem Frame. prevTime wird BEWUSST nicht
            // aktualisiert, damit der nächste Frame mit echtem Vehicle
            // die Zeitdifferenz korrekt messen kann. Wenn jedoch lange
            // kein Vehicle erkannt wird, soll die History gecleart
            // werden, um stale Distanzen zu verwerfen.
            val currentTime = System.currentTimeMillis()
            val gapSec = if (prevTime > 0) (currentTime - prevTime) / 1000f else 0f
            if (gapSec > 1.5f) {
                distanceHistory.clear()
            }
            if (gapSec > 3f) {
                _vehicleDistance.value = null
                prevTime = 0L  // Reset, damit beim nächsten Vehicle die Lücke nicht "ewig" ist
            }
        }
    }

    private fun estimateDistance(boundingBox: Rect, imageHeight: Int): Float {
        // Use bounding box height for distance estimation (more reliable than area)
        val boxHeight = boundingBox.height().toFloat()
        val boxBottom = boundingBox.bottom.toFloat()
        
        if (boxHeight <= 0) return 100f
        
        // Method 1: Using known object height and camera focal length
        // distance = (focalLength * realHeight * imageHeight) / (boxHeight * sensorHeight)
        // Simplified: distance = focalLength * realHeight / boxHeight
        val distanceByHeight = (focalLengthPixels * vehicleHeightMeters) / boxHeight
        
        // Method 2: Using position in image (lower in image = closer)
        // Assumes horizon is at 40% of image height
        val horizonY = imageHeight * 0.4f
        val groundY = imageHeight.toFloat()
        val normalizedBottom = (boxBottom - horizonY) / (groundY - horizonY)
        
        // If object is above horizon, it's very far
        if (normalizedBottom < 0.1f) return 100f
        
        // Distance based on position (empirical formula)
        val distanceByPosition = 5f / max(normalizedBottom, 0.05f)
        
        // Combine both methods with weighting
        val combinedDistance = if (distanceByHeight > 0 && distanceByHeight < 200f) {
            distanceByHeight * 0.6f + distanceByPosition * 0.4f
        } else {
            distanceByPosition
        }
        
        // Adjust based on object type (trucks are taller)
        return combinedDistance.coerceIn(3f, 150f)
    }

    private fun calculateTimeToCollision(currentDistance: Float, currentTime: Long): Float {
        val prevDist = prevDistance
        val prevT = prevTime
        
        if (prevDist == null || prevT == 0L || currentTime <= prevT) {
            return Float.MAX_VALUE
        }
        
        val dt = (currentTime - prevT) / 1000f // seconds
        val distanceDelta = prevDist - currentDistance // meters
        
        if (distanceDelta <= 0.1f || dt <= 0f) {
            return Float.MAX_VALUE // Not approaching or invalid
        }
        
        val relativeSpeed = distanceDelta / dt // m/s
        val ttc = currentDistance / relativeSpeed // seconds
        
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
