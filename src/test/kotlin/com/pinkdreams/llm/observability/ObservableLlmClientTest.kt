package com.pinkdreams.llm.observability

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.llm.ExchangeCapturing
import com.pinkdreams.llm.GenerationConfig
import com.pinkdreams.llm.GenerationRequest
import com.pinkdreams.llm.LlmClient
import com.pinkdreams.llm.LlmResponse
import com.pinkdreams.llm.OpenRouterBudgetExhaustionException
import com.pinkdreams.llm.ProviderExchange
import com.pinkdreams.llm.ProviderRequestDiagnostics
import com.pinkdreams.llm.ProviderResponseDiagnostics
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.LlmExchangeRepository
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * LLM Observability and Raw Exchange Capture phase: proves every one of the
 * ten required properties for [ObservableLlmClient], the single decorator
 * every existing LLM call site is wrapped in exactly once (ChatEngineFactory)
 * — no call site's own invocation code changes.
 */
class ObservableLlmClientTest {

    private fun request(workload: String = "test_workload", skillKey: String? = null) = GenerationRequest(
        requestId = UUID.randomUUID(), userId = UUID.randomUUID(), conversationId = UUID.randomUUID(),
        personaId = UUID.randomUUID(), engineVersionId = UUID.randomUUID(), personaCoreVersionId = UUID.randomUUID(),
        context = ChatContext(blocks = listOf(ContextBlock("system", "x"))),
        config = GenerationConfig(model = "test-model", workload = workload),
        skillKey = skillKey,
    )

    private fun exchange(status: Int = 200, isError: Boolean = false) = ProviderExchange(
        request = ProviderRequestDiagnostics("POST", "https://example", "test-model", mapOf("Content-Type" to "application/json"), "{}"),
        response = ProviderResponseDiagnostics(status, "{}", providerRequestId = "req-1", isError = isError),
    )

    private fun repo(): LlmExchangeRepository {
        val db = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }
        return LlmExchangeRepository(db)
    }

    @Test
    fun `1 - a successful call is captured`() {
        val repository = repo()
        val client = LlmClient {
            LlmResponse(
                content = "ok", provider = "openrouter", model = "test-model",
                metadata = mapOf("finish_reason" to "stop", "prompt_tokens" to "10", "completion_tokens" to "5", "total_tokens" to "15", "reasoning_tokens" to "0", "provider_upstream" to "CoreWeave"),
                providerExchange = exchange(),
            )
        }
        val observable = ObservableLlmClient(client, repository, isTestChat = false)
        val req = request()

        observable.generate(req)

        val recorded = repository.findForTurn(req.requestId).single()
        assertEquals(LlmExchangeRepository.Outcome.SUCCESS, recorded.outcome)
        assertEquals("CoreWeave", recorded.provider)
        assertEquals(10, recorded.promptTokens)
        assertEquals(false, recorded.isTestChat)
    }

    @Test
    fun `2 - a provider failure is captured`() {
        val repository = repo()
        val failingExchange = exchange(status = 500, isError = true)
        val fakeClient = ExchangeCapturingFake(failingExchange) { throw RuntimeException("boom") }
        val observable = ObservableLlmClient(fakeClient, repository, isTestChat = false)
        val req = request()

        assertTrue(runCatching { observable.generate(req) }.isFailure)

        val recorded = repository.findForTurn(req.requestId).single()
        assertEquals(LlmExchangeRepository.Outcome.PROVIDER_ERROR, recorded.outcome)
        assertEquals(500, recorded.httpStatusCode)
        assertEquals("java.lang.RuntimeException", recorded.errorClass)
    }

    @Test
    fun `3 - finish reason is captured`() {
        val repository = repo()
        val client = LlmClient {
            LlmResponse(content = "ok", metadata = mapOf("finish_reason" to "length"), providerExchange = exchange())
        }
        val observable = ObservableLlmClient(client, repository, isTestChat = false)
        val req = request()

        observable.generate(req)

        assertEquals("length", repository.findForTurn(req.requestId).single().finishReason)
    }

    @Test
    fun `4 - reasoning tokens are captured when supplied`() {
        val repository = repo()
        val client = LlmClient {
            LlmResponse(content = "ok", metadata = mapOf("reasoning_tokens" to "247"), providerExchange = exchange())
        }
        val observable = ObservableLlmClient(client, repository, isTestChat = false)
        val req = request()

        observable.generate(req)

        assertEquals(247, repository.findForTurn(req.requestId).single().reasoningTokens)
    }

    @Test
    fun `5 - malformed output is distinguishable from provider failure via markMalformed`() {
        val repository = repo()
        val client = LlmClient {
            LlmResponse(content = "ok", metadata = mapOf("finish_reason" to "stop"), providerExchange = exchange())
        }
        val observable = ObservableLlmClient(client, repository, isTestChat = false)
        val req = request(workload = "intent_discovery")
        observable.generate(req)
        assertEquals(LlmExchangeRepository.Outcome.SUCCESS, repository.findForTurn(req.requestId).single().outcome)

        val updated = repository.markMalformed(req.requestId, "intent_discovery")

        assertTrue(updated)
        assertEquals(LlmExchangeRepository.Outcome.MALFORMED, repository.findForTurn(req.requestId).single().outcome)
    }

    @Test
    fun `6 - budget exhaustion is distinguishable from normal completion`() {
        val repository = repo()
        val fakeClient = ExchangeCapturingFake(null) {
            throw OpenRouterBudgetExhaustionException(finishReason = "length", completionTokens = 600, reasoningTokens = 600, provider = "Wafer")
        }
        val observable = ObservableLlmClient(fakeClient, repository, isTestChat = false)
        val req = request()

        assertTrue(runCatching { observable.generate(req) }.isFailure)

        val recorded = repository.findForTurn(req.requestId).single()
        assertEquals(LlmExchangeRepository.Outcome.BUDGET_EXHAUSTION, recorded.outcome)
        assertEquals("Wafer", recorded.provider)
        assertEquals(600, recorded.reasoningTokens)
    }

    @Test
    fun `7 - diagnostics persistence does not block the response`() {
        val repository = repo()
        val client = LlmClient { LlmResponse(content = "ok", providerExchange = exchange()) }
        val observable = ObservableLlmClient(client, repository, isTestChat = false)

        val response = observable.generate(request())

        assertEquals("ok", response.content)
    }

    @Test
    fun `8 - a diagnostics persistence failure does not fail the response`() {
        val brokenRepository = LlmExchangeRepository(
            DatabaseFactory.connectInMemory(), // schema never initialized -> every insert fails
        )
        val client = LlmClient { LlmResponse(content = "ok", providerExchange = exchange()) }
        val observable = ObservableLlmClient(client, brokenRepository, isTestChat = false)

        val response = observable.generate(request())

        assertEquals("ok", response.content, "The response must succeed even though persistence failed")
    }

    @Test
    fun `9 - secrets are excluded from the persisted request`() {
        val repository = repo()
        val client = LlmClient {
            LlmResponse(
                content = "ok",
                providerExchange = ProviderExchange(
                    request = ProviderRequestDiagnostics("POST", "https://example", "test-model", mapOf("Content-Type" to "application/json"), "{}"),
                    response = ProviderResponseDiagnostics(200, "{}"),
                ),
            )
        }
        val observable = ObservableLlmClient(client, repository, isTestChat = false)
        val req = request()

        observable.generate(req)

        // The exchange's own request diagnostics never carry an Authorization
        // header (ProviderExchange's own contract, enforced at capture time in
        // OpenRouterLlmClient) — this test proves the repository doesn't
        // introduce a secret-bearing field of its own.
        val recorded = repository.findForTurn(req.requestId).single()
        assertTrue(!(recorded.requestBody ?: "").contains("Bearer"), "No API key material should ever reach the persisted request body")
    }

    @Test
    fun `10 - Test Chat exchanges are distinguishable from production`() {
        val repository = repo()
        val client = LlmClient { LlmResponse(content = "ok", providerExchange = exchange()) }
        val prodReq = request()
        val testReq = request()

        ObservableLlmClient(client, repository, isTestChat = false).generate(prodReq)
        ObservableLlmClient(client, repository, isTestChat = true).generate(testReq)

        assertEquals(false, repository.findForTurn(prodReq.requestId).single().isTestChat)
        assertEquals(true, repository.findForTurn(testReq.requestId).single().isTestChat)
    }

    @Test
    fun `11 - Task 8 - the selected skill is captured on a successful exchange`() {
        val repository = repo()
        val client = LlmClient { LlmResponse(content = "ok", providerExchange = exchange()) }
        val observable = ObservableLlmClient(client, repository, isTestChat = false)
        val req = request(skillKey = "flirting")

        observable.generate(req)

        assertEquals("flirting", repository.findForTurn(req.requestId).single().skillKey)
    }

    @Test
    fun `12 - Task 8 - a null skill (SkillSelection#None) is recorded as null, never guessed at`() {
        val repository = repo()
        val client = LlmClient { LlmResponse(content = "ok", providerExchange = exchange()) }
        val observable = ObservableLlmClient(client, repository, isTestChat = false)
        val req = request(skillKey = null)

        observable.generate(req)

        assertEquals(null, repository.findForTurn(req.requestId).single().skillKey)
    }

    @Test
    fun `13 - Task 8 - the selected skill is captured even on a failed exchange`() {
        val repository = repo()
        val fakeClient = ExchangeCapturingFake(exchange(status = 500, isError = true)) { throw RuntimeException("boom") }
        val observable = ObservableLlmClient(fakeClient, repository, isTestChat = false)
        val req = request(skillKey = "emotional_support")

        assertTrue(runCatching { observable.generate(req) }.isFailure)

        assertEquals("emotional_support", repository.findForTurn(req.requestId).single().skillKey)
    }

    /**
     * A minimal test double implementing both [LlmClient] and
     * [ExchangeCapturing] directly — proves ObservableLlmClient recovers a
     * failed call's raw exchange via the interface, not via a concrete
     * dependency on OpenRouterLlmClient.
     */
    private class ExchangeCapturingFake(
        override val lastExchange: ProviderExchange?,
        private val body: () -> LlmResponse,
    ) : LlmClient, ExchangeCapturing {
        override fun generate(request: GenerationRequest): LlmResponse = body()
    }
}
