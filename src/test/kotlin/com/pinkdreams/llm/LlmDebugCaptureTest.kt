package com.pinkdreams.llm

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.StageResult
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.util.UUID

class LlmDebugCaptureTest {
    @Test
    fun `LlmGenerator captures execution diagnostics with actual context blocks`() {
        val mockResponse = LlmResponse(
            content = "Test response",
            provider = "test",
            model = "test-model",
            metadata = mapOf(
                "prompt_tokens" to "100",
                "completion_tokens" to "50",
                "total_tokens" to "150",
            ),
        )
        val mockClient = FakeLlmClient(response = mockResponse)
        val generator = LlmGenerator(mockClient, GenerationConfig(model = "test-model", maxOutputTokens = 512))

        val chatRequest = ChatRequest(
            requestId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            conversationId = UUID.randomUUID(),
            personaId = UUID.randomUUID(),
            clientMessageId = UUID.randomUUID(),
            content = "Hello, test message 123",
        )

        val context = ChatContext(
            blocks = listOf(
                ContextBlock("system", "You are a test assistant"),
                ContextBlock("system", "Test user profile"),
                ContextBlock("system", "Test memory facts"),
                ContextBlock("user", "Hello, test message 123"),
            ),
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID(),
        )

        val result = generator.generate(chatRequest, context)

        assertTrue(result is StageResult.Succeeded, "Generation should succeed")
        val response = (result as StageResult.Succeeded).value

        val diagnostics = response.executionDiagnostics
        assertNotNull(diagnostics, "Execution diagnostics should be captured")

        val contextBlocks = diagnostics?.contextBlocks
        assertNotNull(contextBlocks, "Context blocks should be captured")

        val userMessage = contextBlocks?.find { it.content.contains("test message 123") }
        assertNotNull(userMessage, "Actual user message should be in captured context")

        val metadata = diagnostics?.llmResponseMetadata
        assertNotNull(metadata, "Response metadata should be captured")
        assertTrue(metadata?.get("prompt_tokens") == "100", "Prompt tokens should be 100")
    }

    @Test
    fun `LlmGenerator captures all context blocks from ChatContext`() {
        val mockResponse = LlmResponse(
            content = "Response",
            provider = "test",
            model = "test-model",
            metadata = mapOf("total_tokens" to "42"),
        )
        val mockClient = FakeLlmClient(response = mockResponse)
        val generator = LlmGenerator(mockClient)

        val context = ChatContext(
            blocks = listOf(
                ContextBlock("system", "Engine rules"),
                ContextBlock("system", "User profile"),
                ContextBlock("system", "Memory facts"),
                ContextBlock("user", "Current message"),
            ),
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID(),
        )

        val result = generator.generate(
            ChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "test"),
            context,
        )

        assertTrue(result is StageResult.Succeeded)
        val response = (result as StageResult.Succeeded).value
        val blocks = response.executionDiagnostics?.contextBlocks

        assertNotNull(blocks, "Context blocks should be captured")
        assertTrue(blocks?.size == 4, "All 4 blocks should be captured")
    }
}
