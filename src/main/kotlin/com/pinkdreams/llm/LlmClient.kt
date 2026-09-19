package com.pinkdreams.llm

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import java.util.UUID

data class GenerationConfig(
    val model: String? = null,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
)

data class GenerationRequest(
    val requestId: UUID,
    val userId: UUID,
    val conversationId: UUID,
    val personaId: UUID,
    val engineVersionId: UUID,
    val personaCoreVersionId: UUID,
    val context: ChatContext,
    val config: GenerationConfig,
) {
    companion object {
        fun from(request: ChatRequest, context: ChatContext, config: GenerationConfig): GenerationRequest {
            val engineVersionId = context.engineVersionId
                ?: throw IllegalArgumentException("Context is missing the engine version ID")
            val personaCoreVersionId = context.personaCoreVersionId
                ?: throw IllegalArgumentException("Context is missing the persona core version ID")
            return GenerationRequest(
                requestId = request.requestId,
                userId = request.userId,
                conversationId = request.conversationId,
                personaId = request.personaId,
                engineVersionId = engineVersionId,
                personaCoreVersionId = personaCoreVersionId,
                context = context,
                config = config,
            )
        }
    }
}

data class LlmResponse(
    val content: String,
    val provider: String? = null,
    val model: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val providerExchange: ProviderExchange? = null,
)

fun interface LlmClient {
    fun generate(request: GenerationRequest): LlmResponse
}

class FakeLlmClient(
    private val response: LlmResponse? = null,
    private val failure: RuntimeException? = null,
) : LlmClient {
    var lastRequest: GenerationRequest? = null
        private set

    override fun generate(request: GenerationRequest): LlmResponse {
        lastRequest = request
        failure?.let { throw it }
        return response ?: LlmResponse(content = "fake response", provider = "fake")
    }
}