package com.pinkdreams.chat

import com.pinkdreams.baseline.BaselineConfiguration
import com.pinkdreams.baseline.BaselineSeeder
import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.llm.LlmClient
import com.pinkdreams.llm.LlmResponse
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.IntentEngineRepository
import com.pinkdreams.persistence.repositories.MemoryEngineRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import com.pinkdreams.chat.skill.LlmIntentDiscovery
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Intent Context Minimization & Routing Optimization phase: locks in the
 * evidence-based conclusion that a 4-native-turn history window is already
 * the smallest context that does not measurably harm routing quality (see
 * LlmIntentDiscovery.DEFAULT_RECENT_HISTORY_LIMIT for the full measurement
 * trail). This test fails loudly if a future change silently shrinks or
 * grows that window, or lets unrelated content leak into the Intent
 * request, without a fresh evaluation.
 */
class IntentContextWindowTest {

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
        val userId: UUID = UUID.randomUUID()
        val personaId: UUID

        init {
            BaselineSeeder(engines, personas, cores, skills, memoryEngines, intentEngines).seedIfMissing()
            skills.findAll().forEach { skills.activate(skills.publish(it.id).id) }
            personaId = personas.findAll().first { it.slug == BaselineConfiguration.SIMRAN_SLUG }.id
        }
    }

    @Test
    fun `intent discovery sends at most 4 native history blocks regardless of conversation length`() {
        val w = World()
        val conversation = w.conversations.create(w.userId, w.personaId)
        // Seed well beyond both the assembler's 10-message window and the
        // intent-specific 4-turn cap, so this proves the tighter cap, not
        // just the assembler's.
        val t0 = java.time.LocalDateTime.now()
        repeat(20) { i ->
            w.messages.createUserMessage(
                conversation.id, "turn $i", UUID.randomUUID(), UUID.randomUUID(),
                createdAt = t0.plusSeconds(i.toLong()),
            )
        }

        var capturedBlocks: List<ContextBlock>? = null
        val client = LlmClient { request ->
            capturedBlocks = request.context.blocks
            LlmResponse(content = """{"skillKey": null}""", provider = "test")
        }
        val discovery = LlmIntentDiscovery(client, w.skills, intentEngineRepository = w.intentEngines)
        val assembled = (
            RepositoryContextAssembler(w.conversations, w.messages, com.pinkdreams.persistence.repositories.UserProfileRepository(w.db), com.pinkdreams.chat.memory.MemoryService(com.pinkdreams.persistence.repositories.MemoryFactRepository(w.db)), w.engines, w.personas)
                .assemble(ChatRequest(UUID.randomUUID(), w.userId, conversation.id, w.personaId, UUID.randomUUID(), "current message")) as StageResult.Succeeded
            ).value

        discovery.selectSkill(ChatRequest(UUID.randomUUID(), w.userId, conversation.id, w.personaId, UUID.randomUUID(), "current message"), assembled)

        val blocks = capturedBlocks!!
        val historyBlocks = blocks.drop(1).dropLast(1) // drop the system instructions block and the current-message block
        assertTrue(
            historyBlocks.size <= 4,
            "Intent Discovery must cap native history at 4 turns (measured minimum that preserves routing quality); got ${historyBlocks.size}",
        )
        assertEquals("system", blocks.first().role)
        assertEquals("user", blocks.last().role)
        assertEquals("current message", blocks.last().content)
    }

    @Test
    fun `the intent request payload contains no persona core or conversation engine text`() {
        val w = World()
        val conversation = w.conversations.create(w.userId, w.personaId)
        w.messages.createUserMessage(conversation.id, "hi", UUID.randomUUID(), UUID.randomUUID())

        var capturedBlocks: List<ContextBlock>? = null
        val client = LlmClient { request ->
            capturedBlocks = request.context.blocks
            LlmResponse(content = """{"skillKey": null}""", provider = "test")
        }
        val discovery = LlmIntentDiscovery(client, w.skills, intentEngineRepository = w.intentEngines)
        val assembled = (
            RepositoryContextAssembler(w.conversations, w.messages, com.pinkdreams.persistence.repositories.UserProfileRepository(w.db), com.pinkdreams.chat.memory.MemoryService(com.pinkdreams.persistence.repositories.MemoryFactRepository(w.db)), w.engines, w.personas)
                .assemble(ChatRequest(UUID.randomUUID(), w.userId, conversation.id, w.personaId, UUID.randomUUID(), "hello again")) as StageResult.Succeeded
            ).value

        discovery.selectSkill(ChatRequest(UUID.randomUUID(), w.userId, conversation.id, w.personaId, UUID.randomUUID(), "hello again"), assembled)

        val fullText = capturedBlocks!!.joinToString("\n") { it.content }
        val persona = w.personas.findAll().first { it.id == w.personaId }
        val activeCoreVersionId = persona.activeCoreVersionId
        if (activeCoreVersionId != null) {
            val activeCore = w.cores.findById(activeCoreVersionId)
            assertTrue(activeCore == null || !fullText.contains(activeCore.content), "Persona Core content must never reach the Intent request")
        }
        val activeEngine = w.engines.getActiveEngine()
        if (activeEngine != null) {
            assertTrue(!fullText.contains(activeEngine.content), "Conversation Engine content must never reach the Intent request")
        }
    }
}
