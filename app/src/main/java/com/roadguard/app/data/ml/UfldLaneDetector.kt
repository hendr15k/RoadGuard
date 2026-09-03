package com.roadguard.app.data.ml

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Ultra-Fast-Lane-Detection (TuSimple) as the primary lane source.
 *
 * The old pipeline (classic CV histogram peaks + DeepLab fallback) picked the
 * OUTER marking on multi-lane roads and the DeepLab model collapsed to
 * all-background on dashcam footage (verified freeRatio == 1.00 on all
 * scenes). UFLD outputs up to 4 lanes as row-anchored points directly, so the
 * ego pair (lanes 1+2) is selected by geometry instead of peak hunting.
 *
 * Validated via a Python port on 5 dashcam clips (solidWhiteRight,
 * solidYellowLeft, challenge, project_video, harder_challenge): both-rate
 * 1.00 on 4/5, 0.997 on the mountain clip; QA overlays sit on the markings.
 *
 * Model: ufld_tusimple_float16.tflite (float16-quant, ~122 MB), downloaded on
 * demand via [ModelDownloader] into filesDir/roadguard_models — NOT bundled,
 * the APK would triple. Input [1,288,800,3] float32, ImageNet-normalized.
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
        // TuSimple row anchors in 288px model space, bottom-up.
        val ROW_ANCHORS = intArrayOf(
            64, 68, 72, 76, 80, 84, 88, 92, 96, 100, 104, 108, 112,
            116, 120, 124, 128, 132, 136, 140, 144, 148, 152, 156, 160, 164,
            168, 172, 176, 180, 184, 188, 192, 196, 200, 204, 208, 212, 216,
            220, 224, 228, 232, 236, 240, 244, 248, 252, 256, 260, 264, 268,
            272, 276, 280, 284
        )
        const val CFG_W = 1280f
        const val CFG_H = 720f
        private const val MIN_GAP_FRac = 0.22f
        private const val MAX_GAP_FRac = 0.85f
        private const val MIN_POINTS = 3
        private const val HOLD_FRAMES = 6
        private const val EMA_ALPHA = 0.6f
    }

    data class LanePoints(val x: FloatArray, val y: FloatArray) {
        val size: Int get() = x.size
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null
    private var cachedInput: ByteBuffer? = null
    private var cachedOutput: ByteBuffer? = null
    /** Which execution path the interpreter actually uses (for diagnostics). */
    var activeBackend: String = "none"
        private set

    // EMA state per side, keyed "L"/"R". Shapes must match to blend.
    private val emaState = mutableMapOf<String, LanePoints>()
    private val emaFrame = mutableMapOf<String, Long>()
    private var frameCounter = 0L

    @Synchronized
    fun isLoaded(): Boolean = interpreter != null

    @Synchronized
    fun loadModel(fileName: String = MODEL_FILE) {
        try {
            val modelFile = File(File(context.filesDir, ModelDownloader.MODEL_DIR), fileName)
            if (!modelFile.exists()) return
            val buffer = FileUtil.loadMappedFile(context, modelFile.absolutePath)
            closeLocked()
            // float16-quant UFLD: GPU first (Adreno/Mali handle fp16 well),
            // then NNAPI, then 4-thread CPU. Each delegate is tried with a
            // probe inference; failures fall through to the next backend.
            val options = Interpreter.Options().setNumThreads(4)
            var backend = "cpu"
            try {
                val gpu = GpuDelegate()
                options.addDelegate(gpu)
                val probe = Interpreter(buffer, options)
                try {
                    probe.run(obtainInput(), obtainOutput())
                    gpuDelegate = gpu
                    backend = "gpu"
                    probe.close()
                } catch (e: Exception) {
                    try { probe.close() } catch (_: Exception) {}
                    try { gpu.close() } catch (_: Exception) {}
                    options.addDelegate(null)
                    throw e
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            if (backend == "cpu") {
                try {
                    val nnapi = NnApiDelegate()
                    val nnOptions = Interpreter.Options().setNumThreads(4).addDelegate(nnapi)
                    val probe = Interpreter(buffer, nnOptions)
                    try {
                        probe.run(obtainInput(), obtainOutput())
                        nnApiDelegate = nnapi
                        backend = "nnapi"
                        probe.close()
                    } catch (e: Exception) {
                        try { probe.close() } catch (_: Exception) {}
                        try { nnapi.close() } catch (_: Exception) {}
                        nnApiDelegate = null
                        throw e
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            val finalOptions = Interpreter.Options().setNumThreads(4)
            gpuDelegate?.let { finalOptions.addDelegate(it) }
            if (backend == "nnapi") {
                nnApiDelegate?.let { finalOptions.addDelegate(it) }
            }
            interpreter = Interpreter(buffer, finalOptions)
            activeBackend = backend
            cachedInput = null
            cachedOutput = null
        } catch (e: Exception) {
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

    @Synchronized
    fun close() {
        closeLocked()
        cachedInput = null
        cachedOutput = null
    }

    fun reset() {
        emaState.clear()
        emaFrame.clear()
        frameCounter = 0L
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
            val lanes = runInference(itp, bitmap)
            val pair = chooseEgoPair(lanes, bitmap.width)
            if (pair == null) {
                holdLast()
            } else {
                val (li, ri) = pair
                val left = smooth("L", lanes[li]!!)
                val right = smooth("R", lanes[ri]!!)
                val nPts = (lanes[li]!!.size + lanes[ri]!!.size) / (2f * NUM_ROWS)
                val conf = (0.4f + minOf(1f, nPts * 1.5f) * 0.55f).coerceIn(0f, 1f)
                UfldResult(left, right, conf, true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            UfldResult(null, null, 0f, false)
        }
    }

    private fun holdLast(): UfldResult {
        // Dashed lines / shadows: keep last lanes briefly, then decay.
        val l = emaState["L"]?.takeIf { frameCounter - (emaFrame["L"] ?: -99L) <= HOLD_FRAMES }
        val r = emaState["R"]?.takeIf { frameCounter - (emaFrame["R"] ?: -99L) <= HOLD_FRAMES }
        return if (l != null || r != null) {
            UfldResult(l, r, 0.35f, l != null && r != null)
        } else {
            UfldResult(null, null, 0.1f, false)
        }
    }

    private fun smooth(side: String, cur: LanePoints): LanePoints {
        val prev = emaState[side]
        val out = if (prev != null && prev.size == cur.size) {
            val nx = FloatArray(cur.size) { i -> EMA_ALPHA * cur.x[i] + (1 - EMA_ALPHA) * prev.x[i] }
            val ny = FloatArray(cur.size) { i -> EMA_ALPHA * cur.y[i] + (1 - EMA_ALPHA) * prev.y[i] }
            LanePoints(nx, ny)
        } else {
            cur
        }
        emaState[side] = out
        emaFrame[side] = frameCounter
        return out
    }

    private fun runInference(itp: Interpreter, bitmap: Bitmap): Array<LanePoints?> {
        val input = obtainInput()
        val pixels = IntArray(INPUT_W * INPUT_H)
        val scaled = if (bitmap.width == INPUT_W && bitmap.height == INPUT_H) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, INPUT_W, INPUT_H, true)
        }
        try {
            scaled.getPixels(pixels, 0, INPUT_W, 0, 0, INPUT_W, INPUT_H)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
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
                val expVals = FloatArray(GRIDING_NUM)
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
                // Reference formula in 1280x720 cfg space, then scale to image.
                val pxCfg = loc * (800f / GRIDING_NUM) * (CFG_W / 800f) - 1f
                val pyCfg = CFG_H * (ROW_ANCHORS[row] / 288f) - 1f
                xs.add(pxCfg * imgW / CFG_W)
                ys.add(pyCfg * imgH / CFG_H)
            }
            if (xs.size >= MIN_POINTS) {
                lanes[lane] = LanePoints(xs.toFloatArray(), ys.toFloatArray())
            }
        }
        return lanes
    }

    /**
     * Ego pair: one lane left of center, one right of center, plausible gap.
     * Prefers TuSimple-semantic indices (1,2) on ties; accepts (0,2)/(1,3)/
     * (0,3) when the model merged or split lanes. Bottom-row x decides.
     */
    internal fun chooseEgoPair(lanes: Array<LanePoints?>, imgW: Int): Pair<Int, Int>? {
        val mid = imgW / 2f
        data class Cand(val idx: Int, val xBot: Float)
        val left = ArrayList<Cand>()
        val right = ArrayList<Cand>()
        for (i in lanes.indices) {
            val l = lanes[i] ?: continue
            if (l.size < MIN_POINTS) continue
            var maxY = Float.NEGATIVE_INFINITY
            var xAtMaxY = 0f
            for (j in 0 until l.size) {
                if (l.y[j] > maxY) {
                    maxY = l.y[j]
                    xAtMaxY = l.x[j]
                }
            }
            if (xAtMaxY < mid) left.add(Cand(i, xAtMaxY)) else right.add(Cand(i, xAtMaxY))
        }
        var best: Pair<Int, Int>? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (l in left) {
            for (r in right) {
                val gap = (r.xBot - l.xBot) / imgW
                if (gap < MIN_GAP_FRac || gap > MAX_GAP_FRac) continue
                var score = -abs(gap - 0.45f)
                score -= (abs(l.idx - 1) + abs(r.idx - 2)) * 0.01f
                if (score > bestScore) {
                    bestScore = score
                    best = Pair(l.idx, r.idx)
                }
            }
        }
        return best
    }

    data class UfldResult(
        val left: LanePoints?,
        val right: LanePoints?,
        val confidence: Float,
        val bothValid: Boolean
    )
}
