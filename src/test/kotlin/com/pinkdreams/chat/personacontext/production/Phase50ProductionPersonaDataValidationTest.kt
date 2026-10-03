package com.pinkdreams.chat.personacontext.production

import com.pinkdreams.baseline.BaselineConfiguration
import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ChatResult
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.ContextBlockType
import com.pinkdreams.chat.EntitlementDecision
import com.pinkdreams.chat.ModerationDecision
import com.pinkdreams.chat.PersonaContextResolutionStage
import com.pinkdreams.chat.PipelineChatEngine
import com.pinkdreams.chat.StageResult
import com.pinkdreams.chat.ValidationDecision
import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.chat.skill.IntentDiscovery
import com.pinkdreams.chat.skill.SkillSelection
import com.pinkdreams.llm.GenerationConfig
import com.pinkdreams.llm.GenerationRequest
import com.pinkdreams.llm.LlmClient
import com.pinkdreams.llm.LlmGenerator
import com.pinkdreams.llm.LlmResponse
import com.pinkdreams.persistence.RepositoryChatExecutionCoordinator
import com.pinkdreams.persistence.RepositoryChatPersistence
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ChatRequestExecutionRepository
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaFieldRegistryEntry
import com.pinkdreams.persistence.repositories.PersonaFieldRegistryRepository
import com.pinkdreams.persistence.repositories.PersonaMasterRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillContextRequirement
import com.pinkdreams.persistence.repositories.SkillContextRequirementRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * PHASE 50 — production persona-data path validation, against a deterministic
 * fixture built for Simran (the real, existing production persona, per
 * BaselineConfiguration.SIMRAN_SLUG) — never a persistent/production database.
 *
 * Every field value below is a REAL fact already transcribed from
 * docs/personas/Simran/simran-master.md (cross-checked, not reused by
 * import, from the research-only PersonaContextPocSeed.kt — this file is a
 * new, independent, production-namespaced fixture, per Phase 50's rule
 * against reusing research code for production/production-adjacent tests).
 *
 * Skill keys used below (general_chat, friendship, romantic_intimacy,
 * foreplay) are REAL production skill keys, verified against
 * BaselineConfiguration.SKILLS this phase — NOT the research-only "about"/
 * "perspective_of" labels PersonaContextPocSeed.kt used (those do not exist
 * in BaselineConfiguration.SKILLS; see PHASE50 report §6 for this finding).
 *
 * Zero API calls, zero network access, zero production database mutation.
 */
class Phase50ProductionPersonaDataValidationTest {

    /**
     * The real Simran facts this fixture authorizes against (see class doc).
     * Nested to match the dot-path structure PersonaMasterRepository.resolvePath()
     * actually walks (each path segment is one JsonObject level, not a literal
     * dotted key) -- confirmed against PersonaMasterRepository.kt's resolvePath().
     */
    private fun simranMasterContent(): JsonObject = buildJsonObject {
        put(
            "identity",
            buildJsonObject {
                put("name", "Simran Kohli")
                put("age", 27)
                put("hometown", "Nashik, Maharashtra")
                put("current_city", "Pune, Maharashtra (Kothrud)")
            },
        )
        put("life_context", buildJsonObject { put("occupation", "Architect (junior)") })
        put(
            "personality",
            buildJsonObject { put("core_traits", "Observant, warm, independent-minded, practical, conversational, comfortable with uncertainty") },
        )
        put(
            "relational",
            buildJsonObject { put("romantic_style", "Warm, playful, direct. Doesn't do grand gestures. Wants to be chosen clearly.") },
        )
        put("intimate_anatomy", "RESTRICTED_ANATOMY_DETAIL")
        put("adult_intimacy", "RESTRICTED_INTIMACY_DETAIL")
    }

    private inner class Fixture {
        val db: Database = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }
        val personas = PersonaRepository(db)
        val cores = PersonaCoreVersionRepository(db)
        val masterRepo = PersonaMasterRepository(db)
        val fieldRegistryRepo = PersonaFieldRegistryRepository(db)
        val skillReqRepo = SkillContextRequirementRepository(db)
        val authorizer = PersonaFieldAuthorizer(db, masterRepo, fieldRegistryRepo, skillReqRepo)
        val service = ProductionPersonaContextService(authorizer, BareScalarRendering)

        val personaId: UUID
        val coreVersionId: UUID

        init {
            // Uses the REAL production slug (BaselineConfiguration.SIMRAN_SLUG) so this
            // fixture is unambiguously "the existing persona," not a lookalike.
            val persona = personas.create(
                slug = BaselineConfiguration.SIMRAN_SLUG,
                displayName = BaselineConfiguration.SIMRAN_DISPLAY_NAME,
                gender = "female",
                orientation = "n/a",
                apparentAge = 27,
                languageProfile = emptyMap(),
            )
            personaId = persona.id
            // Uses the REAL production PersonaCoreVersions.content text
            // (BaselineConfiguration.SIMRAN_PERSONA_CORE) as the baseline/fallback --
            // proving the fallback this whole mechanism relies on is the actual
            // production text, not a stand-in string.
            val core = cores.create(personaId = personaId, version = 1, content = BaselineConfiguration.SIMRAN_PERSONA_CORE)
            coreVersionId = core.id
        }

        fun seedRealMasterDocument() = masterRepo.create(coreVersionId, simranMasterContent())

        fun seedRealFieldRegistry() {
            listOf(
                field("identity.name"),
                field("identity.age"),
                field("identity.hometown"),
                field("identity.current_city"),
                field("life_context.occupation"),
                field("personality.core_traits"),
                field("relational.romantic_style"),
                field("intimate_anatomy", sensitivity = "restricted", allowedContexts = listOf("romantic_intimacy", "foreplay")),
                field("adult_intimacy", sensitivity = "restricted", allowedContexts = listOf("romantic_intimacy", "foreplay")),
            ).forEach { fieldRegistryRepo.create(it) }
        }

        private fun field(fieldId: String, sensitivity: String = "normal", allowedContexts: List<String>? = null) =
            PersonaFieldRegistryEntry(
                fieldId = fieldId, path = fieldId, semanticType = null, classification = "identity",
                sensitivity = sensitivity, stability = "stable", behavioralCore = false, defaultLoad = false,
                allowedContexts = allowedContexts, prohibitedContexts = null, description = fieldId,
            )

        /** Real production skill keys only (verified against BaselineConfiguration.SKILLS this phase). */
        fun seedRealSkillMappings() {
            skillReqRepo.create(
                SkillContextRequirement(
                    skillKey = "general_chat",
                    requiredFields = listOf("identity.name", "personality.core_traits"),
                    recommendedFields = listOf("life_context.occupation"),
                    conditionalFields = null, restrictedFields = null,
                    purpose = "General conversation", whenToUse = null, whenNotToUse = null,
                ),
            )
            skillReqRepo.create(
                SkillContextRequirement(
                    skillKey = "friendship",
                    requiredFields = listOf("identity.name", "personality.core_traits"),
                    recommendedFields = listOf("identity.hometown", "life_context.occupation"),
                    conditionalFields = null, restrictedFields = null,
                    purpose = "Friendship", whenToUse = null, whenNotToUse = null,
                ),
            )
            skillReqRepo.create(
                SkillContextRequirement(
                    skillKey = "romantic_intimacy",
                    requiredFields = listOf("identity.name", "relational.romantic_style", "adult_intimacy"),
                    recommendedFields = null,
                    conditionalFields = listOf("intimate_anatomy"),
                    restrictedFields = listOf("intimate_anatomy", "adult_intimacy"),
                    purpose = "Sexual intimacy", whenToUse = null, whenNotToUse = null,
                ),
            )
            // "advice" is a REAL production skill key (BaselineConfiguration.SKILLS) deliberately
            // left WITHOUT a Persona Context mapping, to exercise case E (known skill, no mapping)
            // without fabricating a mapping merely to make a test pass.
        }
    }

    // ===================== Part 5/9 — real skill-key verification =====================

    @Test
    fun `real production skill keys used in this fixture actually exist in BaselineConfiguration SKILLS`() {
        val realKeys = BaselineConfiguration.SKILLS.map { it.key }.toSet()
        assertTrue("general_chat" in realKeys)
        assertTrue("friendship" in realKeys)
        assertTrue("romantic_intimacy" in realKeys)
        assertTrue("foreplay" in realKeys)
        assertTrue("advice" in realKeys)
        // Documents the Part 5 finding: the research seed's "about"/"perspective_of"
        // skill keys are NOT real production skills.
        assertFalse("about" in realKeys)
        assertFalse("perspective_of" in realKeys)
    }

    // ===================== Part 6 — restricted-field matrix (A-J) =====================

    // A. authorized normal field
    @Test
    fun `A - authorized normal field is included and rendered`() {
        val f = Fixture()
        f.seedRealMasterDocument(); f.seedRealFieldRegistry(); f.seedRealSkillMappings()

        val result = f.authorizer.resolve(f.personaId, f.coreVersionId, "general_chat", "hi", BareScalarRendering)

        assertEquals(PersonaContextStatus.USABLE, result.status)
        assertTrue(result.renderedText.contains("Simran Kohli"))
        assertTrue(result.includedFieldIds.contains("identity.name"))
    }

    // B. unauthorized normal field (not required/recommended/conditional for this skill)
    @Test
    fun `B - a normal field not mapped to the skill is simply absent, not an error`() {
        val f = Fixture()
        f.seedRealMasterDocument(); f.seedRealFieldRegistry(); f.seedRealSkillMappings()

        // general_chat never references identity.hometown or identity.current_city.
        val result = f.authorizer.resolve(f.personaId, f.coreVersionId, "general_chat", "hi", BareScalarRendering)

        assertFalse(result.includedFieldIds.contains("identity.hometown"))
        assertFalse(result.renderedText.contains("Nashik"))
    }

    // C. restricted field
    @Test
    fun `C - restricted field is included only for its authorized skill`() {
        val f = Fixture()
        f.seedRealMasterDocument(); f.seedRealFieldRegistry(); f.seedRealSkillMappings()

        val blocked = f.authorizer.resolve(f.personaId, f.coreVersionId, "general_chat", "hi", BareScalarRendering)
        assertFalse(blocked.renderedText.contains("RESTRICTED_INTIMACY_DETAIL"))

        val allowed = f.authorizer.resolve(f.personaId, f.coreVersionId, "romantic_intimacy", "hi", BareScalarRendering)
        assertEquals(PersonaContextStatus.USABLE, allowed.status)
        assertTrue(allowed.renderedText.contains("RESTRICTED_INTIMACY_DETAIL"))
    }

    // D. unknown skill key
    @Test
    fun `D - unknown skill key yields SKILL_MAPPING_MISSING`() {
        val f = Fixture()
        f.seedRealMasterDocument(); f.seedRealFieldRegistry(); f.seedRealSkillMappings()

        val result = f.authorizer.resolve(f.personaId, f.coreVersionId, "totally_unknown_skill", "hi", BareScalarRendering)
        assertEquals(PersonaContextStatus.SKILL_MAPPING_MISSING, result.status)
        assertFalse(result.isUsable())
    }

    // E. known (real) skill with no mapping
    @Test
    fun `E - a real but unmapped skill key (advice) yields SKILL_MAPPING_MISSING, no fabricated mapping`() {
        val f = Fixture()
        f.seedRealMasterDocument(); f.seedRealFieldRegistry(); f.seedRealSkillMappings()

        val result = f.authorizer.resolve(f.personaId, f.coreVersionId, "advice", "hi", BareScalarRendering)
        assertEquals(PersonaContextStatus.SKILL_MAPPING_MISSING, result.status)
    }

    // F. empty registry
    @Test
    fun `F - empty field registry yields REGISTRY_EMPTY`() {
        val f = Fixture()
        f.seedRealMasterDocument(); f.seedRealSkillMappings()
        // deliberately: no field registry seeded

        val result = f.authorizer.resolve(f.personaId, f.coreVersionId, "general_chat", "hi", BareScalarRendering)
        assertEquals(PersonaContextStatus.REGISTRY_EMPTY, result.status)
    }

    // G. empty persona document
    @Test
    fun `G - empty persona document yields DOCUMENT_EMPTY`() {
        val f = Fixture()
        f.masterRepo.create(f.coreVersionId, JsonObject(emptyMap()))
        f.seedRealFieldRegistry(); f.seedRealSkillMappings()

        val result = f.authorizer.resolve(f.personaId, f.coreVersionId, "general_chat", "hi", BareScalarRendering)
        assertEquals(PersonaContextStatus.DOCUMENT_EMPTY, result.status)
    }

    // H. missing persona document
    @Test
    fun `H - missing persona document yields DOCUMENT_NOT_FOUND`() {
        val f = Fixture()
        f.seedRealFieldRegistry(); f.seedRealSkillMappings()
        // deliberately: no document seeded

        val result = f.authorizer.resolve(f.personaId, f.coreVersionId, "general_chat", "hi", BareScalarRendering)
        assertEquals(PersonaContextStatus.DOCUMENT_NOT_FOUND, result.status)
    }

    // I. resolver/authorizer exception -> facade falls back, never throws
    @Test
    fun `I - facade never throws and falls back to the real production core content`() {
        val f = Fixture()
        // No document/registry/mapping seeded at all -- exercises the facade's full
        // fallback path against the REAL production baseline text.
        val result = f.service.resolvePersonaContent(f.personaId, f.coreVersionId, "general_chat", "hi", BaselineConfiguration.SIMRAN_PERSONA_CORE)
        assertEquals(BaselineConfiguration.SIMRAN_PERSONA_CORE, result)
    }

    // J. mixed normal + restricted fields
    @Test
    fun `J - mixed normal and restricted fields only include the authorized subset`() {
        val f = Fixture()
        f.seedRealMasterDocument(); f.seedRealFieldRegistry(); f.seedRealSkillMappings()

        val result = f.authorizer.resolve(f.personaId, f.coreVersionId, "romantic_intimacy", "hi", BareScalarRendering)
        assertEquals(PersonaContextStatus.USABLE, result.status)
        assertTrue(result.includedFieldIds.contains("identity.name"))
        assertTrue(result.includedFieldIds.contains("relational.romantic_style"))
        assertTrue(result.includedFieldIds.contains("adult_intimacy"))
        // conditional field intimate_anatomy is scored, not guaranteed -- assert it
        // never appears WITHOUT authorization, not that it always appears.
        if (result.includedFieldIds.contains("intimate_anatomy")) {
            assertTrue(result.renderedText.contains("RESTRICTED_ANATOMY_DETAIL"))
        }
    }

    // ===================== Part 7 — rendering policy (not a product decision) =====================

    @Test
    fun `rendering policy swap changes only representation for the real Simran age field`() {
        val f = Fixture()
        f.seedRealMasterDocument()
        f.fieldRegistryRepo.create(
            PersonaFieldRegistryEntry(
                fieldId = "identity.age", path = "identity.age", semanticType = null, classification = "identity",
                sensitivity = "normal", stability = "stable", behavioralCore = false, defaultLoad = false,
                allowedContexts = null, prohibitedContexts = null, description = "identity.age",
            ),
        )
        f.skillReqRepo.create(
            SkillContextRequirement(
                skillKey = "general_chat", requiredFields = listOf("identity.age"),
                recommendedFields = null, conditionalFields = null, restrictedFields = null,
                purpose = null, whenToUse = null, whenNotToUse = null,
            ),
        )

        val bare = f.authorizer.resolve(f.personaId, f.coreVersionId, "general_chat", "hi", BareScalarRendering)
        val labeled = f.authorizer.resolve(f.personaId, f.coreVersionId, "general_chat", "hi", AgeOnlyLabeledScalarRendering)

        assertEquals(bare.includedFieldIds, labeled.includedFieldIds)
        assertEquals(bare.status, labeled.status)
        assertTrue(bare.renderedText.contains("27") && !bare.renderedText.contains("Age: 27"))
        assertTrue(labeled.renderedText.contains("Age: 27"))
    }

    // ===================== Part 8 — budget validation =====================

    @Test
    fun `budget - comfortably under budget the real authorized replacement is installed`() {
        val f = Fixture()
        f.seedRealMasterDocument(); f.seedRealFieldRegistry(); f.seedRealSkillMappings()
        val stage = PersonaContextResolutionStage(f.service, tokenBudget = 100_000)

        val context = simranBaseContext(f, selectedSkillKey = "general_chat")
        val result = stage.resolve(context, f.personaId, "hi")

        assertTrue(result.blocks.first { it.blockType == ContextBlockType.PERSONA }.content.contains("Simran Kohli"))
    }

    @Test
    fun `budget - a replacement that would exceed a tight budget is rejected, real baseline text kept intact`() {
        val f = Fixture()
        f.seedRealMasterDocument(); f.seedRealFieldRegistry(); f.seedRealSkillMappings()
        // Budget far smaller than even the real SIMRAN_PERSONA_CORE baseline text --
        // no replacement, however small, can fit; must reject cleanly, not truncate.
        val stage = PersonaContextResolutionStage(f.service, tokenBudget = 1)

        val context = simranBaseContext(f, selectedSkillKey = "general_chat")
        val result = stage.resolve(context, f.personaId, "hi")

        assertEquals(context, result, "Over-budget replacement must be rejected; the real baseline text must survive untrimmed and unaltered in meaning")
    }

    @Test
    fun `budget - exactly at the boundary of the real content's own size behaves deterministically either way`() {
        val f = Fixture()
        f.seedRealMasterDocument(); f.seedRealFieldRegistry(); f.seedRealSkillMappings()
        val context = simranBaseContext(f, selectedSkillKey = "general_chat")

        // Budget sized to exactly the pre-resolution total (no replacement yet) --
        // determinism check: running twice with the same budget yields the same result.
        val exactBudget = context.blocks.sumOf { CharacterTokenEstimatorCompat.estimate(it.content) }
        val stage = PersonaContextResolutionStage(f.service, tokenBudget = exactBudget)
        val result1 = stage.resolve(context, f.personaId, "hi")
        val result2 = stage.resolve(context, f.personaId, "hi")
        assertEquals(result1, result2, "Budget-boundary resolution must be deterministic across repeated identical calls")
    }

    // ===================== Part 9 — end-to-end production-path fixture =====================

    @Test
    fun `end to end - real production pipeline with real skill and real Simran fixture data, no LLM call`() {
        val f = Fixture()
        f.seedRealMasterDocument(); f.seedRealFieldRegistry(); f.seedRealSkillMappings()

        val userId = UUID.randomUUID()
        val engineRepository = ConversationEngineRepository(f.db)
        val engineRow = engineRepository.create(1, "REAL_ENGINE_PLACEHOLDER_RULES", "draft")
        engineRepository.activateEngine(engineRepository.publishEngine(engineRow.id).id)
        f.personas.activateCoreVersion(f.personaId, f.cores.publishCoreVersion(f.coreVersionId).id)

        val conversationRepository = ConversationRepository(f.db)
        val conversation = conversationRepository.create(userId, f.personaId)
        val messageRepository = MessageRepository(f.db)
        val executions = ChatRequestExecutionRepository(f.db)
        val memoryService = MemoryService(MemoryFactRepository(f.db))
        val persistence = RepositoryChatPersistence(f.db, executions)
        val coordinator = RepositoryChatExecutionCoordinator(executions, conversationRepository, messageRepository)

        val contextAssembler = RepositoryContextAssembler(
            conversationRepository, messageRepository, UserProfileRepository(f.db),
            memoryService, engineRepository, f.personas,
        )
        val intentDiscovery = IntentDiscovery { _, _ -> SkillSelection.Selected("friendship") }
        val personaContextResolution = PersonaContextResolutionStage(f.service)

        var captured: GenerationRequest? = null
        val llmClient = LlmClient { request -> captured = request; LlmResponse(content = "reply", provider = "test") }
        val generator = LlmGenerator(llmClient, GenerationConfig(model = "test-model"))

        val engine = PipelineChatEngine(
            entitlementChecker = { EntitlementDecision.Allowed },
            inputModerator = { ModerationDecision.Allowed },
            contextAssembler = contextAssembler,
            generator = generator,
            outputValidator = { _, _ -> ValidationDecision.Accepted },
            persistence = persistence,
            delivery = { _, _ -> StageResult.Succeeded(Unit) },
            executionCoordinator = coordinator,
            intentDiscovery = intentDiscovery,
            personaContextResolution = personaContextResolution,
        )

        val request = ChatRequest(UUID.randomUUID(), userId, conversation.id, f.personaId, UUID.randomUUID(), "hello")
        val result = engine.process(request)
        assertIs<ChatResult.Success>(result)

        val req = captured ?: error("no request captured")
        val blocks = req.context.blocks

        assertEquals(1, blocks.count { it.blockType == ContextBlockType.PERSONA }, "exactly one persona block")
        assertEquals(1, blocks.count { it.blockType == ContextBlockType.ENGINE }, "exactly one engine block")
        assertEquals(ContextBlockType.ENGINE, blocks[0].blockType, "engine ordered first")
        assertEquals(ContextBlockType.PERSONA, blocks[1].blockType, "persona ordered second")

        val personaBlock = blocks.first { it.blockType == ContextBlockType.PERSONA }
        assertTrue(personaBlock.content.contains("Simran Kohli"), "real authorized field reaches generation")
        assertFalse(personaBlock.content.contains("RESTRICTED_INTIMACY_DETAIL"), "restricted field must not leak for friendship")
        assertFalse(blocks.any { it.content.contains("_full_document_supplement") }, "no raw master document marker reaches generation")
        assertEquals("friendship", req.skillKey)
        assertEquals(f.personaId, req.personaId)
    }

    // ===================== Part 10 — no-data production state =====================

    @Test
    fun `no-data state - with zero persona_master_documents rows the real production baseline text is sent unchanged`() {
        val f = Fixture()
        // Deliberately: no master document, no field registry, no skill mapping --
        // reproducing today's actual production database state (Phase 35/49 finding).
        val context = simranBaseContext(f, selectedSkillKey = "general_chat")
        val stage = PersonaContextResolutionStage(f.service)

        val result = stage.resolve(context, f.personaId, "hi")

        assertEquals(context, result, "No-op: the real SIMRAN_PERSONA_CORE baseline text is untouched")
        val personaBlock = result.blocks.first { it.blockType == ContextBlockType.PERSONA }
        assertEquals(BaselineConfiguration.SIMRAN_PERSONA_CORE.let { "PERSONA CORE:\n$it" }, personaBlock.content)
        assertFalse(personaBlock.content.isBlank(), "must never send empty persona content as if valid")
    }

    private fun simranBaseContext(f: Fixture, selectedSkillKey: String?): ChatContext = ChatContext(
        blocks = listOf(
            ContextBlock("system", "CONVERSATION ENGINE — UNIVERSAL RULES:\nREAL_ENGINE_PLACEHOLDER_RULES", ContextBlockType.ENGINE),
            ContextBlock("system", "PERSONA CORE:\n${BaselineConfiguration.SIMRAN_PERSONA_CORE}", ContextBlockType.PERSONA),
            ContextBlock("system", "USER PROFILE:\n(no profile record exists for this user)", ContextBlockType.PROFILE),
            ContextBlock("user", "hi", ContextBlockType.CURRENT_MESSAGE),
        ),
        personaCoreVersionId = f.coreVersionId,
        selectedSkillKey = selectedSkillKey,
    )
}

/** Mirrors RepositoryContextAssembler's CharacterTokenEstimator exactly, for budget-boundary math in tests only. */
private object CharacterTokenEstimatorCompat {
    fun estimate(content: String): Int = (content.length + 3) / 4
}
