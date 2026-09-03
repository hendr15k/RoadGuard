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
    private var ufldDetector: UfldLaneDetector? = null
    private var ufldLoadAttempted = false

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
        // Only construct the runners here; loading maps the model and builds the
        // native interpreter, which must not happen during composition.
        appContext?.let { ctx ->
            try {
                tfliteRunner = TfliteModelRunner(ctx)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                ufldDetector = UfldLaneDetector(ctx)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private val modelLock = Any()

    private fun ensureUfldLoaded(): UfldLaneDetector? {
        if (ufldLoadAttempted) return ufldDetector?.takeIf { it.isLoaded() }
        synchronized(modelLock) {
            if (ufldLoadAttempted) return ufldDetector?.takeIf { it.isLoaded() }
            ufldLoadAttempted = true
            try {
                ufldDetector?.loadModel()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return ufldDetector?.takeIf { it.isLoaded() }
    }

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
            // UFLD first: direct lane points instead of histogram hunting. Falls
            // back to classic CV when no model is present or no ego pair is
            // found; DeepLab stays as the last-resort drift signal.
            //
            // ORDER MATTERS: classic CV (LaneDetector.detectLanes, ~50-60 ms)
            // must NOT run before UFLD on every frame. detectLanes() costs a
            // full BEV warp + histogram pass even when its result is discarded
            // — on the emulator that starved the UI thread and produced ANRs.
            // Run UFLD first; classic CV only as fallback when UFLD misses.
            val ufld = ensureUfldLoaded()
            var ufldResult: UfldLaneDetector.UfldResult? = null
            if (ufld != null) {
                try {
                    ufldResult = ufld.detect(bitmap)
                } catch (e: Exception) {
                    e.printStackTrace()
                    ufldResult = null
                }
            }
            val ufldOk = ufldResult != null && (ufldResult.left != null || ufldResult.right != null)

            // Lazy classic-CV fallback: only computed when UFLD has no ego
            // pair. Held nullable so the log line and the fallback branch can
            // share one invocation per frame.
            var swResult: LaneDetector.LaneDetectionResult? = null
            fun sw(): LaneDetector.LaneDetectionResult {
                var r = swResult
                if (r == null) {
                    r = laneDetector.detectLanes(bitmap)
                    swResult = r
                }
                return r
            }

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

            val finalIsDriftingLeft: Boolean
            val finalIsDriftingRight: Boolean
            val finalConfidence: Float
            val finalCenterOffset: Float
            val finalLaneWidth: Float
            val leftMark: String
            val rightMark: String
            val leftCurve: com.roadguard.app.domain.model.LaneCurve
            val rightCurve: com.roadguard.app.domain.model.LaneCurve
            if (ufldOk) {
                // UFLD won: fit curves through its points, derive offset/width
                // from the fitted pair. Classic CV is skipped entirely.
                // Span-gated: stub curves (short y-range, e.g. curb) come
                // back invalid -> that side is not drawn and drops to the
                // classic fallback below instead of floating in the sky.
                val curves = ufldCurvesToDomain(ufldResult!!, bitmap.height)
                val leftOk = curves.first.valid
                val rightOk = curves.second.valid
                if (!leftOk && !rightOk) {
                    // Both stubs: treat as UFLD miss, run classic fallback.
                    val cv = sw()
                    finalIsDriftingLeft = if (cv.confidence > 0.3f) cv.isDriftingLeft else tfliteDriftL
                    finalIsDriftingRight = if (cv.confidence > 0.3f) cv.isDriftingRight else tfliteDriftR
                    finalConfidence = maxOf(cv.confidence, tfliteConf)
                    finalCenterOffset = if (cv.confidence > 0.3f) cv.centerOffset else tfliteCenterOffset
                    finalLaneWidth = cv.laneWidth
                    leftMark = if (cv.leftLane?.valid == true) "L" else "-"
                    rightMark = if (cv.rightLane?.valid == true) "R" else "-"
                    leftCurve = cv.leftLane?.let { l ->
                        com.roadguard.app.domain.model.LaneCurve(
                            a = l.polyA, b = l.polyB, c = l.polyC,
                            yStart = l.yStart, yEnd = l.yEnd, valid = l.valid
                        )
                    } ?: com.roadguard.app.domain.model.LaneCurve()
                    rightCurve = cv.rightLane?.let { l ->
                        com.roadguard.app.domain.model.LaneCurve(
                            a = l.polyA, b = l.polyB, c = l.polyC,
                            yStart = l.yStart, yEnd = l.yEnd, valid = l.valid
                        )
                    } ?: com.roadguard.app.domain.model.LaneCurve()
                } else {
                    val ufldOff = ufldCenterOffset(ufldResult, bitmap.width)
                    val driftGate = 0.04f * (1.5f - laneSensitivity)
                    finalIsDriftingLeft = ufldOff < -bitmap.width * driftGate && ufldResult.confidence > 0.4f
                    finalIsDriftingRight = ufldOff > bitmap.width * driftGate && ufldResult.confidence > 0.4f
                    finalConfidence = ufldResult.confidence
                    finalCenterOffset = ufldOff
                    finalLaneWidth = ufldLaneWidth(ufldResult)
                    leftMark = if (ufldResult.left != null && leftOk) "L" else "-"
                    rightMark = if (ufldResult.right != null && rightOk) "R" else "-"
                    leftCurve = curves.first
                    rightCurve = curves.second
                }
            } else {
                val cv = sw()
                finalIsDriftingLeft = if (cv.confidence > 0.3f) cv.isDriftingLeft else tfliteDriftL
                finalIsDriftingRight = if (cv.confidence > 0.3f) cv.isDriftingRight else tfliteDriftR
                finalConfidence = maxOf(cv.confidence, tfliteConf)
                finalCenterOffset = if (cv.confidence > 0.3f) cv.centerOffset else tfliteCenterOffset
                finalLaneWidth = cv.laneWidth
                leftMark = if (cv.leftLane?.valid == true) "L" else "-"
                rightMark = if (cv.rightLane?.valid == true) "R" else "-"
                leftCurve = cv.leftLane?.let { l ->
                    com.roadguard.app.domain.model.LaneCurve(
                        a = l.polyA, b = l.polyB, c = l.polyC,
                        yStart = l.yStart, yEnd = l.yEnd, valid = l.valid
                    )
                } ?: com.roadguard.app.domain.model.LaneCurve()
                rightCurve = cv.rightLane?.let { l ->
                    com.roadguard.app.domain.model.LaneCurve(
                        a = l.polyA, b = l.polyB, c = l.polyC,
                        yStart = l.yStart, yEnd = l.yEnd, valid = l.valid
                    )
                } ?: com.roadguard.app.domain.model.LaneCurve()
            }
            // Log sw= lazily: evaluating it would run classic CV on every
            // frame even when UFLD won (defeating the lazy fallback above).
            // -1.00 marks "not computed" in the log.
            val swConf = if (swResult != null) sw()?.confidence ?: -1f else -1f
            android.util.Log.d("LaneTracking",
                "sw=%.2f tf=%.2f cf=%.2f dL=%b dR=%b lanes=%s offset=%.1f width=%.0f".format(
                    swConf, tfliteConf, finalConfidence,
                    finalIsDriftingLeft, finalIsDriftingRight,
                    leftMark + rightMark, finalCenterOffset, finalLaneWidth
                )
            )

            val ufldActive = ufldOk && ufldResult != null
            // imageWidth/Height for the overlay transform: UFLD path has no
            // classic result, so fall back to the raw frame size.
            val refW = swResult?.imageWidth ?: bitmap.width
            val refH = swResult?.imageHeight ?: bitmap.height
            _laneInfo.value = LaneInfo(
                isDriftingLeft = finalIsDriftingLeft,
                isDriftingRight = finalIsDriftingRight,
                confidence = finalConfidence,
                centerOffset = finalCenterOffset,
                laneWidth = finalLaneWidth,
                // smoothLane() returns a stale extrapolation with valid=false for a
                // missed detection; reporting it as "visible" lied in the HUD.
                leftLaneVisible = if (ufldActive) leftMark == "L" else sw()?.leftLane?.valid == true,
                rightLaneVisible = if (ufldActive) rightMark == "R" else sw()?.rightLane?.valid == true,
                leftCurve = leftCurve,
                rightCurve = rightCurve,
                imageWidth = refW,
                imageHeight = refH
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

    // === UFLD helpers: points -> domain curves / offset / width ===
    // Mirror of MlDetectionAnalyzer: the video path consumes the same raw
    // polylines but owns its own copy (no shared base class yet).

    private fun fitQuadratic(
        xs: FloatArray, ys: FloatArray
    ): Triple<Float, Float, Float>? {
        if (xs.size < 3 || xs.size != ys.size) return null
        val n = xs.size
        var sY = 0.0; var sY2 = 0.0; var sY3 = 0.0; var sY4 = 0.0
        var sX = 0.0; var sXY = 0.0; var sXY2 = 0.0
        for (i in 0 until n) {
            val x = xs[i].toDouble(); val y = ys[i].toDouble()
            val y2 = y * y
            sY += y; sY2 += y2; sY3 += y2 * y; sY4 += y2 * y2
            sX += x; sXY += x * y; sXY2 += x * y2
        }
        fun det3(m: Array<DoubleArray>): Double {
            return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
                m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
        }
        val m = arrayOf(
            doubleArrayOf(sY4, sY3, sY2),
            doubleArrayOf(sY3, sY2, sY),
            doubleArrayOf(sY2, sY, n.toDouble())
        )
        val d = det3(m)
        if (kotlin.math.abs(d) < 1e-9) return null
        val mx = arrayOf(
            doubleArrayOf(sXY2, sY3, sY2),
            doubleArrayOf(sXY, sY2, sY),
            doubleArrayOf(sX, sY, n.toDouble())
        )
        val my = arrayOf(
            doubleArrayOf(sY4, sXY2, sY2),
            doubleArrayOf(sY3, sXY, sY),
            doubleArrayOf(sY2, sX, n.toDouble())
        )
        val mz = arrayOf(
            doubleArrayOf(sY4, sY3, sXY2),
            doubleArrayOf(sY3, sY2, sXY),
            doubleArrayOf(sY2, sY, sX)
        )
        val a = (det3(mx) / d).toFloat()
        if (kotlin.math.abs(a) > 0.5f) return null
        return Triple(a, (det3(my) / d).toFloat(), (det3(mz) / d).toFloat())
    }

    private fun ufldPointsToCurve(
        pts: UfldLaneDetector.LanePoints?,
        imgH: Int = 0
    ): com.roadguard.app.domain.model.LaneCurve {
        if (pts == null || pts.size < 3) return com.roadguard.app.domain.model.LaneCurve()
        var yMin = pts.y[0]; var yMax = pts.y[0]
        for (y in pts.y) {
            if (y < yMin) yMin = y
            if (y > yMax) yMax = y
        }
        // Span gate: a real ego boundary runs most of the frame height.
        // UFLD sometimes returns a short stub (a few rows near the horizon,
        // e.g. a curb fragment) that fitQuadratic happily fits — the overlay
        // then extrapolates the stub across the whole frame and the corridor
        // floats in the sky. Reject stubs instead of drawing them.
        // pts.y are IMAGE pixels (runInference scales CFG->image), so the
        // span is tested against the real frame height.
        if (imgH > 0 && yMax - yMin < imgH * 0.35f) {
            return com.roadguard.app.domain.model.LaneCurve()
        }
        val abc = fitQuadratic(pts.x, pts.y) ?: return com.roadguard.app.domain.model.LaneCurve()
        return com.roadguard.app.domain.model.LaneCurve(
            a = abc.first, b = abc.second, c = abc.third,
            yStart = yMin, yEnd = yMax, valid = true
        )
    }

    private fun ufldCurvesToDomain(
        res: UfldLaneDetector.UfldResult,
        imgH: Int = 0
    ): Pair<com.roadguard.app.domain.model.LaneCurve, com.roadguard.app.domain.model.LaneCurve> {
        return Pair(ufldPointsToCurve(res.left, imgH), ufldPointsToCurve(res.right, imgH))
    }

    private fun ufldLaneCenterX(pts: UfldLaneDetector.LanePoints?): Float? {
        if (pts == null || pts.size == 0) return null
        var maxY = Float.NEGATIVE_INFINITY
        var xAtMaxY = 0f
        for (i in 0 until pts.size) {
            if (pts.y[i] > maxY) {
                maxY = pts.y[i]
                xAtMaxY = pts.x[i]
            }
        }
        return xAtMaxY
    }

    private fun ufldCenterOffset(res: UfldLaneDetector.UfldResult, imgW: Int): Float {
        val lx = ufldLaneCenterX(res.left)
        val rx = ufldLaneCenterX(res.right)
        val vehicleCenter = imgW * 0.5f
        return when {
            lx != null && rx != null -> vehicleCenter - (lx + rx) / 2f
            lx != null -> vehicleCenter - lx - 150f
            rx != null -> vehicleCenter - rx + 150f
            else -> 0f
        }
    }

    private fun ufldLaneWidth(res: UfldLaneDetector.UfldResult): Float {
        val lx = ufldLaneCenterX(res.left) ?: return 0f
        val rx = ufldLaneCenterX(res.right) ?: return 0f
        return (rx - lx).coerceIn(80f, 500f)
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
            try {
                ufldDetector?.close()
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
