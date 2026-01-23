package com.facemorphai.service

import android.content.Context
import android.util.Log
import ai.nexa.core.LlmWrapper
import ai.nexa.core.NexaSdk
import ai.nexa.core.data.ChatMessage
import ai.nexa.core.data.GenerationConfig
import ai.nexa.core.data.LlmCreateInput
import ai.nexa.core.data.LlmStreamResult
import ai.nexa.core.data.ModelConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Service for managing NexaSDK initialization and LLM operations.
 * Handles model loading, inference, and cleanup.
 */
class NexaService(private val context: Context) {

    companion object {
        private const val TAG = "NexaService"

        // Model configuration
        const val MODEL_NAME = "LFM2.5-1.2B-thinking-npu"
        const val PLUGIN_ID_NPU = "npu"
        const val PLUGIN_ID_CPU = "cpu"
        const val PLUGIN_ID_GPU = "gpu"

        @Volatile
        private var instance: NexaService? = null

        fun getInstance(context: Context): NexaService {
            return instance ?: synchronized(this) {
                instance ?: NexaService(context.applicationContext).also { instance = it }
            }
        }
    }

    private var llmWrapper: LlmWrapper? = null
    private var isInitialized = false
    private var isModelLoaded = false

    // Track current plugin for fallback logic
    private var currentPluginId: String = PLUGIN_ID_NPU

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
     * Load the LLM model for face morph generation.
     * Attempts NPU first, falls back to CPU if NPU unavailable.
     */
    fun loadModel(
        modelPath: String,
        tokenizerPath: String,
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
            nCtx = 2048,  // Context window
            nGpuLayers = 0,
            enable_thinking = true,  // Enable thinking for better reasoning
            npu_lib_folder_path = context.applicationInfo.nativeLibraryDir,
            npu_model_folder_path = modelPath
        )

        LlmWrapper.builder()
            .llmCreateInput(
                LlmCreateInput(
                    model_name = MODEL_NAME,
                    model_path = modelPath,
                    tokenizer_path = tokenizerPath,
                    config = config,
                    plugin_id = currentPluginId
                )
            )
            .build()
            .onSuccess { wrapper ->
                llmWrapper = wrapper
                isModelLoaded = true
                Log.d(TAG, "Model loaded successfully with plugin: $currentPluginId")
                callback.onSuccess()
            }
            .onFailure { error ->
                Log.e(TAG, "Model load failed with $currentPluginId: ${error.message}")

                // Fallback to CPU if NPU failed
                if (currentPluginId == PLUGIN_ID_NPU) {
                    Log.d(TAG, "Attempting CPU fallback...")
                    loadModelWithPlugin(modelPath, tokenizerPath, PLUGIN_ID_CPU, callback)
                } else {
                    callback.onFailure(error.message ?: "Unknown error loading model")
                }
            }
    }

    private fun loadModelWithPlugin(
        modelPath: String,
        tokenizerPath: String,
        pluginId: String,
        callback: ModelLoadCallback
    ) {
        currentPluginId = pluginId

        val config = ModelConfig(
            nCtx = 2048,
            nGpuLayers = if (pluginId == PLUGIN_ID_GPU) 32 else 0,
            enable_thinking = true,
            npu_lib_folder_path = context.applicationInfo.nativeLibraryDir,
            npu_model_folder_path = ""
        )

        LlmWrapper.builder()
            .llmCreateInput(
                LlmCreateInput(
                    model_name = MODEL_NAME,
                    model_path = modelPath,
                    tokenizer_path = tokenizerPath,
                    config = config,
                    plugin_id = pluginId
                )
            )
            .build()
            .onSuccess { wrapper ->
                llmWrapper = wrapper
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
     * Generate a response from the LLM using chat messages.
     * Returns a Flow that emits tokens as they're generated.
     */
    fun generateStream(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 512
    ): Flow<StreamResult> = flow {
        val wrapper = llmWrapper
        if (wrapper == null) {
            emit(StreamResult.Error("Model not loaded"))
            return@flow
        }

        val chatList = arrayListOf(
            ChatMessage("system", systemPrompt),
            ChatMessage("user", userPrompt)
        )

        wrapper.applyChatTemplate(chatList.toTypedArray(), null, false)
            .onSuccess { templateOutput ->
                val config = GenerationConfig(
                    maxTokens = maxTokens,
                    stopWords = arrayOf("```", "\n\n\n"),
                    imagePaths = null,
                    imageCount = 0,
                    audioPaths = null,
                    audioCount = 0
                )

                wrapper.generateStreamFlow(templateOutput.formattedText, config)
                    .collect { result ->
                        when (result) {
                            is LlmStreamResult.Token -> {
                                emit(StreamResult.Token(result.text))
                            }
                            is LlmStreamResult.Completed -> {
                                emit(StreamResult.Completed(
                                    promptTokens = result.profile.promptTokens,
                                    generatedTokens = result.profile.generatedTokens,
                                    ttftMs = result.profile.ttftMs,
                                    decodingSpeed = result.profile.decodingSpeed
                                ))
                            }
                            is LlmStreamResult.Error -> {
                                emit(StreamResult.Error(result.message))
                            }
                        }
                    }
            }
            .onFailure { error ->
                emit(StreamResult.Error(error.message ?: "Chat template failed"))
            }
    }

    /**
     * Generate a complete response (non-streaming).
     * Useful when you need the full JSON output at once.
     */
    suspend fun generate(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 512
    ): Result<String> {
        val builder = StringBuilder()
        var error: String? = null

        generateStream(systemPrompt, userPrompt, maxTokens).collect { result ->
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
        llmWrapper?.stopStream()
    }

    /**
     * Reset the model state (clear conversation history).
     */
    fun reset() {
        llmWrapper?.reset()
    }

    /**
     * Unload the model and free resources.
     */
    fun unloadModel() {
        llmWrapper?.let {
            it.stopStream()
            it.destroy()
        }
        llmWrapper = null
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

    /**
     * Check if the SDK is initialized.
     */
    fun isReady(): Boolean = isInitialized

    /**
     * Check if a model is loaded.
     */
    fun hasModelLoaded(): Boolean = isModelLoaded

    /**
     * Get the current plugin being used (npu, cpu, gpu).
     */
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
