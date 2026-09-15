package com.pinkdreams.imaging.compiler

import com.pinkdreams.imaging.provider.GenerationRequest
import com.pinkdreams.imaging.provider.ReferenceInput
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import com.pinkdreams.visual.identity.PhysicalGuide
import kotlinx.serialization.json.Json
import java.util.UUID

class PromptCompiler {
    private val json = Json { ignoreUnknownKeys = true }

    fun compile(
        sceneIntent: SceneIntent,
        visualVersion: PersonaVisualVersionRepository.PersonaVisualVersion,
        idempotencyKey: String
    ): GenerationRequest {
        val validation = sceneIntent.validate()
        require(validation.valid) { "Invalid scene intent: ${validation.errors.joinToString("; ")}" }

        val physicalGuide = parsePhysicalGuide(visualVersion.physicalGuide)
        val prompt = buildPrompt(sceneIntent, physicalGuide)

        val references = sceneIntent.generation.selectedReferences.mapIndexed { index, refId ->
            ReferenceInput(
                referenceImageId = refId,
                role = determineReferenceRole(index),
                weight = 1.0f
            )
        }

        return GenerationRequest(
            prompt = prompt,
            references = references,
            candidateCount = sceneIntent.generation.candidateCount,
            widthPx = sceneIntent.generation.widthPx,
            heightPx = sceneIntent.generation.heightPx,
            aspectRatio = sceneIntent.generation.aspectRatio,
            idempotencyKey = idempotencyKey,
            clientMetadata = buildMetadata(sceneIntent)
        )
    }

    private fun buildPrompt(sceneIntent: SceneIntent, physicalGuide: PhysicalGuide?): String {
        val sections = mutableListOf<String>()

        // Subject with identity priority
        val subjectSection = buildSubjectSection(sceneIntent.subject, physicalGuide)
        if (subjectSection.isNotEmpty()) sections.add(subjectSection)

        // Appearance
        val appearanceSection = buildAppearanceSection(sceneIntent.appearance)
        if (appearanceSection.isNotEmpty()) sections.add(appearanceSection)

        // Environment
        val environmentSection = buildEnvironmentSection(sceneIntent.environment)
        if (environmentSection.isNotEmpty()) sections.add(environmentSection)

        // Composition
        val compositionSection = buildCompositionSection(sceneIntent.composition)
        if (compositionSection.isNotEmpty()) sections.add(compositionSection)

        // Mood and Style
        val styleSection = buildMoodAndStyleSection(sceneIntent.moodAndStyle)
        if (styleSection.isNotEmpty()) sections.add(styleSection)

        return sections.joinToString(" ")
    }

    private fun buildSubjectSection(subject: Subject, physicalGuide: PhysicalGuide?): String {
        val parts = mutableListOf<String>()

        if (subject.identity != null) {
            parts.add(subject.identity)
        }

        // Physical guide provides authoritative identity
        if (physicalGuide != null) {
            val physicalDescription = describePhysicalGuide(physicalGuide)
            if (physicalDescription.isNotEmpty()) {
                parts.add("Physical description: $physicalDescription")
            }
        }

        if (subject.presentation != null) {
            parts.add(subject.presentation)
        }

        if (subject.pose != null) {
            parts.add("Pose: ${subject.pose.name.lowercase().replace("_", " ")}")
        }

        if (subject.expression != null) {
            parts.add("Expression: ${subject.expression}")
        }

        if (subject.framing != null) {
            parts.add("Framing: ${subject.framing.name.lowercase().replace("_", " ")}")
        }

        return parts.joinToString(". ").ifEmpty { "" }
    }

    private fun buildAppearanceSection(appearance: Appearance): String {
        val parts = mutableListOf<String>()

        if (appearance.outfit != null) {
            parts.add("Outfit: ${appearance.outfit}")
        }

        if (appearance.hairstyle != null) {
            parts.add("Hairstyle: ${appearance.hairstyle}")
        }

        if (appearance.makeup != null) {
            parts.add("Makeup: ${appearance.makeup}")
        }

        if (appearance.accessories.isNotEmpty()) {
            parts.add("Accessories: ${appearance.accessories.joinToString(", ")}")
        }

        return parts.joinToString(". ").ifEmpty { "" }
    }

    private fun buildEnvironmentSection(environment: Environment): String {
        val parts = mutableListOf<String>()

        if (environment.location != null) {
            parts.add("Location: ${environment.location}")
        }

        if (environment.background != null) {
            parts.add("Background: ${environment.background}")
        }

        if (environment.season != null) {
            parts.add("Season: ${environment.season.name.lowercase()}")
        }

        if (environment.weather != null) {
            parts.add("Weather: ${environment.weather.name.lowercase().replace("_", " ")}")
        }

        if (environment.time != null) {
            parts.add("Time of day: ${environment.time}")
        }

        return parts.joinToString(". ").ifEmpty { "" }
    }

    private fun buildCompositionSection(composition: Composition): String {
        val parts = mutableListOf<String>()

        if (composition.cameraDistance != null) {
            parts.add("Camera distance: ${composition.cameraDistance.name.lowercase().replace("_", " ")}")
        }

        if (composition.cameraAngle != null) {
            parts.add("Camera angle: ${composition.cameraAngle.name.lowercase().replace("_", " ")}")
        }

        if (composition.orientation != null) {
            parts.add("Orientation: ${composition.orientation.name.lowercase()}")
        }

        if (composition.focalEmphasis != null) {
            parts.add("Focal emphasis: ${composition.focalEmphasis.name.lowercase().replace("_", " ")}")
        }

        return parts.joinToString(". ").ifEmpty { "" }
    }

    private fun buildMoodAndStyleSection(moodAndStyle: MoodAndStyle): String {
        val parts = mutableListOf<String>()

        if (moodAndStyle.mood != null) {
            parts.add("Mood: ${moodAndStyle.mood.name.lowercase()}")
        }

        if (moodAndStyle.lighting != null) {
            parts.add("Lighting: ${moodAndStyle.lighting.name.lowercase().replace("_", " ")}")
        }

        if (moodAndStyle.atmosphere != null) {
            parts.add("Atmosphere: ${moodAndStyle.atmosphere}")
        }

        if (moodAndStyle.visualStyle != null) {
            parts.add("Visual style: ${moodAndStyle.visualStyle.name.lowercase().replace("_", " ")}")
        }

        if (moodAndStyle.realismLevel != null) {
            parts.add("Realism: ${moodAndStyle.realismLevel.name.lowercase().replace("_", " ")}")
        }

        return parts.joinToString(". ").ifEmpty { "" }
    }

    private fun describePhysicalGuide(guide: PhysicalGuide): String {
        val parts = mutableListOf<String>()

        parts.add("Age ${guide.agePresentation.apparentAge}")

        if (guide.face.faceShape != null) {
            parts.add("face shape: ${guide.face.faceShape}")
        }

        if (guide.eyes.color != null || guide.eyes.shape != null) {
            val eyeDesc = listOfNotNull(
                guide.eyes.color?.let { "$it eyes" },
                guide.eyes.shape?.let { "shape: $it" }
            ).joinToString(", ")
            if (eyeDesc.isNotEmpty()) parts.add(eyeDesc)
        }

        if (guide.hair.color != null || guide.hair.length != null) {
            val hairDesc = listOfNotNull(
                guide.hair.color,
                guide.hair.length?.let { "$it hair" }
            ).joinToString(" ")
            if (hairDesc.isNotEmpty()) parts.add(hairDesc)
        }

        if (guide.skin.tone != null) {
            parts.add("${guide.skin.tone} skin")
        }

        if (guide.body.build != null) {
            parts.add("${guide.body.build} build")
        }

        if (guide.distinctiveFeatures.isNotEmpty()) {
            parts.add("distinctive: ${guide.distinctiveFeatures.joinToString(", ")}")
        }

        return parts.joinToString(", ")
    }

    private fun determineReferenceRole(index: Int): String {
        return when (index) {
            0 -> "FACE"
            1 -> "BODY"
            2 -> "STYLE"
            else -> "GENERAL_IDENTITY"
        }
    }

    private fun buildMetadata(sceneIntent: SceneIntent): Map<String, String> {
        return mapOf(
            "subject_pose" to (sceneIntent.subject.pose?.name ?: "unspecified"),
            "framing" to (sceneIntent.subject.framing?.name ?: "unspecified"),
            "camera_distance" to (sceneIntent.composition.cameraDistance?.name ?: "unspecified"),
            "camera_angle" to (sceneIntent.composition.cameraAngle?.name ?: "unspecified"),
            "mood" to (sceneIntent.moodAndStyle.mood?.name ?: "unspecified"),
            "lighting" to (sceneIntent.moodAndStyle.lighting?.name ?: "unspecified"),
            "visual_style" to (sceneIntent.moodAndStyle.visualStyle?.name ?: "unspecified"),
            "realism_level" to (sceneIntent.moodAndStyle.realismLevel?.name ?: "unspecified"),
            "environment_location" to (sceneIntent.environment.location ?: "unspecified"),
            "environment_weather" to (sceneIntent.environment.weather?.name ?: "unspecified")
        )
    }

    private fun parsePhysicalGuide(physicalGuideJson: String): PhysicalGuide? {
        return try {
            if (physicalGuideJson.isBlank() || physicalGuideJson == "{}") {
                null
            } else {
                json.decodeFromString<PhysicalGuide>(physicalGuideJson)
            }
        } catch (e: Exception) {
            null
        }
    }
}
