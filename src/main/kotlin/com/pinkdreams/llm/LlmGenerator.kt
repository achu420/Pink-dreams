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
) : Generator {
    override fun generate(request: ChatRequest, context: ChatContext): StageResult<GenerationResponse> {
        return try {
            val generationRequest = GenerationRequest.from(request, context, config)
            val response = client.generate(generationRequest)
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
                ),
            )
        } catch (_: Exception) {
            StageResult.Failed(ErrorCode.GENERATION_FAILED)
        }
    }
}