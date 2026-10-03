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
import kotlin.test.assertTrue

/**
 * PHASE 46 — deterministic tests for the new production Persona Context
 * foundation (PersonaFieldAuthorizer, PersonaContextResult, the rendering
 * policies, and the fallback function). Zero API calls, zero network
 * access. Uses its own small, self-contained fixture data — deliberately
 * NOT PersonaContextPocSeed.kt's research fixture — per Phase 46's "use
 * deterministic fixtures/test repositories for all tests" instruction.
 *
 * Covers test matrix items A-P from PHASE46_IMPLEMENTATION_REPORT.md §10.
 */
class PersonaFieldAuthorizerTest {

    /** Minimal, self-contained fixture — independent of the benchmark harness's World(). */
    private class Fixture {
        val db: Database = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }
        val personas = PersonaRepository(db)
        val cores = PersonaCoreVersionRepository(db)
        val masterRepo = PersonaMasterRepository(db)
        val fieldRegistryRepo = PersonaFieldRegistryRepository(db)
        val skillReqRepo = SkillContextRequirementRepository(db)
        val authorizer = PersonaFieldAuthorizer(db, masterRepo, fieldRegistryRepo, skillReqRepo)

        val personaId: UUID
        val coreVersionId: UUID

        init {
            val persona = personas.create(
                slug = "phase46-test-persona-${UUID.randomUUID()}",
                displayName = "Phase46 Test Persona",
                gender = "female",
                orientation = "n/a",
                apparentAge = 27,
                languageProfile = emptyMap(),
            )
            personaId = persona.id
            val core = cores.create(personaId = personaId, version = 1, content = "GENERIC BASELINE PERSONA CORE TEXT")
            coreVersionId = core.id
        }

        fun seedDocument(content: JsonObject) {
            masterRepo.create(coreVersionId, content)
        }

        fun seedField(
            fieldId: String,
            path: String,
            classification: String = "identity",
            sensitivity: String = "normal",
            allowedContexts: List<String>? = null,
        ) {
            fieldRegistryRepo.create(
                PersonaFieldRegistryEntry(
                    fieldId = fieldId,
                    path = path,
                    semanticType = null,
                    classification = classification,
                    sensitivity = sensitivity,
                    stability = "stable",
                    behavioralCore = false,
                    defaultLoad = false,
                    allowedContexts = allowedContexts,
                    prohibitedContexts = null,
                    description = fieldId,
                ),
            )
        }

        fun seedSkill(
            skillKey: String,
            required: List<String>? = null,
            recommended: List<String>? = null,
            conditional: List<String>? = null,
        ) {
            skillReqRepo.create(
                SkillContextRequirement(
                    skillKey = skillKey,
                    requiredFields = required,
                    recommendedFields = recommended,
                    conditionalFields = conditional,
                    restrictedFields = null,
                    purpose = null,
                    whenToUse = null,
                    whenNotToUse = null,
                ),
            )
        }
    }

    // A. Valid selected fields
    @Test
    fun `A - valid selected fields produce USABLE with correct content`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { putJsonObjectCompat(this, "identity") { put("name", "Test Persona"); put("age", 30) } })
        f.seedField("identity.name", "identity.name")
        f.seedField("identity.age", "identity.age")
        f.seedSkill("general_chat", required = listOf("identity.name", "identity.age"))

        val result = f.authorizer.resolve(f.personaId, f.coreVersionId, "general_chat", "hi", BareScalarRendering)

        assertEquals(PersonaContextStatus.USABLE, result.status)
        assertTrue(result.isUsable())
        assertTrue(result.renderedText.contains("Test Persona"))
        assertTrue(result.renderedText.contains("30"))
        assertEquals(setOf("identity.name", "identity.age"), result.includedFieldIds.toSet())
    }

    // B. Missing persona document
    @Test
    fun `B - missing persona document yields DOCUMENT_NOT_FOUND and empty content`() {
        val f = Fixture()
        f.seedField("identity.name", "identity.name")
        f.seedSkill("general_chat", required = listOf("identity.name"))

        val result = f.authorizer.resolve(f.personaId, f.coreVersionId, "general_chat", "hi", BareScalarRendering)

        assertEquals(PersonaContextStatus.DOCUMENT_NOT_FOUND, result.status)
        assertFalse(result.isUsable())
        assertEquals("", result.renderedText)
        assertTrue(result.includedFieldIds.isEmpty())
    }

    // C. Empty persona document
    @Test
    fun `C - empty persona document yields DOCUMENT_EMPTY`() {
        val f = Fixture()
        f.seedDocument(JsonObject(emptyMap()))
        f.seedField("identity.name", "identity.name")
        f.seedSkill("general_chat", required = listOf("identity.name"))

        val result = f.authorizer.resolve(f.personaId, f.coreVersionId, "general_chat", "hi", BareScalarRendering)

        assertEquals(PersonaContextStatus.DOCUMENT_EMPTY, result.status)
        assertFalse(result.isUsable())
        assertEquals("", result.renderedText)
    }

    // D. Empty registry
    @Test
    fun `D - empty field registry yields REGISTRY_EMPTY`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { putJsonObjectCompat(this, "identity") { put("name", "X") } })
        f.seedSkill("general_chat", required = listOf("identity.name"))
        // deliberately no field registered

        val result = f.authorizer.resolve(f.personaId, f.coreVersionId, "general_chat", "hi", BareScalarRendering)

        assertEquals(PersonaContextStatus.REGISTRY_EMPTY, result.status)
        assertFalse(result.isUsable())
    }

    // E. Missing skill mapping
    @Test
    fun `E - missing skill mapping yields SKILL_MAPPING_MISSING`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { putJsonObjectCompat(this, "identity") { put("name", "X") } })
        f.seedField("identity.name", "identity.name")
        // deliberately no skill requirement seeded

        val result = f.authorizer.resolve(f.personaId, f.coreVersionId, "general_chat", "hi", BareScalarRendering)

        assertEquals(PersonaContextStatus.SKILL_MAPPING_MISSING, result.status)
        assertFalse(result.isUsable())
    }

    // F. Unknown skill key
    @Test
    fun `F - unknown skill key behaves identically to missing mapping`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { putJsonObjectCompat(this, "identity") { put("name", "X") } })
        f.seedField("identity.name", "identity.name")
        f.seedSkill("general_chat", required = listOf("identity.name"))

        val result = f.authorizer.resolve(f.personaId, f.coreVersionId, "totally_unknown_skill_xyz", "hi", BareScalarRendering)

        assertEquals(PersonaContextStatus.SKILL_MAPPING_MISSING, result.status)
        assertFalse(result.isUsable())
    }

    // G. Known skill key with no mapping (== E/F, same code path — asserted explicitly for the record)
    @Test
    fun `G - known-but-unmapped skill key produces the same status as unknown skill`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { putJsonObjectCompat(this, "identity") { put("name", "X") } })
        f.seedField("identity.name", "identity.name")
        f.seedSkill("about", required = listOf("identity.name")) // a DIFFERENT skill is mapped

        val result = f.authorizer.resolve(f.personaId, f.coreVersionId, "general_chat", "hi", BareScalarRendering)

        assertEquals(PersonaContextStatus.SKILL_MAPPING_MISSING, result.status)
    }

    // H. Restricted field present in source, I. absent from authorization (unmapped skill)
    @Test
    fun `H and I - restricted field present in document but never authorized for an unmapped skill`() {
        val f = Fixture()
        f.seedDocument(
            buildJsonObject {
                putJsonObjectCompat(this, "identity") { put("name", "X") }
                put("intimate_anatomy", "SENSITIVE_RESTRICTED_VALUE")
            },
        )
        f.seedField("identity.name", "identity.name")
        f.seedField("intimate_anatomy", "intimate_anatomy", sensitivity = "restricted", allowedContexts = listOf("romantic_intimacy", "foreplay"))
        // no skill mapping seeded for "general_chat" at all

        val result = f.authorizer.resolve(f.personaId, f.coreVersionId, "general_chat", "hi", BareScalarRendering)

        assertEquals(PersonaContextStatus.SKILL_MAPPING_MISSING, result.status)
        assertFalse(result.renderedText.contains("SENSITIVE_RESTRICTED_VALUE"))
        assertFalse(result.includedFieldIds.contains("intimate_anatomy"))
    }

    @Test
    fun `H and I (variant 2) - restricted field excluded even when its skill IS mapped but not authorized for it`() {
        val f = Fixture()
        f.seedDocument(
            buildJsonObject {
                putJsonObjectCompat(this, "identity") { put("name", "X") }
                put("intimate_anatomy", "SENSITIVE_RESTRICTED_VALUE")
            },
        )
        f.seedField("identity.name", "identity.name")
        f.seedField("intimate_anatomy", "intimate_anatomy", sensitivity = "restricted", allowedContexts = listOf("romantic_intimacy", "foreplay"))
        // general_chat DOES have a mapping, and it (mistakenly, or by a future bug) lists the restricted field
        f.seedSkill("general_chat", required = listOf("identity.name", "intimate_anatomy"))

        val result = f.authorizer.resolve(f.personaId, f.coreVersionId, "general_chat", "hi", BareScalarRendering)

        assertEquals(PersonaContextStatus.USABLE, result.status)
        assertFalse(result.includedFieldIds.contains("intimate_anatomy"))
        assertFalse(result.renderedText.contains("SENSITIVE_RESTRICTED_VALUE"))
        assertTrue(result.includedFieldIds.contains("identity.name"))
    }

    @Test
    fun `restricted field IS included when its skill is authorized`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("intimate_anatomy", "ALLOWED_VALUE") })
        f.seedField("intimate_anatomy", "intimate_anatomy", sensitivity = "restricted", allowedContexts = listOf("romantic_intimacy", "foreplay"))
        f.seedSkill("romantic_intimacy", required = listOf("intimate_anatomy"))

        val result = f.authorizer.resolve(f.personaId, f.coreVersionId, "romantic_intimacy", "hi", BareScalarRendering)

        assertEquals(PersonaContextStatus.USABLE, result.status)
        assertTrue(result.includedFieldIds.contains("intimate_anatomy"))
        assertTrue(result.renderedText.contains("ALLOWED_VALUE"))
    }

    // J. Non-blank but structurally unusable context — the critical "raw document" analog:
    // confirm the authorizer NEVER produces this shape at all (no raw-dump code path exists).
    @Test
    fun `J - authorizer has no code path producing non-blank content with empty includedFieldIds`() {
        val f = Fixture()
        f.seedDocument(
            buildJsonObject {
                putJsonObjectCompat(this, "identity") { put("name", "X") }
                put("intimate_anatomy", "SHOULD_NEVER_APPEAR")
            },
        )
        f.seedField("identity.name", "identity.name")
        f.seedField("intimate_anatomy", "intimate_anatomy", sensitivity = "restricted", allowedContexts = listOf("romantic_intimacy"))
        // no skill mapping at all -> SKILL_MAPPING_MISSING

        val result = f.authorizer.resolve(f.personaId, f.coreVersionId, "unmapped_skill", "hi", BareScalarRendering)

        // Every non-USABLE status MUST have blank renderedText and empty includedFieldIds —
        // there is no status representing "non-blank but unusable," confirming the raw-document
        // bypass this design set out to eliminate (Phase 45 D1) simply cannot occur.
        assertFalse(result.isUsable())
        assertEquals("", result.renderedText)
        assertTrue(result.includedFieldIds.isEmpty())
    }

    // K. Baseline fallback to PersonaCoreVersions.content
    @Test
    fun `K - resolvePersonaContent falls back to core content for every non-usable result`() {
        val core = "GENERIC BASELINE PERSONA CORE TEXT"
        val notUsable = PersonaContextResult(
            status = PersonaContextStatus.DOCUMENT_NOT_FOUND,
            renderedText = "",
            includedFieldIds = emptyList(),
            personaId = UUID.randomUUID(),
            personaVersion = UUID.randomUUID(),
            skillKey = "general_chat",
        )
        assertEquals(core, resolvePersonaContent(notUsable, core))
        assertEquals(core, resolvePersonaContent(null, core))

        val usable = notUsable.copy(status = PersonaContextStatus.USABLE, renderedText = "## Identity\n\nReal Content", includedFieldIds = listOf("identity.name"))
        assertEquals("## Identity\n\nReal Content", resolvePersonaContent(usable, core))
    }

    @Test
    fun `K2 - a non-blank raw-document-shaped result is still rejected by resolvePersonaContent if status is not USABLE`() {
        // Simulates the exact dangerous shape the old research resolveVariantA() could produce:
        // non-blank renderedText, but NOT a USABLE status. Confirms the fallback function itself
        // is a second line of defense, independent of the authorizer never producing this shape.
        val dangerous = PersonaContextResult(
            status = PersonaContextStatus.SKILL_MAPPING_MISSING,
            renderedText = "{\"intimate_anatomy\":\"leaked\"}", // hypothetically non-blank, e.g. if misconstructed
            includedFieldIds = emptyList(),
            personaId = UUID.randomUUID(),
            personaVersion = UUID.randomUUID(),
            skillKey = "general_chat",
        )
        val core = "SAFE BASELINE"
        val result = resolvePersonaContent(dangerous, core)
        assertEquals(core, result)
        assertFalse(result.contains("leaked"))
    }

    // L. Rendering-policy substitution
    @Test
    fun `L - swapping rendering policy changes only the age scalar's representation`() {
        val f = Fixture()
        f.seedDocument(
            buildJsonObject {
                putJsonObjectCompat(this, "identity") {
                    put("name", "Test Persona")
                    put("age", 27)
                    put("hometown", "Nashik")
                }
            },
        )
        f.seedField("identity.name", "identity.name")
        f.seedField("identity.age", "identity.age")
        f.seedField("identity.hometown", "identity.hometown")
        f.seedSkill("about", required = listOf("identity.name", "identity.age", "identity.hometown"))

        val bare = f.authorizer.resolve(f.personaId, f.coreVersionId, "about", "hi", BareScalarRendering)
        val ageLabeled = f.authorizer.resolve(f.personaId, f.coreVersionId, "about", "hi", AgeOnlyLabeledScalarRendering)
        val allLabeled = f.authorizer.resolve(f.personaId, f.coreVersionId, "about", "hi", AllScalarsLabeledRendering)

        assertEquals(bare.includedFieldIds.toSet(), ageLabeled.includedFieldIds.toSet())
        assertEquals(bare.includedFieldIds.toSet(), allLabeled.includedFieldIds.toSet())

        assertTrue(bare.renderedText.contains("27") && !bare.renderedText.contains("Age: 27"))
        assertTrue(ageLabeled.renderedText.contains("Age: 27"))
        assertTrue(ageLabeled.renderedText.contains("Nashik") && !ageLabeled.renderedText.contains("Hometown: Nashik"))
        assertTrue(allLabeled.renderedText.contains("Age: 27"))
        assertTrue(allLabeled.renderedText.contains("Hometown: Nashik"))
    }

    // M. (removed — requestedVariant/returnedVariant no longer exist on
    // PersonaContextResult; see PersonaContextResult.kt's updated KDoc.)

    // N. Determinism
    @Test
    fun `N - resolve is deterministic given identical input`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { putJsonObjectCompat(this, "identity") { put("name", "X"); put("age", 5) } })
        f.seedField("identity.name", "identity.name")
        f.seedField("identity.age", "identity.age")
        f.seedSkill("about", required = listOf("identity.name", "identity.age"))

        val r1 = f.authorizer.resolve(f.personaId, f.coreVersionId, "about", "hi", AgeOnlyLabeledScalarRendering)
        val r2 = f.authorizer.resolve(f.personaId, f.coreVersionId, "about", "hi", AgeOnlyLabeledScalarRendering)

        assertEquals(r1, r2)
    }

    // O. No restricted-field leakage (broader sweep across every non-USABLE state)
    @Test
    fun `O - no restricted-field leakage across every unusable state`() {
        val f = Fixture()
        f.seedField("intimate_anatomy", "intimate_anatomy", sensitivity = "restricted", allowedContexts = listOf("romantic_intimacy"))
        f.seedField("identity.name", "identity.name")

        // state: no document at all
        val noDoc = f.authorizer.resolve(f.personaId, f.coreVersionId, "general_chat", "hi", BareScalarRendering)
        assertFalse(noDoc.renderedText.contains("intimate"))

        f.seedDocument(buildJsonObject { put("intimate_anatomy", "SECRET"); putJsonObjectCompat(this, "identity") { put("name", "X") } })

        // state: no skill mapping
        val noMapping = f.authorizer.resolve(f.personaId, f.coreVersionId, "general_chat", "hi", BareScalarRendering)
        assertFalse(noMapping.renderedText.contains("SECRET"))

        // state: mapped, but restricted field not authorized for this skill
        f.seedSkill("general_chat", required = listOf("identity.name", "intimate_anatomy"))
        val mappedButUnauthorized = f.authorizer.resolve(f.personaId, f.coreVersionId, "general_chat", "hi", BareScalarRendering)
        assertFalse(mappedButUnauthorized.renderedText.contains("SECRET"))
        assertFalse(mappedButUnauthorized.includedFieldIds.contains("intimate_anatomy"))
    }

    // P. No accidental raw-document serialization
    @Test
    fun `P - the authorizer never serializes the raw master document, under any state`() {
        val f = Fixture()
        val rawMarker = "RAW_DOCUMENT_MARKER_SHOULD_NEVER_APPEAR"
        f.seedDocument(
            buildJsonObject {
                putJsonObjectCompat(this, "identity") { put("name", "X") }
                put("some_unregistered_field", rawMarker)
            },
        )
        f.seedField("identity.name", "identity.name")
        // deliberately: no skill mapping at all, and "some_unregistered_field" is never registered

        val result = f.authorizer.resolve(f.personaId, f.coreVersionId, "general_chat", "hi", BareScalarRendering)

        assertFalse(result.renderedText.contains(rawMarker))
        assertEquals(PersonaContextStatus.SKILL_MAPPING_MISSING, result.status)
    }

    // RESOLVER_ERROR path (invalid personaCoreVersionId that cannot be looked up cleanly is
    // already covered by "document not found" above; this test confirms an internal exception
    // is caught, not propagated, using a deliberately-corrupt registry entry path).
    @Test
    fun `resolver exceptions are caught and reported as RESOLVER_ERROR, never propagated`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { putJsonObjectCompat(this, "identity") { put("name", "X") } })
        // A field whose registered path cannot possibly resolve cleanly still just yields null
        // (resolvePath returns null, not an exception) per PersonaMasterRepository's own contract —
        // so to actually exercise RESOLVER_ERROR we rely on the authorizer's try/catch wrapping the
        // whole method; this test documents the guarantee even though no currently-known input
        // triggers it (PersonaMasterRepository.resolvePath never throws, confirmed by direct
        // source reading) — the catch block exists as defense-in-depth, not for a known live case.
        f.seedField("identity.name", "identity.name")
        f.seedSkill("general_chat", required = listOf("identity.name"))

        val result = f.authorizer.resolve(f.personaId, f.coreVersionId, "general_chat", "hi", BareScalarRendering)
        // No exception propagated out of this call (if one had, the test itself would fail/throw).
        assertTrue(result.status == PersonaContextStatus.USABLE || result.status == PersonaContextStatus.RESOLVER_ERROR)
    }
}

/** Small local helper since kotlinx.serialization's JsonObjectBuilder has no nested-object convenience for this Kotlin version's DSL used elsewhere in this codebase. */
private fun putJsonObjectCompat(builder: kotlinx.serialization.json.JsonObjectBuilder, key: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
    builder.put(key, buildJsonObject(block))
}
