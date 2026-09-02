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
import kotlin.math.abs

class VideoMlAnalyzer(
    @Volatile private var vehicleThreshold: Float = 20f,
    @Volatile private var laneSensitivity: Float = 0.5f,
    private val appContext: Context? = null
) {

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

    @Volatile
    private var lastProcessTime = 0L
    private val processInterval = 200L

    // For distance smoothing and TTC calculation
    @Volatile
    private var prevDistance: Float? = null
    @Volatile
    private var prevTime: Long = 0
    @Volatile
    private var trackedBox: Rect? = null
    private val distanceHistory = ArrayDeque<Float>(5)

    // Camera parameters (approximate for typical smartphone)
    private var focalLengthPixels = 1500f
    private val vehicleHeightMeters = 1.5f

    @Volatile
    private var closed = false

    @Volatile
    private var modelLoadAttempted = false

    /** Guards the ML Kit detector so close() cannot dispose it mid-frame. */
    private val detectorLock = Any()

    init {
        // Only construct the runner here; loading maps the model and builds the
        // native interpreter, which must not happen during composition.
        appContext?.let { ctx ->
            try {
                tfliteRunner = TfliteModelRunner(ctx)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private val modelLock = Any()

    private fun ensureModelLoaded() {
        if (modelLoadAttempted) return
        synchronized(modelLock) {
            if (modelLoadAttempted) return
            modelLoadAttempted = true
            try {
                tfliteRunner?.loadModel()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun analyzeFrame(bitmap: Bitmap, height: Int) {
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

        // Ownership contract: this method owns `bitmap` after entry and must
        // recycle it on every synchronous exit. The async ML Kit path transfers
        // ownership to its onComplete listener.
        var handedToMlKit = false
        try {
            val swResult = laneDetector.detectLanes(bitmap)

            var tfliteDriftL = false
            var tfliteDriftR = false
            var tfliteConf = 0.05f
            var tfliteCenterOffset = 0f

            ensureModelLoaded()
            val runner = tfliteRunner
            if (runner != null && runner.isLoaded()) {
                try {
                    val segmentation = runner.runSegmentation(bitmap)
                    val segResult = runner.detectLanesFromSegmentation(
                        segmentation[0], bitmap.width, bitmap.height
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

            val leftMark = if (swResult.leftLane?.valid == true) "L" else "-"
            val rightMark = if (swResult.rightLane?.valid == true) "R" else "-"
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
                // smoothLane() returns a stale extrapolation with valid=false for a
                // missed detection; reporting it as "visible" lied in the HUD.
                leftLaneVisible = swResult.leftLane?.valid == true,
                rightLaneVisible = swResult.rightLane?.valid == true,
                leftCurve = swResult.leftLane?.let { l ->
                    com.roadguard.app.domain.model.LaneCurve(
                        a = l.polyA, b = l.polyB, c = l.polyC,
                        yStart = l.yStart, yEnd = l.yEnd, valid = l.valid
                    )
                } ?: com.roadguard.app.domain.model.LaneCurve(),
                rightCurve = swResult.rightLane?.let { l ->
                    com.roadguard.app.domain.model.LaneCurve(
                        a = l.polyA, b = l.polyB, c = l.polyC,
                        yStart = l.yStart, yEnd = l.yEnd, valid = l.valid
                    )
                } ?: com.roadguard.app.domain.model.LaneCurve(),
                imageWidth = swResult.imageWidth,
                imageHeight = swResult.imageHeight
            )

            val inputImage = InputImage.fromBitmap(bitmap, 0)
            synchronized(detectorLock) {
                if (closed) return
                detectVehicles(inputImage, bitmap, height)
                handedToMlKit = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (!handedToMlKit && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun detectVehicles(inputImage: InputImage, bitmapToRecycle: Bitmap, imageHeight: Int) {
        objectDetector.process(inputImage)
            .addOnSuccessListener { detectedObjects ->
                if (closed) return@addOnSuccessListener
                processVehicleResult(detectedObjects, imageHeight)
            }
            .addOnFailureListener {
                if (closed) return@addOnFailureListener
                _vehicleDistance.value = null
            }
            .addOnCompleteListener {
                try {
                    if (!bitmapToRecycle.isRecycled) bitmapToRecycle.recycle()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
    }

    private val vehiclePipeline = VehiclePipeline()

    private fun processVehicleResult(
        detectedObjects: List<com.google.mlkit.vision.objects.DetectedObject>,
        imageHeight: Int
    ) {
        // Delegates label- vs geometry-fallback to the JVM-tested VehiclePipeline.
        // The base ML Kit model never emits Vehicle/Car labels (only
        // fashion/food/home/plants/places), so geometry fallback is required.
        val detections = detectedObjects.map { obj ->
            VehiclePipeline.Detection(
                VehicleBox(obj.boundingBox.left, obj.boundingBox.top, obj.boundingBox.right, obj.boundingBox.bottom),
                obj.labels.map { VehiclePipeline.Label(it.text, it.confidence) }
            )
        }
        val candidate = vehiclePipeline.selectClosestVehicle(detections, imageHeight)
        var closestVehicle: DetectedVehicle? = null
        if (candidate != null) {
            val distance = vehiclePipeline.estimateDistance(candidate.boundingBox, imageHeight)
            closestVehicle = DetectedVehicle(candidate.boundingBox.toRect(), distance, candidate.label)
        }

        if (closestVehicle != null) {
            val currentTime = System.currentTimeMillis()
            if (!isSameTrackedVehicle(closestVehicle.boundingBox)) {
                distanceHistory.clear()
                prevDistance = null
                prevTime = 0L
            }
            trackedBox = Rect(closestVehicle.boundingBox)

            distanceHistory.addLast(closestVehicle.distance)
            if (distanceHistory.size > 5) distanceHistory.removeFirst()

            val smoothedDistance = median(distanceHistory)
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
            }
            if (gapSec > 3f) {
                _vehicleDistance.value = null
                prevDistance = null
                prevTime = 0L
                trackedBox = null
            }
        }
    }

    private fun median(values: Collection<Float>): Float {
        val sorted = values.sorted()
        if (sorted.isEmpty()) return 0f
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
    }

    private fun intersectionOverUnion(a: Rect, b: Rect): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersection = maxOf(0, right - left).toFloat() * maxOf(0, bottom - top).toFloat()
        val union = a.width().toFloat() * a.height().toFloat() +
            b.width().toFloat() * b.height().toFloat() - intersection
        return if (union > 0f) intersection / union else 0f
    }

    private fun isSameTrackedVehicle(box: Rect): Boolean {
        val previous = trackedBox ?: return false
        if (intersectionOverUnion(previous, box) > 0.15f) return true
        val dx = abs(previous.centerX() - box.centerX()).toFloat()
        val dy = abs(previous.centerY() - box.centerY()).toFloat()
        val tolerance = maxOf(previous.width(), previous.height(), box.width(), box.height()) * 0.35f
        return dx < tolerance && dy < tolerance
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
        
        val distanceByPosition = 5f / maxOf(normalizedBottom, 0.05f)
        
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
        // serialize with a frame that is still being processed
        synchronized(detectorLock) {
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
    }
    
    private data class DetectedVehicle(
        val boundingBox: Rect,
        val distance: Float,
        val label: String
    )
}
