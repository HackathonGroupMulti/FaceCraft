package com.facemorphai.service

import android.content.Context
import android.util.Log
import com.nexa.sdk.NexaSdk
import com.nexa.sdk.VlmWrapper
import com.nexa.sdk.bean.VlmCreateInput
import com.nexa.sdk.bean.ModelConfig
import com.nexa.sdk.bean.GenerationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Service for managing NexaSDK initialization and VLM operations.
 * Handles model loading, inference, and cleanup.
 */
class NexaService(private val context: Context) {

    companion object {
        private const val TAG = "NexaService"

        // Model configuration
        const val MODEL_NAME = "omni-neural"
        const val PLUGIN_ID_NPU = "npu"
        const val PLUGIN_ID_CPU = "cpu"

        @Volatile
        private var instance: NexaService? = null

        fun getInstance(context: Context): NexaService {
            return instance ?: synchronized(this) {
                instance ?: NexaService(context.applicationContext).also { instance = it }
            }
        }
    }

    private var vlmWrapper: VlmWrapper? = null
    private var isInitialized = false
    private var isModelLoaded = false
    private var currentPluginId: String = PLUGIN_ID_CPU

    private val modelScope = CoroutineScope(Dispatchers.IO)

    private val modelsDir: File
        get() = File(context.filesDir, "models")

    /**
     * Initialize Nexa SDK using the license token from ModelDownloader.
     */
    fun initialize(callback: InitCallback) {
        if (isInitialized) {
            callback.onSuccess()
            return
        }

        val licenseToken = ModelDownloader.LICENSE_TOKEN
        
        // Ensure we pass the license token if the SDK supports it
        NexaSdk.getInstance().init(context, licenseToken, object : NexaSdk.InitCallback {
            override fun onSuccess() {
                isInitialized = true
                Log.d(TAG, "NexaSDK initialized successfully with license token")
                callback.onSuccess()
            }

            override fun onFailure(reason: String) {
                Log.e(TAG, "NexaSDK initialization failed: $reason")
                callback.onFailure(reason)
            }
        })
    }

    fun getModelsDirectory(): File {
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
        return modelsDir
    }

    fun isModelDownloaded(modelName: String): Boolean {
        val modelDir = File(modelsDir, modelName)
        return modelDir.exists() && modelDir.listFiles()?.isNotEmpty() == true
    }

    /**
     * Load the VLM model. 
     * NOTE: preferNpu defaults to false to avoid SIGABRT on devices with locked DSPs.
     */
    fun loadModel(
        modelPath: String,
        preferNpu: Boolean = false, 
        callback: ModelLoadCallback
    ) {
        if (!isInitialized) {
            callback.onFailure("SDK not initialized. Call initialize() first.")
            return
        }

        if (isModelLoaded) {
            callback.onSuccess()
            return
        }

        currentPluginId = if (preferNpu) PLUGIN_ID_NPU else PLUGIN_ID_CPU

        modelScope.launch {
            try {
                val config = ModelConfig(
                    nCtx = 2048,
                    nThreads = 4,
                    enable_thinking = false,
                    npu_lib_folder_path = context.applicationInfo.nativeLibraryDir,
                    npu_model_folder_path = File(modelPath).parent ?: ""
                )

                VlmWrapper.builder()
                    .vlmCreateInput(
                        VlmCreateInput(
                            model_name = MODEL_NAME,
                            model_path = modelPath,
                            config = config,
                            plugin_id = currentPluginId
                        )
                    )
                    .build()
                    .onSuccess { wrapper ->
                        vlmWrapper = wrapper
                        isModelLoaded = true
                        Log.d(TAG, "Model loaded successfully with plugin: $currentPluginId")
                        callback.onSuccess()
                    }
                    .onFailure { error ->
                        Log.e(TAG, "Model load failed with $currentPluginId: ${error.message}")
                        if (currentPluginId == PLUGIN_ID_NPU) {
                            Log.d(TAG, "Attempting CPU fallback...")
                            loadModelCpuFallback(modelPath, callback)
                        } else {
                            callback.onFailure(error.message ?: "Unknown error loading model")
                        }
                    }
            } catch (e: Exception) {
                // This might not catch native SIGABRT, but handles JVM side errors
                Log.e(TAG, "Exception during model load: ${e.message}")
                if (currentPluginId == PLUGIN_ID_NPU) {
                    loadModelCpuFallback(modelPath, callback)
                } else {
                    callback.onFailure(e.message ?: "Exception during load")
                }
            }
        }
    }

    private suspend fun loadModelCpuFallback(
        modelPath: String,
        callback: ModelLoadCallback
    ) {
        currentPluginId = PLUGIN_ID_CPU

        val config = ModelConfig(
            nCtx = 2048,
            nThreads = 4,
            enable_thinking = false
        )

        VlmWrapper.builder()
            .vlmCreateInput(
                VlmCreateInput(
                    model_name = MODEL_NAME,
                    model_path = modelPath,
                    config = config,
                    plugin_id = PLUGIN_ID_CPU
                )
            )
            .build()
            .onSuccess { wrapper ->
                vlmWrapper = wrapper
                isModelLoaded = true
                Log.d(TAG, "Model loaded with CPU fallback")
                callback.onSuccess()
            }
            .onFailure { error ->
                Log.e(TAG, "Model load failed with CPU: ${error.message}")
                callback.onFailure(error.message ?: "Unknown error")
            }
    }

    fun generateStream(prompt: String): Flow<StreamResult> = flow {
        val wrapper = vlmWrapper
        if (wrapper == null) {
            emit(StreamResult.Error("Model not loaded"))
            return@flow
        }

        try {
            val genConfig = GenerationConfig(
                maxTokens = 512,
                stopWords = null,
                stopCount = 0,
                nPast = 0,
                imagePaths = null,
                imageCount = 0,
                audioPaths = null,
                audioCount = 0
            )
            wrapper.generateStreamFlow(prompt, genConfig)
                .collect { result ->
                    emit(StreamResult.Token(result.toString()))
                }
            emit(StreamResult.Completed(0, 0, 0, 0f))
        } catch (e: Exception) {
            emit(StreamResult.Error(e.message ?: "Generation failed"))
        }
    }

    suspend fun generate(prompt: String, maxTokens: Int = 512): Result<String> {
        val builder = StringBuilder()
        var error: String? = null

        generateStream(prompt).collect { result ->
            when (result) {
                is StreamResult.Token -> builder.append(result.text)
                is StreamResult.Completed -> { }
                is StreamResult.Error -> error = result.message
            }
        }

        return if (error != null) {
            Result.failure(Exception(error))
        } else {
            Result.success(builder.toString())
        }
    }

    fun stopGeneration() {
        modelScope.launch {
            vlmWrapper?.stopStream()
        }
    }

    fun unloadModel() {
        modelScope.launch {
            vlmWrapper?.let {
                it.stopStream()
                it.destroy()
            }
            vlmWrapper = null
            isModelLoaded = false
            Log.d(TAG, "Model unloaded")
        }
    }

    fun destroy() {
        unloadModel()
        isInitialized = false
        instance = null
    }

    fun isReady(): Boolean = isInitialized
    fun hasModelLoaded(): Boolean = isModelLoaded
    fun getCurrentPlugin(): String = currentPluginId

    interface InitCallback {
        fun onSuccess()
        fun onFailure(reason: String)
    }

    interface ModelLoadCallback {
        fun onSuccess()
        fun onFailure(reason: String)
    }

    sealed class StreamResult {
        data class Token(val text: String) : StreamResult()
        data class Completed(
            val promptTokens: Int,
            val generatedTokens: Int,
            val ttftMs: Long,
            val decodingSpeed: Float
        ) : StreamResult()
        data class Error(val message: String) : StreamResult()
    }
}
