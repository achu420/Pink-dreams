package com.pinkdreams.imaging.job

import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ImageJobRepository
import com.pinkdreams.persistence.repositories.PersonaIdentityRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import org.jetbrains.exposed.sql.Database
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageJobLeaseHeartbeatTest {

    @Test
    fun `renewLease refreshes claimedAt for owner only`() {
        val (_, repo, versionId) = fresh()
        val job = repo.createJob(versionId, ImageJobType.IMAGE_GENERATION, "hb-1-${UUID.randomUUID()}", "{}")
        assertEquals(ImageJobStatus.QUEUED, job.status)
        val claimed = repo.claimJob(job.id, "worker-a")
        assertNotNull(claimed, "fresh QUEUED job must be claimable")
        assertTrue(repo.renewLease(job.id, "worker-a"))
        assertFalse(repo.renewLease(job.id, "worker-b"))
        assertEquals("worker-a", repo.findById(job.id)!!.claimedByWorker)
    }

    @Test
    fun `renewLease fails after lease release or different owner`() {
        val (_, repo, versionId) = fresh()
        val job = repo.createJob(versionId, ImageJobType.IMAGE_GENERATION, "hb-2-${UUID.randomUUID()}", "{}")
        assertNotNull(repo.claimJob(job.id, "worker-a"))
        repo.releaseLeasedJob(job.id)
        assertFalse(repo.renewLease(job.id, "worker-a"))

        assertNotNull(repo.claimJob(job.id, "worker-b"))
        assertFalse(repo.renewLease(job.id, "worker-a"))
        assertTrue(repo.renewLease(job.id, "worker-b"))
    }

    @Test
    fun `heartbeat keeps job out of stale recovery during long handle`() {
        val (db, repo, versionId) = fresh()
        val job = repo.createJob(versionId, ImageJobType.IMAGE_GENERATION, "hb-3-${UUID.randomUUID()}", "{}")
        assertNotNull(repo.claimJob(job.id, "worker-hb"))

        ImageJobLeaseHeartbeat(repo, job.id, "worker-hb", intervalSeconds = 1).use { heartbeat ->
            heartbeat.start()
            Thread.sleep(2500)
            val recovered = ImageJobWorker(
                db = db,
                workerName = "reaper",
                jobHandler = NoOpImageJobHandler(),
                jobRepository = repo,
                heartbeatIntervalSeconds = 0,
            ).recoverStaleLeasedJobs(leaseTimeoutSeconds = 1)
            assertEquals(0, recovered, "active heartbeat must prevent stale reclaim")
            assertEquals(ImageJobStatus.RUNNING, repo.findById(job.id)!!.status)
            assertEquals("worker-hb", repo.findById(job.id)!!.claimedByWorker)
            assertFalse(heartbeat.hasLostLease())
        }

        val completed = repo.completeJobSuccess(job.id, "worker-hb")
        assertNotNull(completed)
        assertEquals(ImageJobStatus.SUCCEEDED, completed.status)
    }

    @Test
    fun `worker runs heartbeat around handler and still completes`() {
        val (db, repo, versionId) = fresh()
        val key = "hb-3b-${UUID.randomUUID()}"
        repo.createJob(versionId, ImageJobType.IMAGE_GENERATION, key, "{}")

        val worker = ImageJobWorker(
            db = db,
            workerName = "worker-hb",
            jobHandler = object : ImageJobHandler {
                override fun handle(job: ImageJob): ImageJobResult {
                    Thread.sleep(1200)
                    return ImageJobResult.Success("ok")
                }
            },
            jobRepository = repo,
            heartbeatIntervalSeconds = 1,
        )
        val processed = worker.processPendingJobs(5)
        assertTrue(processed >= 1, "expected at least one processed job, got $processed")
        val done = repo.findByIdempotencyKey(versionId, key)!!
        assertEquals(ImageJobStatus.SUCCEEDED, done.status)
    }

    @Test
    fun `stale worker still cannot complete after B reclaim even if A finishes late`() {
        val (_, repo, versionId) = fresh()
        val job = repo.createJob(versionId, ImageJobType.IMAGE_GENERATION, "hb-4-${UUID.randomUUID()}", "{}")
        assertNotNull(repo.claimJob(job.id, "worker-a"))
        repo.releaseLeasedJob(job.id)
        assertNotNull(repo.claimJob(job.id, "worker-b"))
        assertNull(repo.completeJobSuccess(job.id, "worker-a"))
        assertNotNull(repo.completeJobSuccess(job.id, "worker-b"))
    }

    @Test
    fun `default heartbeat interval is strictly less than lease`() {
        val interval = ImageJobWorker.defaultHeartbeatInterval(300)
        assertTrue(interval in 1 until 300)
        assertEquals(100, interval)
    }

    private fun fresh(): Triple<Database, ImageJobRepository, UUID> {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        return Triple(db, ImageJobRepository(db), seedVisual(db))
    }

    private fun seedVisual(db: Database): UUID {
        val identity = PersonaIdentityRepository(db).create()
        val visual = PersonaVisualVersionRepository(db)
        val v = visual.create(identity.id, 1, "{}", author = "t")
        visual.publishVisualVersion(v.id)
        visual.activateVisualVersion(identity.id, v.id)
        return v.id
    }
}
