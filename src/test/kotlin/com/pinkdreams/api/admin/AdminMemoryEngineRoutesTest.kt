package com.pinkdreams.api.admin

import com.pinkdreams.module
import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MemoryEngineRepository
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

class AdminMemoryEngineRoutesTest {
    private val adminId = UUID.fromString("00000000-0000-0000-0000-000000001001")
    private val nonAdminId = UUID.fromString("00000000-0000-0000-0000-000000001002")

    private class TestAdminAuthProvider(private val adminId: UUID) : com.pinkdreams.auth.AdminAuthorizationProvider() {
        override fun isAdmin(userId: String): Boolean = userId == adminId.toString()
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.setup(): MemoryEngineRepository {
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
        return MemoryEngineRepository(db)
    }

    @Test
    fun `first and second versions are numbered 1 and 2 by the server`() = testApplication {
        setup()
        val first = client.post("/v1/admin/memory-engines") {
            basicAuth(adminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"first content"}""")
        }
        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(1, Json.parseToJsonElement(first.bodyAsText()).jsonObject["version"]!!.jsonPrimitive.content.toInt())

        val second = client.post("/v1/admin/memory-engines") {
            basicAuth(adminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"second content"}""")
        }
        assertEquals(2, Json.parseToJsonElement(second.bodyAsText()).jsonObject["version"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `client supplied version field in the request body is ignored`() = testApplication {
        setup()
        val response = client.post("/v1/admin/memory-engines") {
            basicAuth(adminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"content","version":999}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(1, Json.parseToJsonElement(response.bodyAsText()).jsonObject["version"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `non-admin cannot create publish activate or archive`() = testApplication {
        setup()
        val create = client.post("/v1/admin/memory-engines") {
            basicAuth(nonAdminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"content"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, create.status)
    }

    @Test
    fun `missing memory engine returns 404 not 500 on publish activate and archive`() = testApplication {
        setup()
        val missingId = UUID.randomUUID()
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/admin/memory-engines/$missingId/publish") { basicAuth(adminId.toString(), "password") }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/admin/memory-engines/$missingId/activate") { basicAuth(adminId.toString(), "password") }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/admin/memory-engines/$missingId/archive") { basicAuth(adminId.toString(), "password") }.status)
    }

    @Test
    fun `full lifecycle via http create publish activate and list shows active flag`() = testApplication {
        setup()
        val createResponse = client.post("/v1/admin/memory-engines") {
            basicAuth(adminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"lifecycle content"}""")
        }
        val id = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val publishResponse = client.post("/v1/admin/memory-engines/$id/publish") { basicAuth(adminId.toString(), "password") }
        assertEquals("published", Json.parseToJsonElement(publishResponse.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)

        val activateResponse = client.post("/v1/admin/memory-engines/$id/activate") { basicAuth(adminId.toString(), "password") }
        assertEquals(true, Json.parseToJsonElement(activateResponse.bodyAsText()).jsonObject["isActive"]!!.toString().toBoolean())

        val listResponse = client.get("/v1/admin/memory-engines") { basicAuth(adminId.toString(), "password") }
        val engines = Json.parseToJsonElement(listResponse.bodyAsText()).jsonObject["memoryEngines"]!!.jsonArray
        assertEquals(1, engines.size)
        assertEquals(true, engines.single().jsonObject["isActive"]!!.toString().toBoolean())
    }
}
