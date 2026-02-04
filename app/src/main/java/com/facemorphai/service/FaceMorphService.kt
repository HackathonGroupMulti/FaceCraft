package com.facemorphai.service

import android.content.Context
import android.util.Log
import com.facemorphai.logging.VlmLogManager
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
 * Uses dynamically discovered blendshape names from the FBX model.
 */
class FaceMorphService(private val context: Context) {

    companion object {
        private const val TAG = "FaceMorphService"

        private fun buildSystemPrompt(blendShapeNames: List<String>): String {
            val namesList = blendShapeNames.joinToString(", ")
            val exampleKey = blendShapeNames.firstOrNull() ?: "shape_name"
            return """
CRITICAL: Output ONLY raw JSON. No text before or after. No markdown. No explanation. No comments.

Rules:
- Values: 0.0 to 1.0 (0.0=no change, 1.0=maximum)
- Only modify requested features
- Keep other features at their current values

Available keys: $namesList

Format (STRICT):
{"$exampleKey":0.6}

DO NOT write anything except the JSON object.
""".trimIndent()
        }

        private fun getRegionalPrompt(region: FaceRegion, blendShapeNames: List<String>): String {
            if (region == FaceRegion.ALL) {
                return "You may modify any shape keys to achieve the look."
            }
            val matching = blendShapeNames.filter { region.matchesBlendShape(it) }
            return if (matching.isNotEmpty()) {
                "Focus ONLY on these shape keys: ${matching.joinToString(", ")}"
            } else {
                "Focus on shape keys related to: ${region.displayName}"
            }
        }
    }

    private val nexaService = NexaService.getInstance(context)
    val parser = MorphParameterParser()

    private var currentParameters = MorphParameters.DEFAULT
    private var useMockMode = false
    private var blendShapeNames: List<String> = emptyList()

    fun setMockMode(enabled: Boolean) {
        useMockMode = enabled
    }

    /**
     * Update the available blendshape names from the loaded FBX model.
     * This rebuilds the AI prompt to use the actual shape key names.
     */
    fun updateBlendShapeNames(names: List<String>) {
        blendShapeNames = names
        parser.updateValidParams(names)
        Log.d(TAG, "BlendShape names updated: ${names.size} shapes")
    }

    fun getBlendShapeNames(): List<String> = blendShapeNames

    suspend fun generateMorph(request: MorphRequest): MorphResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        if (useMockMode || !nexaService.hasModelLoaded()) {
            return@withContext generateMockMorph(request, startTime)
        }

        if (blendShapeNames.isEmpty()) {
            return@withContext MorphResult(
                parameters = currentParameters,
                generationTimeMs = System.currentTimeMillis() - startTime,
                tokensGenerated = 0,
                success = false,
                errorMessage = "No blendshape names available. Load a model first."
            )
        }

        val systemPrompt = buildSystemPrompt(blendShapeNames)
        val regionalInstruction = getRegionalPrompt(request.region, blendShapeNames)

        // Simplify current state presentation to reduce prompt complexity
        val nonDefaults = currentParameters.getNonDefaultParameters()
        val stateClause = if (nonDefaults.isNotEmpty()) {
            val simplified = nonDefaults.entries.take(5).joinToString(", ") { "${it.key}:${it.value}" }
            val more = if (nonDefaults.size > 5) " +${nonDefaults.size - 5} more" else ""
            "\nActive: $simplified$more"
        } else {
            ""
        }

        val fullPrompt = """
$systemPrompt$stateClause

Task: ${request.prompt}
Focus: $regionalInstruction

Output:""".trimIndent()

        Log.d(TAG, "=== VLM REQUEST (Attempt 1) ===")
        Log.d(TAG, "Prompt length: ${fullPrompt.length} chars")
        Log.d(TAG, "Full prompt:\n$fullPrompt")
        Log.d(TAG, "================================")

        try {
            val maxAttempts = 2
            var lastError: String? = null

            for (attempt in 1..maxAttempts) {
                if (attempt > 1) {
                    Log.d(TAG, "=== RETRY ATTEMPT $attempt ===")
                }
                val attemptStartTime = System.currentTimeMillis()
                val result = nexaService.generateWithStats(prompt = fullPrompt, maxTokens = 256)
                val attemptDuration = System.currentTimeMillis() - attemptStartTime

                val morphResult = result.fold(
                    onSuccess = { genResult ->
                        val jsonOutput = genResult.text
                        Log.d(TAG, "VLM response received (${jsonOutput.length} chars, ${genResult.tokenCount} tokens)")
                        Log.d(TAG, "VLM output: \"$jsonOutput\"")

                        val parseResult = parser.parse(jsonOutput)

                        parseResult.fold(
                            onSuccess = { newParams ->
                                Log.d(TAG, "Successfully parsed ${newParams.values.size} parameters")
                                // Log successful interaction with stream stats
                                VlmLogManager.logVlmInteraction(
                                    prompt = fullPrompt,
                                    vlmOutput = jsonOutput,
                                    parseSuccess = true,
                                    parseError = null,
                                    parsedParamCount = newParams.values.size,
                                    generationTimeMs = attemptDuration,
                                    attempt = attempt,
                                    streamTokenCount = genResult.tokenCount,
                                    streamRawResults = genResult.rawResults
                                )
                                val scaledParams = applyIntensity(newParams, request.intensity)
                                currentParameters = currentParameters.mergeWith(scaledParams)
                                Log.d(TAG, "Cumulative parameters now: ${currentParameters.values.size} active")
                                MorphResult(
                                    parameters = currentParameters,
                                    generationTimeMs = System.currentTimeMillis() - startTime,
                                    tokensGenerated = genResult.tokenCount,
                                    success = true
                                )
                            },
                            onFailure = { parseError ->
                                Log.e(TAG, "Parse error (attempt $attempt/$maxAttempts): ${parseError.message}")
                                Log.e(TAG, "Failed output was: \"$jsonOutput\"")
                                // Log failed parse interaction with stream stats
                                VlmLogManager.logVlmInteraction(
                                    prompt = fullPrompt,
                                    vlmOutput = jsonOutput,
                                    parseSuccess = false,
                                    parseError = parseError.message,
                                    parsedParamCount = null,
                                    generationTimeMs = attemptDuration,
                                    attempt = attempt,
                                    streamTokenCount = genResult.tokenCount,
                                    streamRawResults = genResult.rawResults
                                )
                                lastError = "VLM output was not valid JSON: ${parseError.message}"
                                null // signal retry
                            }
                        )
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Generation error (attempt $attempt/$maxAttempts): ${error.message}")
                        // Log generation failure
                        VlmLogManager.logVlmInteraction(
                            prompt = fullPrompt,
                            vlmOutput = null,
                            parseSuccess = false,
                            parseError = "Generation failed: ${error.message}",
                            parsedParamCount = null,
                            generationTimeMs = attemptDuration,
                            attempt = attempt,
                            streamTokenCount = 0,
                            streamRawResults = null
                        )
                        lastError = "VLM failed: ${error.message}"
                        null // signal retry
                    }
                )

                if (morphResult != null) return@withContext morphResult
                if (attempt < maxAttempts) Log.d(TAG, "Retrying VLM generation...")
            }

            MorphResult(
                parameters = currentParameters,
                generationTimeMs = System.currentTimeMillis() - startTime,
                tokensGenerated = 0,
                success = false,
                errorMessage = lastError ?: "Generation failed after $maxAttempts attempts"
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
        val scaled = params.values.mapValues { (_, value) ->
            (value * intensity).coerceIn(0.0f, 1.0f)
        }
        return MorphParameters(scaled)
    }

    fun getCurrentParameters(): MorphParameters = currentParameters
    fun resetParameters() { currentParameters = MorphParameters.DEFAULT }

    private fun generateMockMorph(request: MorphRequest, startTime: Long): MorphResult {
        if (blendShapeNames.isEmpty()) {
            return MorphResult(
                parameters = currentParameters,
                generationTimeMs = System.currentTimeMillis() - startTime,
                tokensGenerated = 0,
                success = false,
                errorMessage = "No blendshape names available for mock mode"
            )
        }

        val prompt = request.prompt.lowercase()
        val params = mutableMapOf<String, Float>()

        // Find blendshapes matching the selected region
        val regionShapes = if (request.region == FaceRegion.ALL) {
            blendShapeNames
        } else {
            blendShapeNames.filter { request.region.matchesBlendShape(it) }
                .ifEmpty { blendShapeNames }
        }

        // Determine intensity from keywords
        val value = when {
            prompt.contains("big") || prompt.contains("larger") || prompt.contains("wider") ||
            prompt.contains("full") || prompt.contains("plump") || prompt.contains("long") -> 0.6f
            prompt.contains("small") || prompt.contains("thin") || prompt.contains("narrow") ||
            prompt.contains("short") -> 0.2f
            else -> 0.4f
        }

        // Apply to first few matching shapes
        regionShapes.take(3).forEach { name ->
            params[name] = value
        }

        val newParams = MorphParameters(params)
        currentParameters = currentParameters.mergeWith(newParams)

        return MorphResult(
            parameters = currentParameters,
            generationTimeMs = System.currentTimeMillis() - startTime,
            tokensGenerated = 0,
            success = true
        )
    }
}
