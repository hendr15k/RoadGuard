package com.roadguard.app.data.ml

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TfliteModelRunner(private val context: Context) {

    private var interpreter: Interpreter? = null

    // Fallbacks match DeepLab v3 @ 257x257 with 21 PASCAL classes. The real
    // values are read from the interpreter after loading, so downloaded models
    // with different resolutions/class counts no longer overflow the buffers.
    private var inputWidth: Int = 257
    private var inputHeight: Int = 257
    private var numClasses: Int = 21
    private var inputDataType: DataType = DataType.FLOAT32
    private var inputScale: Float = 1f
    private var inputZeroPoint: Int = 0
    private var lastInferenceSucceeded = false

    // Cached direct buffers. A single frame previously allocated ~790 KB input
    // plus ~5.5 MB output (257*257*21*4) — every frame, every 200 ms. The
    // runner is used from one analyzer thread, so reuse is safe.
    private var cachedInput: ByteBuffer? = null
    private var cachedOutput: ByteBuffer? = null

    @Synchronized
    fun loadModel(modelName: String = "deeplabv3.tflite") {
        try {
            val modelFile = File(context.filesDir, modelName)
            val modelBuffer: ByteBuffer = if (modelFile.exists()) {
                // Interpreter rejects heap buffers — it requires a
                // MappedByteBuffer or a direct ByteBuffer.
                FileUtil.loadMappedFile(context, modelFile.absolutePath)
            } else {
                try {
                    FileUtil.loadMappedFile(context, modelName)
                } catch (e: Exception) {
                    val bytes = context.assets.open(modelName).use { it.readBytes() }
                    ByteBuffer.allocateDirect(bytes.size).put(bytes).also { it.rewind() }
                }
            }
            val newInterpreter = Interpreter(modelBuffer)
            try {
                configureTensorShapes(newInterpreter)
                val oldInterpreter = interpreter
                interpreter = newInterpreter
                oldInterpreter?.close()
            } catch (e: Exception) {
                newInterpreter.close()
                throw e
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun loadModelFromFile(filePath: String) {
        try {
            val modelFile = File(filePath)
            if (modelFile.exists()) {
                val newInterpreter = Interpreter(modelFile)
                try {
                    configureTensorShapes(newInterpreter)
                    val oldInterpreter = interpreter
                    interpreter = newInterpreter
                    oldInterpreter?.close()
                } catch (e: Exception) {
                    newInterpreter.close()
                    throw e
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun configureTensorShapes(newInterpreter: Interpreter) {
        // Reset to defaults first: a failed read must not keep stale values from
        // a previously loaded (different) model.
        inputWidth = 257
        inputHeight = 257
        numClasses = 21
        inputDataType = DataType.FLOAT32
        inputScale = 1f
        inputZeroPoint = 0
        lastInferenceSucceeded = false

        val inTensor = newInterpreter.getInputTensor(0)
        val inShape = inTensor.shape()
        // Only NHWC image models with 3 channels are supported. NCHW or exotic
        // layouts would be indexed wrongly by both input fillers.
        require(inShape.size == 4 && inShape[3] == 3) {
            "Unsupported input shape ${inShape.joinToString()}; expected [1,H,W,3]"
        }
        inputHeight = inShape[1]
        inputWidth = inShape[2]
        inputDataType = inTensor.dataType()
        if (inputDataType == DataType.UINT8 || inputDataType == DataType.INT8) {
            inputScale = inTensor.quantizationParams().scale
            inputZeroPoint = inTensor.quantizationParams().zeroPoint
            require(inputScale > 0f) { "Invalid input quantization scale" }
        } else {
            require(inputDataType == DataType.FLOAT32) { "Unsupported input tensor type: $inputDataType" }
        }

        val outShape = newInterpreter.getOutputTensor(0).shape()
        // Require NHWC [1,H,W,C]: a [1,H,W] argmax output would silently be
        // decoded with the default class count and over-sized buffers.
        require(outShape.size == 4) {
            "Unsupported output shape ${outShape.joinToString()}; expected [1,H,W,C]"
        }
        numClasses = outShape[3]
    }

    @Synchronized
    fun runSegmentation(bitmap: Bitmap): Array<Array<IntArray>> {
        val inputBuffer = preprocessBitmap(bitmap) ?: return emptySegmentation()
        return runInference(inputBuffer)
    }

    @Synchronized
    fun runSegmentationYUV(
        yBuffer: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        uBuffer: ByteBuffer, uRowStride: Int, uPixelStride: Int,
        vBuffer: ByteBuffer, vRowStride: Int, vPixelStride: Int,
        width: Int, height: Int, rotationDegrees: Int
    ): Array<Array<IntArray>> {
        val inputBuffer = yuvToInputBuffer(
            yBuffer, yRowStride, yPixelStride,
            uBuffer, uRowStride, uPixelStride,
            vBuffer, vRowStride, vPixelStride,
            width, height, rotationDegrees
        ) ?: return emptySegmentation()
        return runInference(inputBuffer)
    }

    private fun runInference(inputBuffer: ByteBuffer): Array<Array<IntArray>> {
        val currentInterpreter = interpreter ?: return emptySegmentation()

        val outTensor = currentInterpreter.getOutputTensor(0)
        val shape = outTensor.shape()
        val outHeight = shape.getOrNull(1)?.takeIf { it > 0 } ?: inputHeight
        val outWidth = shape.getOrNull(2)?.takeIf { it > 0 } ?: inputWidth
        val classes = shape.getOrNull(3)?.takeIf { it > 0 } ?: numClasses
        if (outHeight <= 0 || outWidth <= 0 || classes <= 0) return emptySegmentation()

        val outputType = outTensor.dataType()
        require(outputType == DataType.FLOAT32 || outputType == DataType.UINT8 || outputType == DataType.INT8) {
            "Unsupported output tensor type: $outputType"
        }
        val bytesPerElement = if (outputType == DataType.FLOAT32) 4 else 1
        val outputBuffer = obtainOutputBuffer(outHeight * outWidth * classes * bytesPerElement)

        try {
            currentInterpreter.run(inputBuffer, outputBuffer)
        } catch (e: Exception) {
            lastInferenceSucceeded = false
            e.printStackTrace()
            return emptySegmentation()
        }
        lastInferenceSucceeded = true
        outputBuffer.rewind()

        val result = Array(outHeight) { IntArray(outWidth) }
        if (outputType != DataType.FLOAT32) {
            val qScale = outTensor.quantizationParams().scale
            val qZero = outTensor.quantizationParams().zeroPoint
            for (i in 0 until outHeight * outWidth) {
                var bestClass = 0
                var bestScore = Float.MIN_VALUE
                for (c in 0 until classes) {
                    val byteValue = outputBuffer.get(i * classes + c).toInt()
                    val raw = if (outputType == DataType.UINT8) byteValue and 0xFF else byteValue
                    val score = (raw - qZero) * qScale
                    if (score > bestScore) {
                        bestScore = score
                        bestClass = c
                    }
                }
                val y = i / outWidth
                val x = i % outWidth
                result[y][x] = bestClass
            }
        } else {
            val floatBuffer = outputBuffer.asFloatBuffer()
            for (i in 0 until outHeight * outWidth) {
                var bestClass = 0
                var bestScore = Float.MIN_VALUE
                for (c in 0 until classes) {
                    val score = floatBuffer.get(i * classes + c)
                    if (score > bestScore) {
                        bestScore = score
                        bestClass = c
                    }
                }
                val y = i / outWidth
                val x = i % outWidth
                result[y][x] = bestClass
            }
        }

        return arrayOf(result)
    }

    /**
     * Convert the camera YUV planes directly onto the model's input grid.
     *
     * Strides come from the plane metadata (padding breaks the old
     * `remaining()/height` guess) and the frame is mapped into the upright
     * orientation before sampling — the model otherwise sees a rotated road in
     * portrait, which flips the left/right split used for drift detection.
     */
    private fun yuvToInputBuffer(
        yBuffer: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        uBuffer: ByteBuffer, uRowStride: Int, uPixelStride: Int,
        vBuffer: ByteBuffer, vRowStride: Int, vPixelStride: Int,
        width: Int, height: Int, rotationDegrees: Int
    ): ByteBuffer? {
        if (width <= 0 || height <= 0 || yRowStride <= 0 || uRowStride <= 0 || vRowStride <= 0) {
            return null
        }
        val yBase = yBuffer.position()
        val uBase = uBuffer.position()
        val vBase = vBuffer.position()
        val yLimit = yBuffer.limit()
        val uLimit = uBuffer.limit()
        val vLimit = vBuffer.limit()
        if (yBase >= yLimit) return null

        val rotation = ((rotationDegrees % 360) + 360) % 360
        val uprightLandscape = rotation == 0 || rotation == 180
        val dispW = if (uprightLandscape) width else height
        val dispH = if (uprightLandscape) height else width

        val bytesPerChannel = if (inputDataType == DataType.FLOAT32) 4 else 1
        val input = obtainInputBuffer(inputWidth * inputHeight * 3 * bytesPerChannel)
        for (oy in 0 until inputHeight) {
            val uy = ((oy + 0.5f) * dispH / inputHeight).toInt().coerceIn(0, dispH - 1)
            for (ox in 0 until inputWidth) {
                val ux = ((ox + 0.5f) * dispW / inputWidth).toInt().coerceIn(0, dispW - 1)

                // Shared with LaneDetector so both paths de-rotate identically.
                val packed = uprightToBufferIndex(ux, uy, width, height, rotation)
                val bx = (packed shr 32).toInt()
                val by = packed.toInt()

                val yIndex = yBase + by * yRowStride + bx * yPixelStride
                val uvX = bx / 2
                val uvY = by / 2
                val uIndex = uBase + uvY * uRowStride + uvX * uPixelStride
                val vIndex = vBase + uvY * vRowStride + uvX * vPixelStride

                val yVal = if (yIndex in 0 until yLimit) yBuffer.get(yIndex).toInt() and 0xFF else 16
                val uVal = if (uIndex in 0 until uLimit) uBuffer.get(uIndex).toInt() and 0xFF else 128
                val vVal = if (vIndex in 0 until vLimit) vBuffer.get(vIndex).toInt() and 0xFF else 128

                // BT.601 limited-range (camera YUV) → full-range RGB, then to
                // the [-1, 1] normalization the model expects.
                val yFull = ((yVal - 16) * 255f / 219f).coerceIn(0f, 255f)
                val r = (yFull + 1.402f * (vVal - 128)).coerceIn(0f, 255f)
                val g = (yFull - 0.344136f * (uVal - 128) - 0.714136f * (vVal - 128)).coerceIn(0f, 255f)
                val b = (yFull + 1.772f * (uVal - 128)).coerceIn(0f, 255f)

                if (inputDataType != DataType.FLOAT32) {
                    input.put(quantize(r).toByte())
                    input.put(quantize(g).toByte())
                    input.put(quantize(b).toByte())
                } else {
                    input.putFloat(r / 127.5f - 1f)
                    input.putFloat(g / 127.5f - 1f)
                    input.putFloat(b / 127.5f - 1f)
                }
            }
        }
        input.rewind()
        return input
    }

    private fun quantize(rawChannel: Float): Int {
        val q = kotlin.math.round(rawChannel / inputScale + inputZeroPoint).toInt()
        return if (inputDataType == DataType.INT8) q.coerceIn(-128, 127) else q.coerceIn(0, 255)
    }

    private fun obtainInputBuffer(sizeBytes: Int): ByteBuffer {
        val existing = cachedInput
        if (existing != null && existing.capacity() == sizeBytes) {
            existing.clear()
            return existing
        }
        val buffer = ByteBuffer.allocateDirect(sizeBytes).order(ByteOrder.nativeOrder())
        cachedInput = buffer
        return buffer
    }

    private fun obtainOutputBuffer(sizeBytes: Int): ByteBuffer {
        val existing = cachedOutput
        if (existing != null && existing.capacity() == sizeBytes) {
            existing.clear()
            return existing
        }
        val buffer = ByteBuffer.allocateDirect(sizeBytes).order(ByteOrder.nativeOrder())
        cachedOutput = buffer
        return buffer
    }

    fun detectLanesFromSegmentation(
        segmentation: Array<IntArray>,
        imgWidth: Int,
        imgHeight: Int
    ): LaneDetector.LaneDetectionResult {
        val segH = segmentation.size
        val segW = if (segH > 0) segmentation[0].size else 0
        if (!lastInferenceSucceeded || segH == 0 || segW == 0 || imgWidth <= 0 || imgHeight <= 0) {
            return LaneDetector.LaneDetectionResult(null, null, 0f, false, false, 0.05f, 0f)
        }

        // The DeepLab model is trained on PASCAL VOC — there is no "road" class.
        // Class 0 (background) in the lower two thirds of an upright dashcam
        // frame is the free drivable surface; everything else (vehicles,
        // persons, riders...) is an obstacle. The old code counted the VEHICLE
        // classes {2,6,7,14} as "road" and reported drift whenever a truck sat
        // off-center — a guaranteed false alarm.
        val startY = segH / 3
        var freeCount = 0
        var totalCount = 0
        var sumFreeX = 0.0
        for (y in startY until segH) {
            val row = segmentation[y]
            for (x in 0 until segW) {
                totalCount++
                if (row[x] == 0) {
                    freeCount++
                    sumFreeX += x
                }
            }
        }
        if (freeCount == 0) {
            return LaneDetector.LaneDetectionResult(null, null, 0f, false, false, 0.05f, 0f)
        }

        val freeRatio = freeCount.toFloat() / totalCount
        // A healthy segmentation always labels vehicles, signs, sky and
        // vegetation as non-background. freeRatio == 1.0 means the model
        // collapsed (e.g. domain mismatch: PASCAL-VOC classes on dashcam
        // footage) and the "free surface" is the whole frame — its centroid
        // is then the image center and any drift signal is noise. Verified on
        // real dashcam clips: freeRatio was exactly 1.00 on all scenes, so
        // this path must report unusable instead of a confident offset.
        if (freeRatio >= 0.995f) {
            return LaneDetector.LaneDetectionResult(null, null, 0f, false, false, 0.05f, 0f)
        }
        val freeCentroidPx = (sumFreeX / freeCount).toFloat() * imgWidth / segW
        val vehicleCenter = imgWidth * 0.5f
        val centerOffset = vehicleCenter - freeCentroidPx

        val driftThreshold = imgWidth * 0.06f
        val usable = freeRatio > 0.15f
        val isDriftingLeft = centerOffset < -driftThreshold && usable
        val isDriftingRight = centerOffset > driftThreshold && usable

        val confidence = if (usable) (freeRatio * 1.6f * 0.55f).coerceIn(0.1f, 0.55f) else 0.05f

        return LaneDetector.LaneDetectionResult(
            leftLane = null,
            rightLane = null,
            centerOffset = centerOffset,
            isDriftingLeft = isDriftingLeft,
            isDriftingRight = isDriftingRight,
            confidence = confidence,
            laneWidth = 0f
        )
    }

    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer? {
        // createScaledBitmap gives the ORIGINAL Bitmap back when the dimensions
        // already match — in that case we must NOT recycle it because the
        // caller still needs it (YUV path, frame loop).
        val needsScale = bitmap.width != inputWidth || bitmap.height != inputHeight
        val resized = if (needsScale) {
            try {
                Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        } else {
            bitmap
        }

        val bytesPerChannel = if (inputDataType == DataType.FLOAT32) 4 else 1
        val inputBuffer = obtainInputBuffer(inputWidth * inputHeight * 3 * bytesPerChannel)
        val pixels = IntArray(inputWidth * inputHeight)
        resized.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF)
            val g = ((pixel shr 8) and 0xFF)
            val b = (pixel and 0xFF)
            if (inputDataType != DataType.FLOAT32) {
                inputBuffer.put(quantize(r.toFloat()).toByte())
                inputBuffer.put(quantize(g.toFloat()).toByte())
                inputBuffer.put(quantize(b.toFloat()).toByte())
            } else {
                inputBuffer.putFloat(r / 127.5f - 1f)
                inputBuffer.putFloat(g / 127.5f - 1f)
                inputBuffer.putFloat(b / 127.5f - 1f)
            }
        }

        if (resized !== bitmap) resized.recycle()
        inputBuffer.rewind()
        return inputBuffer
    }

    private fun emptySegmentation(): Array<Array<IntArray>> {
        return arrayOf(Array(inputHeight) { IntArray(inputWidth) { 0 } })
    }

    @Synchronized
    fun isLoaded(): Boolean = interpreter != null

    @Synchronized
    fun close() {
        // Idempotent: a second close() must not crash when the interpreter has
        // already been disposed.
        val current = interpreter ?: return
        interpreter = null
        lastInferenceSucceeded = false
        try {
            current.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        cachedInput = null
        cachedOutput = null
    }
}
