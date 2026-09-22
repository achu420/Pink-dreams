package com.pinkdreams.imaging.job

import org.jetbrains.exposed.sql.Database
import java.time.LocalDateTime

class ImageJobWorker(
    private val db: Database,
    private val workerName: String,
    private val jobHandler: ImageJobHandler,
    private val jobRepository: com.pinkdreams.persistence.repositories.ImageJobRepository =
        com.pinkdreams.persistence.repositories.ImageJobRepository(db),
    private val completionAttach: ((job: ImageJob, succeeded: Boolean) -> Unit)? = null,
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
                        val completed = jobRepository.completeJobSuccess(claimed.id, workerName)
                        if (completed != null) {
                            notifyCompletion(completed, succeeded = true)
                            processed++
                        } else {
                            System.err.println(
                                "IMAGE_WORKER: lease lost after success job=${claimed.id} worker=$workerName",
                            )
                        }
                    }
                    is ImageJobResult.Failure -> {
                        val failed = jobRepository.completeJobFailure(
                            claimed.id,
                            workerName,
                            result.errorMessage,
                            shouldRetry = result.retryable,
                        )
                        if (failed != null) {
                            if (failed.status == ImageJobStatus.FAILED) {
                                notifyCompletion(failed, succeeded = false)
                            }
                            processed++
                        } else {
                            System.err.println(
                                "IMAGE_WORKER: lease lost after failure job=${claimed.id} worker=$workerName",
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                val failed = jobRepository.completeJobFailure(
                    claimed.id,
                    workerName,
                    "Unexpected error: ${e.message}",
                    shouldRetry = true,
                )
                if (failed != null) {
                    if (failed.status == ImageJobStatus.FAILED) {
                        notifyCompletion(failed, succeeded = false)
                    }
                    processed++
                }
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

    /**
     * Idempotent re-attach for SUCCEEDED/FAILED conversation-scoped jobs whose
     * chat metadata may have been lost after a crash between terminal status and attach.
     */
    fun reconcileMessageAttachments(maxBatchSize: Int = 10): Int {
        if (completionAttach == null) return 0
        var n = 0
        for (job in jobRepository.findRecent(limit = maxBatchSize * 3)) {
            if (n >= maxBatchSize) break
            when (job.status) {
                ImageJobStatus.SUCCEEDED -> {
                    notifyCompletion(job, succeeded = true)
                    n++
                }
                ImageJobStatus.FAILED -> {
                    notifyCompletion(job, succeeded = false)
                    n++
                }
                else -> Unit
            }
        }
        return n
    }

    private fun notifyCompletion(job: ImageJob, succeeded: Boolean) {
        try {
            completionAttach?.invoke(job, succeeded)
        } catch (e: Exception) {
            System.err.println(
                "IMAGE_WORKER: completion attach failed job=${job.id}: ${e.message}",
            )
        }
    }
}
