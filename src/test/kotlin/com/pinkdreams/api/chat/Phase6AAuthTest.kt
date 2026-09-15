package com.pinkdreams.api.chat

import com.pinkdreams.module
import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.persistence.repositories.ConversationRepository
import io.ktor.client.request.basicAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class Phase6AAuthTest {

    @Test
    fun `unauthenticated request returns 401`() = testApplication {
        val conversationId = UUID.randomUUID()
        val testLlmConfig = LlmConfig(apiKey = "test-key")
        val testDatabaseConfig = DatabaseConfig(
            jdbcUrl = "jdbc:h2:mem:test",
            username = "sa",
            password = "",
        )

        val mockConversationRepo = object : ConversationRepository(
            com.pinkdreams.persistence.database.DatabaseFactory.connectInMemory()
        ) {
            override fun findByIdForUser(id: UUID, userId: UUID): Conversation? = null
        }

        application {
            module(
                databaseConfig = testDatabaseConfig,
                llmConfig = testLlmConfig,
                conversationRepository = mockConversationRepo
            )
        }

        val response = client.post("/v1/conversations/$conversationId/messages") {
            contentType(ContentType.Application.Json)
            header("Idempotency-Key", UUID.randomUUID().toString())
            setBody("""{"content": "test"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `authenticated request with basic auth works`() = testApplication {
        val userId = UUID.randomUUID()
        val conversationId = UUID.randomUUID()
        val testLlmConfig = LlmConfig(apiKey = "test-key")
        val testDatabaseConfig = DatabaseConfig(
            jdbcUrl = "jdbc:h2:mem:test",
            username = "sa",
            password = "",
        )

        val mockConversationRepo = object : ConversationRepository(
            com.pinkdreams.persistence.database.DatabaseFactory.connectInMemory()
        ) {
            override fun findByIdForUser(id: UUID, userId: UUID): Conversation? {
                return Conversation(conversationId, userId, UUID.randomUUID(), "active", null, java.time.LocalDateTime.now())
            }
        }

        val mockEngine = object : com.pinkdreams.chat.ChatEngine {
            override fun process(request: com.pinkdreams.chat.ChatRequest): com.pinkdreams.chat.ChatResult {
                return com.pinkdreams.chat.ChatResult.Success(
                    requestId = request.requestId,
                    response = com.pinkdreams.chat.PersistedResponse(
                        assistantMessageId = UUID.randomUUID(),
                        content = "test response",
                    ),
                    state = com.pinkdreams.chat.PipelineState(
                        com.pinkdreams.chat.PipelineStage.DELIVER,
                        listOf()
                    ),
                )
            }
        }

        application {
            module(
                databaseConfig = testDatabaseConfig,
                llmConfig = testLlmConfig,
                chatEngine = mockEngine,
                conversationRepository = mockConversationRepo
            )
        }

        val response = client.post("/v1/conversations/$conversationId/messages") {
            contentType(ContentType.Application.Json)
            basicAuth("${userId}", "password")
            header("Idempotency-Key", UUID.randomUUID().toString())
            setBody("""{"content": "hello"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }
}
