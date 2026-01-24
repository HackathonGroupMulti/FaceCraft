package com.facemorphai.service

import android.content.Context
import android.util.Log
import com.nexa.sdk.NexaSdk
import com.nexa.sdk.VlmWrapper
import com.nexa.sdk.bean.VlmCreateInput
import com.nexa.sdk.bean.ModelConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Service for managing NexaSDK initialization and VLM operations.
 * Handles model loading, inference, and cleanup.
 */
class NexaService(private val context: Context) {

    companion object {
        private const val TAG = "NexaService"

        // Model configuration - using OmniNeural-4B for NPU
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
    private var currentPluginId: String = PLUGIN_ID_NPU

    // Model paths
    private val modelsDir: File
        get() = File(context.filesDir, "models")

    /**
     * Initialize the NexaSDK. Must be called before any other operations.
     */
    fun initialize(callback: InitCallback) {
        if (isInitialized) {
            callback.onSuccess()
            return
        }

        NexaSdk.getInstance().init(context, object : NexaSdk.InitCallback {
            override fun onSuccess() {
                isInitialized = true
                Log.d(TAG, "NexaSDK initialized successfully")
                callback.onSuccess()
            }

            override fun onFailure(reason: String) {
                Log.e(TAG, "NexaSDK initialization failed: $reason")
                callback.onFailure(reason)
            }
        })
    }

    /**
     * Get the path where models should be stored.
     * Example: /data/data/com.facemorphai/files/models/
     */
    fun getModelsDirectory(): File {
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
        return modelsDir
    }

    /**
     * Check if the model files exist locally.
     */
    fun isModelDownloaded(modelName: String): Boolean {
        val modelDir = File(modelsDir, modelName)
        return modelDir.exists() && modelDir.listFiles()?.isNotEmpty() == true
    }

    /**
     * Load the VLM model for face morph generation.
     * @param modelPath Absolute path to the .nexa model file
     * @param preferNpu Whether to prefer NPU acceleration (requires Snapdragon 8 Elite)
     */
    fun loadModel(
        modelPath: String,
        preferNpu: Boolean = true,
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

        val config = ModelConfig(
            max_tokens = 2048,
            enable_thinking = false
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

                // Fallback to CPU if NPU failed
                if (currentPluginId == PLUGIN_ID_NPU) {
                    Log.d(TAG, "Attempting CPU fallback...")
                    loadModelWithPlugin(modelPath, PLUGIN_ID_CPU, callback)
                } else {
                    callback.onFailure(error.message ?: "Unknown error loading model")
                }
            }
    }

    private fun loadModelWithPlugin(
        modelPath: String,
        pluginId: String,
        callback: ModelLoadCallback
    ) {
        currentPluginId = pluginId

        val config = ModelConfig(
            max_tokens = 2048,
            enable_thinking = false
        )

        VlmWrapper.builder()
            .vlmCreateInput(
                VlmCreateInput(
                    model_name = MODEL_NAME,
                    model_path = modelPath,
                    config = config,
                    plugin_id = pluginId
                )
            )
            .build()
            .onSuccess { wrapper ->
                vlmWrapper = wrapper
                isModelLoaded = true
                Log.d(TAG, "Model loaded with fallback plugin: $pluginId")
                callback.onSuccess()
            }
            .onFailure { error ->
                Log.e(TAG, "Model load failed with $pluginId: ${error.message}")
                callback.onFailure(error.message ?: "Unknown error")
            }
    }

    /**
     * Generate a response from the VLM.
     * Returns a Flow that emits tokens as they're generated.
     */
    fun generateStream(
        prompt: String
    ): Flow<StreamResult> = flow {
        val wrapper = vlmWrapper
        if (wrapper == null) {
            emit(StreamResult.Error("Model not loaded"))
            return@flow
        }

        try {
            wrapper.generateStreamFlow(prompt)
                .collect { result ->
                    emit(StreamResult.Token(result.toString()))
                }

            emit(StreamResult.Completed(0, 0, 0, 0f))
        } catch (e: Exception) {
            emit(StreamResult.Error(e.message ?: "Generation failed"))
        }
    }

    /**
     * Generate a complete response (non-streaming).
     */
    suspend fun generate(
        prompt: String,
        maxTokens: Int = 512
    ): Result<String> {
        val builder = StringBuilder()
        var error: String? = null

        generateStream(prompt).collect { result ->
            when (result) {
                is StreamResult.Token -> builder.append(result.text)
                is StreamResult.Completed -> { /* Done */ }
                is StreamResult.Error -> error = result.message
            }
        }

        return if (error != null) {
            Result.failure(Exception(error))
        } else {
            Result.success(builder.toString())
        }
    }

    /**
     * Stop any ongoing generation.
     */
    fun stopGeneration() {
        vlmWrapper?.stopStream()
    }

    /**
     * Unload the model and free resources.
     */
    fun unloadModel() {
        vlmWrapper?.let {
            it.stopStream()
            it.destroy()
        }
        vlmWrapper = null
        isModelLoaded = false
        Log.d(TAG, "Model unloaded")
    }

    /**
     * Clean up all resources.
     */
    fun destroy() {
        unloadModel()
        isInitialized = false
        instance = null
    }

    fun isReady(): Boolean = isInitialized
    fun hasModelLoaded(): Boolean = isModelLoaded
    fun getCurrentPlugin(): String = currentPluginId

    // Callback interfaces
    interface InitCallback {
        fun onSuccess()
        fun onFailure(reason: String)
    }

    interface ModelLoadCallback {
        fun onSuccess()
        fun onFailure(reason: String)
    }

    // Stream result sealed class
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
