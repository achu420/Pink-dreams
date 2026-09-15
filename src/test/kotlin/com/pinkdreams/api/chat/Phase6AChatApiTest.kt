package com.pinkdreams.api.chat

import com.pinkdreams.module
import com.pinkdreams.chat.ChatEngine
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ChatResult
import com.pinkdreams.chat.PersistedResponse
import com.pinkdreams.chat.PipelineState
import com.pinkdreams.chat.PipelineStage
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationRepository
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.util.encodeBase64
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class Phase6AChatApiTest {

    private fun createMockConversationRepo(conversations: Map<Pair<UUID, UUID>, ConversationRepository.Conversation>): ConversationRepository {
        // Create a minimal mock that delegates to the real implementation
        // but overrides the repository data
        val realDb = DatabaseFactory.connectInMemory()
        val realRepo = ConversationRepository(realDb)

        return object : ConversationRepository(realDb) {
            override fun findByIdForUser(id: UUID, userId: UUID): Conversation? {
                return conversations[Pair(id, userId)]
            }
        }
    }

    @Test
    fun `send message to own conversation succeeds`() = testApplication {
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val conversationId = UUID.randomUUID()

        val testLlmConfig = LlmConfig(apiKey = "test-key")
        val testDatabaseConfig = DatabaseConfig(
            jdbcUrl = "jdbc:h2:mem:test",
            username = "sa",
            password = "",
        )

        val mockConversationRepo = createMockConversationRepo(
            mapOf(
                Pair(conversationId, userId) to ConversationRepository.Conversation(
                    conversationId, userId, personaId, "active", null, LocalDateTime.now()
                )
            )
        )

        val mockEngine = object : ChatEngine {
            override fun process(request: ChatRequest): ChatResult {
                return ChatResult.Success(
                    requestId = request.requestId,
                    response = PersistedResponse(
                        assistantMessageId = UUID.randomUUID(),
                        content = "response text",
                    ),
                    state = PipelineState(PipelineStage.DELIVER, listOf()),
                )
            }
        }

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, chatEngine = mockEngine, conversationRepository = mockConversationRepo)
        }

        val idempotencyKey = UUID.randomUUID().toString()
        val authHeader = "Basic ${"${userId}:password".encodeBase64()}"
        val response = client.post("/v1/conversations/$conversationId/messages") {
            contentType(ContentType.Application.Json)
            header("Authorization", authHeader)
            header("Idempotency-Key", idempotencyKey)
            setBody("""{"content": "hello"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `send message without authentication returns 401`() = testApplication {
        val conversationId = UUID.randomUUID()

        val testLlmConfig = LlmConfig(apiKey = "test-key")
        val testDatabaseConfig = DatabaseConfig(
            jdbcUrl = "jdbc:h2:mem:test",
            username = "sa",
            password = "",
        )

        val mockConversationRepo = createMockConversationRepo(emptyMap())

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, conversationRepository = mockConversationRepo)
        }

        val response = client.post("/v1/conversations/$conversationId/messages") {
            contentType(ContentType.Application.Json)
            header("Idempotency-Key", UUID.randomUUID().toString())
            setBody("""{"content": "hello"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `send message to someone else's conversation returns 404`() = testApplication {
        val userId = UUID.randomUUID()
        val anotherUserId = UUID.randomUUID()
        val conversationId = UUID.randomUUID()

        val testLlmConfig = LlmConfig(apiKey = "test-key")
        val testDatabaseConfig = DatabaseConfig(
            jdbcUrl = "jdbc:h2:mem:test",
            username = "sa",
            password = "",
        )

        val mockConversationRepo = createMockConversationRepo(
            mapOf(
                Pair(conversationId, anotherUserId) to ConversationRepository.Conversation(
                    conversationId, anotherUserId, UUID.randomUUID(), "active", null, LocalDateTime.now()
                )
            )
        )

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, conversationRepository = mockConversationRepo)
        }

        val authHeader = "Basic ${"${userId}:password".encodeBase64()}"
        val response = client.post("/v1/conversations/$conversationId/messages") {
            contentType(ContentType.Application.Json)
            header("Authorization", authHeader)
            header("Idempotency-Key", UUID.randomUUID().toString())
            setBody("""{"content": "hello"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `send message with blank content returns 400`() = testApplication {
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val conversationId = UUID.randomUUID()

        val testLlmConfig = LlmConfig(apiKey = "test-key")
        val testDatabaseConfig = DatabaseConfig(
            jdbcUrl = "jdbc:h2:mem:test",
            username = "sa",
            password = "",
        )

        val mockConversationRepo = createMockConversationRepo(
            mapOf(
                Pair(conversationId, userId) to ConversationRepository.Conversation(
                    conversationId, userId, personaId, "active", null, LocalDateTime.now()
                )
            )
        )

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, conversationRepository = mockConversationRepo)
        }

        val authHeader = "Basic ${"${userId}:password".encodeBase64()}"
        val response = client.post("/v1/conversations/$conversationId/messages") {
            contentType(ContentType.Application.Json)
            header("Authorization", authHeader)
            header("Idempotency-Key", UUID.randomUUID().toString())
            setBody("""{"content": "   "}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `send message without idempotency key returns 400`() = testApplication {
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val conversationId = UUID.randomUUID()

        val testLlmConfig = LlmConfig(apiKey = "test-key")
        val testDatabaseConfig = DatabaseConfig(
            jdbcUrl = "jdbc:h2:mem:test",
            username = "sa",
            password = "",
        )

        val mockConversationRepo = createMockConversationRepo(
            mapOf(
                Pair(conversationId, userId) to ConversationRepository.Conversation(
                    conversationId, userId, personaId, "active", null, LocalDateTime.now()
                )
            )
        )

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, conversationRepository = mockConversationRepo)
        }

        val authHeader = "Basic ${"${userId}:password".encodeBase64()}"
        val response = client.post("/v1/conversations/$conversationId/messages") {
            contentType(ContentType.Application.Json)
            header("Authorization", authHeader)
            setBody("""{"content": "hello"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `send message with invalid conversation id returns 400`() = testApplication {
        val userId = UUID.randomUUID()

        val testLlmConfig = LlmConfig(apiKey = "test-key")
        val testDatabaseConfig = DatabaseConfig(
            jdbcUrl = "jdbc:h2:mem:test",
            username = "sa",
            password = "",
        )

        val mockConversationRepo = createMockConversationRepo(emptyMap())

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, conversationRepository = mockConversationRepo)
        }

        val authHeader = "Basic ${"${userId}:password".encodeBase64()}"
        val response = client.post("/v1/conversations/invalid-id/messages") {
            contentType(ContentType.Application.Json)
            header("Authorization", authHeader)
            header("Idempotency-Key", UUID.randomUUID().toString())
            setBody("""{"content": "hello"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `generation failure returns 502`() = testApplication {
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val conversationId = UUID.randomUUID()

        val testLlmConfig = LlmConfig(apiKey = "test-key")
        val testDatabaseConfig = DatabaseConfig(
            jdbcUrl = "jdbc:h2:mem:test",
            username = "sa",
            password = "",
        )

        val mockConversationRepo = createMockConversationRepo(
            mapOf(
                Pair(conversationId, userId) to ConversationRepository.Conversation(
                    conversationId, userId, personaId, "active", null, LocalDateTime.now()
                )
            )
        )

        val mockEngine = object : ChatEngine {
            override fun process(request: ChatRequest): ChatResult {
                return ChatResult.Failure(
                    requestId = request.requestId,
                    code = ErrorCode.GENERATION_FAILED,
                    state = PipelineState(PipelineStage.GENERATION, listOf()),
                )
            }
        }

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, chatEngine = mockEngine, conversationRepository = mockConversationRepo)
        }

        val authHeader = "Basic ${"${userId}:password".encodeBase64()}"
        val response = client.post("/v1/conversations/$conversationId/messages") {
            contentType(ContentType.Application.Json)
            header("Authorization", authHeader)
            header("Idempotency-Key", UUID.randomUUID().toString())
            setBody("""{"content": "hello"}""")
        }

        assertEquals(HttpStatusCode.BadGateway, response.status)
    }
}
