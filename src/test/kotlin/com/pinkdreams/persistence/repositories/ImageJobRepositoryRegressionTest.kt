package com.pinkdreams.persistence.repositories

import com.pinkdreams.imaging.job.ImageJobStatus
import com.pinkdreams.imaging.job.ImageJobType
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.database.ImageJobs
import com.pinkdreams.storage.InMemoryObjectStorage
import com.pinkdreams.visual.identity.PersonaVisualAdminService
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

/**
 * Regression and status-transition tests for [ImageJobRepository].
 *
 * Uses an H2 in-memory database.
 *
 * Regression coverage:
 *  - Task 27: completeJobSuccess must clear lastError
 *  - Task 27: adminRequeueFailed must reset attemptCount to 0 and clear lastError
 */
class ImageJobRepositoryRegressionTest {

    private lateinit var db: org.jetbrains.exposed.sql.Database
    private lateinit var repo: ImageJobRepository
    private lateinit var visualVersionId: UUID

    @BeforeTest
    fun setup() {
        db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        repo = ImageJobRepository(db)
        visualVersionId = createPublishedVisualVersion()
    }

    /** Creates a persona + published visual version so the FK constraint is satisfied. */
    private fun createPublishedVisualVersion(): UUID {
        val storage = InMemoryObjectStorage()
        val personaRepo = PersonaRepository(db)
        val visualAdmin = PersonaVisualAdminService(
            personaRepository = personaRepo,
            personaIdentityRepository = PersonaIdentityRepository(db),
            visualVersionRepository = PersonaVisualVersionRepository(db),
            personalGuideRepository = PersonalGuideRepository(db),
            referenceImageRepository = ReferenceImageRepository(db, storage),
        )
        val persona = personaRepo.create(
            slug = "repo-test-${UUID.randomUUID().toString().take(8)}",
            displayName = "Repo Test",
            gender = "female",
            orientation = "straight",
            apparentAge = 25,
            languageProfile = emptyMap(),
        )
        visualAdmin.ensureDraft(persona.id, "test")
        return visualAdmin.publishAndActivateDraft(persona.id).id
    }

    private fun createQueued(key: String = "key-${UUID.randomUUID()}"): com.pinkdreams.imaging.job.ImageJob =
        repo.createJob(
            personaVisualVersionId = visualVersionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = key,
            requestPayload = """{"version":"1"}""",
        )

    // =========================================================================
    // Task 27 regression — completeJobSuccess clears lastError
    // =========================================================================

    @Test
    fun `completeJobSuccess clears lastError even when set before completion`() {
        val job = createQueued()
        val claimed = repo.claimJob(job.id, "worker")!!

        // Inject a synthetic lastError directly into the DB, bypassing normal failure flow,
        // to prove that completeJobSuccess explicitly nulls it out.
        transaction(db) {
            ImageJobs.update({ ImageJobs.id eq claimed.id }) {
                it[ImageJobs.lastError] = "synthetic-prior-error"
            }
        }

        val completed = repo.completeJobSuccess(claimed.id, "worker")
        assertNotNull(completed, "completeJobSuccess must return the completed job")
        assertEquals(ImageJobStatus.SUCCEEDED, completed.status)
        assertNull(completed.lastError, "completeJobSuccess MUST clear lastError (Task 27 regression)")
        assertNotNull(completed.completedAt)
    }

    @Test
    fun `completeJobSuccess via normal fail-requeue-succeed flow clears lastError`() {
        val job = createQueued()
        val claimed = repo.claimJob(job.id, "worker")!!
        // Fail the job (sets lastError)
        repo.completeJobFailure(claimed.id, "worker", "deliberate-error", shouldRetry = false)
        // Admin requeue (clears lastError at requeue too — both must clear it)
        repo.adminRequeueFailed(job.id)
        // Claim and succeed
        val claimed2 = repo.claimJob(job.id, "worker")!!
        val succeeded = repo.completeJobSuccess(claimed2.id, "worker")
        assertNotNull(succeeded)
        assertNull(succeeded!!.lastError, "SUCCEEDED job must have null lastError")
    }

    // =========================================================================
    // Task 27 regression — adminRequeueFailed resets attemptCount and lastError
    // =========================================================================

    @Test
    fun `adminRequeueFailed resets attemptCount to 0 and clears lastError`() {
        val job = createQueued()
        val claimed = repo.claimJob(job.id, "worker")!!
        // Fail → lastError set, attemptCount incremented
        repo.completeJobFailure(claimed.id, "worker", "an error", shouldRetry = false)

        val failed = repo.findById(job.id)!!
        assertEquals(ImageJobStatus.FAILED, failed.status)
        assertNotNull(failed.lastError)
        assertEquals(1, failed.attemptCount)

        val requeued = repo.adminRequeueFailed(job.id)

        assertEquals(ImageJobStatus.QUEUED, requeued.status, "Must be QUEUED after adminRequeueFailed")
        assertEquals(0, requeued.attemptCount, "adminRequeueFailed must reset attemptCount to 0 (Task 27 regression)")
        assertNull(requeued.lastError, "adminRequeueFailed must clear lastError (Task 27 regression)")
        assertNull(requeued.claimedByWorker, "claimedByWorker must be cleared")
        assertNull(requeued.claimedAt, "claimedAt must be cleared")
    }

    // =========================================================================
    // adminRequeueFailed rejects non-FAILED jobs
    // =========================================================================

    @Test
    fun `adminRequeueFailed throws for QUEUED job`() {
        val job = createQueued()
        assertEquals(ImageJobStatus.QUEUED, job.status)
        assertFailsWith<IllegalArgumentException>("QUEUED job must not be admin-requeueable") {
            repo.adminRequeueFailed(job.id)
        }
    }

    @Test
    fun `adminRequeueFailed throws for RUNNING job`() {
        val job = createQueued()
        repo.claimJob(job.id, "worker")  // transitions to RUNNING
        assertFailsWith<IllegalArgumentException>("RUNNING job must not be admin-requeueable") {
            repo.adminRequeueFailed(job.id)
        }
    }

    @Test
    fun `adminRequeueFailed throws for SUCCEEDED job`() {
        val job = createQueued()
        val claimed = repo.claimJob(job.id, "worker")!!
        repo.completeJobSuccess(claimed.id, "worker")
        assertFailsWith<IllegalArgumentException>("SUCCEEDED job must not be admin-requeueable") {
            repo.adminRequeueFailed(job.id)
        }
    }

    // =========================================================================
    // Status transitions: QUEUED → RUNNING → SUCCEEDED / FAILED
    // =========================================================================

    @Test
    fun `new job starts as QUEUED`() {
        val job = createQueued()
        assertEquals(ImageJobStatus.QUEUED, job.status)
        assertEquals(0, job.attemptCount)
        assertNull(job.claimedByWorker)
        assertNull(job.lastError)
    }

    @Test
    fun `claimJob transitions QUEUED to RUNNING and sets claimedByWorker`() {
        val job = createQueued()
        val running = repo.claimJob(job.id, "my-worker")
        assertNotNull(running)
        assertEquals(ImageJobStatus.RUNNING, running!!.status)
        assertEquals("my-worker", running.claimedByWorker)
        assertNotNull(running.claimedAt)
    }

    @Test
    fun `completeJobSuccess transitions RUNNING to SUCCEEDED`() {
        val job = createQueued()
        val claimed = repo.claimJob(job.id, "worker")!!
        val succeeded = repo.completeJobSuccess(claimed.id, "worker")
        assertNotNull(succeeded)
        assertEquals(ImageJobStatus.SUCCEEDED, succeeded!!.status)
        assertNotNull(succeeded.completedAt)
        assertNull(succeeded.claimedByWorker)
    }

    @Test
    fun `completeJobFailure with no retry transitions RUNNING to FAILED`() {
        val job = createQueued()
        val claimed = repo.claimJob(job.id, "worker")!!
        val failed = repo.completeJobFailure(claimed.id, "worker", "error msg", shouldRetry = false)
        assertNotNull(failed)
        assertEquals(ImageJobStatus.FAILED, failed!!.status)
        assertNotNull(failed.lastError)
        assertNotNull(failed.completedAt)
        assertEquals(1, failed.attemptCount)
    }

    @Test
    fun `completeJobFailure with retry transitions RUNNING to RETRY_WAIT`() {
        // maxAttempts = 3 (default), attemptCount will be 1 after first fail, < maxAttempts → retry
        val job = createQueued()
        val claimed = repo.claimJob(job.id, "worker")!!
        val retrying = repo.completeJobFailure(claimed.id, "worker", "transient error", shouldRetry = true)
        assertNotNull(retrying)
        assertEquals(ImageJobStatus.RETRY_WAIT, retrying!!.status)
        assertEquals(1, retrying.attemptCount)
    }

    // =========================================================================
    // Idempotency
    // =========================================================================

    @Test
    fun `createJob with same idempotency key returns the existing job`() {
        val key = "idem-${UUID.randomUUID()}"
        val job1 = createQueued(key)
        val job2 = createQueued(key)
        assertEquals(job1.id, job2.id, "Same idempotency key must return the same job row")
    }

    @Test
    fun `createJob with different keys creates distinct jobs`() {
        val job1 = createQueued("key-a-${UUID.randomUUID()}")
        val job2 = createQueued("key-b-${UUID.randomUUID()}")
        assertNotNull(job1)
        assertNotNull(job2)
        assert(job1.id != job2.id) { "Different idempotency keys must produce different jobs" }
    }
}
