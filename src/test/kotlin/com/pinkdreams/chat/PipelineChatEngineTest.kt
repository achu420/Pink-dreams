package com.pinkdreams.chat

import com.pinkdreams.common.errors.ErrorCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PipelineChatEngineTest {
    @Test
    fun `skeleton visits stages in order and returns a successful result`() {
        val request = request()
        val visited = mutableListOf<String>()
        val engine = engine(
            entitlement = EntitlementChecker {
                visited += "entitlement"
                EntitlementDecision.Allowed
            },
            moderation = InputModerator {
                visited += "moderation"
                ModerationDecision.Allowed
            },
            context = ContextAssembler {
                visited += "context"
                StageResult.Succeeded(ChatContext(emptyList()))
            },
            generation = Generator { _, _ ->
                visited += "generation"
                StageResult.Succeeded(GenerationResponse("reply"))
            },
            validation = OutputValidator { _, _ ->
                visited += "validation"
                ValidationDecision.Accepted
            },
            persistence = ChatPersistence { _, response ->
                visited += "persist"
                StageResult.Succeeded(PersistedResponse(UUID.randomUUID(), response.content))
            },
            delivery = ChatDelivery { _, _ ->
                visited += "deliver"
                StageResult.Succeeded(Unit)
            },
        )

        val result = assertIs<ChatResult.Success>(engine.process(request))

        assertEquals(listOf("entitlement", "moderation", "context", "generation", "validation", "persist", "deliver"), visited)
        assertEquals(
            listOf(
                PipelineStage.RECEIVED,
                PipelineStage.ENTITLEMENT_CHECK,
                PipelineStage.INPUT_MODERATION,
                PipelineStage.CONTEXT_ASSEMBLY,
                PipelineStage.SKILL_SELECTION,
                PipelineStage.GENERATION,
                PipelineStage.OUTPUT_VALIDATION,
                PipelineStage.PERSIST,
                PipelineStage.DELIVER,
            ),
            result.state.visitedStages,
        )
    }

    @Test
    fun `skeleton stops before generation when entitlement is denied`() {
        var generationCalled = false
        val engine = engine(
            entitlement = EntitlementChecker { EntitlementDecision.Denied },
            generation = Generator { _, _ ->
                generationCalled = true
                StageResult.Succeeded(GenerationResponse("unexpected"))
            },
        )

        val result = assertIs<ChatResult.Failure>(engine.process(request()))

        assertEquals(ErrorCode.ENTITLEMENT_DENIED, result.code)
        assertEquals(PipelineStage.ENTITLEMENT_CHECK, result.state.currentStage)
        assertEquals(false, generationCalled)
    }

    @Test
    fun `skeleton stops before generation when input is blocked`() {
        var generationCalled = false
        val engine = engine(
            moderation = InputModerator { ModerationDecision.Blocked },
            generation = Generator { _, _ ->
                generationCalled = true
                StageResult.Succeeded(GenerationResponse("unexpected"))
            },
        )

        val result = assertIs<ChatResult.Failure>(engine.process(request()))

        assertEquals(ErrorCode.MODERATION_BLOCKED, result.code)
        assertEquals(PipelineStage.INPUT_MODERATION, result.state.currentStage)
        assertEquals(false, generationCalled)
    }

    @Test
    fun `skeleton maps stage failures without fabricating success`() {
        val engine = engine(
            generation = Generator { _, _ -> StageResult.Failed(ErrorCode.GENERATION_FAILED) },
        )

        val result = assertIs<ChatResult.Failure>(engine.process(request()))

        assertEquals(ErrorCode.GENERATION_FAILED, result.code)
        assertEquals(PipelineStage.GENERATION, result.state.currentStage)
    }

    private fun engine(
        entitlement: EntitlementChecker = EntitlementChecker { EntitlementDecision.Allowed },
        moderation: InputModerator = InputModerator { ModerationDecision.Allowed },
        context: ContextAssembler = ContextAssembler { StageResult.Succeeded(ChatContext(emptyList())) },
        generation: Generator = Generator { _, _ -> StageResult.Succeeded(GenerationResponse("reply")) },
        validation: OutputValidator = OutputValidator { _, _ -> ValidationDecision.Accepted },
        persistence: ChatPersistence = ChatPersistence { _, response ->
            StageResult.Succeeded(PersistedResponse(UUID.randomUUID(), response.content))
        },
        delivery: ChatDelivery = ChatDelivery { _, _ -> StageResult.Succeeded(Unit) },
    ): PipelineChatEngine = PipelineChatEngine(
        entitlement,
        moderation,
        context,
        generation,
        validation,
        persistence,
        delivery,
    )

    private fun request(): ChatRequest = ChatRequest(
        requestId = UUID.randomUUID(),
        userId = UUID.randomUUID(),
        conversationId = UUID.randomUUID(),
        personaId = UUID.randomUUID(),
        clientMessageId = UUID.randomUUID(),
        content = "hello",
    )
}