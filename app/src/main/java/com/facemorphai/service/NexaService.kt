package com.facemorphai.service

import android.content.Context
import android.util.Log
import ai.nexa.core.NexaSdk
import ai.nexa.core.LlmWrapper
import ai.nexa.core.data.ChatMessage
import ai.nexa.core.data.GenerationConfig
import ai.nexa.core.data.LlmCreateInput
import ai.nexa.core.data.LlmStreamResult
import ai.nexa.core.data.ModelConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Service for managing NexaSDK initialization and LLM operations.
 * Handles model loading, inference, and cleanup.
 */
class NexaService(private val context: Context) {

    companion object {
        private const val TAG = "NexaService"

        // Model configuration - using the thinking model for better reasoning
        const val MODEL_NAME = "LFM2.5-1.2B-thinking-npu"
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

    private var llmWrapper: LlmWrapper? = null
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

        try {
            NexaSdk.getInstance().init(context)
            isInitialized = true
            Log.d(TAG, "NexaSDK initialized successfully")
            callback.onSuccess()
        } catch (e: Exception) {
            Log.e(TAG, "NexaSDK initialization failed: ${e.message}")
            callback.onFailure(e.message ?: "Unknown error")
        }
    }

    /**
     * Get the path where models should be stored.
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
     * Load the LLM model for face morph generation.
     * @param modelPath Path to the model folder containing .nexa files
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
            enable_thinking = true  // Enable thinking for better reasoning
        )

        try {
            LlmWrapper.builder()
                .llmCreateInput(
                    LlmCreateInput(
                        model_name = MODEL_NAME,
                        model_path = modelPath,
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
                        loadModelWithPlugin(modelPath, PLUGIN_ID_CPU, callback)
                    } else {
                        callback.onFailure(error.message ?: "Unknown error loading model")
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading model: ${e.message}")
            callback.onFailure(e.message ?: "Exception loading model")
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
            enable_thinking = true
        )

        try {
            LlmWrapper.builder()
                .llmCreateInput(
                    LlmCreateInput(
                        model_name = MODEL_NAME,
                        model_path = modelPath,
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
        } catch (e: Exception) {
            callback.onFailure(e.message ?: "Exception loading model")
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

        val chatList = arrayOf(
            ChatMessage("system", systemPrompt),
            ChatMessage("user", userPrompt)
        )

        try {
            wrapper.applyChatTemplate(chatList, null, false)
                .onSuccess { templateOutput ->
                    val config = GenerationConfig(
                        maxTokens = maxTokens,
                        stopWords = arrayOf("```", "\n\n\n")
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
        } catch (e: Exception) {
            emit(StreamResult.Error(e.message ?: "Generation failed"))
        }
    }

    /**
     * Generate a complete response (non-streaming).
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
     * Reset the model state.
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
