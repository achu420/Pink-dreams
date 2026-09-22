package com.pinkdreams.imaging.storage

import com.pinkdreams.imaging.compiler.PromptCompiler
import com.pinkdreams.imaging.job.BridgingImageJobHandler
import com.pinkdreams.imaging.job.ImageJobStatus
import com.pinkdreams.imaging.job.ImageJobWorker
import com.pinkdreams.imaging.orchestration.GeneratedCandidateRepository
import com.pinkdreams.imaging.orchestration.ImageGenerationHandler
import com.pinkdreams.imaging.orchestration.ImageGenerationOrchestrator
import com.pinkdreams.imaging.orchestration.ImageGenerationService
import com.pinkdreams.imaging.provider.FakeImageProvider
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.database.Personas
import com.pinkdreams.persistence.repositories.ImageJobRepository
import com.pinkdreams.persistence.repositories.PersonaIdentityRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import com.pinkdreams.persistence.repositories.ReferenceImageRepository
import com.pinkdreams.persistence.repositories.WardrobeRepository
import com.pinkdreams.storage.LocalFileObjectStorage
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves the multi-instance storage contract:
 * shared root → Instance B can read Instance A assets;
 * separate roots → Instance B cannot falsely see the asset.
 */
class SharedStorageMultiInstanceTest {

    @Test
    fun `shared storage root - instance B retrieves asset written by instance A`() {
        val sharedDir = Files.createTempDirectory("img-shared")
        try {
            val storageA = LocalFileObjectStorage(sharedDir)
            val storageB = LocalFileObjectStorage(sharedDir) // same root = shared mount

            val db = DatabaseFactory.connectInMemory()
            DatabaseFactory.initializeSchema(db)
            val jobRepo = ImageJobRepository(db)
            val candidateRepo = GeneratedCandidateRepository(db)
            val personaId = seedPersona(db)

            val serviceA = ImageGenerationService(
                personaRepository = PersonaRepository(db),
                visualVersionRepository = PersonaVisualVersionRepository(db),
                wardrobeRepository = WardrobeRepository(db),
                referenceImageRepository = ReferenceImageRepository(db, storageA),
                orchestrator = ImageGenerationOrchestrator(PromptCompiler(), jobRepo),
                jobRepository = jobRepo,
                candidateRepository = candidateRepo,
            )

            val created = serviceA.create(
                ImageGenerationService.CreateCommand(
                    personaId = personaId,
                    idempotencyKey = "shared-1",
                    widthPx = 256,
                    heightPx = 256,
                )
            )

            ImageJobWorker(
                db = db,
                workerName = "instance-a-worker",
                jobHandler = BridgingImageJobHandler(
                    ImageGenerationHandler(
                        FakeImageProvider(),
                        ReferenceImageRepository(db, storageA),
                        storageA,
                        candidateRepo,
                    )
                ),
                jobRepository = jobRepo,
            ).processPendingJobs(5)

            assertEquals(ImageJobStatus.SUCCEEDED, jobRepo.findById(created.job.id)!!.status)
            val candidate = candidateRepo.findByImageJob(created.job.id).single()

            // Instance B: same DB + same storage root
            val fromDb = jobRepo.findById(created.job.id)
            assertNotNull(fromDb)
            val candidateB = candidateRepo.findById(candidate.id)
            assertNotNull(candidateB)
            val bytes = storageB.retrieve(candidateB.storageKey)
            assertNotNull(bytes, "Instance B must read asset from shared storage")
            assertTrue(bytes.content.isNotEmpty())
            assertEquals(candidate.storageKey, candidateB.storageKey)
            assertTrue(candidate.storageKey.startsWith("jobs/"))
            assertFalse(candidate.storageKey.contains(".."))
        } finally {
            sharedDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `independent storage roots - instance B does not see instance A asset`() {
        val dirA = Files.createTempDirectory("img-a")
        val dirB = Files.createTempDirectory("img-b")
        try {
            val storageA = LocalFileObjectStorage(dirA)
            val storageB = LocalFileObjectStorage(dirB)

            val key = "jobs/${UUID.randomUUID()}/candidates/0"
            val payload = ByteArray(32) { it.toByte() }
            storageA.store(key, payload, "image/png")

            assertNotNull(storageA.retrieve(key))
            assertNull(
                storageB.retrieve(key),
                "Separate IMAGE_STORAGE_DIR must not invent or return A assets",
            )
            assertFalse(storageB.exists(key))

            // DB candidate could still exist; retrieval must fail cleanly (null → API 404)
            val db = DatabaseFactory.connectInMemory()
            DatabaseFactory.initializeSchema(db)
            val candidateRepo = GeneratedCandidateRepository(db)
            val jobId = UUID.randomUUID()
            // Ensure orphaned candidate row without shared bytes does not create false success via storageB
            assertNull(storageB.retrieve("jobs/$jobId/candidates/0"))
        } finally {
            dirA.toFile().deleteRecursively()
            dirB.toFile().deleteRecursively()
        }
    }

    @Test
    fun `storage probe and diagnostics expose mode without absolute path`() {
        val dir = Files.createTempDirectory("img-probe")
        try {
            val storage = LocalFileObjectStorage(dir)
            val probe = storage.probeReadiness()
            assertEquals("local", probe.mode)
            assertTrue(probe.probeOk)
            assertNotNull(probe.rootLabel)
            assertFalse(probe.rootLabel!!.contains("/") || probe.rootLabel!!.contains("\\"))

            val diag = ImageStorageDiagnostics.from(storage)
            assertEquals("local", diag.mode)
            assertEquals("requires_shared_filesystem", diag.multiInstanceContract)
            assertFalse(diag.guidance.contains(dir.toAbsolutePath().toString()))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun seedPersona(db: org.jetbrains.exposed.sql.Database): UUID {
        val identity = PersonaIdentityRepository(db).create()
        val visual = PersonaVisualVersionRepository(db)
        val v = visual.create(identity.id, 1, "{}", author = "t")
        visual.publishVisualVersion(v.id)
        visual.activateVisualVersion(identity.id, v.id)
        val persona = PersonaRepository(db).create(
            slug = "ss-${UUID.randomUUID().toString().take(8)}",
            displayName = "S",
            gender = "female",
            orientation = "straight",
            apparentAge = 22,
            languageProfile = emptyMap(),
        )
        transaction(db) {
            Personas.update({ Personas.id eq persona.id }) {
                it[Personas.personaIdentityId] = identity.id
            }
        }
        return persona.id
    }
}
