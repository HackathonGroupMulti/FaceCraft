package com.facemorphai.parser

import android.util.Log
import com.facemorphai.model.MorphParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses LLM JSON output into MorphParameters.
 * Handles various edge cases and malformed output common in small local VLMs.
 */
class MorphParameterParser {

    companion object {
        private const val TAG = "MorphParameterParser"

        // Valid parameter names for validation
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
     */
    fun parse(llmOutput: String): Result<MorphParameters> {
        // CRITICAL: Log the exact output to help debugging
        Log.d(TAG, "RAW AI OUTPUT: \"$llmOutput\"")

        // 1. Try to find the JSON object boundaries
        val firstBrace = llmOutput.indexOf('{')
        val lastBrace = llmOutput.lastIndexOf('}')

        if (firstBrace == -1 || lastBrace == -1 || lastBrace <= firstBrace) {
            Log.e(TAG, "No valid JSON boundaries found in: $llmOutput")
            return Result.failure(Exception("No valid JSON object found in AI response"))
        }

        val jsonString = llmOutput.substring(firstBrace, lastBrace + 1)
        Log.d(TAG, "Extracted JSON string: $jsonString")

        return try {
            val jsonObject = json.parseToJsonElement(jsonString) as? JsonObject
                ?: return Result.failure(Exception("Extracted text is not a valid JSON object"))

            val params = parseJsonObject(jsonObject)
            Result.success(params)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parsing failed for: $jsonString", e)
            Result.failure(Exception("Failed to parse JSON: ${e.message}"))
        }
    }

    private fun parseJsonObject(jsonObject: JsonObject): MorphParameters {
        val values = mutableMapOf<String, Float>()

        for ((key, element) in jsonObject) {
            val normalizedKey = normalizeKey(key)

            if (normalizedKey in VALID_PARAMS) {
                try {
                    val value = element.jsonPrimitive.float
                    values[normalizedKey] = value.coerceIn(0.0f, 2.0f)
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping invalid value for $key: ${element}")
                }
            } else {
                Log.w(TAG, "Ignoring unknown parameter from AI: $key")
            }
        }

        return fromMap(values)
    }

    private fun normalizeKey(key: String): String {
        val normalized = key.replace("_", "").replace("-", "").lowercase()
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
