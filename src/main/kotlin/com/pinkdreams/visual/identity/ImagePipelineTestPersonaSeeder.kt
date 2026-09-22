package com.pinkdreams.visual.identity

import com.pinkdreams.persistence.repositories.PersonaRepository
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Seeds Ananya + Richa image-pipeline test fixtures from
 * `docs/23 sept/persona testing data/`.
 *
 * Idempotent by slug: if persona exists, refreshes draft visual identity + refs.
 */
class ImagePipelineTestPersonaSeeder(
    private val personaRepository: PersonaRepository,
    private val visualAdminService: PersonaVisualAdminService,
    private val docsRoot: Path = Path.of("docs/23 sept/persona testing data"),
) {
    data class SeedResult(
        val slug: String,
        val personaId: String,
        val draftVersion: Int,
        val referencesUploaded: Int,
        val created: Boolean,
    )

    fun seedAll(): List<SeedResult> = listOf(seedAnanya(), seedRicha())

    fun seedAnanya(): SeedResult = seedPersona(
        slug = "ananya_rajput",
        displayName = "Ananya",
        gender = "female",
        apparentAge = 25,
        guide = PhysicalGuide(
            agePresentation = AgePresentation(25, true),
            face = Face(faceShape = "softly oval", jawline = "soft moderate", cheekbones = "naturally soft", nose = "medium balanced", lips = "medium natural pink"),
            eyes = Eyes(color = "dark brown", shape = "almond", eyebrows = "dark brown medium naturally defined"),
            hair = Hair(color = "very dark brown near-black", length = "long", texture = "naturally straight", style = "loose"),
            skin = Skin(tone = "medium warm Indian", texture = "natural"),
            body = Body(build = "soft slim-average", proportions = "realistic", overallDescription = "naturally proportioned adult woman"),
            anatomy = Anatomy(shoulders = "natural", waist = "natural", hips = "natural"),
            distinctiveFeatures = listOf("canonical face must remain consistent across generations"),
            appearanceConstraints = listOf(
                "do not redesign facial structure",
                "do not change eye shape, skin tone, nose, or lips",
            ),
            notes = "Test fixture Ananya Rajput — Noida AI creator",
        ),
        privateGuide = PrivateVisualGuide(),
        styleConstraints = """{"clothing":["casual Indian","contemporary","minimal","feminine creator casual"]}""",
        picsDir = docsRoot.resolve("anayna pics"),
    )

    fun seedRicha(): SeedResult = seedPersona(
        slug = "richa_mehta",
        displayName = "Richa",
        gender = "female",
        apparentAge = 27,
        guide = PhysicalGuide(
            agePresentation = AgePresentation(27, true),
            face = Face(faceShape = "oval", jawline = "defined soft", lips = "full natural"),
            eyes = Eyes(color = "brown", shape = "almond"),
            hair = Hair(color = "dark brown", length = "long", style = "open waves or straight"),
            skin = Skin(tone = "fair to light-medium Indian"),
            body = Body(build = "slim", proportions = "balanced", overallDescription = "adult woman — white-top reference is canonical"),
            distinctiveFeatures = listOf("canonical face from richa reference set"),
            appearanceConstraints = listOf("preserve face identity across scenes"),
            notes = "Test fixture Richa — white-top image is primary face reference",
        ),
        privateGuide = PrivateVisualGuide(),
        styleConstraints = """{"clothing":["contemporary casual","white top baseline allowed to vary"]}""",
        picsDir = docsRoot.resolve("richa pics"),
    )

    private fun seedPersona(
        slug: String,
        displayName: String,
        gender: String,
        apparentAge: Int,
        guide: PhysicalGuide,
        privateGuide: PrivateVisualGuide,
        styleConstraints: String,
        picsDir: Path,
    ): SeedResult {
        val existing = personaRepository.findAll().firstOrNull { it.slug == slug }
        val created: Boolean
        val persona = if (existing != null) {
            created = false
            existing
        } else {
            created = true
            personaRepository.create(
                slug = slug,
                displayName = displayName,
                gender = gender,
                orientation = "straight",
                apparentAge = apparentAge,
                languageProfile = mapOf("primary" to "Hindi", "secondary" to "English"),
            )
        }

        val ensure = visualAdminService.ensureDraft(persona.id, author = "image-pipeline-seeder")
        visualAdminService.updatePhysicalGuide(persona.id, guide)
        visualAdminService.updatePrivateGuide(persona.id, privateGuide)
        visualAdminService.updateStyleConstraints(persona.id, styleConstraints)

        var uploaded = 0
        val roleOrder = listOf(
            ReferenceRole.FACE_CLOSE,
            ReferenceRole.FRONT,
            ReferenceRole.LEFT_PROFILE,
            ReferenceRole.RIGHT_PROFILE,
            ReferenceRole.BACK,
        )
        val files = listPngs(picsDir)
        files.forEachIndexed { index, file ->
            val role = roleOrder.getOrElse(index) { ReferenceRole.OTHER }
            val bytes = Files.readAllBytes(file.toPath())
            val contentType = when {
                file.name.endsWith(".png", true) -> "image/png"
                file.name.endsWith(".jpg", true) || file.name.endsWith(".jpeg", true) -> "image/jpeg"
                file.name.endsWith(".webp", true) -> "image/webp"
                else -> "image/png"
            }
            visualAdminService.uploadReference(
                personaId = persona.id,
                role = role,
                content = bytes,
                contentType = contentType,
                notes = "Seeded from ${file.name}",
                replaceSlot = role.isStandardIdentitySlot(),
                finalize = true,
            )
            uploaded++
        }

        visualAdminService.publishAndActivateDraft(persona.id)

        return SeedResult(
            slug = slug,
            personaId = persona.id.toString(),
            draftVersion = ensure.draftVersion,
            referencesUploaded = uploaded,
            created = created,
        )
    }

    private fun listPngs(dir: Path): List<File> {
        val f = dir.toFile()
        if (!f.isDirectory) return emptyList()
        return f.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }
}
