package com.pinkdreams.api.admin

import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.module
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import io.ktor.client.request.basicAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for a gap found during real-API QA validation: no admin
 * route ever transitioned Personas.status to "active", so a persona created
 * through the admin API (or the baseline seeder) could never satisfy
 * GET /v1/personas' status == "active" filter. These tests exercise the new
 * POST .../activate and POST .../retire routes through the real HTTP layer,
 * same auth convention as AdminPersonaCoreVersionActiveFlagTest.
 */
class AdminPersonaActivationRoutesTest {
    private val adminId = UUID.fromString("00000000-0000-0000-0000-0000000000e2")
    private val nonAdminId = UUID.fromString("00000000-0000-0000-0000-0000000000e3")

    private class TestAdminAuthProvider(private val adminId: UUID) : com.pinkdreams.auth.AdminAuthorizationProvider() {
        override fun isAdmin(userId: String): Boolean = userId == adminId.toString()
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.setup(): Pair<PersonaRepository, PersonaCoreVersionRepository> {
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
        return PersonaRepository(db) to PersonaCoreVersionRepository(db)
    }

    @Test
    fun `activating a draft persona with a published active core version succeeds`() = testApplication {
        val (personaRepo, coreRepo) = setup()
        val persona = personaRepo.create("activation-ok", "Test", "female", "straight", 25, emptyMap())
        val published = coreRepo.publishCoreVersion(coreRepo.create(persona.id, 1, "core content", "draft").id)
        personaRepo.activateCoreVersion(persona.id, published.id)

        val response = client.post("/v1/admin/personas/${persona.id}/activate") {
            basicAuth(adminId.toString(), "password")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("active", Json.parseToJsonElement(response.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `activating a persona with no active core version is rejected with 409`() = testApplication {
        val (personaRepo, _) = setup()
        val persona = personaRepo.create("activation-no-core", "Test", "female", "straight", 25, emptyMap())

        val response = client.post("/v1/admin/personas/${persona.id}/activate") {
            basicAuth(adminId.toString(), "password")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `activating an already active persona is rejected with 409`() = testApplication {
        val (personaRepo, coreRepo) = setup()
        val persona = personaRepo.create("activation-twice", "Test", "female", "straight", 25, emptyMap())
        val published = coreRepo.publishCoreVersion(coreRepo.create(persona.id, 1, "core content", "draft").id)
        personaRepo.activateCoreVersion(persona.id, published.id)
        personaRepo.activatePersona(persona.id)

        val response = client.post("/v1/admin/personas/${persona.id}/activate") {
            basicAuth(adminId.toString(), "password")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `retiring a draft persona succeeds without requiring activation first`() = testApplication {
        val (personaRepo, _) = setup()
        val persona = personaRepo.create("retire-from-draft", "Test", "female", "straight", 25, emptyMap())

        val response = client.post("/v1/admin/personas/${persona.id}/retire") {
            basicAuth(adminId.toString(), "password")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("retired", Json.parseToJsonElement(response.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `retiring an active persona succeeds`() = testApplication {
        val (personaRepo, coreRepo) = setup()
        val persona = personaRepo.create("retire-from-active", "Test", "female", "straight", 25, emptyMap())
        val published = coreRepo.publishCoreVersion(coreRepo.create(persona.id, 1, "core content", "draft").id)
        personaRepo.activateCoreVersion(persona.id, published.id)
        personaRepo.activatePersona(persona.id)

        val response = client.post("/v1/admin/personas/${persona.id}/retire") {
            basicAuth(adminId.toString(), "password")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("retired", Json.parseToJsonElement(response.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a retired persona cannot be reactivated through the route`() = testApplication {
        val (personaRepo, coreRepo) = setup()
        val persona = personaRepo.create("no-reactivate", "Test", "female", "straight", 25, emptyMap())
        val published = coreRepo.publishCoreVersion(coreRepo.create(persona.id, 1, "core content", "draft").id)
        personaRepo.activateCoreVersion(persona.id, published.id)
        personaRepo.activatePersona(persona.id)
        personaRepo.retirePersona(persona.id)

        val response = client.post("/v1/admin/personas/${persona.id}/activate") {
            basicAuth(adminId.toString(), "password")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `activate without authentication returns 401`() = testApplication {
        val (personaRepo, _) = setup()
        val persona = personaRepo.create("no-auth", "Test", "female", "straight", 25, emptyMap())

        val response = client.post("/v1/admin/personas/${persona.id}/activate")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `activate as a non admin returns 403`() = testApplication {
        val (personaRepo, _) = setup()
        val persona = personaRepo.create("non-admin", "Test", "female", "straight", 25, emptyMap())

        val response = client.post("/v1/admin/personas/${persona.id}/activate") {
            basicAuth(nonAdminId.toString(), "password")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `activating a nonexistent persona returns 404`() = testApplication {
        setup()

        val response = client.post("/v1/admin/personas/${UUID.randomUUID()}/activate") {
            basicAuth(adminId.toString(), "password")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
