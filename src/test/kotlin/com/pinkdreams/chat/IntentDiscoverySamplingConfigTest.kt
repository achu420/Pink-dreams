package com.pinkdreams.chat

import com.pinkdreams.baseline.BaselineConfiguration
import com.pinkdreams.baseline.BaselineSeeder
import com.pinkdreams.config.AiRuntimeSettings
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.llm.LlmClient
import com.pinkdreams.llm.LlmResponse
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.AiSettingsRepository
import com.pinkdreams.persistence.repositories.ChatRequestExecutionRepository
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.IntentEngineRepository
import com.pinkdreams.persistence.repositories.MemoryEngineRepository
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runtime Quality + Latency Verification phase — final state after a full
 * investigation into Intent Discovery's sampling configuration.
 *
 * A controlled, context-free A/B (25 cases) found reasoning-off gave no
 * accuracy loss and a real latency win. A FULL live conversation — with the
 * real accumulating recent-history Intent Discovery actually receives in
 * production — told a different story: reasoning-off dropped the
 * selected-skill rate from 92% (23/25) to 24% (6/25), and adding
 * temperature=0 as a mitigation made it worse, not better. Reasoning is
 * genuinely load-bearing for this classifier once realistic context is
 * involved, so BOTH knobs were reverted to the provider default (null) for
 * this workload. See ChatEngineFactory and the phase report for the full
 * measurement trail. This test locks in that reverted, final state — and
 * that primary generation was never touched by any of this.
 */
class IntentDiscoverySamplingConfigTest {

    @Test
    fun `intent discovery and generation both run at provider default sampling`() {
        val db = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }
        val engines = ConversationEngineRepository(db)
        val personas = PersonaRepository(db)
        val cores = PersonaCoreVersionRepository(db)
        val skills = SkillRepository(db)
        val memoryEngines = MemoryEngineRepository(db)
        val intentEngines = IntentEngineRepository(db)
        BaselineSeeder(engines, personas, cores, skills, memoryEngines, intentEngines).seedIfMissing()
        skills.findAll().forEach { skills.activate(skills.publish(it.id).id) }
        val personaId = personas.findAll().first { it.slug == BaselineConfiguration.SIMRAN_SLUG }.id

        data class Captured(val reasoningEnabled: Boolean?, val temperature: Double?)
        val capturedByCallType = mutableMapOf<String, Captured>()
        val client = LlmClient { request ->
            val system = request.context.blocks.firstOrNull { it.role == "system" }?.content ?: ""
            val callType = when {
                system.contains("Intent Discovery component") -> "intent"
                system.contains("CONVERSATION ENGINE") -> "generation"
                else -> "other"
            }
            capturedByCallType[callType] = Captured(request.config.reasoningEnabled, request.config.temperature)
            val body = if (callType == "intent") """{"skillKey": null}""" else "a reply"
            LlmResponse(content = body, provider = "test")
        }
        val llmConfig = LlmConfig(apiKey = "k", model = "deepseek/deepseek-v4-flash-0731")
        val memoryFacts = MemoryFactRepository(db)
        val memoryService = com.pinkdreams.chat.memory.MemoryService(memoryFacts)
        val aiSettings = AiSettingsRepository(db)
        val executions = ChatRequestExecutionRepository(db)
        val aiRuntimeSettings = AiRuntimeSettings(llmConfig, aiSettings)
        val conversationRepo = ConversationRepository(db)
        val messageRepo = MessageRepository(db)

        val deps = ChatEngineFactory.Dependencies(
            llmClient = client, llmConfig = llmConfig, db = db,
            conversationRepository = conversationRepo, messageRepository = messageRepo,
            userProfileRepository = UserProfileRepository(db),
            memoryService = memoryService, memoryFactRepository = memoryFacts,
            engineRepository = engines, personaRepository = personas, skillRepository = skills,
            memoryEngineRepository = memoryEngines, intentEngineRepository = intentEngines,
            executionRepository = executions, aiRuntimeSettings = aiRuntimeSettings,
        )
        val engine = ChatEngineFactory.build(deps)
        val userId = UUID.randomUUID()
        val conversation = conversationRepo.create(userId, personaId)
        val request = ChatRequest(UUID.randomUUID(), userId, conversation.id, personaId, UUID.randomUUID(), "hello")

        val result = engine.process(request)

        assertTrue(result is ChatResult.Success)
        val intentConfig = capturedByCallType["intent"]!!
        assertNull(intentConfig.reasoningEnabled, "Intent Discovery runs at the provider's reasoning default — disabling it measurably broke routing once real context was involved")
        assertNull(intentConfig.temperature, "temperature=0 was tried as a mitigation and made routing worse, not better — reverted")

        val generationConfig = capturedByCallType["generation"]!!
        assertEquals(
            false,
            generationConfig.reasoningEnabled,
            "Primary generation was untouched by THIS investigation, but the later Primary Generation Latency + " +
                "Response Quality Hardening phase measured reasoning-off as a real latency win for generation " +
                "specifically (unlike Intent, generation is not a discrete classification judgment) — see " +
                "GenerationReasoningConfigTest for that evidence trail",
        )
        assertNull(generationConfig.temperature, "Primary generation must keep its established varied response quality")
    }
}
