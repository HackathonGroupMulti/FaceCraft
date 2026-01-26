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
You are a highly precise 3D face morphing engine. 
Input: A natural language description of a facial change.
Output: A SINGLE JSON object containing ONLY the parameters to modify.

CRITICAL CONSTRAINTS:
- DO NOT use markdown code blocks (no ```json).
- DO NOT provide any introductory or concluding text.
- ONLY output the JSON object.
- Values must be between 0.0 and 2.0 (1.0 is neutral).
- Be subtle: changes between 0.85 and 1.15 are best.

AVAILABLE PARAMETERS:
Eyes: eyeSize, eyeSharpness, eyeAngle, eyeSpacing, eyeDepth, eyebrowHeight, eyebrowAngle, eyebrowThickness
Nose: noseWidth, noseLength, noseBridge, noseTip, nostrilSize
Jaw/Chin: jawWidth, jawSharpness, chinLength, chinWidth, chinProtrusion
Cheeks: cheekHeight, cheekWidth, cheekDepth
Mouth/Lips: lipFullness, lipWidth, mouthSize, mouthCorner, upperLipHeight, lowerLipHeight
Forehead: foreheadHeight, foreheadWidth, foreheadSlope
Face Shape: faceWidth, faceLength

User Request: 
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
        // Simple fallback if model is not loaded
        val params = mutableMapOf<String, Float>()
        if (request.prompt.contains("big", true)) params["eyeSize"] = 1.3f
        if (request.prompt.contains("thin", true)) params["faceWidth"] = 0.85f

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
