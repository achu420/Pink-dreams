package com.pinkdreams.imaging.provider

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FakeImageProvider : ImageProvider {
    override val providerId: String = "fake-provider"

    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        maxCandidateCount = 4,
        supportsReferences = true,
        supportsMultipleReferences = true,
        supportedAspectRatios = listOf("1:1", "4:3", "3:4", "16:9", "9:16"),
        minWidthPx = 256,
        maxWidthPx = 2048,
        minHeightPx = 256,
        maxHeightPx = 2048,
        supportsCancellation = true,
        supportsIdempotency = true,
    )

    private val jobs = ConcurrentHashMap<String, JobState>()

    private data class JobState(
        val request: GenerationRequest,
        val handle: ProviderJobHandle,
        var status: GenerationStatus = GenerationStatus.QUEUED,
        val candidates: MutableList<GeneratedCandidate> = mutableListOf(),
        var error: GenerationError? = null,
    )

    override suspend fun submit(request: GenerationRequest): GenerationResult {
        val validation = request.validate()
        if (!validation.valid) {
            return GenerationResult(
                jobHandle = ProviderJobHandle("fake-provider", "invalid"),
                status = GenerationStatus.FAILED,
                error = GenerationError(
                    code = "VALIDATION_ERROR",
                    message = validation.errors.joinToString("; "),
                    retryable = false,
                ),
            )
        }

        // Evaluation failure injection: model id containing "force-fail"
        val effectiveModel = request.modelId ?: "fake-default"
        if (effectiveModel.contains("force-fail", ignoreCase = true)) {
            return GenerationResult(
                jobHandle = ProviderJobHandle("fake-provider", UUID.randomUUID().toString()),
                status = GenerationStatus.FAILED,
                error = GenerationError(
                    code = "PROVIDER_REJECTION",
                    message = "Forced failure for evaluation model '$effectiveModel'",
                    retryable = false,
                ),
            )
        }

        val externalJobId = UUID.randomUUID().toString()
        val handle = ProviderJobHandle("fake-provider", externalJobId)

        val jobState = JobState(request, handle)
        jobs[externalJobId] = jobState

        jobState.status = GenerationStatus.RUNNING

        for (i in 0 until request.candidateCount) {
            val candidate = GeneratedCandidate(
                id = UUID.randomUUID(),
                imageData = generateDeterministicImageData(externalJobId, i, request),
                widthPx = request.widthPx ?: 512,
                heightPx = request.heightPx ?: 512,
                checksum = "fake:${externalJobId}:$i",
            )
            jobState.candidates.add(candidate)
        }

        jobState.status = GenerationStatus.COMPLETED

        return GenerationResult(
            jobHandle = handle,
            status = GenerationStatus.COMPLETED,
            candidates = jobState.candidates.toList(),
        )
    }

    override suspend fun getStatus(jobHandle: ProviderJobHandle): GenerationResult {
        val jobState = jobs[jobHandle.externalJobId]
            ?: return GenerationResult(
                jobHandle = jobHandle,
                status = GenerationStatus.FAILED,
                error = GenerationError("NOT_FOUND", "Job not found", retryable = true),
            )

        return GenerationResult(
            jobHandle = jobHandle,
            status = jobState.status,
            candidates = jobState.candidates.toList(),
            error = jobState.error,
        )
    }

    override suspend fun getResult(jobHandle: ProviderJobHandle): GenerationResult {
        val jobState = jobs[jobHandle.externalJobId]
            ?: return GenerationResult(
                jobHandle = jobHandle,
                status = GenerationStatus.FAILED,
                error = GenerationError("NOT_FOUND", "Job not found", retryable = true),
            )

        if (jobState.status != GenerationStatus.COMPLETED && jobState.status != GenerationStatus.FAILED) {
            return GenerationResult(
                jobHandle = jobHandle,
                status = jobState.status,
                error = GenerationError("NOT_READY", "Job not yet complete", retryable = true),
            )
        }

        return GenerationResult(
            jobHandle = jobHandle,
            status = jobState.status,
            candidates = jobState.candidates.toList(),
            error = jobState.error,
        )
    }

    override suspend fun cancel(jobHandle: ProviderJobHandle): Boolean {
        val jobState = jobs[jobHandle.externalJobId] ?: return false

        if (jobState.status.let { it == GenerationStatus.COMPLETED || it == GenerationStatus.FAILED || it == GenerationStatus.CANCELLED }) {
            return false
        }

        jobState.status = GenerationStatus.CANCELLED
        return true
    }

    fun clear() {
        jobs.clear()
    }

    private fun generateDeterministicImageData(jobId: String, candidateIndex: Int, request: GenerationRequest): ByteArray {
        val seed = (jobId + candidateIndex).hashCode()
        val width = request.widthPx ?: 512
        val height = request.heightPx ?: 512
        val pixelCount = width * height
        val data = ByteArray(pixelCount * 3)

        for (i in 0 until pixelCount) {
            val value = ((seed + i) % 256).toByte()
            data[i * 3] = value
            data[i * 3 + 1] = value
            data[i * 3 + 2] = value
        }

        return data
    }
}
