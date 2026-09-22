package com.pinkdreams.llm.observability

import com.pinkdreams.llm.ExchangeCapturing
import com.pinkdreams.llm.GenerationRequest
import com.pinkdreams.llm.LlmClient
import com.pinkdreams.llm.LlmResponse
import com.pinkdreams.llm.OpenRouterBudgetExhaustionException
import com.pinkdreams.llm.ProviderExchange
import com.pinkdreams.persistence.repositories.LlmExchangeRepository

/**
 * LLM Observability and Raw Exchange Capture phase.
 *
 * Wraps the ONE shared [LlmClient] instance once, in ChatEngineFactory.build()
 * — every existing call site (Intent, primary generation, memory extraction,
 * continuity, memory-engine maintenance) keeps calling `client.generate(...)`
 * exactly as before; nothing about how a workload invokes the client changes.
 * This is a decorator, not a new pipeline stage or classifier.
 *
 * Records every exchange — success, budget exhaustion, provider error, or any
 * other exception — before re-throwing/returning exactly what the delegate
 * produced, so behavior is byte-identical to not having this wrapper at all
 * except for the side effect of a persisted row. Persistence failures are
 * swallowed and logged; they must never fail the user-facing turn.
 */
class ObservableLlmClient(
    private val delegate: LlmClient,
    private val exchangeRepository: LlmExchangeRepository,
    private val isTestChat: Boolean,
) : LlmClient {

    override fun generate(request: GenerationRequest): LlmResponse {
        val workload = request.config.workload ?: "unknown"
        val start = System.nanoTime()
        try {
            val response = delegate.generate(request)
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            persistSafely {
                val exchange = response.providerExchange
                val finishReason = response.metadata["finish_reason"]
                // Task 25F fix 1: a response the provider cut off at the
                // completion-token limit is not a success, even though the HTTP
                // call succeeded and content came back. Recording it as SUCCESS
                // made silent downstream data loss invisible (a truncated
                // memory_extraction JSON body parses to nothing and is discarded
                // as "nothing to remember"). This is a classification change
                // only: the delegate's response is still returned untouched
                // below, no retry is attempted and no token budget changes.
                val truncated = finishReason.equals("length", ignoreCase = true)
                exchangeRepository.record(
                    LlmExchangeRepository.RecordInput(
                        turnRequestId = request.requestId,
                        conversationId = request.conversationId,
                        workload = workload,
                        isTestChat = isTestChat,
                        model = response.model ?: request.config.model,
                        provider = response.metadata["provider_upstream"]?.takeIf { it.isNotBlank() },
                        latencyMs = latencyMs,
                        promptTokens = response.metadata["prompt_tokens"]?.toIntOrNull(),
                        completionTokens = response.metadata["completion_tokens"]?.toIntOrNull(),
                        reasoningTokens = response.metadata["reasoning_tokens"]?.toIntOrNull(),
                        totalTokens = response.metadata["total_tokens"]?.toIntOrNull(),
                        finishReason = finishReason,
                        httpStatusCode = exchange?.response?.statusCode,
                        outcome = if (truncated) {
                            LlmExchangeRepository.Outcome.TRUNCATED
                        } else {
                            LlmExchangeRepository.Outcome.SUCCESS
                        },
                        requestBody = exchange?.request?.requestBody,
                        responseBody = exchange?.response?.responseBody,
                        skillKey = request.skillKey,
                    ),
                )
            }
            return response
        } catch (e: OpenRouterBudgetExhaustionException) {
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            val lastExchange = lastExchangeFrom(delegate)
            persistSafely {
                exchangeRepository.record(
                    LlmExchangeRepository.RecordInput(
                        turnRequestId = request.requestId,
                        conversationId = request.conversationId,
                        workload = workload,
                        isTestChat = isTestChat,
                        model = request.config.model,
                        provider = e.provider,
                        latencyMs = latencyMs,
                        completionTokens = e.completionTokens,
                        reasoningTokens = e.reasoningTokens,
                        finishReason = e.finishReason,
                        httpStatusCode = lastExchange?.response?.statusCode,
                        outcome = LlmExchangeRepository.Outcome.BUDGET_EXHAUSTION,
                        errorClass = e.javaClass.name,
                        errorMessage = e.message,
                        requestBody = lastExchange?.request?.requestBody,
                        responseBody = lastExchange?.response?.responseBody,
                        skillKey = request.skillKey,
                    ),
                )
            }
            throw e
        } catch (e: Exception) {
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            val lastExchange = lastExchangeFrom(delegate)
            val outcome = if (lastExchange?.response?.isError == true) {
                LlmExchangeRepository.Outcome.PROVIDER_ERROR
            } else {
                LlmExchangeRepository.Outcome.EXCEPTION
            }
            persistSafely {
                exchangeRepository.record(
                    LlmExchangeRepository.RecordInput(
                        turnRequestId = request.requestId,
                        conversationId = request.conversationId,
                        workload = workload,
                        isTestChat = isTestChat,
                        model = request.config.model,
                        provider = null,
                        latencyMs = latencyMs,
                        httpStatusCode = lastExchange?.response?.statusCode,
                        outcome = outcome,
                        errorClass = e.javaClass.name,
                        errorMessage = e.message,
                        requestBody = lastExchange?.request?.requestBody,
                        responseBody = lastExchange?.response?.responseBody,
                        skillKey = request.skillKey,
                    ),
                )
            }
            throw e
        }
    }

    private fun lastExchangeFrom(client: LlmClient): ProviderExchange? =
        (client as? ExchangeCapturing)?.lastExchange

    private inline fun persistSafely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // Task 2 requirement: diagnostics persistence must never fail the
            // user-facing turn. Log and move on.
            System.err.println("LLM_EXCHANGE_PERSIST_FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
