package com.pinkdreams.chat.imaging

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageMessageCompletionAttachTest {

    @Test
    fun `worker success merges imageAssetIds onto assistant message with imageJobId`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val storage = InMemoryObjectStorage()
        val jobRepo = ImageJobRepository(db)
        val candidateRepo = GeneratedCandidateRepository(db)
        val messageRepo = MessageRepository(db)
        val conversationRepo = ConversationRepository(db)

        val identityRepo = PersonaIdentityRepository(db)
        val visualRepo = PersonaVisualVersionRepository(db)
        val personaRepo = PersonaRepository(db)
        val identity = identityRepo.create()
        val version = visualRepo.create(identity.id, 1, "{}", author = "t")
        visualRepo.publishVisualVersion(version.id)
        visualRepo.activateVisualVersion(identity.id, version.id)
        val persona = personaRepo.create(
            slug = "att-${UUID.randomUUID().toString().take(8)}",
            displayName = "A",
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
        val conversation = conversationRepo.create(userId, persona.id)

        // Minimal provenance for assistant message
        val engineId = seedEngine(db)
        val coreId = seedCore(db, persona.id)

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
                idempotencyKey = "attach-1",
                conversationId = conversation.id,
                turnRequestId = UUID.randomUUID(),
                userId = userId,
                widthPx = 256,
                heightPx = 256,
            )
        )

        val assistant = messageRepo.createAssistantMessage(
            conversationId = conversation.id,
            content = "here is a photo",
            engineVersionId = engineId,
            personaCoreVersionId = coreId,
            requestId = UUID.randomUUID(),
            metadata = """{"imageJobId":"${created.job.id}","imageJobStatus":"QUEUED"}""",
        )

        val attach = ImageMessageCompletionAttach(messageRepo, candidateRepo)
        ImageJobWorker(
            db = db,
            workerName = "attach-worker",
            jobHandler = BridgingImageJobHandler(
                ImageGenerationHandler(
                    FakeImageProvider(),
                    ReferenceImageRepository(db, storage),
                    storage,
                    candidateRepo,
                )
            ),
            jobRepository = jobRepo,
            completionAttach = { job, succeeded ->
                if (succeeded) attach.onSucceeded(job) else attach.onFailed(job)
            },
        ).processPendingJobs(5)

        assertEquals(ImageJobStatus.SUCCEEDED, jobRepo.findById(created.job.id)!!.status)
        val candidates = service.getCandidates(created.job.id)
        assertEquals(1, candidates.size)

        val updated = messageRepo.findById(assistant.id)!!
        val meta = Json.parseToJsonElement(updated.metadata).jsonObject
        assertEquals("SUCCEEDED", meta["imageJobStatus"]!!.jsonPrimitive.content)
        assertEquals(candidates.single().id.toString(), meta["imageAssetIds"]!!.jsonPrimitive.content)
        assertTrue(meta["imageAssetUrls"]!!.jsonPrimitive.content.contains("/v1/images/assets/"))
    }

    private fun seedEngine(db: org.jetbrains.exposed.sql.Database): UUID {
        val repo = ConversationEngineRepository(db)
        val draft = repo.createNextVersion(content = "test", createdBy = "t")
        repo.publishEngine(draft.id)
        return draft.id
    }

    private fun seedCore(db: org.jetbrains.exposed.sql.Database, personaId: UUID): UUID {
        val repo = PersonaCoreVersionRepository(db)
        val draft = repo.createNextVersion(personaId = personaId, content = "core", author = "t")
        repo.publishCoreVersion(draft.id)
        return draft.id
    }
}
