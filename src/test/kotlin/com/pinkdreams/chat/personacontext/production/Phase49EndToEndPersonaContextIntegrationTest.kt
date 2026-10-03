package com.pinkdreams.chat.personacontext.production

import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ChatResult
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * PHASE 49 (Strategy D) — end-to-end deterministic pipeline test.
 *
 * ChatRequest -> personaId -> initial context assembly -> real SKILL_SELECTION
 * (via a fixed, deterministic IntentDiscovery -- no LLM call) -> selected skill
 * -> post-skill persona resolution (real PersonaFieldAuthorizer +
 * ProductionPersonaContextService, real seeded data) -> PERSONA block
 * replacement -> captured GenerationRequest at the actual LlmClient boundary.
 *
 * Zero external API calls: the LlmClient here is a recording fake, exactly
 * like every other pipeline regression test in this codebase (e.g.
 * ConversationProvenanceRoundTripTest, LlmClientBoundaryAuditTest).
 */
class Phase49EndToEndPersonaContextIntegrationTest {

    private class RecordingLlmClient(private val reply: String) : LlmClient {
        var lastRequest: GenerationRequest? = null
        override fun generate(request: GenerationRequest): LlmResponse {
            lastRequest = request
            return LlmResponse(content = reply, provider = "test", model = "test-model")
        }
    }

    @Test
    fun `real skill selection drives authorized persona resolution end to end with no unauthorized content reaching the LlmClient`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userId = UUID.randomUUID()
        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("e2e-persona-${UUID.randomUUID()}", "E2E Persona", "female", "straight", 27, emptyMap())
        val coreRepository = PersonaCoreVersionRepository(db)
        val core = coreRepository.create(persona.id, 1, "FALLBACK_BASELINE_CORE_CONTENT", "draft")
        val publishedCore = coreRepository.publishCoreVersion(core.id)
        personaRepository.activateCoreVersion(persona.id, publishedCore.id)

        val engineRepository = ConversationEngineRepository(db)
        val engineRow = engineRepository.create(1, "ENGINE_RULES_CONTENT", "draft")
        engineRepository.activateEngine(engineRepository.publishEngine(engineRow.id).id)

        // Real persona master document + field registry + skill mapping -- the
        // authorization data this end-to-end test exercises for real, not a fixture.
        val masterRepo = PersonaMasterRepository(db)
        masterRepo.create(
            publishedCore.id,
            buildJsonObject {
                put("public_hobby", "PUBLIC_HOBBY_VALUE")
                put("intimate_detail", "SHOULD_NEVER_REACH_GENERATION")
            },
        )
        val fieldRegistryRepo = PersonaFieldRegistryRepository(db)
        fieldRegistryRepo.create(
            PersonaFieldRegistryEntry(
                fieldId = "public_hobby", path = "public_hobby", semanticType = null, classification = "identity",
                sensitivity = "normal", stability = "stable", behavioralCore = false, defaultLoad = false,
                allowedContexts = null, prohibitedContexts = null, description = "public_hobby",
            ),
        )
        fieldRegistryRepo.create(
            PersonaFieldRegistryEntry(
                fieldId = "intimate_detail", path = "intimate_detail", semanticType = null, classification = "identity",
                sensitivity = "restricted", stability = "stable", behavioralCore = false, defaultLoad = false,
                allowedContexts = listOf("romantic_intimacy"), prohibitedContexts = null, description = "intimate_detail",
            ),
        )
        val skillReqRepo = SkillContextRequirementRepository(db)
        skillReqRepo.create(
            SkillContextRequirement(
                skillKey = "general_chat", requiredFields = listOf("public_hobby", "intimate_detail"),
                recommendedFields = null, conditionalFields = null, restrictedFields = null,
                purpose = null, whenToUse = null, whenNotToUse = null,
            ),
        )

        val conversationRepository = ConversationRepository(db)
        val conversation = conversationRepository.create(userId, persona.id)
        val messageRepository = MessageRepository(db)
        val executions = ChatRequestExecutionRepository(db)
        val memoryService = MemoryService(MemoryFactRepository(db))
        val persistence = RepositoryChatPersistence(db, executions)
        val coordinator = RepositoryChatExecutionCoordinator(executions, conversationRepository, messageRepository)

        val contextAssembler = RepositoryContextAssembler(
            conversationRepository,
            messageRepository,
            UserProfileRepository(db),
            memoryService,
            engineRepository,
            personaRepository,
        )

        // Deterministic, real skill selection -- no LLM call, but a REAL selected
        // key flowing through PipelineStage.SKILL_SELECTION exactly as
        // LlmIntentDiscovery would produce in production.
        val intentDiscovery = IntentDiscovery { _, _ -> SkillSelection.Selected("general_chat") }

        val authorizer = PersonaFieldAuthorizer(db, masterRepo, fieldRegistryRepo, skillReqRepo)
        val personaContextResolution = PersonaContextResolutionStage(ProductionPersonaContextService(authorizer, BareScalarRendering))

        val llmClient = RecordingLlmClient("assistant reply")
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

        val request = ChatRequest(
            requestId = UUID.randomUUID(),
            userId = userId,
            conversationId = conversation.id,
            personaId = persona.id,
            clientMessageId = UUID.randomUUID(),
            content = "tell me about yourself",
        )

        val result = engine.process(request)
        assertIs<ChatResult.Success>(result)

        val captured = llmClient.lastRequest ?: error("LlmClient did not capture a request")
        val blocks = captured.context.blocks

        // 1. Real, non-fabricated skill key reached authorization.
        assertEquals("general_chat", captured.skillKey)

        // 2. personaId corresponds exactly to the request.
        assertEquals(persona.id, captured.personaId)

        // 3. PERSONA block was authorized/replaced -- contains the authorized public
        // field, and is no longer the raw PersonaCoreVersions.content fallback.
        val personaBlock = blocks.first { it.blockType == ContextBlockType.PERSONA }
        assertTrue(personaBlock.content.contains("PUBLIC_HOBBY_VALUE"), "Authorized field must reach the final persona block")
        assertFalse(personaBlock.content.contains("FALLBACK_BASELINE_CORE_CONTENT"), "Authorized replacement must have occurred, not the raw baseline")

        // 4. Restricted field never reaches ANY block in the final request.
        assertFalse(blocks.any { it.content.contains("SHOULD_NEVER_REACH_GENERATION") }, "Restricted field must never reach the LlmClient boundary")

        // 5. ENGINE remains independent and untouched.
        val engineBlock = blocks.first { it.blockType == ContextBlockType.ENGINE }
        assertTrue(engineBlock.content.contains("ENGINE_RULES_CONTENT"))

        // 6. No dependency on the research PersonaContextResolver anywhere in this path.
        assertFalse(
            PersonaContextResolutionStage::class.java.declaredFields.any { it.type.name.contains("PersonaContextResolver") },
            "Production pipeline must not reference the research PersonaContextResolver",
        )
    }

    @Test
    fun `when skill selection returns None the persona block is the unauthorized baseline, unchanged from today`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userId = UUID.randomUUID()
        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("e2e-persona-none-${UUID.randomUUID()}", "E2E Persona", "female", "straight", 27, emptyMap())
        val coreRepository = PersonaCoreVersionRepository(db)
        val core = coreRepository.create(persona.id, 1, "FALLBACK_BASELINE_CORE_CONTENT", "draft")
        val publishedCore = coreRepository.publishCoreVersion(core.id)
        personaRepository.activateCoreVersion(persona.id, publishedCore.id)

        val engineRepository = ConversationEngineRepository(db)
        val engineRow = engineRepository.create(1, "ENGINE_RULES_CONTENT", "draft")
        engineRepository.activateEngine(engineRepository.publishEngine(engineRow.id).id)

        val masterRepo = PersonaMasterRepository(db)
        masterRepo.create(publishedCore.id, buildJsonObject { put("public_hobby", "PUBLIC_HOBBY_VALUE") })
        val fieldRegistryRepo = PersonaFieldRegistryRepository(db)
        fieldRegistryRepo.create(
            PersonaFieldRegistryEntry(
                fieldId = "public_hobby", path = "public_hobby", semanticType = null, classification = "identity",
                sensitivity = "normal", stability = "stable", behavioralCore = false, defaultLoad = false,
                allowedContexts = null, prohibitedContexts = null, description = "public_hobby",
            ),
        )
        val skillReqRepo = SkillContextRequirementRepository(db)
        skillReqRepo.create(
            SkillContextRequirement(
                skillKey = "general_chat", requiredFields = listOf("public_hobby"),
                recommendedFields = null, conditionalFields = null, restrictedFields = null,
                purpose = null, whenToUse = null, whenNotToUse = null,
            ),
        )

        val conversationRepository = ConversationRepository(db)
        val conversation = conversationRepository.create(userId, persona.id)
        val messageRepository = MessageRepository(db)
        val executions = ChatRequestExecutionRepository(db)
        val memoryService = MemoryService(MemoryFactRepository(db))
        val persistence = RepositoryChatPersistence(db, executions)
        val coordinator = RepositoryChatExecutionCoordinator(executions, conversationRepository, messageRepository)

        val contextAssembler = RepositoryContextAssembler(
            conversationRepository,
            messageRepository,
            UserProfileRepository(db),
            memoryService,
            engineRepository,
            personaRepository,
        )

        // Skill selection deliberately returns None -- proves no key is fabricated
        // and the pre-existing unauthorized baseline survives unchanged.
        val intentDiscovery = IntentDiscovery { _, _ -> SkillSelection.None }

        val authorizer = PersonaFieldAuthorizer(db, masterRepo, fieldRegistryRepo, skillReqRepo)
        val personaContextResolution = PersonaContextResolutionStage(ProductionPersonaContextService(authorizer, BareScalarRendering))

        val llmClient = RecordingLlmClient("assistant reply")
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

        val request = ChatRequest(
            requestId = UUID.randomUUID(),
            userId = userId,
            conversationId = conversation.id,
            personaId = persona.id,
            clientMessageId = UUID.randomUUID(),
            content = "hello",
        )

        val result = engine.process(request)
        assertIs<ChatResult.Success>(result)

        val captured = llmClient.lastRequest ?: error("LlmClient did not capture a request")
        val personaBlock = captured.context.blocks.first { it.blockType == ContextBlockType.PERSONA }
        assertTrue(personaBlock.content.contains("FALLBACK_BASELINE_CORE_CONTENT"), "With no skill selected, the unauthorized baseline must be exactly what reaches generation")
        assertFalse(personaBlock.content.contains("PUBLIC_HOBBY_VALUE"), "No authorization may occur without a real selected skill")
        assertEquals(null, captured.skillKey)
    }
}
