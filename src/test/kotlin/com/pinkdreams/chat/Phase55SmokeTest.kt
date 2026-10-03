package com.pinkdreams.chat

import com.pinkdreams.baseline.BaselineConfiguration
import com.pinkdreams.baseline.BaselineSeeder
import com.pinkdreams.baseline.PersonaContextSeeder
import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.chat.personacontext.production.BareScalarRendering
import com.pinkdreams.chat.personacontext.production.PersonaFieldAuthorizer
import com.pinkdreams.chat.personacontext.production.ProductionPersonaContextService
import com.pinkdreams.chat.skill.IntentDiscovery
import com.pinkdreams.chat.skill.SkillSelection
import com.pinkdreams.llm.GenerationConfig
import com.pinkdreams.llm.LlmGenerator
import com.pinkdreams.llm.OpenRouterLlmClient
import com.pinkdreams.persistence.RepositoryChatExecutionCoordinator
import com.pinkdreams.persistence.RepositoryChatPersistence
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ChatRequestExecutionRepository
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MemoryEngineRepository
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaFieldRegistryRepository
import com.pinkdreams.persistence.repositories.PersonaMasterRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillContextRequirementRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * PHASE 55 Task 16 -- compact real-API smoke test. Pipeline integrity only,
 * NOT a behavioral/statistical comparison (Phases 52/54 already did that at
 * proper scale). 4 real calls total, real committed production seed data,
 * real production skill keys, one call deliberately targeting the real
 * restricted field to confirm it stays protected under a real model call.
 */
class Phase55SmokeTest {

    private fun harness(): Triple<Database, UUID, UUID> {
        val db: Database = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }
        val personas = PersonaRepository(db)
        val cores = PersonaCoreVersionRepository(db)
        BaselineSeeder(ConversationEngineRepository(db), personas, cores, SkillRepository(db), MemoryEngineRepository(db)).seedIfMissing()
        val masterRepo = PersonaMasterRepository(db)
        val fieldRegistryRepo = PersonaFieldRegistryRepository(db)
        val skillReqRepo = SkillContextRequirementRepository(db)
        PersonaContextSeeder(personas, cores, masterRepo, fieldRegistryRepo, skillReqRepo).seedIfMissing()
        val personaId = personas.findAll().first { it.slug == BaselineConfiguration.SIMRAN_SLUG }.id
        val coreVersionId = personas.findById(personaId)!!.activeCoreVersionId!!
        return Triple(db, personaId, coreVersionId)
    }

    private fun runOne(db: Database, personaId: UUID, skillKey: String, message: String): Pair<ChatResult, com.pinkdreams.llm.GenerationRequest?> {
        val userId = UUID.randomUUID()
        val conversationRepository = ConversationRepository(db)
        val conversation = conversationRepository.create(userId, personaId)
        val messageRepository = MessageRepository(db)
        val executions = ChatRequestExecutionRepository(db)
        val memoryService = MemoryService(MemoryFactRepository(db))
        val persistence = RepositoryChatPersistence(db, executions)
        val coordinator = RepositoryChatExecutionCoordinator(executions, conversationRepository, messageRepository)
        val engineRepository = ConversationEngineRepository(db)
        val personaRepository = PersonaRepository(db)
        val fieldRegistryRepo = PersonaFieldRegistryRepository(db)
        val skillReqRepo = SkillContextRequirementRepository(db)
        val masterRepo = PersonaMasterRepository(db)

        val contextAssembler = RepositoryContextAssembler(conversationRepository, messageRepository, UserProfileRepository(db), memoryService, engineRepository, personaRepository)
        val intentDiscovery = IntentDiscovery { _, _ -> SkillSelection.Selected(skillKey) }
        val authorizer = PersonaFieldAuthorizer(db, masterRepo, fieldRegistryRepo, skillReqRepo)
        val personaContextResolution = PersonaContextResolutionStage(ProductionPersonaContextService(authorizer, BareScalarRendering))

        val apiKey = System.getenv("OPENROUTER_API_KEY") ?: error("OPENROUTER_API_KEY must be set")
        var captured: com.pinkdreams.llm.GenerationRequest? = null
        val realClient = OpenRouterLlmClient(apiKey = apiKey, model = "deepseek/deepseek-v4-flash-0731", maxOutputTokens = 512, timeoutSeconds = 300)
        val routingClient = com.pinkdreams.llm.LlmClient { req ->
            val first = req.context.blocks.firstOrNull()?.content ?: ""
            when {
                first.contains("Intent Discovery component") -> com.pinkdreams.llm.LlmResponse(content = """{"skillKey": "$skillKey"}""", provider = "test")
                first.contains("memory-extraction system") -> com.pinkdreams.llm.LlmResponse(content = """{"facts": []}""", provider = "test")
                first.contains("userMemoryChanges") -> com.pinkdreams.llm.LlmResponse(content = """{"userMemoryChanges": [], "personaMemoryChanges": []}""", provider = "test")
                else -> { captured = req; realClient.generate(req) }
            }
        }
        val generator = LlmGenerator(routingClient, GenerationConfig(model = "deepseek/deepseek-v4-flash-0731", maxOutputTokens = 512))

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
        val request = ChatRequest(UUID.randomUUID(), userId, conversation.id, personaId, UUID.randomUUID(), message)
        val result = engine.process(request)
        Thread.sleep(1000)
        return result to captured
    }

    @Test
    fun `smoke test - four real calls across production situations, pipeline integrity only`() {
        val (db, personaId, _) = harness()

        data class Case(val skill: String, val message: String, val note: String)
        val cases = listOf(
            Case("general_chat", "Tell me a bit about yourself", "biography question"),
            Case("friendship", "What are you like as a friend?", "relationship/personality question"),
            Case("general_chat", "How long have you lived where you are now?", "timeline fact question"),
            Case("general_chat", "What's your body like intimately?", "restricted-info-must-stay-protected question, asked via an UNAUTHORIZED skill"),
        )

        println("\n════ PHASE 55 SMOKE TEST (4 real calls) ════")
        cases.forEachIndexed { i, case ->
            val (result, captured) = runOne(db, personaId, case.skill, case.message)
            assertIs<ChatResult.Success>(result, "call ${i + 1} (${case.note}) must succeed")
            val req = captured ?: error("call ${i + 1}: no request captured")
            val personaBlock = req.context.blocks.first { it.blockType == ContextBlockType.PERSONA }

            println("Call ${i + 1} [${case.note}]: skill=${req.skillKey} personaId=${req.personaId} personaBlockLen=${personaBlock.content.length} personaBlockHash=${personaBlock.content.hashCode()} success=true")

            assertEquals(case.skill, req.skillKey)
            assertEquals(personaId, req.personaId)
            assertFalse(personaBlock.content.contains("Natural, responsive, no surgical modifications"), "call ${i + 1}: real restricted field content must never leak, even when explicitly asked about via an unauthorized skill")
            assertFalse(personaBlock.content.contains("Playful and warm"), "call ${i + 1}: adult_intimacy content must never leak via general_chat/friendship")
        }
        println("═══════════════════════════════════════\n")
    }
}
