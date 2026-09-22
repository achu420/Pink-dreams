package com.pinkdreams.api.admin

import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.imaging.job.ImageJobType
import com.pinkdreams.imaging.orchestration.CandidateStatus
import com.pinkdreams.imaging.orchestration.GeneratedCandidateRepository
import com.pinkdreams.module
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ImageJobRepository
import com.pinkdreams.persistence.repositories.PersonaIdentityRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import com.pinkdreams.persistence.repositories.PersonalGuideRepository
import com.pinkdreams.persistence.repositories.ReferenceImageRepository
import com.pinkdreams.storage.InMemoryObjectStorage
import com.pinkdreams.visual.identity.PersonaVisualAdminService
import com.pinkdreams.visual.identity.ReferenceRole
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.Database
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminImageIdentityConsistencyTest {
    private val adminId = UUID.fromString("00000000-0000-0000-0000-0000000000e3")

    private class TestAdminAuthProvider(private val adminId: UUID) : com.pinkdreams.auth.AdminAuthorizationProvider() {
        override fun isAdmin(userId: String): Boolean = userId == adminId.toString()
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.setup(): Database {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        application {
            module(
                databaseConfig = DatabaseConfig(jdbcUrl = "jdbc:h2:mem:test", username = "sa", password = ""),
                llmConfig = LlmConfig(apiKey = "test-key"),
                chatEngine = null,
                adminAuthProvider = TestAdminAuthProvider(adminId),
                database = db,
            )
        }
        return db
    }

    private fun json(body: String) = Json.parseToJsonElement(body).jsonObject

    private data class Seeded(val personaId: UUID, val versionId: UUID, val refIds: List<UUID>)

    private fun seedWithStandardRefs(db: Database): Seeded {
        val personaRepo = PersonaRepository(db)
        val storage = InMemoryObjectStorage()
        val visualAdmin = PersonaVisualAdminService(
            personaRepository = personaRepo,
            personaIdentityRepository = PersonaIdentityRepository(db),
            visualVersionRepository = PersonaVisualVersionRepository(db),
            personalGuideRepository = PersonalGuideRepository(db),
            referenceImageRepository = ReferenceImageRepository(db, storage),
        )
        val persona = personaRepo.create(
            slug = "idcon-${UUID.randomUUID().toString().take(8)}",
            displayName = "Identity Persona",
            gender = "female",
            orientation = "straight",
            apparentAge = 24,
            languageProfile = emptyMap(),
        )
        visualAdmin.ensureDraft(persona.id, author = "test")
        val png = ByteArray(64) { 2 }
        val refIds = ReferenceRole.STANDARD_SLOTS.map { role ->
            visualAdmin.uploadReference(persona.id, role, png, "image/png").id
        }
        val published = visualAdmin.publishAndActivateDraft(persona.id)
        return Seeded(persona.id, published.id, refIds)
    }

    @Test
    fun `Admin generate without standard refs returns clear validation error`() = testApplication {
        val db = setup()
        val personaRepo = PersonaRepository(db)
        val storage = InMemoryObjectStorage()
        val visualAdmin = PersonaVisualAdminService(
            personaRepository = personaRepo,
            personaIdentityRepository = PersonaIdentityRepository(db),
            visualVersionRepository = PersonaVisualVersionRepository(db),
            personalGuideRepository = PersonalGuideRepository(db),
            referenceImageRepository = ReferenceImageRepository(db, storage),
        )
        val persona = personaRepo.create(
            slug = "noref-${UUID.randomUUID().toString().take(8)}",
            displayName = "No Ref",
            gender = "female",
            orientation = "straight",
            apparentAge = 22,
            languageProfile = emptyMap(),
        )
        visualAdmin.ensureDraft(persona.id, author = "test")
        visualAdmin.publishAndActivateDraft(persona.id)

        val res = client.post("/v1/admin/images/jobs") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody(
                """{"personaId":"${persona.id}","idempotencyKey":"k1","seedPrompt":"beach","candidateCount":1}""",
            )
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("Missing required identity references"))
    }

    @Test
    fun `job identity endpoint returns version and reference set`() = testApplication {
        val db = setup()
        val seeded = seedWithStandardRefs(db)
        val jobRepo = ImageJobRepository(db)
        val refsEncoded = Json.encodeToString(seeded.refIds.map { it.toString() })
        // Match ImageGenerationOrchestrator: selectedReferenceIds is a JSON-encoded string field
        val refsField = Json.encodeToString(refsEncoded)
        val job = jobRepo.createJob(
            personaVisualVersionId = seeded.versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "idctx-${UUID.randomUUID()}",
            requestPayload = """{"version":"1","personaId":"${seeded.personaId}","selectedReferenceIds":$refsField,"sceneIntent":{"seedPrompt":"cafe","location":"","outfit":"","presentation":"","expression":"","identity":"x"}}""",
        )

        val res = client.get("/v1/admin/images/jobs/${job.id}/identity") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = json(res.bodyAsText())
        assertEquals(seeded.versionId.toString(), body["visualVersionId"]!!.jsonPrimitive.content)
        assertEquals(seeded.personaId.toString(), body["personaId"]!!.jsonPrimitive.content)
        assertEquals(5, body["referenceImageIds"]!!.jsonArray.size)
        assertTrue(body["references"]!!.jsonArray.size >= 1)
    }

    @Test
    fun `IDENTITY_MISMATCH status can be recorded on candidate`() = testApplication {
        val db = setup()
        val seeded = seedWithStandardRefs(db)
        val jobRepo = ImageJobRepository(db)
        val candidateRepo = GeneratedCandidateRepository(db)
        val job = jobRepo.createJob(
            personaVisualVersionId = seeded.versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "mm-${UUID.randomUUID()}",
            requestPayload = """{"version":"1","personaId":"${seeded.personaId}","sceneIntent":{"seedPrompt":"x","location":"","outfit":"","presentation":"","expression":"","identity":"x"}}""",
        )
        val candidate = candidateRepo.create(
            imageJobId = job.id,
            storageKey = "jobs/${job.id}/0.png",
            contentType = "image/png",
            fileSize = 8,
            widthPx = 64,
            heightPx = 64,
            checksum = "x",
            candidateIndex = 0,
        )
        val patch = client.patch("/v1/admin/images/candidates/${candidate.id}") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"status":"IDENTITY_MISMATCH","adminRemark":"Face does not match reference"}""")
        }
        assertEquals(HttpStatusCode.OK, patch.status)
        assertEquals("IDENTITY_MISMATCH", json(patch.bodyAsText())["status"]!!.jsonPrimitive.content)
        assertEquals(CandidateStatus.IDENTITY_MISMATCH, candidateRepo.findById(candidate.id)!!.status)
    }

    @Test
    fun `unauthenticated identity context is rejected`() = testApplication {
        setup()
        val res = client.get("/v1/admin/images/jobs/${UUID.randomUUID()}/identity")
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }
}
