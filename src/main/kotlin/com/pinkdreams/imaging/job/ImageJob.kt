package com.pinkdreams.imaging.job

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

data class ImageJob(
    val id: UUID,
    val personaVisualVersionId: UUID,
    val jobType: ImageJobType,
    val status: ImageJobStatus,
    val idempotencyKey: String,
    val requestPayload: String,
    val attemptCount: Int,
    val maxAttempts: Int,
    val availableAt: LocalDateTime,
    val claimedByWorker: String?,
    val claimedAt: LocalDateTime?,
    val lastError: String?,
    val createdAt: LocalDateTime,
    val startedAt: LocalDateTime?,
    val completedAt: LocalDateTime?,
    // V016: provider cost capture — null until the provider starts returning cost data.
    // providerCostSource is written as "UNAVAILABLE" for OpenRouter image jobs since
    // the image API does not expose per-request cost in its response body.
    val providerCostRaw: BigDecimal? = null,
    val providerCostCurrency: String? = null,
    val providerCostSource: String? = null,
) {
    fun validate(): ValidationResult {
        val errors = mutableListOf<String>()

        if (attemptCount < 0) errors.add("attemptCount cannot be negative")
        if (maxAttempts <= 0) errors.add("maxAttempts must be positive")
        if (attemptCount > maxAttempts) errors.add("attemptCount cannot exceed maxAttempts")

        if (status.isTerminal() && !status.isTerminal()) errors.add("Terminal status cannot have incomplete timestamps")

        when (status) {
            ImageJobStatus.RUNNING -> {
                if (claimedByWorker == null) errors.add("RUNNING job must have claimedByWorker")
                if (claimedAt == null) errors.add("RUNNING job must have claimedAt")
            }
            ImageJobStatus.SUCCEEDED -> {
                if (completedAt == null) errors.add("SUCCEEDED job must have completedAt")
            }
            ImageJobStatus.FAILED -> {
                if (lastError == null) errors.add("FAILED job must have lastError")
                if (completedAt == null) errors.add("FAILED job must have completedAt")
            }
            else -> {}
        }

        return ValidationResult(errors.isEmpty(), errors)
    }

    data class ValidationResult(
        val valid: Boolean,
        val errors: List<String> = emptyList(),
    )
}
