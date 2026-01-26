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
 * Replicates the exact loading strategy from the official Nexa SDK Demo.
 */
class NexaService(private val context: Context) {

    companion object {
        private const val TAG = "NexaService"

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

    /**
     * Initialize Nexa SDK using the license token.
     */
    fun initialize(callback: InitCallback) {
        if (isInitialized) {
            callback.onSuccess()
            return
        }

        val licenseToken = ModelDownloader.LICENSE_TOKEN
        
        NexaSdk.getInstance().init(context, licenseToken, object : NexaSdk.InitCallback {
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
     * Load the VLM model using the manifest path.
     * Replicates the exact ModelConfig structure from the SDK demo.
     */
    fun loadModel(
        manifestPath: String,
        preferNpu: Boolean = true, // Default to true as in the demo
        callback: ModelLoadCallback
    ) {
        if (!isInitialized) {
            callback.onFailure("SDK not initialized.")
            return
        }

        if (isModelLoaded) {
            callback.onSuccess()
            return
        }

        currentPluginId = if (preferNpu) PLUGIN_ID_NPU else PLUGIN_ID_CPU
        val modelFolder = File(manifestPath).parent ?: ""

        modelScope.launch {
            try {
                // Exact config used for NPU/CPU loading in the demo
                val config = ModelConfig(
                    nCtx = 2048,
                    nThreads = 4,
                    enable_thinking = false,
                    npu_lib_folder_path = context.applicationInfo.nativeLibraryDir,
                    npu_model_folder_path = modelFolder
                )

                VlmWrapper.builder()
                    .vlmCreateInput(
                        VlmCreateInput(
                            model_name = MODEL_NAME,
                            model_path = manifestPath,
                            config = config,
                            plugin_id = currentPluginId
                        )
                    )
                    .build()
                    .onSuccess { wrapper ->
                        vlmWrapper = wrapper
                        isModelLoaded = true
                        Log.d(TAG, "Model loaded successfully with: $currentPluginId")
                        callback.onSuccess()
                    }
                    .onFailure { error ->
                        Log.e(TAG, "Model load failed ($currentPluginId): ${error.message}")
                        // Fallback to CPU if NPU fails
                        if (currentPluginId == PLUGIN_ID_NPU) {
                            Log.d(TAG, "NPU failed, falling back to CPU...")
                            loadModelCpuFallback(manifestPath, callback)
                        } else {
                            callback.onFailure(error.message ?: "Load failed")
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during load: ${e.message}")
                if (currentPluginId == PLUGIN_ID_NPU) {
                    loadModelCpuFallback(manifestPath, callback)
                } else {
                    callback.onFailure(e.message ?: "Load error")
                }
            }
        }
    }

    private suspend fun loadModelCpuFallback(
        manifestPath: String,
        callback: ModelLoadCallback
    ) {
        currentPluginId = PLUGIN_ID_CPU
        val modelFolder = File(manifestPath).parent ?: ""

        val config = ModelConfig(
            nCtx = 2048,
            nThreads = 4,
            enable_thinking = false,
            npu_lib_folder_path = context.applicationInfo.nativeLibraryDir,
            npu_model_folder_path = modelFolder
        )

        VlmWrapper.builder()
            .vlmCreateInput(
                VlmCreateInput(
                    model_name = MODEL_NAME,
                    model_path = manifestPath,
                    config = config,
                    plugin_id = PLUGIN_ID_CPU
                )
            )
            .build()
            .onSuccess { wrapper ->
                vlmWrapper = wrapper
                isModelLoaded = true
                callback.onSuccess()
            }
            .onFailure { error ->
                callback.onFailure(error.message ?: "CPU load failed")
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
