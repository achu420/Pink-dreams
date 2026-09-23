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
import org.jetbrains.exposed.sql.Database
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for admin image-generation HTTP routes.
 *
 * All persistence goes to an H2 in-memory database.
 * Image generation uses FakeImageProvider (no OPENROUTER_API_KEY in test JVM).
 *
 * Note: POST /v1/admin/images/jobs (trigger generation) requires a persona with
 * an active visual version; the test creates one via PersonaVisualAdminService.
 */
class AdminImageJobRoutesTest {

    private val adminId = UUID.fromString("00000000-0000-0000-0000-0000000000f1")

    private class TestAdminAuth(private val adminId: UUID) : com.pinkdreams.auth.AdminAuthorizationProvider() {
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
                adminAuthProvider = TestAdminAuth(adminId),
                database = db,
            )
        }
        return db
    }

    private fun json(body: String) = Json.parseToJsonElement(body).jsonObject

    private fun seedVisualVersion(db: Database): Pair<UUID, UUID> {
        val storage = InMemoryObjectStorage()
        val personaRepo = PersonaRepository(db)
        val visualAdmin = PersonaVisualAdminService(
            personaRepository = personaRepo,
            personaIdentityRepository = PersonaIdentityRepository(db),
            visualVersionRepository = PersonaVisualVersionRepository(db),
            personalGuideRepository = PersonalGuideRepository(db),
            referenceImageRepository = ReferenceImageRepository(db, storage),
        )
        val persona = personaRepo.create(
            slug = "imgtest-${UUID.randomUUID().toString().take(8)}",
            displayName = "Image Test Persona",
            gender = "female",
            orientation = "straight",
            apparentAge = 25,
            languageProfile = emptyMap(),
        )
        visualAdmin.ensureDraft(persona.id, "test")
        val version = visualAdmin.publishAndActivateDraft(persona.id)
        return persona.id to version.id
    }

    // =========================================================================
    // POST /v1/admin/images/jobs — trigger generation returns 202 with jobId
    // =========================================================================

    @Test
    fun `POST trigger generation returns 202 with jobId`() = testApplication {
        val db = setup()
        val (personaId, _) = seedVisualVersion(db)

        val response = client.post("/v1/admin/images/jobs") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "personaId": "$personaId",
                  "idempotencyKey": "trigger-${UUID.randomUUID()}",
                  "seedPrompt": "soft morning light",
                  "candidateCount": 1,
                  "requireStandardReferences": false
                }
                """.trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.Accepted, response.status, "Expected 202 Accepted; body=${response.bodyAsText()}")
        val body = json(response.bodyAsText())
        assertNotNull(body["jobId"], "Response must include jobId")
        assertTrue(body["jobId"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `POST trigger generation with same idempotency key returns 200 reusing existing job`() = testApplication {
        val db = setup()
        val (personaId, _) = seedVisualVersion(db)
        val key = "idem-trigger-${UUID.randomUUID()}"

        val first = client.post("/v1/admin/images/jobs") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"personaId":"$personaId","idempotencyKey":"$key","requireStandardReferences":false}""")
        }
        assertEquals(HttpStatusCode.Accepted, first.status)
        val firstJobId = json(first.bodyAsText())["jobId"]!!.jsonPrimitive.content

        val second = client.post("/v1/admin/images/jobs") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"personaId":"$personaId","idempotencyKey":"$key","requireStandardReferences":false}""")
        }
        // Reuse of existing job → 200 OK
        assertEquals(HttpStatusCode.OK, second.status)
        val secondJobId = json(second.bodyAsText())["jobId"]!!.jsonPrimitive.content
        assertEquals(firstJobId, secondJobId, "Duplicate idempotency key must return same jobId")
    }

    // =========================================================================
    // GET /v1/admin/images/jobs/{jobId} — returns current job state
    // =========================================================================

    @Test
    fun `GET job status returns current state and jobId`() = testApplication {
        val db = setup()
        val (personaId, versionId) = seedVisualVersion(db)

        // Create job directly via repo
        val jobRepo = ImageJobRepository(db)
        val job = jobRepo.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "get-status-${UUID.randomUUID()}",
            requestPayload = """{"version":"1"}""",
        )

        val response = client.get("/v1/admin/images/jobs/${job.id}") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        assertEquals(job.id.toString(), body["jobId"]!!.jsonPrimitive.content)
        assertEquals("QUEUED", body["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `GET job status returns 404 for unknown jobId`() = testApplication {
        setup()
        val response = client.get("/v1/admin/images/jobs/${UUID.randomUUID()}") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // =========================================================================
    // GET /v1/admin/images/jobs/{jobId}/result — returns ALL candidates
    // =========================================================================

    @Test
    fun `GET job result returns all saved candidates not just first`() = testApplication {
        val db = setup()
        val (_, versionId) = seedVisualVersion(db)

        val jobRepo = ImageJobRepository(db)
        val candidateRepo = GeneratedCandidateRepository(db)

        val job = jobRepo.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "result-${UUID.randomUUID()}",
            requestPayload = """{"version":"1","sceneIntent":{"seedPrompt":"test"}}""",
        )

        // Persist 3 candidates
        repeat(3) { idx ->
            candidateRepo.create(
                imageJobId = job.id,
                storageKey = "jobs/${job.id}/$idx.png",
                contentType = "image/png",
                fileSize = 100L,
                widthPx = 256,
                heightPx = 256,
                checksum = "ck-$idx",
                candidateIndex = idx,
            )
        }

        val response = client.get("/v1/admin/images/jobs/${job.id}/result") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        val candidates = body["candidates"]!!.jsonArray
        assertEquals(3, candidates.size, "All 3 candidates must be returned, not just the first")
    }

    // =========================================================================
    // Shortlisting / declining a candidate does NOT delete the underlying asset
    // =========================================================================

    @Test
    fun `PATCH candidate to SHORTLISTED does not delete candidate row or asset reference`() = testApplication {
        val db = setup()
        val (_, versionId) = seedVisualVersion(db)

        val jobRepo = ImageJobRepository(db)
        val candidateRepo = GeneratedCandidateRepository(db)

        val job = jobRepo.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "shortlist-${UUID.randomUUID()}",
            requestPayload = """{"version":"1","sceneIntent":{"seedPrompt":"test"}}""",
        )
        val storageKey = "jobs/${job.id}/0.png"
        val candidate = candidateRepo.create(
            imageJobId = job.id,
            storageKey = storageKey,
            contentType = "image/png",
            fileSize = 256L,
            widthPx = 256,
            heightPx = 256,
            checksum = "ckshortlist",
            candidateIndex = 0,
        )

        val patch = client.patch("/v1/admin/images/candidates/${candidate.id}") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"status":"SHORTLISTED"}""")
        }
        assertEquals(HttpStatusCode.OK, patch.status)

        // Candidate row still exists with SHORTLISTED status
        val updated = candidateRepo.findById(candidate.id)
        assertNotNull(updated, "Candidate row must not be deleted after SHORTLISTED")
        assertEquals(CandidateStatus.SHORTLISTED, updated!!.status)
        assertEquals(storageKey, updated.storageKey, "storageKey must be unchanged — asset not deleted")
    }

    @Test
    fun `PATCH candidate to DECLINED does not delete candidate row or asset reference`() = testApplication {
        val db = setup()
        val (_, versionId) = seedVisualVersion(db)

        val jobRepo = ImageJobRepository(db)
        val candidateRepo = GeneratedCandidateRepository(db)

        val job = jobRepo.createJob(
            personaVisualVersionId = versionId,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "decline-${UUID.randomUUID()}",
            requestPayload = """{"version":"1","sceneIntent":{"seedPrompt":"test"}}""",
        )
        val storageKey = "jobs/${job.id}/0.png"
        val candidate = candidateRepo.create(
            imageJobId = job.id,
            storageKey = storageKey,
            contentType = "image/png",
            fileSize = 256L,
            widthPx = 256,
            heightPx = 256,
            checksum = "ckdecline",
            candidateIndex = 0,
        )

        val patch = client.patch("/v1/admin/images/candidates/${candidate.id}") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"status":"DECLINED"}""")
        }
        assertEquals(HttpStatusCode.OK, patch.status)

        val updated = candidateRepo.findById(candidate.id)
        assertNotNull(updated, "Candidate row must not be deleted after DECLINED")
        assertEquals(CandidateStatus.DECLINED, updated!!.status)
        assertEquals(storageKey, updated.storageKey, "storageKey must be unchanged — asset not deleted")
    }

    // =========================================================================
    // Auth guard
    // =========================================================================

    @Test
    fun `non-admin user is rejected with 403`() = testApplication {
        setup()
        val nonAdminId = UUID.fromString("00000000-0000-0000-0000-0000000000f9")
        val response = client.get("/v1/admin/images/jobs") {
            basicAuth(nonAdminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}
