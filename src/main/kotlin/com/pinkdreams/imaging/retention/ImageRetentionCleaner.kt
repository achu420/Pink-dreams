package com.pinkdreams.imaging.retention

import com.pinkdreams.imaging.job.ImageJobStatus
import com.pinkdreams.imaging.orchestration.GeneratedCandidateRepository
import com.pinkdreams.persistence.database.GeneratedCandidates
import com.pinkdreams.persistence.database.ImageJobs
import com.pinkdreams.storage.LocalFileObjectStorage
import com.pinkdreams.storage.ObjectStorage
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Bounded cleanup of terminal image jobs older than [retentionDays].
 * Never deletes RUNNING/QUEUED jobs.
 */
class ImageRetentionCleaner(
    private val db: Database,
    private val objectStorage: ObjectStorage,
    private val candidateRepository: GeneratedCandidateRepository,
    private val retentionDays: Long = System.getenv("IMAGE_RETENTION_DAYS")?.toLongOrNull() ?: 30L,
) {
    data class CleanupResult(
        val jobsDeleted: Int,
        val candidatesDeleted: Int,
        val blobsDeleted: Int,
    )

    fun cleanupOnce(now: LocalDateTime = LocalDateTime.now()): CleanupResult {
        val cutoff = now.minusDays(retentionDays)
        var jobsDeleted = 0
        var candidatesDeleted = 0
        var blobsDeleted = 0

        val terminalStatuses = listOf(
            ImageJobStatus.SUCCEEDED.name,
            ImageJobStatus.FAILED.name,
            ImageJobStatus.CANCELLED.name,
        )

        val terminalJobs = transaction(db) {
            ImageJobs.select {
                (ImageJobs.status inList terminalStatuses) and (ImageJobs.completedAt less cutoff)
            }.map { it[ImageJobs.id] }
        }

        for (jobId in terminalJobs) {
            val candidates = candidateRepository.findByImageJob(jobId)
            for (c in candidates) {
                if (objectStorage.delete(c.storageKey)) blobsDeleted++
                candidatesDeleted++
            }
            transaction(db) {
                GeneratedCandidates.deleteWhere { GeneratedCandidates.imageJobId eq jobId }
                ImageJobs.deleteWhere { ImageJobs.id eq jobId }
            }
            jobsDeleted++
        }

        if (objectStorage is LocalFileObjectStorage) {
            val referenced = transaction(db) {
                GeneratedCandidates.selectAll().map { it[GeneratedCandidates.storageKey] }.toSet()
            }
            for (key in objectStorage.listKeysWithPrefix("jobs/")) {
                if (key !in referenced) {
                    if (objectStorage.delete(key)) blobsDeleted++
                }
            }
        }

        return CleanupResult(jobsDeleted, candidatesDeleted, blobsDeleted)
    }

    fun startScheduled(intervalHours: Long = 24) {
        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "image-retention-cleaner").apply { isDaemon = true }
        }
        exec.scheduleWithFixedDelay({
            try {
                cleanupOnce()
            } catch (e: Exception) {
                System.err.println("image-retention-cleaner failed: ${e.message}")
            }
        }, 1, intervalHours, TimeUnit.HOURS)
    }
}
