package com.pinkdreams.llm

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.StageResult
import com.pinkdreams.common.errors.ErrorCode
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tier 1 item 3: generation failures must not be silently swallowed. This proves
 * the pipeline-facing result is unchanged (StageResult.Failed(GENERATION_FAILED))
 * while a diagnostic log line is emitted, and — when the failure came from
 * OpenRouterLlmClient — that the log line summarizes the captured provider
 * exchange without leaking the API key or dumping full response bodies.
 */
class LlmGeneratorFailureDiagnosticsTest {

    private fun chatRequestAndContext(): Pair<ChatRequest, ChatContext> {
        val request = ChatRequest(
            requestId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            conversationId = UUID.randomUUID(),
            personaId = UUID.randomUUID(),
            clientMessageId = UUID.randomUUID(),
            content = "hello",
        )
        val context = ChatContext(
            blocks = listOf(ContextBlock("system", "rules"), ContextBlock("user", "hello")),
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID(),
        )
        return request to context
    }

    private fun <T> captureStderr(block: () -> T): Pair<T, String> {
        val originalErr = System.err
        val buffer = ByteArrayOutputStream()
        System.setErr(PrintStream(buffer))
        try {
            val result = block()
            System.err.flush()
            return result to buffer.toString()
        } finally {
            System.setErr(originalErr)
        }
    }

    @Test
    fun `generation failure still returns StageResult Failed and logs diagnostic instead of swallowing silently`() {
        val (request, context) = chatRequestAndContext()
        val failingClient = LlmClient { throw IllegalStateException("simulated provider failure") }
        val generator = LlmGenerator(failingClient)

        val (result, stderrOutput) = captureStderr { generator.generate(request, context) }

        val failed = assertIs<StageResult.Failed>(result)
        assertEquals(ErrorCode.GENERATION_FAILED, failed.code)

        assertTrue(stderrOutput.contains("LLM_GENERATION"), "Failure must be logged via the diagnostic mechanism, not swallowed silently")
        assertTrue(stderrOutput.contains(request.requestId.toString()), "Log must identify the failing request")
        assertTrue(stderrOutput.contains("simulated provider failure"), "Log must include the underlying exception message")
        assertTrue(stderrOutput.contains("no provider exchange captured"), "Non-HTTP clients have no provider exchange to summarize")
    }

    @Test
    fun `openrouter http failure log summarizes the captured exchange without leaking the api key or full body`() {
        val errorBody = "x".repeat(5000) + """{"error":"invalid model"}"""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/chat/completions") { exchange ->
            exchange.sendResponseHeaders(400, errorBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(errorBody.toByteArray()) }
        }
        server.start()

        try {
            val secretKey = "SECRET-KEY-MUST-NOT-APPEAR-IN-LOGS"
            val client = OpenRouterLlmClient(
                apiKey = secretKey,
                endpoint = "http://localhost:${server.address.port}/api/v1/chat/completions",
            )
            val generator = LlmGenerator(client, GenerationConfig(model = "test-model"))
            val (request, context) = chatRequestAndContext()

            val (result, stderrOutput) = captureStderr { generator.generate(request, context) }

            val failed = assertIs<StageResult.Failed>(result)
            assertEquals(ErrorCode.GENERATION_FAILED, failed.code)

            assertTrue(stderrOutput.contains("statusCode=400"), "Log must surface the actual HTTP status")
            assertTrue(stderrOutput.contains("isError=true"), "Log must flag this as a provider error")
            assertTrue(stderrOutput.contains("responseBodyLength=${errorBody.length}"), "Log must reference body size, not necessarily its content")

            assertFalse(stderrOutput.contains(secretKey), "API key must never appear in diagnostic logs")
            assertFalse(stderrOutput.contains(errorBody), "Full response body must not be dumped into normal diagnostic logs")
        } finally {
            server.stop(0)
        }
    }
}
