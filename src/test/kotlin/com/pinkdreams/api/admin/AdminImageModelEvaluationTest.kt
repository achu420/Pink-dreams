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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdminImageModelEvaluationTest {
    private val adminId = UUID.fromString("00000000-0000-0000-0000-0000000000e2")

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

    private fun json(body: String) = Json.parseToJsonElement(body).jsonObject

    private fun seedPersona(db: Database): UUID {
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
            slug = "eval-${UUID.randomUUID().toString().take(8)}",
            displayName = "Eval Persona",
            gender = "female",
            orientation = "straight",
            apparentAge = 26,
            languageProfile = emptyMap(),
        )
        visualAdmin.ensureDraft(persona.id, author = "test")
        visualAdmin.publishAndActivateDraft(persona.id)
        return persona.id
    }

    @Test
    fun `unauthenticated evaluation create is rejected`() = testApplication {
        setup()
        val res = client.post("/v1/admin/images/evaluations") {
            contentType(ContentType.Application.Json)
            setBody("""{"personaId":"${UUID.randomUUID()}","seedPrompt":"x","modelIds":["a","b"]}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `two-model evaluation persists exact model ids and does not claim production change`() = testApplication {
        val db = setup()
        val personaId = seedPersona(db)
        val beforeProd = System.getenv("OPENROUTER_IMAGE_MODEL")

        val create = client.post("/v1/admin/images/evaluations") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "personaId":"$personaId",
                  "seedPrompt":"Swimming in a yellow bikini at a Goa beach, early morning, facing camera.",
                  "modelIds":["openai/gpt-image-2.5-flare","openai/eval-model-b"],
                  "candidateCount":2
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.Accepted, create.status)
        val body = json(create.bodyAsText())
        assertEquals(2, body["models"]!!.jsonArray.size)
        val modelIds = body["models"]!!.jsonArray.map {
            it.jsonObject["modelId"]!!.jsonPrimitive.content
        }
        assertTrue(modelIds.contains("openai/gpt-image-2.5-flare"))
        assertTrue(modelIds.contains("openai/eval-model-b"))
        assertTrue(body["productionModelUnchanged"]!!.jsonPrimitive.content.toBoolean())
        assertNotNull(body["evaluationId"])

        // Production env must not be mutated by evaluation create
        assertEquals(beforeProd, System.getenv("OPENROUTER_IMAGE_MODEL"))

        val get = client.get("/v1/admin/images/evaluations/${body["evaluationId"]!!.jsonPrimitive.content}") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.OK, get.status)
        val detail = json(get.bodyAsText())
        assertEquals(
            "Swimming in a yellow bikini at a Goa beach, early morning, facing camera.",
            detail["seedPrompt"]!!.jsonPrimitive.content,
        )
        assertFalse(detail["models"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `three-model evaluation with one force-fail still returns usable evaluation`() = testApplication {
        val db = setup()
        val personaId = seedPersona(db)

        val create = client.post("/v1/admin/images/evaluations") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "personaId":"$personaId",
                  "seedPrompt":"cafe window light",
                  "modelIds":["fake-model-a","force-fail-model","fake-model-c"],
                  "candidateCount":1
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.Accepted, create.status)
        val body = json(create.bodyAsText())
        assertEquals(3, body["models"]!!.jsonArray.size)
        // Jobs are submitted; force-fail manifests when worker runs. Model rows must all exist.
        val ids = body["models"]!!.jsonArray.map { it.jsonObject["modelId"]!!.jsonPrimitive.content }
        assertTrue(ids.contains("force-fail-model"))
    }

    @Test
    fun `evaluation notes persist dimensional ratings`() = testApplication {
        val db = setup()
        val personaId = seedPersona(db)
        val create = client.post("/v1/admin/images/evaluations") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody(
                """{"personaId":"$personaId","seedPrompt":"pose test","modelIds":["m-a","m-b"],"candidateCount":1}""",
            )
        }
        val body = json(create.bodyAsText())
        val evalId = body["evaluationId"]!!.jsonPrimitive.content
        val modelRowId = body["models"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        val notes = client.post("/v1/admin/images/evaluations/$evalId/notes") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody(
                """{"modelRowId":"$modelRowId","notes":"Good face. Weak hands.","identityConsistency":4,"sceneAdherence":3,"imageQuality":5}""",
            )
        }
        assertEquals(HttpStatusCode.OK, notes.status)
        val detail = client.get("/v1/admin/images/evaluations/$evalId") {
            basicAuth(adminId.toString(), "x")
        }
        val m0 = json(detail.bodyAsText())["models"]!!.jsonArray[0].jsonObject
        assertEquals("Good face. Weak hands.", m0["notes"]!!.jsonPrimitive.content)
        assertEquals("4", m0["identityConsistency"]!!.jsonPrimitive.content)
    }

    @Test
    fun `evaluation-models catalogue is admin-only`() = testApplication {
        setup()
        val denied = client.get("/v1/admin/images/evaluation-models")
        assertEquals(HttpStatusCode.Unauthorized, denied.status)
        val ok = client.get("/v1/admin/images/evaluation-models") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue(Json.parseToJsonElement(ok.bodyAsText()).jsonArray.isNotEmpty())
    }
}
