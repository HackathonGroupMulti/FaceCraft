package com.facemorphai.service

import android.content.Context
import android.util.Log
import com.facemorphai.config.AppConfig
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
import java.lang.ref.WeakReference

/**
 * Service for managing NexaSDK initialization and VLM operations.
 * Replicates the exact loading strategy from the official Nexa SDK Demo.
 */
class NexaService private constructor(context: Context) {

    private val contextRef = WeakReference(context.applicationContext)
    private val context: Context? get() = contextRef.get()

    companion object {
        private const val TAG = "NexaService"

        const val MODEL_NAME = "omni-neural"
        const val PLUGIN_ID_NPU = "npu"
        const val PLUGIN_ID_CPU = "cpu"

        @Volatile
        private var instance: NexaService? = null

        fun getInstance(context: Context): NexaService {
            return instance ?: synchronized(this) {
                instance ?: NexaService(context).also { instance = it }
            }
        }
    }

    private var vlmWrapper: VlmWrapper? = null
    private var isInitialized = false
    private var isModelLoaded = false
    private var currentPluginId: String = PLUGIN_ID_CPU

    private val modelScope = CoroutineScope(Dispatchers.IO)

    /**
     * Initialize Nexa SDK.
     */
    fun initialize(callback: InitCallback) {
        val ctx = context ?: return
        if (isInitialized) {
            callback.onSuccess()
            return
        }

        NexaSdk.getInstance().init(ctx, object : NexaSdk.InitCallback {
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
     */
    fun loadModel(
        manifestPath: String,
        preferNpu: Boolean = true,
        callback: ModelLoadCallback
    ) {
        val ctx = context ?: return
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
                val config = ModelConfig(
                    nCtx = AppConfig.Model.CONTEXT_SIZE,
                    nThreads = AppConfig.Model.NUM_THREADS,
                    enable_thinking = false,
                    npu_lib_folder_path = ctx.applicationInfo.nativeLibraryDir,
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
        val ctx = context ?: return
        currentPluginId = PLUGIN_ID_CPU
        val modelFolder = File(manifestPath).parent ?: ""

        val config = ModelConfig(
            nCtx = AppConfig.Model.CONTEXT_SIZE,
            nThreads = AppConfig.Model.NUM_THREADS,
            enable_thinking = false,
            npu_lib_folder_path = ctx.applicationInfo.nativeLibraryDir,
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

    // Track generation count for debugging
    private var generationCount = 0

    /**
     * Correctly extract text from the Nexa SDK response.
     * The SDK returns objects like Token(text="...") and Completed(profile=...).
     */
    fun generateStream(prompt: String): Flow<StreamResult> = flow {
        val wrapper = vlmWrapper
        if (wrapper == null) {
            emit(StreamResult.Error("Model not loaded"))
            return@flow
        }

        generationCount++
        val currentGeneration = generationCount
        Log.d(TAG, "=== GENERATION #$currentGeneration START ===")
        Log.d(TAG, "Prompt length: ${prompt.length} chars")

        try {
            val genConfig = GenerationConfig(
                maxTokens = AppConfig.Generation.STREAM_MAX_TOKENS,
                stopWords = null,
                stopCount = 0,
                nPast = 0,  // Should reset context, but may not work as expected
                imagePaths = null,
                imageCount = 0,
                audioPaths = null,
                audioCount = 0
            )

            var tokenCount = 0
            var rawResultCount = 0

            wrapper.generateStreamFlow(prompt, genConfig)
                .collect { result ->
                    rawResultCount++
                    // The SDK result is an object. We extract the text property from its string representation.
                    val resultStr = result.toString()

                    // Log every raw result for debugging
                    Log.d(TAG, "Gen#$currentGeneration Raw[$rawResultCount]: $resultStr")

                    if (resultStr.contains("text=")) {
                        // Surgically extract text content: finds "text=" and grabs until the closing part
                        val start = resultStr.indexOf("text=") + 5
                        var end = resultStr.indexOf(")", start)
                        val comma = resultStr.indexOf(",", start)

                        // Handle cases where comma comes before closing parenthesis
                        if (comma in (start + 1)..<end) {
                            end = comma
                        }

                        if (start > 4 && end > start) {
                            val text = resultStr.substring(start, end)
                            tokenCount++
                            Log.d(TAG, "Gen#$currentGeneration Token[$tokenCount]: '$text'")
                            emit(StreamResult.Token(text))
                        } else {
                            Log.w(TAG, "Gen#$currentGeneration: Failed to extract text from: $resultStr")
                        }
                    } else if (resultStr.startsWith("Completed")) {
                        Log.d(TAG, "Gen#$currentGeneration: Completed after $tokenCount tokens")
                        emit(StreamResult.Completed(0, 0, 0, 0f))
                    } else {
                        Log.w(TAG, "Gen#$currentGeneration: Unknown result type: $resultStr")
                    }
                }

            Log.d(TAG, "=== GENERATION #$currentGeneration END: $tokenCount tokens from $rawResultCount raw results ===")
        } catch (e: Exception) {
            Log.e(TAG, "Gen#$currentGeneration Exception: ${e.message}", e)
            emit(StreamResult.Error(e.message ?: "Generation failed"))
        }
    }

    /**
     * Result of a generation including debug stats.
     */
    data class GenerationResult(
        val text: String,
        val tokenCount: Int,
        val rawResultCount: Int,
        val rawResults: List<String>
    )

    suspend fun generate(prompt: String, maxTokens: Int = AppConfig.Generation.STREAM_MAX_TOKENS): Result<String> {
        val result = generateWithStats(prompt, maxTokens)
        return result.map { it.text }
    }

    suspend fun generateWithStats(prompt: String, maxTokens: Int = AppConfig.Generation.STREAM_MAX_TOKENS): Result<GenerationResult> {
        val builder = StringBuilder()
        var error: String? = null
        var tokenCount = 0
        val rawResults = mutableListOf<String>()

        generateStream(prompt).collect { result ->
            when (result) {
                is StreamResult.Token -> {
                    builder.append(result.text)
                    tokenCount++
                }
                is StreamResult.Completed -> { }
                is StreamResult.Error -> error = result.message
            }
            // Capture raw result for debugging
            rawResults.add(result.toString())
        }

        return if (error != null) {
            Result.failure(Exception(error))
        } else {
            Result.success(GenerationResult(
                text = builder.toString(),
                tokenCount = tokenCount,
                rawResultCount = rawResults.size,
                rawResults = rawResults.takeLast(AppConfig.Generation.DEBUG_RAW_RESULTS_LIMIT)
            ))
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
