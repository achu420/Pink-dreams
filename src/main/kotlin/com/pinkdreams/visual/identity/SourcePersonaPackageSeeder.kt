package com.pinkdreams.visual.identity

import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.ReferenceImageRepository
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.streams.toList

/**
 * Seeds Aanya (Anaya), Zoya, and Pihu from `docs/personas/{Aanya,Zoya,Pihu}`.
 * Idempotent by logical slug. Does not invent source fields.
 */
class SourcePersonaPackageSeeder(
    private val personaRepository: PersonaRepository,
    private val coreVersionRepository: PersonaCoreVersionRepository,
    private val visualAdminService: PersonaVisualAdminService,
    private val referenceImageRepository: ReferenceImageRepository,
    private val docsRoot: Path = Path.of("docs/personas"),
) {
    data class ImageRecord(
        val filename: String,
        val width: Int?,
        val height: Int?,
        val role: String,
        val usable: Boolean,
        val notes: String,
    )

    data class SeedResult(
        val slug: String,
        val displayName: String,
        val personaId: String,
        val created: Boolean,
        val coreVersionId: String?,
        val visualVersionId: String?,
        val visualVersionNumber: Int?,
        val imagesFound: Int,
        val imagesUsable: Int,
        val imagesInvalid: Int,
        val imageRecords: List<ImageRecord>,
        val fieldsPopulated: List<String>,
    )

    fun seedAll(): List<SeedResult> = listOf(seedAnaya(), seedZoya(), seedPihu())

    fun seedAnaya(): SeedResult = seedPackage(
        logicalSlug = "anaya",
        aliasSlugs = listOf("anaya", "aanya", "aanya_deshpande"),
        displayName = "Aanya",
        gender = "female",
        orientation = "heterosexual",
        apparentAge = 26,
        city = "Pune, Maharashtra (Kothrud)",
        occupation = "UX Designer",
        bio = "Warm, observant UX designer from Nashik living in Pune. Adult (26). Reliable, self-protective, and quietly proud of being needed.",
        interests = "books, chai, design, walking, plants, series",
        tags = listOf("adult", "indian", "marathi", "ux-designer", "pune"),
        languages = mapOf("primary" to "Marathi", "secondary" to "Hindi", "tertiary" to "English"),
        packageDir = docsRoot.resolve("Aanya"),
        markdownName = "Aanya Deshpande.md",
        guide = PhysicalGuide(
            agePresentation = AgePresentation(26, true),
            face = Face(faceShape = "Round, slightly oval", jawline = "Soft, rounded", cheekbones = "Not prominent, soft", nose = "Small, straight, slightly rounded tip", lips = "Full, natural shape"),
            eyes = Eyes(color = "Dark brown, nearly black", shape = "Large, almond, expressive", eyebrows = "Naturally thick, dark, slightly arched"),
            hair = Hair(color = "Dark brown, almost black", length = "Mid-back, usually tied", texture = "Thick, slightly wavy", style = "Loose bun for work; down when she has made an effort"),
            skin = Skin(tone = "Medium-brown", undertone = "warm", texture = "Clear, occasional small acne, natural texture"),
            body = Body(height = "163 cm", weight = "56 kg", build = "Soft, natural, average", muscularity = "Low", proportions = "Average frame, slight leg dominance", overallDescription = "Natural, untoned, approachable adult woman"),
            anatomy = Anatomy(chest = "86 cm bust / 74 cm underbust", shoulders = "Average width, soft, natural slope", waist = "68 cm, slight definition, soft", hips = "92 cm, average", belly = "Natural, untoned. Not flat, not prominent", legs = "Average length, natural tone"),
            distinctiveFeatures = listOf("small mole on left jaw near ear", "silver studs in both ears", "thin silver ring on right hand", "faint small scar on right knee"),
            marks = DistinguishingMarks(
                moles = listOf("small mole on left jaw, near ear, approximately 1.5 cm below earlobe"),
                scars = listOf("faint small scar on right knee from childhood fall"),
            ),
            appearanceConstraints = listOf("do not redesign facial structure", "preserve mole on left jaw", "do not change medium-brown warm skin"),
            notes = "Source: docs/personas/Aanya/Aanya Deshpande.md visual_identity",
        ),
        styleConstraints = """{"clothing":["oversized tees","jeans","track pants","kurtis","sneakers","flat sandals"],"colors":["mustard","deep green","off-white","black"],"favorite":"mustard kurta, grey hoodie","makeup":"minimal kajal"}""",
        imageRoles = mapOf(
            "ChatGPT Image Sep 23, 2026, 07_57_06 PM.png" to ReferenceRole.FACE_CLOSE,
            "ChatGPT Image Sep 23, 2026, 08_00_05 PM.png" to ReferenceRole.FRONT,
            "ChatGPT Image Sep 23, 2026, 08_00_15 PM.png" to ReferenceRole.OTHER,
            "ChatGPT Image Sep 23, 2026, 08_00_23 PM.png" to ReferenceRole.LEFT_PROFILE,
            "ChatGPT Image Sep 23, 2026, 08_01_32 PM.png" to ReferenceRole.OTHER,
            "ChatGPT Image Sep 23, 2026, 08_03_41 PM.png" to ReferenceRole.OTHER,
            "ChatGPT Image Sep 23, 2026, 08_04_45 PM.png" to ReferenceRole.OTHER,
        ),
        imageNotes = mapOf(
            "ChatGPT Image Sep 23, 2026, 08_00_15 PM.png" to "Waist-up three-quarter; schema has no THREE_QUARTER role — stored as OTHER",
            "ChatGPT Image Sep 23, 2026, 08_01_32 PM.png" to "Lifestyle desk scene — OTHER (no LIFESTYLE role)",
            "ChatGPT Image Sep 23, 2026, 08_03_41 PM.png" to "Detail collage (eyes/lips/ear/wrist tattoo/bun) — OTHER",
            "ChatGPT Image Sep 23, 2026, 08_04_45 PM.png" to "Front/side/back composite — OTHER to preserve dedicated FRONT slot",
        ),
        extraFields = listOf("slug", "displayName", "gender", "orientation", "apparentAge", "bio", "city", "occupation", "interests", "tags", "languageProfile", "persona core markdown", "physical guide", "style constraints", "reference images"),
    )

    fun seedZoya(): SeedResult = seedPackage(
        logicalSlug = "zoya",
        aliasSlugs = listOf("zoya", "zoya_fatima"),
        displayName = "Zoya",
        gender = "female",
        orientation = "heterosexual",
        apparentAge = 25, // schema personas_apparent_age_check requires >= 25; authored source age is 24
        city = "Hyderabad, Telangana (Gachibowli)",
        occupation = "Contemporary dancer and dance teacher",
        bio = "Fast, warm contemporary dancer from Hyderabad's old city. Adult (24). Chaotic on the surface, rigorous underneath.",
        interests = "contemporary dance, aerial silks, teaching, movement",
        tags = listOf("adult", "indian", "deccani-muslim", "dancer", "hyderabad"),
        languages = mapOf("primary" to "Hindi", "secondary" to "Urdu", "tertiary" to "Telugu", "other" to "English"),
        packageDir = docsRoot.resolve("Zoya"),
        markdownName = "Zoya.md",
        guide = PhysicalGuide(
            agePresentation = AgePresentation(24, true),
            face = Face(faceShape = "Triangular, wide at temples, tapering to a pointed chin", jawline = "Strong, defined, angular", cheekbones = "Wide, prominent, high", nose = "Straight, prominent, small nose hoop on the right nostril", lips = "Full, natural, warm-toned"),
            eyes = Eyes(color = "Very dark brown with amber/gold flecks visible in sunlight", shape = "Large, almond, slightly upturned, expressive", eyebrows = "Strong, dark, thick, natural, slightly unshaped"),
            hair = Hair(color = "Natural dark brown, dyed platinum-blonde on top with darker roots", length = "Short-cropped pixie with a shaved right side", texture = "Thick, straight", style = "Pixie with undercut; messy, often unstyled"),
            skin = Skin(tone = "Rich warm brown", undertone = "golden", texture = "Clear, healthy, occasional bruise from training"),
            body = Body(height = "160 cm", weight = "53 kg", build = "Compact, muscular, athletic", muscularity = "High — visible muscle definition throughout", proportions = "Shoulder-dominant rectangle-athletic", overallDescription = "Compact and physically powerful adult dancer"),
            anatomy = Anatomy(chest = "82 cm bust / 72 cm underbust", shoulders = "37 cm, muscular, broader than hips", waist = "63 cm, minimal definition", hips = "87 cm", legs = "Defined from dance and aerial training"),
            distinctiveFeatures = listOf("bleached platinum pixie with shaved right side", "small silver nose hoop on the right nostril", "small geometric tattoo on inner left wrist (source); reference photos also show a fine-line floral on the left upper arm", "small gold hoop in each ear", "small scar on left knee from ACL surgery"),
            marks = DistinguishingMarks(
                scars = listOf("small scar on the left knee from ACL reconstruction surgery at 20"),
                tattoos = listOf("small fine-line geometric design on the inner left wrist — six intersecting lines forming an abstract shape"),
            ),
            appearanceConstraints = listOf("preserve platinum pixie and shaved right side", "preserve right-nostril hoop", "do not soften athletic muscularity"),
            notes = "Source: docs/personas/Zoya/Zoya.md visual_identity",
        ),
        styleConstraints = """{"clothing":["sports bra","joggers","dancewear","athletic"],"markers":["platinum pixie","right nose hoop"]}""",
        imageRoles = mapOf(
            "ChatGPT Image Sep 23, 2026, 08_22_32 PM.png" to ReferenceRole.FACE_CLOSE,
            "ChatGPT Image Sep 23, 2026, 08_23_23 PM.png" to ReferenceRole.FRONT,
            "ChatGPT Image Sep 23, 2026, 08_25_31 PM.png" to ReferenceRole.FULL_BODY,
            "ChatGPT Image Sep 23, 2026, 08_27_04 PM.png" to ReferenceRole.OTHER,
            "ChatGPT Image Sep 23, 2026, 08_30_29 PM.png" to ReferenceRole.LEFT_PROFILE,
            "ChatGPT Image Sep 23, 2026, 08_33_30 PM.png" to ReferenceRole.BACK,
            "ChatGPT Image Sep 23, 2026, 08_36_43 PM.png" to ReferenceRole.OTHER,
        ),
        imageNotes = mapOf(
            "ChatGPT Image Sep 23, 2026, 08_25_31 PM.png" to "Second full-front in shorts — FULL_BODY so FRONT slot stays the joggers shot",
            "ChatGPT Image Sep 23, 2026, 08_27_04 PM.png" to "Studio lifestyle sit — OTHER (no LIFESTYLE role)",
            "ChatGPT Image Sep 23, 2026, 08_30_29 PM.png" to "Over-shoulder left profile",
            "ChatGPT Image Sep 23, 2026, 08_36_43 PM.png" to "Beach lifestyle — OTHER",
        ),
        extraFields = listOf("slug", "displayName", "gender", "orientation", "apparentAge(schema=25, authored=24)", "bio", "city", "occupation", "interests", "tags", "languageProfile", "persona core markdown", "physical guide", "style constraints", "reference images"),
    )

    fun seedPihu(): SeedResult = seedPackage(
        logicalSlug = "pihu",
        aliasSlugs = listOf("pihu", "pihu_mishra"),
        displayName = "Pihu",
        gender = "female",
        orientation = "heterosexual",
        apparentAge = 26,
        city = "Mumbai, Maharashtra (Andheri West)",
        occupation = "Actor (OTT / web-series)",
        bio = "Clear-eyed OTT actor from Lucknow living in Mumbai. Adult (26). Warm, self-aware, and at peace with the work she chose.",
        interests = "acting, scripts, cooking, reading, gym",
        tags = listOf("adult", "indian", "lucknow", "mumbai", "actor"),
        languages = mapOf("primary" to "Hindi", "secondary" to "English", "tertiary" to "Punjabi"),
        packageDir = docsRoot.resolve("Pihu"),
        markdownName = "pihu.md",
        guide = PhysicalGuide(
            agePresentation = AgePresentation(26, true),
            face = Face(faceShape = "Heart-shaped with a strong jaw", jawline = "Strong, defined", cheekbones = "Prominent, warm, softly wide", nose = "Straight, refined, defined bridge", lips = "Full, natural, warm-toned"),
            eyes = Eyes(color = "Deep brown", shape = "Large, almond, warm", eyebrows = "Strong, dark, shaped, gently arched"),
            hair = Hair(color = "Dark brown with warm caramel balayage through the lengths", length = "Shoulder-length", texture = "Thick, wavy", style = "Worn loose most days"),
            skin = Skin(tone = "Warm fair", undertone = "golden", texture = "Clear, natural texture, glossy from care"),
            body = Body(height = "168 cm", weight = "63 kg", build = "Curvy hourglass, soft, maintained", muscularity = "Low — some gym tone, not athletic", proportions = "Narrow waist with full bust and generous hips", overallDescription = "Curvy, warm-bodied adult woman"),
            anatomy = Anatomy(chest = "95 cm bust / 80 cm underbust", shoulders = "37 cm", waist = "69 cm, defined", hips = "98 cm"),
            distinctiveFeatures = listOf("small dimple on the right cheek", "small gold studs in each ear", "thin gold chain she wears constantly"),
            marks = DistinguishingMarks(),
            appearanceConstraints = listOf("preserve heart-shaped face with strong jaw", "preserve right-cheek dimple", "do not flatten hourglass proportions"),
            notes = "Source: docs/personas/Pihu/pihu.md visual_identity",
        ),
        styleConstraints = """{"clothing":["tank","shorts","athleisure","maintained casual"],"markers":["caramel balayage","gold studs","gold chain"]}""",
        imageRoles = mapOf(
            "ChatGPT Image Sep 23, 2026, 09_05_19 PM.png" to ReferenceRole.FRONT,
            "ChatGPT Image Sep 23, 2026, 09_05_29 PM.png" to ReferenceRole.FULL_BODY,
            "ChatGPT Image Sep 23, 2026, 09_05_49 PM.png" to ReferenceRole.LEFT_PROFILE,
            "ChatGPT Image Sep 23, 2026, 09_06_55 PM.png" to ReferenceRole.OTHER,
            "ChatGPT Image Sep 23, 2026, 09_08_33 PM.png" to ReferenceRole.OTHER,
            "ChatGPT Image Sep 23, 2026, 09_10_30 PM.png" to ReferenceRole.OTHER,
            "ChatGPT Image Sep 23, 2026, 09_10_59 PM.png" to ReferenceRole.BACK,
            "ChatGPT Image Sep 23, 2026, 09_11_35 PM.png" to ReferenceRole.FACE_CLOSE,
            "ChatGPT Image Sep 23, 2026, 09_12_33 PM.png" to ReferenceRole.PRIVATE,
        ),
        imageNotes = mapOf(
            "ChatGPT Image Sep 23, 2026, 09_05_29 PM.png" to "Three-quarter standing — FULL_BODY (no THREE_QUARTER role)",
            "ChatGPT Image Sep 23, 2026, 09_06_55 PM.png" to "Lifestyle desk — OTHER",
            "ChatGPT Image Sep 23, 2026, 09_08_33 PM.png" to "Face/detail collage — OTHER",
            "ChatGPT Image Sep 23, 2026, 09_10_30 PM.png" to "Second front in sportswear — OTHER to keep FRONT slot",
            "ChatGPT Image Sep 23, 2026, 09_12_33 PM.png" to "Intimate lingerie bedroom — PRIVATE",
        ),
        extraFields = listOf("slug", "displayName", "gender", "orientation", "apparentAge", "bio", "city", "occupation", "interests", "tags", "languageProfile", "persona core markdown", "physical guide", "style constraints", "reference images"),
    )

    private fun seedPackage(
        logicalSlug: String,
        aliasSlugs: List<String>,
        displayName: String,
        gender: String,
        orientation: String,
        apparentAge: Int,
        city: String,
        occupation: String,
        bio: String,
        interests: String,
        tags: List<String>,
        languages: Map<String, String>,
        packageDir: Path,
        markdownName: String,
        guide: PhysicalGuide,
        styleConstraints: String,
        imageRoles: Map<String, ReferenceRole>,
        imageNotes: Map<String, String>,
        extraFields: List<String>,
    ): SeedResult {
        val existing = personaRepository.findAll().firstOrNull { it.slug in aliasSlugs || it.displayName.equals(displayName, true) }
        val created: Boolean
        val persona = if (existing != null) {
            created = false
            personaRepository.update(
                id = existing.id,
                displayName = displayName,
                gender = gender,
                orientation = orientation,
                apparentAge = apparentAge,
                bio = bio,
                city = city,
                occupation = occupation,
                interests = interests,
                tags = tags,
            )
        } else {
            created = true
            val createdPersona = personaRepository.create(
                slug = logicalSlug,
                displayName = displayName,
                gender = gender,
                orientation = orientation,
                apparentAge = apparentAge,
                languageProfile = languages,
            )
            personaRepository.update(
                id = createdPersona.id,
                bio = bio,
                city = city,
                occupation = occupation,
                interests = interests,
                tags = tags,
            )
        }

        val markdown = packageDir.resolve(markdownName)
        val coreText = if (Files.isRegularFile(markdown)) Files.readString(markdown) else displayName
        val latest = coreVersionRepository.findForPersona(persona.id).maxByOrNull { it.version }
        val core = if (latest != null && latest.content == coreText) {
            latest
        } else {
            val draft = coreVersionRepository.createNextVersion(
                personaId = persona.id,
                content = coreText,
                status = "draft",
                changelogNote = "Seeded from $markdownName",
                author = "source-persona-package-seeder",
            )
            val published = coreVersionRepository.publishCoreVersion(draft.id)
            personaRepository.activateCoreVersion(persona.id, published.id)
            published
        }
        if (personaRepository.findById(persona.id)?.status == "draft") {
            personaRepository.activatePersona(persona.id)
        }

        visualAdminService.ensureDraft(persona.id, author = "source-persona-package-seeder")
        val draft = visualAdminService.requireDraftForPersona(persona.id)
        referenceImageRepository.findForVersion(draft.id)
            .filter { it.status != ReferenceStatus.ARCHIVED }
            .forEach { referenceImageRepository.archiveReference(it.id) }

        visualAdminService.updatePhysicalGuide(persona.id, guide)
        visualAdminService.updatePrivateGuide(persona.id, PrivateVisualGuide())
        visualAdminService.updateStyleConstraints(persona.id, styleConstraints)

        val records = mutableListOf<ImageRecord>()
        val files = listImages(packageDir)
        files.forEach { path ->
            val name = path.fileName.toString()
            val bytes = Files.readAllBytes(path)
            val decoded = decodeImage(bytes)
            val role = imageRoles[name] ?: ReferenceRole.OTHER
            val extra = imageNotes[name]
            if (decoded == null) {
                records += ImageRecord(name, null, null, role.name, false, listOfNotNull("INVALID_OR_UNREADABLE", extra).joinToString("; "))
                return@forEach
            }
            val (w, h) = decoded
            val reasonable = w >= 64 && h >= 64
            if (!reasonable) {
                records += ImageRecord(name, w, h, role.name, false, "UNREASONABLE_DIMENSIONS")
                return@forEach
            }
            val type = when (path.toString().substringAfterLast('.').lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                else -> "image/png"
            }
            visualAdminService.uploadReference(
                personaId = persona.id,
                role = role,
                content = bytes,
                contentType = type,
                notes = listOfNotNull("Seeded from $name", extra).joinToString(" — "),
                replaceSlot = false,
                finalize = true,
            )
            records += ImageRecord(name, w, h, role.name, true, extra ?: "usable")
        }

        val published = visualAdminService.publishAndActivateDraft(persona.id)
        return SeedResult(
            slug = persona.slug,
            displayName = displayName,
            personaId = persona.id.toString(),
            created = created,
            coreVersionId = core.id.toString(),
            visualVersionId = published.id.toString(),
            visualVersionNumber = published.version,
            imagesFound = files.size,
            imagesUsable = records.count { it.usable },
            imagesInvalid = records.count { !it.usable },
            imageRecords = records,
            fieldsPopulated = extraFields,
        )
    }

    private fun listImages(dir: Path): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp") }
                .sorted()
                .toList()
        }
    }

    private fun decodeImage(bytes: ByteArray): Pair<Int, Int>? {
        return try {
            val img: BufferedImage = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
            img.width to img.height
        } catch (_: Exception) {
            null
        }
    }
}
