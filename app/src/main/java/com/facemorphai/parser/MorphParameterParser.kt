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
     * Enhanced with multiple extraction strategies for robustness.
     */
    fun parse(llmOutput: String): Result<MorphParameters> {
        Log.d(TAG, "RAW AI OUTPUT: \"$llmOutput\"")

        // Strategy 1: Find JSON boundaries
        val firstBrace = llmOutput.indexOf('{')
        val lastBrace = llmOutput.lastIndexOf('}')

        if (firstBrace == -1 || lastBrace == -1 || lastBrace <= firstBrace) {
            Log.e(TAG, "No valid JSON boundaries found")
            // Strategy 2: Try manual extraction as fallback
            val fallbackParams = extractKeyValuesManually(llmOutput)
            return if (fallbackParams.isNotEmpty()) {
                Log.d(TAG, "Recovered ${fallbackParams.size} params via manual extraction")
                Result.success(fromMap(fallbackParams))
            } else {
                Result.failure(Exception("No valid JSON object found in AI response"))
            }
        }

        val rawJson = llmOutput.substring(firstBrace, lastBrace + 1)

        // Strategy 3: Find the FIRST complete JSON object (in case of multiple or broken JSON)
        val cleanJson = extractFirstCompleteJson(rawJson)

        return try {
            val jsonString = repairJson(cleanJson)
            val jsonObject = json.parseToJsonElement(jsonString) as? JsonObject
                ?: return Result.failure(Exception("Extracted text is not a valid JSON object"))

            val params = parseJsonObject(jsonObject)
            if (params.values.isEmpty()) {
                Log.w(TAG, "JSON parsed but no valid parameters found, trying manual extraction")
                val fallbackParams = extractKeyValuesManually(rawJson)
                if (fallbackParams.isNotEmpty()) {
                    return Result.success(fromMap(fallbackParams))
                }
            }
            Result.success(params)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parsing failed, trying fallback extraction", e)
            val fallbackParams = extractKeyValuesManually(rawJson)
            if (fallbackParams.isNotEmpty()) {
                Log.d(TAG, "Recovered ${fallbackParams.size} params via fallback")
                Result.success(fromMap(fallbackParams))
            } else {
                Result.failure(Exception("Failed to parse JSON: ${e.message}"))
            }
        }
    }

    /**
     * Extract the first complete JSON object from potentially mixed content.
     * Handles cases like: "Here's the result: {...} I hope this helps!"
     */
    private fun extractFirstCompleteJson(text: String): String {
        var braceDepth = 0
        var startIdx = -1
        var endIdx = -1

        for (i in text.indices) {
            when (text[i]) {
                '{' -> {
                    if (braceDepth == 0) startIdx = i
                    braceDepth++
                }
                '}' -> {
                    braceDepth--
                    if (braceDepth == 0 && startIdx != -1) {
                        endIdx = i
                        break
                    }
                }
            }
        }

        return if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
            text.substring(startIdx, endIdx + 1)
        } else {
            text
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
        var repaired = raw.trim()

        // Strip JavaScript-style line comments
        repaired = repaired.replace(Regex("//[^\n]*"), "")

        // Replace single quotes with double quotes for keys and string values
        repaired = repaired.replace(Regex("'([^']*)'\\s*:")) { "\"${it.groupValues[1]}\":" }
        repaired = repaired.replace(Regex(":\\s*'([^']*)'")) { ": \"${it.groupValues[1]}\"" }

        // Quote unquoted keys: { key: 0.5 } → { "key": 0.5 }
        repaired = repaired.replace(Regex("""(?<=\{|,)\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*:""")) {
            " \"${it.groupValues[1]}\":"
        }

        // Remove trailing commas before }
        repaired = repaired.replace(Regex(""",\s*}"""), "}")

        return repaired.trim()
    }

    private fun extractKeyValuesManually(text: String): Map<String, Float> {
        val params = mutableMapOf<String, Float>()

        // Strategy 1: Match standard JSON key-value pairs
        // Matches: "key":0.5, 'key':0.5, key:0.5
        val standardPattern = Regex("""(?:["']([^"']+)["']|([a-zA-Z_][a-zA-Z0-9_]*))\s*:\s*(-?\d+\.?\d*)""")
        standardPattern.findAll(text).forEach { match ->
            val key = normalizeKey(match.groupValues[1].ifEmpty { match.groupValues[2] })
            val value = match.groupValues[3].toFloatOrNull() ?: return@forEach
            if (isValidParam(key)) {
                params[key] = value.coerceIn(0.0f, 1.0f)
                Log.d(TAG, "Extracted: $key = $value")
            }
        }

        // Strategy 2: If validParams is set, look for any mention of param names followed by numbers
        // This catches cases like: "set eyeBlink_L to 0.6" or "eyeBlink_R = 0.5"
        if (params.isEmpty() && validParams.isNotEmpty()) {
            validParams.forEach { paramName ->
                val escapedName = Regex.escape(paramName)
                val mentionPattern = Regex("""$escapedName\D+?(\d+\.?\d*)""", RegexOption.IGNORE_CASE)
                mentionPattern.find(text)?.let { match ->
                    match.groupValues[1].toFloatOrNull()?.let { value ->
                        params[paramName] = value.coerceIn(0.0f, 1.0f)
                        Log.d(TAG, "Recovered from mention: $paramName = $value")
                    }
                }
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
