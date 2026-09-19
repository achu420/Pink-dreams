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
    val message: OpenRouterResponseMessage,
)

/**
 * Response-side message. Deliberately SEPARATE from the request-side
 * [OpenRouterMessage] because `content` is genuinely nullable on responses:
 * reasoning-capable models (e.g. deepseek-v*-flash) return
 * `{"content": null, "reasoning": "...", "finish_reason": "length"}` whenever
 * reasoning tokens exhaust max_tokens before any final content is emitted.
 *
 * Declaring it non-null previously made kotlinx throw on those responses, which
 * the client swallowed into a generic "Failed to parse OpenRouter response" —
 * the intermittent, long-standing failure seen across memory extraction,
 * continuity, intent discovery, and (rarely) main generation. The frequency
 * tracked the per-call token budget exactly: the smaller the budget, the more
 * often reasoning consumed all of it.
 */
@Serializable
data class OpenRouterResponseMessage(
    val role: String,
    val content: String? = null,
    val reasoning: String? = null,
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
    private val model: String = "deepseek/deepseek-v4-flash-0731",
    val maxOutputTokens: Int = 1024,
    val timeoutSeconds: Int = 60,
) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The exact request/response exchanged with the provider on the most recent
     * [generate] call, successful or not. Captured at [sendRawRequest] — the same
     * point where the HTTP call is actually made — never reconstructed afterward.
     * Exposed for admin observability; callers must never render requestHeaders'
     * Authorization value since it is redacted at capture time, not display time.
     */
    @Volatile
    var lastExchange: ProviderExchange? = null
        private set

    override fun generate(request: GenerationRequest): LlmResponse {
        val openRouterRequest = buildOpenRouterRequest(request)
        val requestBodyJson = buildOpenRouterRequestJson(openRouterRequest)

        val (statusCode, responseBodyText) = sendRawRequest(requestBodyJson)
        val isError = statusCode !in 200..299

        var parsedResponse: OpenRouterResponse? = null
        var parseFailure: String? = null
        if (!isError) {
            parsedResponse = try {
                json.decodeFromString<OpenRouterResponse>(responseBodyText)
            } catch (e: Exception) {
                // Keep the real reason: swallowing it is what made this class of
                // failure undiagnosable from logs for several phases.
                parseFailure = "${e.javaClass.simpleName}: ${e.message}"
                null
            }
        }

        val exchange = ProviderExchange(
            request = ProviderRequestDiagnostics(
                httpMethod = "POST",
                endpoint = endpoint,
                model = openRouterRequest.model,
                requestHeaders = mapOf(
                    "Content-Type" to "application/json",
                    "Authorization" to "[REDACTED]",
                ),
                requestBody = requestBodyJson,
            ),
            response = ProviderResponseDiagnostics(
                statusCode = statusCode,
                responseBody = responseBodyText,
                providerRequestId = parsedResponse?.id,
                isError = isError,
            ),
        )
        lastExchange = exchange

        if (isError) {
            throw IllegalStateException("OpenRouter request failed with status $statusCode: $responseBodyText")
        }

        val response = parsedResponse
            ?: throw IllegalStateException("Failed to parse OpenRouter response: ${parseFailure ?: "unknown reason"}")
        val choice = response.choices.firstOrNull()
            ?: throw IllegalStateException("No choices in OpenRouter response")

        val content = choice.message.content
        if (content.isNullOrBlank()) {
            // Name the actual cause instead of a generic "empty content": a
            // reasoning model that spent the whole budget thinking reports
            // finish_reason=length with reasoning present but no content, and
            // the fix is a larger max_tokens for that call site — which this
            // message states outright so it is diagnosable from logs alone.
            val reasoningTokens = choice.message.reasoning?.length ?: 0
            throw IllegalStateException(
                "OpenRouter returned no content (finish_reason=${choice.finishReason}, " +
                    "reasoningChars=$reasoningTokens, completionTokens=${response.usage.completionTokens}). " +
                    "For reasoning models this usually means max_tokens was exhausted by reasoning before any content was produced.",
            )
        }

        return LlmResponse(
            content = content,
            provider = "openrouter",
            model = response.model,
            metadata = mapOf(
                "finish_reason" to choice.finishReason,
                "prompt_tokens" to response.usage.promptTokens.toString(),
                "completion_tokens" to response.usage.completionTokens.toString(),
                "total_tokens" to response.usage.totalTokens.toString(),
            ),
            providerExchange = exchange,
        )
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

    /**
     * Sends the already-serialized request body exactly as-is and returns the raw
     * HTTP status and body text, for both success and error responses. This is the
     * single point where bytes actually leave the process — diagnostics captured
     * here reflect reality, not a reconstruction.
     */
    private fun sendRawRequest(requestBodyJson: String): Pair<Int, String> {
        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = TimeUnit.SECONDS.toMillis(5).toInt()
            connection.readTimeout = TimeUnit.SECONDS.toMillis(30).toInt()

            connection.doOutput = true
            connection.outputStream.use { it.write(requestBodyJson.toByteArray()) }

            val statusCode = connection.responseCode
            val bodyText = if (statusCode in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            }
            return statusCode to bodyText
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
