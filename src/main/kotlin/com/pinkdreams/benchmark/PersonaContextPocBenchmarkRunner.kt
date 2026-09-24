package com.pinkdreams.benchmark

import kotlinx.serialization.Serializable
import java.util.UUID
import java.time.LocalDateTime
import java.time.Instant

/**
 * Benchmark runner: Execute A vs B comparison on realistic scenarios.
 * Focuses on empirical evidence: tokens, cost, latency, quality.
 * Does NOT modify production behavior.
 */
@Serializable
data class BenchmarkScenario(
    val scenarioId: UUID = UUID.randomUUID(),
    val skillKey: String, // general_chat, friendship, romantic_conversation, romantic_intimacy, foreplay, about, perspective_of
    val category: String, // A-H for tracking
    val userMessage: String,
    val seedMessages: List<String> = emptyList(), // last 10 messages context
    val seedMemoryFacts: String? = null, // user/persona memory to inject
    val expectedPersonaFields: List<String> = emptyList(), // which persona fields should be relevant
    val notes: String? = null,
)

@Serializable
data class BenchmarkExecution(
    val executionId: UUID = UUID.randomUUID(),
    val runId: UUID,
    val scenarioId: UUID,
    val variant: String, // "A" = full persona, "B" = selective persona
    val skillKey: String,

    // Context captured
    val assembledPersonaContext: String, // actual persona text sent to LLM
    val userMessage: String,
    val conversationEngineContent: String?,
    val lastMessagesContext: String?,
    val memoryContext: String?,

    // Token metrics (actual from LLM if available, else estimate)
    val personaContextTokens: Int,
    val otherInputTokens: Int, // engine + skill + memories + messages
    val totalInputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,

    // Cost (USD)
    val inputCost: Double = 0.0,
    val outputCost: Double = 0.0,
    val totalCost: Double = 0.0,

    // Latency
    val latencyMs: Long,
    val timestamp: LocalDateTime = LocalDateTime.now(),

    // Response
    val generatedResponse: String,
    val error: String? = null,
)

@Serializable
data class BenchmarkEvaluation(
    val evaluationId: UUID = UUID.randomUUID(),
    val executionId: UUID,
    val evaluator: String,

    // Quality scores (0-5)
    val personaFidelity: Int,
    val factualAccuracy: Int,
    val skillAdherence: Int,
    val relationshipContinuity: Int,
    val narrativeContinuity: Int,
    val naturalness: Int,
    val contextPrecision: Int,
    val restrictedLeakage: Boolean,
    val notes: String? = null,
    val evaluatedAt: LocalDateTime = LocalDateTime.now(),
)

@Serializable
data class BenchmarkResults(
    val runId: UUID,
    val totalScenarios: Int,
    val totalExecutions: Int,
    val executionsBySkill: Map<String, Int>,

    // Per-variant aggregates
    val variantA: VariantSummary,
    val variantB: VariantSummary,
    val comparison: ComparisonSummary,

    // Quality findings
    val qualityBySkill: Map<String, QualityMetrics>,
    val failureCases: List<FailureCase>,
    val restrictedLeakages: Int,

    // Conclusion
    val recommendedForProduction: Boolean,
    val findingsSummary: String,
)

@Serializable
data class VariantSummary(
    val variant: String,
    val executionCount: Int,

    // Tokens
    val personaTokensMean: Double,
    val personaTokensMedian: Int,
    val inputTokensMean: Double,
    val inputTokensMedian: Int,
    val outputTokensMean: Double,
    val totalTokensMean: Double,
    val totalTokensMedian: Int,

    // Cost (USD)
    val totalCostUsd: Double,
    val costPerRequest: Double,

    // Latency (ms)
    val latencyMsMean: Double,
    val latencyMsMedian: Long,
    val latencyMsP90: Long,
    val latencyMsP95: Long,

    // Quality
    val qualityMean: Double,
    val qualityMedian: Double,
    val errorRate: Double,
)

@Serializable
data class ComparisonSummary(
    val personaTokenReductionPercent: Double,
    val inputTokenReductionPercent: Double,
    val totalTokenReductionPercent: Double,
    val costReductionPercent: Double,
    val latencyDeltaPercent: Double,
    val qualityDeltaPercent: Double,

    val variantBBetter: Boolean,
    val qualityDegraded: Boolean,
    val restrictedLeakageDetected: Boolean,
)

@Serializable
data class QualityMetrics(
    val skill: String,
    val scenarioCount: Int,
    val variantAMean: Double,
    val variantBMean: Double,
    val delta: Double,
)

@Serializable
data class FailureCase(
    val executionId: UUID,
    val scenarioId: UUID,
    val variant: String,
    val skill: String,
    val userMessage: String,
    val problem: String,
    val expectedFields: List<String>,
    val missingFields: List<String>,
)
