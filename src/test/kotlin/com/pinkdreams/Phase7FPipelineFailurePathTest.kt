package com.pinkdreams

import com.pinkdreams.chat.*
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.chat.memory.CompletedTurn
import com.pinkdreams.chat.memory.NoopPostDeliveryMemoryExtraction
import com.pinkdreams.chat.memory.PostDeliveryMemoryExtraction
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 7F: Chat Pipeline Failure-Path Hardening
 *
 * Comprehensive testing of every failure boundary and stage ordering in the
 * PipelineChatEngine. Verifies that failures don't cascade, regeneration limits
 * are enforced, invalid outputs are never persisted, and memory extraction is
 * isolated after delivery.
 */
class Phase7FPipelineFailurePathTest {

    // Event recording for stage ordering verification
    data class PipelineEvent(val stage: PipelineStage, val timestamp: Long = System.nanoTime())

    class EventRecorder {
        private val events = mutableListOf<PipelineEvent>()

        fun record(stage: PipelineStage) {
            events.add(PipelineEvent(stage))
        }

        fun getSequence(): List<PipelineStage> = events.map { it.stage }

        fun clear() = events.clear()
    }

    private val eventRecorder = EventRecorder()

    // ============================================================================
    // 7F.1 — PIPELINE ORDER
    // ============================================================================

    @Test
    fun `pipeline order - successful request executes all stages and then memory extraction`() {
        var persistenceCalled = false
        var deliveryCalled = false
        var memoryExtractionCalled = false

        val entitlementChecker = object : EntitlementChecker {
            override fun check(request: ChatRequest): EntitlementDecision {
                return EntitlementDecision.Allowed
            }
        }
        val inputModerator = object : InputModerator {
            override fun moderate(request: ChatRequest): ModerationDecision {
                return ModerationDecision.Allowed
            }
        }
        val contextAssembler = object : ContextAssembler {
            override fun assemble(request: ChatRequest): StageResult<ChatContext> {
                return StageResult.Succeeded(ChatContext(emptyList(), UUID.randomUUID(), UUID.randomUUID()))
            }
        }
        val generator = object : Generator {
            override fun generate(request: ChatRequest, context: ChatContext): StageResult<GenerationResponse> {
                return StageResult.Succeeded(GenerationResponse("test", UUID.randomUUID(), UUID.randomUUID()))
            }
        }
        val outputValidator = object : OutputValidator {
            override fun validate(request: ChatRequest, response: GenerationResponse): ValidationDecision {
                return ValidationDecision.Accepted
            }
        }
        val persistence = object : ChatPersistence {
            override fun persist(request: ChatRequest, response: GenerationResponse): StageResult<PersistedResponse> {
                persistenceCalled = true
                return StageResult.Succeeded(PersistedResponse(UUID.randomUUID(), response.content))
            }
        }
        val delivery = object : ChatDelivery {
            override fun deliver(request: ChatRequest, response: PersistedResponse): StageResult<Unit> {
                deliveryCalled = true
                assertTrue(persistenceCalled, "Persistence must execute before delivery")
                return StageResult.Succeeded(Unit)
            }
        }
        val memoryExtraction = object : PostDeliveryMemoryExtraction {
            override fun dispatch(turn: CompletedTurn) {
                memoryExtractionCalled = true
                assertTrue(persistenceCalled && deliveryCalled, "Memory extraction is post-delivery")
            }
        }

        val engine = PipelineChatEngine(
            entitlementChecker, inputModerator, contextAssembler, generator, outputValidator,
            persistence, delivery, NoopChatExecutionCoordinator, memoryExtraction
        )

        val request = ChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "test")
        val result = engine.process(request)

        assertIs<ChatResult.Success>(result)
        assertTrue(persistenceCalled, "Persistence must be called")
        assertTrue(deliveryCalled, "Delivery must be called")
        assertTrue(memoryExtractionCalled, "Memory extraction must be called")
    }

    // ============================================================================
    // 7F.2 — ENTITLEMENT DENIED
    // ============================================================================

    @Test
    fun `entitlement denied - no further stages execute`() {
        eventRecorder.clear()

        val entitlementChecker = object : EntitlementChecker {
            override fun check(request: ChatRequest): EntitlementDecision {
                eventRecorder.record(PipelineStage.ENTITLEMENT_CHECK)
                return EntitlementDecision.Denied
            }
        }
        val inputModerator = object : InputModerator {
            override fun moderate(request: ChatRequest): ModerationDecision {
                eventRecorder.record(PipelineStage.INPUT_MODERATION)
                throw AssertionError("Moderation should not execute after entitlement denial")
            }
        }
        val contextAssembler = object : ContextAssembler {
            override fun assemble(request: ChatRequest): StageResult<ChatContext> {
                eventRecorder.record(PipelineStage.CONTEXT_ASSEMBLY)
                throw AssertionError("Context assembly should not execute")
            }
        }
        val generator = object : Generator {
            override fun generate(request: ChatRequest, context: ChatContext): StageResult<GenerationResponse> {
                eventRecorder.record(PipelineStage.GENERATION)
                throw AssertionError("Generation should not execute")
            }
        }
        val outputValidator = object : OutputValidator {
            override fun validate(request: ChatRequest, response: GenerationResponse): ValidationDecision {
                eventRecorder.record(PipelineStage.OUTPUT_VALIDATION)
                throw AssertionError("Validation should not execute")
            }
        }
        val persistence = object : ChatPersistence {
            override fun persist(request: ChatRequest, response: GenerationResponse): StageResult<PersistedResponse> {
                eventRecorder.record(PipelineStage.PERSIST)
                throw AssertionError("Persistence should not execute")
            }
        }
        val delivery = object : ChatDelivery {
            override fun deliver(request: ChatRequest, response: PersistedResponse): StageResult<Unit> {
                eventRecorder.record(PipelineStage.DELIVER)
                throw AssertionError("Delivery should not execute")
            }
        }

        val engine = PipelineChatEngine(
            entitlementChecker, inputModerator, contextAssembler, generator, outputValidator,
            persistence, delivery
        )

        val request = ChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "test")
        val result = engine.process(request)

        assertIs<ChatResult.Failure>(result)
        assertEquals(ErrorCode.ENTITLEMENT_DENIED, result.code)
        assertEquals(listOf(PipelineStage.ENTITLEMENT_CHECK), eventRecorder.getSequence())
    }

    // ============================================================================
    // 7F.3 — MODERATION BLOCKED
    // ============================================================================

    @Test
    fun `moderation blocked - generation does not occur`() {
        eventRecorder.clear()

        val entitlementChecker = object : EntitlementChecker {
            override fun check(request: ChatRequest): EntitlementDecision {
                eventRecorder.record(PipelineStage.ENTITLEMENT_CHECK)
                return EntitlementDecision.Allowed
            }
        }
        val inputModerator = object : InputModerator {
            override fun moderate(request: ChatRequest): ModerationDecision {
                eventRecorder.record(PipelineStage.INPUT_MODERATION)
                return ModerationDecision.Blocked
            }
        }
        val generator = object : Generator {
            override fun generate(request: ChatRequest, context: ChatContext): StageResult<GenerationResponse> {
                eventRecorder.record(PipelineStage.GENERATION)
                throw AssertionError("Generation should not execute after moderation block")
            }
        }
        val contextAssembler = object : ContextAssembler {
            override fun assemble(request: ChatRequest): StageResult<ChatContext> {
                eventRecorder.record(PipelineStage.CONTEXT_ASSEMBLY)
                throw AssertionError("Context assembly should not execute after moderation block")
            }
        }
        val outputValidator = object : OutputValidator {
            override fun validate(request: ChatRequest, response: GenerationResponse): ValidationDecision {
                throw AssertionError("Validation should not execute")
            }
        }
        val persistence = object : ChatPersistence {
            override fun persist(request: ChatRequest, response: GenerationResponse): StageResult<PersistedResponse> {
                throw AssertionError("Persistence should not execute")
            }
        }
        val delivery = object : ChatDelivery {
            override fun deliver(request: ChatRequest, response: PersistedResponse): StageResult<Unit> {
                throw AssertionError("Delivery should not execute")
            }
        }

        val engine = PipelineChatEngine(
            entitlementChecker, inputModerator, contextAssembler, generator, outputValidator,
            persistence, delivery
        )

        val request = ChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "test")
        val result = engine.process(request)

        assertIs<ChatResult.Failure>(result)
        assertEquals(ErrorCode.MODERATION_BLOCKED, result.code)
        assertEquals(
            listOf(PipelineStage.ENTITLEMENT_CHECK, PipelineStage.INPUT_MODERATION),
            eventRecorder.getSequence()
        )
    }

    // ============================================================================
    // 7F.5 — GENERATION FAILURE
    // ============================================================================

    @Test
    fun `generation failure - no validation or regeneration`() {
        eventRecorder.clear()

        val entitlementChecker = object : EntitlementChecker {
            override fun check(request: ChatRequest): EntitlementDecision {
                eventRecorder.record(PipelineStage.ENTITLEMENT_CHECK)
                return EntitlementDecision.Allowed
            }
        }
        val inputModerator = object : InputModerator {
            override fun moderate(request: ChatRequest): ModerationDecision {
                eventRecorder.record(PipelineStage.INPUT_MODERATION)
                return ModerationDecision.Allowed
            }
        }
        val contextAssembler = object : ContextAssembler {
            override fun assemble(request: ChatRequest): StageResult<ChatContext> {
                eventRecorder.record(PipelineStage.CONTEXT_ASSEMBLY)
                return StageResult.Succeeded(ChatContext(emptyList(), UUID.randomUUID(), UUID.randomUUID()))
            }
        }
        val generator = object : Generator {
            override fun generate(request: ChatRequest, context: ChatContext): StageResult<GenerationResponse> {
                eventRecorder.record(PipelineStage.GENERATION)
                return StageResult.Failed(ErrorCode.GENERATION_FAILED)
            }
        }
        val outputValidator = object : OutputValidator {
            override fun validate(request: ChatRequest, response: GenerationResponse): ValidationDecision {
                eventRecorder.record(PipelineStage.OUTPUT_VALIDATION)
                throw AssertionError("Validation should not execute after generation failure")
            }
        }
        val persistence = object : ChatPersistence {
            override fun persist(request: ChatRequest, response: GenerationResponse): StageResult<PersistedResponse> {
                throw AssertionError("Persistence should not execute")
            }
        }
        val delivery = object : ChatDelivery {
            override fun deliver(request: ChatRequest, response: PersistedResponse): StageResult<Unit> {
                throw AssertionError("Delivery should not execute")
            }
        }

        val engine = PipelineChatEngine(
            entitlementChecker, inputModerator, contextAssembler, generator, outputValidator,
            persistence, delivery
        )

        val request = ChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "test")
        val result = engine.process(request)

        assertIs<ChatResult.Failure>(result)
        assertEquals(ErrorCode.GENERATION_FAILED, result.code)
    }

    // ============================================================================
    // 7F.6 — VALIDATION FAILURE → ONE REGENERATION
    // ============================================================================

    @Test
    fun `validation failure triggers exactly one regeneration`() {
        eventRecorder.clear()
        var generationCount = 0
        var validationCount = 0

        val entitlementChecker = object : EntitlementChecker {
            override fun check(request: ChatRequest): EntitlementDecision {
                return EntitlementDecision.Allowed
            }
        }
        val inputModerator = object : InputModerator {
            override fun moderate(request: ChatRequest): ModerationDecision {
                return ModerationDecision.Allowed
            }
        }
        val contextAssembler = object : ContextAssembler {
            override fun assemble(request: ChatRequest): StageResult<ChatContext> {
                return StageResult.Succeeded(ChatContext(emptyList(), UUID.randomUUID(), UUID.randomUUID()))
            }
        }
        val generator = object : Generator {
            override fun generate(request: ChatRequest, context: ChatContext): StageResult<GenerationResponse> {
                generationCount++
                eventRecorder.record(PipelineStage.GENERATION)
                return StageResult.Succeeded(GenerationResponse("generated-$generationCount", UUID.randomUUID(), UUID.randomUUID()))
            }
        }
        val outputValidator = object : OutputValidator {
            override fun validate(request: ChatRequest, response: GenerationResponse): ValidationDecision {
                validationCount++
                eventRecorder.record(PipelineStage.OUTPUT_VALIDATION)
                return if (validationCount == 1) {
                    ValidationDecision.Rejected("first validation fails")
                } else {
                    ValidationDecision.Accepted
                }
            }
        }
        val persistence = object : ChatPersistence {
            override fun persist(request: ChatRequest, response: GenerationResponse): StageResult<PersistedResponse> {
                eventRecorder.record(PipelineStage.PERSIST)
                // Verify only the second (regenerated) output is persisted
                assertEquals("generated-2", response.content, "Only regenerated output should be persisted")
                return StageResult.Succeeded(PersistedResponse(UUID.randomUUID(), response.content))
            }
        }
        val delivery = object : ChatDelivery {
            override fun deliver(request: ChatRequest, response: PersistedResponse): StageResult<Unit> {
                eventRecorder.record(PipelineStage.DELIVER)
                return StageResult.Succeeded(Unit)
            }
        }

        val engine = PipelineChatEngine(
            entitlementChecker, inputModerator, contextAssembler, generator, outputValidator,
            persistence, delivery
        )

        val request = ChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "test")
        val result = engine.process(request)

        assertIs<ChatResult.Success>(result)
        assertEquals(2, generationCount, "Exactly 2 generation attempts (initial + 1 regeneration)")
        assertEquals(2, validationCount, "Exactly 2 validation attempts")
        assertEquals("generated-2", result.response.content, "Final response is from regeneration")
    }

    // ============================================================================
    // 7F.8 — VALIDATION FAILURE AFTER REGENERATION
    // ============================================================================

    @Test
    fun `validation fails after regeneration - no third generation`() {
        eventRecorder.clear()
        var generationCount = 0

        val entitlementChecker = object : EntitlementChecker {
            override fun check(request: ChatRequest): EntitlementDecision {
                return EntitlementDecision.Allowed
            }
        }
        val inputModerator = object : InputModerator {
            override fun moderate(request: ChatRequest): ModerationDecision {
                return ModerationDecision.Allowed
            }
        }
        val contextAssembler = object : ContextAssembler {
            override fun assemble(request: ChatRequest): StageResult<ChatContext> {
                return StageResult.Succeeded(ChatContext(emptyList(), UUID.randomUUID(), UUID.randomUUID()))
            }
        }
        val generator = object : Generator {
            override fun generate(request: ChatRequest, context: ChatContext): StageResult<GenerationResponse> {
                generationCount++
                return StageResult.Succeeded(GenerationResponse("gen-$generationCount", UUID.randomUUID(), UUID.randomUUID()))
            }
        }
        val outputValidator = object : OutputValidator {
            override fun validate(request: ChatRequest, response: GenerationResponse): ValidationDecision {
                return ValidationDecision.Rejected("always reject")
            }
        }
        val persistence = object : ChatPersistence {
            override fun persist(request: ChatRequest, response: GenerationResponse): StageResult<PersistedResponse> {
                throw AssertionError("Should not persist after validation failure")
            }
        }
        val delivery = object : ChatDelivery {
            override fun deliver(request: ChatRequest, response: PersistedResponse): StageResult<Unit> {
                throw AssertionError("Should not deliver")
            }
        }

        val engine = PipelineChatEngine(
            entitlementChecker, inputModerator, contextAssembler, generator, outputValidator,
            persistence, delivery
        )

        val request = ChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "test")
        val result = engine.process(request)

        assertIs<ChatResult.Failure>(result)
        assertEquals(ErrorCode.VALIDATION_FAILED, result.code)
        assertEquals(2, generationCount, "Exactly 2 generation attempts, no third")
    }

    // ============================================================================
    // 7F.9 — PERSISTENCE FAILURE
    // ============================================================================

    @Test
    fun `persistence failure - generation succeeds but nothing persists`() {
        var generationExecuted = false
        var validationExecuted = false
        var deliveryExecuted = false

        val entitlementChecker = object : EntitlementChecker {
            override fun check(request: ChatRequest): EntitlementDecision {
                return EntitlementDecision.Allowed
            }
        }
        val inputModerator = object : InputModerator {
            override fun moderate(request: ChatRequest): ModerationDecision {
                return ModerationDecision.Allowed
            }
        }
        val contextAssembler = object : ContextAssembler {
            override fun assemble(request: ChatRequest): StageResult<ChatContext> {
                return StageResult.Succeeded(ChatContext(emptyList(), UUID.randomUUID(), UUID.randomUUID()))
            }
        }
        val generator = object : Generator {
            override fun generate(request: ChatRequest, context: ChatContext): StageResult<GenerationResponse> {
                generationExecuted = true
                return StageResult.Succeeded(GenerationResponse("test", UUID.randomUUID(), UUID.randomUUID()))
            }
        }
        val outputValidator = object : OutputValidator {
            override fun validate(request: ChatRequest, response: GenerationResponse): ValidationDecision {
                validationExecuted = true
                return ValidationDecision.Accepted
            }
        }
        val persistence = object : ChatPersistence {
            override fun persist(request: ChatRequest, response: GenerationResponse): StageResult<PersistedResponse> {
                return StageResult.Failed(ErrorCode.PERSIST_FAILED)
            }
        }
        val delivery = object : ChatDelivery {
            override fun deliver(request: ChatRequest, response: PersistedResponse): StageResult<Unit> {
                deliveryExecuted = true
                throw AssertionError("Delivery should not execute after persistence failure")
            }
        }

        val engine = PipelineChatEngine(
            entitlementChecker, inputModerator, contextAssembler, generator, outputValidator,
            persistence, delivery
        )

        val request = ChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "test")
        val result = engine.process(request)

        assertIs<ChatResult.Failure>(result)
        assertEquals(ErrorCode.PERSIST_FAILED, result.code)
        assertTrue(generationExecuted, "Generation should execute before persistence")
        assertTrue(validationExecuted, "Validation should execute before persistence")
        assertEquals(false, deliveryExecuted, "Delivery should not execute after persistence failure")
    }

    // ============================================================================
    // 7F.10 — DELIVERY FAILURE
    // ============================================================================

    @Test
    fun `delivery failure - persistence succeeds, no rollback`() {
        var persistenceExecuted = false

        val entitlementChecker = object : EntitlementChecker {
            override fun check(request: ChatRequest): EntitlementDecision {
                return EntitlementDecision.Allowed
            }
        }
        val inputModerator = object : InputModerator {
            override fun moderate(request: ChatRequest): ModerationDecision {
                return ModerationDecision.Allowed
            }
        }
        val contextAssembler = object : ContextAssembler {
            override fun assemble(request: ChatRequest): StageResult<ChatContext> {
                return StageResult.Succeeded(ChatContext(emptyList(), UUID.randomUUID(), UUID.randomUUID()))
            }
        }
        val generator = object : Generator {
            override fun generate(request: ChatRequest, context: ChatContext): StageResult<GenerationResponse> {
                return StageResult.Succeeded(GenerationResponse("test", UUID.randomUUID(), UUID.randomUUID()))
            }
        }
        val outputValidator = object : OutputValidator {
            override fun validate(request: ChatRequest, response: GenerationResponse): ValidationDecision {
                return ValidationDecision.Accepted
            }
        }
        val persistence = object : ChatPersistence {
            override fun persist(request: ChatRequest, response: GenerationResponse): StageResult<PersistedResponse> {
                persistenceExecuted = true
                return StageResult.Succeeded(PersistedResponse(UUID.randomUUID(), response.content))
            }
        }
        val delivery = object : ChatDelivery {
            override fun deliver(request: ChatRequest, response: PersistedResponse): StageResult<Unit> {
                return StageResult.Failed(ErrorCode.DELIVERY_FAILED)
            }
        }

        val engine = PipelineChatEngine(
            entitlementChecker, inputModerator, contextAssembler, generator, outputValidator,
            persistence, delivery
        )

        val request = ChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "test")
        val result = engine.process(request)

        assertIs<ChatResult.Failure>(result)
        assertEquals(ErrorCode.DELIVERY_FAILED, result.code)
        assertTrue(persistenceExecuted, "Persistence should execute and succeed")
    }

    // ============================================================================
    // 7F.11 — MEMORY EXTRACTION ISOLATION
    // ============================================================================

    @Test
    fun `memory extraction failure does not affect successful chat result`() {
        var memoryExtractionCalled = false

        val entitlementChecker = object : EntitlementChecker {
            override fun check(request: ChatRequest): EntitlementDecision {
                return EntitlementDecision.Allowed
            }
        }
        val inputModerator = object : InputModerator {
            override fun moderate(request: ChatRequest): ModerationDecision {
                return ModerationDecision.Allowed
            }
        }
        val contextAssembler = object : ContextAssembler {
            override fun assemble(request: ChatRequest): StageResult<ChatContext> {
                return StageResult.Succeeded(ChatContext(emptyList(), UUID.randomUUID(), UUID.randomUUID()))
            }
        }
        val generator = object : Generator {
            override fun generate(request: ChatRequest, context: ChatContext): StageResult<GenerationResponse> {
                return StageResult.Succeeded(GenerationResponse("test", UUID.randomUUID(), UUID.randomUUID()))
            }
        }
        val outputValidator = object : OutputValidator {
            override fun validate(request: ChatRequest, response: GenerationResponse): ValidationDecision {
                return ValidationDecision.Accepted
            }
        }
        val persistence = object : ChatPersistence {
            override fun persist(request: ChatRequest, response: GenerationResponse): StageResult<PersistedResponse> {
                return StageResult.Succeeded(PersistedResponse(UUID.randomUUID(), response.content))
            }
        }
        val delivery = object : ChatDelivery {
            override fun deliver(request: ChatRequest, response: PersistedResponse): StageResult<Unit> {
                return StageResult.Succeeded(Unit)
            }
        }
        val memoryExtraction = object : PostDeliveryMemoryExtraction {
            override fun dispatch(turn: CompletedTurn) {
                memoryExtractionCalled = true
                throw RuntimeException("Memory extraction failed")
            }
        }

        val engine = PipelineChatEngine(
            entitlementChecker, inputModerator, contextAssembler, generator, outputValidator,
            persistence, delivery, NoopChatExecutionCoordinator, memoryExtraction
        )

        val request = ChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "test")
        val result = engine.process(request)

        assertIs<ChatResult.Success>(result)
        assertTrue(memoryExtractionCalled, "Memory extraction should be called")
    }

    // ============================================================================
    // 7F.16 — MODERATION / GENERATION BOUNDARY
    // ============================================================================

    @Test
    fun `moderation blocked - generator never called`() {
        var generatorCalled = false

        val entitlementChecker = object : EntitlementChecker {
            override fun check(request: ChatRequest): EntitlementDecision {
                return EntitlementDecision.Allowed
            }
        }
        val inputModerator = object : InputModerator {
            override fun moderate(request: ChatRequest): ModerationDecision {
                return ModerationDecision.Blocked
            }
        }
        val contextAssembler = object : ContextAssembler {
            override fun assemble(request: ChatRequest): StageResult<ChatContext> {
                return StageResult.Succeeded(ChatContext(emptyList(), UUID.randomUUID(), UUID.randomUUID()))
            }
        }
        val generator = object : Generator {
            override fun generate(request: ChatRequest, context: ChatContext): StageResult<GenerationResponse> {
                generatorCalled = true
                throw AssertionError("Generator should never be called when moderation blocks")
            }
        }
        val outputValidator = object : OutputValidator {
            override fun validate(request: ChatRequest, response: GenerationResponse): ValidationDecision {
                throw AssertionError("Validator should not be called")
            }
        }
        val persistence = object : ChatPersistence {
            override fun persist(request: ChatRequest, response: GenerationResponse): StageResult<PersistedResponse> {
                throw AssertionError("Persistence should not occur")
            }
        }
        val delivery = object : ChatDelivery {
            override fun deliver(request: ChatRequest, response: PersistedResponse): StageResult<Unit> {
                throw AssertionError("Delivery should not occur")
            }
        }

        val engine = PipelineChatEngine(
            entitlementChecker, inputModerator, contextAssembler, generator, outputValidator,
            persistence, delivery
        )

        val request = ChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "test")
        val result = engine.process(request)

        assertIs<ChatResult.Failure>(result)
        assertEquals(ErrorCode.MODERATION_BLOCKED, result.code)
        assertEquals(false, generatorCalled, "Generator should never be called")
    }

    // ============================================================================
    // 7F.17 — ENTITLEMENT / GENERATION BOUNDARY
    // ============================================================================

    @Test
    fun `entitlement denied - generator never called`() {
        var generatorCalled = false

        val entitlementChecker = object : EntitlementChecker {
            override fun check(request: ChatRequest): EntitlementDecision {
                return EntitlementDecision.Denied
            }
        }
        val inputModerator = object : InputModerator {
            override fun moderate(request: ChatRequest): ModerationDecision {
                throw AssertionError("Moderator should not be called when entitlement denied")
            }
        }
        val contextAssembler = object : ContextAssembler {
            override fun assemble(request: ChatRequest): StageResult<ChatContext> {
                throw AssertionError("Context assembler should not be called")
            }
        }
        val generator = object : Generator {
            override fun generate(request: ChatRequest, context: ChatContext): StageResult<GenerationResponse> {
                generatorCalled = true
                throw AssertionError("Generator should never be called when entitlement denied")
            }
        }
        val outputValidator = object : OutputValidator {
            override fun validate(request: ChatRequest, response: GenerationResponse): ValidationDecision {
                throw AssertionError("Validator should not be called")
            }
        }
        val persistence = object : ChatPersistence {
            override fun persist(request: ChatRequest, response: GenerationResponse): StageResult<PersistedResponse> {
                throw AssertionError("Persistence should not occur")
            }
        }
        val delivery = object : ChatDelivery {
            override fun deliver(request: ChatRequest, response: PersistedResponse): StageResult<Unit> {
                throw AssertionError("Delivery should not occur")
            }
        }

        val engine = PipelineChatEngine(
            entitlementChecker, inputModerator, contextAssembler, generator, outputValidator,
            persistence, delivery
        )

        val request = ChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "test")
        val result = engine.process(request)

        assertIs<ChatResult.Failure>(result)
        assertEquals(ErrorCode.ENTITLEMENT_DENIED, result.code)
        assertEquals(false, generatorCalled, "Generator should never be called")
    }
}
