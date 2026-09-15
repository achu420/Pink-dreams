package com.pinkdreams.imaging.provider

import java.util.UUID

data class ReferenceInput(
    val referenceImageId: UUID,
    val role: String,
    val weight: Float = 1.0f,
)

data class GenerationRequest(
    val prompt: String,
    val references: List<ReferenceInput> = emptyList(),
    val candidateCount: Int = 1,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val aspectRatio: String? = null,
    val idempotencyKey: String,
    val clientMetadata: Map<String, String> = emptyMap(),
) {
    fun validate(): ValidationResult {
        val errors = mutableListOf<String>()

        if (prompt.isBlank()) errors.add("Prompt cannot be empty")
        if (candidateCount < 1) errors.add("Candidate count must be at least 1")
        if (candidateCount > 100) errors.add("Candidate count must be at most 100")
        if (idempotencyKey.isBlank()) errors.add("Idempotency key cannot be empty")

        if (widthPx != null && widthPx < 64) errors.add("Width must be at least 64px")
        if (heightPx != null && heightPx < 64) errors.add("Height must be at least 64px")
        if (widthPx != null && widthPx > 4096) errors.add("Width must be at most 4096px")
        if (heightPx != null && heightPx > 4096) errors.add("Height must be at most 4096px")

        return ValidationResult(errors.isEmpty(), errors)
    }

    data class ValidationResult(
        val valid: Boolean,
        val errors: List<String> = emptyList(),
    )
}
