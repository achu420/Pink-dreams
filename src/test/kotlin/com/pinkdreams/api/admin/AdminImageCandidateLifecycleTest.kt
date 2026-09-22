package com.pinkdreams.api.admin

import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.imaging.job.ImageJobType
import com.pinkdreams.imaging.orchestration.CandidateStatus
import com.pinkdreams.imaging.orchestration.GeneratedCandidateRepository
import com.pinkdreams.module
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ImageJobRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.visual.identity.PersonaVisualAdminService
import com.pinkdreams.persistence.repositories.PersonaIdentityRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import com.pinkdreams.persistence.repositories.PersonalGuideRepository
import com.pinkdreams.persistence.repositories.ReferenceImageRepository
import com.pinkdreams.storage.InMemoryObjectStorage
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
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
 * Candidate lifecycle: PATCH status/remark + GET result fields + config.
 */
class AdminImageCandidateLifecycleTest {
    private val adminId = UUID.fromString("00000000-0000-0000-0000-0000000000d1")

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

    @Test
    fun `PATCH candidate status and remark then GET result reflects lifecycle fields`() = testApplication {
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
            slug = "lifecycle-${UUID.randomUUID().toString().take(8)}",
            displayName = "Lifecycle Persona",
            gender = "female",
            orientation = "straight",
            apparentAge = 25,
            languageProfile = emptyMap(),
        )
        visualAdmin.ensureDraft(persona.id, author = "test")
        val published = visualAdmin.publishAndActivateDraft(persona.id)

        val jobRepo = ImageJobRepository(db)
        val candidateRepo = GeneratedCandidateRepository(db)
        val seed = "soft cafe window light, casual smile"
        val job = jobRepo.createJob(
            personaVisualVersionId = published.id,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "lifecycle-seed-${UUID.randomUUID()}",
            requestPayload = """{"version":"1","sceneIntent":{"seedPrompt":"$seed","location":"","outfit":"","presentation":"","expression":"","identity":"Lifecycle Persona"}}""",
        )
        val candidate = candidateRepo.create(
            imageJobId = job.id,
            storageKey = "jobs/${job.id}/0.png",
            contentType = "image/png",
            fileSize = 12,
            widthPx = 256,
            heightPx = 256,
            checksum = "abc",
            candidateIndex = 0,
        )
        assertEquals(CandidateStatus.GENERATED, candidate.status)

        val patch = client.patch("/v1/admin/images/candidates/${candidate.id}") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"status":"SHORTLISTED","adminRemark":"keep this vibe"}""")
        }
        assertEquals(HttpStatusCode.OK, patch.status)
        val patchBody = json(patch.bodyAsText())
        assertEquals("SHORTLISTED", patchBody["status"]!!.jsonPrimitive.content)
        assertEquals("keep this vibe", patchBody["adminRemark"]!!.jsonPrimitive.content)
        assertEquals(seed, patchBody["seedPrompt"]!!.jsonPrimitive.content)

        val result = client.get("/v1/admin/images/jobs/${job.id}/result") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.OK, result.status)
        val resultBody = json(result.bodyAsText())
        val candidates = resultBody["candidates"]!!.jsonArray
        assertEquals(1, candidates.size)
        val c0 = candidates[0].jsonObject
        assertEquals("SHORTLISTED", c0["status"]!!.jsonPrimitive.content)
        assertEquals("keep this vibe", c0["adminRemark"]!!.jsonPrimitive.content)
        assertEquals(seed, c0["seedPrompt"]!!.jsonPrimitive.content)
        assertNotNull(c0["createdAt"])
    }

    @Test
    fun `GET images config returns provider model and candidate defaults`() = testApplication {
        setup()
        val response = client.get("/v1/admin/images/config") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        assertTrue(body["provider"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(body["model"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals(4, body["maxCandidateCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(4, body["defaultCandidateCount"]!!.jsonPrimitive.content.toInt())
    }
}
