package com.pinkdreams.chat

import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.chat.memory.MemoryCandidate
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.chat.sensitive.SensitivePreferenceService
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SensitivePreferenceRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase 4B: completed user-profile projection + sensitive/intimacy preference
 * architecture, verified at the assembled ChatContext boundary (the same
 * boundary Phase 4A audited). Builds directly on the fixtures/patterns already
 * established in ChatContextContractRegressionTest and
 * ContinuitySummarizationContextIntegrationTest.
 */
class Phase4BSensitiveProfileContextTest {

    private class Fixture(
        val db: org.jetbrains.exposed.sql.Database,
        val userId: UUID,
        val personaId: UUID,
        val conversationId: UUID,
        val conversationRepository: ConversationRepository,
        val messageRepository: MessageRepository,
        val sensitivePreferenceService: SensitivePreferenceService,
    )

    private fun fixture(): Fixture {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val userId = UUID.randomUUID()

        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("sensitive-ctx-${UUID.randomUUID()}", "Test", "female", "straight", 30, emptyMap())
        val coreRepository = PersonaCoreVersionRepository(db)
        val core = coreRepository.create(persona.id, 1, "core", "draft")
        personaRepository.activateCoreVersion(persona.id, coreRepository.publishCoreVersion(core.id).id)

        val engineRepository = ConversationEngineRepository(db)
        val engine = engineRepository.create(1, "engine", "draft")
        engineRepository.activateEngine(engineRepository.publishEngine(engine.id).id)

        val conversationRepository = ConversationRepository(db)
        val conversation = conversationRepository.create(userId, persona.id)

        val sensitivePreferenceService = SensitivePreferenceService(SensitivePreferenceRepository(db))

        return Fixture(db, userId, persona.id, conversation.id, conversationRepository, MessageRepository(db), sensitivePreferenceService)
    }

    private fun assembler(f: Fixture, memoryService: MemoryService = MemoryService(MemoryFactRepository(f.db))) = RepositoryContextAssembler(
        f.conversationRepository,
        f.messageRepository,
        UserProfileRepository(f.db),
        memoryService,
        ConversationEngineRepository(f.db),
        PersonaRepository(f.db),
        sensitivePreferenceService = f.sensitivePreferenceService,
    )

    // --- Profile completion: all 4 previously-missing fields now reach context ---
    @Test
    fun `all user profile fields reach the assembled context with distinctive markers`() {
        val f = fixture()
        UserProfileRepository(f.db).create(
            userId = f.userId,
            displayName = "PROFILE_NAME_MARKER",
            preferredLanguage = "en_US",
            communicationStyle = "warm",
            gender = "PROFILE_GENDER_MARKER",
            interest = "female",
            city = "PROFILE_CITY_MARKER",
            age = 29,
        )

        val request = ChatRequest(UUID.randomUUID(), f.userId, f.conversationId, f.personaId, UUID.randomUUID(), "hello")
        val context = assertIs<StageResult.Succeeded<ChatContext>>(assembler(f).assemble(request)).value
        val profileBlock = context.blocks[1]

        assertTrue(profileBlock.content.contains("PROFILE_NAME_MARKER"))
        assertTrue(profileBlock.content.contains("PROFILE_GENDER_MARKER"))
        assertTrue(profileBlock.content.contains("PROFILE_CITY_MARKER"))
        assertTrue(profileBlock.content.contains("interest: female"))
        assertTrue(profileBlock.content.contains("age: 29"))
    }

    @Test
    fun `missing profile fields are explicitly marked not set never fabricated or asserted negative`() {
        val f = fixture()
        // Only displayName set — every other field left null.
        UserProfileRepository(f.db).create(userId = f.userId, displayName = "PARTIAL_PROFILE")

        val request = ChatRequest(UUID.randomUUID(), f.userId, f.conversationId, f.personaId, UUID.randomUUID(), "hello")
        val context = assertIs<StageResult.Succeeded<ChatContext>>(assembler(f).assemble(request)).value
        val profileBlock = context.blocks[1]

        assertTrue(profileBlock.content.contains("age: (not set)"))
        assertTrue(profileBlock.content.contains("gender: (not set)"))
        assertTrue(profileBlock.content.contains("city: (not set)"))
        assertTrue(profileBlock.content.contains("interest: (not set)"))
        assertFalse(profileBlock.content.contains("no age"), "Absence must never be phrased as a negative assertion")
    }

    // --- Sensitive preferences: retrieved only when the current message is relevant ---
    @Test
    fun `sensitive preferences are injected when the current message is relevant`() {
        val f = fixture()
        f.sensitivePreferenceService.recordExplicit(f.userId, f.personaId, "romantic", "preference", "SENSITIVE_PREFERENCE_MARKER")

        val request = ChatRequest(UUID.randomUUID(), f.userId, f.conversationId, f.personaId, UUID.randomUUID(), "Can we talk about romance and dating?")
        val context = assertIs<StageResult.Succeeded<ChatContext>>(assembler(f).assemble(request)).value

        assertTrue(context.blocks.any { it.content.contains("SENSITIVE_PREFERENCE_MARKER") }, "Relevant sensitive preference must reach the context")
        val sensitiveBlockIndex = context.blocks.indexOfFirst { it.content.startsWith("SENSITIVE USER PREFERENCES") }
        assertTrue(sensitiveBlockIndex >= 0)
        assertEquals("system", context.blocks[sensitiveBlockIndex].role)
    }

    @Test
    fun `sensitive preferences are NOT injected into an unrelated ordinary conversation`() {
        val f = fixture()
        f.sensitivePreferenceService.recordExplicit(f.userId, f.personaId, "romantic", "preference", "SENSITIVE_PREFERENCE_MARKER")

        val request = ChatRequest(UUID.randomUUID(), f.userId, f.conversationId, f.personaId, UUID.randomUUID(), "What's a good recipe for dinner tonight?")
        val context = assertIs<StageResult.Succeeded<ChatContext>>(assembler(f).assemble(request)).value

        assertFalse(context.blocks.any { it.content.contains("SENSITIVE_PREFERENCE_MARKER") }, "Sensitive preference must not leak into an unrelated topic")
    }

    @Test
    fun `sensitive preferences are absent when no service is wired in (backward compatibility)`() {
        val f = fixture()
        val request = ChatRequest(UUID.randomUUID(), f.userId, f.conversationId, f.personaId, UUID.randomUUID(), "Let's talk about romance")
        val noServiceAssembler = RepositoryContextAssembler(
            f.conversationRepository,
            f.messageRepository,
            UserProfileRepository(f.db),
            MemoryService(MemoryFactRepository(f.db)),
            ConversationEngineRepository(f.db),
            PersonaRepository(f.db),
        )
        val context = assertIs<StageResult.Succeeded<ChatContext>>(noServiceAssembler.assemble(request)).value
        assertFalse(context.blocks.any { it.content.startsWith("SENSITIVE USER PREFERENCES") })
    }

    // --- Ordinary memory extraction never creates a sensitive-preference row ---
    @Test
    fun `ordinary memory facts remain fully decoupled from sensitive preference storage`() {
        val f = fixture()
        val memoryService = MemoryService(MemoryFactRepository(f.db))
        memoryService.record(f.userId, f.personaId, listOf(MemoryCandidate("user mentioned liking spicy food", "interest", "medium")))

        assertTrue(f.sensitivePreferenceService.selectForContext(f.userId, f.personaId).isEmpty(), "Recording an ordinary memory fact must never create a sensitive preference row")
    }

    // --- Full context integration: every marker lands in the correct logical section ---
    @Test
    fun `full context integration places every marker in its correct logical section`() {
        val f = fixture()
        UserProfileRepository(f.db).create(f.userId, displayName = "PROFILE_MARKER")
        val memoryService = MemoryService(MemoryFactRepository(f.db))
        memoryService.record(f.userId, f.personaId, listOf(MemoryCandidate("MEMORY_MARKER", "interest", "high")))
        f.sensitivePreferenceService.recordExplicit(f.userId, f.personaId, "intimacy", "preference", "SENSITIVE_PREFERENCE_MARKER")
        f.conversationRepository.updateContinuitySummary(f.conversationId, "SUMMARY_MARKER", 2)

        val base = LocalDateTime.of(2026, 1, 1, 0, 0)
        (1..10).forEach { i ->
            f.messageRepository.createUserMessage(f.conversationId, "RECENT_%02d".format(i), UUID.randomUUID(), UUID.randomUUID(), createdAt = base.plusMinutes(i.toLong()))
        }

        val request = ChatRequest(UUID.randomUUID(), f.userId, f.conversationId, f.personaId, UUID.randomUUID(), "I want to talk about intimacy and romance — CURRENT_MESSAGE_MARKER")
        val context = assertIs<StageResult.Succeeded<ChatContext>>(assembler(f, memoryService).assemble(request)).value

        val engineBlock = context.blocks[0]
        val profileBlock = context.blocks[1]
        val memoryBlock = context.blocks[2]
        val sensitiveBlock = context.blocks[3]
        val continuityBlock = context.blocks[4]
        val historyBlocks = context.blocks.subList(5, 15)
        val currentBlock = context.blocks.last()

        assertEquals("system", engineBlock.role)
        assertEquals("system", profileBlock.role)
        assertTrue(profileBlock.content.contains("PROFILE_MARKER"))
        assertEquals("system", memoryBlock.role)
        assertTrue(memoryBlock.content.contains("MEMORY_MARKER"))
        assertEquals("system", sensitiveBlock.role)
        assertTrue(sensitiveBlock.content.contains("SENSITIVE_PREFERENCE_MARKER"))
        assertEquals("system", continuityBlock.role)
        assertTrue(continuityBlock.content.contains("SUMMARY_MARKER"))
        historyBlocks.forEachIndexed { index, block ->
            assertEquals("RECENT_%02d".format(index + 1), block.content)
            assertEquals("user", block.role)
        }
        assertEquals("user", currentBlock.role)
        assertTrue(currentBlock.content.contains("CURRENT_MESSAGE_MARKER"))
        assertEquals(16, context.blocks.size, "3 system + sensitive + continuity + 10 history + current")
    }

    // --- Budget: the sensitive block participates in the same accounting as everything else ---
    @Test
    fun `sensitive preference block is trimmed as a whole unit under budget pressure`() {
        val f = fixture()
        f.sensitivePreferenceService.recordExplicit(f.userId, f.personaId, "romantic", "preference", "x".repeat(300))
        f.messageRepository.createUserMessage(f.conversationId, "recent message", UUID.randomUUID(), UUID.randomUUID())

        val tightAssembler = RepositoryContextAssembler(
            f.conversationRepository,
            f.messageRepository,
            UserProfileRepository(f.db),
            MemoryService(MemoryFactRepository(f.db)),
            ConversationEngineRepository(f.db),
            PersonaRepository(f.db),
            tokenBudget = 120,
            sensitivePreferenceService = f.sensitivePreferenceService,
        )
        val request = ChatRequest(UUID.randomUUID(), f.userId, f.conversationId, f.personaId, UUID.randomUUID(), "let's talk about romance")
        val context = assertIs<StageResult.Succeeded<ChatContext>>(tightAssembler.assemble(request)).value

        assertFalse(context.blocks.any { it.content.startsWith("SENSITIVE USER PREFERENCES") }, "Sensitive block must be droppable under budget pressure")
        assertTrue(context.blocks.any { it.content == "recent message" }, "Native history should survive once the (larger) sensitive block alone is dropped")
    }
}
