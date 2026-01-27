package com.facemorphai.service

import android.content.Context
import android.util.Log
import com.facemorphai.model.FaceRegion
import com.facemorphai.model.MorphParameters
import com.facemorphai.model.MorphRequest
import com.facemorphai.model.MorphResult
import com.facemorphai.parser.MorphParameterParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Service that handles face morph generation using the VLM.
 * Takes natural language descriptions and converts them to morph parameters.
 */
class FaceMorphService(private val context: Context) {

    companion object {
        private const val TAG = "FaceMorphService"

        /**
         * System prompt that instructs the LLM how to generate morph parameters.
         * Strength increased to ensure JSON-only output.
         */
        private val SYSTEM_PROMPT = """
Output ONLY a JSON object. No markdown, no text, no explanation.
Values: 0.0-2.0 (1.0=neutral, >1.0=bigger, <1.0=smaller).

Parameters: eyeSize, eyeSpacing, eyeDepth, eyebrowHeight, noseWidth, noseLength, noseTip, jawWidth, jawSharpness, chinLength, chinWidth, chinProtrusion, cheekHeight, cheekWidth, lipFullness, lipWidth, mouthSize, foreheadHeight, faceWidth, faceLength

Example input: "make eyes bigger and nose thinner"
Example output: {"eyeSize":1.3,"noseWidth":0.8}

""".trimIndent()

        /**
         * Regional prompts for when user selects a specific face region.
         */
        private fun getRegionalPrompt(region: FaceRegion): String {
            return when (region) {
                FaceRegion.EYES -> "Focus ONLY on eye and eyebrow parameters."
                FaceRegion.NOSE -> "Focus ONLY on nose parameters."
                FaceRegion.JAW_CHIN -> "Focus ONLY on jaw and chin parameters."
                FaceRegion.CHEEKS -> "Focus ONLY on cheek parameters."
                FaceRegion.MOUTH_LIPS -> "Focus ONLY on mouth and lip parameters."
                FaceRegion.FOREHEAD -> "Focus ONLY on forehead parameters."
                FaceRegion.FACE_SHAPE -> "Focus ONLY on overall face shape, width, and length."
                FaceRegion.ALL -> "You may modify any parameters to achieve the look."
            }
        }
    }

    private val nexaService = NexaService.getInstance(context)
    private val parser = MorphParameterParser()

    private var currentParameters = MorphParameters.DEFAULT
    private var useMockMode = false

    fun setMockMode(enabled: Boolean) {
        useMockMode = enabled
    }

    suspend fun generateMorph(request: MorphRequest): MorphResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        if (useMockMode || !nexaService.hasModelLoaded()) {
            return@withContext generateMockMorph(request, startTime)
        }

        val regionalInstruction = getRegionalPrompt(request.region)
        val fullPrompt = "$SYSTEM_PROMPT\nRequest: ${request.prompt}\nRegion Focus: $regionalInstruction\nOutput JSON:"

        Log.d(TAG, "Sending prompt to VLM: $fullPrompt")

        try {
            val result = nexaService.generate(prompt = fullPrompt, maxTokens = 128)

            result.fold(
                onSuccess = { jsonOutput ->
                    val parseResult = parser.parse(jsonOutput)

                    parseResult.fold(
                        onSuccess = { newParams ->
                            val scaledParams = applyIntensity(newParams, request.intensity)
                            currentParameters = currentParameters.mergeWith(scaledParams)
                            MorphResult(
                                parameters = currentParameters,
                                generationTimeMs = System.currentTimeMillis() - startTime,
                                tokensGenerated = jsonOutput.length / 4,
                                success = true
                            )
                        },
                        onFailure = { parseError ->
                            Log.e(TAG, "Parse error: ${parseError.message}")
                            MorphResult(
                                parameters = currentParameters,
                                generationTimeMs = System.currentTimeMillis() - startTime,
                                tokensGenerated = 0,
                                success = false,
                                errorMessage = "VLM output was not valid JSON: ${parseError.message}"
                            )
                        }
                    )
                },
                onFailure = { error ->
                    Log.e(TAG, "Generation error: ${error.message}")
                    MorphResult(
                        parameters = currentParameters,
                        generationTimeMs = System.currentTimeMillis() - startTime,
                        tokensGenerated = 0,
                        success = false,
                        errorMessage = "VLM failed: ${error.message}"
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error", e)
            MorphResult(
                parameters = currentParameters,
                generationTimeMs = System.currentTimeMillis() - startTime,
                tokensGenerated = 0,
                success = false,
                errorMessage = e.message ?: "Unknown error"
            )
        }
    }

    private fun applyIntensity(params: MorphParameters, intensity: Float): MorphParameters {
        if (intensity == 1.0f) return params
        val scaled = params.toMap().mapValues { (_, value) ->
            val deviation = value - 1.0f
            (1.0f + deviation * intensity).coerceIn(0.0f, 2.0f)
        }
        return parser.fromMap(scaled)
    }

    fun getCurrentParameters(): MorphParameters = currentParameters
    fun resetParameters() { currentParameters = MorphParameters.DEFAULT }

    private fun generateMockMorph(request: MorphRequest, startTime: Long): MorphResult {
        val prompt = request.prompt.lowercase()
        val params = mutableMapOf<String, Float>()

        // Keyword-based parameter mapping
        if (prompt.contains("big") || prompt.contains("larger") || prompt.contains("wider")) {
            when (request.region) {
                FaceRegion.EYES -> params["eyeSize"] = 1.35f
                FaceRegion.NOSE -> params["noseWidth"] = 1.3f
                FaceRegion.MOUTH_LIPS -> { params["lipFullness"] = 1.3f; params["mouthSize"] = 1.2f }
                FaceRegion.JAW_CHIN -> { params["jawWidth"] = 1.3f; params["chinWidth"] = 1.2f }
                FaceRegion.CHEEKS -> params["cheekWidth"] = 1.3f
                FaceRegion.FOREHEAD -> params["foreheadHeight"] = 1.3f
                else -> params["faceWidth"] = 1.2f
            }
        }
        if (prompt.contains("small") || prompt.contains("thin") || prompt.contains("narrow")) {
            when (request.region) {
                FaceRegion.EYES -> params["eyeSize"] = 0.75f
                FaceRegion.NOSE -> params["noseWidth"] = 0.75f
                FaceRegion.MOUTH_LIPS -> params["lipFullness"] = 0.75f
                FaceRegion.JAW_CHIN -> { params["jawWidth"] = 0.75f; params["jawSharpness"] = 1.3f }
                else -> params["faceWidth"] = 0.85f
            }
        }
        if (prompt.contains("long") || prompt.contains("longer")) {
            when (request.region) {
                FaceRegion.NOSE -> params["noseLength"] = 1.35f
                FaceRegion.JAW_CHIN -> { params["chinLength"] = 1.35f; params["chinProtrusion"] = 1.2f }
                FaceRegion.FOREHEAD -> params["foreheadHeight"] = 1.35f
                else -> params["faceLength"] = 1.25f
            }
        }
        if (prompt.contains("short") || prompt.contains("shorter")) {
            when (request.region) {
                FaceRegion.NOSE -> params["noseLength"] = 0.7f
                FaceRegion.JAW_CHIN -> params["chinLength"] = 0.7f
                FaceRegion.FOREHEAD -> params["foreheadHeight"] = 0.7f
                else -> params["faceLength"] = 0.8f
            }
        }
        if (prompt.contains("sharp") || prompt.contains("angular")) {
            params["jawSharpness"] = 1.4f; params["cheekHeight"] = 1.2f
        }
        if (prompt.contains("round") || prompt.contains("soft")) {
            params["jawSharpness"] = 0.7f; params["cheekWidth"] = 1.2f
        }
        if (prompt.contains("full") || prompt.contains("plump")) {
            params["lipFullness"] = 1.4f; params["cheekWidth"] = 1.15f
        }

        // If no keywords matched, apply a default change for the selected region
        if (params.isEmpty()) {
            Log.d(TAG, "Mock: no keywords matched, applying default for region ${request.region}")
            when (request.region) {
                FaceRegion.EYES -> params["eyeSize"] = 1.15f
                FaceRegion.NOSE -> params["noseWidth"] = 1.1f
                FaceRegion.JAW_CHIN -> params["jawWidth"] = 1.1f
                FaceRegion.CHEEKS -> params["cheekWidth"] = 1.1f
                FaceRegion.MOUTH_LIPS -> params["lipFullness"] = 1.15f
                FaceRegion.FOREHEAD -> params["foreheadHeight"] = 1.1f
                FaceRegion.FACE_SHAPE -> params["faceWidth"] = 1.1f
                FaceRegion.ALL -> params["faceWidth"] = 1.1f
            }
        }

        val newParams = parser.fromMap(params)
        currentParameters = currentParameters.mergeWith(newParams)

        return MorphResult(
            parameters = currentParameters,
            generationTimeMs = System.currentTimeMillis() - startTime,
            tokensGenerated = 0,
            success = true
        )
    }
}
