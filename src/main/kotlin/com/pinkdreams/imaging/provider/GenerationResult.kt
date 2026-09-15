package com.pinkdreams.imaging.provider

import java.util.UUID

data class GeneratedCandidate(
    val id: UUID,
    val imageData: ByteArray? = null,
    val imageUrl: String? = null,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val checksum: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GeneratedCandidate) return false
        if (id != other.id) return false
        if (imageData != null && !imageData.contentEquals(other.imageData)) return false
        if (imageUrl != other.imageUrl) return false
        if (widthPx != other.widthPx) return false
        if (heightPx != other.heightPx) return false
        if (checksum != other.checksum) return false
        if (metadata != other.metadata) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (imageData?.contentHashCode() ?: 0)
        result = 31 * result + (imageUrl?.hashCode() ?: 0)
        result = 31 * result + (widthPx ?: 0)
        result = 31 * result + (heightPx ?: 0)
        result = 31 * result + (checksum?.hashCode() ?: 0)
        result = 31 * result + metadata.hashCode()
        return result
    }
}

data class GenerationResult(
    val jobHandle: ProviderJobHandle,
    val status: GenerationStatus,
    val candidates: List<GeneratedCandidate> = emptyList(),
    val error: GenerationError? = null,
)

enum class GenerationStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class GenerationError(
    val code: String,
    val message: String,
    val retryable: Boolean = true,
)

data class ProviderJobHandle(
    val providerIdentifier: String,
    val externalJobId: String,
)
