package com.pinkdreams

import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthEndpointTest {

    @BeforeTest
    fun setUp() {
        System.setProperty("OPENROUTER_API_KEY", "test-key-for-health-test")
    }

    @Test
    fun `health endpoint returns ok`() = testApplication {
        application {
            val testLlmConfig = LlmConfig(
                apiKey = "test-key-for-health-test",
                provider = "openrouter",
                endpoint = "https://openrouter.ai/api/v1/chat/completions",
                model = "deepseek/deepseek-v4.1-flash",
                maxOutputTokens = 1024,
                timeoutSeconds = 60,
            )
            val testDatabaseConfig = DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:test",
                username = "sa",
                password = "",
            )
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig)
        }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("status"))
        assertTrue(response.bodyAsText().contains("ok"))
    }
}
