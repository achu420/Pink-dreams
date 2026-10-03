package com.pinkdreams.baseline

import com.pinkdreams.chat.personacontext.production.BareScalarRendering
import com.pinkdreams.chat.personacontext.production.PersonaContextStatus
import com.pinkdreams.chat.personacontext.production.PersonaFieldAuthorizer
import com.pinkdreams.chat.personacontext.production.isUsable
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaFieldRegistryRepository
import com.pinkdreams.persistence.repositories.PersonaMasterRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillContextRequirementRepository
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Production-readiness (Master Instruction Part 16) — validates the REAL
 * [PersonaContextSeeder] (not a test fixture) against a real seeded database,
 * proving the actual production data path works end-to-end for Simran. Zero
 * API calls.
 */
class PersonaContextSeederTest {

    private class Fixture {
        val db: Database = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }
        val personaRepository = PersonaRepository(db)
        val coreVersionRepository = PersonaCoreVersionRepository(db)
        val masterRepo = PersonaMasterRepository(db)
        val fieldRegistryRepo = PersonaFieldRegistryRepository(db)
        val skillReqRepo = SkillContextRequirementRepository(db)
        val authorizer = PersonaFieldAuthorizer(db, masterRepo, fieldRegistryRepo, skillReqRepo)

        init {
            // Real BaselineSeeder path -- creates the persona + activates a core version,
            // exactly like real application startup, before PersonaContextSeeder runs.
            BaselineSeeder(
                conversationEngineRepository = com.pinkdreams.persistence.repositories.ConversationEngineRepository(db),
                personaRepository = personaRepository,
                personaCoreVersionRepository = coreVersionRepository,
                skillRepository = com.pinkdreams.persistence.repositories.SkillRepository(db),
                memoryEngineRepository = com.pinkdreams.persistence.repositories.MemoryEngineRepository(db),
            ).seedIfMissing()
        }

        fun seeder() = PersonaContextSeeder(personaRepository, coreVersionRepository, masterRepo, fieldRegistryRepo, skillReqRepo)
        fun personaId() = personaRepository.findAll().first { it.slug == BaselineConfiguration.SIMRAN_SLUG }.id
        fun coreVersionId() = personaRepository.findAll().first { it.slug == BaselineConfiguration.SIMRAN_SLUG }.activeCoreVersionId!!
    }

    // A. Basic seeding works
    @Test
    fun `seeding populates master document, field registry, and skill mappings`() {
        val f = Fixture()
        val report = f.seeder().seedIfMissing()

        assertTrue(report.masterDocument.startsWith("created"))
        assertTrue(report.fieldRegistry.startsWith("created"))
        assertTrue(report.skillMappings.startsWith("created"))

        assertTrue(f.fieldRegistryRepo.findAll().isNotEmpty())
        assertTrue(f.skillReqRepo.findAll().isNotEmpty())
        assertTrue(f.masterRepo.findByPersonaCoreVersionId(f.coreVersionId()) != null)
    }

    // B. Idempotent re-run (Test K from PHASE51 matrix)
    @Test
    fun `re-running the seeder is idempotent, never duplicates rows`() {
        val f = Fixture()
        f.seeder().seedIfMissing()
        val fieldCountAfterFirst = f.fieldRegistryRepo.findAll().size
        val skillCountAfterFirst = f.skillReqRepo.findAll().size

        val secondReport = f.seeder().seedIfMissing()

        assertEquals("already present for this core version", secondReport.masterDocument)
        assertTrue(secondReport.fieldRegistry.startsWith("already present"))
        assertTrue(secondReport.skillMappings.startsWith("already present"))
        assertEquals(fieldCountAfterFirst, f.fieldRegistryRepo.findAll().size)
        assertEquals(skillCountAfterFirst, f.skillReqRepo.findAll().size)
    }

    // C. general_chat resolves real data, includes occupation (the Phase 50-identified defect, now fixed)
    @Test
    fun `general_chat resolves real Simran identity and occupation`() {
        val f = Fixture()
        f.seeder().seedIfMissing()

        val result = f.authorizer.resolve(f.personaId(), f.coreVersionId(), "general_chat", "hi", BareScalarRendering)

        assertEquals(PersonaContextStatus.USABLE, result.status)
        assertTrue(result.renderedText.contains("Simran Kohli"))
        assertTrue(result.renderedText.contains("Architect (junior)"), "general_chat must include occupation")
        assertFalse(result.renderedText.contains("adult_intimacy"))
    }

    // D. friendship resolves real data
    @Test
    fun `friendship resolves real Simran identity and attachment style`() {
        val f = Fixture()
        f.seeder().seedIfMissing()

        val result = f.authorizer.resolve(f.personaId(), f.coreVersionId(), "friendship", "hi", BareScalarRendering)

        assertEquals(PersonaContextStatus.USABLE, result.status)
        assertTrue(result.renderedText.contains("Simran Kohli"))
        assertTrue(result.includedFieldIds.contains("behavioral_signature.attachment_style"))
    }

    // E. romantic_intimacy resolves restricted fields
    @Test
    fun `romantic_intimacy resolves the restricted adult_intimacy field`() {
        val f = Fixture()
        f.seeder().seedIfMissing()

        val result = f.authorizer.resolve(f.personaId(), f.coreVersionId(), "romantic_intimacy", "hi", BareScalarRendering)

        assertEquals(PersonaContextStatus.USABLE, result.status)
        assertTrue(result.includedFieldIds.contains("adult_intimacy"))
        assertTrue(result.renderedText.contains("Playful and warm"), "real adult_intimacy.romantic_style content must be present")
    }

    // F. foreplay resolves restricted fields
    @Test
    fun `foreplay resolves the restricted adult_intimacy field`() {
        val f = Fixture()
        f.seeder().seedIfMissing()

        val result = f.authorizer.resolve(f.personaId(), f.coreVersionId(), "foreplay", "hi", BareScalarRendering)

        assertEquals(PersonaContextStatus.USABLE, result.status)
        assertTrue(result.includedFieldIds.contains("adult_intimacy"))
    }

    // G. general_chat and friendship CANNOT receive restricted fields
    @Test
    fun `general_chat and friendship never receive restricted fields even though they exist in the document`() {
        val f = Fixture()
        f.seeder().seedIfMissing()

        val generalChat = f.authorizer.resolve(f.personaId(), f.coreVersionId(), "general_chat", "hi", BareScalarRendering)
        val friendship = f.authorizer.resolve(f.personaId(), f.coreVersionId(), "friendship", "hi", BareScalarRendering)

        assertFalse(generalChat.includedFieldIds.contains("adult_intimacy"))
        assertFalse(generalChat.includedFieldIds.contains("private_anatomical_profile"))
        assertFalse(friendship.includedFieldIds.contains("adult_intimacy"))
        assertFalse(friendship.includedFieldIds.contains("private_anatomical_profile"))
        assertFalse(generalChat.renderedText.contains("Playful and warm"), "adult_intimacy content must not leak into general_chat")
    }

    // H. advice (real, unmapped production skill) fails closed, no fabricated mapping
    @Test
    fun `advice is a real production skill deliberately left unmapped, fails closed`() {
        val f = Fixture()
        f.seeder().seedIfMissing()

        assertTrue("advice" in BaselineConfiguration.SKILLS.map { it.key }, "advice must be a real skill, not a typo")
        val result = f.authorizer.resolve(f.personaId(), f.coreVersionId(), "advice", "hi", BareScalarRendering)

        assertEquals(PersonaContextStatus.SKILL_MAPPING_MISSING, result.status)
        assertFalse(result.isUsable())
    }

    // I. every mapped skill key is a real production skill
    @Test
    fun `every seeded skill mapping key is a real BaselineConfiguration skill`() {
        val f = Fixture()
        val realKeys = BaselineConfiguration.SKILLS.map { it.key }.toSet()
        PersonaContextSeeder.SKILL_MAPPINGS.forEach { mapping ->
            assertTrue(mapping.skillKey in realKeys, "${mapping.skillKey} must be a real production skill key")
        }
    }

    // J. field registry has no duplicate field IDs
    @Test
    fun `field registry has no duplicate field IDs`() {
        val fieldIds = PersonaContextSeeder.FIELD_REGISTRY.map { it.fieldId }
        assertEquals(fieldIds.toSet().size, fieldIds.size, "field registry must not contain duplicate field IDs")
    }

    // K. restricted fields are correctly flagged in the registry
    @Test
    fun `both restricted fields are marked sensitivity restricted with correct allowedContexts`() {
        val adultIntimacy = PersonaContextSeeder.FIELD_REGISTRY.first { it.fieldId == "adult_intimacy" }
        val anatomical = PersonaContextSeeder.FIELD_REGISTRY.first { it.fieldId == "private_anatomical_profile" }

        assertEquals("restricted", adultIntimacy.sensitivity)
        assertEquals(setOf("romantic_intimacy", "foreplay"), adultIntimacy.allowedContexts?.toSet())
        assertEquals("restricted", anatomical.sensitivity)
        assertEquals(setOf("romantic_intimacy", "foreplay"), anatomical.allowedContexts?.toSet())
    }
}
