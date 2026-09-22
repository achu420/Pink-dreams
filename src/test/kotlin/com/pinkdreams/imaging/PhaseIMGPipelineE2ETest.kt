package com.pinkdreams.imaging

import com.pinkdreams.imaging.compiler.PromptCompiler
import com.pinkdreams.imaging.job.BridgingImageJobHandler
import com.pinkdreams.imaging.job.ImageJobStatus
import com.pinkdreams.imaging.job.ImageJobWorker
import com.pinkdreams.imaging.observability.ImageGenerationEventRepository
import com.pinkdreams.imaging.observability.ObservableImageJobHandler
import com.pinkdreams.imaging.orchestration.GeneratedCandidateRepository
import com.pinkdreams.imaging.orchestration.ImageGenerationHandler
import com.pinkdreams.imaging.orchestration.ImageGenerationOrchestrator
import com.pinkdreams.imaging.orchestration.ImageGenerationService
import com.pinkdreams.imaging.provider.FakeImageProvider
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ImageJobRepository
import com.pinkdreams.persistence.repositories.PersonaIdentityRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import com.pinkdreams.persistence.repositories.ReferenceImageRepository
import com.pinkdreams.persistence.repositories.WardrobeRepository
import com.pinkdreams.storage.InMemoryObjectStorage
import com.pinkdreams.storage.LocalFileObjectStorage
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.util.UUID

/**
 * Phase B/C integration: request → job → worker → fake provider → durable asset.
 */
class PhaseIMGPipelineE2ETest {

    private lateinit var db: org.jetbrains.exposed.sql.Database
    private lateinit var service: ImageGenerationService
    private lateinit var worker: ImageJobWorker
    private lateinit var storage: InMemoryObjectStorage
    private lateinit var events: ImageGenerationEventRepository
    private lateinit var personaId: UUID

    @BeforeTest
    fun setup() {
        db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        storage = InMemoryObjectStorage()
        events = ImageGenerationEventRepository(db)

        val jobRepo = ImageJobRepository(db)
        val candidateRepo = GeneratedCandidateRepository(db)
        val identityRepo = PersonaIdentityRepository(db)
        val visualRepo = PersonaVisualVersionRepository(db)
        val wardrobeRepo = WardrobeRepository(db)
        val refRepo = ReferenceImageRepository(db, storage)
        val personaRepo = PersonaRepository(db)

        val identity = identityRepo.create()
        val version = visualRepo.create(
            personaIdentityId = identity.id,
            version = 1,
            physicalGuide = """{"face":{"shape":"oval"},"hair":{"color":"black","length":"long"}}""",
            author = "test",
        )
        visualRepo.publishVisualVersion(version.id)
        visualRepo.activateVisualVersion(identity.id, version.id)

        val persona = personaRepo.create(
            slug = "e2e-simran-${UUID.randomUUID().toString().take(8)}",
            displayName = "Simran",
            gender = "female",
            orientation = "straight",
            apparentAge = 24,
            languageProfile = mapOf("primary" to "en"),
        )
        transaction(db) {
            com.pinkdreams.persistence.database.Personas.update({
                com.pinkdreams.persistence.database.Personas.id eq persona.id
            }) {
                it[com.pinkdreams.persistence.database.Personas.personaIdentityId] = identity.id
            }
        }
        personaId = persona.id

        val provider = FakeImageProvider()
        val handler = ImageGenerationHandler(provider, refRepo, storage, candidateRepo)
        worker = ImageJobWorker(
            db = db,
            workerName = "e2e-worker",
            jobHandler = ObservableImageJobHandler(
                BridgingImageJobHandler(handler),
                events,
                provider.providerId,
                model = "fake",
            ),
            jobRepository = jobRepo,
        )
        service = ImageGenerationService(
            personaRepository = personaRepo,
            visualVersionRepository = visualRepo,
            wardrobeRepository = wardrobeRepo,
            referenceImageRepository = refRepo,
            orchestrator = ImageGenerationOrchestrator(PromptCompiler(), jobRepo),
            jobRepository = jobRepo,
            candidateRepository = candidateRepo,
        )
    }

    @Test
    fun `e2e create job worker produces candidates and observability event`() {
        val created = service.create(
            ImageGenerationService.CreateCommand(
                personaId = personaId,
                idempotencyKey = "e2e-key-1",
                location = "mountain road",
                candidateCount = 1,
                widthPx = 256,
                heightPx = 256,
            )
        )
        assertEquals(ImageJobStatus.QUEUED, created.job.status)

        val processed = worker.processPendingJobs(10)
        assertTrue(processed >= 1)

        val job = service.getJob(created.job.id)!!
        assertEquals(ImageJobStatus.SUCCEEDED, job.status)

        val candidates = service.getCandidates(created.job.id)
        assertEquals(1, candidates.size)
        assertNotNull(storage.retrieve(candidates[0].storageKey))

        val reused = service.create(
            ImageGenerationService.CreateCommand(
                personaId = personaId,
                idempotencyKey = "e2e-key-1",
                location = "ignored",
            )
        )
        assertTrue(reused.reusedExisting)
        assertEquals(created.job.id, reused.job.id)

        assertTrue(events.findByJob(created.job.id).isNotEmpty())
    }

    @Test
    fun `local file storage rejects path traversal`() {
        val dir = Files.createTempDirectory("img-store-test")
        val store = LocalFileObjectStorage(dir)
        try {
            store.store("jobs/ok.png", byteArrayOf(1, 2, 3), "image/png")
            assertTrue(store.exists("jobs/ok.png"))
            var threw = false
            try {
                store.store("../escape.png", byteArrayOf(1), "image/png")
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue(threw)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
