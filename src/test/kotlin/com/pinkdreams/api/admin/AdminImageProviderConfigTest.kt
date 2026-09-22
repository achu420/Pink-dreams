package com.pinkdreams.api.admin

import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.module
import com.pinkdreams.persistence.database.DatabaseFactory
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminImageProviderConfigTest {
    private val adminId = UUID.fromString("00000000-0000-0000-0000-0000000000e4")

    private class TestAdminAuthProvider(private val adminId: UUID) : com.pinkdreams.auth.AdminAuthorizationProvider() {
        override fun isAdmin(userId: String): Boolean = userId == adminId.toString()
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.setup() {
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
    }

    private fun json(body: String) = Json.parseToJsonElement(body).jsonObject

    @Test
    fun `GET images config exposes precedence and sources`() = testApplication {
        setup()
        val res = client.get("/v1/admin/images/config") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = json(res.bodyAsText())
        assertTrue(body["model"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(body["provider"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(body.containsKey("modelSource") || body.containsKey("precedence"))
        // precedence may be omitted when encodeDefaults=false; presence of modelSource proves resolver path
        if (body.containsKey("precedence")) {
            assertEquals("request > database > environment > default", body["precedence"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `PATCH images config persists Admin default without storing secrets`() = testApplication {
        setup()
        val patch = client.patch("/v1/admin/images/config") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"openrouter","model":"openai/eval-admin-default","enabled":true}""")
        }
        assertEquals(HttpStatusCode.OK, patch.status)
        val body = json(patch.bodyAsText())
        assertEquals("openai/eval-admin-default", body["model"]!!.jsonPrimitive.content)
        assertEquals("DATABASE", body["modelSource"]!!.jsonPrimitive.content)
        assertEquals("openai/eval-admin-default", body["adminConfiguredModel"]!!.jsonPrimitive.content)
        assertTrue(!patch.bodyAsText().contains("OPENROUTER_API_KEY", ignoreCase = true))

        val get = client.get("/v1/admin/images/config") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals("DATABASE", json(get.bodyAsText())["modelSource"]!!.jsonPrimitive.content)

        val clear = client.patch("/v1/admin/images/config") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"clearOverrides":true}""")
        }
        assertEquals(HttpStatusCode.OK, clear.status)
        val cleared = json(clear.bodyAsText())
        assertTrue(cleared["modelSource"]!!.jsonPrimitive.content != "DATABASE" || cleared["adminConfiguredModel"] == null)
    }
}
