package com.facemorphai.parser

import android.util.Log
import com.facemorphai.model.MorphParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses LLM JSON output into MorphParameters.
 * Handles various edge cases and malformed output.
 */
class MorphParameterParser {

    companion object {
        private const val TAG = "MorphParameterParser"

        // Regex to extract JSON from potentially noisy LLM output
        private val JSON_REGEX = Regex("""\{[^{}]*\}""")

        // Valid parameter names
        private val VALID_PARAMS = setOf(
            "eyeSize", "eyeSharpness", "eyeAngle", "eyeSpacing", "eyeDepth",
            "eyebrowHeight", "eyebrowAngle", "eyebrowThickness",
            "noseWidth", "noseLength", "noseBridge", "noseTip", "nostrilSize",
            "jawWidth", "jawSharpness", "chinLength", "chinWidth", "chinProtrusion",
            "cheekHeight", "cheekWidth", "cheekDepth",
            "lipFullness", "lipWidth", "mouthSize", "mouthCorner",
            "upperLipHeight", "lowerLipHeight",
            "foreheadHeight", "foreheadWidth", "foreheadSlope",
            "faceWidth", "faceLength"
        )
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Parse LLM output into MorphParameters.
     * Handles various formats and edge cases.
     */
    fun parse(llmOutput: String): Result<MorphParameters> {
        Log.d(TAG, "Parsing: $llmOutput")

        // Clean up the output
        val cleaned = cleanOutput(llmOutput)

        // Try to extract JSON
        val jsonString = extractJson(cleaned)
            ?: return Result.failure(Exception("No valid JSON found in output"))

        return try {
            val jsonObject = json.parseToJsonElement(jsonString) as? JsonObject
                ?: return Result.failure(Exception("Output is not a JSON object"))

            val params = parseJsonObject(jsonObject)
            Result.success(params)
        } catch (e: Exception) {
            Log.e(TAG, "Parse error", e)
            Result.failure(Exception("Failed to parse JSON: ${e.message}"))
        }
    }

    /**
     * Clean up LLM output by removing common artifacts.
     */
    private fun cleanOutput(output: String): String {
        return output
            // Remove markdown code blocks
            .replace(Regex("```json\\s*"), "")
            .replace(Regex("```\\s*"), "")
            // Remove leading/trailing whitespace
            .trim()
            // Remove common LLM preambles
            .replace(Regex("^(Here is|Here's|The|Output:).*?(?=\\{)", RegexOption.IGNORE_CASE), "")
            // Remove trailing explanations
            .replace(Regex("\\}\\s*[A-Za-z].*$", RegexOption.DOT_MATCHES_ALL), "}")
    }

    /**
     * Extract the first valid JSON object from the text.
     */
    private fun extractJson(text: String): String? {
        // First, try to parse the whole text as JSON
        if (text.startsWith("{") && text.endsWith("}")) {
            return text
        }

        // Otherwise, find JSON in the text
        return JSON_REGEX.find(text)?.value
    }

    /**
     * Parse a JsonObject into MorphParameters.
     */
    private fun parseJsonObject(jsonObject: JsonObject): MorphParameters {
        val values = mutableMapOf<String, Float>()

        for ((key, element) in jsonObject) {
            // Normalize key (handle camelCase variations)
            val normalizedKey = normalizeKey(key)

            if (normalizedKey in VALID_PARAMS) {
                try {
                    val value = element.jsonPrimitive.float
                    // Clamp to valid range
                    values[normalizedKey] = value.coerceIn(0.0f, 2.0f)
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping invalid value for $key: ${element}")
                }
            } else {
                Log.w(TAG, "Ignoring unknown parameter: $key")
            }
        }

        return fromMap(values)
    }

    /**
     * Normalize parameter key names to handle variations.
     */
    private fun normalizeKey(key: String): String {
        // Handle common variations
        val normalized = key
            .replace("_", "")
            .replace("-", "")
            .lowercase()

        // Map to correct camelCase
        return when (normalized) {
            "eyesize" -> "eyeSize"
            "eyesharpness" -> "eyeSharpness"
            "eyeangle" -> "eyeAngle"
            "eyespacing" -> "eyeSpacing"
            "eyedepth" -> "eyeDepth"
            "eyebrowheight" -> "eyebrowHeight"
            "eyebrowangle" -> "eyebrowAngle"
            "eyebrowthickness" -> "eyebrowThickness"
            "nosewidth" -> "noseWidth"
            "noselength" -> "noseLength"
            "nosebridge" -> "noseBridge"
            "nosetip" -> "noseTip"
            "nostrilsize" -> "nostrilSize"
            "jawwidth" -> "jawWidth"
            "jawsharpness" -> "jawSharpness"
            "chinlength" -> "chinLength"
            "chinwidth" -> "chinWidth"
            "chinprotrusion" -> "chinProtrusion"
            "cheekheight" -> "cheekHeight"
            "cheekwidth" -> "cheekWidth"
            "cheekdepth" -> "cheekDepth"
            "lipfullness" -> "lipFullness"
            "lipwidth" -> "lipWidth"
            "mouthsize" -> "mouthSize"
            "mouthcorner" -> "mouthCorner"
            "upperlipheight" -> "upperLipHeight"
            "lowerlipheight" -> "lowerLipHeight"
            "foreheadheight" -> "foreheadHeight"
            "foreheadwidth" -> "foreheadWidth"
            "foreheadslope" -> "foreheadSlope"
            "facewidth" -> "faceWidth"
            "facelength" -> "faceLength"
            else -> key
        }
    }

    /**
     * Create MorphParameters from a map of values.
     */
    fun fromMap(values: Map<String, Float>): MorphParameters {
        return MorphParameters(
            eyeSize = values["eyeSize"] ?: 1.0f,
            eyeSharpness = values["eyeSharpness"] ?: 1.0f,
            eyeAngle = values["eyeAngle"] ?: 1.0f,
            eyeSpacing = values["eyeSpacing"] ?: 1.0f,
            eyeDepth = values["eyeDepth"] ?: 1.0f,
            eyebrowHeight = values["eyebrowHeight"] ?: 1.0f,
            eyebrowAngle = values["eyebrowAngle"] ?: 1.0f,
            eyebrowThickness = values["eyebrowThickness"] ?: 1.0f,
            noseWidth = values["noseWidth"] ?: 1.0f,
            noseLength = values["noseLength"] ?: 1.0f,
            noseBridge = values["noseBridge"] ?: 1.0f,
            noseTip = values["noseTip"] ?: 1.0f,
            nostrilSize = values["nostrilSize"] ?: 1.0f,
            jawWidth = values["jawWidth"] ?: 1.0f,
            jawSharpness = values["jawSharpness"] ?: 1.0f,
            chinLength = values["chinLength"] ?: 1.0f,
            chinWidth = values["chinWidth"] ?: 1.0f,
            chinProtrusion = values["chinProtrusion"] ?: 1.0f,
            cheekHeight = values["cheekHeight"] ?: 1.0f,
            cheekWidth = values["cheekWidth"] ?: 1.0f,
            cheekDepth = values["cheekDepth"] ?: 1.0f,
            lipFullness = values["lipFullness"] ?: 1.0f,
            lipWidth = values["lipWidth"] ?: 1.0f,
            mouthSize = values["mouthSize"] ?: 1.0f,
            mouthCorner = values["mouthCorner"] ?: 1.0f,
            upperLipHeight = values["upperLipHeight"] ?: 1.0f,
            lowerLipHeight = values["lowerLipHeight"] ?: 1.0f,
            foreheadHeight = values["foreheadHeight"] ?: 1.0f,
            foreheadWidth = values["foreheadWidth"] ?: 1.0f,
            foreheadSlope = values["foreheadSlope"] ?: 1.0f,
            faceWidth = values["faceWidth"] ?: 1.0f,
            faceLength = values["faceLength"] ?: 1.0f
        )
    }

    /**
     * Convert MorphParameters to JSON string for Three.js.
     */
    fun toJson(params: MorphParameters): String {
        val nonDefault = params.getNonDefaultParameters()
        if (nonDefault.isEmpty()) {
            return "{}"
        }

        return buildString {
            append("{")
            nonDefault.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) append(",")
                append("\"$key\":$value")
            }
            append("}")
        }
    }

    /**
     * Convert MorphParameters to full JSON string (including defaults).
     */
    fun toFullJson(params: MorphParameters): String {
        return buildString {
            append("{")
            params.toMap().entries.forEachIndexed { index, (key, value) ->
                if (index > 0) append(",")
                append("\"$key\":$value")
            }
            append("}")
        }
    }
}
