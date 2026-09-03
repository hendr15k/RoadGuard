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
    private var ufldDetector: UfldLaneDetector? = null
    private var ufldLoadAttempted = false

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
        // Only construct the runners here. Mapping models and building the
        // native interpreters belongs on the analyzer thread, not in composition.
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

            // UFLD first: direct lane points instead of histogram hunting. Falls
            // back to classic CV when no model is downloaded or no ego pair is
            // found; DeepLab stays as the last-resort drift signal.
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
            val ufldOk = ufldResult != null && (ufldResult.left != null || ufldResult.right != null)

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
                val ufldOff = ufldCenterOffset(ufldResult, uprightWidth)
                val driftGate = 0.04f * (1.5f - laneSensitivity)
                finalIsDriftingLeft = ufldOff < -uprightWidth * driftGate && ufldResult.confidence > 0.4f
                finalIsDriftingRight = ufldOff > uprightWidth * driftGate && ufldResult.confidence > 0.4f
                finalConfidence = ufldResult.confidence
                finalCenterOffset = ufldOff
                finalLaneWidth = ufldLaneWidth(ufldResult)
                leftVisible = ufldResult.left != null && ufldCurves.first.valid
                rightVisible = ufldResult.right != null && ufldCurves.second.valid
                leftCurve = ufldCurves.first
                rightCurve = ufldCurves.second
            } else {
                val finalIsDriftingLeftCv = if (swResult.confidence > 0.3f) swResult.isDriftingLeft else tfliteDriftL
                val finalIsDriftingRightCv = if (swResult.confidence > 0.3f) swResult.isDriftingRight else tfliteDriftR
                finalIsDriftingLeft = finalIsDriftingLeftCv
                finalIsDriftingRight = finalIsDriftingRightCv
                finalConfidence = maxOf(swResult.confidence, tfliteConf)
                finalCenterOffset = if (swResult.confidence > 0.3f) swResult.centerOffset else tfliteCenterOffset
                finalLaneWidth = swResult.laneWidth
                leftVisible = swResult.leftLane?.valid == true
                rightVisible = swResult.rightLane?.valid == true
                leftCurve = toDomainCurve(swResult.leftLane)
                rightCurve = toDomainCurve(swResult.rightLane)
            }

            _laneInfo.value = LaneInfo(
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

    // === UFLD helpers: points -> domain curves / offset / width ===
    // UFLD returns raw polylines; the HUD expects LaneCurve quadratics, so fit
    // x = a*y^2 + b*y + c through the points (least squares, same convention
    // as LaneDetector.fitPolynomial).

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
        // Solve 3x3 normal equations via Cramer.
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
        if (abs(d) < 1e-9) return null
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
        if (abs(a) > 0.5f) return null
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
        // Span gate (same as video path): reject short stubs (curb
        // fragments) whose extrapolation would float in the sky.
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
