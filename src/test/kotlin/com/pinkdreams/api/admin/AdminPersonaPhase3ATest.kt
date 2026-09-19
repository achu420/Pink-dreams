package com.pinkdreams.api.admin

import com.pinkdreams.module
import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
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
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Phase 3A: admin-only persona metadata editing and server-computed
 * persona-core-version creation, at the HTTP layer.
 */
class AdminPersonaPhase3ATest {
    private val adminId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val nonAdminId = UUID.fromString("00000000-0000-0000-0000-0000000000c2")

    private class TestAdminAuthProvider(private val adminId: UUID) : com.pinkdreams.auth.AdminAuthorizationProvider() {
        override fun isAdmin(userId: String): Boolean = userId == adminId.toString()
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.setup(): Pair<org.jetbrains.exposed.sql.Database, PersonaRepository> {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val conversationRepo = ConversationRepository(db)
        application {
            module(
                databaseConfig = DatabaseConfig(jdbcUrl = "jdbc:h2:mem:test", username = "sa", password = ""),
                llmConfig = LlmConfig(apiKey = "test-key"),
                chatEngine = null,
                conversationRepository = conversationRepo,
                adminAuthProvider = TestAdminAuthProvider(adminId),
                database = db,
            )
        }
        return db to PersonaRepository(db)
    }

    // --- A. Persona update ---
    @Test
    fun `scenario A admin can update existing persona fields and values persist`() = testApplication {
        val (_, personaRepository) = setup()
        val persona = personaRepository.create("update-me", "Original", "female", "straight", 25, emptyMap())

        val response = client.patch("/v1/admin/personas/${persona.id}") {
            basicAuth(adminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Updated Name","gender":"nonbinary","orientation":"queer","apparentAge":31}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Updated Name", json["displayName"]!!.jsonPrimitive.content)
        assertEquals("nonbinary", json["gender"]!!.jsonPrimitive.content)
        assertEquals("queer", json["orientation"]!!.jsonPrimitive.content)
        assertEquals(31, json["apparentAge"]!!.jsonPrimitive.content.toInt())

        val reloaded = personaRepository.findById(persona.id)!!
        assertEquals("Updated Name", reloaded.displayName)
        assertEquals("nonbinary", reloaded.gender)
        assertEquals("queer", reloaded.orientation)
        assertEquals(31, reloaded.apparentAge)
    }

    @Test
    fun `scenario A non-admin cannot update persona`() = testApplication {
        val (_, personaRepository) = setup()
        val persona = personaRepository.create("guard-me", "Original", "female", "straight", 25, emptyMap())

        val response = client.patch("/v1/admin/personas/${persona.id}") {
            basicAuth(nonAdminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Hacked"}""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("Original", personaRepository.findById(persona.id)!!.displayName)
    }

    @Test
    fun `scenario A missing persona returns 404 not 500`() = testApplication {
        setup()
        val response = client.patch("/v1/admin/personas/${UUID.randomUUID()}") {
            basicAuth(adminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Doesn't Matter"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `scenario A invalid persona id format returns 400`() = testApplication {
        setup()
        val response = client.patch("/v1/admin/personas/not-a-uuid") {
            basicAuth(adminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"X"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `scenario A malformed request body returns 400`() = testApplication {
        val (_, personaRepository) = setup()
        val persona = personaRepository.create("malformed-body", "Original", "female", "straight", 25, emptyMap())

        val response = client.patch("/v1/admin/personas/${persona.id}") {
            basicAuth(adminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"apparentAge": "not-a-number"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // --- B. Version creation ---
    @Test
    fun `scenario B first and second versions are numbered 1 and 2 by the server`() = testApplication {
        val (_, personaRepository) = setup()
        val persona = personaRepository.create("versioned", "Test", "female", "straight", 25, emptyMap())

        val first = client.post("/v1/admin/personas/${persona.id}/core-versions") {
            basicAuth(adminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"first content","changelogNote":"initial"}""")
        }
        assertEquals(HttpStatusCode.Created, first.status)
        val firstJson = Json.parseToJsonElement(first.bodyAsText()).jsonObject
        assertEquals(1, firstJson["version"]!!.jsonPrimitive.content.toInt())
        assertEquals("draft", firstJson["status"]!!.jsonPrimitive.content)
        assertEquals("first content", firstJson["content"]!!.jsonPrimitive.content)
        assertEquals("initial", firstJson["changelogNote"]!!.jsonPrimitive.content)
        assertEquals(persona.id.toString(), firstJson["personaId"]!!.jsonPrimitive.content)

        val second = client.post("/v1/admin/personas/${persona.id}/core-versions") {
            basicAuth(adminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"second content"}""")
        }
        assertEquals(HttpStatusCode.Created, second.status)
        val secondJson = Json.parseToJsonElement(second.bodyAsText()).jsonObject
        assertEquals(2, secondJson["version"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `scenario B a client supplied version field in the request body is ignored`() = testApplication {
        val (_, personaRepository) = setup()
        val persona = personaRepository.create("ignore-client-version", "Test", "female", "straight", 25, emptyMap())

        // Attempt to smuggle an explicit version number — the API contract has no
        // such field; ignoreUnknownKeys silently drops it and the server still
        // computes its own value.
        val response = client.post("/v1/admin/personas/${persona.id}/core-versions") {
            basicAuth(adminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"content","version":999}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, json["version"]!!.jsonPrimitive.content.toInt(), "Client-supplied version must be ignored; server always computes it")
    }

    @Test
    fun `scenario B non-admin cannot create a core version`() = testApplication {
        val (_, personaRepository) = setup()
        val persona = personaRepository.create("guard-versions", "Test", "female", "straight", 25, emptyMap())

        val response = client.post("/v1/admin/personas/${persona.id}/core-versions") {
            basicAuth(nonAdminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"content"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // --- D. Existing lifecycle regression via HTTP: create -> publish -> activate ---
    @Test
    fun `scenario D server numbered version integrates with existing publish and activate lifecycle`() = testApplication {
        val (_, personaRepository) = setup()
        val persona = personaRepository.create("lifecycle", "Test", "female", "straight", 25, emptyMap())

        val createResponse = client.post("/v1/admin/personas/${persona.id}/core-versions") {
            basicAuth(adminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"lifecycle content"}""")
        }
        val versionId = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val publishResponse = client.post("/v1/admin/personas/${persona.id}/core-versions/$versionId/publish") {
            basicAuth(adminId.toString(), "password")
        }
        assertEquals(HttpStatusCode.OK, publishResponse.status)
        assertEquals("published", Json.parseToJsonElement(publishResponse.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)

        val activateResponse = client.post("/v1/admin/personas/${persona.id}/core-versions/$versionId/activate") {
            basicAuth(adminId.toString(), "password")
        }
        assertEquals(HttpStatusCode.OK, activateResponse.status)

        val listResponse = client.get("/v1/admin/personas/${persona.id}/core-versions") {
            basicAuth(adminId.toString(), "password")
        }
        val versions = Json.parseToJsonElement(listResponse.bodyAsText()).jsonObject["versions"]!!.jsonArray
        assertEquals(1, versions.size)
        assertEquals(1, versions.single().jsonObject["version"]!!.jsonPrimitive.content.toInt())
    }
}
