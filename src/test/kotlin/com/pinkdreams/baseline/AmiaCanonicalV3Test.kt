package com.pinkdreams.baseline

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.skill.IntentEngineDefaultContent
import com.pinkdreams.chat.skill.LlmIntentDiscovery
import com.pinkdreams.chat.skill.SkillContextEnricher
import com.pinkdreams.chat.skill.SkillSelection
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.llm.GenerationRequest
import com.pinkdreams.llm.LlmClient
import com.pinkdreams.llm.LlmResponse
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.IntentEngineRepository
import com.pinkdreams.persistence.repositories.MemoryEngineRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AMIA Canonical AI Behavior Configuration v3 — the expanded skill roster (12
 * -> 25 skills), the model change to deepseek/deepseek-v4-flash-0731, and the
 * new Conversation Engine product-behavior rules (response length, turn
 * balance, capability discovery, emotional availability).
 *
 * These tests verify additions on top of the existing Phase D / ADMIN-2 /
 * ADMIN-3 baseline, which already covers seeding idempotency, persona/memory
 * separation, skill isolation, and the Test Chat pipeline — none of that is
 * re-duplicated here.
 */
class AmiaCanonicalV3Test {

    private val v3SkillKeys = setOf(
        "companionship", "friendship", "emotional_support", "general_chat",
        "flirting", "romantic_conversation", "relationship_building", "relationship_discussion",
        "dating", "playful_teasing", "romantic_intimacy", "foreplay",
        "sexual_stimulation", "social_practice", "conversation_practice", "flirting_practice",
        "dating_practice", "relationship_guidance", "breakup_support", "confidence_building",
        "encouragement", "advice", "problem_solving", "learning", "entertainment",
    )

    // ---------- roster completeness ----------

    @Test
    fun `the canonical skill roster contains exactly the 25 v3 skills`() {
        assertEquals(v3SkillKeys, BaselineConfiguration.SKILLS.map { it.key }.toSet())
    }

    @Test
    fun `every v3 skill follows the mandated structural format`() {
        BaselineConfiguration.SKILLS.forEach { skill ->
            assertTrue(skill.content.startsWith("SKILL: "), "${skill.key} must start with the SKILL header")
            assertTrue(skill.content.contains("Purpose:"), "${skill.key} must declare a Purpose")
            assertTrue(skill.content.contains("Behavior:"), "${skill.key} must declare Behavior")
            assertTrue(skill.content.contains("Do not:"), "${skill.key} must declare Do not")
        }
    }

    @Test
    fun `no new skill restates the persona core or conversation engine`() {
        BaselineConfiguration.SKILLS.forEach { skill ->
            assertTrue(!skill.content.contains("PERSONA CORE — SIMRAN"), "${skill.key} must reference the Persona Core, never copy it")
            assertTrue(!skill.content.contains("CONVERSATION ENGINE — UNIVERSAL"), "${skill.key} must reference the Conversation Engine, never copy it")
        }
    }

    // ---------- Intent Engine knows every skill; generation never sees the full catalogue ----------

    private fun seededSkillRepo(): Triple<SkillRepository, IntentEngineRepository, BaselineSeeder> {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val skills = SkillRepository(db)
        val intentEngines = IntentEngineRepository(db)
        val seeder = BaselineSeeder(
            ConversationEngineRepository(db), PersonaRepository(db), PersonaCoreVersionRepository(db),
            skills, MemoryEngineRepository(db), intentEngines,
        )
        seeder.seedIfMissing()
        skills.findAll().forEach { skills.activate(skills.publish(it.id).id) }
        return Triple(skills, intentEngines, seeder)
    }

    private fun requestAndContext(message: String): Pair<ChatRequest, ChatContext> {
        val request = ChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), message)
        val context = ChatContext(
            blocks = listOf(ContextBlock("system", "engine+persona"), ContextBlock("user", message)),
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID(),
        )
        return request to context
    }

    private class ScriptedClient(private val key: String) : LlmClient {
        var promptSeen: String? = null
        override fun generate(request: GenerationRequest): LlmResponse {
            promptSeen = request.context.blocks.first().content
            return LlmResponse(content = """{"skillKey": "$key"}""", provider = "test")
        }
    }

    @Test
    fun `every one of the 25 skills is selectable through intent discovery`() {
        val (skills, intentEngines, _) = seededSkillRepo()
        v3SkillKeys.forEach { key ->
            val client = ScriptedClient(key)
            val discovery = LlmIntentDiscovery(client, skills, intentEngineRepository = intentEngines)
            val (request, context) = requestAndContext("message about $key")

            assertEquals(SkillSelection.Selected(key), discovery.selectSkill(request, context), "$key must be selectable")
        }
    }

    @Test
    fun `the intent engine glossary documents every new v3 skill`() {
        val content = IntentEngineDefaultContent.CONTENT
        v3SkillKeys.forEach { key ->
            assertTrue(content.contains(key), "Intent Engine glossary must mention $key")
        }
    }

    @Test
    fun `the intent engine distinguishes live interaction from explicit practice requests`() {
        val content = IntentEngineDefaultContent.CONTENT
        assertTrue(content.contains("DISTINGUISHING A REAL INTERACTION FROM A REQUEST TO PRACTICE IT"))
        assertTrue(content.contains("flirting_practice"))
        assertTrue(content.contains("dating_practice"))
    }

    @Test
    fun `disambiguation guidance covers the v3 overlap pairs`() {
        val content = IntentEngineDefaultContent.CONTENT
        assertTrue(content.contains("romantic_intimacy vs foreplay"))
        assertTrue(content.contains("foreplay vs sexual_stimulation"))
        assertTrue(content.contains("relationship_discussion vs relationship_guidance"))
        assertTrue(content.contains("confidence_building vs emotional_support"))
    }

    @Test
    fun `only the selected skill among all 25 ever reaches the generation context`() {
        val (skills, _, _) = seededSkillRepo()
        val context = ChatContext(
            blocks = listOf(ContextBlock("system", "engine"), ContextBlock("user", "hello")),
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID(),
        )

        val enriched = SkillContextEnricher(skills).enrich(context, SkillSelection.Selected("confidence_building"))

        val sent = enriched.blocks.joinToString("\n") { it.content }
        val presentSkillHeaders = BaselineConfiguration.SKILLS.count { sent.contains("SKILL: ${it.key.uppercase()}") }
        assertEquals(1, presentSkillHeaders, "Exactly the selected skill of the 25 must reach generation, never the full catalogue")
    }

    // ---------- Conversation Engine v3 product-behavior rules ----------

    @Test
    fun `the conversation engine declares response length targets`() {
        val content = BaselineConfiguration.CONVERSATION_ENGINE
        assertTrue(content.contains("Response length"))
        assertTrue(content.contains("10-60 words"))
        assertTrue(content.contains("40-120 words"))
    }

    @Test
    fun `the conversation engine declares turn balance rules`() {
        val content = BaselineConfiguration.CONVERSATION_ENGINE
        assertTrue(content.contains("Conversational turn balance"))
        assertTrue(content.contains("Do not ask a question after every response"))
    }

    @Test
    fun `the conversation engine declares capability discovery`() {
        val content = BaselineConfiguration.CONVERSATION_ENGINE
        assertTrue(content.contains("capability discovery") || content.contains("Capability discovery") || content.contains("User-goal orientation"))
        assertTrue(content.contains("Let's practise here."))
    }

    @Test
    fun `the conversation engine declares emotional availability without unsolicited meta lecturing`() {
        val content = BaselineConfiguration.CONVERSATION_ENGINE
        assertTrue(content.contains("Emotional availability"))
        assertTrue(content.contains("Do not spontaneously lecture the user about AI"))
        assertTrue(content.contains("Do not create exclusivity, guilt, jealousy"))
    }

    // ---------- model change ----------

    @Test
    fun `the default generation model is the v3 canonical model`() {
        assertEquals("deepseek/deepseek-v4-flash-0731", LlmConfig(apiKey = "k").model)
    }

    @Test
    fun `every chat engine factory workload uses the same configured model`() {
        // ChatEngineFactory.build() passes llmConfig.model into every workload's
        // GenerationConfig (primary generation, intent discovery, memory
        // extraction) — none of them hardcode a model of their own. Drive one
        // real turn and capture request.config.model from each call site.
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

        val modelsByCallType = mutableMapOf<String, String?>()
        val client = com.pinkdreams.llm.LlmClient { request ->
            val system = request.context.blocks.firstOrNull { it.role == "system" }?.content ?: ""
            val callType = when {
                system.contains("Intent Discovery component") -> "intent"
                system.contains("memory-extraction system") -> "memory_extraction"
                system.contains("CONVERSATION ENGINE") -> "generation"
                else -> "other"
            }
            modelsByCallType[callType] = request.config.model
            val body = when (callType) {
                "intent" -> """{"skillKey": null}"""
                "memory_extraction" -> """{"facts": []}"""
                else -> "a reply"
            }
            com.pinkdreams.llm.LlmResponse(content = body, provider = "test")
        }
        val llmConfig = LlmConfig(apiKey = "k", model = "deepseek/deepseek-v4-flash-0731")
        val memoryFacts = com.pinkdreams.persistence.repositories.MemoryFactRepository(db)
        val memoryService = com.pinkdreams.chat.memory.MemoryService(memoryFacts)
        val aiSettings = com.pinkdreams.persistence.repositories.AiSettingsRepository(db)
        val executions = com.pinkdreams.persistence.repositories.ChatRequestExecutionRepository(db)
        val aiRuntimeSettings = com.pinkdreams.config.AiRuntimeSettings(llmConfig, aiSettings)
        val conversationRepo = com.pinkdreams.persistence.repositories.ConversationRepository(db)
        val messageRepo = com.pinkdreams.persistence.repositories.MessageRepository(db)

        val deps = com.pinkdreams.chat.ChatEngineFactory.Dependencies(
            llmClient = client, llmConfig = llmConfig, db = db,
            conversationRepository = conversationRepo, messageRepository = messageRepo,
            userProfileRepository = com.pinkdreams.persistence.repositories.UserProfileRepository(db),
            memoryService = memoryService, memoryFactRepository = memoryFacts,
            engineRepository = engines, personaRepository = personas, skillRepository = skills,
            memoryEngineRepository = memoryEngines, intentEngineRepository = intentEngines,
            executionRepository = executions, aiRuntimeSettings = aiRuntimeSettings,
        )
        val engine = com.pinkdreams.chat.ChatEngineFactory.build(deps)
        val userId = UUID.randomUUID()
        val conversation = conversationRepo.create(userId, personaId)
        val request = ChatRequest(UUID.randomUUID(), userId, conversation.id, personaId, UUID.randomUUID(), "hello there")

        val result = engine.process(request)

        assertTrue(result is com.pinkdreams.chat.ChatResult.Success)
        assertEquals("deepseek/deepseek-v4-flash-0731", modelsByCallType["generation"])
        assertEquals("deepseek/deepseek-v4-flash-0731", modelsByCallType["intent"])
        // Memory extraction dispatches asynchronously; give it a moment.
        Thread.sleep(500)
        assertEquals("deepseek/deepseek-v4-flash-0731", modelsByCallType["memory_extraction"], "Async memory extraction must use the same configured model")
    }
}
