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

    /** Bottom share of the frame occupied by the car hood; excluded from detection. */
    @Volatile
    private var hoodFraction: Float = 0.08f

    fun updateHoodFraction(value: Float) {
        hoodFraction = value
        laneDetector.updateHoodFraction(value)
        ufldDetector?.updateHoodFraction(value)
    }
    private val objectDetector: ObjectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .build()
    )

    private val laneDetector = LaneDetector(laneSensitivity)
    private var ufldDetector: UfldLaneDetector? = null
    private var ufldRetryAtMs = 0L
    private companion object {
        /** Minimum gap between UFLD (re-)load attempts. */
        private const val UFLD_RETRY_COOLDOWN_MS = 30_000L
    }

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
    /** When the last frame carrying a detection was captured (frame time, not callback time). */
    @Volatile
    private var imageCapturedAtMs: Long = 0
    /** When a detection was last processed — drives the "vehicle left the frame" gap check. */
    @Volatile
    private var vehicleSampleAtMs: Long = 0
    private val distanceHistory = ArrayDeque<Float>(5)

    // Camera parameters (approximate for typical smartphone)
    private var focalLengthPixels = 1500f
    private val vehicleHeightMeters = 1.5f

    @Volatile
    private var closed = false

    /**
     * Temporal median of classic-CV offsets — same justification as
     * [MlDetectionAnalyzer]: the fallback's per-frame flags warn on single
     * noisy frames, so the offset is re-gated below.
     */
    private val classicOffsetWindow = LaneOffsetWindow()
    @Volatile
    private var classicPrevOffset: Float? = null

    /** Guards the ML Kit detector so close() cannot dispose it mid-frame. */
    private val detectorLock = Any()

    /**
     * One lane sample per frame max: repeated identical emissions re-stamped
     * the alert gate with "now" and held a stale hazard open forever. The
     * sample therefore carries the frame's capture time.
     */

    init {
        // Only construct the runners here; loading maps the model and builds the
        // native interpreter, which must not happen during composition.
        appContext?.let { ctx ->
            try {
                ufldDetector = UfldLaneDetector(ctx)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private val modelLock = Any()

    private fun ensureUfldLoaded(): UfldLaneDetector? {
        ufldDetector?.takeIf { it.isLoaded() }?.let {
            it.updateHoodFraction(hoodFraction)
            return it
        }
        synchronized(modelLock) {
            ufldDetector?.takeIf { it.isLoaded() }?.let {
                it.updateHoodFraction(hoodFraction)
                return it
            }
            // Retry with cooldown instead of a one-shot flag: a model
            // downloaded later (or a transient load failure) previously
            // needed a full process restart to take effect.
            val now = System.currentTimeMillis()
            if (now - ufldRetryAtMs < UFLD_RETRY_COOLDOWN_MS) return null
            ufldRetryAtMs = now
            try {
                ufldDetector?.loadModel()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return ufldDetector?.takeIf { it.isLoaded() }?.also {
            it.updateHoodFraction(hoodFraction)
        }
    }

    fun analyzeFrame(bitmap: Bitmap, height: Int) {
        if (closed) {
            bitmap.recycle()
            return
        }
        // The whole frame — lane inference included — is serialized with
        // close(): the lane/TFLite work runs off the ML Kit callback, so a
        // rotation disposing the interpreter mid-detect() would otherwise be a
        // native use-after-free. The bitmap is handed to ML Kit from inside the
        // same lock, so it stays alive across the hand-off.
        synchronized(detectorLock) {
            if (closed) {
                bitmap.recycle()
                return
            }
            analyzeFrameLocked(bitmap, height)
        }
    }

    private fun analyzeFrameLocked(bitmap: Bitmap, height: Int) {
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
            // found.
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
            val ufldOk = ufld != null && ufldResult != null &&
                (ufldResult.left != null || ufldResult.right != null)

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
                    val gate = classicGate(cv, bitmap.width)
                    finalIsDriftingLeft = gate.first
                    finalIsDriftingRight = gate.second
                    finalConfidence = cv.confidence
                    finalCenterOffset = gate.third
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
                    // Mirrored/hold samples are guesses, not measurements (see
                    // the camera path): read the stale median instead of
                    // polluting it with a fabricated offset.
                    val ufldOff = if (ufldResult.offsetTrusted) {
                        ufld!!.smoothedOffset(ufldResult.offsetPx)
                    } else {
                        ufld!!.peekSmoothedOffset()
                    }
                    val historyLen = ufld.offsetHistorySize()
                    val laneWidthPx = ufld.measuredLaneWidthPx()
                    // Same gate as the camera path ([MlDetectionAnalyzer]): a
                    // stub side that the span gate rejected must not feed the
                    // offset helper and trigger a warning for a lane the
                    // overlay hides, and the mirror fallback is a guess.
                    finalIsDriftingLeft = LaneDriftGate.isDriftingLeft(
                        centerOffset = ufldOff,
                        frameWidth = bitmap.width,
                        sensitivity = laneSensitivity,
                        confidence = ufldResult.confidence,
                        leftCurveValid = leftOk,
                        rightCurveValid = rightOk,
                        historySize = historyLen,
                        laneWidth = laneWidthPx
                    )
                    finalIsDriftingRight = LaneDriftGate.isDriftingRight(
                        centerOffset = ufldOff,
                        frameWidth = bitmap.width,
                        sensitivity = laneSensitivity,
                        confidence = ufldResult.confidence,
                        leftCurveValid = leftOk,
                        rightCurveValid = rightOk,
                        historySize = historyLen,
                        laneWidth = laneWidthPx
                    )
                    finalConfidence = ufldResult.confidence
                    finalCenterOffset = ufldOff
                    finalLaneWidth = laneWidthPx.takeIf { it > 1f } ?: ufldLaneWidth(ufldResult)
                    leftMark = if (ufldResult.left != null && leftOk) "L" else "-"
                    rightMark = if (ufldResult.right != null && rightOk) "R" else "-"
                    leftCurve = curves.first
                    rightCurve = curves.second
                }
            } else {
                val cv = sw()
                val gate = classicGate(cv, bitmap.width)
                finalIsDriftingLeft = gate.first
                finalIsDriftingRight = gate.second
                finalConfidence = cv.confidence
                finalCenterOffset = gate.third
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
                "sw=%.2f cf=%.2f dL=%b dR=%b lanes=%s offset=%.1f width=%.0f".format(
                    swConf, finalConfidence,
                    finalIsDriftingLeft, finalIsDriftingRight,
                    leftMark + rightMark, finalCenterOffset, finalLaneWidth
                )
            )

            val ufldActive = ufldOk && ufldResult != null
            // imageWidth/Height for the overlay transform: UFLD path has no
            // classic result, so fall back to the raw frame size.
            val refW = swResult?.imageWidth ?: bitmap.width
            val refH = swResult?.imageHeight ?: bitmap.height
            val laneSample = LaneInfo(
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
                imageHeight = refH,
                timestamp = currentTime
            )
            // timestamp = the FRAME's capture time, not the emission instant:
            // for a paused video the frame can be re-analysed seconds after it
            // was captured, and the alert gate expires samples by this value.
            // No same-millisecond guard: the 200 ms throttle already yields one
            // distinct stamp per processed frame.
            _laneInfo.value = laneSample

            val inputImage = InputImage.fromBitmap(bitmap, 0)
            // Already inside detectorLock (see analyzeFrame); the re-entrant
            // monitor keeps the existing nesting harmless.
            synchronized(detectorLock) {
                // `return` here would skip the finally and leak the bitmap.
                if (!closed) {
                    detectVehicles(inputImage, bitmap, height, bitmap.width, currentTime)
                    handedToMlKit = true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (!handedToMlKit && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun detectVehicles(inputImage: InputImage, bitmapToRecycle: Bitmap, imageHeight: Int, imageWidth: Int, capturedAtMs: Long) {
        objectDetector.process(inputImage)
            .addOnSuccessListener { detectedObjects ->
                // Serialize with close() and other in-flight callbacks: frames
                // can overlap (throttle < ML Kit latency) and the tracking
                // state below is mutated from callback threads.
                synchronized(detectorLock) {
                    if (closed) return@addOnSuccessListener
                    processVehicleResult(detectedObjects, imageHeight, imageWidth, capturedAtMs)
                }
            }
            .addOnFailureListener {
                // closed ist @Volatile, der Read unter Lock serialisiert
                // zusätzlich gegen einen parallel laufenden Success-Callback.
                synchronized(detectorLock) {
                    if (closed) return@addOnFailureListener
                    _vehicleDistance.value = null
                }
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
        imageHeight: Int,
        imageWidth: Int,
        capturedAtMs: Long
    ) {
        imageCapturedAtMs = capturedAtMs
        // Delegates label- vs geometry-fallback to the JVM-tested VehiclePipeline.
        // The base ML Kit model never emits Vehicle/Car labels (only
        // fashion/food/home/plants/places), so geometry fallback is required.
        val detections = detectedObjects.map { obj ->
            VehiclePipeline.Detection(
                VehicleBox(obj.boundingBox.left, obj.boundingBox.top, obj.boundingBox.right, obj.boundingBox.bottom),
                obj.labels.map { VehiclePipeline.Label(it.text, it.confidence) }
            )
        }
        val candidate = vehiclePipeline.selectClosestVehicle(detections, imageHeight, imageWidth)
        var closestVehicle: DetectedVehicle? = null
        if (candidate != null) {
            // The frame width decides the pixel focal length — see
            // VehiclePipeline.focalLengthPixels.
            val distance = vehiclePipeline.estimateDistance(candidate.boundingBox, imageHeight, imageWidth)
            closestVehicle = DetectedVehicle(candidate.boundingBox.toRect(), distance, candidate.label)
        }

        if (closestVehicle != null) {
            val currentTime = System.currentTimeMillis()
            vehicleSampleAtMs = currentTime
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
                // The FRAME's capture time, not the ML Kit callback instant: the
                // whole point of the timestamp is to expire detections whose
                // frames stopped arriving (paused video / stalled pipeline), and
                // a callback that lands late must not refresh that.
                timestamp = imageCapturedAtMs
            )
        } else {
            val gapSec = if (vehicleSampleAtMs > 0L) (System.currentTimeMillis() - vehicleSampleAtMs) / 1000f else 0f
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

    /**
     * Classic-CV fallback drift, on the same terms as the UFLD path.
     * See [MlDetectionAnalyzer] for why the fallback's own flags are not used.
     */
    private fun classicGate(
        cv: LaneDetector.LaneDetectionResult,
        frameWidth: Int
    ): Triple<Boolean, Boolean, Float> {
        val smoothed = classicOffsetWindow.add(cv.centerOffset)
        val historyLen = classicOffsetWindow.size
        val leftOk = cv.leftLane?.valid == true
        val rightOk = cv.rightLane?.valid == true
        val implausible = classicPrevOffset?.let { prev ->
            LaneDriftGate.isImplausibleStep(prev, smoothed, cv.laneWidth)
        } ?: false
        classicPrevOffset = smoothed
        if (implausible) return Triple(false, false, smoothed)
        val left = LaneDriftGate.isDriftingLeft(
            centerOffset = smoothed,
            frameWidth = frameWidth,
            sensitivity = laneSensitivity,
            confidence = cv.confidence,
            leftCurveValid = leftOk,
            rightCurveValid = rightOk,
            historySize = historyLen,
            laneWidth = cv.laneWidth
        )
        val right = LaneDriftGate.isDriftingRight(
            centerOffset = smoothed,
            frameWidth = frameWidth,
            sensitivity = laneSensitivity,
            confidence = cv.confidence,
            leftCurveValid = leftOk,
            rightCurveValid = rightOk,
            historySize = historyLen,
            laneWidth = cv.laneWidth
        )
        return Triple(left, right, smoothed)
    }

    private fun ufldPointsToCurve(
        pts: UfldLaneDetector.LanePoints?,
        imgH: Int = 0
    ): com.roadguard.app.domain.model.LaneCurve =
        LaneGeometry.curveOf(pts, imgH, hoodFraction)

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
        // Same containment rule as MlDetectionAnalyzer: centre proximity alone
        // let a newly appearing nearer vehicle inherit the followed car's track.
        val previous = trackedBox ?: return false
        if (intersectionOverUnion(previous, box) > 0.15f) return true
        val dx = abs(previous.centerX() - box.centerX()).toFloat()
        val dy = abs(previous.centerY() - box.centerY()).toFloat()
        val tolerance = maxOf(previous.width(), previous.height(), box.width(), box.height()) * 0.35f
        val contained = box.left >= previous.left && box.right <= previous.right &&
            box.top >= previous.top && box.bottom <= previous.bottom
        return contained && dx < tolerance && dy < tolerance
    }

    /** Dead copy of the distance formula: [VehiclePipeline] is the only live path. */
    @Suppress("unused")
    private fun estimateDistanceUnused(boundingBox: Rect, imageHeight: Int): Float {
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
