package com.pinkdreams.imaging.job

import org.jetbrains.exposed.sql.Database
import java.time.LocalDateTime
import java.util.UUID

class ImageJobWorker(
    private val db: Database,
    private val workerName: String,
    private val jobHandler: ImageJobHandler,
    private val jobRepository: com.pinkdreams.persistence.repositories.ImageJobRepository = com.pinkdreams.persistence.repositories.ImageJobRepository(db),
) {

    fun processPendingJobs(maxBatchSize: Int = 10): Int {
        val pendingJobs = jobRepository.findByStatus(ImageJobStatus.QUEUED, limit = maxBatchSize)
        var processed = 0

        for (job in pendingJobs) {
            if (LocalDateTime.now() < job.availableAt) {
                continue
            }

            val claimed = jobRepository.claimJob(job.id, workerName) ?: continue
            try {
                val result = jobHandler.handle(claimed)
                when (result) {
                    is ImageJobResult.Success -> {
                        jobRepository.completeJobSuccess(claimed.id)
                        processed++
                    }
                    is ImageJobResult.Failure -> {
                        jobRepository.completeJobFailure(claimed.id, result.errorMessage, shouldRetry = result.retryable)
                        processed++
                    }
                }
            } catch (e: Exception) {
                jobRepository.completeJobFailure(claimed.id, "Unexpected error: ${e.message}", shouldRetry = true)
                processed++
            }
        }

        return processed
    }

    fun processRetryableJobs(maxBatchSize: Int = 10): Int {
        val retryJobs = jobRepository.findByStatus(ImageJobStatus.RETRY_WAIT, limit = maxBatchSize)
        var requeued = 0

        for (job in retryJobs) {
            if (LocalDateTime.now() >= job.availableAt) {
                jobRepository.requeueRetry(job.id)
                requeued++
            }
        }

        return requeued
    }

    fun recoverStaleLeasedJobs(leaseTimeoutSeconds: Long = 300): Int {
        val staleLeasedJobs = jobRepository.findStaleLeasedJobs(leaseTimeoutSeconds)
        var recovered = 0

        for (job in staleLeasedJobs) {
            jobRepository.releaseLeasedJob(job.id)
            recovered++
        }

        return recovered
    }
}
