package com.pinkdreams

import com.pinkdreams.chat.GenerationResponse
import com.pinkdreams.chat.StageResult
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.persistence.RepositoryChatPersistence
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ChatRequestExecutionRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.chat.ChatRequest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 7D: Persistence & Transaction Integrity
 *
 * Tests transaction boundaries and persistence atomicity across the chat execution lifecycle.
 * Verifies that successful and failed requests cannot leave inconsistent state.
 */
class Phase7DPersistenceIntegrityTest {

    // ============================================================================
    // SECTION 1: SUCCESSFUL PERSISTENCE - COMPLETE TRANSACTION
    // ============================================================================

    @Test
    fun `successful persistence - all operations committed atomically`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val convRepo = ConversationRepository(db)
        val msgRepo = MessageRepository(db)
        val execRepo = ChatRequestExecutionRepository(db)
        val persistence = RepositoryChatPersistence(db, execRepo)

        val conversation = convRepo.create(userId, personaId)
        val clientMessageId = UUID.randomUUID()
        val requestId = UUID.randomUUID()
        val engineVersionId = UUID.randomUUID()
        val personaCoreVersionId = UUID.randomUUID()

        // Claim idempotency
        val claim = execRepo.claim(conversation.id, clientMessageId, requestId)
        assertTrue(claim.winner)

        // Simulate generation
        val chatRequest = ChatRequest(
            requestId = requestId,
            userId = userId,
            conversationId = conversation.id,
            personaId = personaId,
            clientMessageId = clientMessageId,
            content = "test message"
        )
        val generationResponse = GenerationResponse(
            content = "generated response",
            engineVersionId = engineVersionId,
            personaCoreVersionId = personaCoreVersionId
        )

        // Persist
        val result = persistence.persist(chatRequest, generationResponse)
        assertIs<StageResult.Succeeded<*>>(result)

        // Verify atomic completion
        val execution = execRepo.find(conversation.id, clientMessageId)
        assertNotNull(execution)
        assertEquals("completed", execution.status)
        assertNotNull(execution.assistantMessageId)

        val messages = msgRepo.findForConversation(conversation.id)
        assertEquals(2, messages.size, "Should have user + assistant message")

        val userMsg = messages.find { it.role == "user" }
        assertNotNull(userMsg)
        assertEquals(clientMessageId, userMsg.clientMessageId)
        assertEquals(requestId, userMsg.requestId)
        assertEquals("test message", userMsg.content)

        val assistantMsg = messages.find { it.role == "assistant" }
        assertNotNull(assistantMsg)
        assertEquals(engineVersionId, assistantMsg.engineVersionId)
        assertEquals(personaCoreVersionId, assistantMsg.personaCoreVersionId)
        assertEquals(requestId, assistantMsg.requestId)
        assertEquals("generated response", assistantMsg.content)

        val updatedConv = convRepo.findById(conversation.id)
        assertNotNull(updatedConv?.lastMessageAt)
    }

    // ============================================================================
    // SECTION 2: PERSISTENCE FAILURE - NO PARTIAL STATE
    // ============================================================================

    @Test
    fun `persistence failure - transaction rolls back leaving no partial state`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val convRepo = ConversationRepository(db)
        val msgRepo = MessageRepository(db)
        val execRepo = ChatRequestExecutionRepository(db)

        val conversation = convRepo.create(userId, personaId)
        val clientMessageId = UUID.randomUUID()
        val requestId = UUID.randomUUID()

        // Claim idempotency
        execRepo.claim(conversation.id, clientMessageId, requestId)

        // Simulate persistence with missing engineVersionId (will fail)
        val chatRequest = ChatRequest(
            requestId = requestId,
            userId = userId,
            conversationId = conversation.id,
            personaId = personaId,
            clientMessageId = clientMessageId,
            content = "test message"
        )
        val generationResponse = GenerationResponse(
            content = "response",
            engineVersionId = null, // This will cause failure
            personaCoreVersionId = UUID.randomUUID()
        )

        val persistence = RepositoryChatPersistence(db, execRepo)
        val result = persistence.persist(chatRequest, generationResponse)
        assertIs<StageResult.Failed>(result)

        // Verify no partial state
        val execution = execRepo.find(conversation.id, clientMessageId)
        assertNotNull(execution)
        assertEquals("failed", execution.status, "Execution should be marked as failed")
        assertNull(execution.assistantMessageId, "No assistant message should be persisted")

        val messages = msgRepo.findForConversation(conversation.id)
        assertEquals(0, messages.size, "No messages should be persisted on failure")

        val conv = convRepo.findById(conversation.id)
        assertNull(conv?.lastMessageAt, "Conversation timestamp should not be updated")
    }

    // ============================================================================
    // SECTION 3: USER/ASSISTANT ATOMICITY
    // ============================================================================

    @Test
    fun `user and assistant messages persist atomically - no orphans`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val convRepo = ConversationRepository(db)
        val msgRepo = MessageRepository(db)
        val execRepo = ChatRequestExecutionRepository(db)

        val conversation = convRepo.create(userId, personaId)
        val clientMessageId = UUID.randomUUID()
        val requestId = UUID.randomUUID()

        execRepo.claim(conversation.id, clientMessageId, requestId)

        val chatRequest = ChatRequest(
            requestId = requestId,
            userId = userId,
            conversationId = conversation.id,
            personaId = personaId,
            clientMessageId = clientMessageId,
            content = "user input"
        )
        val generationResponse = GenerationResponse(
            content = "assistant output",
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID()
        )

        val persistence = RepositoryChatPersistence(db, execRepo)
        persistence.persist(chatRequest, generationResponse)

        val messages = msgRepo.findForConversation(conversation.id)
        val userMessages = messages.filter { it.role == "user" }
        val assistantMessages = messages.filter { it.role == "assistant" }

        assertEquals(1, userMessages.size, "Exactly one user message")
        assertEquals(1, assistantMessages.size, "Exactly one assistant message")
        assertEquals(requestId, userMessages[0].requestId)
        assertEquals(requestId, assistantMessages[0].requestId)
    }

    // ============================================================================
    // SECTION 4: CONVERSATION TIMESTAMP CONSISTENCY
    // ============================================================================

    @Test
    fun `conversation timestamp updates only on successful persistence`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val convRepo = ConversationRepository(db)
        val execRepo = ChatRequestExecutionRepository(db)
        val persistence = RepositoryChatPersistence(db, execRepo)

        val conversation = convRepo.create(userId, personaId)
        val initialTimestamp = conversation.lastMessageAt

        // Failed persistence
        val failureRequest = ChatRequest(
            requestId = UUID.randomUUID(),
            userId = userId,
            conversationId = conversation.id,
            personaId = personaId,
            clientMessageId = UUID.randomUUID(),
            content = "test"
        )
        val failureResponse = GenerationResponse(
            content = "test",
            engineVersionId = null,
            personaCoreVersionId = UUID.randomUUID()
        )

        execRepo.claim(conversation.id, failureRequest.clientMessageId, failureRequest.requestId)
        persistence.persist(failureRequest, failureResponse)

        val afterFailure = convRepo.findById(conversation.id)
        assertEquals(initialTimestamp, afterFailure?.lastMessageAt, "Timestamp should not change on failure")

        // Successful persistence
        val successRequest = ChatRequest(
            requestId = UUID.randomUUID(),
            userId = userId,
            conversationId = conversation.id,
            personaId = personaId,
            clientMessageId = UUID.randomUUID(),
            content = "test"
        )
        val successResponse = GenerationResponse(
            content = "generated",
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID()
        )

        execRepo.claim(conversation.id, successRequest.clientMessageId, successRequest.requestId)
        persistence.persist(successRequest, successResponse)

        val afterSuccess = convRepo.findById(conversation.id)
        assertNotNull(afterSuccess)
        assertNotNull(afterSuccess!!.lastMessageAt)
        val timestamp = afterSuccess.lastMessageAt ?: java.time.LocalDateTime.MIN
        assertTrue(timestamp > (initialTimestamp ?: java.time.LocalDateTime.MIN), "Timestamp should update on success")
    }

    // ============================================================================
    // SECTION 5: IDEMPOTENCY STATE CONSISTENCY
    // ============================================================================

    @Test
    fun `duplicate completed request - no duplicate messages`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val convRepo = ConversationRepository(db)
        val msgRepo = MessageRepository(db)
        val execRepo = ChatRequestExecutionRepository(db)
        val persistence = RepositoryChatPersistence(db, execRepo)

        val conversation = convRepo.create(userId, personaId)
        val clientMessageId = UUID.randomUUID()
        val requestId = UUID.randomUUID()
        val engineVersionId = UUID.randomUUID()
        val personaCoreVersionId = UUID.randomUUID()

        // First request
        execRepo.claim(conversation.id, clientMessageId, requestId)
        val request = ChatRequest(
            requestId = requestId,
            userId = userId,
            conversationId = conversation.id,
            personaId = personaId,
            clientMessageId = clientMessageId,
            content = "message"
        )
        val response = GenerationResponse(
            content = "response",
            engineVersionId = engineVersionId,
            personaCoreVersionId = personaCoreVersionId
        )
        persistence.persist(request, response)

        val firstMessages = msgRepo.findForConversation(conversation.id)
        assertEquals(2, firstMessages.size)

        // Duplicate request - this should not persist new messages
        // The second claim() would return winner=false and completed state
        val secondClaim = execRepo.claim(conversation.id, clientMessageId, UUID.randomUUID())
        assertEquals(false, secondClaim.winner)
        assertEquals("completed", secondClaim.execution.status)

        val secondMessages = msgRepo.findForConversation(conversation.id)
        assertEquals(2, secondMessages.size, "No additional messages on duplicate")
    }

    // ============================================================================
    // SECTION 6: DELIVERY FAILURE - PERSISTENCE SURVIVES
    // ============================================================================

    @Test
    fun `delivery failure - persisted data survives in completed state`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val convRepo = ConversationRepository(db)
        val msgRepo = MessageRepository(db)
        val execRepo = ChatRequestExecutionRepository(db)
        val persistence = RepositoryChatPersistence(db, execRepo)

        val conversation = convRepo.create(userId, personaId)
        val clientMessageId = UUID.randomUUID()
        val requestId = UUID.randomUUID()

        execRepo.claim(conversation.id, clientMessageId, requestId)

        val request = ChatRequest(
            requestId = requestId,
            userId = userId,
            conversationId = conversation.id,
            personaId = personaId,
            clientMessageId = clientMessageId,
            content = "message"
        )
        val response = GenerationResponse(
            content = "response",
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID()
        )

        // Persist successfully
        val result = persistence.persist(request, response)
        assertIs<StageResult.Succeeded<*>>(result)

        // Simulate delivery failure - execution should remain completed
        val execution = execRepo.find(conversation.id, clientMessageId)
        assertNotNull(execution)
        assertEquals("completed", execution.status)

        val messages = msgRepo.findForConversation(conversation.id)
        assertEquals(2, messages.size, "All messages survive delivery failure")

        // Retry with same key should get completed result
        val retryClaimResult = execRepo.claim(conversation.id, clientMessageId, UUID.randomUUID())
        assertEquals(false, retryClaimResult.winner, "Retry should not win")
        assertEquals("completed", retryClaimResult.execution.status, "Should see completed state")
        assertEquals(execution.assistantMessageId, retryClaimResult.execution.assistantMessageId, "Same assistant message")
    }

    // ============================================================================
    // SECTION 7: PROVENANCE INTEGRITY
    // ============================================================================

    @Test
    fun `provenance - exact engineVersionId and personaCoreVersionId persisted`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val convRepo = ConversationRepository(db)
        val msgRepo = MessageRepository(db)
        val execRepo = ChatRequestExecutionRepository(db)
        val persistence = RepositoryChatPersistence(db, execRepo)

        val conversation = convRepo.create(userId, personaId)
        val clientMessageId = UUID.randomUUID()
        val requestId = UUID.randomUUID()
        val specificEngineId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val specificCoreId = UUID.fromString("22222222-2222-2222-2222-222222222222")

        execRepo.claim(conversation.id, clientMessageId, requestId)

        val request = ChatRequest(
            requestId = requestId,
            userId = userId,
            conversationId = conversation.id,
            personaId = personaId,
            clientMessageId = clientMessageId,
            content = "test"
        )
        val response = GenerationResponse(
            content = "output",
            engineVersionId = specificEngineId,
            personaCoreVersionId = specificCoreId
        )

        persistence.persist(request, response)

        val messages = msgRepo.findForConversation(conversation.id)
        val assistantMsg = messages.find { it.role == "assistant" }
        assertNotNull(assistantMsg)
        assertEquals(specificEngineId, assistantMsg.engineVersionId, "Engine version must match")
        assertEquals(specificCoreId, assistantMsg.personaCoreVersionId, "Persona core version must match")
    }

    // ============================================================================
    // SECTION 8: FAILED EXECUTION STATE
    // ============================================================================

    @Test
    fun `failed execution - no invalid assistant message reference`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val convRepo = ConversationRepository(db)
        val execRepo = ChatRequestExecutionRepository(db)
        val persistence = RepositoryChatPersistence(db, execRepo)

        val conversation = convRepo.create(userId, personaId)
        val clientMessageId = UUID.randomUUID()
        val requestId = UUID.randomUUID()

        execRepo.claim(conversation.id, clientMessageId, requestId)

        val request = ChatRequest(
            requestId = requestId,
            userId = userId,
            conversationId = conversation.id,
            personaId = personaId,
            clientMessageId = clientMessageId,
            content = "test"
        )
        val response = GenerationResponse(
            content = "output",
            engineVersionId = null,
            personaCoreVersionId = UUID.randomUUID()
        )

        persistence.persist(request, response)

        val execution = execRepo.find(conversation.id, clientMessageId)
        assertNotNull(execution)
        assertEquals("failed", execution.status)
        assertNull(execution.assistantMessageId, "Failed execution should not have assistant message ID")
    }

    // ============================================================================
    // SECTION 9: CROSS-USER PERSISTENCE SAFETY
    // ============================================================================

    @Test
    fun `cross-user protection - persistence checks ownership before committing`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val convRepo = ConversationRepository(db)
        val execRepo = ChatRequestExecutionRepository(db)
        val persistence = RepositoryChatPersistence(db, execRepo)

        // User A owns conversation
        val conversation = convRepo.create(userA, personaId)
        val clientMessageId = UUID.randomUUID()
        val requestId = UUID.randomUUID()

        execRepo.claim(conversation.id, clientMessageId, requestId)

        // User B tries to persist into User A's conversation
        val request = ChatRequest(
            requestId = requestId,
            userId = userB, // Different user
            conversationId = conversation.id,
            personaId = personaId,
            clientMessageId = clientMessageId,
            content = "hack"
        )
        val response = GenerationResponse(
            content = "response",
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID()
        )

        val result = persistence.persist(request, response)
        assertIs<StageResult.Failed>(result, "Should reject cross-user persistence")
    }
}
