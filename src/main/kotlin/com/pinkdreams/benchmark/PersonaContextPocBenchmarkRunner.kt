package com.pinkdreams.benchmark

import java.util.UUID
import java.time.LocalDateTime

/**
 * Benchmark runner: Execute A vs B comparison on realistic scenarios.
 * Focuses on empirical evidence: tokens, cost, latency, quality.
 * Does NOT modify production behavior.
 */
data class BenchmarkScenario(
    val scenarioId: UUID = UUID.randomUUID(),
    val skillKey: String,
    val category: String,
    val userMessage: String,
    val seedMessages: List<String> = emptyList(),
    val seedMemoryFacts: String? = null,
    val expectedPersonaFields: List<String> = emptyList(),
    val notes: String? = null,
)

data class BenchmarkExecution(
    val executionId: UUID = UUID.randomUUID(),
    val runId: UUID,
    val scenarioId: UUID,
    val variant: String,
    val skillKey: String,
    val assembledPersonaContext: String,
    val userMessage: String,
    val conversationEngineContent: String?,
    val lastMessagesContext: String?,
    val memoryContext: String?,
    val personaContextTokens: Int,
    val otherInputTokens: Int,
    val totalInputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val inputCost: Double = 0.0,
    val outputCost: Double = 0.0,
    val totalCost: Double = 0.0,
    val latencyMs: Long,
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val generatedResponse: String,
    val error: String? = null,
)

data class BenchmarkEvaluation(
    val evaluationId: UUID = UUID.randomUUID(),
    val executionId: UUID,
    val evaluator: String,
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

data class VariantSummary(
    val variant: String,
    val executionCount: Int,
    val personaTokensMean: Double,
    val personaTokensMedian: Int,
    val inputTokensMean: Double,
    val inputTokensMedian: Int,
    val outputTokensMean: Double,
    val totalTokensMean: Double,
    val totalTokensMedian: Int,
    val totalCostUsd: Double,
    val costPerRequest: Double,
    val latencyMsMean: Double,
    val latencyMsMedian: Long,
    val latencyMsP90: Long,
    val latencyMsP95: Long,
    val qualityMean: Double,
    val qualityMedian: Double,
    val errorRate: Double,
)

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

data class QualityMetrics(
    val skill: String,
    val scenarioCount: Int,
    val variantAMean: Double,
    val variantBMean: Double,
    val delta: Double,
)

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

data class BenchmarkResults(
    val runId: UUID,
    val totalScenarios: Int,
    val totalExecutions: Int,
    val executionsBySkill: Map<String, Int>,
    val variantA: VariantSummary,
    val variantB: VariantSummary,
    val comparison: ComparisonSummary,
    val qualityBySkill: Map<String, QualityMetrics>,
    val failureCases: List<FailureCase>,
    val restrictedLeakages: Int,
    val recommendedForProduction: Boolean,
    val findingsSummary: String,
)
