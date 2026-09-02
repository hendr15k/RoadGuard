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
import kotlin.math.abs
import kotlin.math.min

class MlDetectionAnalyzer(
    @Volatile private var vehicleThreshold: Float = 20f,
    @Volatile private var laneSensitivity: Float = 0.5f,
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

    @Volatile
    private var lastProcessTime = 0L
    private val processInterval = 200L

    // Reused luma scratch: a 1080p frame is ~2 MB, allocating one per frame
    // (5 fps) was 10 MB/s of pure garbage for data we throw away immediately.
    private var lumaScratch: ByteArray? = null

    private fun obtainLumaBuffer(size: Int): ByteArray {
        val existing = lumaScratch
        if (existing != null && existing.size == size) return existing
        val buffer = ByteArray(size)
        lumaScratch = buffer
        return buffer
    }

    // For distance smoothing and TTC calculation
    @Volatile
    private var prevDistance: Float? = null
    @Volatile
    private var prevTime: Long = 0
    @Volatile
    private var trackedBox: Rect? = null
    private val distanceHistory = ArrayDeque<Float>(5)

    // Camera parameters (approximate for typical smartphone)
    private var focalLengthPixels = 1500f // More accurate default for ~1080p smartphones
    private val vehicleHeightMeters = 1.5f // Average car height

    @Volatile
    private var closed = false

    @Volatile
    private var modelLoadAttempted = false

    init {
        // Only construct the runner here. Mapping a ~2.8 MB model and building the
        // native interpreter belongs on the analyzer thread, not in composition.
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

            val yPlane = mediaImage.planes[0]
            val uPlane = mediaImage.planes[1]
            val vPlane = mediaImage.planes[2]
            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            // Stride-Safety: CameraX nutzt fast nie rowStride==width (Padding,
            // z.B. 1280 bei 1080-Breite). Ohne Stride-Respekt werden Zeilen
            // verschoben eingelesen → Lane-Erkennung zeigt auf realen Geräten
            // komplett falsche Resultate.
            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            val yBase = yBuffer.position()
            val yBufferLimit = yBuffer.limit()
            val yData = obtainLumaBuffer(imageProxy.width * imageProxy.height)
            java.util.Arrays.fill(yData, 0.toByte())
            // Duplicate keeps the plane's position untouched for the TFLite path.
            val yRead = yBuffer.duplicate()
            if (yPixelStride == 1 && yRowStride == imageProxy.width) {
                val copyLen = minOf(yData.size, yBufferLimit - yBase)
                yRead.position(yBase)
                yRead.get(yData, 0, copyLen)
            } else if (yPixelStride == 1) {
                for (row in 0 until imageProxy.height) {
                    val srcStart = yBase + row * yRowStride
                    if (srcStart >= yBufferLimit) break
                    val copyLen = minOf(imageProxy.width, yBufferLimit - srcStart)
                    yRead.position(srcStart)
                    yRead.get(yData, row * imageProxy.width, copyLen)
                }
            } else {
                for (row in 0 until imageProxy.height) {
                    val srcStart = yBase + row * yRowStride
                    val dstStart = row * imageProxy.width
                    if (srcStart >= yBufferLimit) break
                    for (col in 0 until imageProxy.width) {
                        val srcIndex = srcStart + col * yPixelStride
                        if (srcIndex >= yBufferLimit) break
                        yData[dstStart + col] = yBuffer.get(srcIndex)
                    }
                }
            }

            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val swResult = laneDetector.detectLanesFromYUV(
                yData, imageProxy.width, imageProxy.height, rotationDegrees
            )

            // Upright dimensions — the buffer is frequently landscape-oriented
            // while the UI (and the lane model) work in the upright frame.
            // Normalized like every other consumer of rotationDegrees.
            val uprightRotation = ((rotationDegrees % 360) + 360) % 360
            val uprightLandscape = uprightRotation == 0 || uprightRotation == 180
            val uprightWidth = if (uprightLandscape) imageProxy.width else imageProxy.height
            val uprightHeight = if (uprightLandscape) imageProxy.height else imageProxy.width

            var tfliteDriftL = false
            var tfliteDriftR = false
            var tfliteConf = 0.05f
            var tfliteCenterOffset = 0f

            ensureModelLoaded()
            val runner = tfliteRunner
            if (runner != null && runner.isLoaded()) {
                try {
                    val segmentation = runner.runSegmentationYUV(
                        yBuffer, yPlane.rowStride, yPlane.pixelStride,
                        uBuffer, uPlane.rowStride, uPlane.pixelStride,
                        vBuffer, vPlane.rowStride, vPlane.pixelStride,
                        imageProxy.width, imageProxy.height, rotationDegrees
                    )
                    val segResult = runner.detectLanesFromSegmentation(
                        segmentation[0],
                        uprightWidth,
                        uprightHeight
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
                // smoothLane() returns a stale extrapolation with valid=false for a
                // missed detection; reporting it as "visible" lied in the HUD.
                leftLaneVisible = swResult.leftLane?.valid == true,
                rightLaneVisible = swResult.rightLane?.valid == true,
                leftCurve = toDomainCurve(swResult.leftLane),
                rightCurve = toDomainCurve(swResult.rightLane),
                imageWidth = swResult.imageWidth,
                imageHeight = swResult.imageHeight
            )

            detectVehicles(inputImage, imageProxy, uprightHeight)
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
    private fun detectVehicles(inputImage: InputImage, imageProxy: ImageProxy, uprightImageHeight: Int) {
        // close() läuft IMMER im onCompleteListener, nie synchron davor.
        // So vermeiden wir "trying to use closed ImageProxy"-Crashes, die
        // auftreten, wenn ML Kit noch auf die underlying mediaImage-Buffer
        // zugreift während wir sie schon zurückgeben.
        objectDetector.process(inputImage)
            .addOnSuccessListener { detectedObjects ->
                if (closed) return@addOnSuccessListener
                processVehicleResult(detectedObjects, uprightImageHeight)
            }
            .addOnFailureListener {
                if (closed) return@addOnFailureListener
                _vehicleDistance.value = null
            }
            .addOnCompleteListener {
                try {
                    // Always release CameraX's frame, including during shutdown.
                    // Skipping close when `closed` stalls the image queue.
                    imageProxy.close()
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
            // Do not blend distance/TTC across different objects. Switching from
            // a far car to a nearer truck otherwise looks like impossible closing
            // speed and immediately triggers a collision warning.
            if (!isSameTrackedVehicle(closestVehicle.boundingBox)) {
                distanceHistory.clear()
                prevDistance = null
                prevTime = 0L
            }
            trackedBox = Rect(closestVehicle.boundingBox)

            distanceHistory.addLast(closestVehicle.distance)
            if (distanceHistory.size > 5) distanceHistory.removeFirst()

            // Median is robust to one-frame bounding-box jitter (mean is not).
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

    /**
     * A fast-approaching vehicle's box grows quickly, so plain IoU drops it and
     * suppresses the TTC exactly when it matters. Fall back to centre proximity.
     */
    private fun isSameTrackedVehicle(box: Rect): Boolean {
        val previous = trackedBox ?: return false
        if (intersectionOverUnion(previous, box) > 0.15f) return true
        val dx = abs(previous.centerX() - box.centerX()).toFloat()
        val dy = abs(previous.centerY() - box.centerY()).toFloat()
        val tolerance = maxOf(previous.width(), previous.height(), box.width(), box.height()) * 0.35f
        return dx < tolerance && dy < tolerance
    }

    private fun estimateDistance(boundingBox: Rect, imageHeight: Int): Float =
        vehiclePipeline.estimateDistance(VehicleBox(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom), imageHeight)

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
