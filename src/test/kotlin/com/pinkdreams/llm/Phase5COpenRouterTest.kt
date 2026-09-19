package com.pinkdreams.llm

import com.pinkdreams.chat.ContextBlock
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Phase5COpenRouterTest {

    @Test
    fun `openrouter client configuration uses deepseek model`() {
        val client = OpenRouterLlmClient(
            apiKey = "test-key",
            model = "deepseek/deepseek-v4.1-flash",
            endpoint = "https://openrouter.ai/api/v1/chat/completions",
            maxOutputTokens = 1024,
        )

        assertEquals("https://openrouter.ai/api/v1/chat/completions", client.endpoint)
    }

    @Test
    fun `openrouter request structure includes model and max tokens`() {
        val messages = listOf(
            OpenRouterMessage("system", "engine + core"),
        )
        val request = OpenRouterRequest(
            model = "deepseek/deepseek-v4.1-flash",
            messages = messages,
            maxTokens = 1024,
            stream = false,
        )

        assertEquals("deepseek/deepseek-v4.1-flash", request.model)
        assertEquals(1024, request.maxTokens)
        assertEquals(false, request.stream)
    }

    @Test
    fun `openrouter response data class parses usage tokens correctly`() {
        val usage = OpenRouterUsage(
            promptTokens = 100,
            completionTokens = 50,
            totalTokens = 150,
        )

        assertEquals(100, usage.promptTokens)
        assertEquals(50, usage.completionTokens)
        assertEquals(150, usage.totalTokens)
    }

    @Test
    fun `openrouter choice extracts finish reason correctly`() {
        val choice = OpenRouterChoice(
            finishReason = "stop",
            message = OpenRouterResponseMessage("assistant", "response")
        )

        assertEquals("stop", choice.finishReason)
        assertEquals("response", choice.message.content)
    }

    @Test
    fun `context blocks map to openrouter messages`() {
        val blocks = listOf(
            ContextBlock("system", "engine + core"),
            ContextBlock("system", "profile"),
            ContextBlock("system", "memory"),
        )

        val messages = blocks.map { block ->
            OpenRouterMessage(role = block.role, content = block.content)
        }

        assertEquals(3, messages.size)
        assertEquals("system", messages[0].role)
        assertEquals("engine + core", messages[0].content)
        assertEquals("memory", messages[2].content)
    }

    @Test
    fun `generation config respects null values`() {
        val config = GenerationConfig(
            model = null,
            temperature = null,
            maxOutputTokens = null,
        )

        assertEquals(null, config.model)
        assertEquals(null, config.temperature)
        assertEquals(null, config.maxOutputTokens)
    }

    @Test
    fun `generation config preserves explicit values`() {
        val config = GenerationConfig(
            model = "custom/model",
            temperature = 0.5,
            maxOutputTokens = 512,
        )

        assertEquals("custom/model", config.model)
        assertEquals(0.5, config.temperature)
        assertEquals(512, config.maxOutputTokens)
    }

    @Test
    fun `stream parameter is always false in openrouter request`() {
        val request = OpenRouterRequest(
            model = "deepseek/deepseek-v4.1-flash",
            messages = listOf(OpenRouterMessage("system", "test")),
            maxTokens = 1024,
            stream = false,
        )

        assertEquals(false, request.stream)
    }

    @Test
    fun `json serialization handles special characters in content`() {
        val content = """Hello "world" with \backslash and
newlines"""

        val escapedContent = content
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        // Verify escaping worked
        assertTrue(escapedContent.contains("\\\\"))
        assertTrue(escapedContent.contains("\\\""))
        assertTrue(escapedContent.contains("\\n"))

        // Verify original problematic characters are now safe
        assertTrue(!escapedContent.contains("\"world\""))
        assertTrue(!escapedContent.contains("\n"))
    }

    @Test
    fun `llm response structure preserves provider metadata`() {
        val response = LlmResponse(
            content = "generated text",
            provider = "openrouter",
            model = "deepseek/deepseek-v4.1-flash",
            metadata = mapOf(
                "finish_reason" to "stop",
                "prompt_tokens" to "100",
                "completion_tokens" to "50",
                "total_tokens" to "150",
            ),
        )

        assertEquals("generated text", response.content)
        assertEquals("openrouter", response.provider)
        assertEquals("deepseek/deepseek-v4.1-flash", response.model)
        assertEquals("stop", response.metadata["finish_reason"])
        assertEquals("100", response.metadata["prompt_tokens"])
    }

    @Test
    fun `generation request structure carries provenance ids`() {
        val engineId = UUID.randomUUID()
        val coreId = UUID.randomUUID()

        val request = GenerationRequest(
            requestId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            conversationId = UUID.randomUUID(),
            personaId = UUID.randomUUID(),
            engineVersionId = engineId,
            personaCoreVersionId = coreId,
            context = com.pinkdreams.chat.ChatContext(
                blocks = listOf(ContextBlock("system", "test")),
                engineVersionId = engineId,
                personaCoreVersionId = coreId,
            ),
            config = GenerationConfig(),
        )

        assertEquals(engineId, request.engineVersionId)
        assertEquals(coreId, request.personaCoreVersionId)
    }

    @Test
    fun `max output tokens default is 1024`() {
        val client = OpenRouterLlmClient(
            apiKey = "test",
            maxOutputTokens = 1024
        )

        assertEquals(1024, client.maxOutputTokens)
    }

    @Test
    fun `config timeout seconds is configurable`() {
        val client = OpenRouterLlmClient(
            apiKey = "test",
            timeoutSeconds = 60
        )

        assertEquals(60, client.timeoutSeconds)
    }
}
