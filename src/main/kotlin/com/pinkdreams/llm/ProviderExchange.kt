package com.pinkdreams.llm

/**
 * Captures the exact HTTP request/response exchanged with an LLM provider,
 * recorded at the moment the request is actually sent — never reconstructed
 * afterward from GenerationRequest or ChatContext.
 *
 * requestHeaders MUST NOT contain Authorization or any API key value.
 */
data class ProviderRequestDiagnostics(
    val httpMethod: String,
    val endpoint: String,
    val model: String,
    val requestHeaders: Map<String, String>,
    val requestBody: String,
)

data class ProviderResponseDiagnostics(
    val statusCode: Int,
    val responseBody: String,
    val providerRequestId: String? = null,
    val isError: Boolean = false,
)

data class ProviderExchange(
    val request: ProviderRequestDiagnostics,
    val response: ProviderResponseDiagnostics,
)

/**
 * LLM Observability and Raw Exchange Capture phase: implemented by any
 * [LlmClient] that can expose the raw exchange of its most recent call even
 * when that call threw — this is how [com.pinkdreams.llm.observability.ObservableLlmClient]
 * recovers request/response bodies for a failed exchange without needing
 * every failure path to thread them through an exception.
 */
interface ExchangeCapturing {
    val lastExchange: ProviderExchange?
}
