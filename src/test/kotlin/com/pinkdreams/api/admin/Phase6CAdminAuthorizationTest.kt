package com.pinkdreams.api.admin

import com.pinkdreams.module
import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import io.ktor.client.request.basicAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class Phase6CAdminAuthorizationTest {

    private val testAdminId = UUID.fromString("00000000-0000-0000-0000-000000000099")

    @Test
    fun `normal user cannot access admin engine endpoints - returns 403`() {
        val userId = UUID.randomUUID()

        // Configure admin users for this test
        val originalEnv = System.getenv("ADMIN_USER_IDS")
        try {
            System.getProperties()["ADMIN_USER_IDS"] = testAdminId.toString()

            testApplication {
                val testLlmConfig = LlmConfig(apiKey = "test-key")
                val testDatabaseConfig = DatabaseConfig(
                    jdbcUrl = "jdbc:h2:mem:test",
                    username = "sa",
                    password = "",
                )

                val db = com.pinkdreams.persistence.database.DatabaseFactory.connectInMemory()
                val mockConversationRepo = object : com.pinkdreams.persistence.repositories.ConversationRepository(db) {
                    override fun findByIdForUser(id: UUID, userId: UUID): com.pinkdreams.persistence.repositories.ConversationRepository.Conversation? = null
                }

                application {
                    module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, conversationRepository = mockConversationRepo)
                }

                val response = client.post("/v1/admin/engines") {
                    contentType(ContentType.Application.Json)
                    basicAuth("${userId}", "password")
                    setBody("""{"version": 1, "content": "test"}""")
                }

                assertEquals(HttpStatusCode.Forbidden, response.status)
            }
        } finally {
            if (originalEnv != null) {
                System.getProperties()["ADMIN_USER_IDS"] = originalEnv
            } else {
                System.getProperties().remove("ADMIN_USER_IDS")
            }
        }
    }

    @Test
    fun `unauthenticated request to admin endpoint returns 401`() {
        val originalEnv = System.getenv("ADMIN_USER_IDS")
        try {
            System.getProperties()["ADMIN_USER_IDS"] = testAdminId.toString()

            testApplication {
                val testLlmConfig = LlmConfig(apiKey = "test-key")
                val testDatabaseConfig = DatabaseConfig(
                    jdbcUrl = "jdbc:h2:mem:test",
                    username = "sa",
                    password = "",
                )

                val db = com.pinkdreams.persistence.database.DatabaseFactory.connectInMemory()
                val mockConversationRepo = object : com.pinkdreams.persistence.repositories.ConversationRepository(db) {
                    override fun findByIdForUser(id: UUID, userId: UUID): com.pinkdreams.persistence.repositories.ConversationRepository.Conversation? = null
                }

                application {
                    module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, conversationRepository = mockConversationRepo)
                }

                val response = client.post("/v1/admin/engines") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"version": 1, "content": "test"}""")
                }

                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
        } finally {
            if (originalEnv != null) {
                System.getProperties()["ADMIN_USER_IDS"] = originalEnv
            } else {
                System.getProperties().remove("ADMIN_USER_IDS")
            }
        }
    }
}
