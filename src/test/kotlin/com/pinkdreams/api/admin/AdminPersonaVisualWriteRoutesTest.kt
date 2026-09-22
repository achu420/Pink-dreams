package com.pinkdreams.api.admin

import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.module
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.visual.identity.PhysicalGuide
import com.pinkdreams.visual.identity.AgePresentation
import com.pinkdreams.visual.identity.Body
import com.pinkdreams.visual.identity.Hair
import com.pinkdreams.visual.identity.PrivateVisualGuide
import com.pinkdreams.visual.identity.ReferenceRole
import io.ktor.client.request.basicAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task 24 — Admin visual identity write path: ensure, guides, references, publish.
 */
class AdminPersonaVisualWriteRoutesTest {
    private val adminId = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val nonAdminId = UUID.fromString("00000000-0000-0000-0000-0000000000d2")

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

    private fun seedPersona(db: Database): UUID = PersonaRepository(db).create(
        slug = "visual-write-${UUID.randomUUID()}",
        displayName = "Write Persona",
        gender = "female",
        orientation = "straight",
        apparentAge = 26,
        languageProfile = emptyMap(),
    ).id

    private fun tinyPng(): ByteArray {
        // Minimal 1x1 PNG
        return byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53,
            0xDE.toByte(), 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
            0x54, 0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(), 0xCF.toByte(), 0xC0.toByte(), 0x00,
            0x00, 0x00, 0x03, 0x00, 0x01, 0x00, 0x05, 0xFE.toByte(),
            0xD4.toByte(), 0xEF.toByte(), 0x00, 0x00, 0x00, 0x00, 0x49, 0x45,
            0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
    }

    @Test
    fun `ensure save publish and reference workflow`() = testApplication {
        val db = setup()
        val personaId = seedPersona(db)

        val ensure = client.post("/v1/admin/personas/$personaId/visual/ensure") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.OK, ensure.status)
        val ensureBody = json(ensure.bodyAsText())
        assertNotNull(ensureBody["draftVersionId"])

        val guide = PhysicalGuide(
            agePresentation = AgePresentation(apparentAge = 27, adult = true),
            body = Body(height = "168 cm", weight = "56 kg", build = "slim", muscularity = "low"),
            hair = Hair(color = "dark brown", length = "long", style = "wavy"),
            distinctiveFeatures = listOf("small mole on left cheek"),
        )
        val putGuide = client.put("/v1/admin/personas/$personaId/visual/physical-guide") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(PhysicalGuide.serializer(), guide))
        }
        assertEquals(HttpStatusCode.OK, putGuide.status)
        assertTrue(putGuide.bodyAsText().contains("168 cm"))

        val putPrivate = client.put("/v1/admin/personas/$personaId/visual/private-guide") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody(
                Json.encodeToString(
                    PrivateVisualGuide.serializer(),
                    PrivateVisualGuide(breastDescription = "medium", otherPrivateNotes = "admin only"),
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, putPrivate.status)

        val upload = client.submitFormWithBinaryData(
            url = "/v1/admin/personas/$personaId/visual/references",
            formData = formData {
                append("role", ReferenceRole.FRONT.name)
                append("finalize", "true")
                append(
                    "file",
                    tinyPng(),
                    Headers.build {
                        append(HttpHeaders.ContentType, "image/png")
                        append(HttpHeaders.ContentDisposition, "filename=\"front.png\"")
                    },
                )
            },
        ) {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.OK, upload.status)
        val uploadBody = json(upload.bodyAsText())
        assertEquals("FRONT", uploadBody["role"]!!.jsonPrimitive.content)
        assertEquals("FINALIZED", uploadBody["status"]!!.jsonPrimitive.content)
        val refId = uploadBody["id"]!!.jsonPrimitive.content

        val content = client.get("/v1/admin/personas/$personaId/visual/references/$refId/content") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.OK, content.status)
        assertTrue(content.readBytes().isNotEmpty())

        val publish = client.post("/v1/admin/personas/$personaId/visual/publish-activate") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.OK, publish.status)
        assertEquals("published", json(publish.bodyAsText())["status"]!!.jsonPrimitive.content)

        val getVisual = client.get("/v1/admin/personas/$personaId/visual") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.OK, getVisual.status)
        val visual = json(getVisual.bodyAsText())
        assertTrue(visual["hasIdentity"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(getVisual.bodyAsText().contains("privateGuide"))
        assertTrue(getVisual.bodyAsText().contains("medium"))

        // Cross-persona content must 404 when reference id is used with wrong persona
        val other = seedPersona(db)
        client.post("/v1/admin/personas/$other/visual/ensure") { basicAuth(adminId.toString(), "x") }
        val cross = client.get("/v1/admin/personas/$other/visual/references/$refId/content") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.NotFound, cross.status)
    }

    @Test
    fun `non-admin cannot modify visual identity`() = testApplication {
        val db = setup()
        val personaId = seedPersona(db)

        val ensure = client.post("/v1/admin/personas/$personaId/visual/ensure") {
            basicAuth(nonAdminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.Forbidden, ensure.status)

        val put = client.put("/v1/admin/personas/$personaId/visual/physical-guide") {
            basicAuth(nonAdminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody(
                Json.encodeToString(
                    PhysicalGuide.serializer(),
                    PhysicalGuide(agePresentation = AgePresentation(25, true)),
                ),
            )
        }
        assertEquals(HttpStatusCode.Forbidden, put.status)
    }

    @Test
    fun `delete reference from draft`() = testApplication {
        val db = setup()
        val personaId = seedPersona(db)
        client.post("/v1/admin/personas/$personaId/visual/ensure") { basicAuth(adminId.toString(), "x") }

        val upload = client.submitFormWithBinaryData(
            url = "/v1/admin/personas/$personaId/visual/references",
            formData = formData {
                append("role", "FACE_CLOSE")
                append("finalize", "true")
                append(
                    "file",
                    tinyPng(),
                    Headers.build {
                        append(HttpHeaders.ContentType, "image/png")
                        append(HttpHeaders.ContentDisposition, "filename=\"face.png\"")
                    },
                )
            },
        ) { basicAuth(adminId.toString(), "x") }
        val refId = json(upload.bodyAsText())["id"]!!.jsonPrimitive.content

        val del = client.delete("/v1/admin/personas/$personaId/visual/references/$refId") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.NoContent, del.status)

        val missing = client.get("/v1/admin/personas/$personaId/visual/references/$refId/content") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }
}
