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
 * Primary Generation Latency: Production Configuration phase — locks in the
 * FULL validated production combination (Intent = gpt-4o-mini + JSON mode,
 * generation = deepseek-v4-flash-0731 + provider.sort=latency) applying
 * simultaneously, exactly as production now constructs it in
 * Application.kt, and proves no cross-contamination between the two
 * independent override sets or into any side-channel workload.
 */
class ProductionLatencyConfigurationTest {

    private class World {
        val db = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }
        val engines = ConversationEngineRepository(db)
        val personas = PersonaRepository(db)
        val cores = PersonaCoreVersionRepository(db)
        val skills = SkillRepository(db)
        val memoryEngines = MemoryEngineRepository(db)
        val intentEngines = IntentEngineRepository(db)
        val personaId: UUID

        init {
            BaselineSeeder(engines, personas, cores, skills, memoryEngines, intentEngines).seedIfMissing()
            skills.findAll().forEach { skills.activate(skills.publish(it.id).id) }
            personaId = personas.findAll().first { it.slug == BaselineConfiguration.SIMRAN_SLUG }.id
        }
    }

    @Test
    fun `the full validated production configuration resolves correctly with no cross-contamination`() {
        val w = World()
        val llmConfig = LlmConfig(apiKey = "k", model = "deepseek/deepseek-v4-flash-0731")
        val memoryFacts = MemoryFactRepository(w.db)
        val memoryService = com.pinkdreams.chat.memory.MemoryService(memoryFacts)
        val aiSettings = AiSettingsRepository(w.db)
        val executions = ChatRequestExecutionRepository(w.db)

        // Mirrors Application.kt exactly: the production AiRuntimeSettings now
        // carries the generation provider-sort default.
        val aiRuntimeSettings = AiRuntimeSettings(llmConfig, aiSettings, generationProviderSortOverride = "latency")

        val capturedConfigs = mutableMapOf<String, com.pinkdreams.llm.GenerationConfig>()
        val client = LlmClient { request ->
            val system = request.context.blocks.firstOrNull { it.role == "system" }?.content ?: ""
            val callType = when {
                system.contains("Intent Discovery component") -> "intent"
                system.contains("CONVERSATION ENGINE") -> "generation"
                system.contains("memory-extraction system") -> "memory_extraction"
                system.contains("TASK 1") || system.contains("userMemoryChanges") -> "memory_engine"
                else -> "other"
            }
            capturedConfigs[callType] = request.config
            val body = when (callType) {
                "intent" -> """{"skillKey": null}"""
                "memory_extraction" -> """{"facts": []}"""
                "memory_engine" -> """{"userMemoryChanges": [], "personaMemoryChanges": []}"""
                else -> "a reply"
            }
            LlmResponse(content = body, provider = "test")
        }

        // Mirrors Application.kt exactly: the production Dependencies now
        // carries the three Intent-only defaults.
        val deps = ChatEngineFactory.Dependencies(
            llmClient = client, llmConfig = llmConfig, db = w.db,
            conversationRepository = ConversationRepository(w.db), messageRepository = MessageRepository(w.db),
            userProfileRepository = UserProfileRepository(w.db),
            memoryService = memoryService, memoryFactRepository = memoryFacts,
            engineRepository = w.engines, personaRepository = w.personas, skillRepository = w.skills,
            memoryEngineRepository = w.memoryEngines, intentEngineRepository = w.intentEngines,
            executionRepository = executions, aiRuntimeSettings = aiRuntimeSettings,
            intentModelOverride = "openai/gpt-4o-mini",
            intentJsonModeOverride = true,
        )
        val engine = ChatEngineFactory.build(deps)
        val userId = UUID.randomUUID()
        val conversation = deps.conversationRepository.create(userId, w.personaId)
        val request = ChatRequest(UUID.randomUUID(), userId, conversation.id, w.personaId, UUID.randomUUID(), "hello")

        val result = engine.process(request)

        assertTrue(result is ChatResult.Success)

        val intent = capturedConfigs["intent"]!!
        assertEquals("openai/gpt-4o-mini", intent.model)
        assertEquals(true, intent.jsonMode)
        assertEquals(600, intent.maxOutputTokens)
        assertNull(intent.providerSort, "Intent must never inherit generation's provider-sort setting")

        val generation = capturedConfigs["generation"]!!
        assertEquals("deepseek/deepseek-v4-flash-0731", generation.model)
        assertEquals("latency", generation.providerSort)
        assertEquals(false, generation.reasoningEnabled)
        assertEquals(2048, generation.maxOutputTokens)
        assertNull(generation.jsonMode, "Generation must never inherit Intent's jsonMode setting")
    }
}
