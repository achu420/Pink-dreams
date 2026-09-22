package com.pinkdreams.imaging.retention

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
import com.pinkdreams.persistence.database.ImageJobs
import com.pinkdreams.persistence.database.Personas
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.ImageJobRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaIdentityRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import com.pinkdreams.persistence.repositories.ReferenceImageRepository
import com.pinkdreams.persistence.repositories.UserRepository
import com.pinkdreams.persistence.repositories.WardrobeRepository
import com.pinkdreams.storage.InMemoryObjectStorage
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ImageRetentionMessageReferenceTest {

    @Test
    fun `cleanup skips terminal jobs still referenced in message metadata`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val storage = InMemoryObjectStorage()
        val jobRepo = ImageJobRepository(db)
        val candidateRepo = GeneratedCandidateRepository(db)

        val identityRepo = PersonaIdentityRepository(db)
        val visualRepo = PersonaVisualVersionRepository(db)
        val personaRepo = PersonaRepository(db)
        val identity = identityRepo.create()
        val version = visualRepo.create(identity.id, 1, "{}", author = "t")
        visualRepo.publishVisualVersion(version.id)
        visualRepo.activateVisualVersion(identity.id, version.id)
        val persona = personaRepo.create(
            slug = "ret-${UUID.randomUUID().toString().take(8)}",
            displayName = "R",
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

        val userId = UUID.randomUUID()
        UserRepository(db).create(userId)
        val conversation = ConversationRepository(db).create(userId, persona.id)

        val service = ImageGenerationService(
            personaRepository = personaRepo,
            visualVersionRepository = visualRepo,
            wardrobeRepository = WardrobeRepository(db),
            referenceImageRepository = ReferenceImageRepository(db, storage),
            orchestrator = ImageGenerationOrchestrator(PromptCompiler(), jobRepo),
            jobRepository = jobRepo,
            candidateRepository = candidateRepo,
        )
        val created = service.create(
            ImageGenerationService.CreateCommand(
                personaId = persona.id,
                idempotencyKey = "ret-ref-1",
                conversationId = conversation.id,
                widthPx = 256,
                heightPx = 256,
            )
        )
        ImageJobWorker(
            db, "ret-worker",
            BridgingImageJobHandler(
                ImageGenerationHandler(
                    FakeImageProvider(),
                    ReferenceImageRepository(db, storage),
                    storage,
                    candidateRepo,
                )
            ),
            jobRepo,
        ).processPendingJobs(5)

        val job = jobRepo.findById(created.job.id)!!
        assertEquals(ImageJobStatus.SUCCEEDED, job.status)
        val candidate = service.getCandidates(job.id).single()

        transaction(db) {
            ImageJobs.update({ ImageJobs.id eq job.id }) {
                it[ImageJobs.completedAt] = LocalDateTime.now().minusDays(60)
            }
        }

        MessageRepository(db).createSystemMessage(
            conversationId = conversation.id,
            content = "photo attached",
            metadata = """{"imageJobId":"${job.id}","imageAssetIds":"${candidate.id}"}""",
        )

        val cleaner = ImageRetentionCleaner(db, storage, candidateRepo, retentionDays = 30)
        val result = cleaner.cleanupOnce()
        assertEquals(0, result.jobsDeleted)
        assertEquals(1, result.jobsSkippedReferenced)
        assertNotNull(jobRepo.findById(job.id))
        assertNotNull(candidateRepo.findById(candidate.id))
    }

    @Test
    fun `cleanup deletes old unreferenced terminal jobs`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val storage = InMemoryObjectStorage()
        val jobRepo = ImageJobRepository(db)
        val candidateRepo = GeneratedCandidateRepository(db)

        val identityRepo = PersonaIdentityRepository(db)
        val visualRepo = PersonaVisualVersionRepository(db)
        val personaRepo = PersonaRepository(db)
        val identity = identityRepo.create()
        val version = visualRepo.create(identity.id, 1, "{}", author = "t")
        visualRepo.publishVisualVersion(version.id)
        visualRepo.activateVisualVersion(identity.id, version.id)
        val persona = personaRepo.create(
            slug = "ret2-${UUID.randomUUID().toString().take(8)}",
            displayName = "R2",
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

        val service = ImageGenerationService(
            personaRepository = personaRepo,
            visualVersionRepository = visualRepo,
            wardrobeRepository = WardrobeRepository(db),
            referenceImageRepository = ReferenceImageRepository(db, storage),
            orchestrator = ImageGenerationOrchestrator(PromptCompiler(), jobRepo),
            jobRepository = jobRepo,
            candidateRepository = candidateRepo,
        )
        val created = service.create(
            ImageGenerationService.CreateCommand(
                personaId = persona.id,
                idempotencyKey = "ret-unref-1",
                widthPx = 256,
                heightPx = 256,
            )
        )
        ImageJobWorker(
            db, "ret-worker-2",
            BridgingImageJobHandler(
                ImageGenerationHandler(
                    FakeImageProvider(),
                    ReferenceImageRepository(db, storage),
                    storage,
                    candidateRepo,
                )
            ),
            jobRepo,
        ).processPendingJobs(5)

        transaction(db) {
            ImageJobs.update({ ImageJobs.id eq created.job.id }) {
                it[ImageJobs.completedAt] = LocalDateTime.now().minusDays(60)
            }
        }

        val cleaner = ImageRetentionCleaner(db, storage, candidateRepo, retentionDays = 30)
        val result = cleaner.cleanupOnce()
        assertEquals(1, result.jobsDeleted)
        assertEquals(0, result.jobsSkippedReferenced)
        assertEquals(null, jobRepo.findById(created.job.id))
    }
}
