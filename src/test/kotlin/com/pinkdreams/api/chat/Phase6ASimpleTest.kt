package com.pinkdreams.api.chat

import com.pinkdreams.module
import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class Phase6ASimpleTest {

    @Test
    fun `health endpoint still works in test`() = testApplication {
        val testLlmConfig = LlmConfig(apiKey = "test-key")
        val testDatabaseConfig = DatabaseConfig(
            jdbcUrl = "jdbc:h2:mem:test",
            username = "sa",
            password = "",
        )

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig)
        }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
