package com.facemorphai.model

import kotlinx.serialization.Serializable

/**
 * Represents all available morph parameters for a 3D face model.
 * Values range from 0.0 (minimum) to 2.0 (maximum), with 1.0 being the default/neutral.
 */
@Serializable
data class MorphParameters(
    // Eye parameters
    val eyeSize: Float = 1.0f,
    val eyeSharpness: Float = 1.0f,
    val eyeAngle: Float = 1.0f,
    val eyeSpacing: Float = 1.0f,
    val eyeDepth: Float = 1.0f,
    val eyebrowHeight: Float = 1.0f,
    val eyebrowAngle: Float = 1.0f,
    val eyebrowThickness: Float = 1.0f,

    // Nose parameters
    val noseWidth: Float = 1.0f,
    val noseLength: Float = 1.0f,
    val noseBridge: Float = 1.0f,
    val noseTip: Float = 1.0f,
    val nostrilSize: Float = 1.0f,

    // Jaw and chin parameters
    val jawWidth: Float = 1.0f,
    val jawSharpness: Float = 1.0f,
    val chinLength: Float = 1.0f,
    val chinWidth: Float = 1.0f,
    val chinProtrusion: Float = 1.0f,

    // Cheek parameters
    val cheekHeight: Float = 1.0f,
    val cheekWidth: Float = 1.0f,
    val cheekDepth: Float = 1.0f,

    // Mouth and lip parameters
    val lipFullness: Float = 1.0f,
    val lipWidth: Float = 1.0f,
    val mouthSize: Float = 1.0f,
    val mouthCorner: Float = 1.0f,  // Up/down for smile/frown
    val upperLipHeight: Float = 1.0f,
    val lowerLipHeight: Float = 1.0f,

    // Forehead parameters
    val foreheadHeight: Float = 1.0f,
    val foreheadWidth: Float = 1.0f,
    val foreheadSlope: Float = 1.0f,

    // Overall face shape
    val faceWidth: Float = 1.0f,
    val faceLength: Float = 1.0f
) {
    /**
     * Merge with another MorphParameters, taking non-default values from the other.
     */
    fun mergeWith(other: MorphParameters): MorphParameters {
        return MorphParameters(
            eyeSize = if (other.eyeSize != 1.0f) other.eyeSize else this.eyeSize,
            eyeSharpness = if (other.eyeSharpness != 1.0f) other.eyeSharpness else this.eyeSharpness,
            eyeAngle = if (other.eyeAngle != 1.0f) other.eyeAngle else this.eyeAngle,
            eyeSpacing = if (other.eyeSpacing != 1.0f) other.eyeSpacing else this.eyeSpacing,
            eyeDepth = if (other.eyeDepth != 1.0f) other.eyeDepth else this.eyeDepth,
            eyebrowHeight = if (other.eyebrowHeight != 1.0f) other.eyebrowHeight else this.eyebrowHeight,
            eyebrowAngle = if (other.eyebrowAngle != 1.0f) other.eyebrowAngle else this.eyebrowAngle,
            eyebrowThickness = if (other.eyebrowThickness != 1.0f) other.eyebrowThickness else this.eyebrowThickness,
            noseWidth = if (other.noseWidth != 1.0f) other.noseWidth else this.noseWidth,
            noseLength = if (other.noseLength != 1.0f) other.noseLength else this.noseLength,
            noseBridge = if (other.noseBridge != 1.0f) other.noseBridge else this.noseBridge,
            noseTip = if (other.noseTip != 1.0f) other.noseTip else this.noseTip,
            nostrilSize = if (other.nostrilSize != 1.0f) other.nostrilSize else this.nostrilSize,
            jawWidth = if (other.jawWidth != 1.0f) other.jawWidth else this.jawWidth,
            jawSharpness = if (other.jawSharpness != 1.0f) other.jawSharpness else this.jawSharpness,
            chinLength = if (other.chinLength != 1.0f) other.chinLength else this.chinLength,
            chinWidth = if (other.chinWidth != 1.0f) other.chinWidth else this.chinWidth,
            chinProtrusion = if (other.chinProtrusion != 1.0f) other.chinProtrusion else this.chinProtrusion,
            cheekHeight = if (other.cheekHeight != 1.0f) other.cheekHeight else this.cheekHeight,
            cheekWidth = if (other.cheekWidth != 1.0f) other.cheekWidth else this.cheekWidth,
            cheekDepth = if (other.cheekDepth != 1.0f) other.cheekDepth else this.cheekDepth,
            lipFullness = if (other.lipFullness != 1.0f) other.lipFullness else this.lipFullness,
            lipWidth = if (other.lipWidth != 1.0f) other.lipWidth else this.lipWidth,
            mouthSize = if (other.mouthSize != 1.0f) other.mouthSize else this.mouthSize,
            mouthCorner = if (other.mouthCorner != 1.0f) other.mouthCorner else this.mouthCorner,
            upperLipHeight = if (other.upperLipHeight != 1.0f) other.upperLipHeight else this.upperLipHeight,
            lowerLipHeight = if (other.lowerLipHeight != 1.0f) other.lowerLipHeight else this.lowerLipHeight,
            foreheadHeight = if (other.foreheadHeight != 1.0f) other.foreheadHeight else this.foreheadHeight,
            foreheadWidth = if (other.foreheadWidth != 1.0f) other.foreheadWidth else this.foreheadWidth,
            foreheadSlope = if (other.foreheadSlope != 1.0f) other.foreheadSlope else this.foreheadSlope,
            faceWidth = if (other.faceWidth != 1.0f) other.faceWidth else this.faceWidth,
            faceLength = if (other.faceLength != 1.0f) other.faceLength else this.faceLength
        )
    }

    /**
     * Convert to a Map for easy JSON serialization to Three.js.
     */
    fun toMap(): Map<String, Float> = mapOf(
        "eyeSize" to eyeSize,
        "eyeSharpness" to eyeSharpness,
        "eyeAngle" to eyeAngle,
        "eyeSpacing" to eyeSpacing,
        "eyeDepth" to eyeDepth,
        "eyebrowHeight" to eyebrowHeight,
        "eyebrowAngle" to eyebrowAngle,
        "eyebrowThickness" to eyebrowThickness,
        "noseWidth" to noseWidth,
        "noseLength" to noseLength,
        "noseBridge" to noseBridge,
        "noseTip" to noseTip,
        "nostrilSize" to nostrilSize,
        "jawWidth" to jawWidth,
        "jawSharpness" to jawSharpness,
        "chinLength" to chinLength,
        "chinWidth" to chinWidth,
        "chinProtrusion" to chinProtrusion,
        "cheekHeight" to cheekHeight,
        "cheekWidth" to cheekWidth,
        "cheekDepth" to cheekDepth,
        "lipFullness" to lipFullness,
        "lipWidth" to lipWidth,
        "mouthSize" to mouthSize,
        "mouthCorner" to mouthCorner,
        "upperLipHeight" to upperLipHeight,
        "lowerLipHeight" to lowerLipHeight,
        "foreheadHeight" to foreheadHeight,
        "foreheadWidth" to foreheadWidth,
        "foreheadSlope" to foreheadSlope,
        "faceWidth" to faceWidth,
        "faceLength" to faceLength
    )

    /**
     * Get only the parameters that differ from default (1.0).
     */
    fun getNonDefaultParameters(): Map<String, Float> =
        toMap().filter { it.value != 1.0f }

    companion object {
        /**
         * All available parameter names grouped by face region.
         */
        val PARAMETER_GROUPS = mapOf(
            "Eyes" to listOf(
                "eyeSize", "eyeSharpness", "eyeAngle", "eyeSpacing", "eyeDepth",
                "eyebrowHeight", "eyebrowAngle", "eyebrowThickness"
            ),
            "Nose" to listOf(
                "noseWidth", "noseLength", "noseBridge", "noseTip", "nostrilSize"
            ),
            "Jaw & Chin" to listOf(
                "jawWidth", "jawSharpness", "chinLength", "chinWidth", "chinProtrusion"
            ),
            "Cheeks" to listOf(
                "cheekHeight", "cheekWidth", "cheekDepth"
            ),
            "Mouth & Lips" to listOf(
                "lipFullness", "lipWidth", "mouthSize", "mouthCorner",
                "upperLipHeight", "lowerLipHeight"
            ),
            "Forehead" to listOf(
                "foreheadHeight", "foreheadWidth", "foreheadSlope"
            ),
            "Face Shape" to listOf(
                "faceWidth", "faceLength"
            )
        )

        /**
         * Default/neutral parameters.
         */
        val DEFAULT = MorphParameters()
    }
}

/**
 * Enum representing face regions for targeted modifications.
 */
enum class FaceRegion(val displayName: String, val parameters: List<String>) {
    EYES("Eyes", MorphParameters.PARAMETER_GROUPS["Eyes"] ?: emptyList()),
    NOSE("Nose", MorphParameters.PARAMETER_GROUPS["Nose"] ?: emptyList()),
    JAW_CHIN("Jaw & Chin", MorphParameters.PARAMETER_GROUPS["Jaw & Chin"] ?: emptyList()),
    CHEEKS("Cheeks", MorphParameters.PARAMETER_GROUPS["Cheeks"] ?: emptyList()),
    MOUTH_LIPS("Mouth & Lips", MorphParameters.PARAMETER_GROUPS["Mouth & Lips"] ?: emptyList()),
    FOREHEAD("Forehead", MorphParameters.PARAMETER_GROUPS["Forehead"] ?: emptyList()),
    FACE_SHAPE("Face Shape", MorphParameters.PARAMETER_GROUPS["Face Shape"] ?: emptyList()),
    ALL("All Features", MorphParameters.PARAMETER_GROUPS.values.flatten());

    companion object {
        fun fromDisplayName(name: String): FaceRegion? =
            entries.find { it.displayName.equals(name, ignoreCase = true) }
    }
}

/**
 * Represents a modification request from the user.
 */
data class MorphRequest(
    val region: FaceRegion,
    val prompt: String,
    val intensity: Float = 1.0f  // 0.0 to 2.0, affects how extreme the changes are
)

/**
 * Represents the result of a morph generation.
 */
data class MorphResult(
    val parameters: MorphParameters,
    val generationTimeMs: Long,
    val tokensGenerated: Int,
    val success: Boolean,
    val errorMessage: String? = null
)
