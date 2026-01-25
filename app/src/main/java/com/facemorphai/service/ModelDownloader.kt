package com.facemorphai.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles downloading AI models from remote URLs.
 *
 * Note: OmniNeural-4B-mobile is 4.76 GB split across multiple .nexa files.
 * For hackathon demos, consider using mock mode instead of full download.
 */
class ModelDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloader"

        // HuggingFace model info
        // Full model: https://huggingface.co/NexaAI/OmniNeural-4B-mobile
        // Total size: ~4.76 GB (8 weight files + attachments)
        const val HF_REPO = "NexaAI/OmniNeural-4B-mobile"
        const val HF_BASE_URL = "https://huggingface.co/$HF_REPO/resolve/main/"

        // Model files needed (from HuggingFace repo)
        val MODEL_FILES = listOf(
            "config.json",
            "nexa.manifest",
            "weights-1-8.nexa",  // 767 MB
            "weights-2-8.nexa",  // 1.16 GB
            "weights-3-8.nexa",  // 4.57 MB
            "weights-4-8.nexa",  // 900 MB
            "weights-5-8.nexa",  // 14.3 MB
            "weights-6-8.nexa",  // 5.62 MB
            "weights-7-8.nexa",  // 651 MB
            "weights-8-8.nexa",  // 1.24 GB
            "attachments-1-3.nexa",
            "attachments-2-3.nexa",
            "attachments-3-3.nexa",
            "files-1-1.nexa"    // 11.4 MB
        )

        const val MODEL_DIR_NAME = "OmniNeural-4B-mobile"

        // Nexa license token (set via NexaService)
        const val LICENSE_TOKEN = "key/eyJhY2NvdW50Ijp7ImlkIjoiNDI1Y2JiNWQtNjk1NC00NDYxLWJiOWMtYzhlZjBiY2JlYzA2In0sInByb2R1Y3QiOnsiaWQiOiIxNDY0ZTk1MS04MGM5LTRjN2ItOWZmYS05MmYyZmQzNmE5YTMifSwicG9saWN5Ijp7ImlkIjoiYzI1YjE3OTUtNTY0OC00NGY1LTgxMmUtNGQ3ZWM3ZjFjYWI0IiwiZHVyYXRpb24iOjI1OTIwMDB9LCJ1c2VyIjp7ImlkIjoiMDM0NjUyZjMtYjc0NS00YzlkLWE3NGItMmRiZmVlM2JhMDQzIiwiZW1haWwiOiJyaWNhcmRvYmV6aTA2QGdtYWlsLmNvbSJ9LCJsaWNlbnNlIjp7ImlkIjoiOWMyODQ2N2YtYzQ0ZC00N2Y5LWJmZDAtMjMzZmNiNzc0NjQ0IiwiY3JlYXRlZCI6IjIwMjYtMDEtMjNUMjA6MjE6NTAuNTE2WiIsImV4cGlyeSI6IjIwMjYtMDItMjJUMjA6MjE6NTAuNTE2WiJ9fQ==.ALRx-MRDIZkxgk4L_X61uZ2iwccq5V_qfXESTjNonhTCncGPZnlqAPByQkm9skYkYZON8rM0AIAis1t7SlELAw=="
    }

    private val modelsDir: File
        get() = File(context.filesDir, "models")

    /**
     * Check if model is already downloaded (all required files present).
     */
    fun isModelDownloaded(): Boolean {
        val modelDir = getModelDirectory()
        if (!modelDir.exists()) return false

        // Check if at least the manifest and first weight file exist
        val manifest = File(modelDir, "nexa.manifest")
        val firstWeight = File(modelDir, "weights-1-8.nexa")
        return manifest.exists() && firstWeight.exists()
    }

    /**
     * Get the path to the model directory.
     */
    fun getModelPath(): String {
        return getModelDirectory().absolutePath
    }

    /**
     * Get the license token for NexaSDK.
     */
    fun getLicenseToken(): String = LICENSE_TOKEN

    private fun getModelDirectory(): File {
        val modelDir = File(modelsDir, MODEL_DIR_NAME)
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }
        return modelDir
    }

    /**
     * Get count of downloaded files vs total required.
     */
    fun getDownloadProgress(): Pair<Int, Int> {
        val modelDir = getModelDirectory()
        val downloadedCount = MODEL_FILES.count { File(modelDir, it).exists() }
        return Pair(downloadedCount, MODEL_FILES.size)
    }

    /**
     * Download all model files with progress updates.
     * Note: Total size is ~4.76 GB - this may take a while!
     */
    fun downloadModel(): Flow<DownloadState> = flow {
        emit(DownloadState.Starting)

        val modelDir = getModelDirectory()
        modelDir.mkdirs()

        var totalDownloaded = 0L
        val estimatedTotalBytes = 4_760_000_000L // ~4.76 GB

        try {
            for ((index, fileName) in MODEL_FILES.withIndex()) {
                val targetFile = File(modelDir, fileName)

                // Skip if already downloaded
                if (targetFile.exists() && targetFile.length() > 0) {
                    Log.d(TAG, "Skipping already downloaded: $fileName")
                    totalDownloaded += targetFile.length()
                    continue
                }

                val fileUrl = HF_BASE_URL + fileName
                Log.d(TAG, "Downloading: $fileUrl")

                emit(DownloadState.Progress(
                    progress = (index * 100 / MODEL_FILES.size),
                    downloadedMB = totalDownloaded / 1024 / 1024,
                    totalMB = estimatedTotalBytes / 1024 / 1024,
                    currentFile = fileName
                ))

                downloadFile(fileUrl, targetFile) { bytesDownloaded ->
                    val overallProgress = ((totalDownloaded + bytesDownloaded).toFloat() / estimatedTotalBytes * 100).toInt()
                    // Note: Can't emit from callback, so we just log
                    Log.d(TAG, "$fileName: ${bytesDownloaded / 1024 / 1024} MB")
                }

                totalDownloaded += targetFile.length()
            }

            Log.d(TAG, "All files downloaded to: ${modelDir.absolutePath}")
            emit(DownloadState.Completed(modelDir.absolutePath))

        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            emit(DownloadState.Error(e.message ?: "Download failed"))
        }
    }.flowOn(Dispatchers.IO)

    private fun downloadFile(urlString: String, targetFile: File, onProgress: (Long) -> Unit) {
        var connection: HttpURLConnection? = null
        val tempFile = File(targetFile.parent, "${targetFile.name}.tmp")

        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 60000
            connection.readTimeout = 60000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP error: $responseCode for ${targetFile.name}")
            }

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var downloadedBytes = 0L
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        onProgress(downloadedBytes)
                    }
                }
            }

            // Move temp to final
            if (targetFile.exists()) targetFile.delete()
            tempFile.renameTo(targetFile)

        } finally {
            connection?.disconnect()
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /**
     * Delete downloaded model to free space.
     */
    fun deleteModel(): Boolean {
        val modelDir = getModelDirectory()
        return if (modelDir.exists()) {
            modelDir.deleteRecursively()
        } else {
            true
        }
    }

    /**
     * Get the size of the downloaded model in MB.
     */
    fun getModelSizeMB(): Long {
        val modelDir = getModelDirectory()
        if (!modelDir.exists()) return 0

        var totalSize = 0L
        modelDir.listFiles()?.forEach { file ->
            totalSize += file.length()
        }
        return totalSize / 1024 / 1024
    }

    sealed class DownloadState {
        object Starting : DownloadState()
        data class Progress(
            val progress: Int, // 0-100, or -1 for indeterminate
            val downloadedMB: Long,
            val totalMB: Long,
            val currentFile: String = ""
        ) : DownloadState()
        data class Completed(val modelPath: String) : DownloadState()
        data class Error(val message: String) : DownloadState()
    }
}
