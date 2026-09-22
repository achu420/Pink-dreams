package com.pinkdreams.persistence.repositories

import com.pinkdreams.imaging.job.ImageJob
import com.pinkdreams.imaging.job.ImageJobStatus
import com.pinkdreams.imaging.job.ImageJobType
import com.pinkdreams.persistence.database.ImageJobs
import com.pinkdreams.persistence.database.defaultNow
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID

class ImageJobRepository(private val db: Database) {

    /**
     * Creates a job, or returns the existing job for the same
     * (personaVisualVersionId, idempotencyKey). Safe for client retries.
     */
    fun createJob(
        personaVisualVersionId: UUID,
        jobType: ImageJobType,
        idempotencyKey: String,
        requestPayload: String,
        maxAttempts: Int = 3,
    ): ImageJob = transaction(db) {
        findByIdempotencyKey(personaVisualVersionId, idempotencyKey)?.let { return@transaction it }

        val id = UUID.randomUUID()
        val now = LocalDateTime.now()

        try {
            ImageJobs.insert {
                it[ImageJobs.id] = id
                it[ImageJobs.personaVisualVersionId] = personaVisualVersionId
                it[ImageJobs.jobType] = jobType.name
                it[ImageJobs.status] = ImageJobStatus.QUEUED.name
                it[ImageJobs.idempotencyKey] = idempotencyKey
                it[ImageJobs.requestPayload] = requestPayload
                it[ImageJobs.attemptCount] = 0
                it[ImageJobs.maxAttempts] = maxAttempts
                it[ImageJobs.availableAt] = now
                it[ImageJobs.createdAt] = now
            }
            findById(id)!!
        } catch (e: Exception) {
            // Concurrent duplicate insert — return the winner
            findByIdempotencyKey(personaVisualVersionId, idempotencyKey)
                ?: throw e
        }
    }

    fun adminRequeueFailed(jobId: UUID): ImageJob = transaction(db) {
        val job = findById(jobId) ?: throw IllegalArgumentException("Job not found: $jobId")
        require(job.status == ImageJobStatus.FAILED || job.status == ImageJobStatus.RETRY_WAIT) {
            "Only FAILED or RETRY_WAIT jobs can be admin-requeued (status=${job.status})"
        }
        ImageJobs.update({ ImageJobs.id eq jobId }) {
            it[ImageJobs.status] = ImageJobStatus.QUEUED.name
            it[ImageJobs.availableAt] = LocalDateTime.now()
            it[ImageJobs.claimedByWorker] = null
            it[ImageJobs.claimedAt] = null
            it[ImageJobs.completedAt] = null
        }
        findById(jobId)!!
    }

    fun findRecent(limit: Int = 50): List<ImageJob> = transaction(db) {
        ImageJobs.selectAll()
            .orderBy(ImageJobs.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC)
            .limit(limit)
            .map(::rowToModel)
    }

    fun findById(id: UUID): ImageJob? = transaction(db) {
        ImageJobs.select { ImageJobs.id eq id }
            .map(::rowToModel)
            .singleOrNull()
    }

    fun findByIdempotencyKey(personaVisualVersionId: UUID, idempotencyKey: String): ImageJob? = transaction(db) {
        ImageJobs.select {
            (ImageJobs.personaVisualVersionId eq personaVisualVersionId) and
            (ImageJobs.idempotencyKey eq idempotencyKey)
        }
            .map(::rowToModel)
            .singleOrNull()
    }

    fun findForVersion(personaVisualVersionId: UUID): List<ImageJob> = transaction(db) {
        ImageJobs.select { ImageJobs.personaVisualVersionId eq personaVisualVersionId }
            .map(::rowToModel)
    }

    fun findByStatus(status: ImageJobStatus, limit: Int = 100): List<ImageJob> = transaction(db) {
        ImageJobs.select { ImageJobs.status eq status.name }
            .limit(limit)
            .map(::rowToModel)
    }

    fun claimJob(jobId: UUID, workerName: String): ImageJob? = transaction(db) {
        val now = LocalDateTime.now()

        val updated = ImageJobs.update({
            (ImageJobs.id eq jobId) and
            (ImageJobs.status eq ImageJobStatus.QUEUED.name) and
            (ImageJobs.availableAt lessEq now)
        }) {
            it[ImageJobs.status] = ImageJobStatus.RUNNING.name
            it[ImageJobs.claimedByWorker] = workerName
            it[ImageJobs.claimedAt] = now
            it[ImageJobs.startedAt] = now
        }

        if (updated > 0) findById(jobId) else null
    }

    /**
     * Completes a job only if it is still RUNNING and leased to [workerName].
     * Returns null when the lease was lost (stale recovery / another worker).
     */
    fun completeJobSuccess(jobId: UUID, workerName: String): ImageJob? = transaction(db) {
        val now = LocalDateTime.now()
        val updated = ImageJobs.update({
            (ImageJobs.id eq jobId) and
                (ImageJobs.status eq ImageJobStatus.RUNNING.name) and
                (ImageJobs.claimedByWorker eq workerName)
        }) {
            it[ImageJobs.status] = ImageJobStatus.SUCCEEDED.name
            it[ImageJobs.completedAt] = now
            it[ImageJobs.claimedByWorker] = null
            it[ImageJobs.claimedAt] = null
        }
        if (updated > 0) findById(jobId) else null
    }

    /**
     * Fails a job only if still RUNNING and leased to [workerName].
     * Returns null when the lease was lost.
     */
    fun completeJobFailure(
        jobId: UUID,
        workerName: String,
        errorMessage: String,
        shouldRetry: Boolean,
    ): ImageJob? = transaction(db) {
        val job = findById(jobId) ?: return@transaction null
        if (job.status != ImageJobStatus.RUNNING || job.claimedByWorker != workerName) {
            return@transaction null
        }

        val now = LocalDateTime.now()
        val nextAttemptCount = job.attemptCount + 1
        val redacted = com.pinkdreams.imaging.observability.ImageGenerationEventRepository.redactSecrets(errorMessage)
        val retry = shouldRetry && nextAttemptCount < job.maxAttempts

        val updated = ImageJobs.update({
            (ImageJobs.id eq jobId) and
                (ImageJobs.status eq ImageJobStatus.RUNNING.name) and
                (ImageJobs.claimedByWorker eq workerName)
        }) {
            if (retry) {
                it[ImageJobs.status] = ImageJobStatus.RETRY_WAIT.name
                it[ImageJobs.availableAt] = now.plusSeconds(60)
            } else {
                it[ImageJobs.status] = ImageJobStatus.FAILED.name
                it[ImageJobs.completedAt] = now
            }
            it[ImageJobs.attemptCount] = nextAttemptCount
            it[ImageJobs.lastError] = redacted
            it[ImageJobs.claimedByWorker] = null
            it[ImageJobs.claimedAt] = null
        }

        if (updated > 0) findById(jobId) else null
    }

    fun cancelJob(jobId: UUID): ImageJob = transaction(db) {
        val job = findById(jobId) ?: throw IllegalArgumentException("Job not found: $jobId")
        require(!job.status.isTerminal()) { "Cannot cancel terminal job with status ${job.status}" }

        val now = LocalDateTime.now()
        ImageJobs.update({ ImageJobs.id eq jobId }) {
            it[ImageJobs.status] = ImageJobStatus.CANCELLED.name
            it[ImageJobs.completedAt] = now
            it[ImageJobs.claimedByWorker] = null
        }

        findById(jobId)!!
    }

    fun requeueRetry(jobId: UUID): ImageJob = transaction(db) {
        val job = findById(jobId) ?: throw IllegalArgumentException("Job not found: $jobId")
        require(job.status == ImageJobStatus.RETRY_WAIT) { "Only RETRY_WAIT jobs can be requeued" }

        ImageJobs.update({ ImageJobs.id eq jobId }) {
            it[ImageJobs.status] = ImageJobStatus.QUEUED.name
            it[ImageJobs.claimedByWorker] = null
            it[ImageJobs.claimedAt] = null
        }

        findById(jobId)!!
    }

    fun releaseLeasedJob(jobId: UUID): ImageJob = transaction(db) {
        val job = findById(jobId) ?: throw IllegalArgumentException("Job not found: $jobId")
        require(job.status == ImageJobStatus.RUNNING) { "Only RUNNING jobs can be released" }

        ImageJobs.update({ ImageJobs.id eq jobId }) {
            it[ImageJobs.status] = ImageJobStatus.QUEUED.name
            it[ImageJobs.claimedByWorker] = null
            it[ImageJobs.claimedAt] = null
        }

        findById(jobId)!!
    }

    fun findClaimedByWorker(workerName: String): List<ImageJob> = transaction(db) {
        ImageJobs.select { ImageJobs.claimedByWorker eq workerName }
            .map(::rowToModel)
    }

    fun findStaleLeasedJobs(leaseTimeoutSeconds: Long): List<ImageJob> = transaction(db) {
        val cutoffTime = LocalDateTime.now().minusSeconds(leaseTimeoutSeconds)
        ImageJobs.select {
            (ImageJobs.status eq ImageJobStatus.RUNNING.name) and
            (ImageJobs.claimedAt.isNotNull()) and
            (ImageJobs.claimedAt lessEq cutoffTime)
        }
            .map(::rowToModel)
    }

    private fun rowToModel(row: ResultRow): ImageJob = ImageJob(
        id = row[ImageJobs.id],
        personaVisualVersionId = row[ImageJobs.personaVisualVersionId],
        jobType = ImageJobType.valueOf(row[ImageJobs.jobType]),
        status = ImageJobStatus.valueOf(row[ImageJobs.status]),
        idempotencyKey = row[ImageJobs.idempotencyKey],
        requestPayload = row[ImageJobs.requestPayload],
        attemptCount = row[ImageJobs.attemptCount],
        maxAttempts = row[ImageJobs.maxAttempts],
        availableAt = row[ImageJobs.availableAt],
        claimedByWorker = row[ImageJobs.claimedByWorker],
        claimedAt = row[ImageJobs.claimedAt],
        lastError = row[ImageJobs.lastError],
        createdAt = row[ImageJobs.createdAt],
        startedAt = row[ImageJobs.startedAt],
        completedAt = row[ImageJobs.completedAt],
    )
}
