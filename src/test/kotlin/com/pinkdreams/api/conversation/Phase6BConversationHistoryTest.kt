package com.pinkdreams.api.conversation

import com.pinkdreams.module
import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Phase6BConversationHistoryTest {

    @Test
    fun `GET conversations without authentication returns 401`() = testApplication {
        val testLlmConfig = LlmConfig(apiKey = "test-key")
        val testDatabaseConfig = DatabaseConfig(
            jdbcUrl = "jdbc:h2:mem:test",
            username = "sa",
            password = "",
        )

        val db = com.pinkdreams.persistence.database.DatabaseFactory.connectInMemory()
        val mockConversationRepo = object : ConversationRepository(db) {
            override fun findByIdForUser(id: UUID, userId: UUID): ConversationRepository.Conversation? = null
        }

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, conversationRepository = mockConversationRepo)
        }

        val response = client.get("/v1/conversations") {
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET conversation detail returns 400 for invalid conversation ID format`() = testApplication {
        val userId = UUID.randomUUID()
        val testLlmConfig = LlmConfig(apiKey = "test-key")
        val testDatabaseConfig = DatabaseConfig(
            jdbcUrl = "jdbc:h2:mem:test",
            username = "sa",
            password = "",
        )

        val db = com.pinkdreams.persistence.database.DatabaseFactory.connectInMemory()
        val mockConversationRepo = object : ConversationRepository(db) {
            override fun findByIdForUser(id: UUID, userId: UUID): ConversationRepository.Conversation? = null
        }

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, conversationRepository = mockConversationRepo)
        }

        val response = client.get("/v1/conversations/invalid-id") {
            contentType(ContentType.Application.Json)
            basicAuth("${userId}", "password")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET conversation detail returns 404 if not found`() = testApplication {
        val userId = UUID.randomUUID()
        val conversationId = UUID.randomUUID()
        val testLlmConfig = LlmConfig(apiKey = "test-key")
        val testDatabaseConfig = DatabaseConfig(
            jdbcUrl = "jdbc:h2:mem:test",
            username = "sa",
            password = "",
        )

        val db = com.pinkdreams.persistence.database.DatabaseFactory.connectInMemory()
        val mockConversationRepo = object : ConversationRepository(db) {
            override fun findByIdForUser(id: UUID, userId: UUID): ConversationRepository.Conversation? = null
        }

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, conversationRepository = mockConversationRepo)
        }

        val response = client.get("/v1/conversations/$conversationId") {
            contentType(ContentType.Application.Json)
            basicAuth("${userId}", "password")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET conversation returns 200 if owned`() = testApplication {
        val userId = UUID.randomUUID()
        val conversationId = UUID.randomUUID()
        val testLlmConfig = LlmConfig(apiKey = "test-key")
        val testDatabaseConfig = DatabaseConfig(
            jdbcUrl = "jdbc:h2:mem:test",
            username = "sa",
            password = "",
        )

        val conversation = ConversationRepository.Conversation(
            id = conversationId,
            userId = userId,
            personaId = UUID.randomUUID(),
            state = "active",
            lastMessageAt = null,
            createdAt = LocalDateTime.now(),
        )

        val db = com.pinkdreams.persistence.database.DatabaseFactory.connectInMemory()
        val mockConversationRepo = object : ConversationRepository(db) {
            override fun findByIdForUser(id: UUID, foundUserId: UUID): ConversationRepository.Conversation? {
                return if (id == conversationId && foundUserId == userId) {
                    conversation
                } else null
            }
        }

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, conversationRepository = mockConversationRepo)
        }

        val response = client.get("/v1/conversations/$conversationId") {
            contentType(ContentType.Application.Json)
            basicAuth("${userId}", "password")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET messages returns 404 if conversation not found`() = testApplication {
        val userId = UUID.randomUUID()
        val conversationId = UUID.randomUUID()
        val testLlmConfig = LlmConfig(apiKey = "test-key")
        val testDatabaseConfig = DatabaseConfig(
            jdbcUrl = "jdbc:h2:mem:test",
            username = "sa",
            password = "",
        )

        val db = com.pinkdreams.persistence.database.DatabaseFactory.connectInMemory()
        val mockConversationRepo = object : ConversationRepository(db) {
            override fun findByIdForUser(id: UUID, userId: UUID): ConversationRepository.Conversation? = null
        }

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, conversationRepository = mockConversationRepo)
        }

        val response = client.get("/v1/conversations/$conversationId/messages") {
            contentType(ContentType.Application.Json)
            basicAuth("${userId}", "password")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET messages returns 400 for invalid pagination parameters`() = testApplication {
        val userId = UUID.randomUUID()
        val conversationId = UUID.randomUUID()
        val testLlmConfig = LlmConfig(apiKey = "test-key")
        val testDatabaseConfig = DatabaseConfig(
            jdbcUrl = "jdbc:h2:mem:test",
            username = "sa",
            password = "",
        )

        val conversation = ConversationRepository.Conversation(
            id = conversationId,
            userId = userId,
            personaId = UUID.randomUUID(),
            state = "active",
            lastMessageAt = null,
            createdAt = LocalDateTime.now(),
        )

        val db = com.pinkdreams.persistence.database.DatabaseFactory.connectInMemory()
        val mockConversationRepo = object : ConversationRepository(db) {
            override fun findByIdForUser(id: UUID, foundUserId: UUID): ConversationRepository.Conversation? {
                return if (id == conversationId && foundUserId == userId) {
                    conversation
                } else null
            }
        }

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, conversationRepository = mockConversationRepo)
        }

        val response = client.get("/v1/conversations/$conversationId/messages?limit=-1") {
            contentType(ContentType.Application.Json)
            basicAuth("${userId}", "password")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET conversations respects max limit`() = testApplication {
        val userId = UUID.randomUUID()
        val testLlmConfig = LlmConfig(apiKey = "test-key")
        val testDatabaseConfig = DatabaseConfig(
            jdbcUrl = "jdbc:h2:mem:test",
            username = "sa",
            password = "",
        )

        val db = com.pinkdreams.persistence.database.DatabaseFactory.connectInMemory()
        val mockConversationRepo = object : ConversationRepository(db) {
            override fun findByIdForUser(id: UUID, userId: UUID): ConversationRepository.Conversation? = null
        }

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, conversationRepository = mockConversationRepo)
        }

        val response = client.get("/v1/conversations?limit=999") {
            contentType(ContentType.Application.Json)
            basicAuth("${userId}", "password")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET messages without authentication returns 401`() = testApplication {
        val conversationId = UUID.randomUUID()
        val testLlmConfig = LlmConfig(apiKey = "test-key")
        val testDatabaseConfig = DatabaseConfig(
            jdbcUrl = "jdbc:h2:mem:test",
            username = "sa",
            password = "",
        )

        val db = com.pinkdreams.persistence.database.DatabaseFactory.connectInMemory()
        val mockConversationRepo = object : ConversationRepository(db) {
            override fun findByIdForUser(id: UUID, userId: UUID): ConversationRepository.Conversation? = null
        }

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, conversationRepository = mockConversationRepo)
        }

        val response = client.get("/v1/conversations/$conversationId/messages") {
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
