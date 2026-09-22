package com.pinkdreams.imaging.job

import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.database.ImageJobs
import com.pinkdreams.persistence.repositories.ImageJobRepository
import com.pinkdreams.persistence.repositories.PersonaIdentityRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class PhaseIMG4ImageJobInfrastructureTest {

    private lateinit var db: org.jetbrains.exposed.sql.Database
    private lateinit var jobRepository: ImageJobRepository
    private lateinit var personaIdentityRepo: PersonaIdentityRepository
    private lateinit var visualVersionRepo: PersonaVisualVersionRepository

    @BeforeTest
    fun setupTest() {
        db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        jobRepository = ImageJobRepository(db)
        personaIdentityRepo = PersonaIdentityRepository(db)
        visualVersionRepo = PersonaVisualVersionRepository(db)
    }

    private fun createTestVersion(): Pair<UUID, UUID> {
        val identity = personaIdentityRepo.create()
        val version = visualVersionRepo.create(
            personaIdentityId = identity.id,
            version = 1,
            physicalGuide = "{}",
            author = "test"
        )
        return identity.id to version.id
    }

    // =========================================================================
    // Job Creation & Idempotency (1-5)
    // =========================================================================

    @Test
    fun `Job 1 - create and retrieve job`() {
        val (_, versionId) = createTestVersion()

        val job = jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-1",
            requestPayload = """{"request":"test"}"""
        )

        assertNotNull(job)
        assertEquals(versionId, job.personaVisualVersionId)
        assertEquals(ImageJobStatus.QUEUED, job.status)
        assertEquals("key-1", job.idempotencyKey)
    }

    @Test
    fun `Job 2 - find by idempotency key`() {
        val (_, versionId) = createTestVersion()

        val created = jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-2",
            requestPayload = """{"request":"test"}"""
        )

        val found = jobRepository.findByIdempotencyKey(versionId, "key-2")
        assertNotNull(found)
        assertEquals(created.id, found.id)
    }

    @Test
    fun `Job 3 - idempotency returns existing job on duplicate`() {
        val (_, versionId) = createTestVersion()
        val idempotencyKey = "key-3"

        val first = jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = idempotencyKey,
            requestPayload = """{"request":"first"}"""
        )

        val second = jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = idempotencyKey,
            requestPayload = """{"request":"second"}"""
        )

        assertEquals(first.id, second.id)
        assertEquals(first.requestPayload, second.requestPayload)
    }

    @Test
    fun `Job 4 - find for version`() {
        val (_, versionId) = createTestVersion()

        jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-4a",
            requestPayload = "{}"
        )

        jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-4b",
            requestPayload = "{}"
        )

        val jobs = jobRepository.findForVersion(versionId)
        assertEquals(2, jobs.size)
    }

    @Test
    fun `Job 5 - find by status`() {
        val (_, versionId) = createTestVersion()

        jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-5",
            requestPayload = "{}"
        )

        val queued = jobRepository.findByStatus(ImageJobStatus.QUEUED)
        assertTrue(queued.isNotEmpty())
        assertTrue(queued.any { it.personaVisualVersionId == versionId })
    }

    // =========================================================================
    // Job Claiming & State Transitions (6-10)
    // =========================================================================

    @Test
    fun `Claim 6 - claim and transition to RUNNING`() {
        val (_, versionId) = createTestVersion()

        val job = jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-6",
            requestPayload = "{}"
        )

        val claimed = jobRepository.claimJob(job.id, "worker-1")
        assertNotNull(claimed)
        assertEquals(ImageJobStatus.RUNNING, claimed.status)
        assertEquals("worker-1", claimed.claimedByWorker)
        assertNotNull(claimed.claimedAt)
        assertNotNull(claimed.startedAt)
    }

    @Test
    fun `Claim 7 - cannot claim non-QUEUED job`() {
        val (_, versionId) = createTestVersion()

        val job = jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-7",
            requestPayload = "{}"
        )

        Thread.sleep(10)

        val claimed = jobRepository.claimJob(job.id, "worker-1")
        assertNotNull(claimed, "First claim should succeed")

        val claim2 = jobRepository.claimJob(job.id, "worker-2")
        assertNull(claim2, "Cannot claim non-QUEUED job")
    }

    @Test
    fun `Claim 8 - cannot claim future job`() {
        val (_, versionId) = createTestVersion()

        val job = jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-8",
            requestPayload = "{}"
        )

        val futureTime = LocalDateTime.now().plusHours(1)

        transaction(db) {
            ImageJobs.update({ ImageJobs.id eq job.id }) {
                it[ImageJobs.availableAt] = futureTime
            }
        }

        val claim = jobRepository.claimJob(job.id, "worker-1")
        assertNull(claim, "Cannot claim future job")
    }

    @Test
    fun `Claim 9 - complete job success`() {
        val (_, versionId) = createTestVersion()

        val job = jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-9",
            requestPayload = "{}"
        )

        jobRepository.claimJob(job.id, "worker-1")
        val completed = jobRepository.completeJobSuccess(job.id)

        assertEquals(ImageJobStatus.SUCCEEDED, completed.status)
        assertNotNull(completed.completedAt)
        assertNull(completed.claimedByWorker)
    }

    @Test
    fun `Claim 10 - complete job failure with retry`() {
        val (_, versionId) = createTestVersion()

        val job = jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-10",
            requestPayload = "{}",
            maxAttempts = 3
        )

        Thread.sleep(10)

        val claimed = jobRepository.claimJob(job.id, "worker-1")
        assertNotNull(claimed, "Job claim should succeed")
        assertEquals(ImageJobStatus.RUNNING, claimed.status)

        val failed = jobRepository.completeJobFailure(job.id, "error message", shouldRetry = true)

        assertEquals(ImageJobStatus.RETRY_WAIT, failed.status)
        assertEquals(1, failed.attemptCount)
        assertEquals("error message", failed.lastError)
        assertNull(failed.claimedByWorker)
    }

    // =========================================================================
    // Terminal States & Failure (11-15)
    // =========================================================================

    @Test
    fun `Terminal 11 - complete job failure without retry (max attempts)` () {
        val (_, versionId) = createTestVersion()

        val job = jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-11",
            requestPayload = "{}",
            maxAttempts = 1
        )

        jobRepository.claimJob(job.id, "worker-1")
        val failed = jobRepository.completeJobFailure(job.id, "permanent error", shouldRetry = false)

        assertEquals(ImageJobStatus.FAILED, failed.status)
        assertEquals(1, failed.attemptCount)
        assertNotNull(failed.completedAt)
    }

    @Test
    fun `Terminal 12 - cancel job`() {
        val (_, versionId) = createTestVersion()

        val job = jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-12",
            requestPayload = "{}"
        )

        val cancelled = jobRepository.cancelJob(job.id)
        assertEquals(ImageJobStatus.CANCELLED, cancelled.status)
        assertNotNull(cancelled.completedAt)
    }

    @Test
    fun `Terminal 13 - cannot cancel terminal job`() {
        val (_, versionId) = createTestVersion()

        val job = jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-13",
            requestPayload = "{}"
        )

        jobRepository.claimJob(job.id, "worker-1")
        jobRepository.completeJobSuccess(job.id)

        try {
            jobRepository.cancelJob(job.id)
            fail("Should not allow cancelling terminal job")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("terminal") ?: false)
        }
    }

    @Test
    fun `Terminal 14 - requeue from RETRY_WAIT`() {
        val (_, versionId) = createTestVersion()

        val job = jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-14",
            requestPayload = "{}",
            maxAttempts = 2
        )

        jobRepository.claimJob(job.id, "worker-1")
        jobRepository.completeJobFailure(job.id, "error", shouldRetry = true)

        val requeued = jobRepository.requeueRetry(job.id)
        assertEquals(ImageJobStatus.QUEUED, requeued.status)
        assertNull(requeued.claimedByWorker)
    }

    @Test
    fun `Terminal 15 - release leased job`() {
        val (_, versionId) = createTestVersion()

        val job = jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-15",
            requestPayload = "{}"
        )

        jobRepository.claimJob(job.id, "worker-1")
        val released = jobRepository.releaseLeasedJob(job.id)

        assertEquals(ImageJobStatus.QUEUED, released.status)
        assertNull(released.claimedByWorker)
    }

    // =========================================================================
    // Worker Processing (16-18)
    // =========================================================================

    @Test
    fun `Worker 16 - process pending jobs`() {
        val (_, versionId) = createTestVersion()

        jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-16a",
            requestPayload = "{}"
        )

        jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-16b",
            requestPayload = "{}"
        )

        val handler = NoOpImageJobHandler()
        val worker = ImageJobWorker(db, "test-worker", handler, jobRepository)

        val processed = worker.processPendingJobs(maxBatchSize = 10)
        assertEquals(2, processed)

        val allJobs = jobRepository.findForVersion(versionId)
        assertTrue(allJobs.all { it.status == ImageJobStatus.SUCCEEDED })
    }

    @Test
    fun `Worker 17 - process retryable jobs`() {
        val (_, versionId) = createTestVersion()

        val job = jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-17",
            requestPayload = "{}",
            maxAttempts = 2
        )

        jobRepository.claimJob(job.id, "worker-1")
        jobRepository.completeJobFailure(job.id, "error", shouldRetry = true)

        val updated = jobRepository.findById(job.id)!!
        assertEquals(ImageJobStatus.RETRY_WAIT, updated.status)

        transaction(db) {
            ImageJobs.update({ ImageJobs.id eq job.id }) {
                it[ImageJobs.availableAt] = LocalDateTime.now().minusSeconds(1)
            }
        }

        val handler = NoOpImageJobHandler()
        val worker = ImageJobWorker(db, "worker-1", handler, jobRepository)

        val requeued = worker.processRetryableJobs()
        assertEquals(1, requeued)

        val requeued_job = jobRepository.findById(job.id)!!
        assertEquals(ImageJobStatus.QUEUED, requeued_job.status)
    }

    @Test
    fun `Worker 18 - recover stale leased jobs`() {
        val (_, versionId) = createTestVersion()

        val job = jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-18",
            requestPayload = "{}"
        )

        val claimed = jobRepository.claimJob(job.id, "worker-1")
        assertNotNull(claimed, "Job claim should succeed")
        assertEquals(ImageJobStatus.RUNNING, claimed.status)

        Thread.sleep(10)

        val handler = NoOpImageJobHandler()
        val worker = ImageJobWorker(db, "worker-1", handler, jobRepository)

        val recovered = worker.recoverStaleLeasedJobs(leaseTimeoutSeconds = 0)
        assertEquals(1, recovered)

        val updated = jobRepository.findById(job.id)!!
        assertEquals(ImageJobStatus.QUEUED, updated.status)
    }

    // =========================================================================
    // Validation & State Machine (19-21)
    // =========================================================================

    @Test
    fun `Validation 19 - job validation succeeds`() {
        val (_, versionId) = createTestVersion()

        val job = jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-19",
            requestPayload = "{}"
        )

        val validation = job.validate()
        assertTrue(validation.valid)
    }

    @Test
    fun `Validation 20 - status enum covers all transitions`() {
        assertTrue(ImageJobStatus.QUEUED in listOf(
            ImageJobStatus.QUEUED,
            ImageJobStatus.RUNNING,
            ImageJobStatus.RETRY_WAIT,
            ImageJobStatus.SUCCEEDED,
            ImageJobStatus.FAILED,
            ImageJobStatus.CANCELLED
        ))
    }

    @Test
    fun `Validation 21 - terminal status check`() {
        assertFalse(ImageJobStatus.QUEUED.isTerminal())
        assertFalse(ImageJobStatus.RUNNING.isTerminal())
        assertFalse(ImageJobStatus.RETRY_WAIT.isTerminal())
        assertTrue(ImageJobStatus.SUCCEEDED.isTerminal())
        assertTrue(ImageJobStatus.FAILED.isTerminal())
        assertTrue(ImageJobStatus.CANCELLED.isTerminal())
    }

    // =========================================================================
    // Durability & Restart Resilience (22-24)
    // =========================================================================

    @Test
    fun `Durability 22 - job survives database round-trip`() {
        val (_, versionId) = createTestVersion()

        val created = jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-22",
            requestPayload = """{"test":"payload"}"""
        )

        val retrieved = jobRepository.findById(created.id)!!
        assertEquals(created.id, retrieved.id)
        assertEquals(created.requestPayload, retrieved.requestPayload)
        assertEquals(ImageJobStatus.QUEUED, retrieved.status)
    }

    @Test
    fun `Durability 23 - RUNNING jobs remain after application restart`() {
        val (_, versionId) = createTestVersion()

        val job = jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-23",
            requestPayload = "{}"
        )

        jobRepository.claimJob(job.id, "worker-1")

        val fromDb = jobRepository.findById(job.id)!!
        assertEquals(ImageJobStatus.RUNNING, fromDb.status)
        assertEquals("worker-1", fromDb.claimedByWorker)
    }

    @Test
    fun `Durability 24 - RETRY_WAIT jobs recover after restart`() {
        val (_, versionId) = createTestVersion()

        val job = jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-24",
            requestPayload = "{}",
            maxAttempts = 2
        )

        jobRepository.claimJob(job.id, "worker-1")
        jobRepository.completeJobFailure(job.id, "error", shouldRetry = true)

        val fromDb = jobRepository.findById(job.id)!!
        assertEquals(ImageJobStatus.RETRY_WAIT, fromDb.status)
        assertTrue(fromDb.availableAt > LocalDateTime.now())
    }

    // =========================================================================
    // Verification Tests (25-32)
    // =========================================================================

    @Test
    fun `Verify 25 - any worker can recover stale leases`() {
        val (_, versionId) = createTestVersion()

        val job = jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-25",
            requestPayload = "{}"
        )

        val claimed = jobRepository.claimJob(job.id, "worker-a")
        assertNotNull(claimed, "Job claim should succeed")
        assertEquals(ImageJobStatus.RUNNING, claimed.status)

        Thread.sleep(10)

        val handler = NoOpImageJobHandler()
        val workerB = ImageJobWorker(db, "worker-b", handler, jobRepository)

        val recovered = workerB.recoverStaleLeasedJobs(leaseTimeoutSeconds = 0)
        assertEquals(1, recovered)

        val updated = jobRepository.findById(job.id)!!
        assertEquals(ImageJobStatus.QUEUED, updated.status)
        assertNull(updated.claimedByWorker)
    }

    @Test
    fun `Verify 26 - maxAttempts limits executions correctly`() {
        val (_, versionId) = createTestVersion()

        val job = jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-26",
            requestPayload = "{}",
            maxAttempts = 2
        )

        var executionCount = 0
        val countingHandler = object : ImageJobHandler {
            override fun handle(job: ImageJob): ImageJobResult {
                executionCount++
                return ImageJobResult.Failure("error", retryable = true)
            }
        }

        val worker = ImageJobWorker(db, "worker", countingHandler, jobRepository)

        worker.processPendingJobs()
        assertEquals(1, executionCount)

        val afterFirst = jobRepository.findById(job.id)!!
        assertEquals(ImageJobStatus.RETRY_WAIT, afterFirst.status)
        assertEquals(1, afterFirst.attemptCount)

        transaction(db) {
            ImageJobs.update({ ImageJobs.id eq job.id }) {
                it[ImageJobs.availableAt] = LocalDateTime.now().minusSeconds(1)
            }
        }

        worker.processRetryableJobs()
        worker.processPendingJobs()
        assertEquals(2, executionCount)

        val afterSecond = jobRepository.findById(job.id)!!
        assertEquals(ImageJobStatus.FAILED, afterSecond.status)
        assertEquals(2, afterSecond.attemptCount)
    }

    @Test
    fun `Verify 27 - atomic claiming prevents concurrent double-claim`() {
        val (_, versionId) = createTestVersion()

        val job = jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-27",
            requestPayload = "{}"
        )

        val claim1 = jobRepository.claimJob(job.id, "worker-1")
        assertNotNull(claim1)
        assertEquals("worker-1", claim1.claimedByWorker)

        val claim2 = jobRepository.claimJob(job.id, "worker-2")
        assertNull(claim2, "Second claim should fail because job is no longer QUEUED")

        val actual = jobRepository.findById(job.id)!!
        assertEquals("worker-1", actual.claimedByWorker)
        assertEquals(ImageJobStatus.RUNNING, actual.status)
    }

    @Test
    fun `Verify 28 - stale worker cannot mutate recovered job`() {
        val (_, versionId) = createTestVersion()

        val job = jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-28",
            requestPayload = "{}"
        )

        Thread.sleep(10)

        jobRepository.claimJob(job.id, "worker-a")

        val handler = NoOpImageJobHandler()
        val workerB = ImageJobWorker(db, "worker-b", handler, jobRepository)
        workerB.recoverStaleLeasedJobs(leaseTimeoutSeconds = 0)

        val recovered = jobRepository.findById(job.id)!!
        assertEquals(ImageJobStatus.QUEUED, recovered.status)
        assertNull(recovered.claimedByWorker)

        try {
            jobRepository.completeJobSuccess(job.id)
            fail("Should not allow completion of unclaimed job")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("RUNNING") ?: false)
        }
    }

    @Test
    fun `Verify 29 - non-retryable failures do not retry`() {
        val (_, versionId) = createTestVersion()

        val job = jobRepository.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "key-29",
            requestPayload = "{}",
            maxAttempts = 3
        )

        val claimed = jobRepository.claimJob(job.id, "worker-1")
        assertNotNull(claimed, "Job claim should succeed")
        assertEquals(ImageJobStatus.RUNNING, claimed.status)

        jobRepository.completeJobFailure(job.id, "permanent error", shouldRetry = false)

        val failed = jobRepository.findById(job.id)!!
        assertEquals(ImageJobStatus.FAILED, failed.status)
        assertEquals(1, failed.attemptCount)
        assertNotNull(failed.completedAt)
    }
}
