package com.pinkdreams.api.admin

import com.pinkdreams.module
import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
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

/**
 * Phase 3B: admin-only, server-computed conversation-engine-version creation,
 * at the HTTP layer. Mirrors AdminPersonaPhase3ATest's structure for the
 * engine's global (non-parent-scoped) versioning.
 */
class AdminEnginePhase3BTest {
    private val adminId = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val nonAdminId = UUID.fromString("00000000-0000-0000-0000-0000000000d2")

    private class TestAdminAuthProvider(private val adminId: UUID) : com.pinkdreams.auth.AdminAuthorizationProvider() {
        override fun isAdmin(userId: String): Boolean = userId == adminId.toString()
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.setup(): ConversationEngineRepository {
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
        return ConversationEngineRepository(db)
    }

    @Test
    fun `first and second engine versions are numbered 1 and 2 by the server`() = testApplication {
        setup()

        val first = client.post("/v1/admin/engines") {
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

        val second = client.post("/v1/admin/engines") {
            basicAuth(adminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"second content"}""")
        }
        assertEquals(HttpStatusCode.Created, second.status)
        val secondJson = Json.parseToJsonElement(second.bodyAsText()).jsonObject
        assertEquals(2, secondJson["version"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `a client supplied version field in the request body is ignored`() = testApplication {
        setup()

        // Attempt to smuggle an explicit version number — the API contract has no
        // such field; ignoreUnknownKeys silently drops it and the server still
        // computes its own value.
        val response = client.post("/v1/admin/engines") {
            basicAuth(adminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"content","version":999}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, json["version"]!!.jsonPrimitive.content.toInt(), "Client-supplied version must be ignored; server always computes it")
    }

    @Test
    fun `non-admin cannot create an engine version`() = testApplication {
        setup()

        val response = client.post("/v1/admin/engines") {
            basicAuth(nonAdminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"content"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `missing engine returns 404 not 500 on publish activate and archive`() = testApplication {
        setup()
        val missingId = UUID.randomUUID()

        val publish = client.post("/v1/admin/engines/$missingId/publish") { basicAuth(adminId.toString(), "password") }
        assertEquals(HttpStatusCode.NotFound, publish.status)

        val activate = client.post("/v1/admin/engines/$missingId/activate") { basicAuth(adminId.toString(), "password") }
        assertEquals(HttpStatusCode.NotFound, activate.status)

        val archive = client.post("/v1/admin/engines/$missingId/archive") { basicAuth(adminId.toString(), "password") }
        assertEquals(HttpStatusCode.NotFound, archive.status)
    }

    @Test
    fun `invalid request body returns 400`() = testApplication {
        setup()

        val response = client.post("/v1/admin/engines") {
            basicAuth(adminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""not-json""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `server numbered engine version integrates with existing publish and activate lifecycle`() = testApplication {
        setup()

        val createResponse = client.post("/v1/admin/engines") {
            basicAuth(adminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"lifecycle content"}""")
        }
        val engineId = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val publishResponse = client.post("/v1/admin/engines/$engineId/publish") {
            basicAuth(adminId.toString(), "password")
        }
        assertEquals(HttpStatusCode.OK, publishResponse.status)
        assertEquals("published", Json.parseToJsonElement(publishResponse.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)

        val activateResponse = client.post("/v1/admin/engines/$engineId/activate") {
            basicAuth(adminId.toString(), "password")
        }
        assertEquals(HttpStatusCode.OK, activateResponse.status)
        assertEquals(true, Json.parseToJsonElement(activateResponse.bodyAsText()).jsonObject["isActive"]!!.toString().toBoolean())

        val listResponse = client.get("/v1/admin/engines") {
            basicAuth(adminId.toString(), "password")
        }
        val engines = Json.parseToJsonElement(listResponse.bodyAsText()).jsonObject["engines"]!!.jsonArray
        assertEquals(1, engines.size)
        assertEquals(1, engines.single().jsonObject["version"]!!.jsonPrimitive.content.toInt())
    }
}
