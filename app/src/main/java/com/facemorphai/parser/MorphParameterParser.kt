package com.facemorphai.parser

import android.util.Log
import com.facemorphai.model.MorphParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses LLM JSON output into MorphParameters.
 * Validates against dynamically discovered blendshape names from the FBX model.
 */
class MorphParameterParser {

    companion object {
        private const val TAG = "MorphParameterParser"
    }

    // Dynamic set of valid parameter names, updated when blendshape names are discovered
    var validParams: Set<String> = emptySet()
        private set

    /**
     * Update the valid parameter names from discovered blendshape names.
     */
    fun updateValidParams(blendShapeNames: List<String>) {
        validParams = blendShapeNames.toSet()
        Log.d(TAG, "Valid params updated: ${validParams.size} blendshapes")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Parse LLM output into MorphParameters.
     */
    fun parse(llmOutput: String): Result<MorphParameters> {
        Log.d(TAG, "RAW AI OUTPUT: \"$llmOutput\"")

        val firstBrace = llmOutput.indexOf('{')
        val lastBrace = llmOutput.lastIndexOf('}')

        if (firstBrace == -1 || lastBrace == -1 || lastBrace <= firstBrace) {
            Log.e(TAG, "No valid JSON boundaries found")
            return Result.failure(Exception("No valid JSON object found in AI response"))
        }

        val rawJson = llmOutput.substring(firstBrace, lastBrace + 1)

        return try {
            val jsonString = repairJson(rawJson)
            val jsonObject = json.parseToJsonElement(jsonString) as? JsonObject
                ?: return Result.failure(Exception("Extracted text is not a valid JSON object"))

            val params = parseJsonObject(jsonObject)
            Result.success(params)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parsing failed, trying fallback extraction", e)
            val fallbackParams = extractKeyValuesManually(rawJson)
            if (fallbackParams.isNotEmpty()) {
                Result.success(fromMap(fallbackParams))
            } else {
                Result.failure(Exception("Failed to parse JSON: ${e.message}"))
            }
        }
    }

    private fun parseJsonObject(jsonObject: JsonObject): MorphParameters {
        val values = mutableMapOf<String, Float>()
        for ((key, element) in jsonObject) {
            val normalizedKey = normalizeKey(key)
            if (isValidParam(normalizedKey)) {
                try {
                    val value = element.jsonPrimitive.float
                    values[normalizedKey] = value.coerceIn(0.0f, 1.0f)
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid value for $key: $element")
                }
            } else {
                Log.w(TAG, "Unknown blendshape key: $key (normalized: $normalizedKey)")
            }
        }
        return fromMap(values)
    }

    /**
     * Normalize a key from LLM output.
     * Since blendshape names are used as-is, we just do basic cleanup.
     * If validParams is populated, try case-insensitive matching.
     */
    private fun normalizeKey(key: String): String {
        val trimmed = key.trim()

        // If exact match exists, use it directly
        if (trimmed in validParams) return trimmed

        // Try case-insensitive match against known blendshape names
        if (validParams.isNotEmpty()) {
            val match = validParams.firstOrNull { it.equals(trimmed, ignoreCase = true) }
            if (match != null) return match
        }

        return trimmed
    }

    /**
     * Check if a parameter name is valid.
     * If no blendshape names have been set yet, accept anything.
     */
    private fun isValidParam(key: String): Boolean {
        if (validParams.isEmpty()) return true  // accept all if names not yet discovered
        return key in validParams
    }

    private fun repairJson(raw: String): String {
        return raw
            .replace(Regex(""",\s*}"""), "}")
            .trim()
    }

    private fun extractKeyValuesManually(text: String): Map<String, Float> {
        val params = mutableMapOf<String, Float>()
        val pattern = Regex("\"([^\"]+)\"\\s*:\\s*(\\d+\\.?\\d*)")
        pattern.findAll(text).forEach { match ->
            val key = normalizeKey(match.groupValues[1])
            val value = match.groupValues[2].toFloatOrNull() ?: return@forEach
            if (isValidParam(key)) {
                params[key] = value.coerceIn(0.0f, 1.0f)
            }
        }
        return params
    }

    fun fromMap(values: Map<String, Float>): MorphParameters {
        return MorphParameters(values)
    }

    fun toJson(params: MorphParameters): String {
        val nonDefault = params.getNonDefaultParameters()
        if (nonDefault.isEmpty()) return "{}"
        return buildString {
            append("{")
            nonDefault.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) append(",")
                append("\"$key\":$value")
            }
            append("}")
        }
    }
}
