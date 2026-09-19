package com.pinkdreams.admin2

import com.pinkdreams.baseline.BaselineConfiguration
import com.pinkdreams.baseline.BaselineSeeder
import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.PersistedResponse
import com.pinkdreams.chat.memory.CompletedTurn
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.chat.memoryengine.LlmMemoryEngineMaintainer
import com.pinkdreams.chat.memoryengine.MemoryEngineChangeApplier
import com.pinkdreams.chat.skill.IntentEngineDefaultContent
import com.pinkdreams.chat.skill.LlmIntentDiscovery
import com.pinkdreams.chat.skill.SkillContextEnricher
import com.pinkdreams.chat.skill.SkillSelection
import com.pinkdreams.config.AiRuntimeSettings
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.llm.GenerationRequest
import com.pinkdreams.llm.LlmClient
import com.pinkdreams.llm.LlmGenerator
import com.pinkdreams.llm.LlmResponse
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.AiSettingsRepository
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.IntentEngineRepository
import com.pinkdreams.persistence.repositories.MemoryEngineRepository
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase ADMIN-2 sections 32 and 35: admin configuration changes reaching the
 * runtime, and the Memory Engine batch loop feeding its updated memory set
 * into the next batch.
 */
class AdminConfigurationEndToEndTest {

    private class World {
        val db: Database = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }
        val engines = ConversationEngineRepository(db)
        val personas = PersonaRepository(db)
        val cores = PersonaCoreVersionRepository(db)
        val skills = SkillRepository(db)
        val memoryEngines = MemoryEngineRepository(db)
        val intentEngines = IntentEngineRepository(db)
        val aiSettings = AiSettingsRepository(db)
        val memoryFacts = MemoryFactRepository(db)
        val memoryService = MemoryService(memoryFacts)
        val messages = MessageRepository(db)
        val conversations = ConversationRepository(db)
        val userId: UUID = UUID.randomUUID()
        val personaId: UUID = UUID.randomUUID()

        init {
            BaselineSeeder(engines, personas, cores, skills, memoryEngines, intentEngines).seedIfMissing()
            skills.findAll().forEach { skills.activate(skills.publish(it.id).id) }
        }

        fun turn() = CompletedTurn(
            request = ChatRequest(UUID.randomUUID(), userId, UUID.randomUUID(), personaId, UUID.randomUUID(), "hello"),
            context = ChatContext(
                blocks = listOf(ContextBlock("system", "engine"), ContextBlock("user", "hello")),
                engineVersionId = UUID.randomUUID(),
                personaCoreVersionId = UUID.randomUUID(),
            ),
            response = PersistedResponse(UUID.randomUUID(), "hi"),
        )

        fun batch(size: Int): List<MessageRepository.Message> {
            val conversation = conversations.create(userId, personaId)
            return (1..size).map {
                messages.createUserMessage(conversation.id, "message $it", UUID.randomUUID(), UUID.randomUUID())
            }
        }
    }

    // ---------- section 35: admin changes Memory Engine → extraction uses it ----------

    @Test
    fun `publishing and activating a memory engine makes the runtime use it`() {
        val w = World()
        var promptSeen: String? = null
        val client = object : LlmClient {
            override fun generate(request: GenerationRequest): LlmResponse {
                promptSeen = request.context.blocks.first().content
                return LlmResponse(content = """{"userMemoryChanges": [], "personaMemoryChanges": []}""", provider = "test")
            }
        }
        val maintainer = LlmMemoryEngineMaintainer(client, w.memoryEngines)

        // The seeded baseline is active first.
        maintainer.maintain(w.turn(), w.batch(2), emptyList(), emptyList())
        assertEquals(BaselineConfiguration.MEMORY_ENGINE, promptSeen)

        // Admin: create → publish → activate.
        val draft = w.memoryEngines.createNextVersion("REVISED MEMORY RULES", batchSize = 15, relevantMemoryTarget = 30, createdBy = "admin")
        w.memoryEngines.activate(w.memoryEngines.publish(draft.id).id)

        maintainer.maintain(w.turn(), w.batch(2), emptyList(), emptyList())

        assertEquals("REVISED MEMORY RULES", promptSeen, "The newly activated version must be used")
        val active = w.memoryEngines.getActiveEngine()!!
        assertEquals(15, active.batchSize)
        assertEquals(30, active.relevantMemoryTarget)
    }

    @Test
    fun `a draft memory engine is not used until it is activated`() {
        val w = World()
        var promptSeen: String? = null
        val client = object : LlmClient {
            override fun generate(request: GenerationRequest): LlmResponse {
                promptSeen = request.context.blocks.first().content
                return LlmResponse(content = """{"userMemoryChanges": [], "personaMemoryChanges": []}""", provider = "test")
            }
        }

        // Section 38: creation must not auto-activate.
        w.memoryEngines.createNextVersion("UNAPPROVED RULES", createdBy = "admin")
        LlmMemoryEngineMaintainer(client, w.memoryEngines).maintain(w.turn(), w.batch(2), emptyList(), emptyList())

        assertEquals(BaselineConfiguration.MEMORY_ENGINE, promptSeen, "A draft must never reach the runtime")
    }

    // ---------- section 35: admin changes Intent Engine → selection uses it ----------

    @Test
    fun `publishing and activating an intent engine changes skill selection input`() {
        val w = World()
        var promptSeen: String? = null
        val client = object : LlmClient {
            override fun generate(request: GenerationRequest): LlmResponse {
                promptSeen = request.context.blocks.first().content
                return LlmResponse(content = """{"skillKey": "emotional_support"}""", provider = "test")
            }
        }
        val discovery = LlmIntentDiscovery(client, w.skills, intentEngineRepository = w.intentEngines)
        val request = ChatRequest(UUID.randomUUID(), w.userId, UUID.randomUUID(), w.personaId, UUID.randomUUID(), "rough day")
        val context = ChatContext(
            blocks = listOf(ContextBlock("system", "engine"), ContextBlock("user", "rough day")),
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID(),
        )

        assertEquals(SkillSelection.Selected("emotional_support"), discovery.selectSkill(request, context))
        assertTrue(promptSeen!!.contains("Intent Discovery component"))

        val draft = w.intentEngines.createNextVersion("REVISED INTENT RULES", createdBy = "admin")
        w.intentEngines.activate(w.intentEngines.publish(draft.id).id)

        val selection = discovery.selectSkill(request, context)

        assertEquals(SkillSelection.Selected("emotional_support"), selection)
        assertTrue(promptSeen!!.contains("REVISED INTENT RULES"))
        // And the selected skill flows on into generation context, unchanged.
        val enriched = SkillContextEnricher(w.skills).enrich(context, selection)
        assertEquals(1, enriched.blocks.count { it.content.startsWith("SELECTED SKILL (") })
        assertTrue(enriched.blocks.any { it.content.contains("SKILL: EMOTIONAL_SUPPORT") })
    }

    // ---------- section 35: admin changes AI settings → next request uses them ----------

    @Test
    fun `changing ai settings changes the next generation request`() {
        val w = World()
        val captured = mutableListOf<GenerationRequest>()
        val client = LlmClient { request -> captured += request; LlmResponse(content = "reply", provider = "test") }
        val envConfig = LlmConfig(apiKey = "k", model = "env-model", maxOutputTokens = 1024)
        val runtime = AiRuntimeSettings(envConfig, w.aiSettings)
        val generator = LlmGenerator(client, configProvider = { runtime.generationConfig() })

        val request = ChatRequest(UUID.randomUUID(), w.userId, UUID.randomUUID(), w.personaId, UUID.randomUUID(), "hi")
        val context = ChatContext(
            blocks = listOf(ContextBlock("system", "engine"), ContextBlock("user", "hi")),
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID(),
        )

        generator.generate(request, context)
        assertEquals("env-model", captured.last().config.model)
        assertEquals(1024, captured.last().config.maxOutputTokens)

        // Admin saves new settings — no restart, no re-construction.
        w.aiSettings.save(model = "admin-chosen-model", temperature = 0.35, maxOutputTokens = 2048, updatedBy = "admin")

        generator.generate(request, context)

        assertEquals("admin-chosen-model", captured.last().config.model, "The new model must reach the provider request")
        assertEquals(0.35, captured.last().config.temperature)
        assertEquals(2048, captured.last().config.maxOutputTokens)
    }

    // ---------- section 32: the batch loop feeds its output into the next batch ----------

    @Test
    fun `memory produced by one batch becomes the working set supplied to the next`() {
        val w = World()
        val applier = MemoryEngineChangeApplier(w.memoryFacts)

        // Batch 1: the engine extracts one durable fact.
        val batch1Client = LlmClient { _ ->
            LlmResponse(
                content = """{"userMemoryChanges": [
                    {"action": "ADD", "memoryType": "interest", "content": "user is a nurse in Chennai", "criticality": "medium"}
                ], "personaMemoryChanges": []}""",
                provider = "test",
            )
        }
        val result1 = LlmMemoryEngineMaintainer(batch1Client, w.memoryEngines)
            .maintain(w.turn(), w.batch(10), emptyList(), emptyList())
        val applied1 = applier.apply(w.userId, w.personaId, "USER", result1.userMemoryChanges)
        assertEquals(1, applied1.applied)

        // The working set for batch 2 is derived from stored memory, so it now
        // contains what batch 1 produced — this is the loop the phase describes.
        val target = w.memoryEngines.getActiveEngine()!!.relevantMemoryTarget
        val workingSetForBatch2 = w.memoryService.selectWorkingSet(w.userId, w.personaId, "USER", target)
        assertEquals(listOf("user is a nurse in Chennai"), workingSetForBatch2.map { it.fact })

        // Batch 2 receives it and supersedes it with corrected information.
        var suppliedToBatch2: String? = null
        val batch2Client = object : LlmClient {
            override fun generate(request: GenerationRequest): LlmResponse {
                suppliedToBatch2 = request.context.blocks.last().content
                return LlmResponse(
                    content = """{"userMemoryChanges": [
                        {"action": "ADD", "memoryType": "interest", "content": "user is a nurse in Madurai", "criticality": "medium"}
                    ], "personaMemoryChanges": []}""",
                    provider = "test",
                )
            }
        }
        val result2 = LlmMemoryEngineMaintainer(batch2Client, w.memoryEngines)
            .maintain(w.turn(), w.batch(10), workingSetForBatch2, emptyList())
        applier.apply(w.userId, w.personaId, "USER", result2.userMemoryChanges)

        assertNotNull(suppliedToBatch2)
        assertTrue(
            suppliedToBatch2!!.contains("user is a nurse in Chennai"),
            "Batch 2 must be given the memory batch 1 produced",
        )
        assertTrue(
            w.memoryService.selectWorkingSet(w.userId, w.personaId, "USER", target)
                .any { it.fact == "user is a nurse in Madurai" },
        )
    }

    @Test
    fun `the batch payload carries both the conversation and the current memory set`() {
        val w = World()
        w.memoryFacts.create(w.userId, w.personaId, "user teaches first aid", "habit", "low")
        w.memoryFacts.create(w.userId, w.personaId, "assistant promised to check in", "promise", "low", owner = "PERSONA")
        var payload: String? = null
        val client = object : LlmClient {
            override fun generate(request: GenerationRequest): LlmResponse {
                payload = request.context.blocks.last().content
                return LlmResponse(content = """{"userMemoryChanges": [], "personaMemoryChanges": []}""", provider = "test")
            }
        }

        LlmMemoryEngineMaintainer(client, w.memoryEngines).maintain(
            w.turn(),
            w.batch(10),
            w.memoryService.selectWorkingSet(w.userId, w.personaId, "USER", 20),
            w.memoryService.selectWorkingSet(w.userId, w.personaId, "PERSONA", 20),
        )

        assertNotNull(payload)
        assertTrue(payload!!.contains("RECENT CONVERSATION"))
        assertTrue(payload!!.contains("message 1") && payload!!.contains("message 10"), "All 10 batch messages must be supplied")
        assertTrue(payload!!.contains("user teaches first aid"), "User working memory must be supplied")
        assertTrue(payload!!.contains("assistant promised to check in"), "Persona working memory must be supplied")
    }

    // ---------- sections 27-28: control-plane prompts stay out of generation ----------

    @Test
    fun `neither the intent engine nor the memory engine prompt enters the generation context`() {
        val w = World()
        val captured = mutableListOf<GenerationRequest>()
        val client = LlmClient { request -> captured += request; LlmResponse(content = "reply", provider = "test") }

        val context = ChatContext(
            blocks = listOf(
                ContextBlock("system", "CONVERSATION ENGINE + PERSONA CORE"),
                ContextBlock("system", "RETRIEVED MEMORY:\n- user teaches first aid"),
                ContextBlock("user", "hello"),
            ),
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID(),
        )
        val enriched = SkillContextEnricher(w.skills).enrich(context, SkillSelection.Selected("general_chat"))
        LlmGenerator(client).generate(
            ChatRequest(UUID.randomUUID(), w.userId, UUID.randomUUID(), w.personaId, UUID.randomUUID(), "hello"),
            enriched,
        )

        val sent = captured.single().context.blocks.joinToString("\n") { it.content }
        assertTrue(!sent.contains("Intent Discovery component"), "The Intent Engine prompt must not reach generation")
        assertTrue(!sent.contains(IntentEngineDefaultContent.ACTIVE_SKILL_KEYS_PLACEHOLDER))
        assertTrue(!sent.contains("TASK 1 — EXTRACT DURABLE MEMORIES"), "The Memory Engine prompt must not reach generation")
        assertTrue(!sent.contains("RELEVANT_MEMORY_TARGET"))
        // The selected skill DOES belong there — it is an output, not machinery.
        assertTrue(sent.contains("SELECTED SKILL (general_chat)"))
    }

    @Test
    fun `the full skill catalogue never reaches the generation context`() {
        val w = World()
        val context = ChatContext(
            blocks = listOf(ContextBlock("system", "engine"), ContextBlock("user", "hello")),
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID(),
        )

        val enriched = SkillContextEnricher(w.skills).enrich(context, SkillSelection.Selected("flirting"))

        val sent = enriched.blocks.joinToString("\n") { it.content }
        val presentSkillHeaders = BaselineConfiguration.SKILLS.count { sent.contains("SKILL: ${it.key.uppercase()}") }
        assertEquals(1, presentSkillHeaders, "Exactly the selected skill, never the catalogue")
    }
}
