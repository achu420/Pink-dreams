package com.pinkdreams.llm

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.GenerationResponse
import com.pinkdreams.chat.Generator
import com.pinkdreams.chat.StageResult
import com.pinkdreams.common.errors.ErrorCode

class LlmGenerator(
    private val client: LlmClient,
    private val config: GenerationConfig = GenerationConfig(),
    // Phase ADMIN-2: resolved PER REQUEST rather than captured at startup, so
    // an admin changing the model/temperature/max-tokens takes effect on the
    // next message instead of the next deploy. Defaults to the constructor
    // config, so every existing caller and test behaves exactly as before.
    private val configProvider: () -> GenerationConfig = { config },
) : Generator {
    override fun generate(request: ChatRequest, context: ChatContext): StageResult<GenerationResponse> {
        // A failure resolving admin settings must never fail generation: fall
        // back to the startup config, which is always valid.
        val config = runCatching { configProvider() }.getOrDefault(config)
        return try {
            val generationRequest = GenerationRequest.from(request, context, config)
            val response = client.generate(generationRequest)

            val providerExchangeDiagnostics = response.providerExchange?.let { exchange ->
                mapOf(
                    "httpMethod" to exchange.request.httpMethod,
                    "endpoint" to exchange.request.endpoint,
                    "model" to exchange.request.model,
                    "requestHeaders" to exchange.request.requestHeaders.entries.joinToString("; ") { "${it.key}: ${it.value}" },
                    "requestBody" to exchange.request.requestBody,
                    "responseStatusCode" to exchange.response.statusCode.toString(),
                    "responseBody" to exchange.response.responseBody,
                    "providerRequestId" to (exchange.response.providerRequestId ?: ""),
                    "isError" to exchange.response.isError.toString(),
                )
            }

            val diagnostics = com.pinkdreams.chat.LlmExecutionDiagnostics(
                contextBlocks = context.blocks,
                generationConfig = mapOf(
                    "model" to (config.model ?: "default"),
                    "temperature" to (config.temperature?.toString() ?: "default"),
                    "maxOutputTokens" to (config.maxOutputTokens?.toString() ?: "default"),
                ),
                llmResponseMetadata = response.metadata,
                providerExchange = providerExchangeDiagnostics,
            )

            StageResult.Succeeded(
                GenerationResponse(
                    content = response.content,
                    engineVersionId = generationRequest.engineVersionId,
                    personaCoreVersionId = generationRequest.personaCoreVersionId,
                    providerMetadata = buildMap {
                        response.provider?.let { put("provider", it) }
                        response.model?.let { put("model", it) }
                        putAll(response.metadata)
                    },
                    executionDiagnostics = diagnostics,
                ),
            )
        } catch (e: Exception) {
            // The OpenRouterLlmClient captures its request/response exchange onto
            // itself (with Authorization already redacted) before throwing on any
            // HTTP-level failure, independent of this catch block. Surface a summary
            // here for diagnosability without dumping full user-authored request/
            // response bodies into normal server logs. Note: some exceptions (e.g.
            // OpenRouterLlmClient's HTTP-error IllegalStateException) embed the raw
            // response body in their own message, so that message is truncated too.
            val exchangeSummary = (client as? OpenRouterLlmClient)?.lastExchange?.let { exchange ->
                "endpoint=${exchange.request.endpoint} model=${exchange.request.model} " +
                    "statusCode=${exchange.response.statusCode} isError=${exchange.response.isError} " +
                    "responseBodyLength=${exchange.response.responseBody.length}"
            } ?: "no provider exchange captured"
            val truncatedMessage = e.message?.take(MAX_LOGGED_EXCEPTION_MESSAGE_LENGTH)
            System.err.println(
                "LLM_GENERATION: Generation failed for requestId=${request.requestId} " +
                    "conversationId=${request.conversationId}: ${e.javaClass.simpleName}: $truncatedMessage | providerExchange: $exchangeSummary",
            )
            e.stackTrace.take(MAX_LOGGED_STACK_FRAMES).forEach { System.err.println("    at $it") }
            StageResult.Failed(ErrorCode.GENERATION_FAILED)
        }
    }

    companion object {
        private const val MAX_LOGGED_EXCEPTION_MESSAGE_LENGTH = 200
        private const val MAX_LOGGED_STACK_FRAMES = 10
    }
}