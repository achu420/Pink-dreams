package com.pinkdreams.chat

import com.pinkdreams.config.LlmConfig
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.time.LocalDateTime
import java.util.*
import kotlin.test.Test

/**
 * LIVE OPENROUTER BENCHMARK — Real API calls, one scenario at a time.
 *
 * Execution model:
 * 1. Load scenario
 * 2. Execute Variant A → capture response, tokens, latency, cost
 * 3. Execute Variant B → capture response, tokens, latency, cost
 * 4. Execute Variant C → capture response, tokens, latency, cost
 * 5. Write results to CSV
 * 6. Move to next scenario
 *
 * This way you can monitor progress, see actual responses, verify quality in real-time.
 *
 * Execution:
 *   cd "E:\Projects\Pink dreams"
 *   . .\run-local.secrets.ps1
 *   ./gradlew test --tests "PersonaContextPocLiveRealBenchmark" -i
 */
class PersonaContextPocLiveRealBenchmark {

    data class ExecutionResult(
        val scenarioId: UUID,
        val skillKey: String,
        val userMessage: String,
        val variant: Char,
        val inputTokens: Int,
        val outputTokens: Int,
        val totalTokens: Int,
        val costUsd: Double,
        val latencyMs: Long,
        val generatedResponse: String,
        val status: String = "SUCCESS",
        val error: String? = null,
    )

    @Test
    fun `execute live openrouter benchmark - real api calls`() {
        println("\n════════════════════════════════════════════════════════════════")
        println("LIVE OPENROUTER BENCHMARK — REAL API CALLS")
        println("Execution model: 1 scenario → all 3 variants → verify → next")
        println("════════════════════════════════════════════════════════════════\n")

        // Try multiple ways to get the API key
        val apiKeyEnv = System.getenv("OPENROUTER_API_KEY")
        val apiKeyProp = System.getProperty("com.pinkdreams.openrouter.api.key")
        println("API Key sources:")
        println("  Environment: ${if (apiKeyEnv?.isNotEmpty() == true) "✓ found (${apiKeyEnv.length} chars)" else "✗ not set"}")
        println("  System property: ${if (apiKeyProp?.isNotEmpty() == true) "✓ found (${apiKeyProp.length} chars)" else "✗ not set"}")

        val llmConfig = LlmConfig.from(com.pinkdreams.config.AppConfig.load())
        println("\n✓ LLM Config loaded")
        println("  Endpoint: ${llmConfig.endpoint}")
        println("  Model: ${llmConfig.model}")
        println("  API Key: ${if (llmConfig.apiKey.isNotEmpty() && !llmConfig.apiKey.startsWith("test-key")) "✓ loaded (${llmConfig.apiKey.length} chars)" else "✗ MISSING OR TEST KEY"}")

        if (llmConfig.apiKey == "test-key-for-fake-llm" || llmConfig.apiKey.isEmpty()) {
            println("\n⚠ API key issue detected - will proceed and observe error messages")
            println("  Env OPENROUTER_API_KEY: ${apiKeyEnv?.take(20)}...")
            println("  System property: ${apiKeyProp?.take(20)}...")
        }
        println()

        val scenarios = PersonaContextPocBenchmarkRunner.createBenchmarkScenarios()
        println("✓ ${scenarios.size} scenarios loaded")
        println()

        val httpClient = HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = 300_000 // 5 minutes
                connectTimeoutMillis = 300_000 // 5 minutes
                socketTimeoutMillis = 300_000 // 5 minutes
            }
        }
        val allResults = mutableListOf<ExecutionResult>()
        val startTime = System.currentTimeMillis()

        try {
            scenarios.forEachIndexed { index, scenario ->
                println("─".repeat(70))
                println("[${index + 1}/${scenarios.size}] ${scenario.skillKey.uppercase()} — ${scenario.category}")
                println("User: ${scenario.userMessage.take(60)}...")
                println()

                val scenarioResults = mutableListOf<ExecutionResult>()

                // Execute all 3 variants for this scenario
                for (variant in listOf('A', 'B', 'C')) {
                    try {
                        print("  Variant $variant... ")

                        // Build system prompt based on variant
                        val systemPrompt = buildSystemPrompt(scenario, variant)

                        // Make REAL OpenRouter API call
                        val callStart = System.currentTimeMillis()
                        val response = runBlocking {
                            makeOpenRouterApiCall(
                                httpClient,
                                llmConfig,
                                systemPrompt,
                                scenario.userMessage
                            )
                        }
                        val callLatency = System.currentTimeMillis() - callStart

                        // Parse response
                        val generatedText = try {
                            response["choices"]?.jsonArray?.get(0)
                                ?.jsonObject?.get("message")?.jsonObject?.get("content")
                                ?.jsonPrimitive?.content ?: throw Exception("No content in response structure")
                        } catch (e: Exception) {
                            throw Exception("Failed to parse response content: ${e.message}\nResponse: $response")
                        }

                        val usage = response["usage"]?.jsonObject
                        val inputTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.int ?: 0
                        val outputTokens = usage?.get("completion_tokens")?.jsonPrimitive?.int ?: 0
                        val totalTokens = inputTokens + outputTokens

                        val costUsd = calculateCost(llmConfig.model, inputTokens, outputTokens)

                        val result = ExecutionResult(
                            scenarioId = scenario.id,
                            skillKey = scenario.skillKey,
                            userMessage = scenario.userMessage,
                            variant = variant,
                            inputTokens = inputTokens,
                            outputTokens = outputTokens,
                            totalTokens = totalTokens,
                            costUsd = costUsd,
                            latencyMs = callLatency,
                            generatedResponse = generatedText,
                        )

                        scenarioResults.add(result)
                        allResults.add(result)

                        println("✓ ${callLatency}ms | ${inputTokens}→${outputTokens} tokens | \$${String.format("%.4f", costUsd)}")

                        // Pause between calls to avoid rate limiting
                        Thread.sleep(1500)

                    } catch (e: Exception) {
                        val errorMsg = e.message ?: "Unknown error"
                        println("✗ ERROR: $errorMsg")
                        allResults.add(
                            ExecutionResult(
                                scenarioId = scenario.id,
                                skillKey = scenario.skillKey,
                                userMessage = scenario.userMessage,
                                variant = variant,
                                inputTokens = 0,
                                outputTokens = 0,
                                totalTokens = 0,
                                costUsd = 0.0,
                                latencyMs = 0,
                                generatedResponse = "",
                                status = "FAILED",
                                error = errorMsg.take(500),
                            )
                        )
                    }
                }

                // Show responses for this scenario
                println()
                scenarioResults.forEach { result ->
                    println("  Variant ${result.variant}: \"${result.generatedResponse.take(60)}...\"")
                }
                println()
            }
        } finally {
            httpClient.close()
        }

        println("─".repeat(70))
        val elapsedSec = (System.currentTimeMillis() - startTime) / 1000
        val elapsedMin = elapsedSec / 60
        println()
        println("✓ COMPLETE")
        println("  Time: ${elapsedMin}m ${elapsedSec % 60}s")
        println("  Executions: ${allResults.size}")
        println("  Successful: ${allResults.count { it.status == "SUCCESS" }}")
        println("  Total cost: \$${String.format("%.2f", allResults.sumOf { it.costUsd })}")
        println()

        // Save results
        saveCsv(allResults)

        println("════════════════════════════════════════════════════════════════")
        println("BENCHMARK COMPLETE — REAL MEASURED DATA")
        println("════════════════════════════════════════════════════════════════")
    }

    private fun buildSystemPrompt(
        scenario: PersonaContextPocBenchmarkRunner.TestScenario,
        variant: Char,
    ): String {
        // Realistic system prompts for each variant
        return when (variant) {
            'A' -> """You are Simran, a 27-year-old architect living in Pune.
You are direct, independent-minded, and value authenticity in conversations.
You work at a small studio in Baner on residential redesigns.
You're planning a trip to Ladakh in October.
You live in Kothrud with your flatmate Ritu.
You speak mostly in Hinglish with a tu-tum casual tone.
You don't have patience for games or pretense in relationships.
You prefer meaningful work over pure career ambition.
You enjoy traveling and personal growth.

Respond naturally and authentically to the user's message while staying true to your character."""

            'B' -> """You are Simran, 27, an architect in Pune. You're direct and value authenticity.
Respond to the user's message naturally."""

            'C' -> """You are Simran, 27, an architect in Pune.
You're direct, independent, and value authentic connections.
You're planning a Ladakh trip soon and working on a restaurant redesign.
You live with your flatmate Ritu in Kothrud.

Respond naturally to the user's message while staying true to your character."""

            else -> ""
        }
    }

    private suspend fun makeOpenRouterApiCall(
        client: HttpClient,
        config: LlmConfig,
        systemPrompt: String,
        userMessage: String,
    ): JsonObject {
        val requestBody = buildJsonObject {
            put("model", config.model)
            put("temperature", 0.8)
            put("max_tokens", config.maxOutputTokens)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", userMessage)
                }
            }
        }

        val response = client.post(config.endpoint) {
            header("Authorization", "Bearer ${config.apiKey}")
            header("Content-Type", "application/json")
            setBody(requestBody.toString())
        }

        val responseText = response.bodyAsText()
        val parsedResponse = Json.parseToJsonElement(responseText).jsonObject

        // Check for error in response
        if (parsedResponse.containsKey("error")) {
            val error = parsedResponse["error"]?.jsonObject
            val errorMsg = error?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
            throw Exception("OpenRouter API Error: $errorMsg (HTTP ${response.status.value})")
        }

        return parsedResponse
    }

    private fun calculateCost(model: String, inputTokens: Int, outputTokens: Int): Double {
        // DeepSeek pricing
        return (inputTokens * 0.00014) + (outputTokens * 0.00042)
    }

    private fun saveCsv(results: List<ExecutionResult>) {
        val header = "scenario_id,skill_key,user_message,variant,input_tokens,output_tokens,total_tokens,cost_usd,latency_ms,generated_response,status,error\n"
        val rows = results.map { result ->
            listOf(
                result.scenarioId,
                result.skillKey,
                "\"${result.userMessage.replace("\"", "\"\"")}\"",
                result.variant,
                result.inputTokens,
                result.outputTokens,
                result.totalTokens,
                "%.4f".format(result.costUsd),
                result.latencyMs,
                "\"${result.generatedResponse.replace("\"", "\"\"")}\"",
                result.status,
                "\"${(result.error ?: "").replace("\"", "\"\"")}\"",
            ).joinToString(",")
        }.joinToString("\n")

        val csv = header + rows
        File("persona-poc-benchmark-LIVE-REAL.csv").writeText(csv)
        println("✓ Saved: persona-poc-benchmark-LIVE-REAL.csv")
    }
}
