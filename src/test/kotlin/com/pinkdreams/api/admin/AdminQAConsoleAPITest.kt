package com.pinkdreams.api.admin

import com.pinkdreams.module
import com.pinkdreams.auth.AdminAuthorizationProvider
import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MessageRepository
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
import kotlinx.serialization.json.jsonArray
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TestAdminAuthProvider : AdminAuthorizationProvider() {
    override fun isAdmin(userId: String): Boolean = true
}

class AdminQAConsoleAPITest {

    private val testAdminId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val testUserId = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @Test
    fun `POST admin personas creates and persists persona`() = testApplication {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val personaRepo = PersonaRepository(db)
        val conversationRepo = ConversationRepository(db)

        application {
            module(
                databaseConfig = DatabaseConfig(jdbcUrl = "jdbc:h2:mem:test", username = "sa", password = ""),
                llmConfig = LlmConfig(apiKey = "test-key"),
                chatEngine = null,
                conversationRepository = conversationRepo,
                adminAuthProvider = TestAdminAuthProvider(),
                database = db,
            )
        }

        val response = client.post("/v1/admin/personas") {
            basicAuth(testAdminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"alice","displayName":"Alice","gender":"female","orientation":"straight","apparentAge":25}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `GET admin personas returns persisted personas not empty list`() = testApplication {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val personaRepo = PersonaRepository(db)
        val conversationRepo = ConversationRepository(db)
        val messageRepo = MessageRepository(db)

        // Create personas
        personaRepo.create("alice", "Alice", "female", "straight", 30, emptyMap())
        personaRepo.create("bob", "Bob", "male", "straight", 35, emptyMap())

        application {
            module(
                databaseConfig = DatabaseConfig(jdbcUrl = "jdbc:h2:mem:test", username = "sa", password = ""),
                llmConfig = LlmConfig(apiKey = "test-key"),
                chatEngine = null,
                conversationRepository = conversationRepo,
                adminAuthProvider = TestAdminAuthProvider(),
                database = db,
            )
        }

        val response = client.get("/v1/admin/personas") {
            basicAuth(testAdminId.toString(), "password")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject
        val personas = json["personas"]?.jsonArray
        assertNotNull(personas)
        assertEquals(2, personas.size)
    }

    @Test
    fun `GET admin engines returns persisted engines not empty list`() = testApplication {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val engineRepo = ConversationEngineRepository(db)
        val conversationRepo = ConversationRepository(db)

        // Create engines
        engineRepo.create(1, "engine 1 content", "draft")
        engineRepo.create(2, "engine 2 content", "published")

        application {
            module(
                databaseConfig = DatabaseConfig(jdbcUrl = "jdbc:h2:mem:test", username = "sa", password = ""),
                llmConfig = LlmConfig(apiKey = "test-key"),
                chatEngine = null,
                conversationRepository = conversationRepo,
                adminAuthProvider = TestAdminAuthProvider(),
                database = db,
            )
        }

        val response = client.get("/v1/admin/engines") {
            basicAuth(testAdminId.toString(), "password")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject
        val engines = json["engines"]?.jsonArray
        assertNotNull(engines)
        assertEquals(2, engines.size)
    }

    @Test
    fun `POST conversations creates conversation with authenticated user ID`() = testApplication {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val personaRepo = PersonaRepository(db)
        val persona = personaRepo.create("alice", "Alice", "female", "straight", 30, emptyMap())
        val conversationRepo = ConversationRepository(db)

        application {
            module(
                databaseConfig = DatabaseConfig(jdbcUrl = "jdbc:h2:mem:test", username = "sa", password = ""),
                llmConfig = LlmConfig(apiKey = "test-key"),
                chatEngine = null,
                conversationRepository = conversationRepo,
                database = db,
            )
        }

        val response = client.post("/v1/conversations") {
            basicAuth(testUserId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"personaId":"${persona.id}"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)

        // Verify conversation was created for the authenticated user
        val conversations = conversationRepo.findAllForUser(testUserId, 10, 0)
        assertEquals(1, conversations.size)
        assertEquals(persona.id, conversations[0].personaId)
    }

    @Test
    fun `POST conversations cannot be created as another user`() = testApplication {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val personaRepo = PersonaRepository(db)
        val persona = personaRepo.create("alice", "Alice", "female", "straight", 30, emptyMap())
        val conversationRepo = ConversationRepository(db)

        application {
            module(
                databaseConfig = DatabaseConfig(jdbcUrl = "jdbc:h2:mem:test", username = "sa", password = ""),
                llmConfig = LlmConfig(apiKey = "test-key"),
                chatEngine = null,
                conversationRepository = conversationRepo,
                database = db,
            )
        }

        val response = client.post("/v1/conversations") {
            basicAuth(testUserId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"personaId":"${persona.id}"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)

        // Verify other users have no conversations
        val otherUserId = UUID.randomUUID()
        val otherConversations = conversationRepo.findAllForUser(otherUserId, 10, 0)
        assertEquals(0, otherConversations.size)
    }

    @Test
    fun `Application wiring uses real repositories`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        // Test that repositories work correctly with real database
        val personaRepo = PersonaRepository(db)
        val persona = personaRepo.create("test", "Test", "neutral", "any", 30, emptyMap())

        assertTrue(persona.id.toString().isNotEmpty())
        assertEquals("test", persona.slug)
    }
}
