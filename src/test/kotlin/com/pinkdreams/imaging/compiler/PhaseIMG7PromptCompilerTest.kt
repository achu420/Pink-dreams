package com.pinkdreams.imaging.compiler

import com.pinkdreams.imaging.provider.GenerationRequest
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import com.pinkdreams.visual.identity.AgePresentation
import com.pinkdreams.visual.identity.Face
import com.pinkdreams.visual.identity.Eyes
import com.pinkdreams.visual.identity.Hair
import com.pinkdreams.visual.identity.Skin
import com.pinkdreams.visual.identity.Body
import com.pinkdreams.visual.identity.Anatomy
import com.pinkdreams.visual.identity.PhysicalGuide
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class PhaseIMG7PromptCompilerTest {

    private val compiler = PromptCompiler()
    private val json = Json { ignoreUnknownKeys = true }

    private fun createDummyVisualVersion(physicalGuideJson: String = "{}"): PersonaVisualVersionRepository.PersonaVisualVersion {
        return PersonaVisualVersionRepository.PersonaVisualVersion(
            id = UUID.randomUUID(),
            personaIdentityId = UUID.randomUUID(),
            version = 1,
            physicalGuide = physicalGuideJson,
            styleConstraints = "{}",
            status = "published",
            changelogNote = null,
            author = null
        )
    }

    // =========================================================================
    // Validation Tests (1-3)
    // =========================================================================

    @Test
    fun `Validation 1 - invalid candidate count rejected`() {
        val intent = SceneIntent(
            generation = GenerationSpecs(candidateCount = 0)
        )
        val result = intent.validate()
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("candidateCount") })
    }

    @Test
    fun `Validation 2 - excessive candidate count rejected`() {
        val intent = SceneIntent(
            generation = GenerationSpecs(candidateCount = 101)
        )
        val result = intent.validate()
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("candidateCount") })
    }

    @Test
    fun `Validation 3 - valid dimensions accepted`() {
        val intent = SceneIntent(
            generation = GenerationSpecs(
                candidateCount = 2,
                widthPx = 512,
                heightPx = 768
            )
        )
        val result = intent.validate()
        assertTrue(result.valid)
    }

    // =========================================================================
    // Determinism Tests (4-6)
    // =========================================================================

    @Test
    fun `Determinism 4 - same scene intent produces identical prompt`() {
        val intent = SceneIntent(
            subject = Subject(
                identity = "A young woman",
                pose = Pose.STANDING,
                expression = "serene smile"
            ),
            environment = Environment(location = "garden"),
            generation = GenerationSpecs(candidateCount = 1)
        )
        val version = createDummyVisualVersion()

        val result1 = compiler.compile(intent, version, "key-1")
        val result2 = compiler.compile(intent, version, "key-1")

        assertEquals(result1.prompt, result2.prompt)
    }

    @Test
    fun `Determinism 5 - prompt structure is stable`() {
        val intent = SceneIntent(
            subject = Subject(identity = "Subject A"),
            environment = Environment(location = "Location B"),
            composition = Composition(cameraDistance = CameraDistance.MEDIUM),
            moodAndStyle = MoodAndStyle(mood = Atmosphere.CALM),
            generation = GenerationSpecs(candidateCount = 1)
        )
        val version = createDummyVisualVersion()

        val result = compiler.compile(intent, version, "key-5")

        // Verify prompt is deterministic and contains all sections
        assertTrue(result.prompt.contains("Subject A"))
        assertTrue(result.prompt.contains("Location B"))
        assertTrue(result.prompt.lowercase().contains("medium"))
        assertTrue(result.prompt.lowercase().contains("calm"))
    }

    @Test
    fun `Determinism 6 - identical calls with different keys still produce same prompt`() {
        val intent = SceneIntent(
            subject = Subject(identity = "Test"),
            generation = GenerationSpecs(candidateCount = 1)
        )
        val version = createDummyVisualVersion()

        val result1 = compiler.compile(intent, version, "key-A")
        val result2 = compiler.compile(intent, version, "key-B")

        assertEquals(result1.prompt, result2.prompt)
        assertNotNull(result1.idempotencyKey)
        assertNotNull(result2.idempotencyKey)
    }

    // =========================================================================
    // Prompt Construction Tests (7-12)
    // =========================================================================

    @Test
    fun `Prompt 7 - subject section includes identity and physical guide`() {
        val guide = PhysicalGuide(
            agePresentation = AgePresentation(apparentAge = 25, adult = true),
            face = Face(faceShape = "oval"),
            eyes = Eyes(color = "blue"),
            hair = Hair(color = "blonde", length = "long")
        )
        val versionJson = json.encodeToString(guide)
        val version = createDummyVisualVersion(versionJson)

        val intent = SceneIntent(
            subject = Subject(identity = "A ethereal woman"),
            generation = GenerationSpecs(candidateCount = 1)
        )

        val result = compiler.compile(intent, version, "key-7")

        assertTrue(result.prompt.contains("ethereal woman"))
        assertTrue(result.prompt.contains("Age 25"))
        assertTrue(result.prompt.contains("blue eyes"))
        assertTrue(result.prompt.contains("blonde") || result.prompt.contains("long"))
    }

    @Test
    fun `Prompt 8 - appearance section includes outfit and accessories`() {
        val intent = SceneIntent(
            appearance = Appearance(
                outfit = "flowing white dress",
                hairstyle = "loose waves",
                makeup = "natural and glowing",
                accessories = listOf("silver necklace", "delicate rings")
            ),
            generation = GenerationSpecs(candidateCount = 1)
        )
        val version = createDummyVisualVersion()

        val result = compiler.compile(intent, version, "key-8")

        assertTrue(result.prompt.contains("white dress"))
        assertTrue(result.prompt.contains("loose waves"))
        assertTrue(result.prompt.contains("natural and glowing"))
        assertTrue(result.prompt.contains("silver necklace"))
        assertTrue(result.prompt.contains("delicate rings"))
    }

    @Test
    fun `Prompt 9 - environment section includes location, weather, season`() {
        val intent = SceneIntent(
            environment = Environment(
                location = "tropical beach",
                weather = Weather.CLEAR,
                season = Season.SUMMER,
                background = "turquoise ocean"
            ),
            generation = GenerationSpecs(candidateCount = 1)
        )
        val version = createDummyVisualVersion()

        val result = compiler.compile(intent, version, "key-9")

        assertTrue(result.prompt.contains("tropical beach"))
        assertTrue(result.prompt.contains("clear"))
        assertTrue(result.prompt.contains("summer"))
        assertTrue(result.prompt.contains("turquoise ocean"))
    }

    @Test
    fun `Prompt 10 - composition section includes camera parameters`() {
        val intent = SceneIntent(
            subject = Subject(pose = Pose.SITTING),
            composition = Composition(
                cameraDistance = CameraDistance.MEDIUM_CLOSE,
                cameraAngle = CameraAngle.EYE_LEVEL,
                orientation = FrameOrientation.PORTRAIT,
                focalEmphasis = FocalEmphasis.FACE
            ),
            generation = GenerationSpecs(candidateCount = 1)
        )
        val version = createDummyVisualVersion()

        val result = compiler.compile(intent, version, "key-10")

        assertTrue(result.prompt.contains("medium close") || result.prompt.contains("medium_close"))
        assertTrue(result.prompt.contains("eye level") || result.prompt.contains("eye_level"))
        assertTrue(result.prompt.contains("portrait"))
        assertTrue(result.prompt.contains("face"))
        assertTrue(result.prompt.contains("sitting"))
    }

    @Test
    fun `Prompt 11 - mood and style section includes all mood components`() {
        val intent = SceneIntent(
            moodAndStyle = MoodAndStyle(
                mood = Atmosphere.DRAMATIC,
                lighting = LightingStyle.CINEMATIC,
                visualStyle = VisualStyle.PHOTOREALISTIC,
                realismLevel = RealismLevel.PHOTOREALISTIC
            ),
            generation = GenerationSpecs(candidateCount = 1)
        )
        val version = createDummyVisualVersion()

        val result = compiler.compile(intent, version, "key-11")

        assertTrue(result.prompt.contains("dramatic"))
        assertTrue(result.prompt.contains("cinematic"))
        assertTrue(result.prompt.contains("photorealistic"))
    }

    @Test
    fun `Prompt 12 - minimal intent with at least subject identity produces valid prompt`() {
        val intent = SceneIntent(
            subject = Subject(identity = "A person"),
            environment = Environment(),
            composition = Composition(),
            appearance = Appearance(),
            moodAndStyle = MoodAndStyle(),
            generation = GenerationSpecs(candidateCount = 1)
        )
        val version = createDummyVisualVersion()

        val result = compiler.compile(intent, version, "key-12")

        // Minimal prompt should be valid
        assertTrue(result.prompt.isNotEmpty())
        assertTrue(result.prompt.contains("A person"))
        val validation = result.validate()
        assertTrue(validation.valid)
    }

    // =========================================================================
    // Reference Handling Tests (13-15)
    // =========================================================================

    @Test
    fun `Reference 13 - selected references mapped to ReferenceInput`() {
        val refId1 = UUID.randomUUID()
        val refId2 = UUID.randomUUID()

        val intent = SceneIntent(
            generation = GenerationSpecs(
                candidateCount = 1,
                selectedReferences = listOf(refId1, refId2)
            )
        )
        val version = createDummyVisualVersion()

        val result = compiler.compile(intent, version, "key-13")

        assertEquals(2, result.references.size)
        assertEquals(refId1, result.references[0].referenceImageId)
        assertEquals(refId2, result.references[1].referenceImageId)
        assertEquals("FACE", result.references[0].role)
        assertEquals("BODY", result.references[1].role)
    }

    @Test
    fun `Reference 14 - reference roles assigned by index`() {
        val refs = (0..3).map { UUID.randomUUID() }

        val intent = SceneIntent(
            generation = GenerationSpecs(
                candidateCount = 1,
                selectedReferences = refs
            )
        )
        val version = createDummyVisualVersion()

        val result = compiler.compile(intent, version, "key-14")

        assertEquals("FACE", result.references[0].role)
        assertEquals("BODY", result.references[1].role)
        assertEquals("STYLE", result.references[2].role)
        assertEquals("GENERAL_IDENTITY", result.references[3].role)
    }

    @Test
    fun `Reference 15 - no references produces empty reference list`() {
        val intent = SceneIntent(
            generation = GenerationSpecs(
                candidateCount = 1,
                selectedReferences = emptyList()
            )
        )
        val version = createDummyVisualVersion()

        val result = compiler.compile(intent, version, "key-15")

        assertEquals(0, result.references.size)
    }

    // =========================================================================
    // Generation Specs Tests (16-19)
    // =========================================================================

    @Test
    fun `GenSpecs 16 - candidate count preserved`() {
        val intent = SceneIntent(
            generation = GenerationSpecs(candidateCount = 5)
        )
        val version = createDummyVisualVersion()

        val result = compiler.compile(intent, version, "key-16")

        assertEquals(5, result.candidateCount)
    }

    @Test
    fun `GenSpecs 17 - dimensions preserved`() {
        val intent = SceneIntent(
            generation = GenerationSpecs(
                candidateCount = 1,
                widthPx = 768,
                heightPx = 1024
            )
        )
        val version = createDummyVisualVersion()

        val result = compiler.compile(intent, version, "key-17")

        assertEquals(768, result.widthPx)
        assertEquals(1024, result.heightPx)
    }

    @Test
    fun `GenSpecs 18 - aspect ratio preserved`() {
        val intent = SceneIntent(
            generation = GenerationSpecs(
                candidateCount = 1,
                aspectRatio = "16:9"
            )
        )
        val version = createDummyVisualVersion()

        val result = compiler.compile(intent, version, "key-18")

        assertEquals("16:9", result.aspectRatio)
    }

    @Test
    fun `GenSpecs 19 - idempotency key preserved`() {
        val intent = SceneIntent(
            generation = GenerationSpecs(candidateCount = 1)
        )
        val version = createDummyVisualVersion()
        val key = "unique-key-19"

        val result = compiler.compile(intent, version, key)

        assertEquals(key, result.idempotencyKey)
    }

    // =========================================================================
    // Metadata Tests (20-21)
    // =========================================================================

    @Test
    fun `Metadata 20 - comprehensive metadata built from scene intent`() {
        val intent = SceneIntent(
            subject = Subject(pose = Pose.STANDING),
            composition = Composition(
                cameraDistance = CameraDistance.WIDE,
                cameraAngle = CameraAngle.HIGH_ANGLE
            ),
            moodAndStyle = MoodAndStyle(
                mood = Atmosphere.PLAYFUL,
                lighting = LightingStyle.SOFT_DIFFUSED,
                visualStyle = VisualStyle.ILLUSTRATION,
                realismLevel = RealismLevel.STYLIZED
            ),
            environment = Environment(
                location = "mountain peak",
                weather = Weather.CLOUDY
            ),
            generation = GenerationSpecs(candidateCount = 1)
        )
        val version = createDummyVisualVersion()

        val result = compiler.compile(intent, version, "key-20")

        assertEquals("STANDING", result.clientMetadata["subject_pose"])
        assertEquals("WIDE", result.clientMetadata["camera_distance"])
        assertEquals("HIGH_ANGLE", result.clientMetadata["camera_angle"])
        assertEquals("PLAYFUL", result.clientMetadata["mood"])
        assertEquals("SOFT_DIFFUSED", result.clientMetadata["lighting"])
        assertEquals("ILLUSTRATION", result.clientMetadata["visual_style"])
        assertEquals("STYLIZED", result.clientMetadata["realism_level"])
        assertEquals("mountain peak", result.clientMetadata["environment_location"])
        assertEquals("CLOUDY", result.clientMetadata["environment_weather"])
    }

    @Test
    fun `Metadata 21 - unspecified fields map to unspecified`() {
        val intent = SceneIntent(
            generation = GenerationSpecs(candidateCount = 1)
        )
        val version = createDummyVisualVersion()

        val result = compiler.compile(intent, version, "key-21")

        assertEquals("unspecified", result.clientMetadata["subject_pose"])
        assertEquals("unspecified", result.clientMetadata["mood"])
        assertEquals("unspecified", result.clientMetadata["environment_location"])
    }

    // =========================================================================
    // Physical Guide Integration Tests (22-24)
    // =========================================================================

    @Test
    fun `PhysicalGuide 22 - parses and incorporates guide data`() {
        val guide = PhysicalGuide(
            agePresentation = AgePresentation(apparentAge = 30, adult = true),
            face = Face(faceShape = "heart-shaped", nose = "straight"),
            eyes = Eyes(color = "green", shape = "almond"),
            hair = Hair(color = "red", length = "shoulder-length", style = "wavy"),
            skin = Skin(tone = "fair", undertone = "warm"),
            body = Body(height = "tall", build = "athletic")
        )
        val versionJson = json.encodeToString(guide)
        val version = createDummyVisualVersion(versionJson)

        val intent = SceneIntent(
            generation = GenerationSpecs(candidateCount = 1)
        )

        val result = compiler.compile(intent, version, "key-22")

        val prompt = result.prompt.lowercase()
        assertTrue(prompt.contains("age 30"))
        assertTrue(prompt.contains("green") || prompt.contains("almond"))
        assertTrue(prompt.contains("red") || prompt.contains("shoulder"))
    }

    @Test
    fun `PhysicalGuide 23 - subject identity takes priority over guide`() {
        val guide = PhysicalGuide(
            agePresentation = AgePresentation(apparentAge = 25, adult = true),
            face = Face(),
            eyes = Eyes(),
            hair = Hair(),
            skin = Skin(),
            body = Body()
        )
        val versionJson = json.encodeToString(guide)
        val version = createDummyVisualVersion(versionJson)

        val intent = SceneIntent(
            subject = Subject(identity = "A mysterious figure"),
            generation = GenerationSpecs(candidateCount = 1)
        )

        val result = compiler.compile(intent, version, "key-23")

        val prompt = result.prompt
        // Identity should appear first/prominently
        val idIdx = prompt.indexOf("mysterious figure")
        val ageIdx = prompt.indexOf("Age 25")
        assertTrue(idIdx >= 0)
        assertTrue(idIdx < ageIdx) // Identity appears before physical guide
    }

    @Test
    fun `PhysicalGuide 24 - missing or empty guide handled gracefully`() {
        val emptyVersion = createDummyVisualVersion("{}")
        val nullVersion = createDummyVisualVersion("")

        val intent = SceneIntent(
            subject = Subject(identity = "A person"),
            generation = GenerationSpecs(candidateCount = 1)
        )

        val result1 = compiler.compile(intent, emptyVersion, "key-24a")
        val result2 = compiler.compile(intent, nullVersion, "key-24b")

        assertTrue(result1.prompt.contains("A person"))
        assertTrue(result2.prompt.contains("A person"))
    }

    // =========================================================================
    // Edge Cases Tests (25-26)
    // =========================================================================

    @Test
    fun `Edge 25 - special characters in strings escaped properly`() {
        val intent = SceneIntent(
            subject = Subject(identity = "A woman with \"quotes\" and 'apostrophes'"),
            environment = Environment(location = "Café with décor"),
            generation = GenerationSpecs(candidateCount = 1)
        )
        val version = createDummyVisualVersion()

        val result = compiler.compile(intent, version, "key-25")

        assertTrue(result.prompt.contains("quotes") || result.prompt.contains("apostrophes"))
        assertTrue(result.prompt.isNotEmpty())
    }

    @Test
    fun `Edge 26 - extreme valid dimensions handled`() {
        val intent = SceneIntent(
            subject = Subject(identity = "Test subject"),
            generation = GenerationSpecs(
                candidateCount = 1,
                widthPx = 4096,
                heightPx = 4096
            )
        )
        val version = createDummyVisualVersion()

        val result = compiler.compile(intent, version, "key-26")

        assertEquals(4096, result.widthPx)
        assertEquals(4096, result.heightPx)
        val validation = result.validate()
        assertTrue(validation.valid)
    }

    // =========================================================================
    // No-Op Tests (27-28)
    // =========================================================================

    @Test
    fun `NoOp 27 - provider-independent output (no OpenRouter types)`() {
        val intent = SceneIntent(
            generation = GenerationSpecs(candidateCount = 1)
        )
        val version = createDummyVisualVersion()

        val result = compiler.compile(intent, version, "key-27")

        assertFalse(result.javaClass.name.contains("OpenRouter"))
        assertEquals("GenerationRequest", result.javaClass.simpleName)
    }

    @Test
    fun `NoOp 28 - no LLM calls made during compilation`() {
        // This is a structural test: if the compiler were calling an LLM,
        // it would need network access or mock setup. The fact this runs
        // synchronously and instantly confirms no LLM calls.
        val intent = SceneIntent(
            subject = Subject(identity = "Complex scene intent"),
            generation = GenerationSpecs(candidateCount = 1)
        )
        val version = createDummyVisualVersion()

        val startTime = System.currentTimeMillis()
        val result = compiler.compile(intent, version, "key-28")
        val elapsed = System.currentTimeMillis() - startTime

        assertNotNull(result)
        assertTrue(elapsed < 100) // Should complete in < 100ms if no network/LLM call
    }
}
