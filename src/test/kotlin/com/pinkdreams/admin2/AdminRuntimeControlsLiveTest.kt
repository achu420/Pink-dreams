package com.pinkdreams.admin2

import com.pinkdreams.baseline.BaselineConfiguration
import com.pinkdreams.baseline.BaselineSeeder
import com.pinkdreams.chat.ChatEngineFactory
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ChatResult
import com.pinkdreams.config.AiRuntimeSettings
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.llm.GenerationRequest
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
 * Task 9 — Admin AI Runtime Controls: proves the central "no restart
 * required" requirement (Part 16) end-to-end. Builds ONE production
 * [com.pinkdreams.chat.PipelineChatEngine] (matching how Application.kt
 * builds the engine exactly once at startup), sends a turn, changes an
 * admin AI setting via [AiSettingsRepository] directly (exactly what
 * `PUT /v1/admin/ai-settings` does), sends a second turn on the SAME
 * engine instance with no rebuild, and asserts the new configuration was
 * actually used — the same guarantee LlmGenerator already provided for
 * model/temperature/maxOutputTokens, now extended to Intent Discovery's
 * model/jsonMode/maxOutputTokens and primary generation's provider-sort.
 */
class AdminRuntimeControlsLiveTest {

    private class World {
        val db = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }
        val engines = ConversationEngineRepository(db)
        val personas = PersonaRepository(db)
        val cores = PersonaCoreVersionRepository(db)
        val skills = SkillRepository(db)
        val memoryEngines = MemoryEngineRepository(db)
        val intentEngines = IntentEngineRepository(db)
        val aiSettings = AiSettingsRepository(db)
        val personaId: UUID
        val llmConfig = LlmConfig(apiKey = "k", model = "deepseek/deepseek-v4-flash-0731")

        init {
            BaselineSeeder(engines, personas, cores, skills, memoryEngines, intentEngines).seedIfMissing()
            skills.findAll().forEach { skills.activate(skills.publish(it.id).id) }
            personaId = personas.findAll().first { it.slug == BaselineConfiguration.SIMRAN_SLUG }.id
        }
    }

    /** Captures every GenerationRequest with which workload it belonged to, by prompt content. */
    private class RecordingLlmClient(private val reply: (workload: String) -> String) : LlmClient {
        val requests = mutableListOf<GenerationRequest>()
        override fun generate(request: GenerationRequest): LlmResponse {
            synchronized(requests) { requests += request }
            val system = request.context.blocks.firstOrNull { it.role == "system" }?.content ?: ""
            val workload = when {
                system.contains("Intent Discovery component") -> "intent"
                system.contains("memory-extraction system") || system.contains("TASK 1") || system.contains("userMemoryChanges") -> "memory"
                else -> "generation"
            }
            return LlmResponse(content = reply(workload), provider = "test")
        }
        fun intentRequests() = synchronized(requests) { requests.toList() }.filter {
            (it.context.blocks.firstOrNull { b -> b.role == "system" }?.content ?: "").contains("Intent Discovery component")
        }
        fun generationRequests() = synchronized(requests) { requests.toList() }.filter {
            val s = it.context.blocks.firstOrNull { b -> b.role == "system" }?.content ?: ""
            !s.contains("Intent Discovery component") && !s.contains("memory-extraction system") && !s.contains("TASK 1") && !s.contains("userMemoryChanges")
        }
    }

    private fun buildDeps(w: World, client: LlmClient, aiRuntimeSettings: AiRuntimeSettings): ChatEngineFactory.Dependencies {
        val memoryFacts = MemoryFactRepository(w.db)
        return ChatEngineFactory.Dependencies(
            llmClient = client, llmConfig = w.llmConfig, db = w.db,
            conversationRepository = ConversationRepository(w.db), messageRepository = MessageRepository(w.db),
            userProfileRepository = UserProfileRepository(w.db),
            memoryService = com.pinkdreams.chat.memory.MemoryService(memoryFacts), memoryFactRepository = memoryFacts,
            engineRepository = w.engines, personaRepository = w.personas, skillRepository = w.skills,
            memoryEngineRepository = w.memoryEngines, intentEngineRepository = w.intentEngines,
            executionRepository = ChatRequestExecutionRepository(w.db), aiRuntimeSettings = aiRuntimeSettings,
            // Task 9: production no longer sets these literal overrides — see Application.kt.
            intentModelOverride = null, intentJsonModeOverride = null,
        )
    }

    private fun sendTurn(deps: ChatEngineFactory.Dependencies, engine: com.pinkdreams.chat.ChatEngine, personaId: UUID) {
        val userId = UUID.randomUUID()
        val conversation = deps.conversationRepository.create(userId, personaId)
        val request = ChatRequest(UUID.randomUUID(), userId, conversation.id, personaId, UUID.randomUUID(), "hello")
        val result = engine.process(request)
        assertTrue(result is ChatResult.Success, "Turn must succeed")
    }

    @Test
    fun `changing the persisted intent model takes effect on the next turn with no engine rebuild`() {
        val w = World()
        val client = RecordingLlmClient { workload -> if (workload == "intent") """{"skillKey": null}""" else "a reply" }
        val aiRuntimeSettings = AiRuntimeSettings(w.llmConfig, w.aiSettings, intentModelDefault = "openai/gpt-4o-mini")
        val deps = buildDeps(w, client, aiRuntimeSettings)
        val engine = ChatEngineFactory.build(deps) // built ONCE — exactly like Application.kt's production engine

        sendTurn(deps, engine, w.personaId)
        assertEquals("openai/gpt-4o-mini", client.intentRequests().last().config.model, "Before any admin change, Intent must use its code default")

        // This is exactly what PUT /v1/admin/ai-settings does server-side.
        w.aiSettings.save(model = null, temperature = null, maxOutputTokens = null, updatedBy = "admin", intentModel = "claude-x")

        sendTurn(deps, engine, w.personaId) // SAME engine instance, no rebuild, no restart
        assertEquals("claude-x", client.intentRequests().last().config.model, "The admin's persisted change must apply on the very next turn")
        assertEquals(w.llmConfig.model, client.generationRequests().last().config.model, "Primary generation must be completely unaffected by an Intent-only setting")
    }

    @Test
    fun `changing the persisted generation provider sort takes effect on the next turn and never reaches intent discovery`() {
        val w = World()
        val client = RecordingLlmClient { workload -> if (workload == "intent") """{"skillKey": null}""" else "a reply" }
        // No code-level default here (unlike production's real "latency"
        // default) so the DATABASE-override-then-reset-to-null round trip is
        // unambiguous: with no code default, null truly means "no preference".
        val aiRuntimeSettings = AiRuntimeSettings(w.llmConfig, w.aiSettings)
        val deps = buildDeps(w, client, aiRuntimeSettings)
        val engine = ChatEngineFactory.build(deps)

        sendTurn(deps, engine, w.personaId)
        assertNull(client.generationRequests().last().config.providerSort, "With nothing configured, no provider-sort preference is sent")

        w.aiSettings.save(model = null, temperature = null, maxOutputTokens = null, updatedBy = "admin", generationProviderSort = "latency")
        sendTurn(deps, engine, w.personaId)
        assertEquals("latency", client.generationRequests().last().config.providerSort, "The admin's persisted change must apply on the very next turn")
        assertNull(client.intentRequests().last().config.providerSort, "providerSort must never reach Intent Discovery")

        w.aiSettings.save(model = null, temperature = null, maxOutputTokens = null, updatedBy = "admin", generationProviderSort = null)
        sendTurn(deps, engine, w.personaId)
        assertNull(client.generationRequests().last().config.providerSort, "Resetting to null must clear the override on the very next turn")
    }

    @Test
    fun `changing the persisted intent json mode does not affect primary generation`() {
        val w = World()
        val client = RecordingLlmClient { workload -> if (workload == "intent") """{"skillKey": null}""" else "a reply" }
        val aiRuntimeSettings = AiRuntimeSettings(w.llmConfig, w.aiSettings, intentJsonModeDefault = null)
        val deps = buildDeps(w, client, aiRuntimeSettings)
        val engine = ChatEngineFactory.build(deps)

        w.aiSettings.save(model = null, temperature = null, maxOutputTokens = null, updatedBy = "admin", intentJsonMode = true)
        sendTurn(deps, engine, w.personaId)

        assertEquals(true, client.intentRequests().last().config.jsonMode)
        assertNull(client.generationRequests().last().config.jsonMode, "jsonMode is an Intent-only knob")
    }
}
