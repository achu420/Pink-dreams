package com.pinkdreams.llm

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ChatResult
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.EntitlementDecision
import com.pinkdreams.chat.InputModerator
import com.pinkdreams.chat.OutputValidator
import com.pinkdreams.chat.PipelineChatEngine
import com.pinkdreams.chat.StageResult
import com.pinkdreams.common.errors.ErrorCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class Phase5CGenerationTest {
    @Test
    fun `generator passes exact assembled context and provenance to client`() {
        val request = request()
        val context = ChatContext(
            blocks = listOf(
                ContextBlock("system", "engine + core"),
                ContextBlock("system", "profile"),
                ContextBlock("system", "memory"),
                ContextBlock("system", "messages"),
            ),
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID(),
        )
        val client = FakeLlmClient(
            response = LlmResponse("generated", provider = "fake", model = "test-model", metadata = mapOf("finish" to "stop")),
        )
        val generator = LlmGenerator(client, GenerationConfig(model = "test-model", temperature = 0.2, maxOutputTokens = 64))

        val result = assertIs<StageResult.Succeeded<com.pinkdreams.chat.GenerationResponse>>(
            generator.generate(request, context),
        ).value

        val sent = assertNotNull(client.lastRequest)
        assertEquals(context, sent.context)
        assertEquals(request.requestId, sent.requestId)
        assertEquals(request.userId, sent.userId)
        assertEquals(request.conversationId, sent.conversationId)
        assertEquals(request.personaId, sent.personaId)
        assertEquals(context.engineVersionId, sent.engineVersionId)
        assertEquals(context.personaCoreVersionId, sent.personaCoreVersionId)
        assertEquals(GenerationConfig("test-model", 0.2, 64), sent.config)
        assertEquals("generated", result.content)
        assertEquals(context.engineVersionId, result.engineVersionId)
        assertEquals(context.personaCoreVersionId, result.personaCoreVersionId)
        assertEquals(mapOf("provider" to "fake", "model" to "test-model", "finish" to "stop"), result.providerMetadata)
    }

    @Test
    fun `pipeline maps provider failure to generation failed without later stages`() {
        val request = request()
        var validationCalled = false
        var persistenceCalled = false
        var deliveryCalled = false
        val context = ChatContext(
            blocks = listOf(ContextBlock("system", "assembled")),
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID(),
        )
        val generator = LlmGenerator(FakeLlmClient(failure = IllegalStateException("provider unavailable")))
        val engine = PipelineChatEngine(
            entitlementChecker = { EntitlementDecision.Allowed },
            inputModerator = InputModerator { com.pinkdreams.chat.ModerationDecision.Allowed },
            contextAssembler = { StageResult.Succeeded(context) },
            generator = generator,
            outputValidator = OutputValidator { _, _ -> validationCalled = true; com.pinkdreams.chat.ValidationDecision.Accepted },
            persistence = { _, _ -> persistenceCalled = true; StageResult.Failed(ErrorCode.PERSIST_FAILED) },
            delivery = { _, _ -> deliveryCalled = true; StageResult.Succeeded(Unit) },
        )

        val result = assertIs<ChatResult.Failure>(engine.process(request))

        assertEquals(ErrorCode.GENERATION_FAILED, result.code)
        assertEquals(com.pinkdreams.chat.PipelineStage.GENERATION, result.state.currentStage)
        assertFalse(validationCalled)
        assertFalse(persistenceCalled)
        assertFalse(deliveryCalled)
    }

    private fun request() = ChatRequest(
        requestId = UUID.randomUUID(),
        userId = UUID.randomUUID(),
        conversationId = UUID.randomUUID(),
        personaId = UUID.randomUUID(),
        clientMessageId = UUID.randomUUID(),
        content = "hello",
    )
}