package com.pinkdreams.api.images

import com.pinkdreams.imaging.compiler.PromptCompiler
import com.pinkdreams.imaging.job.BridgingImageJobHandler
import com.pinkdreams.imaging.job.ImageJobWorker
import com.pinkdreams.imaging.orchestration.GeneratedCandidateRepository
import com.pinkdreams.imaging.orchestration.ImageGenerationHandler
import com.pinkdreams.imaging.orchestration.ImageGenerationOrchestrator
import com.pinkdreams.imaging.orchestration.ImageGenerationService
import com.pinkdreams.imaging.provider.FakeImageProvider
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
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.basic
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * IMG-11: conversation-scoped job/asset ownership over HTTP.
 * User A (owner) succeeds; User B and anonymous are denied.
 */
class UserImageOwnershipSecurityTest {

    @Test
    fun `owner can retrieve job result and asset - other user and anonymous cannot`() = testApplication {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        UserRepository(db).create(userA)
        UserRepository(db).create(userB)

        val storage = InMemoryObjectStorage()
        val personaRepo = PersonaRepository(db)
        val identityRepo = PersonaIdentityRepository(db)
        val visualRepo = PersonaVisualVersionRepository(db)
        val conversationRepo = ConversationRepository(db)
        val jobRepo = ImageJobRepository(db)
        val candidateRepo = GeneratedCandidateRepository(db)

        val identity = identityRepo.create()
        val version = visualRepo.create(identity.id, 1, """{"face":{"shape":"oval"}}""", author = "test")
        visualRepo.publishVisualVersion(version.id)
        visualRepo.activateVisualVersion(identity.id, version.id)

        val persona = personaRepo.create(
            slug = "own-${UUID.randomUUID().toString().take(8)}",
            displayName = "OwnerPersona",
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

        val conversation = conversationRepo.create(userA, persona.id)
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
                idempotencyKey = "own-key-1",
                conversationId = conversation.id,
                turnRequestId = UUID.randomUUID(),
                userId = userA,
                presentation = "show me a selfie",
                widthPx = 256,
                heightPx = 256,
            )
        )

        ImageJobWorker(
            db = db,
            workerName = "own-test-worker",
            jobHandler = BridgingImageJobHandler(
                ImageGenerationHandler(
                    FakeImageProvider(),
                    ReferenceImageRepository(db, storage),
                    storage,
                    candidateRepo,
                )
            ),
            jobRepository = jobRepo,
        ).processPendingJobs(5)

        val assetId = service.getCandidates(created.job.id).single().id
        val jobId = created.job.id

        application {
            install(ContentNegotiation) { json() }
            install(Authentication) {
                basic("dev-auth") {
                    realm = "test"
                    validate { creds ->
                        if (creds.name.isNotBlank() && creds.password.isNotBlank()) {
                            UserIdPrincipal(creds.name)
                        } else {
                            null
                        }
                    }
                }
            }
            routing {
                UserImageRoutes(
                    imageGenerationService = service,
                    imageJobRepository = jobRepo,
                    candidateRepository = candidateRepo,
                    conversationRepository = conversationRepo,
                    objectStorage = storage,
                ).register(this)
            }
        }

        assertEquals(
            HttpStatusCode.OK,
            client.get("/v1/images/jobs/$jobId") { basicAuth(userA.toString(), "password") }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            client.get("/v1/images/jobs/$jobId/result") { basicAuth(userA.toString(), "password") }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            client.get("/v1/images/assets/$assetId") { basicAuth(userA.toString(), "password") }.status,
        )

        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/v1/images/jobs/$jobId") { basicAuth(userB.toString(), "password") }.status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/v1/images/jobs/$jobId/result") { basicAuth(userB.toString(), "password") }.status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/v1/images/assets/$assetId") { basicAuth(userB.toString(), "password") }.status,
        )

        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/images/jobs/$jobId").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/images/assets/$assetId").status)
    }

    @Test
    fun `admin-style job without conversationId is forbidden for end users`() = testApplication {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userA = UUID.randomUUID()
        UserRepository(db).create(userA)

        val storage = InMemoryObjectStorage()
        val personaRepo = PersonaRepository(db)
        val identityRepo = PersonaIdentityRepository(db)
        val visualRepo = PersonaVisualVersionRepository(db)
        val jobRepo = ImageJobRepository(db)
        val candidateRepo = GeneratedCandidateRepository(db)

        val identity = identityRepo.create()
        val version = visualRepo.create(identity.id, 1, "{}", author = "test")
        visualRepo.publishVisualVersion(version.id)
        visualRepo.activateVisualVersion(identity.id, version.id)
        val persona = personaRepo.create(
            slug = "adm-${UUID.randomUUID().toString().take(8)}",
            displayName = "A",
            gender = "female",
            orientation = "straight",
            apparentAge = 24,
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
        // No conversationId — mirrors admin create without chat scope
        val created = service.create(
            ImageGenerationService.CreateCommand(
                personaId = persona.id,
                idempotencyKey = "no-conv",
                widthPx = 256,
                heightPx = 256,
            )
        )

        application {
            install(ContentNegotiation) { json() }
            install(Authentication) {
                basic("dev-auth") {
                    validate { UserIdPrincipal(it.name) }
                }
            }
            routing {
                UserImageRoutes(
                    imageGenerationService = service,
                    imageJobRepository = jobRepo,
                    candidateRepository = candidateRepo,
                    conversationRepository = ConversationRepository(db),
                    objectStorage = storage,
                ).register(this)
            }
        }

        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/v1/images/jobs/${created.job.id}") {
                basicAuth(userA.toString(), "password")
            }.status,
        )
    }
}
