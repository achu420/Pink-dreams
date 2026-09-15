package com.pinkdreams.visual.identity

import java.util.UUID

enum class ReferenceRole {
    FACE, FULL_BODY, BODY, HAIR, WARDROBE, STYLE, GENERAL_IDENTITY
}

enum class ReferenceStatus {
    UPLOADED, FINALIZED, ARCHIVED
}

enum class ReferenceSource {
    HUMAN_UPLOADED, GENERATED, IMPORTED
}

data class ReferenceImage(
    val id: UUID,
    val personaVisualVersionId: UUID,
    val storageKey: String,
    val contentType: String,
    val fileSize: Long,
    val width: Int? = null,
    val height: Int? = null,
    val checksum: String? = null,
    val role: ReferenceRole,
    val status: ReferenceStatus = ReferenceStatus.UPLOADED,
    val source: ReferenceSource,
    val notes: String? = null,
    val createdAt: java.time.LocalDateTime? = null,
    val finalizedAt: java.time.LocalDateTime? = null
) {
    fun validate(): ValidationResult {
        val errors = mutableListOf<String>()

        if (contentType.isBlank()) {
            errors.add("contentType is required")
        }

        if (fileSize <= 0) {
            errors.add("fileSize must be positive")
        }

        val validMimeTypes = setOf(
            "image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif", "image/bmp"
        )

        if (!validMimeTypes.contains(contentType.lowercase())) {
            errors.add("contentType must be one of: ${validMimeTypes.joinToString(", ")}")
        }

        if (width != null && width <= 0) {
            errors.add("width must be positive if provided")
        }

        if (height != null && height <= 0) {
            errors.add("height must be positive if provided")
        }

        if (errors.isNotEmpty()) {
            return ValidationResult(valid = false, errors = errors)
        }

        return ValidationResult(valid = true, errors = emptyList())
    }
}
