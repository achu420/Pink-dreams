package com.pinkdreams.llm

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ContextBlock
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves that OpenRouterLlmClient captures the EXACT bytes exchanged with the
 * provider HTTP endpoint — not a reconstruction from GenerationRequest/ChatContext.
 *
 * Each test independently records what the mock server actually received/sent
 * (server-side truth) and compares it against what the client captured
 * (client-side diagnostics), proving they are identical.
 */
class ProviderExchangeCaptureTest {

    private fun generationRequest(
        config: GenerationConfig = GenerationConfig(),
        context: ChatContext = ChatContext(
            blocks = listOf(ContextBlock("system", "engine+persona"), ContextBlock("user", "CAPTURE_TEST_MESSAGE")),
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID(),
        ),
    ) = GenerationRequest(
        requestId = UUID.randomUUID(),
        userId = UUID.randomUUID(),
        conversationId = UUID.randomUUID(),
        personaId = UUID.randomUUID(),
        engineVersionId = context.engineVersionId!!,
        personaCoreVersionId = context.personaCoreVersionId!!,
        context = context,
        config = config,
    )

    private fun startMockServer(
        statusCode: Int = 200,
        handler: (exchange: com.sun.net.httpserver.HttpExchange) -> String,
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/chat/completions") { exchange ->
            val response = handler(exchange)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(statusCode, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server.start()
        return server
    }

    @Test
    fun `captured request body exactly matches bytes actually received by the server`() {
        var serverSideReceivedBody: String? = null
        val successBody = """{"id":"req-abc-123","model":"deepseek/deepseek-v4.1-flash","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""

        val server = startMockServer { exchange ->
            serverSideReceivedBody = exchange.requestBody.bufferedReader().use { it.readText() }
            successBody
        }

        try {
            val client = OpenRouterLlmClient(
                apiKey = "secret-key-must-not-leak",
                endpoint = "http://localhost:${server.address.port}/api/v1/chat/completions",
            )

            val response = client.generate(generationRequest())

            val exchange = client.lastExchange
            assertNotNull(exchange, "lastExchange must be captured")
            assertNotNull(serverSideReceivedBody, "Mock server must have received a body")

            // The captured request body must be byte-for-byte what the server received.
            assertEquals(serverSideReceivedBody, exchange.request.requestBody)

            // The same exact object must also be attached to the returned LlmResponse.
            assertEquals(exchange, response.providerExchange)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `captured response body exactly matches bytes actually sent by the server`() {
        val successBody = """{"id":"req-xyz-789","model":"deepseek/deepseek-v4.1-flash","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"actual reply text"}}],"usage":{"prompt_tokens":20,"completion_tokens":8,"total_tokens":28}}"""

        val server = startMockServer { successBody }

        try {
            val client = OpenRouterLlmClient(
                apiKey = "test-key",
                endpoint = "http://localhost:${server.address.port}/api/v1/chat/completions",
            )

            client.generate(generationRequest())
            val exchange = client.lastExchange
            assertNotNull(exchange)

            assertEquals(successBody, exchange.response.responseBody, "Captured response body must exactly match what the server sent")
            assertEquals(200, exchange.response.statusCode)
            assertEquals("req-xyz-789", exchange.response.providerRequestId, "Provider request id must be extracted from the actual response")
            assertFalse(exchange.response.isError)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `authorization header is never present in captured diagnostics`() {
        var serverSideAuthHeader: String? = null
        val successBody = """{"id":"req-1","model":"m","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""

        val server = startMockServer { exchange ->
            serverSideAuthHeader = exchange.requestHeaders.getFirst("Authorization")
            successBody
        }

        try {
            val client = OpenRouterLlmClient(
                apiKey = "TOP-SECRET-API-KEY-MUST-NEVER-APPEAR-IN-DIAGNOSTICS",
                endpoint = "http://localhost:${server.address.port}/api/v1/chat/completions",
            )

            client.generate(generationRequest())
            val exchange = client.lastExchange
            assertNotNull(exchange)

            // The real HTTP call DID carry the real bearer token (server-side proof)...
            assertEquals("Bearer TOP-SECRET-API-KEY-MUST-NEVER-APPEAR-IN-DIAGNOSTICS", serverSideAuthHeader)

            // ...but the captured diagnostics must never expose it.
            val capturedAuthValue = exchange.request.requestHeaders["Authorization"]
            assertEquals("[REDACTED]", capturedAuthValue)
            assertFalse(exchange.request.requestHeaders.values.any { it.contains("TOP-SECRET-API-KEY") })
            assertFalse(exchange.request.requestBody.contains("TOP-SECRET-API-KEY"))
            assertFalse(exchange.request.endpoint.contains("TOP-SECRET-API-KEY"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `error response is captured with isError true and the exact error body`() {
        val errorBody = """{"error":{"message":"invalid model","code":400}}"""
        val server = startMockServer(statusCode = 400) { errorBody }

        try {
            val client = OpenRouterLlmClient(
                apiKey = "test-key",
                endpoint = "http://localhost:${server.address.port}/api/v1/chat/completions",
            )

            var thrown = false
            try {
                client.generate(generationRequest())
            } catch (e: Exception) {
                thrown = true
            }
            assertTrue(thrown, "Non-2xx status must still throw for pipeline error handling")

            val exchange = client.lastExchange
            assertNotNull(exchange, "Exchange must be captured even on HTTP error, before the exception is thrown")
            assertEquals(400, exchange.response.statusCode)
            assertTrue(exchange.response.isError)
            assertEquals(errorBody, exchange.response.responseBody, "Captured error body must exactly match what the server returned")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `provider exchange flows through LlmGenerator into a provider-agnostic flattened diagnostics map`() {
        val successBody = """{"id":"req-flow-1","model":"deepseek/deepseek-v4.1-flash","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"reply"}}],"usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}"""
        val server = startMockServer { successBody }

        try {
            val client = OpenRouterLlmClient(
                apiKey = "test-key",
                endpoint = "http://localhost:${server.address.port}/api/v1/chat/completions",
            )
            val generator = LlmGenerator(client, GenerationConfig(model = "deepseek/deepseek-v4.1-flash"))

            val chatRequest = com.pinkdreams.chat.ChatRequest(
                requestId = UUID.randomUUID(),
                userId = UUID.randomUUID(),
                conversationId = UUID.randomUUID(),
                personaId = UUID.randomUUID(),
                clientMessageId = UUID.randomUUID(),
                content = "hi",
            )
            val context = ChatContext(
                blocks = listOf(ContextBlock("system", "rules"), ContextBlock("user", "hi")),
                engineVersionId = UUID.randomUUID(),
                personaCoreVersionId = UUID.randomUUID(),
            )

            val result = generator.generate(chatRequest, context)
            val response = kotlin.test.assertIs<com.pinkdreams.chat.StageResult.Succeeded<com.pinkdreams.chat.GenerationResponse>>(result).value

            val diagnostics = response.executionDiagnostics
            assertNotNull(diagnostics)
            val providerExchangeMap = diagnostics.providerExchange
            assertNotNull(providerExchangeMap, "Flattened provider exchange map must reach LlmExecutionDiagnostics")

            assertEquals("POST", providerExchangeMap["httpMethod"])
            assertEquals("http://localhost:${server.address.port}/api/v1/chat/completions", providerExchangeMap["endpoint"])
            assertEquals("200", providerExchangeMap["responseStatusCode"])
            assertEquals("req-flow-1", providerExchangeMap["providerRequestId"])
            assertEquals("false", providerExchangeMap["isError"])
            assertTrue(providerExchangeMap["requestBody"]!!.contains("\"content\":\"hi\""))
            assertEquals(successBody, providerExchangeMap["responseBody"])
            assertFalse(providerExchangeMap["requestHeaders"]!!.contains("test-key"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `native user and assistant history blocks serialize as separate provider messages with correct roles`() {
        val successBody = """{"id":"req-native-roles","model":"m","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
        var serverSideReceivedBody: String? = null
        val server = startMockServer { exchange ->
            serverSideReceivedBody = exchange.requestBody.bufferedReader().use { it.readText() }
            successBody
        }

        try {
            val client = OpenRouterLlmClient(
                apiKey = "test-key",
                endpoint = "http://localhost:${server.address.port}/api/v1/chat/completions",
            )

            // Simulates exactly what RepositoryContextAssembler now produces: system
            // sections followed by NATIVE per-message history blocks, then the current
            // user message — never a flattened "[role] content" system transcript.
            val context = ChatContext(
                blocks = listOf(
                    ContextBlock("system", "engine+persona"),
                    ContextBlock("system", "profile"),
                    ContextBlock("system", "memory"),
                    ContextBlock("user", "Hello"),
                    ContextBlock("assistant", "Hey, how are you?"),
                    ContextBlock("user", "I am good"),
                ),
                engineVersionId = UUID.randomUUID(),
                personaCoreVersionId = UUID.randomUUID(),
            )

            client.generate(generationRequest(context = context))

            val parsedServerRequest = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString<OpenRouterRequest>(serverSideReceivedBody!!)

            // Prove the ACTUAL bytes the server received preserve native roles in order —
            // no layer collapsed the history into system messages.
            assertEquals(
                listOf("system", "system", "system", "user", "assistant", "user"),
                parsedServerRequest.messages.map { it.role },
            )
            assertEquals("Hello", parsedServerRequest.messages[3].content)
            assertEquals("assistant", parsedServerRequest.messages[4].role)
            assertEquals("Hey, how are you?", parsedServerRequest.messages[4].content)
            assertEquals("I am good", parsedServerRequest.messages[5].content)
            assertEquals("user", parsedServerRequest.messages[5].role)

            // Also verify via the client's own captured exchange (not just server-side truth).
            val captured = client.lastExchange
            assertNotNull(captured)
            val parsedCaptured = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString<OpenRouterRequest>(captured.request.requestBody)
            assertEquals(parsedServerRequest.messages.map { it.role }, parsedCaptured.messages.map { it.role })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `reasoning fields and cost from a real-shaped response never leak into content or typed metadata but remain in the raw admin-only capture`() {
        // Modeled on an actual observed OpenRouter response shape: extra fields
        // (reasoning, reasoning_details, usage.cost, provider) beyond what
        // OpenRouterResponse/OpenRouterMessage/OpenRouterUsage declare.
        val responseWithReasoning = """{
            "id": "req-reasoning-1",
            "model": "deepseek/deepseek-v4.1-flash",
            "provider": "DeepSeek",
            "choices": [{
                "finish_reason": "stop",
                "message": {
                    "role": "assistant",
                    "content": "The user-visible reply.",
                    "reasoning": "SECRET_CHAIN_OF_THOUGHT: step by step internal reasoning that must never reach the user.",
                    "reasoning_details": [{"type": "text", "text": "more internal reasoning detail"}]
                }
            }],
            "usage": {
                "prompt_tokens": 10,
                "completion_tokens": 5,
                "total_tokens": 15,
                "cost": 0.0042
            }
        }"""
        val server = startMockServer { responseWithReasoning }

        try {
            val client = OpenRouterLlmClient(
                apiKey = "test-key",
                endpoint = "http://localhost:${server.address.port}/api/v1/chat/completions",
            )

            val response = client.generate(generationRequest())

            // A. The typed content must be ONLY message.content — never reasoning.
            assertEquals("The user-visible reply.", response.content)
            assertFalse(response.content.contains("SECRET_CHAIN_OF_THOUGHT"), "Reasoning must never be appended to assistant content")

            // Typed metadata (what LlmGenerator forwards as providerMetadata /
            // llmResponseMetadata) must not contain reasoning or cost — those fields
            // are not declared on OpenRouterUsage/OpenRouterMessage, so kotlinx
            // serialization's ignoreUnknownKeys silently drops them here.
            assertFalse(response.metadata.values.any { it.contains("SECRET_CHAIN_OF_THOUGHT") })
            assertFalse(response.metadata.containsKey("cost"))
            assertFalse(response.metadata.containsKey("reasoning"))

            // B. The RAW response bytes (admin-only Provider Debug channel) DO still
            // contain reasoning/cost verbatim — this is the existing, intentional
            // "complete provider-facing response" diagnostic capture, not a leak,
            // since it is only ever exposed behind the isAdmin gate in
            // ConversationHistoryRoutes / RepositoryChatPersistence's lvm_provider_exchange.
            val captured = response.providerExchange
            assertNotNull(captured)
            assertTrue(captured.response.responseBody.contains("SECRET_CHAIN_OF_THOUGHT"))
            assertTrue(captured.response.responseBody.contains("\"cost\": 0.0042"))

            // Secrets: the API key must never appear in the raw captured exchange either.
            assertFalse(captured.request.requestBody.contains("test-key"))
            assertEquals("[REDACTED]", captured.request.requestHeaders["Authorization"])
        } finally {
            server.stop(0)
        }
    }
}
