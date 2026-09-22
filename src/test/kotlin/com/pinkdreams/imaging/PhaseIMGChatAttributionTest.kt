package com.pinkdreams.imaging

import com.pinkdreams.imaging.compiler.PromptCompiler
import com.pinkdreams.imaging.orchestration.GeneratedCandidateRepository
import com.pinkdreams.imaging.orchestration.ImageGenerationOrchestrator
import com.pinkdreams.imaging.orchestration.ImageGenerationService
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.database.Personas
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.ImageJobRepository
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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhaseIMGChatAttributionTest {

    private lateinit var db: org.jetbrains.exposed.sql.Database
    private lateinit var service: ImageGenerationService
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var personaId: UUID
    private lateinit var userId: UUID
    private lateinit var conversationId: UUID

    @BeforeTest
    fun setup() {
        db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val storage = InMemoryObjectStorage()
        val jobRepo = ImageJobRepository(db)
        val candidateRepo = GeneratedCandidateRepository(db)
        val identityRepo = PersonaIdentityRepository(db)
        val visualRepo = PersonaVisualVersionRepository(db)
        val wardrobeRepo = WardrobeRepository(db)
        val refRepo = ReferenceImageRepository(db, storage)
        val personaRepo = PersonaRepository(db)
        conversationRepo = ConversationRepository(db)

        val identity = identityRepo.create()
        val version = visualRepo.create(
            personaIdentityId = identity.id,
            version = 1,
            physicalGuide = """{"face":{"shape":"oval"}}""",
            author = "test",
        )
        visualRepo.publishVisualVersion(version.id)
        visualRepo.activateVisualVersion(identity.id, version.id)

        val persona = personaRepo.create(
            slug = "attr-${UUID.randomUUID().toString().take(8)}",
            displayName = "Simran",
            gender = "female",
            orientation = "straight",
            apparentAge = 24,
            languageProfile = mapOf("primary" to "en"),
        )
        transaction(db) {
            Personas.update({ Personas.id eq persona.id }) {
                it[Personas.personaIdentityId] = identity.id
            }
        }
        personaId = persona.id

        userId = UUID.randomUUID()
        UserRepository(db).create(userId)
        conversationId = conversationRepo.create(userId, personaId).id

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
    fun `create stores conversationId in job payload for ownership checks`() {
        val turnId = UUID.randomUUID()
        val result = service.create(
            ImageGenerationService.CreateCommand(
                personaId = personaId,
                idempotencyKey = "attr-key-1",
                conversationId = conversationId,
                turnRequestId = turnId,
                userId = userId,
                presentation = "show me a selfie",
            )
        )
        val payload = Json.parseToJsonElement(result.job.requestPayload).jsonObject
        assertEquals(conversationId.toString(), payload["conversationId"]!!.jsonPrimitive.content)
        assertEquals(turnId.toString(), payload["turnRequestId"]!!.jsonPrimitive.content)
        assertEquals(userId.toString(), payload["userId"]!!.jsonPrimitive.content)
        val owned = conversationRepo.findByIdForUser(conversationId, userId)
        assertNotNull(owned)
        assertTrue(payload["metadata"]!!.jsonObject["conversationId"]!!.jsonPrimitive.content == conversationId.toString())
    }
}
