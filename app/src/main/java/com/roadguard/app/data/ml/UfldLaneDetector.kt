package com.roadguard.app.data.ml

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Ultra-Fast-Lane-Detection (TuSimple) as the primary lane source.
 *
 * The classic pipeline (histogram peaks + DeepLab fallback) locked onto the
 * OUTER marking on multi-lane roads and the DeepLab model collapsed to
 * all-background on dashcam footage (verified freeRatio == 1.00 on all
 * scenes). UFLD outputs up to 4 lanes as row-anchored points directly, so the
 * ego pair is selected by geometry instead of peak hunting.
 *
 * ## v2 — what the first UFLD integration still got wrong
 *
 * Measured with the Python port over 11 clips (5 standard + 6 real-world:
 * curvy highway, shadow, traffic, night, rain, city). `dx` is the median
 * signed distance between the drawn curve and the nearest marking pixel, so
 * 0 means the line lies exactly on the paint.
 *
 *  1. **Point count is not evidence.** Confidence was
 *     `0.4 + min(1, points/56 * 1.5) * 0.55`: a clean 30-point polyline scored
 *     0.85+ regardless of how much of the frame it covered or whether it sat
 *     on paint — and that number fed the alert gate directly. Replaced by
 *     [LaneGeometry.coverage] x marking support x pair plausibility.
 *  2. **The two boundaries were compared at two different rows.** Each side
 *     used its own lowest point; for pixels x = c*y^2/(2f) that is an error of
 *     roughly c*dy/f. Measured dx -10.2 px on project_video, -14.6 px on
 *     harder_challenge, and only -1.6 px on the straight solidWhiteRight —
 *     i.e. a curve-dependent bias that looked like a real lane departure.
 *     Both sides are now evaluated at ONE row ([LaneGeometry.evalRow]).
 *  3. **The model's own ~10 px bias was ignored** (dx -10.7 px on challenge,
 *     stable over the clip). The fitted curve is re-anchored to the actual
 *     paint when that increases marking support: dx -> -2.6 px.
 *  4. **The ego width prior could collapse.** The median over ALL decoded
 *     lanes mixes a real lane gap with the gap across a lane the model
 *     missed; on solidWhiteRight it drove the prior from 387 px into the
 *     0.15*W clamp. [EgoLaneGeometry] uses only index-adjacent lanes and
 *     limits the per-frame step.
 *  5. **The mirror fallback was a hard-coded 0.45*frameWidth/2** and could not
 *     be distinguished from a measurement downstream. It now uses the
 *     measured width and is capped below the alert floor.
 *  6. **Departure warnings fired while driving straight** (16-53 % of frames on
 *     some clips) because the per-frame offset noise is several pixels and the
 *     threshold sat right on it. The decision now uses a temporal median.
 *
 * Model: ufld_tusimple_float16.tflite (float16-quant, ~122 MB), bundled as an
 * asset in the release build AND downloadable via [ModelDownloader] into
 * filesDir/roadguard_models. Input [1,288,800,3] float32, ImageNet-normalized.
 * Output [1,101,56,4] (griding x row-anchors x lanes).
 */
class UfldLaneDetector(private val context: Context) {

    companion object {
        const val MODEL_FILE = "ufld_tusimple_float16.tflite"
        const val INPUT_W = 800
        const val INPUT_H = 288
        const val GRIDING_NUM = 100
        const val NUM_ROWS = 56
        const val NUM_LANES = 4

        /** TuSimple row anchors in 288px model space, bottom-up. */
        val ROW_ANCHORS = intArrayOf(
            64, 68, 72, 76, 80, 84, 88, 92, 96, 100, 104, 108, 112,
            116, 120, 124, 128, 132, 136, 140, 144, 148, 152, 156, 160, 164,
            168, 172, 176, 180, 184, 188, 192, 196, 200, 204, 208, 212, 216,
            220, 224, 228, 232, 236, 240, 244, 248, 252, 256, 260, 264, 268,
            272, 276, 280, 284
        )
        const val CFG_W = 1280f
        const val CFG_H = 720f

        /**
         * Map UFLD's 1-based expected grid location to image x.
         *
         * The reference decoder uses `linspace(0, INPUT_W - 1, GRIDING_NUM)`,
         * not INPUT_W / GRIDING_NUM. Keeping the exact sample spacing avoids a
         * systematic ~1 % outward bias (about 10 px near the right edge at 1280p).
         */
        fun gridCellToLaneX(location: Number, imageWidth: Int): Float {
            val cellWidth = (INPUT_W - 1f) / (GRIDING_NUM - 1f)
            return location.toFloat() * cellWidth * imageWidth / INPUT_W - imageWidth / CFG_W
        }

        private const val MIN_POINTS = 3
        private const val HOLD_FRAMES = 6
        private const val EMA_ALPHA = 0.6f

        /**
         * Ceiling for a sample whose second boundary was mirrored from the
         * first. Must stay below the alert floor (0.4): a guessed boundary is
         * good enough to draw, never good enough to warn.
         */
        const val MIRROR_CONFIDENCE_CAP = 0.39f

        /** Confidence for a held (last-known) sample: drawable, not warnable. */
        private const val HOLD_CONFIDENCE = 0.35f

        /** Offset median window in samples (~1.4 s at the 5 Hz analyzer rate). */
        private const val OFFSET_MEDIAN_N = 7
        private const val OFFSET_MEDIAN_MIN = 3
    }

    data class LanePoints(val x: FloatArray, val y: FloatArray, val index: Int = 0) {
        val size: Int get() = x.size

        /** Lowest row the model decoded — the only point that is ground contact. */
        val yBottom: Float
            get() {
                var v = Float.NEGATIVE_INFINITY
                for (yi in y) if (yi > v) v = yi
                return v
            }

        /** Highest decoded row (far field). */
        val yTop: Float
            get() {
                var v = Float.POSITIVE_INFINITY
                for (yi in y) if (yi < v) v = yi
                return v
            }

        /** x on the lowest decoded row. */
        val xBottom: Float
            get() {
                var best = 0
                for (i in y.indices) if (y[i] > y[best]) best = i
                return x[best]
            }
    }

    private var interpreter: Interpreter? = null
    // Held as Delegate (not GpuDelegate/NnApiDelegate) so this file compiles
    // even when the optional tensorflow-lite-gpu artifact is missing.
    private var gpuDelegate: org.tensorflow.lite.Delegate? = null
    private var nnApiDelegate: org.tensorflow.lite.Delegate? = null
    private var cachedInput: ByteBuffer? = null
    private var cachedOutput: ByteBuffer? = null
    // Reused inference scratch: per-frame allocations (pixels, decode
    // workspace, scaled Bitmap) were ~3 MB of garbage at 5 Hz — enough GC
    // churn to make the offset median skip beats. The detector is driven
    // from a single analyzer thread, so no synchronization is needed.
    private var cachedPixels: IntArray? = null
    private var cachedExpVals: FloatArray? = null
    private var cachedScaled: Bitmap? = null

    /** Which execution path the interpreter actually uses (for diagnostics). */
    var activeBackend: String = "none"
        private set

    // EMA state per side, keyed "L"/"R". Shapes must match to blend.
    private val emaState = mutableMapOf<String, LanePoints>()
    private val emaFrame = mutableMapOf<String, Long>()
    private var frameCounter = 0L

    /** Ego-lane width learning + pair selection (unit tested). */
    private val egoGeometry = EgoLaneGeometry()

    /** Paint measurement and curve re-anchoring. */
    private val markingMeasurer = LaneOverlayRenderer()

    /** Temporal median of the ego offset, in image pixels. */
    private val offsetHistory = ArrayDeque<Float>()

    /** Marking support of the last processed frame, per side (0..1). */
    private var leftSupport: Float = 1f
    private var rightSupport: Float = 1f

    /** Last applied paint correction per side, in image px (diagnostics). */
    var lastShiftLeft: Float = 0f
        private set
    var lastShiftRight: Float = 0f
        private set

    /** Offset of the last real sample, so a hold does not report "centred". */
    private var currentOffset: Float = 0f

    /** Last ego pair chosen, so a lane change can reset the temporal state. */
    private var lastPair: Pair<Int, Int>? = null

    /** Bottom share of the frame covered by the hood: excluded from detection. */
    @Volatile
    var hoodFraction: Float = LaneGeometry.HOOD_EXCLUSION_FRACTION
        private set

    fun updateHoodFraction(value: Float) {
        hoodFraction = value.coerceIn(0f, 0.5f)
    }

    @Synchronized
    fun isLoaded(): Boolean = interpreter != null

    @Synchronized
    fun loadModel(fileName: String = MODEL_FILE) {
        try {
            // Bundled asset first (shipped in the APK since the model swap),
            // downloaded file as fallback for updates without reinstall.
            val assetBuffer: MappedByteBuffer? = try {
                org.tensorflow.lite.support.common.FileUtil.loadMappedFile(context, fileName)
            } catch (e: Exception) {
                android.util.Log.i("UfldLaneDetector", "no bundled asset, trying download dir")
                null
            }
            val buffer: MappedByteBuffer = assetBuffer ?: run {
                val modelFile = File(File(context.filesDir, ModelDownloader.MODEL_DIR), fileName)
                if (!modelFile.exists()) {
                    android.util.Log.w("UfldLaneDetector", "model missing")
                    return
                }
                android.util.Log.i("UfldLaneDetector", "loading download (${modelFile.length()} bytes)")
                // NOTE: FileUtil.loadMappedFile(context, path) treats `path` as an
                // ASSET name (AssetManager.openFd) — never pass an absolute file
                // path to it. Map the file directly instead.
                FileInputStream(modelFile).channel.use { ch ->
                    ch.map(FileChannel.MapMode.READ_ONLY, 0, modelFile.length())
                }
            }
            closeLocked()
            // float16-quant UFLD: GPU first (Adreno/Mali handle fp16 well),
            // then NNAPI, then 4-thread CPU. Each delegate is tried with a
            // probe inference; failures fall through to the next backend.
            //
            // NOTE: both delegates are loaded via REFLECTION, never via direct
            // constructor calls. The GPU classes live in the separate
            // tensorflow-lite-gpu artifact — if its classes or native libs are
            // missing on a device (e.g. x86_64 emulator), a direct
            // `GpuDelegate()` reference throws NoClassDefFoundError (an Error,
            // not an Exception) and kills the analyzer thread. Reflection
            // turns that into a catchable failure and a clean CPU fallback.
            val options = Interpreter.Options().setNumThreads(4)
            var backend = "cpu"
            try {
                val gpu = newDelegate("org.tensorflow.lite.gpu.GpuDelegate")
                    ?: throw ClassNotFoundException("GpuDelegate not on classpath")
                options.addDelegate(gpu)
                val probe = Interpreter(buffer, options)
                try {
                    probe.run(obtainInput(), obtainOutput())
                    gpuDelegate = gpu
                    backend = "gpu"
                    probe.close()
                } catch (e: Throwable) {
                    try { probe.close() } catch (_: Exception) {}
                    try { gpu.close() } catch (_: Exception) {}
                    // NOTE: `options` is discarded here (finalOptions is built
                    // fresh below) — no need to detach the failed delegate.
                    throw e
                }
            } catch (e: Throwable) {
                android.util.Log.i("UfldLaneDetector", "GPU delegate unavailable, trying NNAPI: ${e.message}")
            }
            if (backend == "cpu") {
                try {
                    val nnapi = newDelegate("org.tensorflow.lite.nnapi.NnApiDelegate")
                        ?: throw ClassNotFoundException("NnApiDelegate not on classpath")
                    val nnOptions = Interpreter.Options().setNumThreads(4).addDelegate(nnapi)
                    val probe = Interpreter(buffer, nnOptions)
                    try {
                        probe.run(obtainInput(), obtainOutput())
                        nnApiDelegate = nnapi
                        backend = "nnapi"
                        probe.close()
                    } catch (e: Throwable) {
                        try { probe.close() } catch (_: Exception) {}
                        try { nnapi.close() } catch (_: Exception) {}
                        nnApiDelegate = null
                        throw e
                    }
                } catch (e: Throwable) {
                    android.util.Log.i("UfldLaneDetector", "NNAPI delegate unavailable, using CPU: ${e.message}")
                }
            }
            val finalOptions = Interpreter.Options().setNumThreads(4)
            gpuDelegate?.let { finalOptions.addDelegate(it) }
            if (backend == "nnapi") {
                nnApiDelegate?.let { finalOptions.addDelegate(it) }
            }
            interpreter = Interpreter(buffer, finalOptions)
            activeBackend = backend
            android.util.Log.i("UfldLaneDetector", "loaded backend=$backend input=${INPUT_W}x$INPUT_H")
            cachedInput = null
            cachedOutput = null
        } catch (e: Exception) {
            android.util.Log.e("UfldLaneDetector", "load failed", e)
            e.printStackTrace()
        }
    }

    private fun closeLocked() {
        try {
            interpreter?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        interpreter = null
        cachedScaled?.let {
            try {
                if (!it.isRecycled) it.recycle()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        cachedScaled = null
        try {
            gpuDelegate?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        gpuDelegate = null
        try {
            nnApiDelegate?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        nnApiDelegate = null
        activeBackend = "none"
    }

    /**
     * Instantiate a TFLite Delegate by class name via reflection.
     * Returns null when the class is absent (optional artifact not packaged)
     * — callers fall through to the next backend. Never reference delegate
     * classes directly: on devices without the artifact that throws
     * NoClassDefFoundError (an Error, not an Exception).
     */
    private fun newDelegate(className: String): org.tensorflow.lite.Delegate? {
        return try {
            val clazz = Class.forName(className)
            clazz.getDeclaredConstructor().newInstance() as? org.tensorflow.lite.Delegate
        } catch (e: Throwable) {
            null
        }
    }

    @Synchronized
    fun close() {
        closeLocked()
        cachedInput = null
        cachedOutput = null
        cachedPixels = null
        cachedExpVals = null
    }

    @Synchronized
    fun reset() {
        emaState.clear()
        emaFrame.clear()
        frameCounter = 0L
        lastPair = null
        egoGeometry.reset()
        offsetHistory.clear()
        lastShiftLeft = 0f
        lastShiftRight = 0f
        leftSupport = 1f
        rightSupport = 1f
        currentOffset = 0f
        cachedScaled?.let {
            try {
                if (!it.isRecycled) it.recycle()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        cachedScaled = null
    }

    private fun obtainInput(): ByteBuffer {
        val need = INPUT_W * INPUT_H * 3 * 4
        val cur = cachedInput
        if (cur != null && cur.capacity() == need) {
            cur.clear()
            return cur
        }
        return ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder()).also { cachedInput = it }
    }

    private fun obtainOutput(): ByteBuffer {
        val need = (GRIDING_NUM + 1) * NUM_ROWS * NUM_LANES * 4
        val cur = cachedOutput
        if (cur != null && cur.capacity() == need) {
            cur.clear()
            return cur
        }
        return ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder()).also { cachedOutput = it }
    }

    private fun obtainPixels(): IntArray {
        val cur = cachedPixels
        if (cur != null && cur.size == INPUT_W * INPUT_H) return cur
        return IntArray(INPUT_W * INPUT_H).also { cachedPixels = it }
    }

    private fun obtainExpVals(): FloatArray {
        val cur = cachedExpVals
        if (cur != null && cur.size == GRIDING_NUM) return cur
        return FloatArray(GRIDING_NUM).also { cachedExpVals = it }
    }

    /**
     * The scaled working Bitmap, reused across frames. Scaling allocates a
     * full RGB bitmap every call — at 5 Hz that is a young-gen churn the
     * analyzer does not need.
     */
    private fun obtainScaled(source: Bitmap): Bitmap {
        if (source.width == INPUT_W && source.height == INPUT_H) return source
        val cur = cachedScaled
        if (cur != null && !cur.isRecycled && cur.width == INPUT_W && cur.height == INPUT_H) {
            return cur
        }
        cur?.let {
            try {
                if (!it.isRecycled) it.recycle()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return Bitmap.createBitmap(INPUT_W, INPUT_H, Bitmap.Config.ARGB_8888)
            .also { cachedScaled = it }
    }

    /**
     * Run UFLD on a frame. Returns (left, right) polylines in IMAGE pixels
     * plus a 0..1 confidence, or nulls when no ego pair passes validation.
     * Thread-safe w.r.t. loading; inference itself is single-analyzer-thread.
     */
    @Synchronized
    fun detect(bitmap: Bitmap): UfldResult {
        val itp = interpreter ?: return UfldResult(null, null, 0f, false)
        frameCounter++
        return try {
            val frame = runInference(itp, bitmap)
            // One marking mask per frame, reused by both sides.
            markingMeasurer.buildMarkingMask(frame.pixels, INPUT_W, INPUT_H)
            // Hood first: the configured bottom band is the bonnet, not
            // road. Clipped before pair/width/offset so hood reflections
            // never steer them.
            val hoodFrac = hoodFraction
            val lanes = Array<LanePoints?>(NUM_LANES) { i ->
                LaneGeometry.aboveHood(frame.lanes[i], bitmap.height, hoodFrac)
            }
            // Compare every lane at ONE row. Raw xBottom places each lane at
            // its own lowest decoded depth, so a gap measured that way mixes a
            // near-field and a far-field x — the same "two different rows" bug
            // already fixed for the offset/confidence path, still present in
            // width learning and pair selection. Fitting each polyline at a
            // shared, as-low-as-supported row makes the learned ego width and
            // the chosen pair agree with what is actually drawn.
            //
            // Span-gated first: a 3-point curb fragment has enough points to
            // reach here but extrapolates wildly at the shared row, so it could
            // win the pair or drag the width prior. The same gate hides it from
            // the overlay, so it must not influence geometry either.
            val decoded = lanes.filterNotNull()
                .filter { LaneGeometry.passesSpanGate(it, bitmap.height, hoodFrac) }
            val commonEvalRow = if (decoded.isEmpty()) {
                bitmap.height.toFloat()
            } else {
                val lowest = decoded.maxOf { it.yBottom }
                LaneGeometry.evalRow(lowest, lowest, bitmap.height, hoodFrac)
            }
            val candidates = decoded.map { pts ->
                val x = LaneGeometry.fitQuadratic(pts.x, pts.y)?.x(commonEvalRow) ?: pts.xBottom
                EgoLaneGeometry.Lane(
                    pts.index,
                    x.coerceIn(0f, bitmap.width.toFloat()),
                    pts.size
                )
            }
            // Learn the ego width BEFORE pairing: this frame supplies both.
            egoGeometry.observeWidth(candidates, bitmap.width)
            val pair = egoGeometry.choosePair(candidates, bitmap.width)
            val sizes = lanes.map { it?.size ?: 0 }
            if (pair == null) {
                singleSide(lanes, bitmap, frame, sizes)
            } else {
                val (li, ri) = pair
                // A changed pair is a lane change (or a model re-slot). The
                // per-side EMA and the offset median still hold the OLD lane's
                // geometry, and blending that into the new lane produces a
                // phantom line plus a fabricated departure. Drop both so the
                // new lane is learned from scratch and the gate stays quiet
                // until it has fresh samples.
                if (lastPair != null && lastPair != pair) {
                    emaState.clear()
                    emaFrame.clear()
                    offsetHistory.clear()
                }
                lastPair = pair
                val left = reAnchor(lanes[li]!!, frame, bitmap)
                val right = reAnchor(lanes[ri]!!, frame, bitmap)
                val leftSm = smooth("L", left)
                val rightSm = smooth("R", right)
                val (rawOffset, conf) = evaluate(leftSm, rightSm, bitmap.width, bitmap.height, mirrored = false)
                currentOffset = rawOffset
                if (frameCounter % 10 == 0L) {
                    android.util.Log.d(
                        "UfldLaneDetector",
                        "frame=$frameCounter backend=$activeBackend sizes=$sizes pair=$pair " +
                            "img=${bitmap.width}x${bitmap.height} " +
                            "shift=${"%.1f".format(lastShiftLeft)}/${"%.1f".format(lastShiftRight)} " +
                            "sup=${"%.2f".format(leftSupport)}/${"%.2f".format(rightSupport)} " +
                            "off=${"%.1f".format(rawOffset)} conf=${"%.2f".format(conf)}"
                    )
                }
                UfldResult(leftSm, rightSm, conf, true, offsetPx = rawOffset, offsetTrusted = true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            UfldResult(null, null, 0f, false)
        }
    }

    /**
     * No ego pair: continue with a single visible boundary. The missing side is
     * mirrored by the MEASURED half width, and the whole sample is capped below
     * the alert floor so it can be drawn but never warned about.
     */
    private fun singleSide(
        lanes: Array<LanePoints?>,
        bitmap: Bitmap,
        frame: Frame,
        sizes: List<Int>
    ): UfldResult {
        val single = singleSideLane(lanes, bitmap.width, bitmap.height, hoodFraction)
        if (single == null) {
            android.util.Log.d(
                "UfldLaneDetector",
                "frame=$frameCounter backend=$activeBackend sizes=$sizes pair=none single=none"
            )
            return holdLast()
        }
        val (side, rawPts) = single
        val pts = reAnchor(rawPts, frame, bitmap)
        val width = egoGeometry.widthOr(bitmap.width)
        val shiftedX = FloatArray(pts.size) { i ->
            egoGeometry.oppositeBoundaryX(pts.x[i], side == "L", bitmap.width)
                .coerceIn(0f, bitmap.width.toFloat())
        }
        val other = LanePoints(shiftedX, pts.y.copyOf())
        val left = if (side == "L") pts else other
        val right = if (side == "L") other else pts
        val leftSm = smooth("L", left)
        val rightSm = smooth("R", right)
        val (rawOffset, conf) = evaluate(leftSm, rightSm, bitmap.width, bitmap.height, mirrored = true)
        currentOffset = rawOffset
        if (frameCounter % 10 == 0L) {
            android.util.Log.d(
                "UfldLaneDetector",
                "frame=$frameCounter backend=$activeBackend sizes=$sizes pair=none " +
                    "single=$side width=${"%.0f".format(width)} conf=${"%.2f".format(conf)} " +
                    "img=${bitmap.width}x${bitmap.height}"
            )
        }
        return UfldResult(leftSm, rightSm, conf, true, mirrored = true, offsetPx = rawOffset, offsetTrusted = false)
    }

    /**
     * Re-anchor a decoded curve to the actual paint and record its support.
     *
     * UFLD carries a small, stable lateral bias (measured ~10 px at 1280 px
     * width, present on every frame of a clip). The correction is applied only
     * when it improves how well the curve sits on paint, so a frame without
     * visible markings keeps the model's own output.
     */
    private fun reAnchor(pts: LanePoints, frame: Frame, bitmap: Bitmap): LanePoints {
        val isLeftSide = pts.xBottom < bitmap.width / 2f
        val scaleX = bitmap.width.toFloat() / INPUT_W
        val scaleY = bitmap.height.toFloat() / INPUT_H
        // Measure in IMAGE pixels; the renderer maps them onto the model-grid
        // mask itself, so the mask is baked once per frame and never per sample.
        val (deviation, supportBefore) = markingMeasurer.measure(pts.x, pts.y, bitmap.width, bitmap.height, hoodFraction)
        val maxCorrection = LaneOverlayRenderer.SEARCH_FRAC * bitmap.width
        var corrected = pts
        var supportAfter = supportBefore
        if (abs(deviation) > 0.5f && abs(deviation) <= maxCorrection) {
            val nx = FloatArray(pts.size) { i -> (pts.x[i] - deviation).coerceIn(0f, bitmap.width.toFloat()) }
            val (_, s) = markingMeasurer.measure(nx, pts.y, bitmap.width, bitmap.height, hoodFraction)
            if (s >= supportBefore) {
                corrected = LanePoints(nx, pts.y)
                supportAfter = s
            }
        }
        if (isLeftSide) {
            leftSupport = supportAfter
            lastShiftLeft = if (corrected === pts) 0f else deviation
        } else {
            rightSupport = supportAfter
            lastShiftRight = if (corrected === pts) 0f else deviation
        }
        return corrected
    }

    /**
     * Offset + evidence-based confidence for one sample.
     *
     * Both boundaries are evaluated at ONE row — as low as the data supports —
     * so a curved boundary can no longer fake a lateral offset against a
     * straight one.
     */
    private fun evaluate(
        left: LanePoints?,
        right: LanePoints?,
        frameWidth: Int,
        frameHeight: Int,
        mirrored: Boolean
    ): Pair<Float, Float> {
        val leftOk = LaneGeometry.passesSpanGate(left, frameHeight, hoodFraction)
        val rightOk = LaneGeometry.passesSpanGate(right, frameHeight, hoodFraction)
        val evalRow = LaneGeometry.evalRow(
            if (leftOk) left!!.yBottom else frameHeight.toFloat(),
            if (rightOk) right!!.yBottom else frameHeight.toFloat(),
            frameHeight,
            hoodFraction
        )
        val covL = if (leftOk) LaneGeometry.coverage(left, evalRow, frameHeight, leftSupport) else 0f
        val covR = if (rightOk) LaneGeometry.coverage(right, evalRow, frameHeight, rightSupport) else 0f

        val offset: Float
        var conf: Float
        if (leftOk && rightOk) {
            val xl = LaneGeometry.fitQuadratic(left!!.x, left.y)?.x(evalRow) ?: left.xBottom
            val xr = LaneGeometry.fitQuadratic(right!!.x, right.y)?.x(evalRow) ?: right.xBottom
            offset = egoGeometry.centerOffset(xl, xr, frameWidth)
            val gap = xr - xl
            val prior = egoGeometry.widthOr(frameWidth)
            val plausibility = (1f - abs(gap - prior) / (0.8f * prior)).coerceIn(0.4f, 1f)
            conf = 0.45f + 0.5f * ((covL + covR) / 2f) * plausibility
            if (mirrored) conf = min(conf, MIRROR_CONFIDENCE_CAP)
        } else {
            val single = if (leftOk) left!! else right!!
            val x = LaneGeometry.fitQuadratic(single.x, single.y)?.x(evalRow) ?: single.xBottom
            offset = egoGeometry.singleSideOffset(x, leftOk, frameWidth)
            conf = min(0.30f + 0.35f * max(covL, covR), MIRROR_CONFIDENCE_CAP)
        }
        return Pair(offset, conf.coerceIn(0f, 0.98f))
    }

    /**
     * Temporal median of the ego offset — the value the drift gate sees.
     * The raw per-frame offset jitters by several pixels even on a straight
     * road, and a threshold sitting exactly at that noise level produced
     * departure warnings while driving straight.
     */
    @Synchronized
    fun smoothedOffset(rawOffset: Float): Float {
        offsetHistory.addLast(rawOffset)
        while (offsetHistory.size > OFFSET_MEDIAN_N) offsetHistory.removeFirst()
        val sorted = offsetHistory.sorted()
        return sorted[sorted.size / 2]
    }

    /**
     * The current offset median WITHOUT adding a sample.
     *
     * Used when this frame's offset is not a measurement (mirrored single side
     * or a held sample). Feeding those guesses into [offsetHistory] would shift
     * the median that later warns about a REAL pair: the gate caps the guess'
     * confidence so it cannot warn itself, but the pollution outlives it.
     */
    @Synchronized
    fun peekSmoothedOffset(): Float {
        if (offsetHistory.isEmpty()) return 0f
        val sorted = offsetHistory.sorted()
        return sorted[sorted.size / 2]
    }

    /** True once enough samples exist for the median to mean anything. */
    fun hasOffsetHistory(): Boolean = offsetHistory.size >= OFFSET_MEDIAN_MIN

    /** Number of offset samples collected (for the drift gate's history floor). */
    fun offsetHistorySize(): Int = offsetHistory.size

    /** Measured ego lane width in image px, or 0 while nothing was measured. */
    fun measuredLaneWidthPx(): Float = egoGeometry.measuredWidth

    private fun holdLast(): UfldResult {
        // Dashed lines / shadows: keep last lanes briefly, then decay.
        // A hold is stale geometry: the offset must not enter the warning
        // median (see offsetTrusted), it only keeps the HUD line alive.
        val l = emaState["L"]?.takeIf { frameCounter - (emaFrame["L"] ?: -99L) <= HOLD_FRAMES }
        val r = emaState["R"]?.takeIf { frameCounter - (emaFrame["R"] ?: -99L) <= HOLD_FRAMES }
        return if (l != null || r != null) {
            UfldResult(l, r, HOLD_CONFIDENCE, l != null && r != null, offsetPx = currentOffset, offsetTrusted = false)
        } else {
            UfldResult(null, null, 0.1f, false)
        }
    }

    private fun smooth(side: String, cur: LanePoints): LanePoints {
        val prev = emaState[side]
        val out = if (prev != null && prev.size == cur.size) {
            // Same point COUNT is not the same ROWS: UFLD skips a row whose
            // no-line cell wins, so a dropped row shifts every following index
            // and an index-wise EMA blends two different rows into a phantom
            // kink. Blend only points that share a row; keep the current
            // measurement where there is no match, and fall back to the raw
            // frame when too few points aligned.
            var aligned = 0
            val nx = FloatArray(cur.size) { i ->
                val j = rowIndex(prev.y, cur.y[i])
                if (j >= 0) {
                    aligned++
                    EMA_ALPHA * cur.x[i] + (1 - EMA_ALPHA) * prev.x[j]
                } else {
                    cur.x[i]
                }
            }
            if (aligned >= cur.size * 3 / 4) {
                LanePoints(nx, cur.y.copyOf())
            } else {
                cur
            }
        } else {
            cur
        }
        emaState[side] = out
        emaFrame[side] = frameCounter
        return out
    }

    /** Index of the sample on row [row] (anchors are shared across frames). */
    private fun rowIndex(rows: FloatArray, row: Float): Int {
        for (i in rows.indices) {
            if (abs(rows[i] - row) < 0.5f) return i
        }
        return -1
    }

    /** Inference result: decoded lanes in IMAGE pixels plus the model-grid pixels. */
    private data class Frame(val lanes: Array<LanePoints?>, val pixels: IntArray)

    private fun runInference(itp: Interpreter, bitmap: Bitmap): Frame {
        val input = obtainInput()
        val pixels = obtainPixels()
        val directHit = bitmap.width == INPUT_W && bitmap.height == INPUT_H
        val scaled = if (directHit) {
            bitmap
        } else {
            val target = obtainScaled(bitmap)
            val canvas = android.graphics.Canvas(target)
            val src = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
            val dst = android.graphics.Rect(0, 0, INPUT_W, INPUT_H)
            val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
            // Opaque fill first: the reused target keeps stale pixels where a
            // source with alpha would composite instead of replace.
            canvas.drawColor(android.graphics.Color.BLACK)
            canvas.drawBitmap(bitmap, src, dst, paint)
            target
        }
        scaled.getPixels(pixels, 0, INPUT_W, 0, 0, INPUT_W, INPUT_H)
        // ImageNet normalization, RGB order.
        for (px in pixels) {
            val r = ((px shr 16) and 0xFF) / 255f
            val g = ((px shr 8) and 0xFF) / 255f
            val b = (px and 0xFF) / 255f
            input.putFloat((r - 0.485f) / 0.229f)
            input.putFloat((g - 0.456f) / 0.224f)
            input.putFloat((b - 0.406f) / 0.225f)
        }
        input.rewind()
        val output = obtainOutput()
        itp.run(input, output)
        output.rewind()
        // Decode: output layout (griding+1, rows, lanes) = (101, 56, 4).
        // Softmax over griding dim per (row, lane), expected cell = sum(p*idx),
        // argmax == GRIDING_NUM means "no line" -> suppressed to 0.
        val lanes = Array<LanePoints?>(NUM_LANES) { null }
        val imgW = bitmap.width
        val imgH = bitmap.height
        for (lane in 0 until NUM_LANES) {
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            for (row in 0 until NUM_ROWS) {
                // NOTE: PINTO's TFLite export stores rows top-first along the
                // row axis, so index (NUM_ROWS-1-row) reads bottom-up — same
                // flip the reference script applies as output[:, ::-1, :].
                val base = ((NUM_ROWS - 1 - row) * NUM_LANES + lane)
                var maxV = Float.NEGATIVE_INFINITY
                var sumExp = 0f
                // Softmax over the 100 lane cells (skip the no-line cell).
                // Two passes: max for stability, then weighted sum.
                for (k in 0 until GRIDING_NUM) {
                    val v = output.getFloat((k * NUM_ROWS * NUM_LANES + base) * 4)
                    if (v > maxV) maxV = v
                }
                val expVals = obtainExpVals()
                for (k in 0 until GRIDING_NUM) {
                    val e = kotlin.math.exp(output.getFloat((k * NUM_ROWS * NUM_LANES + base) * 4) - maxV)
                    expVals[k] = e
                    sumExp += e
                }
                val noLineV = output.getFloat((GRIDING_NUM * NUM_ROWS * NUM_LANES + base) * 4)
                if (noLineV - maxV > 0f && noLineV >= maxV) {
                    // argmax is the no-line cell -> suppressed.
                    var isNoLine = true
                    for (k in 0 until GRIDING_NUM) {
                        if (output.getFloat((k * NUM_ROWS * NUM_LANES + base) * 4) > noLineV) {
                            isNoLine = false
                            break
                        }
                    }
                    if (isNoLine) continue
                }
                var loc = 0f
                for (k in 0 until GRIDING_NUM) {
                    loc += (k + 1) * (expVals[k] / sumExp)
                }
                // Reference x formula uses linspace(0, INPUT_W-1, GRIDING_NUM).
                // Row-axis pairing: base=(NUM_ROWS-1-row) reads bottom-up, so the
                // anchor must use the same axis (NUM_ROWS-1-row).
                val pyCfg = CFG_H * (ROW_ANCHORS[NUM_ROWS - 1 - row] / INPUT_H.toFloat()) - 1f
                xs.add(gridCellToLaneX(loc, imgW))
                ys.add(pyCfg * imgH / CFG_H)
            }
            if (xs.size >= MIN_POINTS) {
                lanes[lane] = LanePoints(xs.toFloatArray(), ys.toFloatArray(), lane)
            }
        }
        return Frame(lanes, pixels)
    }

    /**
     * Single-side fallback: the lane with the most points on the one visible
     * side of the image centre. Returns ("L"|"R", points) or null when both
     * sides have candidates (the pair path owns that case).
     *
     * Span-gated so the choice matches the overlay: without the gate a tall
     * curb fragment on the wrong side could win by point count and the fallback
     * would mirror a boundary nobody drew.
     */
    internal fun singleSideLane(
        lanes: Array<LanePoints?>,
        imgW: Int,
        frameHeight: Int = 0,
        hoodFraction: Float = 0f
    ): Pair<String, LanePoints>? {
        val mid = imgW / 2f
        var bestL: LanePoints? = null
        var bestR: LanePoints? = null
        for (l in lanes) {
            if (l == null || l.size < MIN_POINTS) continue
            if (frameHeight > 0 && !LaneGeometry.passesSpanGate(l, frameHeight, hoodFraction)) continue
            if (l.xBottom < mid) {
                if (bestL == null || l.size > bestL.size) bestL = l
            } else {
                if (bestR == null || l.size > bestR.size) bestR = l
            }
        }
        return when {
            bestL != null && bestR != null -> null // both sides -> pair path owns this
            bestL != null -> Pair("L", bestL)
            bestR != null -> Pair("R", bestR)
            else -> null
        }
    }

    data class UfldResult(
        val left: LanePoints?,
        val right: LanePoints?,
        val confidence: Float,
        val bothValid: Boolean,
        /** True when one side was mirrored from the other (single-lane fallback). */
        val mirrored: Boolean = false,
        /** Vehicle-centre offset in image pixels, evaluated at one common row. */
        val offsetPx: Float = 0f,
        /**
         * True when this frame's offset is a measurement from a real pair. False for
         * the mirrored single-side fallback (a guess) and for held samples (stale):
         * callers must not feed those into the warning median.
         */
        val offsetTrusted: Boolean = true
    )
}
