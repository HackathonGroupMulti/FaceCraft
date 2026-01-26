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
         */
        private val SYSTEM_PROMPT = """
You are a 3D face morph parameter generator for a face modeling application.
Your job is to convert natural language descriptions into JSON parameters.

CRITICAL RULES:
1. Output ONLY valid JSON - no explanations, no markdown, no code blocks
2. Use ONLY the parameters listed below
3. All values must be between 0.0 and 2.0 (1.0 is default/neutral)
4. Only include parameters that need to change from default
5. Be subtle - small changes (0.8-1.2) look more natural than extreme ones

AVAILABLE PARAMETERS:

Eyes:
- eyeSize: larger (>1) or smaller (<1) eyes
- eyeSharpness: sharper/more defined (<1) or softer/rounder (>1) eye shape
- eyeAngle: upward tilt (>1) or downward tilt (<1) at outer corners
- eyeSpacing: further apart (>1) or closer together (<1)
- eyeDepth: more deep-set (<1) or protruding (>1)
- eyebrowHeight: higher (>1) or lower (<1) eyebrows
- eyebrowAngle: more arched (>1) or flatter (<1)
- eyebrowThickness: thicker (>1) or thinner (<1)

Nose:
- noseWidth: wider (>1) or narrower (<1)
- noseLength: longer (>1) or shorter (<1)
- noseBridge: higher/more prominent (>1) or flatter (<1)
- noseTip: more upturned (>1) or downturned (<1)
- nostrilSize: larger (>1) or smaller (<1) nostrils

Jaw & Chin:
- jawWidth: wider (>1) or narrower (<1)
- jawSharpness: more angular/defined (>1) or softer/rounder (<1)
- chinLength: longer (>1) or shorter (<1)
- chinWidth: wider (>1) or narrower (<1)
- chinProtrusion: more prominent (>1) or recessed (<1)

Cheeks:
- cheekHeight: higher cheekbones (>1) or lower (<1)
- cheekWidth: fuller (>1) or narrower (<1) cheeks
- cheekDepth: more hollow (<1) or fuller (>1)

Mouth & Lips:
- lipFullness: fuller (>1) or thinner (<1) lips
- lipWidth: wider (>1) or narrower (<1) mouth
- mouthSize: larger (>1) or smaller (<1) overall
- mouthCorner: upturned/smiling (>1) or downturned (<1)
- upperLipHeight: taller (>1) or shorter (<1) upper lip
- lowerLipHeight: taller (>1) or shorter (<1) lower lip

Forehead:
- foreheadHeight: taller (>1) or shorter (<1)
- foreheadWidth: wider (>1) or narrower (<1)
- foreheadSlope: more sloped back (>1) or more vertical (<1)

Face Shape:
- faceWidth: wider (>1) or narrower (<1) overall face
- faceLength: longer (>1) or shorter (<1) overall face

EXAMPLE INPUT: "Make the eyes look more mysterious and intense"
EXAMPLE OUTPUT: {"eyeSize":0.95,"eyeSharpness":0.85,"eyeAngle":1.1,"eyebrowHeight":0.9,"eyebrowAngle":1.15}

EXAMPLE INPUT: "Give a strong, masculine jawline"
EXAMPLE OUTPUT: {"jawWidth":1.15,"jawSharpness":1.3,"chinWidth":1.1,"chinProtrusion":1.1}

EXAMPLE INPUT: "Softer, more feminine features"
EXAMPLE OUTPUT: {"jawSharpness":0.8,"cheekHeight":1.15,"lipFullness":1.2,"eyeSize":1.1,"eyebrowAngle":1.1}

Now generate parameters for the following request:
""".trimIndent()

        /**
         * Regional prompts for when user selects a specific face region.
         */
        private fun getRegionalPrompt(region: FaceRegion): String {
            return when (region) {
                FaceRegion.EYES -> """
Focus ONLY on eye-related parameters: eyeSize, eyeSharpness, eyeAngle, eyeSpacing, eyeDepth, eyebrowHeight, eyebrowAngle, eyebrowThickness.
Do not modify any other facial features.
""".trimIndent()

                FaceRegion.NOSE -> """
Focus ONLY on nose-related parameters: noseWidth, noseLength, noseBridge, noseTip, nostrilSize.
Do not modify any other facial features.
""".trimIndent()

                FaceRegion.JAW_CHIN -> """
Focus ONLY on jaw and chin parameters: jawWidth, jawSharpness, chinLength, chinWidth, chinProtrusion.
Do not modify any other facial features.
""".trimIndent()

                FaceRegion.CHEEKS -> """
Focus ONLY on cheek parameters: cheekHeight, cheekWidth, cheekDepth.
Do not modify any other facial features.
""".trimIndent()

                FaceRegion.MOUTH_LIPS -> """
Focus ONLY on mouth and lip parameters: lipFullness, lipWidth, mouthSize, mouthCorner, upperLipHeight, lowerLipHeight.
Do not modify any other facial features.
""".trimIndent()

                FaceRegion.FOREHEAD -> """
Focus ONLY on forehead parameters: foreheadHeight, foreheadWidth, foreheadSlope.
Do not modify any other facial features.
""".trimIndent()

                FaceRegion.FACE_SHAPE -> """
Focus ONLY on overall face shape parameters: faceWidth, faceLength, jawWidth, cheekWidth.
Do not modify specific features like eyes, nose, or lips.
""".trimIndent()

                FaceRegion.ALL -> """
You may modify any parameters as needed to achieve the described look.
""".trimIndent()
            }
        }
    }

    private val nexaService = NexaService.getInstance(context)
    private val parser = MorphParameterParser()

    // Current state
    private var currentParameters = MorphParameters.DEFAULT

    // Mock mode for demos when AI model is not available
    private var useMockMode = false

    /**
     * Enable or disable mock mode (for demos without the AI model).
     */
    fun setMockMode(enabled: Boolean) {
        useMockMode = enabled
    }

    /**
     * Generate morph parameters from a natural language request.
     */
    suspend fun generateMorph(request: MorphRequest): MorphResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        // Use mock mode if enabled or if model not loaded
        if (useMockMode || !nexaService.hasModelLoaded()) {
            Log.d(TAG, "Using mock mode for: ${request.prompt}")
            return@withContext generateMockMorph(request, startTime)
        }

        // Build the user prompt with regional focus
        val userPrompt = buildUserPrompt(request)

        Log.d(TAG, "Generating morph for: ${request.prompt}")
        Log.d(TAG, "Region: ${request.region.displayName}")

        try {
            // Generate response from VLM
            // Combine system and user prompt for VLM
            val fullPrompt = "$SYSTEM_PROMPT\n\n$userPrompt"
            val result = nexaService.generate(
                prompt = fullPrompt,
                maxTokens = 256  // JSON output should be small
            )

            result.fold(
                onSuccess = { jsonOutput ->
                    Log.d(TAG, "VLM output: $jsonOutput")

                    // Parse the JSON output
                    val parseResult = parser.parse(jsonOutput)

                    parseResult.fold(
                        onSuccess = { newParams ->
                            // Apply intensity scaling
                            val scaledParams = applyIntensity(newParams, request.intensity)

                            // Merge with current parameters
                            currentParameters = currentParameters.mergeWith(scaledParams)

                            val endTime = System.currentTimeMillis()

                            MorphResult(
                                parameters = currentParameters,
                                generationTimeMs = endTime - startTime,
                                tokensGenerated = jsonOutput.length / 4,  // Rough estimate
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
                                errorMessage = "Failed to parse VLM output: ${parseError.message}"
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
                        errorMessage = "LLM generation failed: ${error.message}"
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
                errorMessage = "Unexpected error: ${e.message}"
            )
        }
    }

    /**
     * Build the user prompt with regional focus.
     */
    private fun buildUserPrompt(request: MorphRequest): String {
        val regionalInstruction = getRegionalPrompt(request.region)
        return """
$regionalInstruction

Region: ${request.region.displayName}
Description: ${request.prompt}
""".trimIndent()
    }

    /**
     * Apply intensity scaling to parameters.
     * Intensity of 1.0 = no change, 0.5 = half effect, 2.0 = double effect.
     */
    private fun applyIntensity(params: MorphParameters, intensity: Float): MorphParameters {
        if (intensity == 1.0f) return params

        val scaled = params.toMap().mapValues { (_, value) ->
            // Scale the deviation from 1.0 by the intensity
            val deviation = value - 1.0f
            val scaledDeviation = deviation * intensity
            (1.0f + scaledDeviation).coerceIn(0.0f, 2.0f)
        }

        return parser.fromMap(scaled)
    }

    /**
     * Get the current morph parameters.
     */
    fun getCurrentParameters(): MorphParameters = currentParameters

    /**
     * Reset parameters to default.
     */
    fun resetParameters() {
        currentParameters = MorphParameters.DEFAULT
    }

    /**
     * Set parameters directly (e.g., when loading a preset).
     */
    fun setParameters(params: MorphParameters) {
        currentParameters = params
    }

    /**
     * Adjust a single parameter by name.
     */
    fun adjustParameter(name: String, value: Float): MorphParameters {
        val map = currentParameters.toMap().toMutableMap()
        if (map.containsKey(name)) {
            map[name] = value.coerceIn(0.0f, 2.0f)
            currentParameters = parser.fromMap(map)
        }
        return currentParameters
    }

    /**
     * Generate mock morph parameters based on keyword matching.
     * This provides a working demo without the AI model.
     */
    private fun generateMockMorph(request: MorphRequest, startTime: Long): MorphResult {
        val prompt = request.prompt.lowercase()
        val params = mutableMapOf<String, Float>()

        // Eye-related keywords
        if (prompt.contains("big") && prompt.contains("eye")) params["eyeSize"] = 1.3f
        if (prompt.contains("small") && prompt.contains("eye")) params["eyeSize"] = 0.8f
        if (prompt.contains("wide") && prompt.contains("eye")) params["eyeSpacing"] = 1.2f
        if (prompt.contains("close") && prompt.contains("eye")) params["eyeSpacing"] = 0.85f
        if (prompt.contains("mysterious") || prompt.contains("intense")) {
            params["eyeSize"] = 0.95f
            params["eyeSharpness"] = 0.85f
            params["eyeAngle"] = 1.1f
            params["eyebrowHeight"] = 0.9f
        }
        if (prompt.contains("innocent") || prompt.contains("cute")) {
            params["eyeSize"] = 1.25f
            params["eyeAngle"] = 0.95f
        }

        // Nose-related keywords
        if (prompt.contains("big") && prompt.contains("nose")) params["noseWidth"] = 1.25f
        if (prompt.contains("small") && prompt.contains("nose")) params["noseWidth"] = 0.8f
        if (prompt.contains("long") && prompt.contains("nose")) params["noseLength"] = 1.2f
        if (prompt.contains("short") && prompt.contains("nose")) params["noseLength"] = 0.85f
        if (prompt.contains("button") && prompt.contains("nose")) {
            params["noseLength"] = 0.85f
            params["noseTip"] = 1.15f
        }

        // Jaw/chin-related keywords
        if (prompt.contains("strong") && (prompt.contains("jaw") || prompt.contains("chin"))) {
            params["jawWidth"] = 1.15f
            params["jawSharpness"] = 1.3f
            params["chinProtrusion"] = 1.1f
        }
        if (prompt.contains("soft") || prompt.contains("round")) {
            params["jawSharpness"] = 0.8f
        }
        if (prompt.contains("masculine")) {
            params["jawWidth"] = 1.2f
            params["jawSharpness"] = 1.25f
            params["chinWidth"] = 1.1f
        }
        if (prompt.contains("feminine")) {
            params["jawSharpness"] = 0.75f
            params["cheekHeight"] = 1.15f
            params["lipFullness"] = 1.2f
        }

        // Lip-related keywords
        if (prompt.contains("full") && prompt.contains("lip")) params["lipFullness"] = 1.35f
        if (prompt.contains("thin") && prompt.contains("lip")) params["lipFullness"] = 0.75f
        if (prompt.contains("wide") && prompt.contains("mouth")) params["lipWidth"] = 1.2f
        if (prompt.contains("smile") || prompt.contains("happy")) params["mouthCorner"] = 1.2f

        // Cheek-related keywords
        if (prompt.contains("high") && prompt.contains("cheek")) params["cheekHeight"] = 1.25f
        if (prompt.contains("hollow") || prompt.contains("gaunt")) params["cheekDepth"] = 0.7f
        if (prompt.contains("chubby") || prompt.contains("full") && prompt.contains("cheek")) {
            params["cheekWidth"] = 1.25f
            params["cheekDepth"] = 1.2f
        }

        // Face shape keywords
        if (prompt.contains("narrow") && prompt.contains("face")) params["faceWidth"] = 0.85f
        if (prompt.contains("wide") && prompt.contains("face")) params["faceWidth"] = 1.2f
        if (prompt.contains("long") && prompt.contains("face")) params["faceLength"] = 1.15f
        if (prompt.contains("short") && prompt.contains("face")) params["faceLength"] = 0.9f

        // Forehead keywords
        if (prompt.contains("high") && prompt.contains("forehead")) params["foreheadHeight"] = 1.2f
        if (prompt.contains("small") && prompt.contains("forehead")) params["foreheadHeight"] = 0.85f

        // Eyebrow keywords
        if (prompt.contains("thick") && prompt.contains("brow")) params["eyebrowThickness"] = 1.3f
        if (prompt.contains("thin") && prompt.contains("brow")) params["eyebrowThickness"] = 0.7f
        if (prompt.contains("arch")) params["eyebrowAngle"] = 1.25f
        if (prompt.contains("straight") && prompt.contains("brow")) params["eyebrowAngle"] = 0.8f

        // General style keywords
        if (prompt.contains("angry") || prompt.contains("fierce")) {
            params["eyebrowAngle"] = 0.8f
            params["eyebrowHeight"] = 0.85f
            params["mouthCorner"] = 0.85f
        }
        if (prompt.contains("sad") || prompt.contains("tired")) {
            params["eyeAngle"] = 0.9f
            params["mouthCorner"] = 0.85f
        }
        if (prompt.contains("surprise") || prompt.contains("shock")) {
            params["eyeSize"] = 1.3f
            params["eyebrowHeight"] = 1.25f
        }

        // Generic bigger/smaller
        if (prompt.contains("bigger") || prompt.contains("larger")) {
            when (request.region) {
                FaceRegion.EYES -> params["eyeSize"] = 1.25f
                FaceRegion.NOSE -> { params["noseWidth"] = 1.2f; params["noseLength"] = 1.15f }
                FaceRegion.MOUTH_LIPS -> { params["lipFullness"] = 1.25f; params["mouthSize"] = 1.2f }
                FaceRegion.JAW_CHIN -> { params["jawWidth"] = 1.2f; params["chinLength"] = 1.15f }
                FaceRegion.CHEEKS -> params["cheekWidth"] = 1.2f
                FaceRegion.FOREHEAD -> params["foreheadHeight"] = 1.2f
                FaceRegion.FACE_SHAPE -> params["faceWidth"] = 1.15f
                FaceRegion.ALL -> {}
            }
        }
        if (prompt.contains("smaller") || prompt.contains("reduce")) {
            when (request.region) {
                FaceRegion.EYES -> params["eyeSize"] = 0.8f
                FaceRegion.NOSE -> { params["noseWidth"] = 0.85f; params["noseLength"] = 0.9f }
                FaceRegion.MOUTH_LIPS -> { params["lipFullness"] = 0.8f; params["mouthSize"] = 0.85f }
                FaceRegion.JAW_CHIN -> { params["jawWidth"] = 0.85f; params["chinLength"] = 0.9f }
                FaceRegion.CHEEKS -> params["cheekWidth"] = 0.85f
                FaceRegion.FOREHEAD -> params["foreheadHeight"] = 0.85f
                FaceRegion.FACE_SHAPE -> params["faceWidth"] = 0.9f
                FaceRegion.ALL -> {}
            }
        }

        // Fallback: if no keywords matched, apply a subtle change based on region
        if (params.isEmpty()) {
            Log.d(TAG, "No keywords matched, applying default region-based change")
            when (request.region) {
                FaceRegion.EYES -> params["eyeSize"] = 1.15f
                FaceRegion.NOSE -> params["noseWidth"] = 1.1f
                FaceRegion.MOUTH_LIPS -> params["lipFullness"] = 1.15f
                FaceRegion.JAW_CHIN -> params["jawWidth"] = 1.1f
                FaceRegion.CHEEKS -> params["cheekWidth"] = 1.1f
                FaceRegion.FOREHEAD -> params["foreheadHeight"] = 1.1f
                FaceRegion.FACE_SHAPE -> params["faceWidth"] = 1.1f
                FaceRegion.ALL -> params["faceWidth"] = 1.1f
            }
        }

        // Log detected keywords
        Log.d(TAG, "Mock mode detected ${params.size} parameters: ${params.keys.joinToString()}")
        params.forEach { (key, value) ->
            Log.d(TAG, "  $key = $value")
        }

        // Apply intensity
        val scaledParams = params.mapValues { (_, value) ->
            val deviation = value - 1.0f
            (1.0f + deviation * request.intensity).coerceIn(0.0f, 2.0f)
        }

        // Merge with current parameters
        val newParams = parser.fromMap(scaledParams)
        currentParameters = currentParameters.mergeWith(newParams)

        // Log final parameters being sent
        val nonDefault = currentParameters.getNonDefaultParameters()
        Log.d(TAG, "Sending ${nonDefault.size} non-default parameters to WebView")

        val endTime = System.currentTimeMillis()

        return MorphResult(
            parameters = currentParameters,
            generationTimeMs = endTime - startTime,
            tokensGenerated = 0,
            success = true
        )
    }
}
