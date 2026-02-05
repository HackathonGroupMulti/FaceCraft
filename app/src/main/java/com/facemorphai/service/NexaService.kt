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
import java.lang.ref.WeakReference

/**
 * Service for managing NexaSDK initialization and VLM operations.
 * Supports both NPU-accelerated models and CPU-only models.
 */
class NexaService private constructor(context: Context) {

    private val contextRef = WeakReference(context.applicationContext)
    private val context: Context? get() = contextRef.get()

    companion object {
        private const val TAG = "NexaService"

        // Model configurations
        const val MODEL_OMNI_NEURAL = "omni-neural"
        const val MODEL_GRANITE = "granite"

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

    /**
     * Model type determines loading strategy.
     */
    enum class ModelType {
        /** VLM that can use NPU acceleration (OmniNeural) */
        VLM_NPU,
        /** CPU-only model - completely avoids NPU initialization */
        CPU_ONLY
    }

    private var vlmWrapper: VlmWrapper? = null
    private var isInitialized = false
    private var isModelLoaded = false
    private var currentPluginId: String = PLUGIN_ID_CPU
    private var currentModelType: ModelType = ModelType.CPU_ONLY
    private var currentModelName: String = MODEL_GRANITE

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
     * Load a GGUF model for CPU/GPU (like SmolVLM).
     * This completely avoids NPU initialization - safe for Samsung S24 Ultra and other non-Qualcomm devices.
     *
     * @param modelPath Path to the .gguf model file
     * @param mmprojPath Optional path to the mmproj file for VLMs
     * @param modelName Display name for the model
     */
    fun loadGgufModel(
        modelPath: String,
        mmprojPath: String? = null,
        modelName: String = "SmolVLM",
        callback: ModelLoadCallback
    ) {
        if (!isInitialized) {
            callback.onFailure("SDK not initialized.")
            return
        }

        currentModelType = ModelType.CPU_ONLY
        currentModelName = modelName
        currentPluginId = PLUGIN_ID_CPU

        modelScope.launch {
            // Unload any existing model first
            if (isModelLoaded) {
                Log.d(TAG, "Unloading existing model before loading GGUF model")
                unloadModelSync()
            }

            try {
                Log.d(TAG, "Loading GGUF model: $modelName")
                Log.d(TAG, "Model path: $modelPath")
                Log.d(TAG, "MMProj path: $mmprojPath")

                // CPU/GPU config - NO NPU paths at all
                val nGpuLayers = 0  // CPU only, set > 0 for GPU offload
                val config = ModelConfig(
                    nCtx = 1024,
                    nThreads = 4,
                    nBatch = 1,
                    nUBatch = 1,
                    nGpuLayers = nGpuLayers,
                    enable_thinking = false
                    // Note: NOT setting npu_lib_folder_path or npu_model_folder_path
                )

                val pluginId = "cpu_gpu"
                Log.d(TAG, "Model config: nCtx=1024, nThreads=4, nBatch=1, nUBatch=1, nGpuLayers=$nGpuLayers")
                Log.d(TAG, "Plugin ID: $pluginId (GPU offload: ${if (nGpuLayers > 0) "enabled" else "disabled"})")

                VlmWrapper.builder()
                    .vlmCreateInput(
                        VlmCreateInput(
                            model_name = modelName,
                            model_path = modelPath,
                            mmproj_path = mmprojPath,
                            config = config,
                            plugin_id = pluginId
                        )
                    )
                    .build()
                    .onSuccess { wrapper ->
                        vlmWrapper = wrapper
                        isModelLoaded = true
                        Log.d(TAG, "GGUF model loaded successfully: $modelName (plugin: $pluginId, GPU layers: $nGpuLayers)")
                        callback.onSuccess()
                    }
                    .onFailure { error ->
                        Log.e(TAG, "GGUF model load failed: ${error.message}")
                        callback.onFailure(error.message ?: "Load failed")
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Exception loading GGUF model: ${e.message}", e)
                callback.onFailure(e.message ?: "Load error")
            }
        }
    }

    /**
     * Legacy: Load a CPU-only model using NPU model format but forcing CPU.
     * May still crash on some devices. Prefer loadGgufModel() instead.
     */
    @Deprecated("Use loadGgufModel() for true CPU-only operation")
    fun loadCpuOnlyModel(
        manifestPath: String,
        modelName: String = MODEL_GRANITE,
        callback: ModelLoadCallback
    ) {
        if (!isInitialized) {
            callback.onFailure("SDK not initialized.")
            return
        }

        currentModelType = ModelType.CPU_ONLY
        currentModelName = modelName
        currentPluginId = PLUGIN_ID_CPU

        modelScope.launch {
            if (isModelLoaded) {
                Log.d(TAG, "Unloading existing model before loading CPU-only model")
                unloadModelSync()
            }

            try {
                Log.d(TAG, "Loading CPU-only model (legacy): $modelName")
                Log.d(TAG, "Manifest: $manifestPath")
                Log.d(TAG, "Plugin ID: $PLUGIN_ID_CPU")

                // Attempt CPU without NPU paths
                val config = ModelConfig(
                    nCtx = 2048,
                    nThreads = 4,
                    enable_thinking = false
                    // Note: NOT setting npu_lib_folder_path or npu_model_folder_path
                )

                Log.d(TAG, "CPU-only config: nCtx=2048, nThreads=4, no NPU paths")

                VlmWrapper.builder()
                    .vlmCreateInput(
                        VlmCreateInput(
                            model_name = modelName,
                            model_path = manifestPath,
                            config = config,
                            plugin_id = PLUGIN_ID_CPU
                        )
                    )
                    .build()
                    .onSuccess { wrapper ->
                        vlmWrapper = wrapper
                        isModelLoaded = true
                        Log.d(TAG, "CPU-only model loaded: $modelName")
                        callback.onSuccess()
                    }
                    .onFailure { error ->
                        Log.e(TAG, "CPU-only model load failed: ${error.message}")
                        callback.onFailure(error.message ?: "Load failed")
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Exception loading CPU model: ${e.message}", e)
                callback.onFailure(e.message ?: "Load error")
            }
        }
    }

    /**
     * Load the VLM model with NPU preference (OmniNeural).
     * Falls back to CPU if NPU fails.
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

        currentModelType = ModelType.VLM_NPU
        currentModelName = MODEL_OMNI_NEURAL
        currentPluginId = if (preferNpu) PLUGIN_ID_NPU else PLUGIN_ID_CPU
        val modelFolder = File(manifestPath).parent ?: ""

        modelScope.launch {
            try {
                Log.d(TAG, "Loading model: $MODEL_OMNI_NEURAL")
                Log.d(TAG, "Manifest path: $manifestPath")
                Log.d(TAG, "Model folder: $modelFolder")
                Log.d(TAG, "Prefer NPU: $preferNpu, Plugin ID: $currentPluginId")

                val config = ModelConfig(
                    nCtx = 2048,
                    nThreads = 4,
                    enable_thinking = false,
                    npu_lib_folder_path = ctx.applicationInfo.nativeLibraryDir,
                    npu_model_folder_path = modelFolder
                )

                Log.d(TAG, "Model config: nCtx=2048, nThreads=4, NPU lib: ${ctx.applicationInfo.nativeLibraryDir}")

                VlmWrapper.builder()
                    .vlmCreateInput(
                        VlmCreateInput(
                            model_name = MODEL_OMNI_NEURAL,
                            model_path = manifestPath,
                            config = config,
                            plugin_id = currentPluginId
                        )
                    )
                    .build()
                    .onSuccess { wrapper ->
                        vlmWrapper = wrapper
                        isModelLoaded = true
                        Log.d(TAG, "Model loaded successfully with plugin: $currentPluginId (NPU: ${currentPluginId == PLUGIN_ID_NPU})")
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
        Log.d(TAG, "CPU Fallback: Attempting to load model with CPU plugin")
        currentPluginId = PLUGIN_ID_CPU
        val modelFolder = File(manifestPath).parent ?: ""

        val config = ModelConfig(
            nCtx = 2048,
            nThreads = 4,
            enable_thinking = false,
            npu_lib_folder_path = ctx.applicationInfo.nativeLibraryDir,
            npu_model_folder_path = modelFolder
        )

        Log.d(TAG, "CPU Fallback config: nCtx=2048, nThreads=4, plugin=$PLUGIN_ID_CPU")

        VlmWrapper.builder()
            .vlmCreateInput(
                VlmCreateInput(
                    model_name = MODEL_OMNI_NEURAL,
                    model_path = manifestPath,
                    config = config,
                    plugin_id = PLUGIN_ID_CPU
                )
            )
            .build()
            .onSuccess { wrapper ->
                vlmWrapper = wrapper
                isModelLoaded = true
                Log.d(TAG, "CPU Fallback: Model loaded successfully")
                callback.onSuccess()
            }
            .onFailure { error ->
                Log.e(TAG, "CPU Fallback: Load failed - ${error.message}")
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
        Log.d(TAG, "Model: $currentModelName ($currentModelType)")
        Log.d(TAG, "Prompt length: ${prompt.length} chars")

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

            var tokenCount = 0
            var rawResultCount = 0

            wrapper.generateStreamFlow(prompt, genConfig)
                .collect { result ->
                    rawResultCount++
                    val resultStr = result.toString()

                    Log.d(TAG, "Gen#$currentGeneration Raw[$rawResultCount]: $resultStr")

                    if (resultStr.contains("text=")) {
                        val start = resultStr.indexOf("text=") + 5
                        var end = resultStr.indexOf(")", start)
                        val comma = resultStr.indexOf(",", start)

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

    suspend fun generate(prompt: String, maxTokens: Int = 512): Result<String> {
        val result = generateWithStats(prompt, maxTokens)
        return result.map { it.text }
    }

    suspend fun generateWithStats(prompt: String, maxTokens: Int = 512): Result<GenerationResult> {
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
            rawResults.add(result.toString())
        }

        return if (error != null) {
            Result.failure(Exception(error))
        } else {
            Result.success(GenerationResult(
                text = builder.toString(),
                tokenCount = tokenCount,
                rawResultCount = rawResults.size,
                rawResults = rawResults.takeLast(20)
            ))
        }
    }

    fun stopGeneration() {
        modelScope.launch {
            vlmWrapper?.stopStream()
        }
    }

    private suspend fun unloadModelSync() {
        vlmWrapper?.let {
            it.stopStream()
            it.destroy()
        }
        vlmWrapper = null
        isModelLoaded = false
        Log.d(TAG, "Model unloaded (sync)")
    }

    fun unloadModel() {
        modelScope.launch {
            unloadModelSync()
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
    fun getCurrentModelType(): ModelType = currentModelType
    fun getCurrentModelName(): String = currentModelName

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
