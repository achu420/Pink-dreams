package com.pinkdreams.api.conversation

import com.pinkdreams.module
import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationRepository
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the "does user+persona already have a conversation" lookup the future
 * Admin Console Chat tab needs, reusing GET /v1/conversations with a new
 * optional personaId filter rather than adding a duplicate endpoint. The
 * console authenticates directly as the selected test user (the same
 * convention already used by AdminQAConsoleAPITest and enabled by
 * DevAuthProvider), so ownership enforcement is identical to the normal
 * user-facing path — never a client-supplied "act as" user ID.
 */
class ConversationPersonaLookupTest {
    @Test
    fun `scenario H matching conversation is returned when filtering by persona`() = testApplication {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val conversationRepo = ConversationRepository(db)
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val conversation = conversationRepo.create(userId, personaId)

        application {
            module(
                databaseConfig = DatabaseConfig(jdbcUrl = "jdbc:h2:mem:test", username = "sa", password = ""),
                llmConfig = LlmConfig(apiKey = "test-key"),
                chatEngine = null,
                conversationRepository = conversationRepo,
                database = db,
            )
        }

        val response = client.get("/v1/conversations?personaId=$personaId") {
            basicAuth(userId.toString(), "password")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val conversations = Json.parseToJsonElement(response.bodyAsText()).jsonObject["conversations"]!!.jsonArray
        assertEquals(1, conversations.size)
        assertEquals(conversation.id.toString(), conversations.single().jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `scenario H no matching conversation for a different persona returns empty`() = testApplication {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val conversationRepo = ConversationRepository(db)
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val otherPersonaId = UUID.randomUUID()
        conversationRepo.create(userId, personaId)

        application {
            module(
                databaseConfig = DatabaseConfig(jdbcUrl = "jdbc:h2:mem:test", username = "sa", password = ""),
                llmConfig = LlmConfig(apiKey = "test-key"),
                chatEngine = null,
                conversationRepository = conversationRepo,
                database = db,
            )
        }

        val response = client.get("/v1/conversations?personaId=$otherPersonaId") {
            basicAuth(userId.toString(), "password")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val conversations = Json.parseToJsonElement(response.bodyAsText()).jsonObject["conversations"]!!.jsonArray
        assertEquals(0, conversations.size)
    }

    @Test
    fun `scenario H a different user's conversation for the same persona is never exposed`() = testApplication {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val conversationRepo = ConversationRepository(db)
        val ownerUserId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        conversationRepo.create(ownerUserId, personaId)

        application {
            module(
                databaseConfig = DatabaseConfig(jdbcUrl = "jdbc:h2:mem:test", username = "sa", password = ""),
                llmConfig = LlmConfig(apiKey = "test-key"),
                chatEngine = null,
                conversationRepository = conversationRepo,
                database = db,
            )
        }

        val response = client.get("/v1/conversations?personaId=$personaId") {
            basicAuth(otherUserId.toString(), "password")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val conversations = Json.parseToJsonElement(response.bodyAsText()).jsonObject["conversations"]!!.jsonArray
        assertEquals(0, conversations.size, "A conversation owned by a different user must never be exposed, even for a matching persona")
    }

    @Test
    fun `scenario H multiple conversations for the same user and persona are all returned`() = testApplication {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val conversationRepo = ConversationRepository(db)
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val first = conversationRepo.create(userId, personaId)
        val second = conversationRepo.create(userId, personaId)

        application {
            module(
                databaseConfig = DatabaseConfig(jdbcUrl = "jdbc:h2:mem:test", username = "sa", password = ""),
                llmConfig = LlmConfig(apiKey = "test-key"),
                chatEngine = null,
                conversationRepository = conversationRepo,
                database = db,
            )
        }

        val response = client.get("/v1/conversations?personaId=$personaId") {
            basicAuth(userId.toString(), "password")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val conversations = Json.parseToJsonElement(response.bodyAsText()).jsonObject["conversations"]!!.jsonArray
        val ids = conversations.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf(first.id.toString(), second.id.toString()), ids)
    }

    @Test
    fun `invalid personaId query parameter is rejected`() = testApplication {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val conversationRepo = ConversationRepository(db)
        val userId = UUID.randomUUID()

        application {
            module(
                databaseConfig = DatabaseConfig(jdbcUrl = "jdbc:h2:mem:test", username = "sa", password = ""),
                llmConfig = LlmConfig(apiKey = "test-key"),
                chatEngine = null,
                conversationRepository = conversationRepo,
                database = db,
            )
        }

        val response = client.get("/v1/conversations?personaId=not-a-uuid") {
            basicAuth(userId.toString(), "password")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
