package com.pinkdreams.api.admin

import com.pinkdreams.module
import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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
 * Regression for a reported bug: after activating v1 then creating/activating
 * v2, the admin console showed both versions as active. Root cause was that
 * CoreVersionResponse never exposed an isActive flag at all, so the UI had no
 * way to distinguish them (and showed an Activate button on every published
 * row, active or not). This locks in that exactly one version is ever
 * reported active via the actual HTTP response the UI consumes.
 */
class AdminPersonaCoreVersionActiveFlagTest {
    private val adminId = UUID.fromString("00000000-0000-0000-0000-0000000000e1")

    private class TestAdminAuthProvider(private val adminId: UUID) : com.pinkdreams.auth.AdminAuthorizationProvider() {
        override fun isAdmin(userId: String): Boolean = userId == adminId.toString()
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.setup(): PersonaRepository {
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
        return PersonaRepository(db)
    }

    @Test
    fun `only the currently active version is reported isActive true after activating a second version`() = testApplication {
        val personaRepository = setup()
        val persona = personaRepository.create("active-flag-test", "Test", "female", "straight", 25, emptyMap())

        suspend fun createPublishActivate(content: String): String {
            val created = client.post("/v1/admin/personas/${persona.id}/core-versions") {
                basicAuth(adminId.toString(), "password")
                setBody("""{"content":"$content"}""")
                contentType(ContentType.Application.Json)
            }
            val versionId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
            client.post("/v1/admin/personas/${persona.id}/core-versions/$versionId/publish") {
                basicAuth(adminId.toString(), "password")
            }
            val activateResponse = client.post("/v1/admin/personas/${persona.id}/core-versions/$versionId/activate") {
                basicAuth(adminId.toString(), "password")
            }
            assertEquals(true, Json.parseToJsonElement(activateResponse.bodyAsText()).jsonObject["isActive"]!!.jsonPrimitive.content.toBoolean())
            return versionId
        }

        createPublishActivate("v1 content")
        createPublishActivate("v2 content")

        val listResponse = client.get("/v1/admin/personas/${persona.id}/core-versions") {
            basicAuth(adminId.toString(), "password")
        }
        val versions = Json.parseToJsonElement(listResponse.bodyAsText()).jsonObject["versions"]!!.jsonArray
        val activeCount = versions.count { it.jsonObject["isActive"]!!.jsonPrimitive.content.toBoolean() }
        assertEquals(1, activeCount, "Exactly one version must be reported active, never zero or two")
        assertEquals(2, versions.single { it.jsonObject["isActive"]!!.jsonPrimitive.content.toBoolean() }.jsonObject["version"]!!.jsonPrimitive.content.toInt())
    }
}
