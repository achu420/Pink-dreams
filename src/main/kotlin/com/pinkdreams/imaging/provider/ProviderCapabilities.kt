package com.pinkdreams.imaging.provider

data class ProviderCapabilities(
    val maxCandidateCount: Int,
    val supportsReferences: Boolean,
    val supportsMultipleReferences: Boolean,
    val supportedAspectRatios: List<String>,
    val minWidthPx: Int,
    val maxWidthPx: Int,
    val minHeightPx: Int,
    val maxHeightPx: Int,
    val supportsCancellation: Boolean,
    val supportsIdempotency: Boolean,
    val metadata: Map<String, String> = emptyMap(),
)
