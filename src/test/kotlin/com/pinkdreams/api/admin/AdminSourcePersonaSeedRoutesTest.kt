package com.pinkdreams.api.admin

import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.module
import com.pinkdreams.persistence.database.DatabaseFactory
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
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
import kotlin.test.assertTrue

class AdminSourcePersonaSeedRoutesTest {
    private val adminId = UUID.fromString("00000000-0000-0000-0000-000000000031")

    private class TestAdminAuthProvider(private val adminId: UUID) : com.pinkdreams.auth.AdminAuthorizationProvider() {
        override fun isAdmin(userId: String): Boolean = userId == adminId.toString()
    }

    @Test
    fun `admin seed source packages then personas and visual are visible`() = testApplication {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        application {
            module(
                databaseConfig = DatabaseConfig(jdbcUrl = "jdbc:h2:mem:test", username = "sa", password = ""),
                llmConfig = LlmConfig(apiKey = "test-key"),
                chatEngine = null,
                adminAuthProvider = TestAdminAuthProvider(adminId),
                database = db,
            )
        }
        val seed = client.post("/v1/admin/personas/seed-source-packages") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.OK, seed.status)
        val seeded = Json.parseToJsonElement(seed.bodyAsText()).jsonObject["seeded"]!!.jsonArray
        assertEquals(3, seeded.size)
        val ids = seeded.associate { it.jsonObject["slug"]!!.jsonPrimitive.content to it.jsonObject["personaId"]!!.jsonPrimitive.content }

        val list = client.get("/v1/admin/personas") { basicAuth(adminId.toString(), "x") }
        assertEquals(HttpStatusCode.OK, list.status)
        val body = list.bodyAsText()
        assertTrue(body.contains("Aanya") || body.contains("Anaya"))
        assertTrue(body.contains("Zoya"))
        assertTrue(body.contains("Pihu"))

        ids.values.forEach { personaId ->
            val visual = client.get("/v1/admin/personas/$personaId/visual") { basicAuth(adminId.toString(), "x") }
            assertEquals(HttpStatusCode.OK, visual.status)
            assertTrue(visual.bodyAsText().contains("reference") || visual.bodyAsText().contains("versions") || visual.bodyAsText().contains("identity"))
            val warehouse = client.get("/v1/admin/personas/$personaId/images/warehouse") { basicAuth(adminId.toString(), "x") }
            assertEquals(HttpStatusCode.OK, warehouse.status)
            assertTrue(warehouse.bodyAsText().contains("candidates") || warehouse.bodyAsText().contains("[]") || warehouse.bodyAsText().isNotBlank())
        }
    }
}
