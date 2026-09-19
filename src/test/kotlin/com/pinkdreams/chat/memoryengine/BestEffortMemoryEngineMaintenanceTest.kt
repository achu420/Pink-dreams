package com.pinkdreams.chat.memoryengine

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.PersistedResponse
import com.pinkdreams.chat.memory.CompletedTurn
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BestEffortMemoryEngineMaintenanceTest {

    private val syncExecutor = Executor { it.run() }

    private class Fixture(
        val db: org.jetbrains.exposed.sql.Database,
        val userId: UUID,
        val personaId: UUID,
        val conversationId: UUID,
        val conversationRepository: ConversationRepository,
        val messageRepository: MessageRepository,
        val memoryFactRepository: MemoryFactRepository,
        val memoryService: MemoryService,
    )

    private fun fixture(): Fixture {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val conversationRepository = ConversationRepository(db)
        val conversation = conversationRepository.create(userId, personaId)
        val messageRepository = MessageRepository(db)
        val memoryFactRepository = MemoryFactRepository(db)
        return Fixture(db, userId, personaId, conversation.id, conversationRepository, messageRepository, memoryFactRepository, MemoryService(memoryFactRepository))
    }

    private fun addMessages(f: Fixture, count: Int, startAt: LocalDateTime = LocalDateTime.of(2026, 1, 1, 0, 0)) {
        repeat(count) { i ->
            f.messageRepository.createUserMessage(f.conversationId, "message $i", UUID.randomUUID(), UUID.randomUUID(), createdAt = startAt.plusMinutes(i.toLong()))
        }
    }

    private fun turn(f: Fixture): CompletedTurn {
        val request = ChatRequest(UUID.randomUUID(), f.userId, f.conversationId, f.personaId, UUID.randomUUID(), "current")
        val context = ChatContext(listOf(ContextBlock("system", "x")), engineVersionId = UUID.randomUUID(), personaCoreVersionId = UUID.randomUUID())
        return CompletedTurn(request, context, PersistedResponse(UUID.randomUUID(), "reply"))
    }

    private class CountingMaintainer(private val result: MemoryMaintenanceResult = MemoryMaintenanceResult()) : MemoryEngineMaintainer {
        val callCount = AtomicInteger(0)
        val receivedBatches = mutableListOf<List<MessageRepository.Message>>()
        override fun maintain(turn: CompletedTurn, batch: List<MessageRepository.Message>, userWorkingMemory: List<MemoryFactRepository.MemoryFact>, personaWorkingMemory: List<MemoryFactRepository.MemoryFact>): MemoryMaintenanceResult {
            callCount.incrementAndGet()
            synchronized(receivedBatches) { receivedBatches += batch }
            return result
        }
    }

    // --- Batch cursor: fewer than batchSize available -> no-op ---
    @Test
    fun `dispatch does nothing when fewer than batchSize messages are available`() {
        val f = fixture()
        addMessages(f, 5)
        val maintainer = CountingMaintainer()
        val hook = BestEffortMemoryEngineMaintenance(maintainer, MemoryEngineChangeApplier(f.memoryFactRepository), f.memoryService, f.conversationRepository, f.messageRepository, batchSize = 10, executor = syncExecutor)

        hook.dispatch(turn(f))

        assertEquals(0, maintainer.callCount.get())
        assertEquals(0, f.conversationRepository.findById(f.conversationId)!!.memoryEngineProcessedCount)
    }

    // --- Full batch triggers processing and advances the cursor ---
    @Test
    fun `dispatch processes exactly the next unprocessed batch and advances the cursor`() {
        val f = fixture()
        addMessages(f, 10)
        val maintainer = CountingMaintainer()
        val hook = BestEffortMemoryEngineMaintenance(maintainer, MemoryEngineChangeApplier(f.memoryFactRepository), f.memoryService, f.conversationRepository, f.messageRepository, batchSize = 10, executor = syncExecutor)

        hook.dispatch(turn(f))

        assertEquals(1, maintainer.callCount.get())
        assertEquals(10, maintainer.receivedBatches.single().size)
        assertEquals("message 0", maintainer.receivedBatches.single().first().content)
        assertEquals(10, f.conversationRepository.findById(f.conversationId)!!.memoryEngineProcessedCount)
    }

    // --- Second batch only includes new messages, never re-sends the first batch ---
    @Test
    fun `next batch contains only newly available messages`() {
        val f = fixture()
        addMessages(f, 10)
        val maintainer = CountingMaintainer()
        val hook = BestEffortMemoryEngineMaintenance(maintainer, MemoryEngineChangeApplier(f.memoryFactRepository), f.memoryService, f.conversationRepository, f.messageRepository, batchSize = 10, executor = syncExecutor)
        hook.dispatch(turn(f))

        addMessages(f, 10, startAt = LocalDateTime.of(2026, 1, 2, 0, 0))
        hook.dispatch(turn(f))

        assertEquals(2, maintainer.callCount.get())
        assertEquals("message 0", maintainer.receivedBatches[1].first().content, "Second batch's messages are new rows, both happen to be labeled 'message 0' of their own addMessages call, but must be the SECOND set chronologically")
        assertEquals(20, f.conversationRepository.findById(f.conversationId)!!.memoryEngineProcessedCount)
    }

    // --- Idempotency: dispatching again with no new messages does nothing further ---
    @Test
    fun `dispatch is idempotent when no new messages have arrived since the last processed batch`() {
        val f = fixture()
        addMessages(f, 10)
        val maintainer = CountingMaintainer()
        val hook = BestEffortMemoryEngineMaintenance(maintainer, MemoryEngineChangeApplier(f.memoryFactRepository), f.memoryService, f.conversationRepository, f.messageRepository, batchSize = 10, executor = syncExecutor)

        hook.dispatch(turn(f))
        hook.dispatch(turn(f)) // simulated retry/duplicate post-delivery execution

        assertEquals(1, maintainer.callCount.get(), "A duplicate dispatch with no new messages must not reprocess or duplicate")
    }

    // --- Concurrency: two racing dispatches for the same batch, only one may win ---
    @Test
    fun `concurrent dispatch for the same batch is claimed by exactly one worker`() {
        val f = fixture()
        addMessages(f, 10)
        val maintainer = CountingMaintainer()
        // Real async executor here (not the sync one) so both threads' internal
        // CompletableFuture.runAsync bodies can genuinely race against each other.
        val hook = BestEffortMemoryEngineMaintenance(maintainer, MemoryEngineChangeApplier(f.memoryFactRepository), f.memoryService, f.conversationRepository, f.messageRepository, batchSize = 10)

        val barrier = CyclicBarrier(2)
        val threadA = Thread { barrier.await(); hook.dispatch(turn(f)) }
        val threadB = Thread { barrier.await(); hook.dispatch(turn(f)) }
        threadA.start(); threadB.start()
        threadA.join(10_000); threadB.join(10_000)
        // dispatch() itself returns immediately (async); give the pool a moment.
        Thread.sleep(500)

        assertEquals(1, maintainer.callCount.get(), "Exactly one of the two racing workers may claim and process this batch")
        assertEquals(10, f.conversationRepository.findById(f.conversationId)!!.memoryEngineProcessedCount)
    }

    // --- Failure isolation: maintainer throwing must not propagate ---
    @Test
    fun `maintainer failure does not throw out of dispatch and no memory is created`() {
        val f = fixture()
        addMessages(f, 10)
        val throwingMaintainer = MemoryEngineMaintainer { _, _, _, _ -> throw RuntimeException("simulated LLM failure") }
        val hook = BestEffortMemoryEngineMaintenance(throwingMaintainer, MemoryEngineChangeApplier(f.memoryFactRepository), f.memoryService, f.conversationRepository, f.messageRepository, batchSize = 10, executor = syncExecutor)

        hook.dispatch(turn(f)) // must not throw

        assertTrue(f.memoryFactRepository.findForRelationship(f.userId, f.personaId).isEmpty())
    }

    // --- Working memory target: a guideline for LLM INPUT size, never a forced cap on canonical storage ---
    @Test
    fun `working memory target bounds what is sent to the maintainer but never caps canonical hot fact count`() {
        val f = fixture()
        repeat(25) { i -> f.memoryFactRepository.create(f.userId, f.personaId, "fact $i", "interest", "medium") }

        val canonicalHotOpen = f.memoryFactRepository.findForRelationship(f.userId, f.personaId).count { it.tier == "hot" && it.status == "open" }
        assertEquals(25, canonicalHotOpen, "Canonical storage must never be force-truncated merely because it exceeds the working-memory target")

        val workingSetForLlmInput = f.memoryService.selectWorkingSet(f.userId, f.personaId, "USER", limit = 20)
        assertEquals(20, workingSetForLlmInput.size, "The target only bounds what is shown to the LLM in one maintenance call, not what's stored")
    }
}
