package com.pinkdreams.api.admin

import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.imaging.job.ImageJobType
import com.pinkdreams.imaging.orchestration.GeneratedCandidateRepository
import com.pinkdreams.module
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.database.Personas
import com.pinkdreams.persistence.repositories.ImageJobRepository
import com.pinkdreams.persistence.repositories.PersonaIdentityRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import com.pinkdreams.persistence.repositories.ReferenceImageRepository
import com.pinkdreams.persistence.repositories.WardrobeRepository
import com.pinkdreams.storage.InMemoryObjectStorage
import com.pinkdreams.visual.identity.ReferenceRole
import com.pinkdreams.visual.identity.ReferenceSource
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Persona Detail — Overview + Visual Identity tab backend. Read-only; asserts
 * the auth gate, the no-identity case, and the full read path across visual
 * versions, wardrobe, reference images, image jobs and generated candidates.
 */
class AdminPersonaVisualRoutesTest {
    private val adminId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val nonAdminId = UUID.fromString("00000000-0000-0000-0000-0000000000c2")

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

    private fun seedPersona(db: Database): UUID = PersonaRepository(db).create(
        slug = "visual-persona-${UUID.randomUUID()}",
        displayName = "Visual Persona",
        gender = "female",
        orientation = "straight",
        apparentAge = 26,
        languageProfile = emptyMap(),
    ).id

    @Test
    fun `single persona endpoint returns persona and 404 for unknown id`() = testApplication {
        val db = setup()
        val personaId = seedPersona(db)

        val ok = client.get("/v1/admin/personas/$personaId") { basicAuth(adminId.toString(), "x") }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("Visual Persona", json(ok.bodyAsText())["displayName"]!!.jsonPrimitive.content)

        val missing = client.get("/v1/admin/personas/${UUID.randomUUID()}") { basicAuth(adminId.toString(), "x") }
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }

    @Test
    fun `visual endpoint rejects non-admin and unauthenticated callers`() = testApplication {
        val db = setup()
        val personaId = seedPersona(db)

        val anonymous = client.get("/v1/admin/personas/$personaId/visual")
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)

        val forbidden = client.get("/v1/admin/personas/$personaId/visual") {
            basicAuth(nonAdminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
    }

    @Test
    fun `visual endpoint reports no identity when persona has none`() = testApplication {
        val db = setup()
        val personaId = seedPersona(db)

        val response = client.get("/v1/admin/personas/$personaId/visual") { basicAuth(adminId.toString(), "x") }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        assertFalse(body["hasIdentity"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(0, body["versions"]!!.jsonArray.size)
        assertTrue(body["generationTriggerWired"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `visual endpoint returns guide wardrobe references jobs and candidates`() = testApplication {
        val db = setup()
        val personaId = seedPersona(db)

        val identity = PersonaIdentityRepository(db).create()
        transaction(db) {
            Personas.update({ Personas.id eq personaId }) { it[Personas.personaIdentityId] = identity.id }
        }

        val visualRepo = PersonaVisualVersionRepository(db)
        val version = visualRepo.create(
            personaIdentityId = identity.id,
            version = 1,
            physicalGuide = """{"hair":"auburn"}""",
            styleConstraints = """{"noLogos":true}""",
        )
        WardrobeRepository(db).addItem(
            versionId = version.id,
            category = "TOP",
            subcategory = "blouse",
            name = "Silk blouse",
            color = "ivory",
        )
        ReferenceImageRepository(db, InMemoryObjectStorage()).uploadReference(
            personaVisualVersionId = version.id,
            personaIdentityId = identity.id,
            content = byteArrayOf(1, 2, 3),
            contentType = "image/png",
            role = ReferenceRole.FACE,
            source = ReferenceSource.HUMAN_UPLOADED,
        )
        val job = ImageJobRepository(db).createJob(
            personaVisualVersionId = version.id,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "idem-1",
            requestPayload = "{}",
        )
        GeneratedCandidateRepository(db).create(
            imageJobId = job.id,
            storageKey = "candidates/1",
            contentType = "image/png",
            fileSize = 42L,
            widthPx = 512,
            heightPx = 512,
            checksum = "abc",
            candidateIndex = 0,
        )

        val response = client.get("/v1/admin/personas/$personaId/visual") { basicAuth(adminId.toString(), "x") }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        assertTrue(body["hasIdentity"]!!.jsonPrimitive.content.toBoolean())
        val versions = body["versions"]!!.jsonArray
        assertEquals(1, versions.size)
        val v = versions[0].jsonObject
        assertTrue(v["physicalGuide"]!!.jsonPrimitive.content.contains("auburn"))
        assertTrue(v["styleConstraints"]!!.jsonPrimitive.content.contains("noLogos"))
        assertEquals("Silk blouse", v["wardrobe"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("FACE", v["referenceImages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
        val jobs = v["imageJobs"]!!.jsonArray
        assertEquals(1, jobs.size)
        assertEquals("idem-1", jobs[0].jsonObject["idempotencyKey"]!!.jsonPrimitive.content)
        assertEquals(1, jobs[0].jsonObject["candidates"]!!.jsonArray.size)
        // Generation trigger is wired in Application (admin image jobs + chat enqueue).
        assertTrue(body["generationTriggerWired"]!!.jsonPrimitive.content.toBoolean())
    }
}
