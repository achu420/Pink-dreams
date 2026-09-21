package com.pinkdreams.api.admin

import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.module
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.IntentEngineRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
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

/** Phase ADMIN-2 sections 18, 25, 29: the new admin endpoints. */
class AdminIntentEngineAndSettingsRoutesTest {
    private val adminId = UUID.fromString("00000000-0000-0000-0000-0000000000f1")
    private val nonAdminId = UUID.fromString("00000000-0000-0000-0000-0000000000f2")

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

    // ---------- Intent Engine ----------

    @Test
    fun `intent engine versions are server numbered and follow the lifecycle`() = testApplication {
        setup()

        val created = client.post("/v1/admin/intent-engines") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"content": "rules v1", "changelogNote": "first"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val body = json(created.bodyAsText())
        assertEquals(1, body["version"]!!.jsonPrimitive.content.toInt())
        assertEquals("draft", body["status"]!!.jsonPrimitive.content)
        assertEquals(false, body["isActive"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(adminId.toString(), body["createdBy"]!!.jsonPrimitive.content)
        val id = body["id"]!!.jsonPrimitive.content

        val published = client.post("/v1/admin/intent-engines/$id/publish") { basicAuth(adminId.toString(), "x") }
        assertEquals("published", json(published.bodyAsText())["status"]!!.jsonPrimitive.content)

        val activated = client.post("/v1/admin/intent-engines/$id/activate") { basicAuth(adminId.toString(), "x") }
        assertTrue(json(activated.bodyAsText())["isActive"]!!.jsonPrimitive.content.toBoolean())

        // An active version cannot be archived.
        val archived = client.post("/v1/admin/intent-engines/$id/archive") { basicAuth(adminId.toString(), "x") }
        assertEquals(HttpStatusCode.Conflict, archived.status)
    }

    @Test
    fun `the intent engine listing reports the active skill candidates`() = testApplication {
        val db = setup()
        val skills = SkillRepository(db)
        val draft = skills.createNextVersion("general_chat", "SKILL: GENERAL_CHAT")
        skills.activate(skills.publish(draft.id).id)
        // A second key left as draft must not appear.
        skills.createNextVersion("flirting", "SKILL: FLIRTING")

        val response = client.get("/v1/admin/intent-engines") { basicAuth(adminId.toString(), "x") }

        val candidates = json(response.bodyAsText())["activeSkillCandidates"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("general_chat"), candidates, "Only active skills may be offered as candidates")
    }

    @Test
    fun `intent engine endpoints reject non admins`() = testApplication {
        setup()
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/v1/admin/intent-engines") { basicAuth(nonAdminId.toString(), "x") }.status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            client.post("/v1/admin/intent-engines") {
                basicAuth(nonAdminId.toString(), "x")
                contentType(ContentType.Application.Json)
                setBody("""{"content": "x"}""")
            }.status,
        )
    }

    @Test
    fun `a client supplied version number cannot influence the assigned version`() = testApplication {
        setup()
        // "version" is not part of the request contract; sending it changes nothing.
        val created = client.post("/v1/admin/intent-engines") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"content": "rules", "version": 99}""")
        }
        assertEquals(1, json(created.bodyAsText())["version"]!!.jsonPrimitive.content.toInt())
    }

    // ---------- AI settings ----------

    @Test
    fun `ai settings default to the environment and report their source`() = testApplication {
        setup()

        val response = client.get("/v1/admin/ai-settings") { basicAuth(adminId.toString(), "x") }

        val body = json(response.bodyAsText())
        assertEquals("ENVIRONMENT_OR_DEFAULT", body["modelSource"]!!.jsonPrimitive.content)
        assertEquals("ENVIRONMENT_OR_DEFAULT", body["maxOutputTokensSource"]!!.jsonPrimitive.content)
    }

    @Test
    fun `saving ai settings requires explicit confirmation`() = testApplication {
        setup()

        val unconfirmed = client.put("/v1/admin/ai-settings") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"model": "new-model", "maxOutputTokens": 2048}""")
        }
        assertEquals(HttpStatusCode.BadRequest, unconfirmed.status, "A change affecting all generation must be confirmed")

        // Nothing was stored.
        val after = json(client.get("/v1/admin/ai-settings") { basicAuth(adminId.toString(), "x") }.bodyAsText())
        assertEquals("ENVIRONMENT_OR_DEFAULT", after["modelSource"]!!.jsonPrimitive.content)
    }

    @Test
    fun `confirmed ai settings are stored and reported as database sourced`() = testApplication {
        setup()

        val saved = client.put("/v1/admin/ai-settings") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"model": "new-model", "temperature": 0.4, "maxOutputTokens": 2048, "confirm": true}""")
        }

        assertEquals(HttpStatusCode.OK, saved.status)
        val body = json(saved.bodyAsText())
        assertEquals("new-model", body["model"]!!.jsonPrimitive.content)
        assertEquals("DATABASE", body["modelSource"]!!.jsonPrimitive.content)
        assertEquals(adminId.toString(), body["updatedBy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an out of range temperature is rejected with a validation error`() = testApplication {
        setup()

        val response = client.put("/v1/admin/ai-settings") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"temperature": 7.5, "confirm": true}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `ai settings endpoints reject non admins`() = testApplication {
        setup()
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/v1/admin/ai-settings") { basicAuth(nonAdminId.toString(), "x") }.status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            client.put("/v1/admin/ai-settings") {
                basicAuth(nonAdminId.toString(), "x")
                contentType(ContentType.Application.Json)
                setBody("""{"confirm": true}""")
            }.status,
        )
    }

    // ---------- Task 9 — Admin AI Runtime Controls ----------

    @Test
    fun `intent and provider-sort settings default to their code default source`() = testApplication {
        setup()

        val body = json(client.get("/v1/admin/ai-settings") { basicAuth(adminId.toString(), "x") }.bodyAsText())

        assertEquals("CODE_DEFAULT", body["intentModelSource"]!!.jsonPrimitive.content)
        assertEquals("CODE_DEFAULT", body["intentJsonModeSource"]!!.jsonPrimitive.content)
        assertEquals("CODE_DEFAULT", body["intentMaxOutputTokensSource"]!!.jsonPrimitive.content)
    }

    @Test
    fun `saving intent and provider-sort settings persists them and reports DATABASE as the source`() = testApplication {
        setup()

        val saved = client.put("/v1/admin/ai-settings") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"intentModel": "claude-x", "intentJsonMode": false, "intentMaxOutputTokens": 300, "generationProviderSort": "latency", "confirm": true}""")
        }

        assertEquals(HttpStatusCode.OK, saved.status)
        val body = json(saved.bodyAsText())
        assertEquals("claude-x", body["intentModel"]!!.jsonPrimitive.content)
        assertEquals("DATABASE", body["intentModelSource"]!!.jsonPrimitive.content)
        assertEquals(false, body["intentJsonMode"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("DATABASE", body["intentJsonModeSource"]!!.jsonPrimitive.content)
        assertEquals(300, body["intentMaxOutputTokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("DATABASE", body["intentMaxOutputTokensSource"]!!.jsonPrimitive.content)
        assertEquals("latency", body["generationProviderSort"]!!.jsonPrimitive.content)
        assertEquals("DATABASE", body["generationProviderSortSource"]!!.jsonPrimitive.content)
    }

    @Test
    fun `resetting intent model to null clears the override and reverts the source to the code default`() = testApplication {
        setup()
        client.put("/v1/admin/ai-settings") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"intentModel": "claude-x", "confirm": true}""")
        }

        val reset = client.put("/v1/admin/ai-settings") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"intentModel": null, "confirm": true}""")
        }

        val body = json(reset.bodyAsText())
        assertEquals("CODE_DEFAULT", body["intentModelSource"]!!.jsonPrimitive.content, "Reset must fall back to the code default, not persist it as a new stored value")
        assertEquals(null, body["storedIntentModel"])
    }

    @Test
    fun `an out of range intent max output tokens is rejected with a validation error`() = testApplication {
        setup()

        val response = client.put("/v1/admin/ai-settings") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"intentMaxOutputTokens": 0, "confirm": true}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `an unsupported generation provider sort value is rejected with a validation error`() = testApplication {
        setup()

        val response = client.put("/v1/admin/ai-settings") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"generationProviderSort": "throughput", "confirm": true}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `a blank intent model is treated as clearing the override same as the existing model field convention`() = testApplication {
        setup()

        val response = client.put("/v1/admin/ai-settings") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"intentModel": "   ", "confirm": true}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("CODE_DEFAULT", json(response.bodyAsText())["intentModelSource"]!!.jsonPrimitive.content)
    }

    // ---------- section 29: configuration overview ----------

    @Test
    fun `the configuration overview answers which configuration is running`() = testApplication {
        val db = setup()
        val intentEngines = IntentEngineRepository(db)
        val skills = SkillRepository(db)
        val intentDraft = intentEngines.createNextVersion("rules", createdBy = "admin")
        intentEngines.activate(intentEngines.publish(intentDraft.id).id)
        val skillDraft = skills.createNextVersion("general_chat", "SKILL: GENERAL_CHAT")
        skills.activate(skills.publish(skillDraft.id).id)

        val response = client.get("/v1/admin/ai-configuration") { basicAuth(adminId.toString(), "x") }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        assertEquals(1, body["intentEngine"]!!.jsonObject["version"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, body["activeSkillCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf("general_chat"), body["activeSkillKeys"]!!.jsonArray.map { it.jsonPrimitive.content })
        // Runtime settings are part of the same answer.
        assertTrue(body["runtime"]!!.jsonObject.containsKey("model"))
    }

    @Test
    fun `the overview reports a missing active engine as null rather than failing`() = testApplication {
        setup()

        val response = client.get("/v1/admin/ai-configuration") { basicAuth(adminId.toString(), "x") }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        // Depending on the serializer's explicitNulls setting the field is either
        // absent or JSON null; both mean "no active version", and neither is an error.
        val version = body["conversationEngine"]!!.jsonObject["version"]
        assertTrue(
            version == null || version is kotlinx.serialization.json.JsonNull,
            "A missing active engine must be reported, not throw: got $version",
        )
        assertEquals(0, body["activeSkillCount"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `the configuration overview rejects non admins`() = testApplication {
        setup()
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/v1/admin/ai-configuration") { basicAuth(nonAdminId.toString(), "x") }.status,
        )
    }
}
