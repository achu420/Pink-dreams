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