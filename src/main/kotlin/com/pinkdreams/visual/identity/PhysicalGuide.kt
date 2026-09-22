package com.pinkdreams.visual.identity

import kotlinx.serialization.Serializable

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
    val weight: String? = null,
    val build: String? = null,
    val muscularity: String? = null,
    val proportions: String? = null,
    val overallDescription: String? = null
)

@Serializable
data class Anatomy(
    /** Gender-neutral chest / bust description (size, shape, etc.). */
    val chest: String? = null,
    val shoulders: String? = null,
    val waist: String? = null,
    val hips: String? = null,
    val belly: String? = null,
    val legs: String? = null,
    val other: String? = null
)

@Serializable
data class DistinguishingMarks(
    val birthmarks: List<String> = emptyList(),
    val moles: List<String> = emptyList(),
    val scars: List<String> = emptyList(),
    val tattoos: List<String> = emptyList(),
    val other: List<String> = emptyList(),
) {
    fun asFlatList(): List<String> {
        val out = mutableListOf<String>()
        birthmarks.forEach { out.add("birthmark: $it") }
        moles.forEach { out.add("mole: $it") }
        scars.forEach { out.add("scar: $it") }
        tattoos.forEach { out.add("tattoo: $it") }
        other.forEach { out.add(it) }
        return out
    }

    fun isEmpty(): Boolean =
        birthmarks.isEmpty() && moles.isEmpty() && scars.isEmpty() && tattoos.isEmpty() && other.isEmpty()
}

@Serializable
data class PhysicalGuide(
    val agePresentation: AgePresentation,
    val face: Face = Face(),
    val eyes: Eyes = Eyes(),
    val hair: Hair = Hair(),
    val skin: Skin = Skin(),
    val body: Body = Body(),
    val anatomy: Anatomy = Anatomy(),
    /** Free-form distinguishing features (legacy + ad-hoc). Prefer [marks] when structured. */
    val distinctiveFeatures: List<String> = emptyList(),
    val marks: DistinguishingMarks = DistinguishingMarks(),
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
