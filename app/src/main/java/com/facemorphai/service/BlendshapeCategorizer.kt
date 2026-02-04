package com.facemorphai.service

import android.util.Log
import com.facemorphai.model.FaceRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Uses the VLM to dynamically categorize blendshape names into facial regions.
 * This runs once when a model is loaded, caching the results.
 */
class BlendshapeCategorizer(private val nexaService: NexaService) {

    companion object {
        private const val TAG = "BlendshapeCategorizer"

        private val CATEGORIZATION_PROMPT = """
You are categorizing facial blendshape names into regions. Analyze each name and assign it to ONE category.

Categories:
- EYES: Anything related to eyes, eyelids, eyebrows, blinking, gaze, pupils
- NOSE: Anything related to nose, nostrils, sniffing, sneer, nasal
- JAW_CHIN: Anything related to jaw, chin, chewing, mandible
- CHEEKS: Anything related to cheeks, puffing, inflating
- MOUTH_LIPS: Anything related to mouth, lips, smile, frown, tongue, teeth
- FOREHEAD: Anything related to forehead, brow raises
- FACE_SHAPE: General face/head shape, scale, or doesn't fit other categories

Output ONLY a JSON object mapping each category to an array of matching blendshape names.
Example format:
{"EYES":["eyeBlink_L","eyeBlink_R"],"MOUTH_LIPS":["mouthSmile_L"],"FACE_SHAPE":["base_head"]}

Blendshape names to categorize:
""".trimIndent()

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    // Cached categorization results
    private var cachedMapping: Map<FaceRegion, List<String>> = emptyMap()
    private var categorizedNames: Set<String> = emptySet()

    /**
     * Check if we have a cached categorization for these blendshape names.
     */
    fun hasCachedMapping(names: List<String>): Boolean {
        return names.toSet() == categorizedNames && cachedMapping.isNotEmpty()
    }

    /**
     * Get cached mapping or empty map if not available.
     */
    fun getCachedMapping(): Map<FaceRegion, List<String>> = cachedMapping

    /**
     * Get blendshapes for a specific region from the cached mapping.
     */
    fun getBlendshapesForRegion(region: FaceRegion): List<String> {
        if (region == FaceRegion.ALL) {
            return cachedMapping.values.flatten()
        }
        return cachedMapping[region] ?: emptyList()
    }

    /**
     * Categorize blendshape names using the VLM.
     * Results are cached for subsequent calls.
     */
    suspend fun categorize(blendShapeNames: List<String>): Map<FaceRegion, List<String>> = withContext(Dispatchers.IO) {
        // Return cached result if available
        if (hasCachedMapping(blendShapeNames)) {
            Log.d(TAG, "Using cached categorization for ${blendShapeNames.size} blendshapes")
            return@withContext cachedMapping
        }

        if (!nexaService.hasModelLoaded()) {
            Log.w(TAG, "VLM not loaded, using fallback keyword matching")
            return@withContext fallbackCategorization(blendShapeNames)
        }

        Log.d(TAG, "Categorizing ${blendShapeNames.size} blendshapes using VLM...")
        val startTime = System.currentTimeMillis()

        val prompt = CATEGORIZATION_PROMPT + blendShapeNames.joinToString(", ")

        val result = nexaService.generate(prompt, maxTokens = 1024)

        result.fold(
            onSuccess = { output ->
                Log.d(TAG, "VLM categorization response: $output")
                val mapping = parseCategorizationResponse(output, blendShapeNames)

                if (mapping.isNotEmpty()) {
                    cachedMapping = mapping
                    categorizedNames = blendShapeNames.toSet()
                    Log.d(TAG, "Categorized ${blendShapeNames.size} blendshapes in ${System.currentTimeMillis() - startTime}ms")
                    logCategorization(mapping)
                    return@withContext mapping
                } else {
                    Log.w(TAG, "VLM categorization failed, using fallback")
                    return@withContext fallbackCategorization(blendShapeNames)
                }
            },
            onFailure = { error ->
                Log.e(TAG, "VLM categorization error: ${error.message}")
                return@withContext fallbackCategorization(blendShapeNames)
            }
        )
    }

    /**
     * Parse the VLM's JSON response into a region mapping.
     */
    private fun parseCategorizationResponse(
        response: String,
        allNames: List<String>
    ): Map<FaceRegion, List<String>> {
        val mapping = mutableMapOf<FaceRegion, MutableList<String>>()
        val assignedNames = mutableSetOf<String>()

        try {
            // Extract JSON from response (handle markdown code blocks)
            val jsonStr = extractJson(response)
            val jsonObject = json.parseToJsonElement(jsonStr) as? JsonObject
                ?: return emptyMap()

            // Map string keys to FaceRegion enum
            for ((key, value) in jsonObject) {
                val region = try {
                    FaceRegion.valueOf(key.uppercase())
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Unknown region in VLM response: $key")
                    continue
                }

                val names = value.jsonArray.mapNotNull { element ->
                    val name = element.jsonPrimitive.content
                    // Validate that the name exists in our blendshape list
                    allNames.find { it.equals(name, ignoreCase = true) }
                }

                if (names.isNotEmpty()) {
                    mapping.getOrPut(region) { mutableListOf() }.addAll(names)
                    assignedNames.addAll(names)
                }
            }

            // Any unassigned blendshapes go to FACE_SHAPE
            val unassigned = allNames.filter { it !in assignedNames }
            if (unassigned.isNotEmpty()) {
                mapping.getOrPut(FaceRegion.FACE_SHAPE) { mutableListOf() }.addAll(unassigned)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse VLM categorization: ${e.message}")
            return emptyMap()
        }

        return mapping
    }

    /**
     * Extract JSON from potentially markdown-wrapped response.
     */
    private fun extractJson(response: String): String {
        var cleaned = response.trim()

        // Remove markdown code blocks
        if (cleaned.contains("```")) {
            val start = cleaned.indexOf("{")
            val end = cleaned.lastIndexOf("}") + 1
            if (start != -1 && end > start) {
                cleaned = cleaned.substring(start, end)
            }
        }

        return cleaned
    }

    /**
     * Fallback to keyword-based categorization when VLM is unavailable.
     */
    private fun fallbackCategorization(names: List<String>): Map<FaceRegion, List<String>> {
        Log.d(TAG, "Using fallback keyword categorization")
        val result = FaceRegion.groupBlendShapes(names)
        cachedMapping = result
        categorizedNames = names.toSet()
        return result
    }

    /**
     * Log the categorization results for debugging.
     */
    private fun logCategorization(mapping: Map<FaceRegion, List<String>>) {
        Log.d(TAG, "=== Blendshape Categorization ===")
        mapping.forEach { (region, names) ->
            Log.d(TAG, "${region.displayName}: ${names.size} shapes - ${names.take(3).joinToString(", ")}${if (names.size > 3) "..." else ""}")
        }
        Log.d(TAG, "=================================")
    }

    /**
     * Clear the cached categorization.
     */
    fun clearCache() {
        cachedMapping = emptyMap()
        categorizedNames = emptySet()
    }
}
