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
 * Intent Discovery Budget Investigation phase: locks in the per-workload
 * maxOutputTokens override added for this phase's benchmarking
 * ([ChatEngineFactory.Dependencies.intentMaxOutputTokensOverride]), and
 * documents the currently-discovered, UNCHANGED production behavior around
 * budget exhaustion (see [budget exhaustion is currently indistinguishable
 * from a genuine None decision] below) — this phase is measurement only, so
 * that behavior is deliberately not altered here.
 */
class IntentBudgetOverrideTest {

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

    private fun buildDeps(w: World, client: LlmClient, intentMaxOutputTokensOverride: Int?): ChatEngineFactory.Dependencies {
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
            intentMaxOutputTokensOverride = intentMaxOutputTokensOverride,
        )
    }

    private fun runOneTurn(w: World, client: LlmClient, intentMaxOutputTokensOverride: Int?) {
        val deps = buildDeps(w, client, intentMaxOutputTokensOverride)
        val engine = ChatEngineFactory.build(deps)
        val userId = UUID.randomUUID()
        val conversation = deps.conversationRepository.create(userId, w.personaId)
        val request = ChatRequest(UUID.randomUUID(), userId, conversation.id, w.personaId, UUID.randomUUID(), "hello")
        val result = engine.process(request)
        assertTrue(result is ChatResult.Success)
    }

    @Test
    fun `with no override intent discovery keeps the production default of 600`() {
        val w = World()
        val capturedBudgets = mutableMapOf<String, Int?>()
        val client = LlmClient { request ->
            val system = request.context.blocks.firstOrNull { it.role == "system" }?.content ?: ""
            val callType = if (system.contains("Intent Discovery component")) "intent" else "generation"
            capturedBudgets[callType] = request.config.maxOutputTokens
            val body = if (callType == "intent") """{"skillKey": null}""" else "a reply"
            LlmResponse(content = body, provider = "test")
        }

        runOneTurn(w, client, intentMaxOutputTokensOverride = null)

        assertEquals(600, capturedBudgets["intent"], "Production default for Intent Discovery must remain 600")
    }

    @Test
    fun `an explicit override changes only intent discovery's budget`() {
        val w = World()
        val capturedBudgets = mutableMapOf<String, Int?>()
        val client = LlmClient { request ->
            val system = request.context.blocks.firstOrNull { it.role == "system" }?.content ?: ""
            val callType = when {
                system.contains("Intent Discovery component") -> "intent"
                system.contains("CONVERSATION ENGINE") -> "generation"
                else -> "other"
            }
            if (callType != "other") capturedBudgets[callType] = request.config.maxOutputTokens
            val body = if (callType == "intent") """{"skillKey": null}""" else "a reply"
            LlmResponse(content = body, provider = "test")
        }

        runOneTurn(w, client, intentMaxOutputTokensOverride = 1024)

        assertEquals(1024, capturedBudgets["intent"], "The override must apply to Intent Discovery")
        assertEquals(2048, capturedBudgets["generation"], "Primary generation's budget must be unaffected by the Intent-only override")
    }

    /**
     * Documents the CURRENT, unchanged production behavior discovered this
     * phase: when the provider client throws because the model exhausted its
     * budget on reasoning before emitting content (see
     * OpenRouterLlmClient's blank-content handling), LlmIntentDiscovery's
     * catch-all silently converts this into SkillSelection.None — completely
     * indistinguishable from a genuine model decision that no skill applies.
     * This phase is measurement-only and does not change this; it is
     * recorded here so a future fix has a regression test to flip.
     */
    @Test
    fun `budget exhaustion is currently indistinguishable from a genuine None decision`() {
        val w = World()
        val client = LlmClient { request ->
            val system = request.context.blocks.firstOrNull { it.role == "system" }?.content ?: ""
            if (system.contains("Intent Discovery component")) {
                throw IllegalStateException(
                    "OpenRouter returned no content (finish_reason=length, reasoningChars=725, completionTokens=600). " +
                        "For reasoning models this usually means max_tokens was exhausted by reasoning before any content was produced.",
                )
            }
            LlmResponse(content = "a reply", provider = "test")
        }
        val deps = buildDeps(w, client, intentMaxOutputTokensOverride = null)
        val engine = ChatEngineFactory.build(deps)
        val userId = UUID.randomUUID()
        val conversation = deps.conversationRepository.create(userId, w.personaId)
        val request = ChatRequest(UUID.randomUUID(), userId, conversation.id, w.personaId, UUID.randomUUID(), "hello")

        val result = engine.process(request)

        assertTrue(result is ChatResult.Success, "A budget-exhausted Intent call must still never block the chat turn")
        // No field anywhere in the successful result distinguishes this from a
        // genuine None decision — that absence is exactly the finding.
    }
}
