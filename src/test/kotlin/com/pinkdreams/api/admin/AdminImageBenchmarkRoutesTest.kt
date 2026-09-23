package com.pinkdreams.api.admin

import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.module
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.PersonaIdentityRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import com.pinkdreams.persistence.repositories.PersonalGuideRepository
import com.pinkdreams.persistence.repositories.ReferenceImageRepository
import com.pinkdreams.storage.InMemoryObjectStorage
import com.pinkdreams.visual.identity.PersonaVisualAdminService
import com.pinkdreams.visual.identity.ReferenceRole
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminImageBenchmarkRoutesTest {
    private val adminId = UUID.fromString("00000000-0000-0000-0000-0000000000b0")

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
                adminAuthProvider = TestAdminAuthProvider(adminId),
                database = db,
            )
        }
        return db
    }

    @Test
    fun `readiness prompts and create run without generation`() = testApplication {
        val db = setup()
        val personaRepo = PersonaRepository(db)
        val storage = InMemoryObjectStorage()
        val visualAdmin = PersonaVisualAdminService(
            personaRepository = personaRepo,
            personaIdentityRepository = PersonaIdentityRepository(db),
            visualVersionRepository = PersonaVisualVersionRepository(db),
            personalGuideRepository = PersonalGuideRepository(db),
            referenceImageRepository = ReferenceImageRepository(db, storage),
        )
        val persona = personaRepo.create(
            slug = "ib-${UUID.randomUUID().toString().take(8)}",
            displayName = "IB Persona",
            gender = "female",
            orientation = "straight",
            apparentAge = 26,
            languageProfile = emptyMap(),
        )
        visualAdmin.ensureDraft(persona.id, author = "test")
        ReferenceRole.STANDARD_SLOTS.forEach { role ->
            visualAdmin.uploadReference(persona.id, role, ByteArray(32) { 3 }, "image/png")
        }
        visualAdmin.publishAndActivateDraft(persona.id)

        val ready = client.get("/v1/admin/images/benchmarks/readiness") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.OK, ready.status)
        val readyJson = Json.parseToJsonElement(ready.bodyAsText()).jsonObject
        assertTrue(readyJson["status"]!!.jsonPrimitive.content in setOf("READY", "NOT_READY"))
        assertTrue(readyJson["productionModel"]!!.jsonPrimitive.content.isNotBlank())

        val prompts = client.get("/v1/admin/images/benchmarks/prompts") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(4, Json.parseToJsonElement(prompts.bodyAsText()).jsonArray.size)

        val personas = client.get("/v1/admin/images/benchmarks/personas") {
            basicAuth(adminId.toString(), "x")
        }
        assertTrue(personas.bodyAsText().contains(persona.id.toString()))

        val created = client.post("/v1/admin/images/benchmarks") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody(
                """{"name":"prep","personaIds":["${persona.id}"],"slotKeys":["SEEDREAM_5_PRO"],"promptIds":["PROMPT_01"],"start":false}"""
            )
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
        assertTrue(body["status"]!!.jsonPrimitive.content in setOf("READY", "COMPLETED"))
        assertTrue(body["executions"]!!.jsonArray.size >= 1)
        assertTrue(body["executions"]!!.jsonArray.all { it.jsonObject["jobId"] is kotlinx.serialization.json.JsonNull })

        val compare = client.get("/v1/admin/images/benchmarks/${body["id"]!!.jsonPrimitive.content}/comparison") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.OK, compare.status)
        assertTrue(compare.bodyAsText().contains("No winner"))
    }
}
