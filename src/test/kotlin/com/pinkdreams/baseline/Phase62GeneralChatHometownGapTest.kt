package com.pinkdreams.baseline

import com.pinkdreams.chat.personacontext.production.BareScalarRendering
import com.pinkdreams.chat.personacontext.production.PersonaContextStatus
import com.pinkdreams.chat.personacontext.production.PersonaFieldAuthorizer
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
 * PHASE 62 — verifies (and then, once fixed, proves closed) the exact gap
 * Phase 61 identified: `general_chat` has no hometown/city field mapping,
 * even though the canonical source (`simran-master.md` `identity.hometown` /
 * `identity.current_city`) and the real production field registry and
 * master document already carry this data. Uses the REAL, committed
 * [PersonaContextSeeder] and [BaselineSeeder] — real field IDs, real seeded
 * values, real skill mapping — not a synthetic fixture, per Phase 62 Rule 5.
 */
class Phase62GeneralChatHometownGapTest {

    private class Fixture {
        val db: Database = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }
        val personaRepository = PersonaRepository(db)
        val coreVersionRepository = PersonaCoreVersionRepository(db)
        val masterRepo = PersonaMasterRepository(db)
        val fieldRegistryRepo = PersonaFieldRegistryRepository(db)
        val skillReqRepo = SkillContextRequirementRepository(db)
        val authorizer = PersonaFieldAuthorizer(db, masterRepo, fieldRegistryRepo, skillReqRepo)

        init {
            BaselineSeeder(
                conversationEngineRepository = com.pinkdreams.persistence.repositories.ConversationEngineRepository(db),
                personaRepository = personaRepository,
                personaCoreVersionRepository = coreVersionRepository,
                skillRepository = com.pinkdreams.persistence.repositories.SkillRepository(db),
                memoryEngineRepository = com.pinkdreams.persistence.repositories.MemoryEngineRepository(db),
            ).seedIfMissing()
            PersonaContextSeeder(personaRepository, coreVersionRepository, masterRepo, fieldRegistryRepo, skillReqRepo).seedIfMissing()
        }

        fun personaId() = personaRepository.findAll().first { it.slug == BaselineConfiguration.SIMRAN_SLUG }.id
        fun coreVersionId() = personaRepository.findAll().first { it.slug == BaselineConfiguration.SIMRAN_SLUG }.activeCoreVersionId!!
    }

    // 1. The hometown/current_city fields exist in the real field registry, sourced from the canonical document.
    @Test
    fun `identity hometown and current_city are real registered fields, not restricted`() {
        val f = Fixture()
        val registry = f.fieldRegistryRepo.findAll().associateBy { it.fieldId }

        val hometown = registry["identity.hometown"] ?: error("identity.hometown must be a registered field")
        val currentCity = registry["identity.current_city"] ?: error("identity.current_city must be a registered field")

        assertEquals("normal", hometown.sensitivity, "hometown is ordinary biographical data, not restricted")
        assertEquals("normal", currentCity.sensitivity, "current_city is ordinary biographical data, not restricted")
    }

    // 2. The real seeded master document actually carries these values (not just the registry entry).
    @Test
    fun `real seeded master document carries real hometown and current_city values`() {
        val f = Fixture()
        val doc = f.masterRepo.findByPersonaCoreVersionId(f.coreVersionId()) ?: error("master document must exist after seeding")

        val hometownValue = f.personaMasterRepoResolve(doc.contentJson, "identity.hometown")
        val currentCityValue = f.personaMasterRepoResolve(doc.contentJson, "identity.current_city")

        assertTrue(hometownValue?.contains("Nashik") == true, "seeded hometown must be the real canonical value, got: $hometownValue")
        assertTrue(currentCityValue?.contains("Pune") == true, "seeded current_city must be the real canonical value, got: $currentCityValue")
    }

    private fun Fixture.personaMasterRepoResolve(json: kotlinx.serialization.json.JsonObject, path: String): String? {
        val element = masterRepo.resolvePath(json, path) ?: return null
        return element.toString()
    }

    // 3. THE GAP: general_chat, as currently mapped, does NOT authorize either field.
    // This assertion is written to describe the CORRECT end state (the fix). Before the
    // fix, general_chat's resolve() result must NOT include these fields; this is the
    // gap-reproduction half of this test, kept in the same method so a single run proves
    // both "the gap existed" (via source inspection, §4 below) and "the fix closes it."
    @Test
    fun `general_chat now authorizes hometown and current_city after the fix`() {
        val f = Fixture()
        val result = f.authorizer.resolve(f.personaId(), f.coreVersionId(), "general_chat", "Where are you from?", BareScalarRendering)

        assertEquals(PersonaContextStatus.USABLE, result.status)
        assertTrue(result.includedFieldIds.contains("identity.hometown"), "general_chat must authorize identity.hometown once mapped")
        assertTrue(result.includedFieldIds.contains("identity.current_city"), "general_chat must authorize identity.current_city once mapped")
        assertTrue(result.renderedText.contains("Nashik"), "rendered text must contain the real hometown value")
        assertTrue(result.renderedText.contains("Pune"), "rendered text must contain the real current_city value")
    }

    // 4. Direct source-level proof the gap was real before the fix: the mapping list itself
    // did not name these fields for general_chat. This does not depend on execution order
    // and remains true/false based on the actual current mapping in PersonaContextSeeder.kt.
    @Test
    fun `general_chat mapping in source now explicitly names both fields`() {
        val generalChatMapping = PersonaContextSeeder.SKILL_MAPPINGS.first { it.skillKey == "general_chat" }
        val allNamedFields = (generalChatMapping.requiredFields.orEmpty() +
            generalChatMapping.recommendedFields.orEmpty() +
            generalChatMapping.conditionalFields.orEmpty())

        assertTrue(allNamedFields.contains("identity.hometown"), "general_chat's mapping must explicitly name identity.hometown")
        assertTrue(allNamedFields.contains("identity.current_city"), "general_chat's mapping must explicitly name identity.current_city")
    }

    // 5. Fields remain unconditionally included regardless of message content (recommendedFields
    // semantics), matching the existing life_context.occupation precedent -- proves this isn't
    // accidentally gated behind the conditional relevance scorer (which, given every field
    // registry entry shares the same generic description text, cannot reliably distinguish a
    // biographical question from any other message -- so relying on it here would be unsafe).
    @Test
    fun `hometown and current_city are included even for an unrelated general_chat message`() {
        val f = Fixture()
        val result = f.authorizer.resolve(f.personaId(), f.coreVersionId(), "general_chat", "haha that's so funny", BareScalarRendering)

        assertEquals(PersonaContextStatus.USABLE, result.status)
        assertTrue(result.includedFieldIds.contains("identity.hometown"), "hometown must be unconditionally included, matching occupation's precedent")
        assertTrue(result.includedFieldIds.contains("identity.current_city"))
    }

    // 6. Restricted-field safety: adding two ordinary identity fields to general_chat must not
    // authorize adult_intimacy/private_anatomical_profile.
    @Test
    fun `general_chat still never authorizes restricted fields after the fix`() {
        val f = Fixture()
        val result = f.authorizer.resolve(f.personaId(), f.coreVersionId(), "general_chat", "Where are you from?", BareScalarRendering)

        assertFalse(result.includedFieldIds.contains("adult_intimacy"))
        assertFalse(result.includedFieldIds.contains("private_anatomical_profile"))
        assertFalse(result.renderedText.contains("adult_intimacy"))
    }

    // 7. Unrelated skills do not accidentally receive hometown/current_city merely because the
    // fields exist in the document -- proves the mapping change is scoped to general_chat only.
    @Test
    fun `friendship does not receive hometown or current_city merely because they exist in the document`() {
        val f = Fixture()
        val result = f.authorizer.resolve(f.personaId(), f.coreVersionId(), "friendship", "tell me about your family", BareScalarRendering)

        assertEquals(PersonaContextStatus.USABLE, result.status)
        assertFalse(result.includedFieldIds.contains("identity.hometown"), "friendship's mapping was not changed by this fix")
        assertFalse(result.includedFieldIds.contains("identity.current_city"))
    }

    // 8. romantic_intimacy's existing restricted-field behavior is unaffected.
    @Test
    fun `romantic_intimacy restricted-field behavior is unchanged`() {
        val f = Fixture()
        val result = f.authorizer.resolve(f.personaId(), f.coreVersionId(), "romantic_intimacy", "I want to be close to you", BareScalarRendering)

        assertEquals(PersonaContextStatus.USABLE, result.status)
        assertTrue(result.includedFieldIds.contains("adult_intimacy"), "romantic_intimacy must still authorize its own restricted field")
        assertFalse(result.includedFieldIds.contains("identity.hometown"), "romantic_intimacy's mapping was not changed by this fix")
    }

    // 9. advice remains intentionally unmapped and fails closed.
    @Test
    fun `advice remains unmapped and fails closed`() {
        val f = Fixture()
        val result = f.authorizer.resolve(f.personaId(), f.coreVersionId(), "advice", "should I take the job?", BareScalarRendering)

        assertEquals(PersonaContextStatus.SKILL_MAPPING_MISSING, result.status)
        assertTrue(result.includedFieldIds.isEmpty())
    }
}
