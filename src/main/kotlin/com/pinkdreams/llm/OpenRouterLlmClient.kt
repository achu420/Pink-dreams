package com.pinkdreams.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

@Serializable
data class OpenRouterRequest(
    val model: String,
    val messages: List<OpenRouterMessage>,
    @SerialName("max_tokens")
    val maxTokens: Int,
    val stream: Boolean = false,
)

@Serializable
data class OpenRouterMessage(
    val role: String,
    val content: String,
)

@Serializable
data class OpenRouterResponse(
    val id: String,
    val model: String,
    val choices: List<OpenRouterChoice>,
    val usage: OpenRouterUsage,
)

@Serializable
data class OpenRouterChoice(
    @SerialName("finish_reason")
    val finishReason: String,
    val message: OpenRouterMessage,
)

@Serializable
data class OpenRouterUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("completion_tokens")
    val completionTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int,
)

class OpenRouterLlmClient(
    private val apiKey: String,
    val endpoint: String = "https://openrouter.ai/api/v1/chat/completions",
    private val model: String = "deepseek/deepseek-v4.1-flash",
    val maxOutputTokens: Int = 1024,
    val timeoutSeconds: Int = 60,
) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true }

    override fun generate(request: GenerationRequest): LlmResponse {
        return try {
            val openRouterRequest = buildOpenRouterRequest(request)
            val response = makeRequest(openRouterRequest)

            val choice = response.choices.firstOrNull()
                ?: throw IllegalStateException("No choices in OpenRouter response")

            val content = choice.message.content
            if (content.isBlank()) {
                throw IllegalStateException("Empty content in OpenRouter response")
            }

            LlmResponse(
                content = content,
                provider = "openrouter",
                model = response.model,
                metadata = mapOf(
                    "finish_reason" to choice.finishReason,
                    "prompt_tokens" to response.usage.promptTokens.toString(),
                    "completion_tokens" to response.usage.completionTokens.toString(),
                    "total_tokens" to response.usage.totalTokens.toString(),
                ),
            )
        } catch (e: Exception) {
            throw e
        }
    }

    private fun buildOpenRouterRequest(request: GenerationRequest): OpenRouterRequest {
        val messages = request.context.blocks.map { block ->
            OpenRouterMessage(
                role = block.role,
                content = block.content,
            )
        }

        return OpenRouterRequest(
            model = request.config.model ?: model,
            messages = messages,
            maxTokens = request.config.maxOutputTokens ?: maxOutputTokens,
            stream = false,
        )
    }

    private fun makeRequest(request: OpenRouterRequest): OpenRouterResponse {
        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = TimeUnit.SECONDS.toMillis(5).toInt()
            connection.readTimeout = TimeUnit.SECONDS.toMillis(30).toInt()

            val requestBody = buildOpenRouterRequestJson(request)
            connection.doOutput = true
            connection.outputStream.use { it.write(requestBody.toByteArray()) }

            val statusCode = connection.responseCode

            if (statusCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                throw IllegalStateException("OpenRouter request failed with status $statusCode: $errorBody")
            }

            val responseBody = connection.inputStream.bufferedReader().readText()
            return json.decodeFromString<OpenRouterResponse>(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildOpenRouterRequestJson(request: OpenRouterRequest): String {
        val sb = StringBuilder()
        sb.append("{\"model\":\"").append(request.model).append("\"")
        sb.append(",\"messages\":[")
        sb.append(request.messages.joinToString(",") { msg ->
            val escapedContent = msg.content
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
            "{\"role\":\"${msg.role}\",\"content\":\"$escapedContent\"}"
        })
        sb.append("]")
        sb.append(",\"max_tokens\":").append(request.maxTokens)
        sb.append(",\"stream\":false}")
        return sb.toString()
    }
}
