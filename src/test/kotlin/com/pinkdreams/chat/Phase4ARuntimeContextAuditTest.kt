package com.pinkdreams.chat

import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase 4A — Runtime Context & LLM Payload Audit.
 *
 * Covers the gaps not already exercised by ChatContextContractRegressionTest,
 * LlmClientBoundaryAuditTest, EndToEndLlmRequestRegressionTest, and
 * ContinuitySummarizationContextIntegrationTest: multi-version engine/persona
 * active-selection through the actual assembled context, and the token-budget
 * truncation defect where the current message was excluded from the budget
 * accounting.
 */
class Phase4ARuntimeContextAuditTest {

    private fun assembler(
        conversationRepository: ConversationRepository,
        messageRepository: MessageRepository,
        db: org.jetbrains.exposed.sql.Database,
        engineRepository: ConversationEngineRepository,
        personaRepository: PersonaRepository,
        tokenBudget: Int = RepositoryContextAssembler.DEFAULT_TOKEN_BUDGET,
    ) = RepositoryContextAssembler(
        conversationRepository,
        messageRepository,
        UserProfileRepository(db),
        MemoryService(MemoryFactRepository(db)),
        engineRepository,
        personaRepository,
        tokenBudget = tokenBudget,
    )

    // --- Engine: only the ACTIVE version reaches the LLM context, never latest/draft ---
    @Test
    fun `only the active engine version reaches context when old draft published and active versions all exist`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val userId = UUID.randomUUID()

        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("engine-audit-${UUID.randomUUID()}", "Test", "female", "straight", 30, emptyMap())
        val coreRepository = PersonaCoreVersionRepository(db)
        val core = coreRepository.create(persona.id, 1, "core content", "draft")
        personaRepository.activateCoreVersion(persona.id, coreRepository.publishCoreVersion(core.id).id)

        val engineRepository = ConversationEngineRepository(db)
        // v1 = old, already archived-equivalent history (published then superseded)
        val v1 = engineRepository.create(1, "ENGINE_V1_OLD_CONTENT", "published")
        // v2 = draft, must NEVER reach the LLM regardless of being the newest row
        engineRepository.create(2, "ENGINE_V2_DRAFT_CONTENT", "draft")
        // v3 = published but not yet activated — must NOT reach the LLM either
        val v3 = engineRepository.create(3, "ENGINE_V3_PUBLISHED_NOT_ACTIVE", "published")
        // v4 = published AND activated — this is the one that must reach the LLM
        val v4 = engineRepository.create(4, "ENGINE_V4_ACTIVE_CONTENT", "published")
        engineRepository.activateEngine(v1.id) // activate v1 first...
        engineRepository.activateEngine(v4.id) // ...then v4, proving mutual-exclusion deactivates v1

        val conversationRepository = ConversationRepository(db)
        val conversation = conversationRepository.create(userId, persona.id)
        val messageRepository = MessageRepository(db)

        val request = ChatRequest(UUID.randomUUID(), userId, conversation.id, persona.id, UUID.randomUUID(), "hello")
        val context = assertIs<StageResult.Succeeded<ChatContext>>(
            assembler(conversationRepository, messageRepository, db, engineRepository, personaRepository).assemble(request),
        ).value

        val engineBlock = context.blocks[0]
        assertTrue(engineBlock.content.contains("ENGINE_V4_ACTIVE_CONTENT"), "Active engine content must reach the context")
        assertFalse(engineBlock.content.contains("ENGINE_V1_OLD_CONTENT"), "Superseded (deactivated) engine content must not reach the context")
        assertFalse(engineBlock.content.contains("ENGINE_V2_DRAFT_CONTENT"), "Draft engine content must never reach the context")
        assertFalse(engineBlock.content.contains("ENGINE_V3_PUBLISHED_NOT_ACTIVE"), "Published-but-inactive engine content must not reach the context")
        assertEquals(v4.id, context.engineVersionId, "Provenance must record the actually-active engine version id")
        assertEquals(1, Regex("ENGINE_V4_ACTIVE_CONTENT").findAll(engineBlock.content).count(), "Active engine content must appear exactly once, not duplicated")
    }

    // --- Persona Core: only the ACTIVE version's content reaches the LLM ---
    @Test
    fun `only the active persona core version reaches context not the highest numbered draft`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val userId = UUID.randomUUID()

        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("persona-audit-${UUID.randomUUID()}", "Test", "female", "straight", 30, emptyMap())
        val coreRepository = PersonaCoreVersionRepository(db)

        val v1 = coreRepository.create(persona.id, 1, "PERSONA_VERSION_ONE", "draft")
        val v1Published = coreRepository.publishCoreVersion(v1.id)
        personaRepository.activateCoreVersion(persona.id, v1Published.id)

        // A newer draft exists with the highest version number, but is never activated.
        coreRepository.create(persona.id, 2, "PERSONA_VERSION_TWO", "draft")

        val engineRepository = ConversationEngineRepository(db)
        val engine = engineRepository.create(1, "engine rules", "draft")
        engineRepository.activateEngine(engineRepository.publishEngine(engine.id).id)

        val conversationRepository = ConversationRepository(db)
        val conversation = conversationRepository.create(userId, persona.id)
        val messageRepository = MessageRepository(db)

        val request = ChatRequest(UUID.randomUUID(), userId, conversation.id, persona.id, UUID.randomUUID(), "hello")
        val context = assertIs<StageResult.Succeeded<ChatContext>>(
            assembler(conversationRepository, messageRepository, db, engineRepository, personaRepository).assemble(request),
        ).value

        val personaBlock = context.blocks[0]
        assertTrue(personaBlock.content.contains("PERSONA_VERSION_ONE"), "Active (v1) persona core content must reach the context")
        assertFalse(personaBlock.content.contains("PERSONA_VERSION_TWO"), "Unactivated draft (v2), despite being the highest version number, must not reach the context")
        assertEquals(v1Published.id, context.personaCoreVersionId, "Provenance must record the actually-active persona core version id")
    }

    // --- Truncation defect: current message must count toward the token budget ---
    @Test
    fun `current message size counts toward the token budget and can trigger history trimming`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val userId = UUID.randomUUID()

        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("budget-audit-${UUID.randomUUID()}", "Test", "female", "straight", 30, emptyMap())
        val coreRepository = PersonaCoreVersionRepository(db)
        val core = coreRepository.create(persona.id, 1, "core", "draft")
        personaRepository.activateCoreVersion(persona.id, coreRepository.publishCoreVersion(core.id).id)
        val engineRepository = ConversationEngineRepository(db)
        val engine = engineRepository.create(1, "engine", "draft")
        engineRepository.activateEngine(engineRepository.publishEngine(engine.id).id)

        val conversationRepository = ConversationRepository(db)
        val conversation = conversationRepository.create(userId, persona.id)
        val messageRepository = MessageRepository(db)
        messageRepository.createUserMessage(conversation.id, "a short recent message", UUID.randomUUID(), UUID.randomUUID())

        // Budget comfortably fits engine+persona+profile+memory+history on their own,
        // but a very large current message must now force the recent history to be
        // trimmed to keep the real total under budget — proving the current message
        // is no longer excluded from the budget calculation.
        val hugeCurrentMessage = "x".repeat(2000)
        val request = ChatRequest(UUID.randomUUID(), userId, conversation.id, persona.id, UUID.randomUUID(), hugeCurrentMessage)
        val context = assertIs<StageResult.Succeeded<ChatContext>>(
            assembler(conversationRepository, messageRepository, db, engineRepository, personaRepository, tokenBudget = 520).assemble(request),
        ).value

        assertTrue(context.blocks.none { it.content == "a short recent message" }, "History must be trimmed once the current message's own size is counted against the budget")
        assertEquals(hugeCurrentMessage, context.blocks.last().content, "The current message itself must never be dropped, only contribute to trimming other content")
        assertEquals("user", context.blocks.last().role)
    }
}
