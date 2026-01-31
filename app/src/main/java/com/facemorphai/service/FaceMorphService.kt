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
 * Uses dynamically discovered blendshape names from the FBX model.
 */
class FaceMorphService(private val context: Context) {

    companion object {
        private const val TAG = "FaceMorphService"

        private fun buildSystemPrompt(blendShapeNames: List<String>): String {
            val namesList = blendShapeNames.joinToString(", ")
            return """
Output ONLY a JSON object. No markdown, no text, no explanation.
Values: 0.0-1.0 (0.0=no change, 0.5=moderate, 1.0=maximum effect).

Available shape keys: $namesList

Example input: "make eyes bigger"
Example output: {"${blendShapeNames.firstOrNull() ?: "shape_name"}":0.6}

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
        val currentState = parser.toJson(currentParameters)
        val stateClause = if (currentState != "{}") "\nCurrent face state: $currentState" else ""
        val fullPrompt = "$systemPrompt$stateClause\nRequest: ${request.prompt}\nRegion Focus: $regionalInstruction\nOutput JSON:"

        Log.d(TAG, "Sending prompt to VLM: $fullPrompt")

        try {
            val maxAttempts = 2
            var lastError: String? = null

            for (attempt in 1..maxAttempts) {
                val result = nexaService.generate(prompt = fullPrompt, maxTokens = 256)

                val morphResult = result.fold(
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
                                Log.e(TAG, "Parse error (attempt $attempt/$maxAttempts): ${parseError.message}")
                                lastError = "VLM output was not valid JSON: ${parseError.message}"
                                null // signal retry
                            }
                        )
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Generation error (attempt $attempt/$maxAttempts): ${error.message}")
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
