package com.pinkdreams.api.admin

import com.pinkdreams.baseline.BaselineConfiguration
import com.pinkdreams.baseline.BaselineSeeder
import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.module
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.IntentEngineRepository
import com.pinkdreams.persistence.repositories.MemoryEngineRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import com.pinkdreams.persistence.repositories.UserRepository
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Phase ADMIN-3 sections 16, 34, 39: the admin-only Test Chat HTTP surface. */
class AdminTestChatRoutesTest {
    private val adminId = UUID.fromString("00000000-0000-0000-0000-0000000000f1")
    private val nonAdminId = UUID.fromString("00000000-0000-0000-0000-0000000000f2")

    private class TestAdminAuthProvider(private val adminId: UUID) : com.pinkdreams.auth.AdminAuthorizationProvider() {
        override fun isAdmin(userId: String): Boolean = userId == adminId.toString()
    }

    private data class Fixture(val db: Database, val personaId: UUID, val testUserId: UUID)

    private fun io.ktor.server.testing.ApplicationTestBuilder.setup(): Fixture {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val engines = ConversationEngineRepository(db)
        val personas = PersonaRepository(db)
        val cores = PersonaCoreVersionRepository(db)
        val skills = SkillRepository(db)
        val memoryEngines = MemoryEngineRepository(db)
        val intentEngines = IntentEngineRepository(db)
        val users = UserRepository(db)
        BaselineSeeder(engines, personas, cores, skills, memoryEngines, intentEngines).seedIfMissing()
        skills.findAll().forEach { skills.activate(skills.publish(it.id).id) }
        val personaId = personas.findAll().first { it.slug == BaselineConfiguration.SIMRAN_SLUG }.id
        val testUserId = UUID.randomUUID()
        users.create(testUserId)

        application {
            module(
                databaseConfig = DatabaseConfig(jdbcUrl = "jdbc:h2:mem:test", username = "sa", password = ""),
                llmConfig = LlmConfig(apiKey = "test-key"),
                chatEngine = null,
                conversationRepository = ConversationRepository(db),
                adminAuthProvider = TestAdminAuthProvider(adminId),
                database = db,
            )
        }
        return Fixture(db, personaId, testUserId)
    }

    private fun json(body: String) = Json.parseToJsonElement(body).jsonObject

    @Test
    fun `creating a test chat resolves testing defaults and returns the snapshot`() = testApplication {
        val f = setup()

        val response = client.post("/v1/admin/test-chat/conversations") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"personaId": "${f.personaId}", "testUserId": "${f.testUserId}"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = json(response.bodyAsText())
        assertEquals("TEST", body["mode"]!!.jsonPrimitive.content)
        val config = body["configuration"]!!.jsonObject
        assertTrue(config.containsKey("conversationEngineVersion"))
        assertTrue(config.containsKey("personaCoreVersion"))
        assertTrue(config.containsKey("intentEngineVersion"))
        assertTrue(config.containsKey("memoryEngineVersion"))
        assertTrue(config["skillVersions"]!!.jsonObject.isNotEmpty())
    }

    @Test
    fun `creating a test chat with an unknown persona is rejected`() = testApplication {
        setup()
        val response = client.post("/v1/admin/test-chat/conversations") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"personaId": "${UUID.randomUUID()}", "testUserId": "${UUID.randomUUID()}"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `sending a message returns the configuration used and stores diagnostics`() = testApplication {
        val f = setup()
        val created = json(
            client.post("/v1/admin/test-chat/conversations") {
                basicAuth(adminId.toString(), "x")
                contentType(ContentType.Application.Json)
                setBody("""{"personaId": "${f.personaId}", "testUserId": "${f.testUserId}"}""")
            }.bodyAsText(),
        )
        val conversationId = created["conversationId"]!!.jsonPrimitive.content

        val messageResponse = client.post("/v1/admin/test-chat/conversations/$conversationId/messages") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"content": "Hello there"}""")
        }

        assertEquals(HttpStatusCode.OK, messageResponse.status)
        val body = json(messageResponse.bodyAsText())
        assertEquals("TEST", body["mode"]!!.jsonPrimitive.content)
        assertTrue(body["configuration"]!!.jsonObject.containsKey("conversationEngineVersion"))

        val detail = json(
            client.get("/v1/admin/test-chat/conversations/$conversationId") { basicAuth(adminId.toString(), "x") }.bodyAsText(),
        )
        val messages = detail["messages"]!!.toString()
        assertTrue(messages.contains("contextBlocks"))
    }

    @Test
    fun `the configuration endpoint returns the immutable snapshot`() = testApplication {
        val f = setup()
        val created = json(
            client.post("/v1/admin/test-chat/conversations") {
                basicAuth(adminId.toString(), "x")
                contentType(ContentType.Application.Json)
                setBody("""{"personaId": "${f.personaId}", "testUserId": "${f.testUserId}"}""")
            }.bodyAsText(),
        )
        val conversationId = created["conversationId"]!!.jsonPrimitive.content

        val configResponse = client.get("/v1/admin/test-chat/conversations/$conversationId/configuration") { basicAuth(adminId.toString(), "x") }

        assertEquals(HttpStatusCode.OK, configResponse.status)
        assertEquals(created["configuration"].toString(), json(configResponse.bodyAsText()).toString())
    }

    @Test
    fun `a production conversation is invisible to the production chat api when queried as test chat`() = testApplication {
        val f = setup()
        val prodConv = ConversationRepository(f.db).create(f.testUserId, f.personaId)

        val response = client.get("/v1/admin/test-chat/conversations/${prodConv.id}") { basicAuth(adminId.toString(), "x") }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `test chat endpoints reject non admins`() = testApplication {
        val f = setup()
        assertEquals(
            HttpStatusCode.Forbidden,
            client.post("/v1/admin/test-chat/conversations") {
                basicAuth(nonAdminId.toString(), "x")
                contentType(ContentType.Application.Json)
                setBody("""{"personaId": "${f.personaId}", "testUserId": "${f.testUserId}"}""")
            }.status,
        )
    }

    @Test
    fun `test chat endpoints require authentication`() = testApplication {
        setup()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/test-chat/conversations/${UUID.randomUUID()}").status)
    }

    @Test
    fun `an admin can pin an explicit published version instead of the default`() = testApplication {
        val f = setup()
        val engines = com.pinkdreams.persistence.repositories.ConversationEngineRepository(f.db)
        val activeVersion = engines.getActiveEngine()!!.version

        val response = client.post("/v1/admin/test-chat/conversations") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"personaId": "${f.personaId}", "testUserId": "${f.testUserId}", "conversationEngineVersion": $activeVersion}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val config = json(response.bodyAsText())["configuration"]!!.jsonObject
        assertEquals(activeVersion, config["conversationEngineVersion"]!!.jsonPrimitive.content.toInt())
    }
}
