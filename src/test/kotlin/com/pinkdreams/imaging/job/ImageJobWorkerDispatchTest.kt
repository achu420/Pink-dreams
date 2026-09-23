package com.pinkdreams.imaging.job

import com.pinkdreams.imaging.orchestration.GeneratedCandidateRepository
import com.pinkdreams.imaging.orchestration.ImageGenerationHandler
import com.pinkdreams.imaging.provider.FakeImageProvider
import com.pinkdreams.imaging.job.BridgingImageJobHandler
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ImageJobRepository
import com.pinkdreams.persistence.repositories.PersonaIdentityRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.PersonalGuideRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import com.pinkdreams.persistence.repositories.ReferenceImageRepository
import com.pinkdreams.storage.InMemoryObjectStorage
import com.pinkdreams.visual.identity.PersonaVisualAdminService
import org.jetbrains.exposed.sql.Database
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit / integration tests for [ImageJobWorker] job-dispatch logic.
 *
 * Uses H2 in-memory DB and either [NoOpImageJobHandler] (for pure status-
 * transition tests) or [FakeImageProvider]-backed [ImageGenerationHandler]
 * (for candidate-persistence tests).
 */
class ImageJobWorkerDispatchTest {

    private lateinit var db: Database
    private lateinit var jobRepo: ImageJobRepository
    private lateinit var candidateRepo: GeneratedCandidateRepository
    private lateinit var storage: InMemoryObjectStorage
    private lateinit var visualVersionId: UUID

    @BeforeTest
    fun setup() {
        db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        jobRepo = ImageJobRepository(db)
        candidateRepo = GeneratedCandidateRepository(db)
        storage = InMemoryObjectStorage()
        visualVersionId = createPublishedVisualVersion()
    }

    private fun createPublishedVisualVersion(): UUID {
        val personaRepo = PersonaRepository(db)
        val visualAdmin = PersonaVisualAdminService(
            personaRepository = personaRepo,
            personaIdentityRepository = PersonaIdentityRepository(db),
            visualVersionRepository = PersonaVisualVersionRepository(db),
            personalGuideRepository = PersonalGuideRepository(db),
            referenceImageRepository = ReferenceImageRepository(db, storage),
        )
        val persona = personaRepo.create(
            slug = "worker-${UUID.randomUUID().toString().take(8)}",
            displayName = "Worker Test",
            gender = "female",
            orientation = "straight",
            apparentAge = 25,
            languageProfile = emptyMap(),
        )
        visualAdmin.ensureDraft(persona.id, "test")
        return visualAdmin.publishAndActivateDraft(persona.id).id
    }

    private fun createQueuedJob(): ImageJob {
        val idem = UUID.randomUUID().toString()
        return jobRepo.createJob(
            personaVisualVersionId = visualVersionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "dispatch-$idem",
            requestPayload = """{"prompt":"test scene prompt","idempotencyKey":"dispatch-$idem","candidateCount":1}""",
        )
    }

    private fun workerWith(handler: ImageJobHandler) = ImageJobWorker(
        db = db,
        workerName = "test-worker",
        jobHandler = handler,
        jobRepository = jobRepo,
        heartbeatIntervalSeconds = 0L,   // disable heartbeat in tests
    )

    // =========================================================================
    // Job status transitions
    // =========================================================================

    @Test
    fun `QUEUED job is picked up and transitions through RUNNING to SUCCEEDED`() {
        val job = createQueuedJob()
        assertEquals(ImageJobStatus.QUEUED, job.status)

        val processed = workerWith(NoOpImageJobHandler()).processPendingJobs(maxBatchSize = 10)
        assertEquals(1, processed, "Worker should have processed exactly 1 job")

        val updated = jobRepo.findById(job.id)!!
        assertEquals(ImageJobStatus.SUCCEEDED, updated.status)
        assertNotNull(updated.completedAt)
        assertNull(updated.claimedByWorker)   // released on completion
    }

    @Test
    fun `on provider failure job transitions to FAILED with error recorded`() {
        val job = createQueuedJob()

        val failHandler = object : ImageJobHandler {
            override fun handle(job: ImageJob): ImageJobResult =
                ImageJobResult.Failure(errorMessage = "simulated-provider-failure", retryable = false)
        }
        workerWith(failHandler).processPendingJobs(10)

        val updated = jobRepo.findById(job.id)!!
        assertEquals(ImageJobStatus.FAILED, updated.status)
        assertNotNull(updated.lastError, "FAILED job must have lastError recorded")
        assertNotNull(updated.completedAt)
    }

    @Test
    fun `on retryable failure with attempts remaining job transitions to RETRY_WAIT`() {
        val job = createQueuedJob() // default maxAttempts = 3

        val retryHandler = object : ImageJobHandler {
            override fun handle(job: ImageJob): ImageJobResult =
                ImageJobResult.Failure(errorMessage = "transient error", retryable = true)
        }
        workerWith(retryHandler).processPendingJobs(10)

        val updated = jobRepo.findById(job.id)!!
        assertEquals(ImageJobStatus.RETRY_WAIT, updated.status)
        assertEquals(1, updated.attemptCount)
    }

    @Test
    fun `worker does not re-process a job that is already SUCCEEDED`() {
        val job = createQueuedJob()
        val worker = workerWith(NoOpImageJobHandler())

        val first = worker.processPendingJobs(10)
        val second = worker.processPendingJobs(10)

        assertEquals(1, first, "First run should process the job")
        assertEquals(0, second, "Second run has nothing to process (job is terminal)")
        assertEquals(ImageJobStatus.SUCCEEDED, jobRepo.findById(job.id)!!.status)
    }

    @Test
    fun `two distinct QUEUED jobs are both processed in one batch`() {
        val job1 = createQueuedJob()
        val job2 = createQueuedJob()

        val processed = workerWith(NoOpImageJobHandler()).processPendingJobs(10)
        assertEquals(2, processed)
        assertEquals(ImageJobStatus.SUCCEEDED, jobRepo.findById(job1.id)!!.status)
        assertEquals(ImageJobStatus.SUCCEEDED, jobRepo.findById(job2.id)!!.status)
    }

    // =========================================================================
    // Candidate persistence (uses FakeImageProvider-backed handler)
    // =========================================================================

    @Test
    fun `on provider success candidates are persisted in GeneratedCandidateRepository`() {
        val job = createQueuedJob()

        // Wire a real ImageGenerationHandler backed by FakeImageProvider and InMemoryObjectStorage
        val handler = BridgingImageJobHandler(
            ImageGenerationHandler(
                imageProvider = FakeImageProvider(),
                referenceImageRepository = ReferenceImageRepository(db, storage),
                objectStorage = storage,
                generatedCandidateRepository = candidateRepo,
            )
        )
        workerWith(handler).processPendingJobs(10)

        val updated = jobRepo.findById(job.id)!!
        assertEquals(ImageJobStatus.SUCCEEDED, updated.status)

        val candidates = candidateRepo.findByImageJob(job.id)
        assert(candidates.isNotEmpty()) {
            "Candidates must be persisted after successful generation"
        }
    }

    @Test
    fun `on provider failure no spurious candidates are persisted`() {
        val job = createQueuedJob()

        val failHandler = object : ImageJobHandler {
            override fun handle(job: ImageJob): ImageJobResult =
                ImageJobResult.Failure(errorMessage = "hard fail", retryable = false)
        }
        workerWith(failHandler).processPendingJobs(10)

        assertEquals(ImageJobStatus.FAILED, jobRepo.findById(job.id)!!.status)
        val candidates = candidateRepo.findByImageJob(job.id)
        assert(candidates.isEmpty()) {
            "No candidates must be persisted when generation fails; found: $candidates"
        }
    }

    // =========================================================================
    // Idempotency via job creation
    // =========================================================================

    @Test
    fun `submitting the same idempotency key twice does not create duplicate jobs`() {
        val key = "idem-${UUID.randomUUID()}"
        val job1 = jobRepo.createJob(
            personaVisualVersionId = visualVersionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = key,
            requestPayload = """{"version":"1"}""",
        )
        val job2 = jobRepo.createJob(
            personaVisualVersionId = visualVersionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = key,
            requestPayload = """{"version":"1"}""",
        )
        assertEquals(job1.id, job2.id, "Same idempotency key must not create a second job row")

        // Worker must process the single job exactly once
        val processed = workerWith(NoOpImageJobHandler()).processPendingJobs(10)
        assertEquals(1, processed)
    }
}
