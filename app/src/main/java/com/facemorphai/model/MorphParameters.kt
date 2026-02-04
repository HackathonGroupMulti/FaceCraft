package com.facemorphai.model

import kotlinx.serialization.Serializable

/**
 * Represents morph parameters for a 3D face model.
 * Parameter names are dynamic - they come from the FBX model's blendshape names.
 * Values range from 0.0 (no effect) to 1.0 (maximum effect).
 */
@Serializable
data class MorphParameters(
    val values: Map<String, Float> = emptyMap()
) {
    /**
     * Merge with another MorphParameters, taking non-default values from the other.
     */
    fun mergeWith(other: MorphParameters): MorphParameters {
        val merged = values.toMutableMap()
        for ((key, value) in other.values) {
            if (value != 0.0f) {
                merged[key] = value
            }
        }
        return MorphParameters(merged)
    }

    /**
     * Convert to a Map for easy JSON serialization to Three.js.
     */
    fun toMap(): Map<String, Float> = values

    /**
     * Get only the parameters that differ from default (0.0).
     */
    fun getNonDefaultParameters(): Map<String, Float> =
        values.filter { it.value != 0.0f }

    companion object {
        val DEFAULT = MorphParameters()
    }
}

/**
 * Enum representing face regions for targeted modifications.
 * Region matching is dynamically inferred from blendshape names at runtime.
 * Keywords cover common naming conventions: ARKit, Faceware, VRoid, custom, etc.
 * When loading a new model, blendshapes are automatically categorized by these patterns.
 */
enum class FaceRegion(val displayName: String, val keywords: List<String>) {
    // Keywords matched case-insensitively against blendshape names
    // Covers: ARKit (eyeBlink_L), Faceware (Eye_Blink), VRoid (Fcl_EYE_Close), generic, etc.
    EYES("Eyes", listOf(
        "eye", "brow", "squint", "blink", "wink", "lash", "lid", "pupil", "gaze", "look",
        "fcl_eye", "fcl_brow"  // VRoid naming
    )),
    NOSE("Nose", listOf(
        "nose", "sneer", "nostril", "nasal", "naris", "bridge",
        "fcl_nose"  // VRoid naming
    )),
    JAW_CHIN("Jaw & Chin", listOf(
        "jaw", "chin", "mandible", "chew", "clench",
        "fcl_jaw", "fcl_chin"  // VRoid naming
    )),
    CHEEKS("Cheeks", listOf(
        "cheek", "puff", "blow", "inflate", "balloon",
        "fcl_cheek"  // VRoid naming
    )),
    MOUTH_LIPS("Mouth & Lips", listOf(
        "mouth", "lip", "smile", "frown", "dimple", "stretch", "funnel", "pucker",
        "kiss", "press", "roll", "shrug", "tight", "tongue",
        "fcl_mth", "fcl_mouth"  // VRoid naming
    )),
    FOREHEAD("Forehead", listOf(
        "forehead", "brow", "glabella",
        "fcl_forehead"  // VRoid naming
    )),
    FACE_SHAPE("Face Shape", listOf(
        "head", "base", "face", "skull", "scale", "morph", "shape", "wide", "narrow",
        "fcl_face", "fcl_all"  // VRoid naming
    )),
    ALL("All Features", emptyList());

    /**
     * Check if a blendshape name matches this region based on keywords.
     * Uses case-insensitive partial matching to support any naming convention.
     */
    fun matchesBlendShape(name: String): Boolean {
        if (this == ALL) return true
        val lower = name.lowercase()
        return keywords.any { keyword -> lower.contains(keyword) }
    }

    companion object {
        fun fromDisplayName(name: String): FaceRegion? =
            entries.find { it.displayName.equals(name, ignoreCase = true) }

        /**
         * Given a list of blendshape names, group them by region.
         * Blendshapes are dynamically categorized based on their names.
         * Works with any model - ARKit, Faceware, VRoid, custom naming, etc.
         */
        fun groupBlendShapes(names: List<String>): Map<FaceRegion, List<String>> {
            val groups = mutableMapOf<FaceRegion, MutableList<String>>()
            for (name in names) {
                // Find the first matching region (excluding ALL)
                val region = entries
                    .filter { it != ALL }
                    .firstOrNull { it.matchesBlendShape(name) }
                    ?: FACE_SHAPE // default group if no keyword matches
                groups.getOrPut(region) { mutableListOf() }.add(name)
            }
            return groups
        }

        /**
         * Analyze blendshape names and return only regions that have matches.
         * Useful for dynamically showing only relevant UI options.
         */
        fun getAvailableRegions(blendShapeNames: List<String>): List<FaceRegion> {
            val grouped = groupBlendShapes(blendShapeNames)
            // Always include ALL, plus any regions that have matches
            return listOf(ALL) + entries.filter { it != ALL && grouped.containsKey(it) }
        }

        /**
         * Get blendshapes that match a specific region.
         * Returns empty list if no matches found.
         */
        fun getBlendShapesForRegion(region: FaceRegion, allNames: List<String>): List<String> {
            if (region == ALL) return allNames
            return allNames.filter { region.matchesBlendShape(it) }
        }
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
