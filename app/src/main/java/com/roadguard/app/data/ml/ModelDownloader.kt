package com.roadguard.app.data.ml

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelDownloader(private val context: Context) {

    companion object {
        const val MODEL_DIR = "roadguard_models"
        const val CURRENT_MODEL_FILE = "lane_model.tflite"

        val modelSources = listOf(
            ModelSource(
                name = "Ultra-Fast-Lane-Detection (TuSimple)",
                url = "https://storage.googleapis.com/download.tensorflow.org/models/tflite/gpu/deeplabv3_257_mv_gpu.tflite",
                description = "DeepLab v3 segmentation model for road understanding"
            ),
            ModelSource(
                name = "MobileNetV2 SSD Vehicle Detection",
                url = "https://storage.googleapis.com/download.tensorflow.org/models/tflite/gpu/mobilenet_v2_ssd_1.0_fpn_2x_256_quantized_edgetpu.tflite",
                description = "Lightweight vehicle detection model"
            )
        )
    }

    data class ModelSource(
        val name: String,
        val url: String,
        val description: String
    )

    suspend fun downloadModel(modelUrl: String, fileName: String = CURRENT_MODEL_FILE): Boolean {
        return withContext(Dispatchers.IO) {
            val connection = try {
                val url = URL(modelUrl)
                url.openConnection() as HttpURLConnection
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext false
            }

            try {
                val modelDir = File(context.filesDir, MODEL_DIR)
                if (!modelDir.exists()) modelDir.mkdirs()

                val modelFile = File(modelDir, fileName)
                val tempFile = File(modelDir, "$fileName.tmp")

                if (tempFile.exists()) tempFile.delete()

                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext false
                }

                val contentLength = connection.contentLengthLong
                val maxSize = 150L * 1024L * 1024L
                if (contentLength > maxSize) {
                    android.util.Log.e("ModelDownloader", "Model too large: $contentLength bytes (max $maxSize)")
                    return@withContext false
                }

                connection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8192)
                        var totalBytes = 0L
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            totalBytes += bytesRead
                            if (totalBytes > maxSize) {
                                android.util.Log.e("ModelDownloader", "Download exceeded max size during transfer")
                                return@withContext false
                            }
                            output.write(buffer, 0, bytesRead)
                        }
                        output.flush()
                    }
                }

                if (modelFile.exists()) modelFile.delete()
                if (!tempFile.renameTo(modelFile)) {
                    android.util.Log.e("ModelDownloader", "Failed to rename temp file to model file")
                    return@withContext false
                }
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            } finally {
                connection.disconnect()
            }
        }
    }

    suspend fun downloadAllModels(): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()
        for (source in modelSources) {
            val fileNameForSource = source.url.substringAfterLast("/").substringBefore("?")
                .ifBlank { "${source.name.hashCode()}.tflite" }
            val success = downloadModel(source.url, fileNameForSource)
            results[source.name] = success
        }
        return results
    }

    fun getModelFile(fileName: String = CURRENT_MODEL_FILE): File {
        return File(File(context.filesDir, MODEL_DIR), fileName)
    }

    fun hasModel(fileName: String = CURRENT_MODEL_FILE): Boolean {
        return getModelFile(fileName).exists()
    }

    fun getModelPath(fileName: String = CURRENT_MODEL_FILE): String {
        return getModelFile(fileName).absolutePath
    }
}
