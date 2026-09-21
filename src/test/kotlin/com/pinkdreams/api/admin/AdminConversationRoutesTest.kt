package com.pinkdreams.api.admin

import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.module
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.LlmExchangeRepository
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.UserRepository
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Module 07 — Conversations Inspector (admin), backend routes. Proves the
 * three endpoints (list, detail/transcript, message LLM-execution) work over
 * real repositories/HTTP, following the same H2 test-harness pattern as
 * AdminObservabilityRoutesTest.
 */
class AdminConversationRoutesTest {
    private val adminId = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
    private val nonAdminId = UUID.fromString("00000000-0000-0000-0000-0000000000b2")

    private class TestAdminAuthProvider(private val adminId: UUID) : com.pinkdreams.auth.AdminAuthorizationProvider() {
        override fun isAdmin(userId: String): Boolean = userId == adminId.toString()
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.setup(): Database {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
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
        return db
    }

    private fun json(body: String) = Json.parseToJsonElement(body).jsonObject

    private fun seedPersona(db: Database): UUID {
        val personaRepo = PersonaRepository(db)
        val persona = personaRepo.create(
            slug = "test-persona-${UUID.randomUUID()}",
            displayName = "Test Persona",
            gender = "female",
            orientation = "straight",
            apparentAge = 25,
            languageProfile = emptyMap(),
        )
        return persona.id
    }

    private fun seedUser(db: Database): UUID {
        val userId = UUID.randomUUID()
        UserRepository(db).create(userId)
        return userId
    }

    @Test
    fun `list conversations returns seeded conversations and supports userId filter`() = testApplication {
        val db = setup()
        val conversationRepo = ConversationRepository(db)
        val personaId = seedPersona(db)
        val userA = seedUser(db)
        val userB = seedUser(db)
        conversationRepo.create(userA, personaId)
        conversationRepo.create(userB, personaId)

        val allResponse = client.get("/v1/admin/conversations") { basicAuth(adminId.toString(), "x") }
        assertEquals(HttpStatusCode.OK, allResponse.status)
        assertEquals(2, json(allResponse.bodyAsText())["conversations"]!!.jsonArray.size)

        val filtered = client.get("/v1/admin/conversations?userId=$userA") { basicAuth(adminId.toString(), "x") }
        assertEquals(HttpStatusCode.OK, filtered.status)
        val filteredConversations = json(filtered.bodyAsText())["conversations"]!!.jsonArray
        assertEquals(1, filteredConversations.size)
        assertEquals(userA.toString(), filteredConversations[0].jsonObject["userId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `conversation detail returns transcript and relationship-scoped memory facts`() = testApplication {
        val db = setup()
        val conversationRepo = ConversationRepository(db)
        val messageRepo = MessageRepository(db)
        val memoryFactRepo = MemoryFactRepository(db)
        val personaId = seedPersona(db)
        val userId = seedUser(db)
        val conversation = conversationRepo.create(userId, personaId)
        messageRepo.createUserMessage(conversation.id, "Hello there", clientMessageId = UUID.randomUUID(), requestId = UUID.randomUUID())
        memoryFactRepo.create(userId, personaId, fact = "Likes hiking", factType = "preference", criticality = "low")

        val response = client.get("/v1/admin/conversations/${conversation.id}") { basicAuth(adminId.toString(), "x") }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        assertEquals(1, body["messages"]!!.jsonArray.size)
        assertEquals("Hello there", body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(1, body["memoryFacts"]!!.jsonArray.size)
        assertEquals("Likes hiking", body["memoryFacts"]!!.jsonArray[0].jsonObject["fact"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown conversation returns 404`() = testApplication {
        setup()
        val response = client.get("/v1/admin/conversations/${UUID.randomUUID()}") { basicAuth(adminId.toString(), "x") }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `message execution endpoint surfaces LLM exchanges sharing the message's turn`() = testApplication {
        val db = setup()
        val conversationRepo = ConversationRepository(db)
        val messageRepo = MessageRepository(db)
        val exchangeRepo = LlmExchangeRepository(db)
        val personaId = seedPersona(db)
        val userId = seedUser(db)
        val conversation = conversationRepo.create(userId, personaId)
        val requestId = UUID.randomUUID()
        val userMessage = messageRepo.createUserMessage(conversation.id, "Hi", clientMessageId = UUID.randomUUID(), requestId = requestId)
        exchangeRepo.record(
            LlmExchangeRepository.RecordInput(
                turnRequestId = requestId,
                conversationId = conversation.id,
                workload = "primary_generation",
                isTestChat = false,
                model = "test-model",
                provider = "TestProvider",
                latencyMs = 1200,
                outcome = LlmExchangeRepository.Outcome.SUCCESS,
                requestBody = """{"model":"x"}""",
                responseBody = """{"choices":[]}""",
            ),
        )

        val response = client.get("/v1/admin/conversations/${conversation.id}/messages/${userMessage.id}/execution") {
            basicAuth(adminId.toString(), "x")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        assertEquals(requestId.toString(), body["requestId"]!!.jsonPrimitive.content)
        val exchanges = body["exchanges"]!!.jsonArray
        assertEquals(1, exchanges.size)
        assertEquals("primary_generation", exchanges[0].jsonObject["workload"]!!.jsonPrimitive.content)
    }

    @Test
    fun `non-admin principal is forbidden`() = testApplication {
        val db = setup()
        val personaId = seedPersona(db)
        val userId = seedUser(db)
        ConversationRepository(db).create(userId, personaId)

        val response = client.get("/v1/admin/conversations") { basicAuth(nonAdminId.toString(), "x") }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}
