package com.pinkdreams.persistence

import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ChatRequestExecutionRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Phase4ConversationMessageIdempotencyTest {
    @Test
    fun `conversation is persisted and ownership lookup is scoped to user`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val repo = ConversationRepository(db)
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        val conversation = repo.create(userA, UUID.randomUUID())

        assertNotNull(repo.findById(conversation.id))
        assertNotNull(repo.findByIdForUser(conversation.id, userA))
        assertNull(repo.findByIdForUser(conversation.id, userB))
        assertNull(repo.findById(UUID.randomUUID()))
    }

    @Test
    fun `messages preserve roles provenance request IDs and conversation ordering`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val conversation = ConversationRepository(db).create(UUID.randomUUID(), UUID.randomUUID())
        val repo = MessageRepository(db)
        val clientMessageId = UUID.randomUUID()
        val requestId = UUID.randomUUID()
        val engineId = UUID.randomUUID()
        val coreId = UUID.randomUUID()
        val firstAt = LocalDateTime.of(2026, 1, 1, 10, 0)
        val secondAt = firstAt.plusMinutes(1)

        val user = repo.createUserMessage(
            conversation.id,
            "Hello",
            clientMessageId,
            requestId,
            createdAt = firstAt,
        )
        val assistant = repo.createAssistantMessage(
            conversation.id,
            "Hi",
            engineId,
            coreId,
            requestId,
            createdAt = secondAt,
        )

        assertEquals("user", user.role)
        assertEquals(clientMessageId, user.clientMessageId)
        assertEquals(requestId, user.requestId)
        assertEquals("assistant", assistant.role)
        assertEquals(engineId, assistant.engineVersionId)
        assertEquals(coreId, assistant.personaCoreVersionId)
        assertEquals(requestId, assistant.requestId)
        assertEquals(listOf(user.id, assistant.id), repo.findForConversation(conversation.id).map { it.id })
    }

    @Test
    fun `system messages do not carry client or assistant provenance`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val conversation = ConversationRepository(db).create(UUID.randomUUID(), UUID.randomUUID())
        val repo = MessageRepository(db)

        val system = repo.createSystemMessage(conversation.id, "system", requestId = UUID.randomUUID())
        assertEquals("system", system.role)
        assertNull(system.clientMessageId)
        assertNull(system.engineVersionId)
        assertNull(system.personaCoreVersionId)
    }

    @Test
    fun `first execution claim wins and duplicate completed execution is reused`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val conversation = ConversationRepository(db).create(UUID.randomUUID(), UUID.randomUUID())
        val repo = ChatRequestExecutionRepository(db)
        val clientMessageId = UUID.randomUUID()
        val first = repo.claim(conversation.id, clientMessageId, UUID.randomUUID())
        val second = repo.claim(conversation.id, clientMessageId, UUID.randomUUID())

        assertTrue(first.winner)
        assertFalse(second.winner)
        assertEquals("processing", second.execution.status)
        assertEquals(1L, repo.count())

        val assistantMessageId = UUID.randomUUID()
        val completed = repo.complete(conversation.id, clientMessageId, assistantMessageId)
        val duplicate = repo.claim(conversation.id, clientMessageId, UUID.randomUUID())
        assertEquals("completed", completed.status)
        assertEquals(assistantMessageId, duplicate.execution.assistantMessageId)
        assertFalse(duplicate.winner)
        assertEquals(1L, repo.count())
    }

    @Test
    fun `processing duplicate is recognized without creating another execution`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val conversation = ConversationRepository(db).create(UUID.randomUUID(), UUID.randomUUID())
        val repo = ChatRequestExecutionRepository(db)
        val clientMessageId = UUID.randomUUID()

        assertTrue(repo.claim(conversation.id, clientMessageId, UUID.randomUUID()).winner)
        val duplicate = repo.claim(conversation.id, clientMessageId, UUID.randomUUID())

        assertFalse(duplicate.winner)
        assertEquals("processing", duplicate.execution.status)
        assertEquals(1L, repo.count())
    }

    @Test
    fun `failed execution preserves error and can be retried as the new winner`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val conversation = ConversationRepository(db).create(UUID.randomUUID(), UUID.randomUUID())
        val repo = ChatRequestExecutionRepository(db)
        val clientMessageId = UUID.randomUUID()

        repo.claim(conversation.id, clientMessageId, UUID.randomUUID())
        val failed = repo.fail(conversation.id, clientMessageId, "GENERATION_FAILED")
        assertEquals("failed", failed.status)
        assertEquals("GENERATION_FAILED", failed.errorCode)
        assertNotNull(failed.completedAt)

        val retry = repo.claim(conversation.id, clientMessageId, UUID.randomUUID())
        assertTrue(retry.winner)
        assertEquals("processing", retry.execution.status)
        assertNull(retry.execution.errorCode)
        assertNull(retry.execution.completedAt)
        assertEquals(1L, repo.count())
    }

    @Test
    fun `idempotency is scoped to conversation and different keys create separate executions`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val conversationRepo = ConversationRepository(db)
        val executionRepo = ChatRequestExecutionRepository(db)
        val userId = UUID.randomUUID()
        val conversationA = conversationRepo.create(userId, UUID.randomUUID())
        val conversationB = conversationRepo.create(userId, UUID.randomUUID())
        val clientMessageId = UUID.randomUUID()

        assertTrue(executionRepo.claim(conversationA.id, clientMessageId, UUID.randomUUID()).winner)
        assertTrue(executionRepo.claim(conversationB.id, clientMessageId, UUID.randomUUID()).winner)
        assertTrue(executionRepo.claim(conversationA.id, UUID.randomUUID(), UUID.randomUUID()).winner)
        assertEquals(3L, executionRepo.count())
    }
}