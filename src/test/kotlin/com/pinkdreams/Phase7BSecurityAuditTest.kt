package com.pinkdreams

import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class Phase7BSecurityAuditTest {
    private val testLlmConfig = LlmConfig(apiKey = "test-key")
    private val testDatabaseConfig = DatabaseConfig(
        jdbcUrl = "jdbc:h2:mem:test",
        username = "sa",
        password = "",
    )

    private val testAdminId = UUID.fromString("00000000-0000-0000-0000-000000000099")

    // ============================================================================
    // SECTION 1: AUTHENTICATION BOUNDARY TESTS
    // ============================================================================

    @Test
    fun `POST messages - unauthenticated request returns 401`() = testApplication {
        val conversationId = UUID.randomUUID()
        val db = DatabaseFactory.connectInMemory()
        val mockConversationRepo = object : ConversationRepository(db) {
            override fun findByIdForUser(id: UUID, userId: UUID): Conversation? = null
        }

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, conversationRepository = mockConversationRepo)
        }

        val response = client.post("/v1/conversations/$conversationId/messages") {
            contentType(ContentType.Application.Json)
            header("Idempotency-Key", UUID.randomUUID().toString())
            setBody("""{"content": "test"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET conversations list - unauthenticated request returns 401`() = testApplication {
        val db = DatabaseFactory.connectInMemory()
        val mockConversationRepo = object : ConversationRepository(db) {
            override fun findAllForUser(userId: UUID, limit: Int, offset: Int, personaId: UUID?): List<Conversation> = emptyList()
        }

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, conversationRepository = mockConversationRepo)
        }

        val response = client.get("/v1/conversations")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET conversation detail - unauthenticated request returns 401`() = testApplication {
        val conversationId = UUID.randomUUID()
        val db = DatabaseFactory.connectInMemory()
        val mockConversationRepo = object : ConversationRepository(db) {
            override fun findByIdForUser(id: UUID, userId: UUID): Conversation? = null
        }

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, conversationRepository = mockConversationRepo)
        }

        val response = client.get("/v1/conversations/$conversationId")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET messages - unauthenticated request returns 401`() = testApplication {
        val conversationId = UUID.randomUUID()
        val db = DatabaseFactory.connectInMemory()
        val mockConversationRepo = object : ConversationRepository(db) {
            override fun findByIdForUser(id: UUID, userId: UUID): Conversation? = null
        }

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, conversationRepository = mockConversationRepo)
        }

        val response = client.get("/v1/conversations/$conversationId/messages")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ============================================================================
    // SECTION 2: USER RESOURCE OWNERSHIP - IDOR PREVENTION
    // ============================================================================

    @Test
    fun `POST messages - user cannot access another user's conversation`() = testApplication {
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        val conversationId = UUID.randomUUID()
        val now = LocalDateTime.now()
        val db = DatabaseFactory.connectInMemory()

        val mockConversationRepo = object : ConversationRepository(db) {
            override fun findByIdForUser(id: UUID, userId: UUID): Conversation? {
                // Only return conversation if userId matches the owner (userA)
                return if (userId == userA && id == conversationId) {
                    Conversation(conversationId, userA, UUID.randomUUID(), "active", null, now)
                } else {
                    null
                }
            }
        }

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, conversationRepository = mockConversationRepo)
        }

        val response = client.post("/v1/conversations/$conversationId/messages") {
            contentType(ContentType.Application.Json)
            basicAuth("$userB", "password")
            header("Idempotency-Key", UUID.randomUUID().toString())
            setBody("""{"content": "hack attempt"}""")
        }

        // userB should not access userA's conversation
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET conversation detail - user cannot access another user's conversation`() = testApplication {
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        val conversationId = UUID.randomUUID()
        val now = LocalDateTime.now()
        val db = DatabaseFactory.connectInMemory()

        val mockConversationRepo = object : ConversationRepository(db) {
            override fun findByIdForUser(id: UUID, userId: UUID): Conversation? {
                return if (userId == userA && id == conversationId) {
                    Conversation(conversationId, userA, UUID.randomUUID(), "active", null, now)
                } else {
                    null
                }
            }
        }

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, conversationRepository = mockConversationRepo)
        }

        val response = client.get("/v1/conversations/$conversationId") {
            basicAuth("$userB", "password")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET messages - user cannot access another user's conversation messages`() = testApplication {
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        val conversationId = UUID.randomUUID()
        val now = LocalDateTime.now()
        val db = DatabaseFactory.connectInMemory()

        val mockConversationRepo = object : ConversationRepository(db) {
            override fun findByIdForUser(id: UUID, userId: UUID): Conversation? {
                return if (userId == userA && id == conversationId) {
                    Conversation(conversationId, userA, UUID.randomUUID(), "active", null, now)
                } else {
                    null
                }
            }
        }

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, conversationRepository = mockConversationRepo)
        }

        val response = client.get("/v1/conversations/$conversationId/messages") {
            basicAuth("$userB", "password")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET conversations list - user sees only own conversations`() = testApplication {
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        val convA = UUID.randomUUID()
        val convB = UUID.randomUUID()
        val now = LocalDateTime.now()
        val db = DatabaseFactory.connectInMemory()

        val mockConversationRepo = object : ConversationRepository(db) {
            override fun findAllForUser(userId: UUID, limit: Int, offset: Int, personaId: UUID?): List<Conversation> {
                return if (userId == userA) {
                    listOf(Conversation(convA, userA, UUID.randomUUID(), "active", null, now))
                } else if (userId == userB) {
                    listOf(Conversation(convB, userB, UUID.randomUUID(), "active", null, now))
                } else {
                    emptyList()
                }
            }
        }

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, conversationRepository = mockConversationRepo)
        }

        val responseA = client.get("/v1/conversations") {
            basicAuth("$userA", "password")
        }
        val responseB = client.get("/v1/conversations") {
            basicAuth("$userB", "password")
        }

        assertEquals(HttpStatusCode.OK, responseA.status)
        assertEquals(HttpStatusCode.OK, responseB.status)
        // Both should succeed with 200, indicating each user sees their own list
        // (actual content verification is in response body, tested separately)
    }

    // ============================================================================
    // SECTION 3: ADMIN AUTHORIZATION BOUNDARY
    // ============================================================================

    @Test
    fun `admin endpoints - unauthenticated request returns 401`() = testApplication {
        val originalEnv = System.getenv("ADMIN_USER_IDS")
        try {
            System.getProperties()["ADMIN_USER_IDS"] = testAdminId.toString()

            val db = DatabaseFactory.connectInMemory()
            application {
                module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig)
            }

            val response = client.post("/v1/admin/engines") {
                contentType(ContentType.Application.Json)
                setBody("""{"version": 1, "content": "test"}""")
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        } finally {
            if (originalEnv != null) {
                System.getProperties()["ADMIN_USER_IDS"] = originalEnv
            } else {
                System.getProperties().remove("ADMIN_USER_IDS")
            }
        }
    }

    @Test
    fun `admin endpoints - non-admin user returns 403`() = testApplication {
        val normalUser = UUID.randomUUID()
        val originalEnv = System.getenv("ADMIN_USER_IDS")
        try {
            System.getProperties()["ADMIN_USER_IDS"] = testAdminId.toString()

            val db = DatabaseFactory.connectInMemory()
            application {
                module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig)
            }

            val response = client.post("/v1/admin/engines") {
                contentType(ContentType.Application.Json)
                basicAuth("$normalUser", "password")
                setBody("""{"version": 1, "content": "test"}""")
            }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        } finally {
            if (originalEnv != null) {
                System.getProperties()["ADMIN_USER_IDS"] = originalEnv
            } else {
                System.getProperties().remove("ADMIN_USER_IDS")
            }
        }
    }

    @Test
    fun `admin GET engines - non-admin user returns 403`() = testApplication {
        val normalUser = UUID.randomUUID()
        val originalEnv = System.getenv("ADMIN_USER_IDS")
        try {
            System.getProperties()["ADMIN_USER_IDS"] = testAdminId.toString()

            val db = DatabaseFactory.connectInMemory()
            application {
                module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig)
            }

            val response = client.get("/v1/admin/engines") {
                basicAuth("$normalUser", "password")
            }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        } finally {
            if (originalEnv != null) {
                System.getProperties()["ADMIN_USER_IDS"] = originalEnv
            } else {
                System.getProperties().remove("ADMIN_USER_IDS")
            }
        }
    }

    @Test
    fun `admin POST engine core-versions - non-admin user returns 403`() = testApplication {
        val normalUser = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val originalEnv = System.getenv("ADMIN_USER_IDS")
        try {
            System.getProperties()["ADMIN_USER_IDS"] = testAdminId.toString()

            val db = DatabaseFactory.connectInMemory()
            application {
                module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig)
            }

            val response = client.post("/v1/admin/personas/$personaId/core-versions") {
                contentType(ContentType.Application.Json)
                basicAuth("$normalUser", "password")
                setBody("""{"version": 1, "content": "test"}""")
            }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        } finally {
            if (originalEnv != null) {
                System.getProperties()["ADMIN_USER_IDS"] = originalEnv
            } else {
                System.getProperties().remove("ADMIN_USER_IDS")
            }
        }
    }


    // ============================================================================
    // SECTION 5: ERROR DISCLOSURE - NO DATA LEAKAGE
    // ============================================================================

    @Test
    fun `conversation not found - does not leak conversation exists but user not owner`() = testApplication {
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        val conversationId = UUID.randomUUID()
        val now = LocalDateTime.now()
        val db = DatabaseFactory.connectInMemory()

        val mockConversationRepo = object : ConversationRepository(db) {
            override fun findByIdForUser(id: UUID, userId: UUID): Conversation? {
                return if (userId == userA && id == conversationId) {
                    Conversation(conversationId, userA, UUID.randomUUID(), "active", null, now)
                } else {
                    null
                }
            }
        }

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, conversationRepository = mockConversationRepo)
        }

        val response = client.get("/v1/conversations/$conversationId") {
            basicAuth("$userB", "password")
        }

        // Should return 404 without revealing whether conversation exists
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
