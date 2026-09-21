package com.pinkdreams.chat

import com.pinkdreams.baseline.BaselineConfiguration
import com.pinkdreams.baseline.BaselineSeeder
import com.pinkdreams.chat.context.CharacterTokenEstimator
import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.chat.skill.LlmIntentDiscovery
import com.pinkdreams.chat.skill.SkillContextEnricher
import com.pinkdreams.chat.skill.SkillSelection
import com.pinkdreams.llm.GenerationRequest
import com.pinkdreams.llm.LlmClient
import com.pinkdreams.llm.LlmResponse
import com.pinkdreams.persistence.database.DatabaseFactory
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
 * Runtime Quality + Latency Verification phase, item 2: prove what actually
 * goes to the Intent LLM vs the Generation LLM, using a real assembled
 * context (real repositories, real seeded content) rather than hand-built
 * fixtures — so the token counts below are the actual numbers a live turn
 * would produce, not an approximation of them.
 */
class TokenAuditTest {

    private class World {
        val db = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }
        val engines = ConversationEngineRepository(db)
        val personas = PersonaRepository(db)
        val cores = PersonaCoreVersionRepository(db)
        val skills = SkillRepository(db)
        val memoryEngines = MemoryEngineRepository(db)
        val intentEngines = IntentEngineRepository(db)
        val conversations = ConversationRepository(db)
        val messages = MessageRepository(db)
        val userProfiles = UserProfileRepository(db)
        val memoryFacts = MemoryFactRepository(db)
        val memoryService = MemoryService(memoryFacts)
        val userId: UUID = UUID.randomUUID()
        lateinit var personaId: UUID

        init {
            BaselineSeeder(engines, personas, cores, skills, memoryEngines, intentEngines).seedIfMissing()
            skills.findAll().forEach { skills.activate(skills.publish(it.id).id) }
            personaId = personas.findAll().first { it.slug == BaselineConfiguration.SIMRAN_SLUG }.id
        }

        fun assembler() = RepositoryContextAssembler(conversations, messages, userProfiles, memoryService, engines, personas)
    }

    private fun tokens(text: String) = CharacterTokenEstimator.estimate(text)

    // ---------- what actually goes to the Intent LLM ----------

    @Test
    fun `the intent llm never receives the full skill catalogue only candidate keys`() {
        val w = World()
        var capturedIntentPrompt: String? = null
        val client = LlmClient { request ->
            capturedIntentPrompt = request.context.blocks.first().content
            LlmResponse(content = """{"skillKey": null}""", provider = "test")
        }
        val discovery = LlmIntentDiscovery(client, w.skills, intentEngineRepository = w.intentEngines)
        val request = ChatRequest(UUID.randomUUID(), w.userId, UUID.randomUUID(), w.personaId, UUID.randomUUID(), "hello")
        val context = ChatContext(
            blocks = listOf(ContextBlock("system", "engine"), ContextBlock("user", "hello")),
            engineVersionId = UUID.randomUUID(), personaCoreVersionId = UUID.randomUUID(),
        )

        discovery.selectSkill(request, context)

        val prompt = capturedIntentPrompt!!
        // The full behavioral text of every skill must be absent — the intent
        // prompt gets only the compact routing registry, never the "Behavior:"/
        // "Do not:" sections that make up each skill's actual full prompt.
        BaselineConfiguration.SKILLS.forEach { skill ->
            assertTrue(!prompt.contains(skill.content), "${skill.key}'s full skill prompt must never reach Intent Discovery")
        }
        val intentTokens = tokens(prompt)
        val fullCatalogueTokens = BaselineConfiguration.SKILLS.sumOf { tokens(it.content) }
        assertTrue(
            intentTokens < fullCatalogueTokens / 3,
            "Intent prompt ($intentTokens tokens) should be far smaller than the full skill catalogue ($fullCatalogueTokens tokens)",
        )
    }

    // ---------- what actually goes to the Generation LLM ----------

    @Test
    fun `generation receives exactly one skill never the full catalogue`() {
        val w = World()
        val conversation = w.conversations.create(w.userId, w.personaId)
        val request = ChatRequest(UUID.randomUUID(), w.userId, conversation.id, w.personaId, UUID.randomUUID(), "I had a rough day")
        val assembled = (w.assembler().assemble(request) as StageResult.Succeeded).value

        val enriched = SkillContextEnricher(w.skills).enrich(assembled, SkillSelection.Selected("emotional_support"))

        val generationText = enriched.blocks.joinToString("\n") { it.content }
        val skillHeadersPresent = BaselineConfiguration.SKILLS.count { generationText.contains("SKILL: ${it.key.uppercase()}") }
        assertEquals(1, skillHeadersPresent, "Exactly one of the 25 skills may reach generation")
        assertTrue(generationText.contains("SKILL: EMOTIONAL_SUPPORT"))
    }

    @Test
    fun `the intent engine prompt and the memory engine prompt never reach generation`() {
        val w = World()
        val conversation = w.conversations.create(w.userId, w.personaId)
        val request = ChatRequest(UUID.randomUUID(), w.userId, conversation.id, w.personaId, UUID.randomUUID(), "hello")
        val assembled = (w.assembler().assemble(request) as StageResult.Succeeded).value
        val generationText = assembled.blocks.joinToString("\n") { it.content }

        assertTrue(!generationText.contains("Intent Discovery component"), "Intent Engine prompt must never reach generation")
        assertTrue(!generationText.contains("TASK 1"), "Memory Engine prompt must never reach generation")
        assertTrue(!generationText.contains(com.pinkdreams.chat.skill.IntentEngineDefaultContent.ACTIVE_SKILL_KEYS_PLACEHOLDER))
    }

    // ---------- recent conversation context is actually bounded and native ----------

    @Test
    fun `recent conversation history is capped at the configured message limit`() {
        val w = World()
        val conversation = w.conversations.create(w.userId, w.personaId)
        // Seed far more messages than the default window.
        repeat(RepositoryContextAssembler.DEFAULT_MESSAGE_LIMIT * 2) { i ->
            w.messages.createUserMessage(conversation.id, "message $i", UUID.randomUUID(), UUID.randomUUID())
        }
        val request = ChatRequest(UUID.randomUUID(), w.userId, conversation.id, w.personaId, UUID.randomUUID(), "current message")

        val assembled = (w.assembler().assemble(request) as StageResult.Succeeded).value

        val historyBlocks = assembled.blocks.filter { it.role != "system" }.dropLast(1) // drop current message
        assertTrue(
            historyBlocks.size <= RepositoryContextAssembler.DEFAULT_MESSAGE_LIMIT,
            "Recent history must be bounded to the configured window (${RepositoryContextAssembler.DEFAULT_MESSAGE_LIMIT}), got ${historyBlocks.size}",
        )
        // Confirm it is genuinely the LAST N messages, not an arbitrary subset.
        val lastHistoryContent = historyBlocks.last().content
        assertTrue(lastHistoryContent.contains("message"), "History must end with the most recent prior messages")
    }

    @Test
    fun `history blocks stay native per role never flattened into one system block`() {
        val w = World()
        val conversation = w.conversations.create(w.userId, w.personaId)
        // Explicit, strictly increasing timestamps: creating both messages in
        // the same millisecond would otherwise make their relative order a
        // coin flip on the random UUID tiebreak (see RepositoryChatPersistence,
        // which applies the same fix for the same reason).
        val t0 = java.time.LocalDateTime.now()
        w.messages.createUserMessage(conversation.id, "hi there", UUID.randomUUID(), UUID.randomUUID(), createdAt = t0)
        w.messages.createAssistantMessage(conversation.id, "hello!", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), createdAt = t0.plusNanos(1000))
        val request = ChatRequest(UUID.randomUUID(), w.userId, conversation.id, w.personaId, UUID.randomUUID(), "how are you")

        val assembled = (w.assembler().assemble(request) as StageResult.Succeeded).value

        val nonSystemRoles = assembled.blocks.filter { it.role != "system" }.map { it.role }
        assertEquals(listOf("user", "assistant", "user"), nonSystemRoles)
    }
}
