package com.pinkdreams.visual.identity

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AgePresentation(
    val apparentAge: Int,
    val adult: Boolean = true
)

@Serializable
data class Face(
    val faceShape: String? = null,
    val jawline: String? = null,
    val cheekbones: String? = null,
    val nose: String? = null,
    val lips: String? = null
)

@Serializable
data class Eyes(
    val color: String? = null,
    val shape: String? = null,
    val size: String? = null,
    val eyebrows: String? = null
)

@Serializable
data class Hair(
    val color: String? = null,
    val length: String? = null,
    val texture: String? = null,
    val style: String? = null
)

@Serializable
data class Skin(
    val tone: String? = null,
    val undertone: String? = null,
    val texture: String? = null
)

@Serializable
data class Body(
    val height: String? = null,
    val build: String? = null,
    val proportions: String? = null
)

@Serializable
data class Anatomy(
    val shoulders: String? = null,
    val waist: String? = null,
    val hips: String? = null,
    val legs: String? = null
)

@Serializable
data class PhysicalGuide(
    val agePresentation: AgePresentation,
    val face: Face = Face(),
    val eyes: Eyes = Eyes(),
    val hair: Hair = Hair(),
    val skin: Skin = Skin(),
    val body: Body = Body(),
    val anatomy: Anatomy = Anatomy(),
    val distinctiveFeatures: List<String> = emptyList(),
    val appearanceConstraints: List<String> = emptyList(),
    val notes: String? = null
) {
    fun validate(): ValidationResult {
        val errors = mutableListOf<String>()

        if (!agePresentation.adult) {
            errors.add("adult flag must be true (all personas are fictional adults)")
        }

        if (agePresentation.apparentAge < 18 || agePresentation.apparentAge > 100) {
            errors.add("apparent_age must be between 18 and 100")
        }

        if (errors.isNotEmpty()) {
            return ValidationResult(valid = false, errors = errors)
        }

        return ValidationResult(valid = true, errors = emptyList())
    }
}

data class ValidationResult(
    val valid: Boolean,
    val errors: List<String> = emptyList()
)
