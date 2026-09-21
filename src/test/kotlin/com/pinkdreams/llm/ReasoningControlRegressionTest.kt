package com.pinkdreams.llm

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runtime Quality + Latency Verification phase.
 *
 * Root cause of the observed 8-45s turn latency: the two sequential
 * user-facing LLM calls (Intent Discovery, then Generation) each incur full
 * chain-of-thought reasoning from the underlying reasoning-capable model,
 * even though Intent Discovery is a mechanical single-keyword classification
 * that does not need it. Live-measured against the real production prompt:
 * 2.4s-20.5s with reasoning on, a consistent ~2s with `reasoning:
 * {"enabled": false}`, identical correct answer both times.
 *
 * This test proves the fix reaches the actual HTTP request body — it starts
 * a local HTTP server and inspects the literal bytes OpenRouterLlmClient
 * sends, the same technique Phase5COpenRouterHttpBoundaryTest already uses.
 */
class ReasoningControlRegressionTest {

    private fun startMockServer(handler: (com.sun.net.httpserver.HttpExchange) -> String): com.sun.net.httpserver.HttpServer {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            val responseBody = handler(exchange)
            val bytes = responseBody.toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    private fun okResponseBody() =
        """{"id":"test","model":"m","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""

    @Test
    fun `reasoningEnabled false is sent as reasoning enabled false in the request body`() {
        var capturedBody: String? = null
        val server = startMockServer { exchange ->
            capturedBody = exchange.requestBody.bufferedReader().use { it.readText() }
            okResponseBody()
        }
        try {
            val client = OpenRouterLlmClient(apiKey = "test", endpoint = "http://localhost:${server.address.port}/")
            val request = GenerationRequest(
                requestId = java.util.UUID.randomUUID(), userId = java.util.UUID.randomUUID(),
                conversationId = java.util.UUID.randomUUID(), personaId = java.util.UUID.randomUUID(),
                engineVersionId = java.util.UUID.randomUUID(), personaCoreVersionId = java.util.UUID.randomUUID(),
                context = com.pinkdreams.chat.ChatContext(blocks = listOf(com.pinkdreams.chat.ContextBlock("system", "classify"))),
                config = GenerationConfig(reasoningEnabled = false),
            )

            client.generate(request)

            assertTrue(capturedBody!!.contains("\"reasoning\":{\"enabled\":false}"), "reasoningEnabled=false must reach the actual request body: $capturedBody")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `temperature reaches the actual request body when set`() {
        // Runtime Quality + Latency Verification phase finding: GenerationConfig
        // .temperature existed but was never actually sent to the provider —
        // every call ran at the provider's default sampling regardless of
        // configuration. Discovered while investigating routing-quality
        // variance under reasoning-off; temperature=0 made an otherwise
        // scattered, non-deterministic classification 5/5 identical on repeat.
        var capturedBody: String? = null
        val server = startMockServer { exchange ->
            capturedBody = exchange.requestBody.bufferedReader().use { it.readText() }
            okResponseBody()
        }
        try {
            val client = OpenRouterLlmClient(apiKey = "test", endpoint = "http://localhost:${server.address.port}/")
            val request = GenerationRequest(
                requestId = java.util.UUID.randomUUID(), userId = java.util.UUID.randomUUID(),
                conversationId = java.util.UUID.randomUUID(), personaId = java.util.UUID.randomUUID(),
                engineVersionId = java.util.UUID.randomUUID(), personaCoreVersionId = java.util.UUID.randomUUID(),
                context = com.pinkdreams.chat.ChatContext(blocks = listOf(com.pinkdreams.chat.ContextBlock("system", "classify"))),
                config = GenerationConfig(temperature = 0.0),
            )

            client.generate(request)

            assertTrue(capturedBody!!.contains("\"temperature\":0.0"), "temperature must reach the actual request body: $capturedBody")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `no temperature field is sent when left null`() {
        var capturedBody: String? = null
        val server = startMockServer { exchange ->
            capturedBody = exchange.requestBody.bufferedReader().use { it.readText() }
            okResponseBody()
        }
        try {
            val client = OpenRouterLlmClient(apiKey = "test", endpoint = "http://localhost:${server.address.port}/")
            val request = GenerationRequest(
                requestId = java.util.UUID.randomUUID(), userId = java.util.UUID.randomUUID(),
                conversationId = java.util.UUID.randomUUID(), personaId = java.util.UUID.randomUUID(),
                engineVersionId = java.util.UUID.randomUUID(), personaCoreVersionId = java.util.UUID.randomUUID(),
                context = com.pinkdreams.chat.ChatContext(blocks = listOf(com.pinkdreams.chat.ContextBlock("system", "reply naturally"))),
                config = GenerationConfig(),
            )

            client.generate(request)

            assertFalse(
                capturedBody!!.contains("\"temperature\""),
                "Primary generation must not force a temperature, preserving established response variety: $capturedBody",
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `no reasoning field is sent when reasoningEnabled is left null`() {
        var capturedBody: String? = null
        val server = startMockServer { exchange ->
            capturedBody = exchange.requestBody.bufferedReader().use { it.readText() }
            okResponseBody()
        }
        try {
            val client = OpenRouterLlmClient(apiKey = "test", endpoint = "http://localhost:${server.address.port}/")
            val request = GenerationRequest(
                requestId = java.util.UUID.randomUUID(), userId = java.util.UUID.randomUUID(),
                conversationId = java.util.UUID.randomUUID(), personaId = java.util.UUID.randomUUID(),
                engineVersionId = java.util.UUID.randomUUID(), personaCoreVersionId = java.util.UUID.randomUUID(),
                context = com.pinkdreams.chat.ChatContext(blocks = listOf(com.pinkdreams.chat.ContextBlock("system", "reply naturally"))),
                config = GenerationConfig(),
            )

            client.generate(request)

            assertFalse(
                capturedBody!!.contains("\"reasoning\""),
                "The primary generation path must not send any reasoning override, preserving established response behavior: $capturedBody",
            )
        } finally {
            server.stop(0)
        }
    }
}
