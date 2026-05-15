package com.roadguard.app.data.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicYuvToRGB
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class TfliteModelRunner(private val context: Context) {

    private var interpreter: Interpreter? = null
    private val inputSize = 257
    private val numClasses = 21

    fun loadModel(modelName: String = "deeplabv3.tflite") {
        try {
            val modelFile = File(context.filesDir, modelName)
            val modelBuffer: java.nio.ByteBuffer = if (modelFile.exists()) {
                java.nio.ByteBuffer.wrap(modelFile.readBytes())
            } else {
                try {
                    FileUtil.loadMappedFile(context, modelName)
                } catch (e: Exception) {
                    val assetFd = context.assets.openFd(modelName)
                    val inputStream = context.assets.open(modelName)
                    val bytes = inputStream.readBytes()
                    inputStream.close()
                    assetFd.close()
                    java.nio.ByteBuffer.wrap(bytes)
                }
            }
            interpreter = Interpreter(modelBuffer)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadModelFromFile(filePath: String) {
        try {
            val modelFile = File(filePath)
            if (modelFile.exists()) {
                interpreter = Interpreter(modelFile)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun runSegmentation(bitmap: Bitmap): Array<Array<IntArray>> {
        val interpreter = interpreter ?: return emptySegmentation()

        val inputBuffer = preprocessBitmap(bitmap)
        val outputShape = interpreter.getOutputTensor(0).shape()
        val outputSize = outputShape[2] * outputShape[3]
        val outputBuffer = ByteBuffer.allocateDirect(
            outputShape[1] * outputSize * 4
        ).order(ByteOrder.nativeOrder())

        interpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        val height = outputShape[2]
        val width = outputShape[3]
        val result = Array(height) { IntArray(width) }

        val floatBuffer = outputBuffer.asFloatBuffer()
        for (y in 0 until height) {
            for (x in 0 until width) {
                var maxClass = 0
                var maxScore = Float.MIN_VALUE
                for (c in 0 until numClasses) {
                    val score = floatBuffer.get(y * width * numClasses + x * numClasses + c)
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

    fun runSegmentationYUV(yData: ByteArray, width: Int, height: Int): Array<Array<IntArray>> {
        val bitmap = yuvToBitmap(yData, width, height)
        return runSegmentation(bitmap)
    }

    fun detectLanesFromSegmentation(segmentation: Array<IntArray>, imgWidth: Int, imgHeight: Int): LaneDetector.LaneDetectionResult {
        val segH = segmentation.size
        val segW = if (segH > 0) segmentation[0].size else 0
        if (segH == 0 || segW == 0) {
            return LaneDetector.LaneDetectionResult(null, null, false, false, 0.05f)
        }

        val roadClasses = setOf(2, 6, 7, 14)
        val binaryRoad = Array(segH) { y ->
            ByteArray(segW) { x ->
                if (segmentation[y][x] in roadClasses || segmentation[y][x] > 0) 255.toByte() else 0.toByte()
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
            isDriftingLeft = isDriftingLeft,
            isDriftingRight = isDriftingRight,
            confidence = if (total > 100) 0.6f else 0.1f
        )
    }

    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
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

        resized.recycle()
        return inputBuffer
    }

    @Suppress("DEPRECATION")
    private fun yuvToBitmap(yData: ByteArray, width: Int, height: Int): Bitmap {
        return try {
            val yuvBuffer = ByteBuffer.allocate(yData.size + width * height / 2)
            yuvBuffer.put(yData)
            yuvBuffer.rewind()

            val rs = RenderScript.create(context)
            val yuvToRgb = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))
            val yuvAllocation = Allocation.createSized(rs, Element.U8(rs), yData.size)
            yuvAllocation.copyFrom(yData)
            val rgbaAllocation = Allocation.createTyped(rs, android.renderscript.Type.Builder(rs, Element.RGBA_8888(rs))
                .setX(width).setY(height).create())
            yuvToRgb.setInput(yuvAllocation)
            yuvToRgb.forEach(rgbaAllocation)
            val pixels = IntArray(width * height)
            rgbaAllocation.copyTo(pixels)

            rs.destroy()
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            e.printStackTrace()
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
    }

    private fun emptySegmentation(): Array<Array<IntArray>> {
        return arrayOf(Array(257) { IntArray(257) { 0 } })
    }

    fun isLoaded(): Boolean = interpreter != null

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
