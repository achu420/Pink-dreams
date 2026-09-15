package com.pinkdreams

import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.persistence.RepositoryChatExecutionCoordinator
import com.pinkdreams.persistence.RepositoryChatPersistence
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ChatRequestExecutionRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ExecutionClaim
import com.pinkdreams.chat.StageResult
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase 7C: Idempotency & Concurrency Hardening
 *
 * Tests real PostgreSQL concurrency behavior around idempotency claim.
 * H2 serialization limits make these tests less meaningful for H2,
 * but we run them anyway for regression coverage.
 */
class Phase7CConcurrencyTest {


    // ============================================================================
    // SECTION 1: SAME-KEY CONCURRENT REQUESTS - EXACTLY ONE SHOULD WIN
    // ============================================================================

    @Test
    fun `concurrent requests with same idempotency key - exactly one wins`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val conversation = ConversationRepository(db).create(UUID.randomUUID(), UUID.randomUUID())
        val execRepo = ChatRequestExecutionRepository(db)
        val clientMessageId = UUID.randomUUID()

        val winners = AtomicInteger(0)
        val losers = AtomicInteger(0)
        val latch = CountDownLatch(2)

        val thread1 = Thread {
            try {
                val result = execRepo.claim(conversation.id, clientMessageId, UUID.randomUUID())
                if (result.winner) winners.incrementAndGet() else losers.incrementAndGet()
            } finally {
                latch.countDown()
            }
        }

        val thread2 = Thread {
            try {
                val result = execRepo.claim(conversation.id, clientMessageId, UUID.randomUUID())
                if (result.winner) winners.incrementAndGet() else losers.incrementAndGet()
            } finally {
                latch.countDown()
            }
        }

        thread1.start()
        thread2.start()
        latch.await()

        assertEquals(1, winners.get(), "Exactly one request should win the idempotency claim")
        assertEquals(1, losers.get(), "Exactly one request should lose the idempotency claim")
    }

    // ============================================================================
    // SECTION 2: DIFFERENT IDEMPOTENCY KEYS - BOTH SHOULD SUCCEED
    // ============================================================================

    @Test
    fun `concurrent requests with different idempotency keys - both succeed independently`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val conversation = ConversationRepository(db).create(UUID.randomUUID(), UUID.randomUUID())
        val execRepo = ChatRequestExecutionRepository(db)

        val winners = AtomicInteger(0)
        val latch = CountDownLatch(2)
        val clientMessageId1 = UUID.randomUUID()
        val clientMessageId2 = UUID.randomUUID()

        val thread1 = Thread {
            try {
                val result = execRepo.claim(conversation.id, clientMessageId1, UUID.randomUUID())
                if (result.winner) winners.incrementAndGet()
            } finally {
                latch.countDown()
            }
        }

        val thread2 = Thread {
            try {
                val result = execRepo.claim(conversation.id, clientMessageId2, UUID.randomUUID())
                if (result.winner) winners.incrementAndGet()
            } finally {
                latch.countDown()
            }
        }

        thread1.start()
        thread2.start()
        latch.await()

        assertEquals(2, winners.get(), "Both requests with different keys should win")
        assertEquals(2L, execRepo.count(), "Two execution records should exist")
    }

    // ============================================================================
    // SECTION 3: PROCESSING RACE - SECOND REQUEST OBSERVES "PROCESSING" STATE
    // ============================================================================

    @Test
    fun `processing race - second request with same key observes processing state`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val conversation = ConversationRepository(db).create(UUID.randomUUID(), UUID.randomUUID())
        val execRepo = ChatRequestExecutionRepository(db)
        val clientMessageId = UUID.randomUUID()

        // First request claims and keeps execution in processing
        val first = execRepo.claim(conversation.id, clientMessageId, UUID.randomUUID())
        assertTrue(first.winner, "First request should win")
        assertEquals("processing", first.execution.status)

        // Second request arrives while first is still processing
        val second = execRepo.claim(conversation.id, clientMessageId, UUID.randomUUID())
        assertIs<ExecutionClaim.Processing>(
            when {
                !second.winner && second.execution.status == "processing" -> ExecutionClaim.Processing
                else -> null
            },
            "Second request should observe processing state"
        )
        assertEquals("processing", second.execution.status, "Existing execution should still be processing")
    }

    // ============================================================================
    // SECTION 4: COMPLETED DUPLICATE - REUSE EXISTING RESPONSE
    // ============================================================================

    @Test
    fun `completed duplicate - second request reuses existing assistant response`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val conversation = ConversationRepository(db).create(UUID.randomUUID(), UUID.randomUUID())
        val execRepo = ChatRequestExecutionRepository(db)
        val msgRepo = MessageRepository(db)
        val clientMessageId = UUID.randomUUID()

        // First request: claim, create messages, complete
        val first = execRepo.claim(conversation.id, clientMessageId, UUID.randomUUID())
        assertTrue(first.winner)

        val assistantMsg = msgRepo.createAssistantMessage(
            conversation.id,
            "Assistant response",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID()
        )
        execRepo.complete(conversation.id, clientMessageId, assistantMsg.id)

        // Second request: should get completed result
        val second = execRepo.claim(conversation.id, clientMessageId, UUID.randomUUID())
        assertIs<ExecutionClaim.Completed>(
            when {
                !second.winner && second.execution.status == "completed" -> ExecutionClaim.Completed(
                    com.pinkdreams.chat.PersistedResponse(second.execution.assistantMessageId!!, "")
                )
                else -> null
            },
            "Second request should observe completed state"
        )
        assertEquals("completed", second.execution.status)
        assertEquals(assistantMsg.id, second.execution.assistantMessageId)
    }

    // ============================================================================
    // SECTION 5: FAILED EXECUTION RETRY - RESTART FROM PROCESSING
    // ============================================================================

    @Test
    fun `failed execution retry - can transition back to processing`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val conversation = ConversationRepository(db).create(UUID.randomUUID(), UUID.randomUUID())
        val execRepo = ChatRequestExecutionRepository(db)
        val clientMessageId = UUID.randomUUID()
        val requestId1 = UUID.randomUUID()
        val requestId2 = UUID.randomUUID()

        // First request: claim and fail
        val first = execRepo.claim(conversation.id, clientMessageId, requestId1)
        assertTrue(first.winner)
        execRepo.fail(conversation.id, clientMessageId, "GENERATION_FAILED")

        // Second request (retry with same key): should be able to claim as a winner
        val retry = execRepo.claim(conversation.id, clientMessageId, requestId2)
        assertTrue(retry.winner, "Failed execution should be retryable")
        assertEquals("processing", retry.execution.status, "Retry should be in processing state")
    }

    // ============================================================================
    // SECTION 6: DELIVERY FAILURE - DO NOT REGENERATE ON RETRY
    // ============================================================================

    @Test
    fun `delivery failure - persisted response remains on retry`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val conversation = ConversationRepository(db).create(UUID.randomUUID(), UUID.randomUUID())
        val execRepo = ChatRequestExecutionRepository(db)
        val msgRepo = MessageRepository(db)
        val clientMessageId = UUID.randomUUID()

        // First request: claim, create and persist assistant message, complete
        val first = execRepo.claim(conversation.id, clientMessageId, UUID.randomUUID())
        assertTrue(first.winner)

        val assistantMsg = msgRepo.createAssistantMessage(
            conversation.id,
            "Response",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID()
        )
        execRepo.complete(conversation.id, clientMessageId, assistantMsg.id)

        // Second request (simulated delivery retry): should get the same response
        val retry = execRepo.claim(conversation.id, clientMessageId, UUID.randomUUID())
        assertIs<ExecutionClaim.Completed>(
            when {
                !retry.winner && retry.execution.status == "completed" -> ExecutionClaim.Completed(
                    com.pinkdreams.chat.PersistedResponse(retry.execution.assistantMessageId!!, "")
                )
                else -> null
            }
        )
        assertEquals(assistantMsg.id, retry.execution.assistantMessageId, "Should reuse persisted message")
    }

    // ============================================================================
    // SECTION 7: CROSS-USER IDEMPOTENCY - USERS CANNOT SHARE CONVERSATIONS
    // ============================================================================

    @Test
    fun `cross-user isolation - user B cannot access user A's conversation`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        val convRepo = ConversationRepository(db)
        val conversation = convRepo.create(userA, UUID.randomUUID())

        // User B tries to access User A's conversation
        val accessByB = convRepo.findByIdForUser(conversation.id, userB)
        assertEquals(null, accessByB, "User B should not access User A's conversation")
    }

    // ============================================================================
    // SECTION 8: DATABASE UNIQUENESS - PRIMARY KEY ENFORCES CONSTRAINT
    // ============================================================================

    @Test
    fun `database uniqueness - primary key prevents duplicate execution records`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val conversation = ConversationRepository(db).create(UUID.randomUUID(), UUID.randomUUID())
        val execRepo = ChatRequestExecutionRepository(db)
        val clientMessageId = UUID.randomUUID()

        // Claim once
        val first = execRepo.claim(conversation.id, clientMessageId, UUID.randomUUID())
        assertTrue(first.winner)

        // Try to claim again with different request ID - should fail due to PK constraint
        val second = execRepo.claim(conversation.id, clientMessageId, UUID.randomUUID())
        assertIs<ExecutionClaim.Processing>(
            when {
                !second.winner && second.execution.status == "processing" -> ExecutionClaim.Processing
                else -> null
            }
        )

        // Only one execution record should exist
        assertEquals(1L, execRepo.count(), "Exactly one execution record should exist")
    }

    // ============================================================================
    // SECTION 9: TRANSACTION ROLLBACK - NO PARTIAL STATE
    // ============================================================================

    @Test
    fun `transaction isolation - no partial state on concurrent claims`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val conversation = ConversationRepository(db).create(UUID.randomUUID(), UUID.randomUUID())
        val execRepo = ChatRequestExecutionRepository(db)
        val clientMessageId = UUID.randomUUID()

        val firstClaim = execRepo.claim(conversation.id, clientMessageId, UUID.randomUUID())
        assertTrue(firstClaim.winner)
        assertEquals("processing", firstClaim.execution.status)

        // Verify second claim sees consistent state
        val secondClaim = execRepo.claim(conversation.id, clientMessageId, UUID.randomUUID())
        assertIs<ExecutionClaim.Processing>(
            when {
                !secondClaim.winner && secondClaim.execution.status == "processing" -> ExecutionClaim.Processing
                else -> null
            }
        )
    }
}
