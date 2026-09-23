package com.pinkdreams.api.admin

import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.module
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Expanded tests for admin persona HTTP routes.
 *
 * Covers gaps not addressed by AdminPersonaPhase3ATest:
 *  - GET  /v1/admin/personas              → list
 *  - POST /v1/admin/personas              → create, 201 + id
 *  - GET  /v1/admin/personas/{id}/core-versions → version list
 *
 * Publish and activate lifecycle is already covered by AdminPersonaPhase3ATest.
 */
class AdminPersonaRoutesExpandedTest {

    private val adminId = UUID.fromString("00000000-0000-0000-0000-000000000ea1")
    private val nonAdminId = UUID.fromString("00000000-0000-0000-0000-000000000ea2")

    private class TestAdminAuth(private val adminId: UUID) : com.pinkdreams.auth.AdminAuthorizationProvider() {
        override fun isAdmin(userId: String): Boolean = userId == adminId.toString()
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.setup(): Pair<Database, PersonaRepository> {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        application {
            module(
                databaseConfig = DatabaseConfig(jdbcUrl = "jdbc:h2:mem:test", username = "sa", password = ""),
                llmConfig = LlmConfig(apiKey = "test-key"),
                chatEngine = null,
                adminAuthProvider = TestAdminAuth(adminId),
                database = db,
            )
        }
        return db to PersonaRepository(db)
    }

    private fun json(body: String) = Json.parseToJsonElement(body).jsonObject

    // =========================================================================
    // GET /v1/admin/personas — returns list
    // =========================================================================

    @Test
    fun `GET personas returns empty list when no personas exist`() = testApplication {
        setup()
        val response = client.get("/v1/admin/personas") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        val personas = body["personas"]!!.jsonArray
        assertTrue(personas.isEmpty(), "Expected empty list; got $personas")
    }

    @Test
    fun `GET personas returns all created personas`() = testApplication {
        val (_, personaRepo) = setup()

        personaRepo.create("list-a", "Alpha", "female", "straight", 25, emptyMap())
        personaRepo.create("list-b", "Beta", "male", "straight", 30, emptyMap())

        val response = client.get("/v1/admin/personas") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        val personas = body["personas"]!!.jsonArray
        assertEquals(2, personas.size, "Expected 2 personas; got $personas")
    }

    @Test
    fun `GET personas includes id, slug, displayName for each entry`() = testApplication {
        val (_, personaRepo) = setup()
        personaRepo.create("get-slug", "Get Name", "female", "straight", 25, emptyMap())

        val response = client.get("/v1/admin/personas") {
            basicAuth(adminId.toString(), "x")
        }
        val body = json(response.bodyAsText())
        val persona = body["personas"]!!.jsonArray.single().jsonObject

        assertNotNull(persona["id"])
        assertEquals("get-slug", persona["slug"]!!.jsonPrimitive.content)
        assertEquals("Get Name", persona["displayName"]!!.jsonPrimitive.content)
    }

    @Test
    fun `GET personas requires admin — non-admin gets 403`() = testApplication {
        setup()
        val response = client.get("/v1/admin/personas") {
            basicAuth(nonAdminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // =========================================================================
    // POST /v1/admin/personas — creates persona, returns 201 with id
    // =========================================================================

    @Test
    fun `POST personas creates persona and returns 201 with id`() = testApplication {
        val (_, personaRepo) = setup()

        val response = client.post("/v1/admin/personas") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "slug": "new-persona",
                  "displayName": "New Persona",
                  "gender": "female",
                  "orientation": "straight",
                  "apparentAge": 24
                }
                """.trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = json(response.bodyAsText())
        val id = body["id"]!!.jsonPrimitive.content
        assertTrue(id.isNotBlank(), "Response must include non-blank id")

        // Verify persisted in DB
        val persisted = personaRepo.findById(UUID.fromString(id))
        assertNotNull(persisted, "Persona must be persisted in the database")
        assertEquals("new-persona", persisted!!.slug)
        assertEquals("New Persona", persisted.displayName)
    }

    @Test
    fun `POST personas returns 201 response with all submitted fields`() = testApplication {
        setup()
        val response = client.post("/v1/admin/personas") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"full-fields","displayName":"Full Fields","gender":"male","orientation":"queer","apparentAge":32}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = json(response.bodyAsText())
        assertEquals("full-fields", body["slug"]!!.jsonPrimitive.content)
        assertEquals("Full Fields", body["displayName"]!!.jsonPrimitive.content)
        assertEquals("male", body["gender"]!!.jsonPrimitive.content)
        assertEquals("queer", body["orientation"]!!.jsonPrimitive.content)
        assertEquals(32, body["apparentAge"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `POST personas requires admin — non-admin gets 403`() = testApplication {
        setup()
        val response = client.post("/v1/admin/personas") {
            basicAuth(nonAdminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"hacked","displayName":"Hacked","gender":"female","orientation":"straight","apparentAge":20}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // =========================================================================
    // GET /v1/admin/personas/{id}/core-versions — returns version list
    // =========================================================================

    @Test
    fun `GET core-versions returns empty list when no versions exist`() = testApplication {
        val (_, personaRepo) = setup()
        val persona = personaRepo.create("no-versions", "No Versions", "female", "straight", 25, emptyMap())

        val response = client.get("/v1/admin/personas/${persona.id}/core-versions") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        val versions = body["versions"]!!.jsonArray
        assertTrue(versions.isEmpty(), "No versions expected; got $versions")
    }

    @Test
    fun `GET core-versions returns created versions in list`() = testApplication {
        val (db, personaRepo) = setup()
        val persona = personaRepo.create("has-versions", "Has Versions", "female", "straight", 25, emptyMap())
        val versionRepo = PersonaCoreVersionRepository(db)
        versionRepo.create(personaId = persona.id, version = 1, content = "v1 content", changelogNote = "first", author = "test")
        versionRepo.create(personaId = persona.id, version = 2, content = "v2 content", changelogNote = "second", author = "test")

        val response = client.get("/v1/admin/personas/${persona.id}/core-versions") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        val versions = body["versions"]!!.jsonArray
        assertEquals(2, versions.size, "Expected 2 versions; got $versions")
    }

    @Test
    fun `GET core-versions version objects include required fields`() = testApplication {
        val (db, personaRepo) = setup()
        val persona = personaRepo.create("version-fields", "Version Fields", "female", "straight", 25, emptyMap())
        val versionRepo = PersonaCoreVersionRepository(db)
        versionRepo.create(personaId = persona.id, version = 1, content = "content here", changelogNote = "note", author = "author1")

        val response = client.get("/v1/admin/personas/${persona.id}/core-versions") {
            basicAuth(adminId.toString(), "x")
        }
        val body = json(response.bodyAsText())
        val version = body["versions"]!!.jsonArray.single().jsonObject

        assertNotNull(version["id"], "version must have id")
        assertNotNull(version["version"], "version must have version number")
        assertNotNull(version["status"], "version must have status")
        assertEquals("draft", version["status"]!!.jsonPrimitive.content)
        assertNotNull(version["content"], "version must include content")
    }

    @Test
    fun `GET core-versions returns empty list for unknown persona`() = testApplication {
        setup()
        val response = client.get("/v1/admin/personas/${UUID.randomUUID()}/core-versions") {
            basicAuth(adminId.toString(), "x")
        }
        // The route returns 200 with an empty list (no FK constraint check on personaId)
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val versions = body["versions"]?.jsonArray
        assertNotNull(versions)
        assertEquals(0, versions.size)
    }

    @Test
    fun `GET core-versions requires admin — non-admin gets 403`() = testApplication {
        val (_, personaRepo) = setup()
        val persona = personaRepo.create("guard-versions", "Guard", "female", "straight", 25, emptyMap())
        val response = client.get("/v1/admin/personas/${persona.id}/core-versions") {
            basicAuth(nonAdminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // =========================================================================
    // POST /v1/admin/personas/{id}/core-versions/{vId}/publish
    // POST /v1/admin/personas/{id}/core-versions/{vId}/activate
    // (Regression-guard via HTTP — full lifecycle covered by AdminPersonaPhase3ATest)
    // =========================================================================

    @Test
    fun `full lifecycle GET list then POST create then GET versions reflects new persona and version`() = testApplication {
        val (_, _) = setup()

        // Create persona via POST
        val createResp = client.post("/v1/admin/personas") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"lifecycle-full","displayName":"Lifecycle Full","gender":"female","orientation":"straight","apparentAge":28}""")
        }
        assertEquals(HttpStatusCode.Created, createResp.status)
        val personaId = json(createResp.bodyAsText())["id"]!!.jsonPrimitive.content

        // Persona appears in list
        val listResp = client.get("/v1/admin/personas") {
            basicAuth(adminId.toString(), "x")
        }
        val listBody = json(listResp.bodyAsText())
        val found = listBody["personas"]!!.jsonArray.any { p ->
            p.jsonObject["id"]?.jsonPrimitive?.content == personaId
        }
        assertTrue(found, "Newly created persona must appear in GET /v1/admin/personas list")

        // Create a version
        val versionResp = client.post("/v1/admin/personas/$personaId/core-versions") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"initial content","changelogNote":"first version"}""")
        }
        assertEquals(HttpStatusCode.Created, versionResp.status)

        // Version appears in GET core-versions
        val versionsResp = client.get("/v1/admin/personas/$personaId/core-versions") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.OK, versionsResp.status)
        val versionsBody = json(versionsResp.bodyAsText())
        assertEquals(1, versionsBody["versions"]!!.jsonArray.size)
    }
}
