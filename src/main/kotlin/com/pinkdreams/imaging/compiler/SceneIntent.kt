package com.pinkdreams.imaging.compiler

import java.util.UUID

enum class Pose {
    STANDING, SITTING, RECLINING, WALKING, DANCING, LEANING, LYING, KNEELING, CROUCHING
}

enum class FramingType {
    CLOSE_UP, HEADSHOT, SHOULDERS_UP, TORSO, WAIST_UP, THREE_QUARTER_BODY, FULL_BODY, WIDE_SHOT
}

enum class CameraDistance {
    EXTREME_CLOSE_UP, CLOSE_UP, MEDIUM_CLOSE, MEDIUM, MEDIUM_WIDE, WIDE, EXTREME_WIDE
}

enum class CameraAngle {
    HIGH_ANGLE, EYE_LEVEL, LOW_ANGLE, WORM_EYE, BIRD_EYE
}

enum class FrameOrientation {
    PORTRAIT, LANDSCAPE, SQUARE
}

enum class FocalEmphasis {
    SUBJECT, FACE, EYES, HANDS, BODY, ENVIRONMENT, BALANCED
}

enum class Season {
    SPRING, SUMMER, AUTUMN, WINTER
}

enum class Weather {
    CLEAR, PARTLY_CLOUDY, CLOUDY, OVERCAST, RAINY, STORMY, FOGGY, SNOWY
}

enum class LightingStyle {
    NATURAL_SUNLIGHT, SOFT_DIFFUSED, HARSH_DRAMATIC, WARM_GOLDEN, COOL_BLUE, CINEMATIC, STUDIO, CANDLELIGHT, NEON
}

enum class Atmosphere {
    INTIMATE, PROFESSIONAL, CASUAL, FORMAL, ROMANTIC, PLAYFUL, MYSTERIOUS, ENERGETIC, CALM, DRAMATIC
}

enum class VisualStyle {
    PHOTOREALISTIC, ILLUSTRATION, PAINTING, SKETCH, COMIC, ANIME, WATERCOLOR, OIL_PAINTING, DIGITAL_ART
}

enum class RealismLevel {
    HYPER_REALISTIC, PHOTOREALISTIC, REALISTIC, SEMI_REALISTIC, STYLIZED, HIGHLY_STYLIZED, ABSTRACT
}

data class Subject(
    val identity: String? = null,
    val presentation: String? = null,
    val pose: Pose? = null,
    val expression: String? = null,
    val framing: FramingType? = null
)

data class Environment(
    val location: String? = null,
    val time: String? = null,
    val weather: Weather? = null,
    val season: Season? = null,
    val background: String? = null
)

data class Composition(
    val cameraDistance: CameraDistance? = null,
    val cameraAngle: CameraAngle? = null,
    val orientation: FrameOrientation? = null,
    val focalEmphasis: FocalEmphasis? = null
)

data class Appearance(
    val outfit: String? = null,
    val accessories: List<String> = emptyList(),
    val hairstyle: String? = null,
    val makeup: String? = null
)

data class MoodAndStyle(
    val mood: Atmosphere? = null,
    val lighting: LightingStyle? = null,
    val atmosphere: String? = null,
    val visualStyle: VisualStyle? = null,
    val realismLevel: RealismLevel? = null
)

data class GenerationSpecs(
    val candidateCount: Int = 1,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val aspectRatio: String? = null,
    val selectedReferences: List<UUID> = emptyList()
)

data class SceneIntent(
    val subject: Subject = Subject(),
    val environment: Environment = Environment(),
    val composition: Composition = Composition(),
    val appearance: Appearance = Appearance(),
    val moodAndStyle: MoodAndStyle = MoodAndStyle(),
    val generation: GenerationSpecs = GenerationSpecs(),
    val seedPrompt: String? = null,
) {
    fun validate(): ValidationResult {
        val errors = mutableListOf<String>()

        if (generation.candidateCount < 1) {
            errors.add("candidateCount must be at least 1")
        }
        if (generation.candidateCount > 100) {
            errors.add("candidateCount must be at most 100")
        }

        if (generation.widthPx != null && generation.widthPx < 64) {
            errors.add("widthPx must be at least 64 if provided")
        }
        if (generation.widthPx != null && generation.widthPx > 4096) {
            errors.add("widthPx must be at most 4096 if provided")
        }

        if (generation.heightPx != null && generation.heightPx < 64) {
            errors.add("heightPx must be at least 64 if provided")
        }
        if (generation.heightPx != null && generation.heightPx > 4096) {
            errors.add("heightPx must be at most 4096 if provided")
        }

        if (generation.aspectRatio != null && generation.aspectRatio.isBlank()) {
            errors.add("aspectRatio cannot be blank if provided")
        }

        return ValidationResult(errors.isEmpty(), errors)
    }

    data class ValidationResult(
        val valid: Boolean,
        val errors: List<String> = emptyList()
    )
}
