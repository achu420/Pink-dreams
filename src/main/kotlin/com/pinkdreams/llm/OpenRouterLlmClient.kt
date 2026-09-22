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
    // null = say nothing, use the provider's default. false is the one value
    // this codebase actually sends, for classification-style side-channel
    // calls that don't need chain-of-thought — see GenerationConfig.reasoningEnabled.
    val reasoningEnabled: Boolean? = null,
    // Runtime Quality + Latency Verification phase finding: this field existed
    // on GenerationConfig but was never actually sent to the provider — every
    // call ran at the provider's default sampling regardless of what an admin
    // configured. Now wired through; see GenerationConfig.temperature.
    val temperature: Double? = null,
    // Make Intent Discovery Fast + Reliable phase — see GenerationConfig.jsonMode.
    val jsonMode: Boolean? = null,
    // Primary Generation Latency phase — see GenerationConfig.providerSort.
    val providerSort: String? = null,
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
    // The actual upstream host that served this specific request (e.g.
    // "CoreWeave", "Fireworks") — OpenRouter routes a single model id across
    // dozens of upstream providers; this is what the Primary Generation
    // Latency phase's investigation was keyed on. Previously never parsed.
    val provider: String? = null,
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
    @SerialName("completion_tokens_details")
    val completionTokensDetails: OpenRouterCompletionTokensDetails? = null,
)

@Serializable
data class OpenRouterCompletionTokensDetails(
    @SerialName("reasoning_tokens")
    val reasoningTokens: Int? = null,
)

/**
 * Live Intent Model Comparison phase: thrown instead of a plain
 * IllegalStateException when a reasoning model exhausts max_tokens on
 * reasoning before producing content — carries the same diagnostic fields
 * structurally so a caller (e.g. LlmIntentDiscovery) can log or measure
 * budget exhaustion precisely, rather than parsing them back out of a
 * formatted message string.
 */
class OpenRouterBudgetExhaustionException(
    val finishReason: String,
    val completionTokens: Int,
    val reasoningTokens: Int?,
    val provider: String? = null,
) : IllegalStateException(
    "OpenRouter returned no content (finish_reason=$finishReason, completionTokens=$completionTokens, " +
        "reasoningTokens=$reasoningTokens). For reasoning models this usually means max_tokens was exhausted " +
        "by reasoning before any content was produced.",
)

class OpenRouterLlmClient(
    private val apiKey: String,
    val endpoint: String = "https://openrouter.ai/api/v1/chat/completions",
    private val model: String = "deepseek/deepseek-v4-flash-0731",
    val maxOutputTokens: Int = 1024,
    val timeoutSeconds: Int = 60,
) : LlmClient, ExchangeCapturing {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The exact request/response exchanged with the provider on the most recent
     * [generate] call, successful or not. Captured at [sendRawRequest] — the same
     * point where the HTTP call is actually made — never reconstructed afterward.
     * Exposed for admin observability; callers must never render requestHeaders'
     * Authorization value since it is redacted at capture time, not display time.
     */
    @Volatile
    override var lastExchange: ProviderExchange? = null
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
            // the fix is a larger max_tokens for that call site — structured
            // so a caller can measure this precisely (see
            // OpenRouterBudgetExhaustionException).
            //
            // Task 25F fix 1: budget exhaustion is specifically "the provider
            // stopped because it ran out of completion budget", i.e.
            // finish_reason=length. Throwing it for ANY blank content mislabeled
            // a normal finish_reason=stop with an empty body as
            // BUDGET_EXHAUSTION in llm_exchanges (observed live), hiding a
            // genuinely different provider behavior behind a budget diagnosis.
            if (choice.finishReason.equals("length", ignoreCase = true)) {
                throw OpenRouterBudgetExhaustionException(
                    finishReason = choice.finishReason,
                    completionTokens = response.usage.completionTokens,
                    reasoningTokens = response.usage.completionTokensDetails?.reasoningTokens,
                    provider = response.provider,
                )
            }
            throw IllegalStateException(
                "OpenRouter returned a completed response with blank content " +
                    "(finish_reason=${choice.finishReason}, completionTokens=${response.usage.completionTokens}). " +
                    "This is not budget exhaustion: the provider stopped normally but produced no content.",
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
                "reasoning_tokens" to (response.usage.completionTokensDetails?.reasoningTokens ?: 0).toString(),
                // The actual upstream host — distinct from "provider" above,
                // which names the API client implementation ("openrouter"),
                // not the specific host OpenRouter routed this call to.
                "provider_upstream" to (response.provider ?: ""),
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
            reasoningEnabled = request.config.reasoningEnabled,
            temperature = request.config.temperature,
            jsonMode = request.config.jsonMode,
            providerSort = request.config.providerSort,
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
        if (request.reasoningEnabled != null) {
            sb.append(",\"reasoning\":{\"enabled\":").append(request.reasoningEnabled).append("}")
        }
        if (request.temperature != null) {
            sb.append(",\"temperature\":").append(request.temperature)
        }
        if (request.jsonMode == true) {
            sb.append(",\"response_format\":{\"type\":\"json_object\"}")
        }
        if (request.providerSort != null) {
            sb.append(",\"provider\":{\"sort\":\"").append(request.providerSort).append("\"}")
        }
        sb.append(",\"stream\":false}")
        return sb.toString()
    }
}
