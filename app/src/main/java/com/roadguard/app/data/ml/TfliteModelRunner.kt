package com.roadguard.app.data.ml

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TfliteModelRunner(private val context: Context) {

    private var interpreter: Interpreter? = null
    private val inputSize = 257
    private var numClasses: Int = 21

    fun loadModel(modelName: String = "deeplabv3.tflite") {
        try {
            val modelFile = File(context.filesDir, modelName)
            val modelBuffer: java.nio.ByteBuffer = if (modelFile.exists()) {
                java.nio.ByteBuffer.wrap(modelFile.readBytes())
            } else {
                try {
                    FileUtil.loadMappedFile(context, modelName)
                } catch (e: Exception) {
                    val bytes = context.assets.open(modelName).use { it.readBytes() }
                    java.nio.ByteBuffer.wrap(bytes)
                }
            }
            val newInterpreter = Interpreter(modelBuffer)
            val oldInterpreter = interpreter
            interpreter = newInterpreter
            oldInterpreter?.close()

            val outputShape = newInterpreter.getOutputTensor(0).shape()
            // NHWC-Layout [1,H,W,C]: Klassenzahl steht an letzter Stelle,
            // nicht an Index 1 (dort steht die Höhe → numClasses=257 war
            // falsch und erzeugte BufferOverflow im Output-Indexing).
            if (outputShape.size >= 2) {
                val classes = outputShape[outputShape.size - 1]
                if (classes > 0) {
                    numClasses = classes
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadModelFromFile(filePath: String) {
        try {
            val modelFile = File(filePath)
            if (modelFile.exists()) {
                val newInterpreter = Interpreter(modelFile)
                val oldInterpreter = interpreter
                interpreter = newInterpreter
                oldInterpreter?.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun runSegmentation(bitmap: Bitmap): Array<Array<IntArray>> {
        val interpreter = interpreter ?: return emptySegmentation()

        val inputBuffer = preprocessBitmap(bitmap)
        val outputShape = interpreter.getOutputTensor(0).shape()
        // [1, H, W, C] — H/W/C jeweils mit Fallback auf inputSize/numClasses
        val outHeight = outputShape.getOrNull(1)?.takeIf { it > 0 } ?: inputSize
        val outWidth = outputShape.getOrNull(2)?.takeIf { it > 0 } ?: inputSize
        val classes = outputShape.getOrNull(3)?.takeIf { it > 0 } ?: numClasses
        val outputBuffer = ByteBuffer.allocateDirect(
            outHeight * outWidth * classes * 4
        ).order(ByteOrder.nativeOrder())

        interpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        val result = Array(outHeight) { IntArray(outWidth) }

        val floatBuffer = outputBuffer.asFloatBuffer()
        for (y in 0 until outHeight) {
            for (x in 0 until outWidth) {
                var maxClass = 0
                var maxScore = Float.MIN_VALUE
                for (c in 0 until classes) {
                    val score = floatBuffer.get(((y * outWidth) + x) * classes + c)
                    if (score > maxScore) {
                        maxScore = score
                        maxClass = c
                    }
                }
                result[y][x] = maxClass
            }
        }

        return arrayOf(result)
    }

    fun runSegmentationYUV(yBuffer: ByteBuffer, uBuffer: ByteBuffer, vBuffer: ByteBuffer, width: Int, height: Int): Array<Array<IntArray>> {
        val bitmap = yuvToBitmap(yBuffer, uBuffer, vBuffer, width, height)
        return runSegmentation(bitmap)
    }

    fun detectLanesFromSegmentation(segmentation: Array<IntArray>, imgWidth: Int, imgHeight: Int): LaneDetector.LaneDetectionResult {
        val segH = segmentation.size
        val segW = if (segH > 0) segmentation[0].size else 0
        if (segH == 0 || segW == 0) {
            return LaneDetector.LaneDetectionResult(null, null, 0f, false, false, 0.05f, 0f)
        }

        val roadClasses = setOf(2, 6, 7, 14)
        val binaryRoad = Array(segH) { y ->
            ByteArray(segW) { x ->
                val cls = segmentation[y][x]
                if (cls in roadClasses) 255.toByte() else 0.toByte()
            }
        }

        var leftScore = 0f
        var rightScore = 0f
        var totalLeft = 0
        var totalRight = 0

        val centerX = segW / 2
        val startY = segH / 3

        for (y in startY until segH) {
            for (x in 0 until centerX) {
                if (binaryRoad[y][x] > 0) totalLeft++
            }
            for (x in centerX until segW) {
                if (binaryRoad[y][x] > 0) totalRight++
            }
        }

        val total = totalLeft + totalRight
        if (total > 0) {
            leftScore = totalLeft.toFloat() / total
            rightScore = totalRight.toFloat() / total
        }

        val idealRatio = 0.5f
        val driftThreshold = 0.15f * (1f + (1f - 0.5f))

        val isDriftingLeft = leftScore < idealRatio - driftThreshold && leftScore > 0.1f
        val isDriftingRight = rightScore < idealRatio - driftThreshold && rightScore > 0.1f

        return LaneDetector.LaneDetectionResult(
            leftLane = null,
            rightLane = null,
            centerOffset = 0f,
            isDriftingLeft = isDriftingLeft,
            isDriftingRight = isDriftingRight,
            confidence = if (total > 100) 0.6f else 0.1f,
            laneWidth = 0f
        )
    }

    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        // createScaledBitmap gibt das Original-Bitmap UNVERÄNDERT zurück, wenn
        // die Maße bereits passen. In dem Fall dürfen wir NICHT recyceln, weil
        // der Aufrufer das Bitmap noch braucht (YUV-Pfad, Frame-Loop).
        val needsScale = bitmap.width != inputSize || bitmap.height != inputSize
        val resized = if (needsScale) {
            Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        } else {
            bitmap
        }
        val inputBuffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4)
            .order(ByteOrder.nativeOrder())

        val pixels = IntArray(inputSize * inputSize)
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in pixels) {
            val r = ((pixel shr 16 and 0xFF).toFloat() / 127.5f) - 1.0f
            val g = ((pixel shr 8 and 0xFF).toFloat() / 127.5f) - 1.0f
            val b = ((pixel and 0xFF).toFloat() / 127.5f) - 1.0f
            inputBuffer.putFloat(r)
            inputBuffer.putFloat(g)
            inputBuffer.putFloat(b)
        }

        if (resized !== bitmap) resized.recycle()
        return inputBuffer
    }

    private fun yuvToBitmap(yPlane: ByteBuffer, uPlane: ByteBuffer, vPlane: ByteBuffer, width: Int, height: Int): Bitmap {
        return try {
            // Stride-Safety: wenn der Buffer kleiner ist als erwartet, würden
            // wir durch 0 teilen (Integer-Division) und danach Off-by-Many-
            // Pixel lesen. coerceAtLeast(1) verhindert das.
            val yRowStride = if (height > 0) (yPlane.remaining() / height).coerceAtLeast(1) else 1
            val uvRowStride = if (height > 1) (uPlane.remaining() / (height / 2)).coerceAtLeast(1) else 1
            val uvPixelStride = if (uvRowStride > 0) (uPlane.remaining() / (height / 2) / 2).coerceAtLeast(1) else 1
            val yPlaneSize = yPlane.remaining()
            val uPlaneSize = uPlane.remaining()
            val vPlaneSize = vPlane.remaining()

            val pixels = IntArray(width * height)

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val yIndex = y * yRowStride + x
                    val uvX = x / 2
                    val uvY = y / 2
                    val uvIndex = uvY * uvRowStride + uvX

                    val yVal = if (yIndex < yPlaneSize) yPlane.get(yIndex).toInt() and 0xFF else 0
                    val uVal = if (uvIndex < uPlaneSize) uPlane.get(uvIndex * uvPixelStride).toInt() and 0xFF else 128
                    val vVal = if (uvIndex < vPlaneSize) vPlane.get(uvIndex * uvPixelStride).toInt() and 0xFF else 128

                    val r = (yVal + 1.402 * (vVal - 128)).toInt().coerceIn(0, 255)
                    val g = (yVal - 0.344136 * (uVal - 128) - 0.714136 * (vVal - 128)).toInt().coerceIn(0, 255)
                    val b = (yVal + 1.772 * (uVal - 128)).toInt().coerceIn(0, 255)

                    pixels[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            e.printStackTrace()
            Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        }
    }

    private fun emptySegmentation(): Array<Array<IntArray>> {
        return arrayOf(Array(257) { IntArray(257) { 0 } })
    }

    fun isLoaded(): Boolean = interpreter != null

    fun close() {
        // Idempotent: zweiter close() darf nicht crashen, falls der
        // Interpreter bereits disposed wurde.
        val current = interpreter ?: return
        interpreter = null
        try {
            current.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
