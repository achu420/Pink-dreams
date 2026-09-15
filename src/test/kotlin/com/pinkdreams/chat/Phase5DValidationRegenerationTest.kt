package com.pinkdreams.chat

import com.pinkdreams.common.errors.ErrorCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class Phase5DValidationRegenerationTest {
    @Test
    fun `flagged output regenerates once and persists the accepted result`() {
        val generated = listOf(GenerationResponse("flagged"), GenerationResponse("accepted"))
        var generationCalls = 0
        var validationCalls = 0
        var persistedContent: String? = null
        val context = context()
        val engine = engine(
            context = context,
            generator = Generator { _, receivedContext ->
                assertEquals(context, receivedContext)
                StageResult.Succeeded(generated[generationCalls++])
            },
            validator = OutputValidator { _, response ->
                validationCalls++
                if (response.content == "flagged") ValidationDecision.Rejected("test") else ValidationDecision.Accepted
            },
            persistence = { _, response ->
                persistedContent = response.content
                StageResult.Succeeded(PersistedResponse(UUID.randomUUID(), response.content))
            },
        )

        val result = assertIs<ChatResult.Success>(engine.process(request()))

        assertEquals(2, generationCalls)
        assertEquals(2, validationCalls)
        assertEquals("accepted", persistedContent)
        assertEquals(
            listOf(
                PipelineStage.RECEIVED,
                PipelineStage.ENTITLEMENT_CHECK,
                PipelineStage.INPUT_MODERATION,
                PipelineStage.CONTEXT_ASSEMBLY,
                PipelineStage.GENERATION,
                PipelineStage.OUTPUT_VALIDATION,
                PipelineStage.REGENERATE,
                PipelineStage.GENERATION,
                PipelineStage.OUTPUT_VALIDATION,
                PipelineStage.PERSIST,
                PipelineStage.DELIVER,
            ),
            result.state.visitedStages,
        )
    }

    @Test
    fun `second flagged output fails and never regenerates a third time`() {
        var generationCalls = 0
        var validationCalls = 0
        var persistenceCalled = false
        var deliveryCalled = false
        val engine = engine(
            generator = Generator { _, _ ->
                generationCalls++
                StageResult.Succeeded(GenerationResponse("flagged-$generationCalls"))
            },
            validator = OutputValidator { _, _ ->
                validationCalls++
                ValidationDecision.Rejected("always")
            },
            persistence = ChatPersistence { _, _ ->
                persistenceCalled = true
                StageResult.Succeeded(PersistedResponse(UUID.randomUUID(), "unexpected"))
            },
            delivery = ChatDelivery { _, _ ->
                deliveryCalled = true
                StageResult.Succeeded(Unit)
            },
        )

        val result = assertIs<ChatResult.Failure>(engine.process(request()))

        assertEquals(ErrorCode.VALIDATION_FAILED, result.code)
        assertEquals(2, generationCalls)
        assertEquals(2, validationCalls)
        assertFalse(persistenceCalled)
        assertFalse(deliveryCalled)
    }

    @Test
    fun `regeneration failure maps to generation failed`() {
        var generationCalls = 0
        var persistenceCalled = false
        var deliveryCalled = false
        val engine = engine(
            generator = Generator { _, _ ->
                generationCalls++
                if (generationCalls == 1) StageResult.Succeeded(GenerationResponse("flagged"))
                else StageResult.Failed(ErrorCode.GENERATION_FAILED)
            },
            validator = OutputValidator { _, _ -> ValidationDecision.Rejected("test") },
            persistence = ChatPersistence { _, _ ->
                persistenceCalled = true
                StageResult.Succeeded(PersistedResponse(UUID.randomUUID(), "unexpected"))
            },
            delivery = ChatDelivery { _, _ ->
                deliveryCalled = true
                StageResult.Succeeded(Unit)
            },
        )

        val result = assertIs<ChatResult.Failure>(engine.process(request()))

        assertEquals(ErrorCode.GENERATION_FAILED, result.code)
        assertEquals(2, generationCalls)
        assertFalse(persistenceCalled)
        assertFalse(deliveryCalled)
    }

    @Test
    fun `validator failure maps to validation failed without regeneration`() {
        var generationCalls = 0
        var persistenceCalled = false
        var deliveryCalled = false
        val engine = engine(
            generator = Generator { _, _ ->
                generationCalls++
                StageResult.Succeeded(GenerationResponse("candidate"))
            },
            validator = OutputValidator { _, _ -> throw IllegalStateException("validator unavailable") },
            persistence = ChatPersistence { _, _ ->
                persistenceCalled = true
                StageResult.Succeeded(PersistedResponse(UUID.randomUUID(), "unexpected"))
            },
            delivery = ChatDelivery { _, _ ->
                deliveryCalled = true
                StageResult.Succeeded(Unit)
            },
        )

        val result = assertIs<ChatResult.Failure>(engine.process(request()))

        assertEquals(ErrorCode.VALIDATION_FAILED, result.code)
        assertEquals(1, generationCalls)
        assertFalse(persistenceCalled)
        assertFalse(deliveryCalled)
    }

    private fun engine(
        context: ChatContext = context(),
        generator: Generator = Generator { _, _ -> StageResult.Succeeded(GenerationResponse("accepted")) },
        validator: OutputValidator = OutputValidator { _, _ -> ValidationDecision.Accepted },
        persistence: ChatPersistence = ChatPersistence { _, response ->
            StageResult.Succeeded(PersistedResponse(UUID.randomUUID(), response.content))
        },
        delivery: ChatDelivery = ChatDelivery { _, _ -> StageResult.Succeeded(Unit) },
    ) = PipelineChatEngine(
        entitlementChecker = { EntitlementDecision.Allowed },
        inputModerator = { ModerationDecision.Allowed },
        contextAssembler = { StageResult.Succeeded(context) },
        generator = generator,
        outputValidator = validator,
        persistence = persistence,
        delivery = delivery,
    )

    private fun context() = ChatContext(
        blocks = listOf(ContextBlock("system", "context")),
        engineVersionId = UUID.randomUUID(),
        personaCoreVersionId = UUID.randomUUID(),
    )

    private fun request() = ChatRequest(
        requestId = UUID.randomUUID(),
        userId = UUID.randomUUID(),
        conversationId = UUID.randomUUID(),
        personaId = UUID.randomUUID(),
        clientMessageId = UUID.randomUUID(),
        content = "hello",
    )
}