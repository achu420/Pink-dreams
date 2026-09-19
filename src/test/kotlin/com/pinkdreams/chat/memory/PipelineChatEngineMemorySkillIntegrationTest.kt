package com.pinkdreams.chat.memory

import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ChatResult
import com.pinkdreams.chat.EntitlementDecision
import com.pinkdreams.chat.ModerationDecision
import com.pinkdreams.chat.PipelineChatEngine
import com.pinkdreams.chat.StageResult
import com.pinkdreams.chat.ValidationDecision
import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.chat.skill.IntentDiscovery
import com.pinkdreams.chat.skill.SkillContextEnricher
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
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Full-pipeline integration for Phase C: real PipelineChatEngine, real
 * RepositoryContextAssembler, real SkillAwareMemoryEnricher + real
 * SkillContextEnricher, and a routing fake LlmClient distinguishing
 * intent-discovery calls from generation calls (mirrors
 * PipelineChatEngineSkillIntegrationTest's fixture from Phase B).
 */
class PipelineChatEngineMemorySkillIntegrationTest {

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
        val userId: UUID,
        val personaId: UUID,
        val conversationId: UUID,
        val engine: PipelineChatEngine,
        val llmClient: RoutingFakeLlmClient,
        val memoryService: MemoryService,
    )

    private fun fixture(
        intentReplies: List<String>,
        intentDiscovery: IntentDiscovery? = null,
        memoryContextEnricher: SkillAwareMemoryEnricher? = null,
        outputValidator: com.pinkdreams.chat.OutputValidator = com.pinkdreams.chat.OutputValidator { _, _ -> ValidationDecision.Accepted },
    ): Fixture {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userId = UUID.randomUUID()
        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("memskill-persona-${UUID.randomUUID()}", "Test", "female", "straight", 30, emptyMap())
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

        val memoryFactRepository = MemoryFactRepository(db)
        val memoryService = MemoryService(memoryFactRepository)
        memoryService.record(userId, persona.id, listOf(MemoryCandidate("user's previous relationship ended badly", "past_event", "medium")))
        memoryService.record(userId, persona.id, listOf(MemoryCandidate("user likes hiking on weekends", "interest", "medium")))

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
            memoryService,
            engineRepository,
            personaRepository,
        )

        val enricher = memoryContextEnricher ?: SkillAwareMemoryEnricher(memoryService, DeterministicMemoryContextSelector(skillRepository))

        val engine = PipelineChatEngine(
            entitlementChecker = { EntitlementDecision.Allowed },
            inputModerator = { ModerationDecision.Allowed },
            contextAssembler = contextAssembler,
            generator = generator,
            outputValidator = outputValidator,
            persistence = persistence,
            delivery = { _, _ -> StageResult.Succeeded(Unit) },
            executionCoordinator = coordinator,
            intentDiscovery = intentDiscovery ?: com.pinkdreams.chat.skill.LlmIntentDiscovery(llmClient, skillRepository),
            skillContextEnricher = SkillContextEnricher(skillRepository),
            memoryContextEnricher = enricher,
        )

        return Fixture(userId, persona.id, conversation.id, engine, llmClient, memoryService)
    }

    private fun sendTurn(fixture: Fixture, content: String): ChatResult {
        val request = ChatRequest(UUID.randomUUID(), fixture.userId, fixture.conversationId, fixture.personaId, UUID.randomUUID(), content)
        return fixture.engine.process(request)
    }

    // --- C. Skill switching influences memory relevance turn by turn ---
    @Test
    fun `memory selection responds to the current turn's selected skill`() {
        val fixture = fixture(intentReplies = listOf("""{"skillKey":"friendship"}""", """{"skillKey":"emotional_support"}"""))

        assertIs<ChatResult.Success>(sendTurn(fixture, "how's it going"))
        assertIs<ChatResult.Success>(sendTurn(fixture, "can we talk about my past relationship"))

        val requests = fixture.llmClient.capturedGenerationRequests
        assertEquals(2, requests.size)
        // Both turns must reflect their OWN turn's skill selection, not a sticky first choice.
        assertTrue(requests[0].context.blocks.any { it.content.contains("FRIENDSHIP_SKILL_CONTENT") })
        assertTrue(requests[1].context.blocks.any { it.content.contains("EMOTIONAL_SUPPORT_SKILL_CONTENT") })
    }

    // --- D. No skill selected still works ---
    @Test
    fun `skill None still produces a normal response with memory context present`() {
        val fixture = fixture(intentReplies = listOf("""{"skillKey":"none"}"""))
        val result = sendTurn(fixture, "what's up")

        assertIs<ChatResult.Success>(result)
        val blocks = fixture.llmClient.capturedGenerationRequests.single().context.blocks
        assertTrue(blocks.any { it.content.startsWith("RETRIEVED MEMORY:") })
        assertTrue(blocks.none { it.content.startsWith("SELECTED SKILL") })
    }

    // --- E. Skill selection failure: memory retrieval still works ---
    @Test
    fun `intent discovery failure still allows normal memory-aware generation`() {
        val throwingDiscovery = IntentDiscovery { _, _ -> throw RuntimeException("simulated failure") }
        val fixture = fixture(intentReplies = emptyList(), intentDiscovery = throwingDiscovery)

        val result = sendTurn(fixture, "hello")

        assertIs<ChatResult.Success>(result)
        val blocks = fixture.llmClient.capturedGenerationRequests.single().context.blocks
        val memoryBlock = blocks.first { it.content.startsWith("RETRIEVED MEMORY:") }
        assertTrue(memoryBlock.content.contains("hiking") || memoryBlock.content.contains("relationship"), "Memory must still be populated even when skill selection fails")
    }

    // --- F. Memory selection failure: chat still succeeds, falls back to assembler's default block ---
    @Test
    fun `memory context selection failure falls back to the assembler default and chat still succeeds`() {
        val throwingSelector = MemoryContextSelector { _, _, _ -> throw RuntimeException("simulated selector failure") }
        val throwingDb = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(throwingDb)
        val failingEnricher = SkillAwareMemoryEnricher(MemoryService(MemoryFactRepository(throwingDb)), throwingSelector)
        val fixture = fixture(intentReplies = listOf("""{"skillKey":"none"}"""), memoryContextEnricher = failingEnricher)

        val result = sendTurn(fixture, "hello")

        assertIs<ChatResult.Success>(result, "Chat must succeed even when memory context selection throws")
        val blocks = fixture.llmClient.capturedGenerationRequests.single().context.blocks
        assertTrue(blocks.any { it.content.startsWith("RETRIEVED MEMORY:") }, "The assembler's own default memory block must remain present as the fallback")
    }

    // --- L. Context ordering with all optional blocks present ---
    @Test
    fun `final context order is engine persona profile memory skill history current message`() {
        val fixture = fixture(intentReplies = listOf("""{"skillKey":"flirting"}"""))
        assertIs<ChatResult.Success>(sendTurn(fixture, "hey there"))

        val blocks = fixture.llmClient.capturedGenerationRequests.single().context.blocks
        val enginePersonaIdx = blocks.indexOfFirst { it.content.contains("ENGINE_CONTENT") }
        val profileIdx = blocks.indexOfFirst { it.content.startsWith("USER PROFILE:") }
        val memoryIdx = blocks.indexOfFirst { it.content.startsWith("RETRIEVED MEMORY:") }
        val skillIdx = blocks.indexOfFirst { it.content.startsWith("SELECTED SKILL") }
        val currentIdx = blocks.size - 1

        assertTrue(enginePersonaIdx < profileIdx)
        assertTrue(profileIdx < memoryIdx)
        assertTrue(memoryIdx < skillIdx, "Memory must precede Skill in the final order")
        assertTrue(skillIdx < currentIdx)
        assertEquals(0, enginePersonaIdx, "Engine+Persona must remain the first block — Skill must never precede or displace it")
    }

    // --- M. LLM payload: only selected memory + selected skill, never unselected candidates/skills ---
    @Test
    fun `llm payload contains only selected memory and selected skill not the full candidate or skill set`() {
        val fixture = fixture(intentReplies = listOf("""{"skillKey":"flirting"}"""))
        assertIs<ChatResult.Success>(sendTurn(fixture, "hey there"))

        val blocks = fixture.llmClient.capturedGenerationRequests.single().context.blocks
        assertTrue(blocks.any { it.content.contains("FLIRTING_SKILL_CONTENT") })
        assertTrue(blocks.none { it.content.contains("FRIENDSHIP_SKILL_CONTENT") })
        assertTrue(blocks.none { it.content.contains("EMOTIONAL_SUPPORT_SKILL_CONTENT") })
        assertEquals(1, blocks.count { it.content.startsWith("SELECTED SKILL") })
        assertEquals(1, blocks.count { it.content.startsWith("RETRIEVED MEMORY:") })
    }

    // --- N. Regeneration keeps the same memory + skill enriched context ---
    @Test
    fun `regeneration reuses the same memory and skill enriched context as the first attempt`() {
        var validationCalls = 0
        val validator = com.pinkdreams.chat.OutputValidator { _, _ ->
            validationCalls++
            if (validationCalls == 1) ValidationDecision.Rejected("simulated rejection") else ValidationDecision.Accepted
        }
        val fixture = fixture(intentReplies = listOf("""{"skillKey":"flirting"}"""), outputValidator = validator)

        val result = sendTurn(fixture, "hey there")

        assertIs<ChatResult.Success>(result)
        assertEquals(2, fixture.llmClient.capturedGenerationRequests.size, "One initial + one regeneration call")
        val first = fixture.llmClient.capturedGenerationRequests[0].context.blocks
        val second = fixture.llmClient.capturedGenerationRequests[1].context.blocks
        assertEquals(first.map { it.role to it.content }, second.map { it.role to it.content }, "Regeneration must use the identical memory+skill enriched context, not reassemble or reselect")
    }
}
