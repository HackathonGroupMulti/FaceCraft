package com.facemorphai.service

import android.content.Context
import android.util.Log
import com.facemorphai.config.AppConfig
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
 */
class ModelDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloader"

        // HuggingFace model info
        const val HF_REPO = "NexaAI/OmniNeural-4B-mobile"
        const val HF_BASE_URL = "https://huggingface.co/$HF_REPO/resolve/main/"

        // Model files needed exactly as the SDK expects
        val MODEL_FILES = listOf(
            "config.json",
            "nexa.manifest",
            "weights-1-8.nexa",
            "weights-2-8.nexa",
            "weights-3-8.nexa",
            "weights-4-8.nexa",
            "weights-5-8.nexa",
            "weights-6-8.nexa",
            "weights-7-8.nexa",
            "weights-8-8.nexa",
            "attachments-1-3.nexa",
            "attachments-2-3.nexa",
            "attachments-3-3.nexa",
            "files-1-1.nexa"
        )

        // The SDK often expects the model folder to be named exactly this
        const val MODEL_DIR_NAME = "OmniNeural-4B-mobile"

        // Nexa license token
        const val LICENSE_TOKEN = "key/eyJhY2NvdW50Ijp7ImlkIjoiNDI1Y2JiNWQtNjk1NC00NDYxLWJiOWMtYzhlZjBiY2JlYzA2In0sInByb2R1Y3QiOnsiaWQiOiIxNDY0ZTk1MS04MGM5LTRjN2ItOWZmYS05MmYyZmQzNmE5YTMifSwicG9saWN5Ijp7ImlkIjoiYzI1YjE3OTUtNTY0OC00NGY1LTgxMmUtNGQ3ZWM3ZjFjYWI0IiwiZHVyYXRpb24iOjI1OTIwMDB9LCJ1c2VyIjp7ImlkIjoiMDM0NjUyZjMtYjc0NS00YzlkLWE3NGItMmRiZmVlM2JhMDQzIiwiZW1haWwiOiJyaWNhcmRvYmV6i06QGdtYWlsLmNvbSJ9LCJsaWNlbnNlIjp7ImlkIjoiOWMyODQ2N2YtYzQ0ZC00N2Y5LWJmZDAtMjMzZmNiNzc0NjQ0IiwiY3JlYXRlZCI6IjIwMjYtMDEtMjNUMjA6MjE6NTAuNTE2WiIsImV4cGlyeSI6IjIwMjYtMDItMjJUMjA6MjE6NTAuNTE2WiJ9fQ==.ALRx-MRDIZkxgk4L_X61uZ2iwccq5V_qfXESTjNonhTCncGPZnlqAPByQkm9skYkYZON8rM0AIAis1t7SlELAw=="
    }

    private val modelsDir: File
        get() = File(context.filesDir, "models")

    fun isModelDownloaded(): Boolean {
        val modelDir = getModelDirectory()
        if (!modelDir.exists()) return false
        val manifest = File(modelDir, "nexa.manifest")
        return manifest.exists() && manifest.length() > 0
    }

    fun getModelPath(): String {
        return getModelDirectory().absolutePath
    }

    fun getManifestPath(): String {
        return File(getModelDirectory(), "nexa.manifest").absolutePath
    }

    private fun getModelDirectory(): File {
        val modelDir = File(modelsDir, MODEL_DIR_NAME)
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }
        return modelDir
    }

    fun getModelSizeMB(): Long {
        val modelDir = getModelDirectory()
        if (!modelDir.exists()) return 0
        var totalSize = 0L
        modelDir.listFiles()?.forEach { totalSize += it.length() }
        return totalSize / 1024 / 1024
    }

    fun downloadModel(): Flow<DownloadState> = flow {
        emit(DownloadState.Starting)
        val modelDir = getModelDirectory()
        modelDir.mkdirs()

        var totalDownloaded = 0L
        val estimatedTotalBytes = AppConfig.Download.ESTIMATED_MODEL_SIZE_BYTES

        try {
            for ((index, fileName) in MODEL_FILES.withIndex()) {
                val targetFile = File(modelDir, fileName)
                if (targetFile.exists() && targetFile.length() > 0) {
                    totalDownloaded += targetFile.length()
                    continue
                }

                val fileUrl = HF_BASE_URL + fileName
                emit(DownloadState.Progress(
                    progress = (index * 100 / MODEL_FILES.size),
                    downloadedMB = totalDownloaded / 1024 / 1024,
                    totalMB = estimatedTotalBytes / 1024 / 1024,
                    currentFile = fileName
                ))

                downloadFile(fileUrl, targetFile)
                totalDownloaded += targetFile.length()
            }
            emit(DownloadState.Completed(modelDir.absolutePath))
        } catch (e: Exception) {
            emit(DownloadState.Error(e.message ?: "Download failed"))
        }
    }.flowOn(Dispatchers.IO)

    private fun downloadFile(urlString: String, targetFile: File) {
        val tempFile = File(targetFile.parent, "${targetFile.name}.tmp")
        (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = AppConfig.Download.CONNECT_TIMEOUT_MS
            readTimeout = AppConfig.Download.READ_TIMEOUT_MS
            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            disconnect()
        }
        if (targetFile.exists()) targetFile.delete()
        tempFile.renameTo(targetFile)
    }

    sealed class DownloadState {
        object Starting : DownloadState()
        data class Progress(val progress: Int, val downloadedMB: Long, val totalMB: Long, val currentFile: String = "") : DownloadState()
        data class Completed(val modelPath: String) : DownloadState()
        data class Error(val message: String) : DownloadState()
    }
}
