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
import kotlin.test.assertTrue

/**
 * Intent Discovery Model Latency Investigation phase: locks in the
 * per-workload model override wired for this phase's benchmarking —
 * [ChatEngineFactory.Dependencies.intentModelOverride], surfaced through Test
 * Chat via [ConversationRepository.ConfigurationSnapshot.intentModel].
 *
 * Prior to this phase, Intent Discovery had NO way to run a different model
 * than everything else — its GenerationConfig was built once from
 * `llmConfig.model` directly, never through [AiRuntimeSettings] (which only
 * ever governed primary generation and side-channel calls). This is
 * benchmarking infrastructure only: the field defaults to null everywhere in
 * production, so the one production engine is byte-identical to before this
 * phase.
 */
class IntentModelOverrideTest {

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

    private fun buildDeps(w: World, client: LlmClient, intentModelOverride: String?): ChatEngineFactory.Dependencies {
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
        )
    }

    private fun runOneTurn(w: World, client: LlmClient, intentModelOverride: String?) {
        val deps = buildDeps(w, client, intentModelOverride)
        val engine = ChatEngineFactory.build(deps)
        val userId = UUID.randomUUID()
        val conversation = deps.conversationRepository.create(userId, w.personaId)
        val request = ChatRequest(UUID.randomUUID(), userId, conversation.id, w.personaId, UUID.randomUUID(), "hello")
        val result = engine.process(request)
        assertTrue(result is ChatResult.Success)
    }

    @Test
    fun `with no override intent discovery uses the same model as everything else`() {
        val w = World()
        val capturedModels = mutableMapOf<String, String?>()
        val client = LlmClient { request ->
            val system = request.context.blocks.firstOrNull { it.role == "system" }?.content ?: ""
            val callType = if (system.contains("Intent Discovery component")) "intent" else "generation"
            capturedModels[callType] = request.config.model
            val body = if (callType == "intent") """{"skillKey": null}""" else "a reply"
            LlmResponse(content = body, provider = "test")
        }

        runOneTurn(w, client, intentModelOverride = null)

        assertEquals(capturedModels["generation"], capturedModels["intent"], "With no override, Intent Discovery must use the exact same model as primary generation")
        assertEquals("deepseek/deepseek-v4-flash-0731", capturedModels["intent"])
    }

    @Test
    fun `an explicit override changes only intent discovery's model`() {
        val w = World()
        val capturedModels = mutableMapOf<String, String?>()
        val client = LlmClient { request ->
            val system = request.context.blocks.firstOrNull { it.role == "system" }?.content ?: ""
            val callType = if (system.contains("Intent Discovery component")) "intent" else "generation"
            capturedModels[callType] = request.config.model
            val body = if (callType == "intent") """{"skillKey": null}""" else "a reply"
            LlmResponse(content = body, provider = "test")
        }

        runOneTurn(w, client, intentModelOverride = "openai/gpt-4o-mini")

        assertEquals("openai/gpt-4o-mini", capturedModels["intent"], "The override must apply to Intent Discovery")
        assertEquals("deepseek/deepseek-v4-flash-0731", capturedModels["generation"], "Primary generation must be unaffected by the Intent-only override")
    }
}
