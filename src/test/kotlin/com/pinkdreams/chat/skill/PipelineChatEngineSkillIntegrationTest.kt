package com.pinkdreams.chat.skill

import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ChatResult
import com.pinkdreams.chat.EntitlementDecision
import com.pinkdreams.chat.ModerationDecision
import com.pinkdreams.chat.PipelineChatEngine
import com.pinkdreams.chat.StageResult
import com.pinkdreams.chat.ValidationDecision
import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.chat.memory.MemoryService
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
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Full-pipeline integration for Phase B: real PipelineChatEngine, real
 * RepositoryContextAssembler, real SkillContextEnricher, and a routing fake
 * LlmClient that distinguishes the intent-discovery call from the primary
 * generation call by prompt content (mirrors
 * ContinuitySummarizationContextIntegrationTest's RoutingFakeLlmClient).
 */
class PipelineChatEngineSkillIntegrationTest {

    private class RoutingFakeLlmClient(private val intentReplies: MutableList<String>) : LlmClient {
        val capturedGenerationRequests = mutableListOf<GenerationRequest>()

        override fun generate(request: GenerationRequest): LlmResponse {
            val firstBlockContent = request.context.blocks.firstOrNull()?.content ?: ""
            return if (firstBlockContent.contains("Intent Discovery component")) {
                LlmResponse(content = intentReplies.removeAt(0), provider = "test")
            } else {
                capturedGenerationRequests += request
                LlmResponse(content = "assistant reply", provider = "test")
            }
        }
    }

    private class Fixture(
        val db: org.jetbrains.exposed.sql.Database,
        val userId: UUID,
        val personaId: UUID,
        val conversationId: UUID,
        val engine: PipelineChatEngine,
        val llmClient: RoutingFakeLlmClient,
        val skillRepository: SkillRepository,
    )

    private fun fixture(intentReplies: List<String>, skillContextEnricher: SkillContextEnricher? = null, intentDiscovery: IntentDiscovery? = null): Fixture {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userId = UUID.randomUUID()
        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("skill-int-persona-${UUID.randomUUID()}", "Test", "female", "straight", 30, emptyMap())
        val coreRepository = PersonaCoreVersionRepository(db)
        val core = coreRepository.create(persona.id, 1, "PERSONA_CORE_CONTENT", "draft")
        personaRepository.activateCoreVersion(persona.id, coreRepository.publishCoreVersion(core.id).id)

        val engineRepository = ConversationEngineRepository(db)
        val engineRow = engineRepository.create(1, "ENGINE_CONTENT", "draft")
        engineRepository.activateEngine(engineRepository.publishEngine(engineRow.id).id)

        val conversationRepository = ConversationRepository(db)
        val conversation = conversationRepository.create(userId, persona.id)

        val skillRepository = SkillRepository(db)
        listOf("flirting", "friendship", "emotional_support").forEach { key ->
            skillRepository.activate(skillRepository.publish(skillRepository.createNextVersion(key, "${key.uppercase()}_SKILL_CONTENT").id).id)
        }

        val messageRepository = MessageRepository(db)
        val executions = ChatRequestExecutionRepository(db)
        val persistence = RepositoryChatPersistence(db, executions)
        val coordinator = RepositoryChatExecutionCoordinator(executions, conversationRepository, messageRepository)

        val llmClient = RoutingFakeLlmClient(intentReplies.toMutableList())
        val generator = LlmGenerator(llmClient, GenerationConfig(model = "test-model"))
        val contextAssembler = RepositoryContextAssembler(
            conversationRepository,
            messageRepository,
            UserProfileRepository(db),
            MemoryService(MemoryFactRepository(db)),
            engineRepository,
            personaRepository,
        )

        val engine = PipelineChatEngine(
            entitlementChecker = { EntitlementDecision.Allowed },
            inputModerator = { ModerationDecision.Allowed },
            contextAssembler = contextAssembler,
            generator = generator,
            outputValidator = { _, _ -> ValidationDecision.Accepted },
            persistence = persistence,
            delivery = { _, _ -> StageResult.Succeeded(Unit) },
            executionCoordinator = coordinator,
            intentDiscovery = intentDiscovery ?: LlmIntentDiscovery(llmClient, skillRepository),
            skillContextEnricher = skillContextEnricher ?: SkillContextEnricher(skillRepository),
        )

        return Fixture(db, userId, persona.id, conversation.id, engine, llmClient, skillRepository)
    }

    private fun sendTurn(fixture: Fixture, content: String): ChatResult {
        val request = ChatRequest(UUID.randomUUID(), fixture.userId, fixture.conversationId, fixture.personaId, UUID.randomUUID(), content)
        return fixture.engine.process(request)
    }

    // --- Skill switching across turns, per-turn selection (not persisted conversation state) ---
    @Test
    fun `skill selection can change between turns within the same conversation`() {
        val fixture = fixture(intentReplies = listOf("""{"skillKey":"friendship"}""", """{"skillKey":"flirting"}""", """{"skillKey":"emotional_support"}"""))

        assertIs<ChatResult.Success>(sendTurn(fixture, "turn 1"))
        assertIs<ChatResult.Success>(sendTurn(fixture, "turn 2"))
        assertIs<ChatResult.Success>(sendTurn(fixture, "turn 3"))

        val requests = fixture.llmClient.capturedGenerationRequests
        assertEquals(3, requests.size)
        assertTrue(requests[0].context.blocks.any { it.content.contains("FRIENDSHIP_SKILL_CONTENT") })
        assertTrue(requests[1].context.blocks.any { it.content.contains("FLIRTING_SKILL_CONTENT") })
        assertTrue(requests[2].context.blocks.any { it.content.contains("EMOTIONAL_SUPPORT_SKILL_CONTENT") })
        // Turn 2's request must not carry over turn 1's skill, and vice versa.
        assertTrue(requests[0].context.blocks.none { it.content.contains("FLIRTING_SKILL_CONTENT") })
        assertTrue(requests[1].context.blocks.none { it.content.contains("FRIENDSHIP_SKILL_CONTENT") })
    }

    // --- Only one skill is ever injected per request ---
    @Test
    fun `exactly one skill block is present per request never multiple`() {
        val fixture = fixture(intentReplies = listOf("""{"skillKey":"flirting"}"""))
        assertIs<ChatResult.Success>(sendTurn(fixture, "you look cute today"))

        val blocks = fixture.llmClient.capturedGenerationRequests.single().context.blocks
        val skillBlocks = blocks.filter { it.content.startsWith("SELECTED SKILL") }
        assertEquals(1, skillBlocks.size)
    }

    // --- No skill catalogue leakage: only the selected skill's content, not all active skills ---
    @Test
    fun `generation request contains only the selected skill content not the full skill catalogue`() {
        val fixture = fixture(intentReplies = listOf("""{"skillKey":"flirting"}"""))
        assertIs<ChatResult.Success>(sendTurn(fixture, "you look cute today"))

        val blocks = fixture.llmClient.capturedGenerationRequests.single().context.blocks
        assertTrue(blocks.any { it.content.contains("FLIRTING_SKILL_CONTENT") })
        assertTrue(blocks.none { it.content.contains("FRIENDSHIP_SKILL_CONTENT") }, "Unselected skill content must never reach generation")
        assertTrue(blocks.none { it.content.contains("EMOTIONAL_SUPPORT_SKILL_CONTENT") }, "Unselected skill content must never reach generation")
    }

    // --- None is a normal result: ordinary generation still proceeds ---
    @Test
    fun `ambiguous input selecting none still produces a normal successful response`() {
        val fixture = fixture(intentReplies = listOf("""{"skillKey":"none"}"""))
        val result = sendTurn(fixture, "what's the capital of France?")

        assertIs<ChatResult.Success>(result)
        val blocks = fixture.llmClient.capturedGenerationRequests.single().context.blocks
        assertTrue(blocks.none { it.content.startsWith("SELECTED SKILL") })
    }

    // --- Failure isolation: any intent-discovery failure must never break chat ---
    @Test
    fun `intent discovery throwing an exception never breaks chat delivery`() {
        val throwingDiscovery = IntentDiscovery { _, _ -> throw RuntimeException("simulated failure") }
        val fixture = fixture(intentReplies = emptyList(), intentDiscovery = throwingDiscovery)

        val result = sendTurn(fixture, "you look cute today")

        assertIs<ChatResult.Success>(result, "Chat must succeed even when skill selection throws")
        val blocks = fixture.llmClient.capturedGenerationRequests.single().context.blocks
        assertTrue(blocks.none { it.content.startsWith("SELECTED SKILL") })
    }

    @Test
    fun `no skill infrastructure configured behaves exactly as before Phase B`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val userId = UUID.randomUUID()
        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("no-skill-persona-${UUID.randomUUID()}", "Test", "female", "straight", 30, emptyMap())
        val coreRepository = PersonaCoreVersionRepository(db)
        val core = coreRepository.create(persona.id, 1, "core", "draft")
        personaRepository.activateCoreVersion(persona.id, coreRepository.publishCoreVersion(core.id).id)
        val engineRepository = ConversationEngineRepository(db)
        val engineRow = engineRepository.create(1, "engine", "draft")
        engineRepository.activateEngine(engineRepository.publishEngine(engineRow.id).id)
        val conversationRepository = ConversationRepository(db)
        val conversation = conversationRepository.create(userId, persona.id)
        val messageRepository = MessageRepository(db)
        val executions = ChatRequestExecutionRepository(db)

        val llmClient = object : LlmClient {
            var lastRequest: GenerationRequest? = null
            override fun generate(request: GenerationRequest): LlmResponse {
                lastRequest = request
                return LlmResponse(content = "reply", provider = "test")
            }
        }
        val engine = PipelineChatEngine(
            entitlementChecker = { EntitlementDecision.Allowed },
            inputModerator = { ModerationDecision.Allowed },
            contextAssembler = RepositoryContextAssembler(
                conversationRepository, messageRepository, UserProfileRepository(db),
                MemoryService(MemoryFactRepository(db)), engineRepository, personaRepository,
            ),
            generator = LlmGenerator(llmClient, GenerationConfig(model = "test-model")),
            outputValidator = { _, _ -> ValidationDecision.Accepted },
            persistence = RepositoryChatPersistence(db, executions),
            delivery = { _, _ -> StageResult.Succeeded(Unit) },
            executionCoordinator = RepositoryChatExecutionCoordinator(executions, conversationRepository, messageRepository),
            // intentDiscovery / skillContextEnricher deliberately omitted — defaults apply.
        )

        val request = ChatRequest(UUID.randomUUID(), userId, conversation.id, persona.id, UUID.randomUUID(), "hello")
        val result = engine.process(request)

        assertIs<ChatResult.Success>(result)
        assertTrue(llmClient.lastRequest!!.context.blocks.none { it.content.startsWith("SELECTED SKILL") })
    }
}
