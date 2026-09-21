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
 * Make Intent Discovery Fast + Reliable phase: locks in the per-workload
 * JSON-mode override ([ChatEngineFactory.Dependencies.intentJsonModeOverride])
 * and the request-level reinforcement text it triggers in
 * [com.pinkdreams.chat.skill.LlmIntentDiscovery] — proven (85-case live
 * validation) to eliminate GPT-4o-mini's malformed-output rate (13.3% -> 0%)
 * without touching the stored, versioned Intent Engine content.
 */
class IntentJsonModeTest {

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

    private fun buildDeps(w: World, client: LlmClient, intentModelOverride: String?, intentJsonModeOverride: Boolean?): ChatEngineFactory.Dependencies {
        val llmConfig = LlmConfig(apiKey = "k", model = "deepseek/deepseek-v4-flash-0731")
        val memoryFacts = MemoryFactRepository(w.db)
        val memoryService = com.pinkdreams.chat.memory.MemoryService(memoryFacts)
        val aiSettings = AiSettingsRepository(w.db)
        val executions = ChatRequestExecutionRepository(w.db)
        return ChatEngineFactory.Dependencies(
            llmClient = client, llmConfig = llmConfig, db = w.db,
            conversationRepository = ConversationRepository(w.db), messageRepository = MessageRepository(w.db),
            userProfileRepository = UserProfileRepository(w.db),
            memoryService = memoryService, memoryFactRepository = memoryFacts,
            engineRepository = w.engines, personaRepository = w.personas, skillRepository = w.skills,
            memoryEngineRepository = w.memoryEngines, intentEngineRepository = w.intentEngines,
            executionRepository = executions, aiRuntimeSettings = AiRuntimeSettings(llmConfig, aiSettings),
            intentModelOverride = intentModelOverride,
            intentJsonModeOverride = intentJsonModeOverride,
        )
    }

    private fun runOneTurn(w: World, client: LlmClient, intentModelOverride: String?, intentJsonModeOverride: Boolean?) {
        val deps = buildDeps(w, client, intentModelOverride, intentJsonModeOverride)
        val engine = ChatEngineFactory.build(deps)
        val userId = UUID.randomUUID()
        val conversation = deps.conversationRepository.create(userId, w.personaId)
        val request = ChatRequest(UUID.randomUUID(), userId, conversation.id, w.personaId, UUID.randomUUID(), "hello")
        val result = engine.process(request)
        assertTrue(result is ChatResult.Success)
    }

    @Test
    fun `with no override intent discovery sends no response_format and no reinforcement text`() {
        val w = World()
        var capturedJsonMode: Boolean? = null
        var capturedIntentPrompt: String? = null
        val client = LlmClient { request ->
            val system = request.context.blocks.firstOrNull { it.role == "system" }?.content ?: ""
            if (system.contains("Intent Discovery component")) {
                capturedJsonMode = request.config.jsonMode
                capturedIntentPrompt = system
            }
            val body = if (system.contains("Intent Discovery component")) """{"skillKey": null}""" else "a reply"
            LlmResponse(content = body, provider = "test")
        }

        runOneTurn(w, client, intentModelOverride = null, intentJsonModeOverride = null)

        assertNull(capturedJsonMode)
        assertTrue(!capturedIntentPrompt!!.contains("no markdown fences"), "Reinforcement text must not be appended when jsonMode is not enabled")
    }

    @Test
    fun `an explicit override enables json mode only for intent discovery and appends the reinforcement`() {
        val w = World()
        val capturedJsonMode = mutableMapOf<String, Boolean?>()
        var capturedIntentPrompt: String? = null
        val client = LlmClient { request ->
            val system = request.context.blocks.firstOrNull { it.role == "system" }?.content ?: ""
            val callType = if (system.contains("Intent Discovery component")) "intent" else "generation"
            capturedJsonMode[callType] = request.config.jsonMode
            if (callType == "intent") capturedIntentPrompt = system
            val body = if (callType == "intent") """{"skillKey": null}""" else "a reply"
            LlmResponse(content = body, provider = "test")
        }

        runOneTurn(w, client, intentModelOverride = "openai/gpt-4o-mini", intentJsonModeOverride = true)

        assertEquals(true, capturedJsonMode["intent"])
        assertNull(capturedJsonMode["generation"], "Primary generation must be unaffected by the Intent-only override")
        assertTrue(capturedIntentPrompt!!.contains("no markdown fences"), "The JSON-mode reinforcement must be appended to Intent's request when enabled")
    }

    @Test
    fun `the reinforcement text is never part of the stored Intent Engine content`() {
        assertTrue(
            !com.pinkdreams.chat.skill.IntentEngineDefaultContent.CONTENT.contains(com.pinkdreams.chat.skill.LlmIntentDiscovery.JSON_MODE_REINFORCEMENT),
            "The reinforcement must be appended at request-construction time only, never stored as versioned Intent Engine content",
        )
    }
}
