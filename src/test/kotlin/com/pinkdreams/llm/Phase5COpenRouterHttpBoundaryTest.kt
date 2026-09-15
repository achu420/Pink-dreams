package com.pinkdreams.llm

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ContextBlock
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Phase5COpenRouterHttpBoundaryTest {

    @Test
    fun `openrouter client sends post request with correct path and headers`() {
        var capturedPath: String? = null
        var capturedMethod: String? = null
        var capturedAuthHeader: String? = null
        var capturedContentType: String? = null

        val server = startMockServer { exchange ->
            capturedPath = exchange.requestURI.path
            capturedMethod = exchange.requestMethod
            capturedAuthHeader = exchange.requestHeaders.getFirst("Authorization")
            capturedContentType = exchange.requestHeaders.getFirst("Content-Type")

            // Return minimal valid response
            """{"id":"test","model":"deepseek/deepseek-v4.1-flash","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
        }

        try {
            val client = OpenRouterLlmClient(
                apiKey = "test-bearer-token",
                endpoint = "http://localhost:${server.address.port}/api/v1/chat/completions"
            )

            try {
                client.generate(generationRequest())
            } catch (e: Exception) {
                // Serialization may fail but we've captured the HTTP request already
            }

            assertEquals("POST", capturedMethod)
            assertEquals("/api/v1/chat/completions", capturedPath)
            assertEquals("Bearer test-bearer-token", capturedAuthHeader)
            assertEquals("application/json", capturedContentType)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `openrouter client sends request body with deepseek model`() {
        var capturedBody: String? = null

        val server = startMockServer { exchange ->
            capturedBody = exchange.requestBody.bufferedReader().use { it.readText() }
            """{"id":"test","model":"deepseek/deepseek-v4.1-flash","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
        }

        try {
            val client = OpenRouterLlmClient(
                apiKey = "test",
                endpoint = "http://localhost:${server.address.port}/api/v1/chat/completions"
            )

            try {
                client.generate(generationRequest())
            } catch (e: Exception) {
                // Serialization may fail but we've captured the body
            }

            assertTrue(!capturedBody.isNullOrEmpty())
            assertTrue(capturedBody!!.contains("\"model\":\"deepseek/deepseek-v4.1-flash\""))
            assertTrue(capturedBody!!.contains("\"stream\":false"))
            assertTrue(capturedBody!!.contains("\"max_tokens\":1024"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `openrouter client sends context blocks as messages in request body`() {
        var capturedBody: String? = null

        val server = startMockServer { exchange ->
            capturedBody = exchange.requestBody.bufferedReader().use { it.readText() }
            """{"id":"test","model":"deepseek/deepseek-v4.1-flash","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
        }

        try {
            val client = OpenRouterLlmClient(
                apiKey = "test",
                endpoint = "http://localhost:${server.address.port}/api/v1/chat/completions"
            )

            val context = ChatContext(
                blocks = listOf(
                    ContextBlock("system", "engine + core"),
                    ContextBlock("system", "profile"),
                ),
                engineVersionId = UUID.randomUUID(),
                personaCoreVersionId = UUID.randomUUID(),
            )

            try {
                client.generate(generationRequest(context = context))
            } catch (e: Exception) {
                // Serialization may fail but we've captured the body
            }

            assertTrue(!capturedBody.isNullOrEmpty())
            assertTrue(capturedBody!!.contains("\"messages\":["))
            assertTrue(capturedBody!!.contains("\"role\":\"system\""))
            assertTrue(capturedBody!!.contains("\"content\":\"engine + core\""))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `openrouter client handles http 400 error response`() {
        val server = startMockServer(statusCode = 400, responseBody = "Bad request") { _ ->
            "Bad request"
        }

        try {
            val client = OpenRouterLlmClient(
                apiKey = "test",
                endpoint = "http://localhost:${server.address.port}/api/v1/chat/completions"
            )

            var exceptionThrown = false
            var errorMessage = ""
            try {
                client.generate(generationRequest())
            } catch (e: Exception) {
                exceptionThrown = true
                errorMessage = e.message ?: ""
            }

            assertTrue(exceptionThrown)
            assertTrue(errorMessage.contains("400"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `openrouter client handles http 500 error response`() {
        val server = startMockServer(statusCode = 500, responseBody = "Server error") { _ ->
            "Server error"
        }

        try {
            val client = OpenRouterLlmClient(
                apiKey = "test",
                endpoint = "http://localhost:${server.address.port}/api/v1/chat/completions"
            )

            var exceptionThrown = false
            var errorMessage = ""
            try {
                client.generate(generationRequest())
            } catch (e: Exception) {
                exceptionThrown = true
                errorMessage = e.message ?: ""
            }

            assertTrue(exceptionThrown)
            assertTrue(errorMessage.contains("500"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `openrouter client handles malformed json response`() {
        val server = startMockServer { _ ->
            """{"invalid json without closing"""
        }

        try {
            val client = OpenRouterLlmClient(
                apiKey = "test",
                endpoint = "http://localhost:${server.address.port}/api/v1/chat/completions"
            )

            var exceptionThrown = false
            try {
                client.generate(generationRequest())
            } catch (e: Exception) {
                exceptionThrown = true
            }

            assertTrue(exceptionThrown)
        } finally {
            server.stop(0)
        }
    }

    private fun generationRequest(
        config: GenerationConfig = GenerationConfig(),
        context: ChatContext = ChatContext(
            blocks = listOf(ContextBlock("system", "test")),
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
        responseBody: String = "{}",
        handler: ((exchange: com.sun.net.httpserver.HttpExchange) -> String)? = null,
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        server.createContext("/api/v1/chat/completions") { exchange ->
            val response = handler?.invoke(exchange) ?: responseBody

            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(statusCode, response.length.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }

        server.start()
        return server
    }
}
