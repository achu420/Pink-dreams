package com.pinkdreams.chat.personacontext.production

import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaFieldRegistryEntry
import com.pinkdreams.persistence.repositories.PersonaFieldRegistryRepository
import com.pinkdreams.persistence.repositories.PersonaMasterRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillContextRequirement
import com.pinkdreams.persistence.repositories.SkillContextRequirementRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * PHASE 47 — deterministic tests for the production integration seam
 * (ProductionPersonaContextService). Zero API calls, zero network access.
 * Covers test matrix items A-M from PHASE47_PRODUCTION_INTEGRATION.md §9.
 */
class ProductionPersonaContextServiceTest {

    private val coreContent = "GENERIC BASELINE PERSONA CORE TEXT — PersonaCoreVersions.content equivalent"

    private class Fixture {
        val db: Database = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }
        val personas = PersonaRepository(db)
        val cores = PersonaCoreVersionRepository(db)
        val masterRepo = PersonaMasterRepository(db)
        val fieldRegistryRepo = PersonaFieldRegistryRepository(db)
        val skillReqRepo = SkillContextRequirementRepository(db)
        val authorizer = PersonaFieldAuthorizer(db, masterRepo, fieldRegistryRepo, skillReqRepo)
        val service = ProductionPersonaContextService(authorizer)

        val personaId: UUID
        val coreVersionId: UUID

        init {
            val persona = personas.create(
                slug = "phase47-test-persona-${UUID.randomUUID()}",
                displayName = "Phase47 Test Persona",
                gender = "female",
                orientation = "n/a",
                apparentAge = 27,
                languageProfile = emptyMap(),
            )
            personaId = persona.id
            val core = cores.create(personaId = personaId, version = 1, content = "unused-in-these-tests")
            coreVersionId = core.id
        }

        fun seedDocument(content: JsonObject) = masterRepo.create(coreVersionId, content)

        fun seedField(fieldId: String, path: String = fieldId, sensitivity: String = "normal", allowedContexts: List<String>? = null) {
            fieldRegistryRepo.create(
                PersonaFieldRegistryEntry(
                    fieldId = fieldId, path = path, semanticType = null, classification = "identity",
                    sensitivity = sensitivity, stability = "stable", behavioralCore = false, defaultLoad = false,
                    allowedContexts = allowedContexts, prohibitedContexts = null, description = fieldId,
                ),
            )
        }

        fun seedSkill(skillKey: String, required: List<String>? = null) {
            skillReqRepo.create(
                SkillContextRequirement(
                    skillKey = skillKey, requiredFields = required, recommendedFields = null,
                    conditionalFields = null, restrictedFields = null, purpose = null, whenToUse = null, whenNotToUse = null,
                ),
            )
        }
    }

    // A. Valid persona context -> selected context used
    @Test
    fun `A - valid persona context is used instead of core content`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("name", "Real Persona Fact") })
        f.seedField("name")
        f.seedSkill("general_chat", required = listOf("name"))

        val text = f.service.resolvePersonaContent(f.personaId, f.coreVersionId, "general_chat", "hi", coreContent)

        assertTrue(text.contains("Real Persona Fact"))
        assertNotEquals(coreContent, text)
    }

    // B. No context -> core.content fallback
    @Test
    fun `B - no persona document falls back to core content exactly`() {
        val f = Fixture()
        f.seedField("name")
        f.seedSkill("general_chat", required = listOf("name"))

        val text = f.service.resolvePersonaContent(f.personaId, f.coreVersionId, "general_chat", "hi", coreContent)

        assertEquals(coreContent, text)
    }

    // C. Empty context -> core.content fallback
    @Test
    fun `C - empty persona document falls back to core content exactly`() {
        val f = Fixture()
        f.seedDocument(JsonObject(emptyMap()))
        f.seedField("name")
        f.seedSkill("general_chat", required = listOf("name"))

        val text = f.service.resolvePersonaContent(f.personaId, f.coreVersionId, "general_chat", "hi", coreContent)

        assertEquals(coreContent, text)
    }

    // D. Restricted field -> never rendered
    @Test
    fun `D - restricted field never appears in the final assembled string`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("name", "X"); put("intimate_anatomy", "SECRET_VALUE") })
        f.seedField("name")
        f.seedField("intimate_anatomy", sensitivity = "restricted", allowedContexts = listOf("romantic_intimacy"))
        f.seedSkill("general_chat", required = listOf("name", "intimate_anatomy"))

        val text = f.service.resolvePersonaContent(f.personaId, f.coreVersionId, "general_chat", "hi", coreContent)

        assertFalse(text.contains("SECRET_VALUE"))
        assertTrue(text.contains("X"))
    }

    // E. Missing skill mapping -> safe fallback
    @Test
    fun `E - missing skill mapping falls back to core content`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("name", "X") })
        f.seedField("name")
        // no skill requirement seeded

        val text = f.service.resolvePersonaContent(f.personaId, f.coreVersionId, "general_chat", "hi", coreContent)

        assertEquals(coreContent, text)
    }

    // F. Unknown skill -> safe fallback
    @Test
    fun `F - unknown skill key falls back to core content`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("name", "X") })
        f.seedField("name")
        f.seedSkill("general_chat", required = listOf("name"))

        val text = f.service.resolvePersonaContent(f.personaId, f.coreVersionId, "some_unknown_skill", "hi", coreContent)

        assertEquals(coreContent, text)
    }

    // G. Resolver error -> core.content fallback
    @Test
    fun `G - resolver failure states still fall back to core content, service never throws`() {
        // PersonaFieldAuthorizer is a concrete (non-open) class, so it cannot be subclassed or
        // mocked here to force a checked exception -- that internal RESOLVER_ERROR path is
        // already covered directly by PersonaFieldAuthorizerTest (Phase 46). This test instead
        // proves the facade's own defensive try/catch (ProductionPersonaContextService lines
        // 55-59 and 69-73) does not break the "never throws, always falls back" contract when
        // given personaId/personaCoreVersionId that were never created in this fixture's DB.
        val f = Fixture()
        val unknownPersonaId = UUID.randomUUID()
        val unknownCoreVersionId = UUID.randomUUID()

        val text = f.service.resolvePersonaContent(unknownPersonaId, unknownCoreVersionId, "general_chat", "hi", coreContent)
        val result = f.service.resolve(unknownPersonaId, unknownCoreVersionId, "general_chat", "hi")

        assertEquals(coreContent, text)
        assertTrue(result == null || result.status != PersonaContextStatus.USABLE)
    }

    // H. Rendering policy changes text only
    @Test
    fun `H - rendering policy changes only the rendered text, never which fields are used`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("age", 27) })
        f.seedField("identity.age", path = "age")
        f.seedSkill("about", required = listOf("identity.age"))

        val bareService = ProductionPersonaContextService(f.authorizer, BareScalarRendering)
        val labeledService = ProductionPersonaContextService(f.authorizer, AgeOnlyLabeledScalarRendering)

        val bareResult = bareService.resolve(f.personaId, f.coreVersionId, "about", "hi")!!
        val labeledResult = labeledService.resolve(f.personaId, f.coreVersionId, "about", "hi")!!

        assertEquals(bareResult.includedFieldIds, labeledResult.includedFieldIds)
        assertEquals(bareResult.status, labeledResult.status)
        assertTrue(bareResult.renderedText.contains("27") && !bareResult.renderedText.contains("Age: 27"))
        assertTrue(labeledResult.renderedText.contains("Age: 27"))
    }

    // I. PersonaCoreVersions.content is never lost when context is unusable
    @Test
    fun `I - core content is never lost across every unusable condition`() {
        val f = Fixture()
        // 1: no document at all
        assertEquals(coreContent, f.service.resolvePersonaContent(f.personaId, f.coreVersionId, "general_chat", "hi", coreContent))

        f.seedDocument(JsonObject(emptyMap()))
        // 2: empty document
        assertEquals(coreContent, f.service.resolvePersonaContent(f.personaId, f.coreVersionId, "general_chat", "hi", coreContent))
    }

    // J. No raw master document can reach the final model content
    @Test
    fun `J - an unregistered raw field never reaches the final content under any state`() {
        val f = Fixture()
        val marker = "RAW_DOCUMENT_MARKER"
        f.seedDocument(buildJsonObject { put("name", "X"); put("unregistered_field", marker) })
        f.seedField("name")
        // no skill mapping -> forces the unsafe-in-the-old-architecture fallback path

        val text = f.service.resolvePersonaContent(f.personaId, f.coreVersionId, "general_chat", "hi", coreContent)

        assertFalse(text.contains(marker))
        assertEquals(coreContent, text)
    }

    // K. No restricted field can reach final model content (broader sweep)
    @Test
    fun `K - restricted field cannot reach final content across multiple unusable states`() {
        val f = Fixture()
        f.seedField("intimate_anatomy", sensitivity = "restricted", allowedContexts = listOf("romantic_intimacy"))
        f.seedField("name")

        // no document
        assertFalse(f.service.resolvePersonaContent(f.personaId, f.coreVersionId, "general_chat", "hi", coreContent).contains("intimate"))

        f.seedDocument(buildJsonObject { put("name", "X"); put("intimate_anatomy", "SECRET") })
        // no mapping
        assertFalse(f.service.resolvePersonaContent(f.personaId, f.coreVersionId, "general_chat", "hi", coreContent).contains("SECRET"))

        f.seedSkill("general_chat", required = listOf("name", "intimate_anatomy"))
        // mapped, but not authorized for this skill
        assertFalse(f.service.resolvePersonaContent(f.personaId, f.coreVersionId, "general_chat", "hi", coreContent).contains("SECRET"))
    }

    // L. personaId is correctly propagated
    @Test
    fun `L - personaId and personaVersion are correctly propagated on the result`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("name", "X") })
        f.seedField("name")
        f.seedSkill("general_chat", required = listOf("name"))

        val result = f.service.resolve(f.personaId, f.coreVersionId, "general_chat", "hi")!!

        assertEquals(f.personaId, result.personaId)
        assertEquals(f.coreVersionId, result.personaVersion)
        assertEquals("general_chat", result.skillKey)
    }

    // M. No accidental dependency on research PersonaContextResolver
    @Test
    fun `M - the production service and its dependencies never reference the research resolver`() {
        val serviceClass = ProductionPersonaContextService::class.java
        val authorizerClass = PersonaFieldAuthorizer::class.java
        listOf(serviceClass, authorizerClass).forEach { klass ->
            val referencesResearchResolver = klass.declaredFields.any {
                it.type.name.contains("PersonaContextResolver")
            } || klass.declaredMethods.any { m ->
                m.parameterTypes.any { it.name.contains("PersonaContextResolver") } ||
                    m.returnType.name.contains("PersonaContextResolver")
            }
            assertFalse(referencesResearchResolver, "${klass.simpleName} must not reference the research PersonaContextResolver")
        }
    }
}
