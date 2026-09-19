package com.pinkdreams.api.admin

import com.pinkdreams.module
import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.SkillRepository
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

class AdminSkillRoutesTest {
    private val adminId = UUID.fromString("00000000-0000-0000-0000-0000000000f1")
    private val nonAdminId = UUID.fromString("00000000-0000-0000-0000-0000000000f2")

    private class TestAdminAuthProvider(private val adminId: UUID) : com.pinkdreams.auth.AdminAuthorizationProvider() {
        override fun isAdmin(userId: String): Boolean = userId == adminId.toString()
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.setup(): SkillRepository {
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
        return SkillRepository(db)
    }

    @Test
    fun `first and second versions for a key are numbered 1 and 2 by the server`() = testApplication {
        setup()

        val first = client.post("/v1/admin/skills") {
            basicAuth(adminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"key":"flirting","content":"first content"}""")
        }
        assertEquals(HttpStatusCode.Created, first.status)
        val firstJson = Json.parseToJsonElement(first.bodyAsText()).jsonObject
        assertEquals(1, firstJson["version"]!!.jsonPrimitive.content.toInt())
        assertEquals("flirting", firstJson["key"]!!.jsonPrimitive.content)
        assertEquals("draft", firstJson["status"]!!.jsonPrimitive.content)

        val second = client.post("/v1/admin/skills") {
            basicAuth(adminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"key":"flirting","content":"second content"}""")
        }
        assertEquals(2, Json.parseToJsonElement(second.bodyAsText()).jsonObject["version"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `a different key starts its own numbering at 1`() = testApplication {
        setup()
        client.post("/v1/admin/skills") {
            basicAuth(adminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"key":"flirting","content":"c1"}""")
        }
        client.post("/v1/admin/skills") {
            basicAuth(adminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"key":"flirting","content":"c2"}""")
        }
        val friendship = client.post("/v1/admin/skills") {
            basicAuth(adminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"key":"friendship","content":"c1"}""")
        }
        assertEquals(1, Json.parseToJsonElement(friendship.bodyAsText()).jsonObject["version"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `non-admin cannot create a skill version`() = testApplication {
        setup()
        val response = client.post("/v1/admin/skills") {
            basicAuth(nonAdminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"key":"flirting","content":"content"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `missing skill returns 404 not 500 on publish activate and archive`() = testApplication {
        setup()
        val missingId = UUID.randomUUID()
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/admin/skills/$missingId/publish") { basicAuth(adminId.toString(), "password") }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/admin/skills/$missingId/activate") { basicAuth(adminId.toString(), "password") }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/admin/skills/$missingId/archive") { basicAuth(adminId.toString(), "password") }.status)
    }

    @Test
    fun `missing key in request body returns 400`() = testApplication {
        setup()
        val response = client.post("/v1/admin/skills") {
            basicAuth(adminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"content"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `full lifecycle via http create publish activate and list shows active flag`() = testApplication {
        setup()

        val createResponse = client.post("/v1/admin/skills") {
            basicAuth(adminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"key":"flirting","content":"lifecycle content"}""")
        }
        val skillId = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val publishResponse = client.post("/v1/admin/skills/$skillId/publish") { basicAuth(adminId.toString(), "password") }
        assertEquals("published", Json.parseToJsonElement(publishResponse.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)

        val activateResponse = client.post("/v1/admin/skills/$skillId/activate") { basicAuth(adminId.toString(), "password") }
        assertEquals(true, Json.parseToJsonElement(activateResponse.bodyAsText()).jsonObject["isActive"]!!.toString().toBoolean())

        val listResponse = client.get("/v1/admin/skills?key=flirting") { basicAuth(adminId.toString(), "password") }
        val skills = Json.parseToJsonElement(listResponse.bodyAsText()).jsonObject["skills"]!!.jsonArray
        assertEquals(1, skills.size)
        assertEquals(true, skills.single().jsonObject["isActive"]!!.toString().toBoolean())
    }
}
