package com.pinkdreams.imaging.job

import com.pinkdreams.chat.imaging.ImageMessageCompletionAttach
import com.pinkdreams.imaging.compiler.PromptCompiler
import com.pinkdreams.imaging.orchestration.GeneratedCandidateRepository
import com.pinkdreams.imaging.orchestration.ImageGenerationHandler
import com.pinkdreams.imaging.orchestration.ImageGenerationOrchestrator
import com.pinkdreams.imaging.orchestration.ImageGenerationService
import com.pinkdreams.imaging.provider.FakeImageProvider
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.database.Personas
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.ImageJobRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaIdentityRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import com.pinkdreams.persistence.repositories.ReferenceImageRepository
import com.pinkdreams.persistence.repositories.UserRepository
import com.pinkdreams.persistence.repositories.WardrobeRepository
import com.pinkdreams.storage.InMemoryObjectStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageJobMultiInstanceHardeningTest {

    @Test
    fun `concurrent claim - only one worker wins`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val jobRepo = ImageJobRepository(db)
        val versionId = seedVisual(db)
        val job = jobRepo.createJob(versionId, ImageJobType.IMAGE_GENERATION, "conc-1", "{}")

        val winners = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        repeat(8) { i ->
            pool.submit {
                latch.await()
                if (jobRepo.claimJob(job.id, "w-$i") != null) winners.incrementAndGet()
            }
        }
        latch.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(1, winners.get())
        assertEquals(ImageJobStatus.RUNNING, jobRepo.findById(job.id)!!.status)
    }

    @Test
    fun `concurrent idempotent create returns same job id`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val jobRepo = ImageJobRepository(db)
        val versionId = seedVisual(db)
        val ids = java.util.Collections.synchronizedSet(mutableSetOf<UUID>())
        val latch = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        repeat(8) {
            pool.submit {
                latch.await()
                ids.add(
                    jobRepo.createJob(versionId, ImageJobType.IMAGE_GENERATION, "idem-conc", "{}").id
                )
            }
        }
        latch.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(1, ids.size)
    }

    @Test
    fun `reconcile attaches assets after SUCCEEDED without prior attach`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val storage = InMemoryObjectStorage()
        val jobRepo = ImageJobRepository(db)
        val candidateRepo = GeneratedCandidateRepository(db)
        val messageRepo = MessageRepository(db)

        val identityRepo = PersonaIdentityRepository(db)
        val visualRepo = PersonaVisualVersionRepository(db)
        val personaRepo = PersonaRepository(db)
        val identity = identityRepo.create()
        val version = visualRepo.create(identity.id, 1, "{}", author = "t")
        visualRepo.publishVisualVersion(version.id)
        visualRepo.activateVisualVersion(identity.id, version.id)
        val persona = personaRepo.create(
            slug = "mi-${UUID.randomUUID().toString().take(8)}",
            displayName = "M",
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
        val engineId = ConversationEngineRepository(db).createNextVersion("e", createdBy = "t").also {
            ConversationEngineRepository(db).publishEngine(it.id)
        }.id
        val coreId = PersonaCoreVersionRepository(db).createNextVersion(persona.id, "c", author = "t").also {
            PersonaCoreVersionRepository(db).publishCoreVersion(it.id)
        }.id

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
                idempotencyKey = "mi-attach",
                conversationId = conversation.id,
                widthPx = 256,
                heightPx = 256,
            )
        )
        val assistant = messageRepo.createAssistantMessage(
            conversationId = conversation.id,
            content = "photo",
            engineVersionId = engineId,
            personaCoreVersionId = coreId,
            requestId = UUID.randomUUID(),
            metadata = """{"imageJobId":"${created.job.id}","imageJobStatus":"QUEUED"}""",
        )

        // Complete without attach hook
        ImageJobWorker(
            db, "w1",
            BridgingImageJobHandler(
                ImageGenerationHandler(
                    FakeImageProvider(),
                    ReferenceImageRepository(db, storage),
                    storage,
                    candidateRepo,
                )
            ),
            jobRepo,
            completionAttach = null,
        ).processPendingJobs(5)
        assertEquals(ImageJobStatus.SUCCEEDED, jobRepo.findById(created.job.id)!!.status)
        assertNull(
            Json.parseToJsonElement(messageRepo.findById(assistant.id)!!.metadata)
                .jsonObject["imageAssetIds"]
        )

        val attach = ImageMessageCompletionAttach(messageRepo, candidateRepo)
        ImageJobWorker(
            db, "w2",
            BridgingImageJobHandler(
                ImageGenerationHandler(FakeImageProvider(), ReferenceImageRepository(db, storage), storage, candidateRepo)
            ),
            jobRepo,
            completionAttach = { job, ok -> if (ok) attach.onSucceeded(job) else attach.onFailed(job) },
        ).reconcileMessageAttachments(10)

        val meta = Json.parseToJsonElement(messageRepo.findById(assistant.id)!!.metadata).jsonObject
        assertEquals("SUCCEEDED", meta["imageJobStatus"]!!.jsonPrimitive.content)
        assertNotNull(meta["imageAssetIds"])
    }

    private fun seedVisual(db: org.jetbrains.exposed.sql.Database): UUID {
        val identity = PersonaIdentityRepository(db).create()
        val visual = PersonaVisualVersionRepository(db)
        val v = visual.create(identity.id, 1, "{}", author = "t")
        visual.publishVisualVersion(v.id)
        visual.activateVisualVersion(identity.id, v.id)
        return v.id
    }
}
