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
            var success = false
            var tempFileRef: File? = null
            val connection = try {
                val url = URL(modelUrl)
                url.openConnection() as HttpURLConnection
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext false
            }

            try {
                // Prevent path traversal if a caller ever supplies a remote or
                // user-controlled filename. Models are always confined to MODEL_DIR.
                val safeFileName = File(fileName).name
                if (safeFileName.isBlank() || safeFileName == "." || safeFileName == "..") {
                    return@withContext false
                }
                val modelDir = File(context.filesDir, MODEL_DIR)
                if (!modelDir.exists() && !modelDir.mkdirs()) return@withContext false

                val modelFile = File(modelDir, safeFileName)
                // Unique temp file so a retry or a concurrent download cannot
                // delete/clobber another download's in-progress file.
                val tempFile = File.createTempFile("$safeFileName.", ".tmp", modelDir)
                tempFileRef = tempFile

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

                if (tempFile.length() <= 0L) return@withContext false

                // Move the current model aside instead of deleting it, so a failed
                // rename cannot leave the app with no model at all.
                val backup = File(modelDir, "$safeFileName.bak")
                if (modelFile.exists()) {
                    backup.delete()
                    if (!modelFile.renameTo(backup)) {
                        android.util.Log.e("ModelDownloader", "Failed to move existing model aside")
                        return@withContext false
                    }
                }
                if (!tempFile.renameTo(modelFile)) {
                    android.util.Log.e("ModelDownloader", "Failed to rename temp file to model file")
                    if (backup.exists()) backup.renameTo(modelFile)
                    return@withContext false
                }
                backup.delete()
                success = true
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            } finally {
                if (!success) {
                    try {
                        tempFileRef?.delete()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
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
