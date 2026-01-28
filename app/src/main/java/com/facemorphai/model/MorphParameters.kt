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
 * Region matching is inferred from blendshape names at runtime.
 */
enum class FaceRegion(val displayName: String, val keywords: List<String>) {
    EYES("Eyes", listOf("eye", "brow", "lid", "lash", "pupil")),
    NOSE("Nose", listOf("nose", "nostril", "nasal", "bridge")),
    JAW_CHIN("Jaw & Chin", listOf("jaw", "chin", "mandible")),
    CHEEKS("Cheeks", listOf("cheek")),
    MOUTH_LIPS("Mouth & Lips", listOf("mouth", "lip", "smile", "frown")),
    FOREHEAD("Forehead", listOf("forehead", "brow")),
    FACE_SHAPE("Face Shape", listOf("face", "head", "skull", "scale")),
    ALL("All Features", emptyList());

    /**
     * Check if a blendshape name matches this region based on keywords.
     */
    fun matchesBlendShape(name: String): Boolean {
        if (this == ALL) return true
        val lower = name.lowercase()
        return keywords.any { lower.contains(it) }
    }

    companion object {
        fun fromDisplayName(name: String): FaceRegion? =
            entries.find { it.displayName.equals(name, ignoreCase = true) }

        /**
         * Given a list of blendshape names, group them by region.
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
