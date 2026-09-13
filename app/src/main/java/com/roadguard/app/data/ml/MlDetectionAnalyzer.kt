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
    /** When the last frame carrying a detection was captured (frame time, not callback time). */
    @Volatile
    private var imageCapturedAtMs: Long = 0
    /** When a detection was last processed — drives the "vehicle left the frame" gap check. */
    @Volatile
    private var vehicleSampleAtMs: Long = 0
    // Only ever touched from the single analyzer thread (analyze() is called
    // serially for one ImageAnalysis use case), unlike the @Volatile fields
    // above which are also read from ML Kit's callback thread.
    private val distanceHistory = ArrayDeque<Float>(5)
    /**
     * Temporal median of classic-CV offsets. The fallback's own per-frame
     * threshold warned on single noisy frames; the smoothed offset here gets
     * the same [LaneDriftGate] treatment as the UFLD path (history floor +
     * lane-relative window) instead of trusting `sw()`'s flags.
     */
    private val classicOffsetWindow = LaneOffsetWindow()
    @Volatile
    private var classicPrevOffset: Float? = null

    // Camera parameters (approximate for typical smartphone)
    private var focalLengthPixels = 1500f // More accurate default for ~1080p smartphones
    private val vehicleHeightMeters = 1.5f // Average car height

    @Volatile
    private var closed = false

    /** Guards the ML Kit detector so close() cannot dispose it mid-frame. */
    private val detectorLock = Any()
    private var ufldRetryAtMs = 0L

    /**
     * One lane sample per frame max — the gate's confirmation window assumes
     * the ~5 Hz detection rate, and (worse) every repetition used to be a
     * "current frame" for the freshness check, so one detection held the
     * hazard open forever. The sample therefore carries the frame's capture
     * time and is only published when it changed.
     */
    private companion object {
        /** Minimum gap between UFLD (re-)load attempts. */
        private const val UFLD_RETRY_COOLDOWN_MS = 30_000L
    }

    init {
        // Only construct the runners here. Mapping models and building the
        // native interpreters belongs on the analyzer thread, not in composition.
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

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        if (closed) {
            imageProxy.close()
            return
        }
        // Captured before the throttle: the lane sample below is stamped with
        // it further down, where `currentTime` is no longer in scope.
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

            // Upright dimensions — the buffer is frequently landscape-oriented
            // while the UI (and the lane model) work in the upright frame.
            // Normalized like every other consumer of rotationDegrees.
            val uprightRotation = ((rotationDegrees % 360) + 360) % 360
            val uprightLandscape = uprightRotation == 0 || uprightRotation == 180
            val uprightWidth = if (uprightLandscape) imageProxy.width else imageProxy.height
            val uprightHeight = if (uprightLandscape) imageProxy.height else imageProxy.width

            // Classic CV is a lazy fallback: detectLanesFromYUV costs ~50-60 ms
            // (blur + BEV warp + edge/road-mask + pair/Hough). Running it on
            // every frame even when UFLD wins starved the analyzer thread; the
            // video path already runs it lazily for the same reason.
            var swResult: LaneDetector.LaneDetectionResult? = null
            fun sw(): LaneDetector.LaneDetectionResult {
                var r = swResult
                if (r == null) {
                    r = laneDetector.detectLanesFromYUV(
                        yData, imageProxy.width, imageProxy.height, rotationDegrees
                    )
                    swResult = r
                }
                return r
            }

            // UFLD first: direct lane points instead of histogram hunting. Falls
            // back to classic CV when no model is present or no ego pair is
            // found.
            val ufld = ensureUfldLoaded()
            var ufldResult: UfldLaneDetector.UfldResult? = null
            if (ufld != null) {
                try {
                    val uprightBitmap = yuvToUprightBitmap(
                        yBuffer, yPlane.rowStride, yPlane.pixelStride,
                        uBuffer, uPlane.rowStride, uPlane.pixelStride,
                        vBuffer, vPlane.rowStride, vPlane.pixelStride,
                        imageProxy.width, imageProxy.height, rotationDegrees
                    )
                    if (uprightBitmap != null) {
                        try {
                            ufldResult = ufld.detect(uprightBitmap)
                        } finally {
                            uprightBitmap.recycle()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            val ufldOk = ufld != null && ufldResult != null &&
                (ufldResult.left != null || ufldResult.right != null)

            val finalIsDriftingLeft: Boolean
            val finalIsDriftingRight: Boolean
            val finalConfidence: Float
            val finalCenterOffset: Float
            val finalLaneWidth: Float
            val leftVisible: Boolean
            val rightVisible: Boolean
            val leftCurve: com.roadguard.app.domain.model.LaneCurve
            val rightCurve: com.roadguard.app.domain.model.LaneCurve
            if (ufldOk) {
                // UFLD won: fit curves through its points, derive offset/width
                // from the fitted pair. Classic CV is skipped entirely.
                // Span-gated like the video path: stub sides come back
                // invalid and are hidden instead of floating in the sky.
                val ufldCurves = ufldCurvesToDomain(ufldResult!!, uprightHeight)
                val leftOk = ufldCurves.first.valid
                val rightOk = ufldCurves.second.valid
                if (!leftOk && !rightOk) {
                    // Both sides are span-gated stubs: UFLD has nothing to
                    // draw or measure. Fall back to classic CV exactly like the
                    // video path instead of reporting a fabricated offset from
                    // a stub the overlay refuses to show.
                    val cv = sw()
                    val gate = classicGate(cv, uprightWidth)
                    finalIsDriftingLeft = gate.first
                    finalIsDriftingRight = gate.second
                    finalConfidence = cv.confidence
                    finalCenterOffset = gate.third
                    finalLaneWidth = cv.laneWidth
                    leftVisible = cv.leftLane?.valid == true
                    rightVisible = cv.rightLane?.valid == true
                    leftCurve = toDomainCurve(cv.leftLane)
                    rightCurve = toDomainCurve(cv.rightLane)
                } else {
                    // Offset from the detector: both boundaries evaluated at ONE
                    // row, then the temporal median — the raw per-frame value
                    // jitters by more than the drift window on a straight road.
                    //
                    // Mirrored/hold samples are NOT measurements: the detector
                    // marks them offsetTrusted=false, and feeding them into the
                    // median would bias the NEXT warnings after a one-lane
                    // stretch ends. The stale median is read out instead, and
                    // the gate's pair/completeness rule stays quiet anyway.
                    val ufldOff = if (ufldResult.offsetTrusted) {
                        ufld!!.smoothedOffset(ufldResult.offsetPx)
                    } else {
                        ufld!!.peekSmoothedOffset()
                    }
                    val historyLen = ufld.offsetHistorySize()
                    val laneWidthPx = ufld.measuredLaneWidthPx()
                    // A departure warning needs a complete, confident pair: a stub
                    // side (span-gated to invalid) would otherwise still feed the
                    // offset helper its sky-high bottom point and alarm for a lane
                    // the overlay refuses to draw; the single-lane mirror is a
                    // guess and is capped below this floor for that reason.
                    finalIsDriftingLeft = LaneDriftGate.isDriftingLeft(
                        centerOffset = ufldOff,
                        frameWidth = uprightWidth,
                        sensitivity = laneSensitivity,
                        confidence = ufldResult.confidence,
                        leftCurveValid = leftOk,
                        rightCurveValid = rightOk,
                        historySize = historyLen,
                        laneWidth = laneWidthPx
                    )
                    finalIsDriftingRight = LaneDriftGate.isDriftingRight(
                        centerOffset = ufldOff,
                        frameWidth = uprightWidth,
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
                    leftVisible = ufldResult.left != null && leftOk
                    rightVisible = ufldResult.right != null && rightOk
                    leftCurve = ufldCurves.first
                    rightCurve = ufldCurves.second
                }
            } else {
                val cv = sw()
                val gate = classicGate(cv, uprightWidth)
                finalIsDriftingLeft = gate.first
                finalIsDriftingRight = gate.second
                finalConfidence = cv.confidence
                finalCenterOffset = gate.third
                finalLaneWidth = cv.laneWidth
                leftVisible = cv.leftLane?.valid == true
                rightVisible = cv.rightLane?.valid == true
                leftCurve = toDomainCurve(cv.leftLane)
                rightCurve = toDomainCurve(cv.rightLane)
            }

            val laneSample = LaneInfo(
                isDriftingLeft = finalIsDriftingLeft,
                isDriftingRight = finalIsDriftingRight,
                confidence = finalConfidence,
                centerOffset = finalCenterOffset,
                laneWidth = finalLaneWidth,
                // smoothLane() returns a stale extrapolation with valid=false for a
                // missed detection; reporting it as "visible" lied in the HUD.
                leftLaneVisible = leftVisible,
                rightLaneVisible = rightVisible,
                leftCurve = leftCurve,
                rightCurve = rightCurve,
                imageWidth = swResult?.imageWidth ?: uprightWidth,
                imageHeight = swResult?.imageHeight ?: uprightHeight,
                timestamp = currentTime
            )
            // The sample carries the frame's capture time (the default stamp
            // would be the emission instant, which is the same here since this
            // runs on the analysis thread). No same-millisecond guard: the
            // 200 ms throttle above already guarantees one distinct stamp per
            // processed frame, so such a guard would be unreachable.
            _laneInfo.value = laneSample

            // Close must be serialized with a frame still in process: closing
            // the detector mid-frame crashes ("Cannot use closed Detector").
            synchronized(detectorLock) {
                if (closed) {
                    imageProxy.close()
                    return
                }
                detectVehicles(inputImage, imageProxy, uprightHeight, uprightWidth, currentTime)
            }
        } catch (e: Exception) {
            // Wenn der synchrone Teil (LaneDetector etc.) crasht, müssen wir
            // trotzdem close() aufrufen, sonst hängt der CameraX-Frame-Queue.
            e.printStackTrace()
            try {
                imageProxy.close()
            } catch (closeEx: Exception) {
                closeEx.printStackTrace()
            }
        }
    }

    private fun toDomainCurve(lane: LaneDetector.LaneLine?) =
        lane?.let {
            com.roadguard.app.domain.model.LaneCurve(
                a = it.polyA, b = it.polyB, c = it.polyC,
                yStart = it.yStart, yEnd = it.yEnd, valid = it.valid
            )
        } ?: com.roadguard.app.domain.model.LaneCurve()

    /**
     * Classic-CV fallback drift, on the same terms as the UFLD path.
     *
     * `sw()` computes drift from its own per-frame offset with a plain
     * frame-relative threshold, so a single noisy frame can warn. The offset is
     * re-evaluated here through the temporal median + [LaneDriftGate] (history
     * floor, lane-relative window, implausible-step veto). Returns
     * (driftingLeft, driftingRight, smoothedOffset).
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

    // === UFLD helpers: points -> domain curves / offset / width ===
    // UFLD returns raw polylines; the HUD expects LaneCurve quadratics, so fit
    // x = a*y^2 + b*y + c through the points (least squares, same convention
    // as LaneDetector.fitPolynomial).

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

    private fun yuvToUprightBitmap(
        yBuffer: java.nio.ByteBuffer, yRowStride: Int, yPixelStride: Int,
        uBuffer: java.nio.ByteBuffer, uRowStride: Int, uPixelStride: Int,
        vBuffer: java.nio.ByteBuffer, vRowStride: Int, vPixelStride: Int,
        width: Int, height: Int, rotationDegrees: Int
    ): android.graphics.Bitmap? {
        // Build an NV21 image respecting strides, convert via YuvImage, then
        // rotate to upright. UFLD needs full RGB (markings are color-coded).
        return try {
            val y = ByteArray(width * height)
            val yR = yBuffer.duplicate()
            val yBase = yBuffer.position()
            if (yPixelStride == 1 && yRowStride == width) {
                yR.position(yBase)
                yR.get(y, 0, minOf(y.size, yBuffer.limit() - yBase))
            } else {
                for (row in 0 until height) {
                    val srcStart = yBase + row * yRowStride
                    if (srcStart >= yBuffer.limit()) break
                    if (yPixelStride == 1) {
                        yR.position(srcStart)
                        yR.get(y, row * width, minOf(width, yBuffer.limit() - srcStart))
                    } else {
                        for (col in 0 until width) {
                            val idx = srcStart + col * yPixelStride
                            if (idx >= yBuffer.limit()) break
                            y[col + row * width] = yR.get(idx)
                        }
                    }
                }
            }
            // Interleave V/U for NV21 (VU order), sampling chroma 2x2.
            val uv = ByteArray(width * height / 2)
            val uR = uBuffer.duplicate()
            val vR = vBuffer.duplicate()
            val uBase = uBuffer.position()
            val vBase = vBuffer.position()
            var k = 0
            for (row in 0 until height / 2) {
                for (col in 0 until width / 2) {
                    val ui = uBase + row * uRowStride + col * uPixelStride
                    val vi = vBase + row * vRowStride + col * vPixelStride
                    if (vi < vBuffer.limit() && ui < uBuffer.limit() && k + 1 < uv.size) {
                        uv[k++] = vR.get(vi)
                        uv[k++] = uR.get(ui)
                    }
                }
            }
            val nv21 = ByteArray(y.size + uv.size)
            System.arraycopy(y, 0, nv21, 0, y.size)
            System.arraycopy(uv, 0, nv21, y.size, uv.size)
            val yuv = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
            val out = java.io.ByteArrayOutputStream()
            yuv.compressToJpeg(android.graphics.Rect(0, 0, width, height), 90, out)
            val bytes = out.toByteArray()
            var bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val rot = ((rotationDegrees % 360) + 360) % 360
            if (rot != 0) {
                val m = android.graphics.Matrix()
                m.postRotate(rot.toFloat())
                val rotated = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (rotated !== bmp) bmp.recycle()
                bmp = rotated
            }
            bmp
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun detectVehicles(inputImage: InputImage, imageProxy: ImageProxy, uprightImageHeight: Int, uprightImageWidth: Int, capturedAtMs: Long) {
        // close() läuft IMMER im onCompleteListener, nie synchron davor.
        // So vermeiden wir "trying to use closed ImageProxy"-Crashes, die
        // auftreten, wenn ML Kit noch auf die underlying mediaImage-Buffer
        // zugreift während wir sie schon zurückgeben.
        objectDetector.process(inputImage)
            .addOnSuccessListener { detectedObjects ->
                // Serialize with close() and other in-flight callbacks: frames
                // can overlap (throttle < ML Kit latency) and the tracking
                // state below is mutated from callback threads.
                synchronized(detectorLock) {
                    if (closed) return@addOnSuccessListener
                    processVehicleResult(detectedObjects, uprightImageHeight, uprightImageWidth, capturedAtMs)
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
                // The FRAME's capture time, not the ML Kit callback instant: the
                // alert gate expires a distance sample by this value, and a
                // callback that lands late (busy device) must not refresh it.
                timestamp = imageCapturedAtMs
            )
        } else {
            // Kein Vehicle in diesem Frame. prevTime wird BEWUSST nicht
            // aktualisiert, damit der nächste Frame mit echtem Vehicle
            // die Zeitdifferenz korrekt messen kann. Wenn jedoch lange
            // kein Vehicle erkannt wird, soll die History gecleart
            // werden, um stale Distanzen zu verwerfen.
            val currentTime = System.currentTimeMillis()
            val gapSec = if (vehicleSampleAtMs > 0L) (currentTime - vehicleSampleAtMs) / 1000f else 0f
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
     * suppresses the TTC exactly when it matters. Fall back to centre proximity
     * — but only while the new box does not leave the previous one: a nearer
     * vehicle entering the frame also sits near the centre, and inheriting that
     * track blended two objects' distances.
     */
    private fun isSameTrackedVehicle(box: Rect): Boolean {
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
    private fun estimateDistanceUnused(boundingBox: Rect, imageHeight: Int): Float =
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
        // Serialized with in-flight frames (see analyze()): disposing the
        // ML Kit detector or the TFLite/UFLD interpreters mid-frame crashes.
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
