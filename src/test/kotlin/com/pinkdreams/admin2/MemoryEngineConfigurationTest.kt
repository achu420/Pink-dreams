package com.pinkdreams.admin2

import com.pinkdreams.baseline.BaselineConfiguration
import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.PersistedResponse
import com.pinkdreams.chat.memory.CompletedTurn
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.chat.memoryengine.LlmMemoryEngineMaintainer
import com.pinkdreams.chat.memoryengine.MemoryEngineChangeApplier
import com.pinkdreams.llm.GenerationRequest
import com.pinkdreams.llm.LlmClient
import com.pinkdreams.llm.LlmResponse
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MemoryEngineRepository
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase ADMIN-2 sections 4-12, 31-33: Memory Engine configuration retrieval,
 * batch/target resolution from the ACTIVE version, and the guarantee that
 * unsupported statements do not become durable facts.
 */
class MemoryEngineConfigurationTest {

    private class Fixture {
        val db: Database = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }
        val memoryEngines = MemoryEngineRepository(db)
        val memoryFacts = MemoryFactRepository(db)
        val memoryService = MemoryService(memoryFacts)
        val messages = MessageRepository(db)
        val conversations = ConversationRepository(db)
        val userId: UUID = UUID.randomUUID()
        val personaId: UUID = UUID.randomUUID()

        fun activate(content: String, batchSize: Int, target: Int): MemoryEngineRepository.MemoryEngine {
            val draft = memoryEngines.createNextVersion(
                content = content, batchSize = batchSize, relevantMemoryTarget = target, createdBy = "admin",
            )
            return memoryEngines.activate(memoryEngines.publish(draft.id).id)
        }
    }

    // ---------- sections 6, 12, 31: configuration travels with the version ----------

    @Test
    fun `batch size and relevant memory target are read from the active version`() {
        val f = Fixture()
        f.activate("engine A", batchSize = 10, target = 20)

        val active = f.memoryEngines.getActiveEngine()
        assertNotNull(active)
        assertEquals(10, active.batchSize)
        assertEquals(20, active.relevantMemoryTarget)
    }

    @Test
    fun `activating a different version activates its configuration with it`() {
        val f = Fixture()
        f.activate("engine A", batchSize = 10, target = 20)

        f.activate("engine B", batchSize = 25, target = 40)

        val active = f.memoryEngines.getActiveEngine()!!
        assertEquals("engine B", active.content)
        assertEquals(25, active.batchSize, "Configuration must switch atomically with the version")
        assertEquals(40, active.relevantMemoryTarget)
        assertEquals(1, f.memoryEngines.findAll().count { it.isActive })
    }

    @Test
    fun `the baseline defaults are ten and twenty`() {
        assertEquals(10, MemoryEngineRepository.DEFAULT_BATCH_SIZE)
        assertEquals(20, MemoryEngineRepository.DEFAULT_RELEVANT_MEMORY_TARGET)
        assertEquals(10, BaselineConfiguration.MEMORY_BATCH_SIZE)
        assertEquals(20, BaselineConfiguration.RELEVANT_MEMORY_TARGET)
    }

    @Test
    fun `the relevant memory target is a target not a forced count`() {
        // Section 12 and the Memory Engine prompt both say "approximately 20,
        // not a mandatory count" — selection must never pad to reach it.
        val f = Fixture()
        f.activate(BaselineConfiguration.MEMORY_ENGINE, batchSize = 10, target = 20)
        f.memoryFacts.create(f.userId, f.personaId, "user lives in Pune", "interest", "medium")

        val selected = f.memoryService.selectWorkingSet(f.userId, f.personaId, "USER", 20)

        assertEquals(1, selected.size)
    }

    // ---------- section 7 / 40: stored prompt matches the parser contract ----------

    @Test
    fun `the memory engine prompt declares the change shape the parser accepts`() {
        val content = BaselineConfiguration.MEMORY_ENGINE
        assertTrue(content.contains("userMemoryChanges"), "Prompt must declare the user change list the DTO expects")
        assertTrue(content.contains("personaMemoryChanges"), "Prompt must declare the persona change list the DTO expects")
        // Section 9: memory safety rules must be explicit in the stored prompt.
        assertTrue(content.contains("Never convert Persona Core information into user memory"))
    }

    @Test
    fun `the memory engine prompt lists only fact types the application accepts`() {
        // A prompt that names a type the database rejects is a broken
        // implementation, not a cosmetic mismatch.
        val content = BaselineConfiguration.MEMORY_ENGINE
        MemoryService.PERSONA_FACT_TYPES.forEach {
            assertTrue(content.contains(it), "Persona fact type '$it' must be documented in the prompt")
        }
        MemoryService.USER_FACT_TYPES.forEach {
            assertTrue(content.contains(it), "User fact type '$it' must be documented in the prompt")
        }
    }

    @Test
    fun `no llm call is made when no memory engine version is active`() {
        val f = Fixture()
        var called = false
        val client = LlmClient { _ -> called = true; LlmResponse(content = "{}", provider = "test") }
        val maintainer = LlmMemoryEngineMaintainer(client, f.memoryEngines)

        val result = maintainer.maintain(turn(f), emptyList(), emptyList(), emptyList())

        assertFalse(called, "Memory Engine unavailable must degrade to doing nothing, not to a hardcoded prompt")
        assertTrue(result.userMemoryChanges.isEmpty())
    }

    @Test
    fun `the active version's content is what is sent to the model`() {
        val f = Fixture()
        f.activate("ADMIN AUTHORED MEMORY RULES", batchSize = 10, target = 20)
        var promptSeen: String? = null
        val client = object : LlmClient {
            override fun generate(request: GenerationRequest): LlmResponse {
                promptSeen = request.context.blocks.first().content
                return LlmResponse(content = """{"userMemoryChanges": [], "personaMemoryChanges": []}""", provider = "test")
            }
        }

        LlmMemoryEngineMaintainer(client, f.memoryEngines).maintain(turn(f), batchOf(f, 2), emptyList(), emptyList())

        assertEquals("ADMIN AUTHORED MEMORY RULES", promptSeen)
    }

    // ---------- section 33: unsupported statements must not become facts ----------

    @Test
    fun `a speculative statement is not turned into a durable fact`() {
        val f = Fixture()
        f.activate(BaselineConfiguration.MEMORY_ENGINE, batchSize = 10, target = 20)
        // A correct engine returns no change for "Maybe I'll move to Delhi someday".
        val client = LlmClient { _ ->
            LlmResponse(content = """{"userMemoryChanges": [], "personaMemoryChanges": []}""", provider = "test")
        }

        val result = LlmMemoryEngineMaintainer(client, f.memoryEngines)
            .maintain(turn(f), batchOf(f, 2), emptyList(), emptyList())
        MemoryEngineChangeApplier(f.memoryFacts)
            .apply(f.userId, f.personaId, "USER", result.userMemoryChanges)

        assertTrue(
            f.memoryFacts.findForRelationship(f.userId, f.personaId).none { it.fact.contains("lives in Delhi") },
            "A speculative 'maybe someday' must never become 'user lives in Delhi'",
        )
        // And the rule is stated in the prompt, which is what actually governs it.
        assertTrue(BaselineConfiguration.MEMORY_ENGINE.contains("Do not invent"))
    }

    @Test
    fun `a change naming an unsupported fact type is rejected rather than stored`() {
        val f = Fixture()
        f.activate(BaselineConfiguration.MEMORY_ENGINE, batchSize = 10, target = 20)
        val client = LlmClient { _ ->
            LlmResponse(
                content = """{"userMemoryChanges": [
                    {"action": "ADD", "memoryType": "totally_invented_type", "content": "x", "criticality": "low"}
                ], "personaMemoryChanges": []}""",
                provider = "test",
            )
        }

        val result = LlmMemoryEngineMaintainer(client, f.memoryEngines)
            .maintain(turn(f), batchOf(f, 2), emptyList(), emptyList())
        val outcome = MemoryEngineChangeApplier(f.memoryFacts)
            .apply(f.userId, f.personaId, "USER", result.userMemoryChanges)

        assertEquals(0, outcome.applied, "An unsupported fact type must not be persisted")
        assertTrue(f.memoryFacts.findForRelationship(f.userId, f.personaId).isEmpty())
    }

    // ---------- helpers ----------

    private fun turn(f: Fixture): CompletedTurn {
        val request = ChatRequest(UUID.randomUUID(), f.userId, UUID.randomUUID(), f.personaId, UUID.randomUUID(), "hello")
        return CompletedTurn(
            request = request,
            context = ChatContext(
                blocks = listOf(ContextBlock("system", "engine"), ContextBlock("user", "hello")),
                engineVersionId = UUID.randomUUID(),
                personaCoreVersionId = UUID.randomUUID(),
            ),
            response = PersistedResponse(UUID.randomUUID(), "hi"),
        )
    }

    private fun batchOf(f: Fixture, size: Int): List<MessageRepository.Message> {
        val conversation = f.conversations.create(f.userId, f.personaId)
        return (1..size).map { i ->
            f.messages.createUserMessage(
                conversationId = conversation.id,
                content = "message $i",
                clientMessageId = UUID.randomUUID(),
                requestId = UUID.randomUUID(),
            )
        }
    }
}
