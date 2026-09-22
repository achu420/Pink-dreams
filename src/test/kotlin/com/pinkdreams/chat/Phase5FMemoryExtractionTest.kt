package com.pinkdreams.chat

import com.pinkdreams.chat.memory.BestEffortMemoryExtraction
import com.pinkdreams.chat.memory.CompletedTurn
import com.pinkdreams.chat.memory.ExtractionResult
import com.pinkdreams.chat.memory.MemoryCandidate
import com.pinkdreams.chat.memory.MemoryExtractor
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.chat.memory.NoopPostDeliveryMemoryExtraction
import com.pinkdreams.chat.memory.PostDeliveryMemoryExtraction
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Phase5FMemoryExtractionTest {
    @Test
    fun `successful delivery dispatches final turn to MemoryService after delivery`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val memoryService = MemoryService(MemoryFactRepository(db))
        val events = mutableListOf<String>()
        var extractedTurn: CompletedTurn? = null
        val extraction = BestEffortMemoryExtraction(
            extractor = MemoryExtractor { turn ->
                events += "extract"
                extractedTurn = turn
                ExtractionResult(listOf(MemoryCandidate("likes tea", "interest", "high", source = "manual")))
            },
            memoryService = memoryService,
            executor = Executor { it.run() },
        )
        val request = request(userId, personaId)
        val context = context()
        val engine = engine(
            request = request,
            context = context,
            persistence = ChatPersistence { _, _ ->
                events += "persist"
                StageResult.Succeeded(PersistedResponse(UUID.randomUUID(), "final"))
            },
            delivery = ChatDelivery { _, _ ->
                events += "deliver"
                StageResult.Succeeded(Unit)
            },
            extraction = extraction,
        )

        val result = assertIs<ChatResult.Success>(engine.process(request))
        val stored = memoryService.findForRelationship(userId, personaId)

        assertEquals("final", result.response.content)
        assertEquals(listOf("persist", "deliver", "extract"), events)
        assertEquals("final", extractedTurn?.response?.content)
        assertEquals(userId, extractedTurn?.request?.userId)
        assertEquals(personaId, extractedTurn?.request?.personaId)
        assertEquals("llm_extracted", stored.single().source)
        assertEquals("hot", stored.single().tier)
        assertEquals(null, stored.single().lastReferencedAt)
    }

    @Test
    fun `extraction failure does not change successful chat result`() {
        var deliveryCalled = false
        val extraction = BestEffortMemoryExtraction(
            extractor = MemoryExtractor { throw IllegalStateException("extractor unavailable") },
            memoryService = MemoryService(MemoryFactRepository(DatabaseFactory.connectInMemory())),
            executor = Executor { it.run() },
        )
        val request = request(UUID.randomUUID(), UUID.randomUUID())
        val engine = engine(
            request = request,
            delivery = ChatDelivery { _, _ ->
                deliveryCalled = true
                StageResult.Succeeded(Unit)
            },
            extraction = extraction,
        )

        val result = engine.process(request)

        assertIs<ChatResult.Success>(result)
        assertTrue(deliveryCalled)
    }

    @Test
    fun `delivery failure does not dispatch extraction`() {
        var extractionCalled = false
        val extraction = PostDeliveryMemoryExtraction { extractionCalled = true }
        val request = request(UUID.randomUUID(), UUID.randomUUID())
        val engine = engine(
            request = request,
            delivery = ChatDelivery { _, _ -> StageResult.Failed(com.pinkdreams.common.errors.ErrorCode.DELIVERY_FAILED) },
            extraction = extraction,
        )

        val result = assertIs<ChatResult.Failure>(engine.process(request))

        assertEquals(com.pinkdreams.common.errors.ErrorCode.DELIVERY_FAILED, result.code)
        assertFalse(extractionCalled)
    }

    /**
     * Task 25F fix 4 regression. The memory-reference write path was
     * structurally dead: MemoryFactRepository.markReferenced and
     * MemoryService.markReferenced both existed, but their only caller was
     * gated on ExtractionResult.referencedFactIds, which no extractor ever
     * populates — 0 of 280 real memory_facts rows had last_referenced_at set,
     * so both the eviction comparator's recency tiebreak and the selector's
     * recency term degraded to learnedAt alone. The facts actually injected
     * into the turn are already known as ChatContext.memoryIdsUsed.
     */
    @Test
    fun `Task 25F - memories injected into the turn are marked as referenced`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val repository = MemoryFactRepository(db)
        val memoryService = MemoryService(repository)
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val injected = repository.create(userId, personaId, "user lives in Goa", "interest", "high")
        val notInjected = repository.create(userId, personaId, "user likes tea", "interest", "low")

        val request = request(userId, personaId)
        val engine = engine(
            request = request,
            context = context().copy(memoryIdsUsed = listOf(injected.id)),
            extraction = BestEffortMemoryExtraction(
                extractor = MemoryExtractor { ExtractionResult(emptyList()) },
                memoryService = memoryService,
                executor = Executor { it.run() },
            ),
        )

        assertIs<ChatResult.Success>(engine.process(request))

        assertTrue(repository.findById(injected.id)!!.lastReferencedAt != null, "an injected memory must be marked as referenced")
        assertEquals(null, repository.findById(notInjected.id)!!.lastReferencedAt, "a memory that was not injected must not be marked")
    }

    @Test
    fun `Task 25F - an unmarkable memory id never breaks extraction`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val repository = MemoryFactRepository(db)
        val memoryService = MemoryService(repository)
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()

        val request = request(userId, personaId)
        val engine = engine(
            request = request,
            // A stale id (e.g. a fact evicted between selection and extraction):
            // markReferenced rejects it, and extraction must still complete.
            context = context().copy(memoryIdsUsed = listOf(UUID.randomUUID())),
            extraction = BestEffortMemoryExtraction(
                extractor = MemoryExtractor { ExtractionResult(listOf(MemoryCandidate("user is a marine biologist", "interest", "high"))) },
                memoryService = memoryService,
                executor = Executor { it.run() },
            ),
        )

        assertIs<ChatResult.Success>(engine.process(request))

        assertEquals(1, repository.findForRelationship(userId, personaId).size, "extraction still recorded its fact")
    }

    private fun engine(
        request: ChatRequest,
        context: ChatContext = context(),
        persistence: ChatPersistence = ChatPersistence { _, _ ->
            StageResult.Succeeded(PersistedResponse(UUID.randomUUID(), "final"))
        },
        delivery: ChatDelivery = ChatDelivery { _, _ -> StageResult.Succeeded(Unit) },
        extraction: PostDeliveryMemoryExtraction = NoopPostDeliveryMemoryExtraction,
    ) = PipelineChatEngine(
        entitlementChecker = { EntitlementDecision.Allowed },
        inputModerator = { ModerationDecision.Allowed },
        contextAssembler = { StageResult.Succeeded(context) },
        generator = { _, _ -> StageResult.Succeeded(GenerationResponse("generated")) },
        outputValidator = { _, _ -> ValidationDecision.Accepted },
        persistence = persistence,
        delivery = delivery,
        postDeliveryMemoryExtraction = extraction,
    )

    private fun context() = ChatContext(
        blocks = listOf(ContextBlock("system", "context")),
        engineVersionId = UUID.randomUUID(),
        personaCoreVersionId = UUID.randomUUID(),
    )

    private fun request(userId: UUID, personaId: UUID) = ChatRequest(
        requestId = UUID.randomUUID(),
        userId = userId,
        conversationId = UUID.randomUUID(),
        personaId = personaId,
        clientMessageId = UUID.randomUUID(),
        content = "hello",
    )
}