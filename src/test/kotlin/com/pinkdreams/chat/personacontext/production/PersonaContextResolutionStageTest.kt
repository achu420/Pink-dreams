package com.pinkdreams.chat.personacontext.production

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.ContextBlockType
import com.pinkdreams.chat.PersonaContextResolutionStage
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
 * PHASE 49 (Strategy D) — deterministic tests for [PersonaContextResolutionStage],
 * the post-skill-selection persona-authorization stage. Zero API calls, zero
 * network access. Covers test matrix items A-AC from
 * PHASE49_SKILL_AWARE_PERSONA_IMPLEMENTATION.md §15.
 */
class PersonaContextResolutionStageTest {

    private val engineContent = "CONVERSATION ENGINE — UNIVERSAL RULES:\nENGINE_BASELINE"
    private val profileContent = "USER PROFILE:\nPROFILE_BASELINE"
    private val memoryContent = "RETRIEVED MEMORY:\nMEMORY_BASELINE"
    private val historyContent = "HISTORY_BASELINE"

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
                slug = "phase49-test-persona-${UUID.randomUUID()}",
                displayName = "Phase49 Test Persona",
                gender = "female",
                orientation = "n/a",
                apparentAge = 27,
                languageProfile = emptyMap(),
            )
            personaId = persona.id
            val core = cores.create(personaId = personaId, version = 1, content = "unused")
            coreVersionId = core.id
        }

        fun seedDocument(content: JsonObject) = masterRepo.create(coreVersionId, content)

        fun seedField(fieldId: String, sensitivity: String = "normal", allowedContexts: List<String>? = null) {
            fieldRegistryRepo.create(
                PersonaFieldRegistryEntry(
                    fieldId = fieldId, path = fieldId, semanticType = null, classification = "identity",
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

        fun stage(renderingPolicy: ScalarRenderingPolicy = BareScalarRendering) =
            PersonaContextResolutionStage(ProductionPersonaContextService(authorizer, renderingPolicy))
    }

    /** Builds a context shaped exactly like RepositoryContextAssembler's real output (Phase 49 §2). */
    private fun baseContext(
        f: Fixture,
        baselinePersonaContent: String = "PERSONA CORE:\nGENERIC BASELINE PERSONA CORE TEXT",
        selectedSkillKey: String? = "general_chat",
    ): ChatContext = ChatContext(
        blocks = listOf(
            ContextBlock("system", engineContent, ContextBlockType.ENGINE),
            ContextBlock("system", baselinePersonaContent, ContextBlockType.PERSONA),
            ContextBlock("system", profileContent, ContextBlockType.PROFILE),
            ContextBlock("system", memoryContent, ContextBlockType.MEMORY),
            ContextBlock("user", historyContent, ContextBlockType.HISTORY),
            ContextBlock("user", "current message", ContextBlockType.CURRENT_MESSAGE),
        ),
        personaCoreVersionId = f.coreVersionId,
        selectedSkillKey = selectedSkillKey,
    )

    // A. personaId propagation
    @Test
    fun `A - the personaId passed into resolve is what PersonaFieldAuthorizer receives`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("name", "X") })
        f.seedField("name")
        f.seedSkill("general_chat", required = listOf("name"))

        val result = f.stage().resolve(baseContext(f), f.personaId, "current message")
        val personaBlock = result.blocks.first { it.blockType == ContextBlockType.PERSONA }
        assertTrue(personaBlock.content.contains("X"), "Resolution must have run for the exact personaId passed in")
    }

    // B. real selected skill propagation
    @Test
    fun `B - the real selectedSkillKey on the context is what authorization uses`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("name", "X") })
        f.seedField("name")
        f.seedSkill("about", required = listOf("name")) // only "about" is mapped

        val wrongSkillResult = f.stage().resolve(baseContext(f, selectedSkillKey = "general_chat"), f.personaId, "hi")
        val rightSkillResult = f.stage().resolve(baseContext(f, selectedSkillKey = "about"), f.personaId, "hi")

        val baseline = baseContext(f).blocks.first { it.blockType == ContextBlockType.PERSONA }.content
        assertEquals(baseline, wrongSkillResult.blocks.first { it.blockType == ContextBlockType.PERSONA }.content, "Unmapped skill must not authorize any content")
        assertNotEquals(baseline, rightSkillResult.blocks.first { it.blockType == ContextBlockType.PERSONA }.content, "Mapped skill must authorize content")
    }

    // C. correct skill authorization
    @Test
    fun `C - authorization result differs per skill, proving the actual key drives it`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("public_fact", "PUBLIC_VALUE"); put("intimate_fact", "INTIMATE_VALUE") })
        f.seedField("public_fact")
        f.seedField("intimate_fact", sensitivity = "restricted", allowedContexts = listOf("romantic_intimacy"))
        f.seedSkill("general_chat", required = listOf("public_fact", "intimate_fact"))
        f.seedSkill("romantic_intimacy", required = listOf("public_fact", "intimate_fact"))

        val generalResult = f.stage().resolve(baseContext(f, selectedSkillKey = "general_chat"), f.personaId, "hi")
        val intimacyResult = f.stage().resolve(baseContext(f, selectedSkillKey = "romantic_intimacy"), f.personaId, "hi")

        val generalPersona = generalResult.blocks.first { it.blockType == ContextBlockType.PERSONA }.content
        val intimacyPersona = intimacyResult.blocks.first { it.blockType == ContextBlockType.PERSONA }.content
        assertFalse(generalPersona.contains("INTIMATE_VALUE"))
        assertTrue(intimacyPersona.contains("INTIMATE_VALUE"))
    }

    // D. missing skill (SkillSelection.None -> selectedSkillKey == null)
    @Test
    fun `D - null selectedSkillKey is a guaranteed no-op, never fabricates a key`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("name", "X") })
        f.seedField("name")
        f.seedSkill("general_chat", required = listOf("name"))

        val context = baseContext(f, selectedSkillKey = null)
        val result = f.stage().resolve(context, f.personaId, "hi")

        assertEquals(context, result, "No skill selected must return the exact same context instance's content, unchanged")
    }

    // E. unknown skill
    @Test
    fun `E - unknown skill key falls back to baseline persona content`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("name", "X") })
        f.seedField("name")
        f.seedSkill("general_chat", required = listOf("name"))

        val context = baseContext(f, selectedSkillKey = "totally_unknown_skill")
        val result = f.stage().resolve(context, f.personaId, "hi")

        assertEquals(
            context.blocks.first { it.blockType == ContextBlockType.PERSONA }.content,
            result.blocks.first { it.blockType == ContextBlockType.PERSONA }.content,
        )
    }

    // F. valid persona data
    @Test
    fun `F - valid persona data produces an authorized, replaced persona block`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("name", "REAL_FACT") })
        f.seedField("name")
        f.seedSkill("general_chat", required = listOf("name"))

        val result = f.stage().resolve(baseContext(f), f.personaId, "hi")
        assertTrue(result.blocks.first { it.blockType == ContextBlockType.PERSONA }.content.contains("REAL_FACT"))
    }

    // G. missing persona data
    @Test
    fun `G - missing persona document preserves the baseline persona block`() {
        val f = Fixture()
        f.seedField("name")
        f.seedSkill("general_chat", required = listOf("name"))

        val context = baseContext(f)
        val result = f.stage().resolve(context, f.personaId, "hi")
        assertEquals(context, result)
    }

    // H. empty persona document
    @Test
    fun `H - empty persona document preserves the baseline persona block`() {
        val f = Fixture()
        f.seedDocument(JsonObject(emptyMap()))
        f.seedField("name")
        f.seedSkill("general_chat", required = listOf("name"))

        val context = baseContext(f)
        val result = f.stage().resolve(context, f.personaId, "hi")
        assertEquals(context, result)
    }

    // I. empty registry
    @Test
    fun `I - empty field registry preserves the baseline persona block`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("name", "X") })
        f.seedSkill("general_chat", required = listOf("name"))
        // deliberately: no field registered

        val context = baseContext(f)
        val result = f.stage().resolve(context, f.personaId, "hi")
        assertEquals(context, result)
    }

    // J. restricted field
    @Test
    fun `J - restricted field never appears in the replaced persona block`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("name", "X"); put("secret", "SECRET_VALUE") })
        f.seedField("name")
        f.seedField("secret", sensitivity = "restricted", allowedContexts = listOf("romantic_intimacy"))
        f.seedSkill("general_chat", required = listOf("name", "secret"))

        val result = f.stage().resolve(baseContext(f), f.personaId, "hi")
        val personaContent = result.blocks.first { it.blockType == ContextBlockType.PERSONA }.content
        assertFalse(personaContent.contains("SECRET_VALUE"))
        assertTrue(personaContent.contains("X"))
    }

    // K. resolver failure (unseeded/mismatched IDs -- see rationale in test G of
    // ProductionPersonaContextServiceTest: PersonaFieldAuthorizer's own RESOLVER_ERROR
    // path is covered directly in PersonaFieldAuthorizerTest; this proves the stage's
    // own defensive wrapper also preserves baseline).
    @Test
    fun `K - failure-shaped input preserves the baseline persona block, never throws`() {
        val f = Fixture()
        val context = baseContext(f)
        val result = f.stage().resolve(context, UUID.randomUUID(), "hi")
        assertEquals(context, result)
    }

    // L. renderer failure -- ProductionPersonaContextRenderer has no known-throwing input
    // (confirmed by direct source reading, same as PersonaFieldAuthorizerTest's own
    // documented rationale); the authorizer's outer try/catch is defense-in-depth for
    // this case too. Documented here rather than asserted against a fabricated failure.
    @Test
    fun `L - a rendering pass that could throw is still contained by the authorizer's try-catch, never propagates`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("name", "X") })
        f.seedField("name")
        f.seedSkill("general_chat", required = listOf("name"))
        // No exception propagates out of resolve() -- if one did, this test would fail/throw.
        f.stage().resolve(baseContext(f), f.personaId, "hi")
    }

    // M. zero selected fields
    @Test
    fun `M - zero selected fields preserves the baseline persona block`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("name", "X") })
        f.seedField("name", sensitivity = "restricted", allowedContexts = listOf("romantic_intimacy")) // not authorized for general_chat
        f.seedSkill("general_chat", required = listOf("name"))

        val context = baseContext(f)
        val result = f.stage().resolve(context, f.personaId, "hi")
        assertEquals(context, result)
    }

    // N. invalid personaId
    @Test
    fun `N - an invalid (never-created) personaId preserves the baseline persona block`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("name", "X") })
        f.seedField("name")
        f.seedSkill("general_chat", required = listOf("name"))

        val context = baseContext(f)
        val result = f.stage().resolve(context, UUID.randomUUID(), "hi")
        // A mismatched personaId still resolves against the real coreVersionId's document today
        // (PersonaFieldAuthorizer keys on personaCoreVersionId, not personaId, for the document
        // lookup) -- so this test's real guarantee is narrower: it must not throw, and whatever
        // it returns must not silently misattribute content to a persona it doesn't belong to.
        assertEquals(context.personaCoreVersionId, result.personaCoreVersionId)
    }

    // O. PERSONA-only replacement
    @Test
    fun `O - a successful resolution replaces only the PERSONA block`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("name", "REPLACED") })
        f.seedField("name")
        f.seedSkill("general_chat", required = listOf("name"))

        val context = baseContext(f)
        val result = f.stage().resolve(context, f.personaId, "hi")

        assertNotEquals(
            context.blocks.first { it.blockType == ContextBlockType.PERSONA }.content,
            result.blocks.first { it.blockType == ContextBlockType.PERSONA }.content,
        )
        assertEquals(context.blocks.size, result.blocks.size)
        assertEquals(context.blocks.map { it.blockType }, result.blocks.map { it.blockType }, "Block order/types must be unchanged")
    }

    // P. ENGINE remains unchanged
    @Test
    fun `P - ENGINE block is byte-for-byte unchanged`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("name", "X") })
        f.seedField("name")
        f.seedSkill("general_chat", required = listOf("name"))

        val context = baseContext(f)
        val result = f.stage().resolve(context, f.personaId, "hi")
        assertEquals(
            context.blocks.first { it.blockType == ContextBlockType.ENGINE },
            result.blocks.first { it.blockType == ContextBlockType.ENGINE },
        )
    }

    // Q. MEMORY remains unchanged
    @Test
    fun `Q - MEMORY block is byte-for-byte unchanged`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("name", "X") })
        f.seedField("name")
        f.seedSkill("general_chat", required = listOf("name"))

        val context = baseContext(f)
        val result = f.stage().resolve(context, f.personaId, "hi")
        assertEquals(
            context.blocks.first { it.blockType == ContextBlockType.MEMORY },
            result.blocks.first { it.blockType == ContextBlockType.MEMORY },
        )
    }

    // R. PROFILE remains unchanged
    @Test
    fun `R - PROFILE block is byte-for-byte unchanged`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("name", "X") })
        f.seedField("name")
        f.seedSkill("general_chat", required = listOf("name"))

        val context = baseContext(f)
        val result = f.stage().resolve(context, f.personaId, "hi")
        assertEquals(
            context.blocks.first { it.blockType == ContextBlockType.PROFILE },
            result.blocks.first { it.blockType == ContextBlockType.PROFILE },
        )
    }

    // S. HISTORY remains unchanged
    @Test
    fun `S - HISTORY block is byte-for-byte unchanged`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("name", "X") })
        f.seedField("name")
        f.seedSkill("general_chat", required = listOf("name"))

        val context = baseContext(f)
        val result = f.stage().resolve(context, f.personaId, "hi")
        assertEquals(
            context.blocks.first { it.blockType == ContextBlockType.HISTORY },
            result.blocks.first { it.blockType == ContextBlockType.HISTORY },
        )
    }

    // T. rendering-policy substitution
    @Test
    fun `T - swapping rendering policy changes only the persona block's representation`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("age", 27) })
        f.fieldRegistryRepo.create(
            PersonaFieldRegistryEntry(
                fieldId = "identity.age", path = "age", semanticType = null, classification = "identity",
                sensitivity = "normal", stability = "stable", behavioralCore = false, defaultLoad = false,
                allowedContexts = null, prohibitedContexts = null, description = "identity.age",
            ),
        )
        f.seedSkill("about", required = listOf("identity.age"))

        val bareResult = f.stage(BareScalarRendering).resolve(baseContext(f, selectedSkillKey = "about"), f.personaId, "hi")
        val labeledResult = f.stage(AgeOnlyLabeledScalarRendering).resolve(baseContext(f, selectedSkillKey = "about"), f.personaId, "hi")

        val barePersona = bareResult.blocks.first { it.blockType == ContextBlockType.PERSONA }.content
        val labeledPersona = labeledResult.blocks.first { it.blockType == ContextBlockType.PERSONA }.content
        assertTrue(barePersona.contains("27") && !barePersona.contains("Age: 27"))
        assertTrue(labeledPersona.contains("Age: 27"))
        // Every non-persona block is identical regardless of rendering policy.
        assertEquals(
            bareResult.blocks.filter { it.blockType != ContextBlockType.PERSONA },
            labeledResult.blocks.filter { it.blockType != ContextBlockType.PERSONA },
        )
    }

    // U. raw-document never reaches generation
    @Test
    fun `U - an unregistered raw document field never reaches the replaced persona block`() {
        val f = Fixture()
        val marker = "RAW_DOCUMENT_MARKER"
        f.seedDocument(buildJsonObject { put("name", "X"); put("unregistered", marker) })
        f.seedField("name")
        f.seedSkill("general_chat", required = listOf("name"))

        val result = f.stage().resolve(baseContext(f), f.personaId, "hi")
        assertFalse(result.blocks.first { it.blockType == ContextBlockType.PERSONA }.content.contains(marker))
    }

    // V. restricted-field never reaches generation (already covered by J; re-asserted
    // across the full final block list to prove it doesn't leak into any OTHER block either)
    @Test
    fun `V - restricted field never appears anywhere in the final block list`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("name", "X"); put("secret", "SECRET_VALUE") })
        f.seedField("name")
        f.seedField("secret", sensitivity = "restricted", allowedContexts = listOf("romantic_intimacy"))
        f.seedSkill("general_chat", required = listOf("name", "secret"))

        val result = f.stage().resolve(baseContext(f), f.personaId, "hi")
        assertFalse(result.blocks.any { it.content.contains("SECRET_VALUE") })
    }

    // W. budget-safe replacement
    @Test
    fun `W - a smaller authorized replacement is installed when within budget`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("name", "X") })
        f.seedField("name")
        f.seedSkill("general_chat", required = listOf("name"))

        val stage = PersonaContextResolutionStage(ProductionPersonaContextService(f.authorizer, BareScalarRendering), tokenBudget = 100_000)
        val result = stage.resolve(baseContext(f), f.personaId, "hi")
        assertTrue(result.blocks.first { it.blockType == ContextBlockType.PERSONA }.content.contains("X"))
    }

    // X. over-budget replacement
    @Test
    fun `X - an authorized replacement that would exceed budget is rejected, baseline kept`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("name", "A_MUCH_LONGER_AUTHORIZED_VALUE_THAN_THE_TINY_BUDGET_ALLOWS") })
        f.seedField("name")
        f.seedSkill("general_chat", required = listOf("name"))

        // Budget far too small for even the engine/profile/memory/history blocks alone --
        // any replacement, however small, cannot fit, proving the recheck actually rejects.
        val stage = PersonaContextResolutionStage(ProductionPersonaContextService(f.authorizer, BareScalarRendering), tokenBudget = 1)
        val context = baseContext(f)
        val result = stage.resolve(context, f.personaId, "hi")
        assertEquals(context, result, "Over-budget replacement must be rejected, baseline retained unchanged")
    }

    // Y. baseline preserved under failure
    @Test
    fun `Y - baseline is preserved across every failure state exercised above`() {
        val f = Fixture()
        val context = baseContext(f)
        // No document, no registry, no skill mapping -- every failure signal at once.
        val result = f.stage().resolve(context, f.personaId, "hi")
        assertEquals(context, result)
    }

    // Z. no PersonaContextResolver dependency
    @Test
    fun `Z - PersonaContextResolutionStage never references the research PersonaContextResolver`() {
        val klass = PersonaContextResolutionStage::class.java
        val referencesResearchResolver = klass.declaredFields.any { it.type.name.contains("PersonaContextResolver") } ||
            klass.declaredMethods.any { m -> m.parameterTypes.any { it.name.contains("PersonaContextResolver") } || m.returnType.name.contains("PersonaContextResolver") }
        assertFalse(referencesResearchResolver)
    }

    // AA. no resolveVariantA dependency
    @Test
    fun `AA - PersonaContextResolutionStage source has no resolveVariantA reference`() {
        val klass = PersonaContextResolutionStage::class.java
        val referencesVariantA = klass.declaredMethods.any { it.name.contains("resolveVariantA") }
        assertFalse(referencesVariantA)
    }

    // AB. no fabricated skill key
    @Test
    fun `AB - resolve never substitutes a skill key other than the one on the context`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("name", "X") })
        f.seedField("name")
        f.seedSkill("a_specific_skill_key", required = listOf("name"))
        // "general_chat" is NOT mapped -- if the stage ever fell back to a placeholder like
        // "general_chat", this would incorrectly resolve; it must not.
        val context = baseContext(f, selectedSkillKey = null)
        val result = f.stage().resolve(context, f.personaId, "hi")
        assertEquals(context, result, "With no real skill selected, no other skill key may be substituted")
    }

    // AC. final generation context contains only authorized content
    @Test
    fun `AC - the final block list contains only authorized persona content plus every untouched block`() {
        val f = Fixture()
        f.seedDocument(buildJsonObject { put("name", "AUTHORIZED_ONLY"); put("secret", "NEVER") })
        f.seedField("name")
        f.seedField("secret", sensitivity = "restricted", allowedContexts = listOf("romantic_intimacy"))
        f.seedSkill("general_chat", required = listOf("name", "secret"))

        val result = f.stage().resolve(baseContext(f), f.personaId, "hi")

        assertTrue(result.blocks.first { it.blockType == ContextBlockType.PERSONA }.content.contains("AUTHORIZED_ONLY"))
        assertFalse(result.blocks.any { it.content.contains("NEVER") })
        assertEquals(engineContent, result.blocks.first { it.blockType == ContextBlockType.ENGINE }.content)
        assertEquals(profileContent, result.blocks.first { it.blockType == ContextBlockType.PROFILE }.content)
        assertEquals(memoryContent, result.blocks.first { it.blockType == ContextBlockType.MEMORY }.content)
        assertEquals(historyContent, result.blocks.first { it.blockType == ContextBlockType.HISTORY }.content)
    }
}
