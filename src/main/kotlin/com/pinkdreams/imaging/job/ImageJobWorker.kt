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
    /**
     * How often to renew [claimedAt] during provider execution.
     * Must be less than the lease timeout. `0` disables heartbeat (tests).
     */
    private val heartbeatIntervalSeconds: Long =
        System.getenv("IMAGE_WORKER_HEARTBEAT_SECONDS")?.toLongOrNull()
            ?: defaultHeartbeatInterval(),
) {

    fun processPendingJobs(maxBatchSize: Int = 10): Int {
        val pendingJobs = jobRepository.findByStatus(ImageJobStatus.QUEUED, limit = maxBatchSize)
        var processed = 0
        val now = LocalDateTime.now()

        for (job in pendingJobs) {
            // 1s skew matches claimJob TIMESTAMP rounding tolerance.
            if (now.plusSeconds(1) < job.availableAt) {
                continue
            }

            val claimed = jobRepository.claimJob(job.id, workerName) ?: continue
            val heartbeat = ImageJobLeaseHeartbeat(
                jobRepository = jobRepository,
                jobId = claimed.id,
                workerName = workerName,
                intervalSeconds = heartbeatIntervalSeconds,
            )
            try {
                heartbeat.start()
                val result = jobHandler.handle(claimed)
                when (result) {
                    is ImageJobResult.Success -> {
                        val completed = jobRepository.completeJobSuccess(
                            claimed.id,
                            workerName,
                            costRaw = result.actualCost,
                            costCurrency = result.costCurrency,
                            costSource = result.costSource,
                        )
                        if (completed != null) {
                            notifyCompletion(completed, succeeded = true)
                            processed++
                        } else {
                            System.err.println(
                                "IMAGE_WORKER: lease lost after success job=${claimed.id} worker=$workerName " +
                                    "heartbeatLost=${heartbeat.hasLostLease()}",
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
                                "IMAGE_WORKER: lease lost after failure job=${claimed.id} worker=$workerName " +
                                    "heartbeatLost=${heartbeat.hasLostLease()}",
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
            } finally {
                heartbeat.close()
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

    companion object {
        fun defaultHeartbeatInterval(
            leaseSeconds: Long = System.getenv("IMAGE_WORKER_LEASE_SECONDS")?.toLongOrNull() ?: 300L,
        ): Long {
            // Renew often enough that a healthy worker is never considered stale.
            val derived = (leaseSeconds / 3).coerceAtLeast(15L)
            return derived.coerceAtMost(leaseSeconds.coerceAtLeast(1L) - 1L).coerceAtLeast(1L)
        }
    }
}
