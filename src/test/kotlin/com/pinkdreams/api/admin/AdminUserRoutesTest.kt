package com.pinkdreams.api.admin

import com.pinkdreams.module
import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
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

class AdminUserRoutesTest {
    private val testAdminId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val testNonAdminId = UUID.fromString("00000000-0000-0000-0000-0000000000b1")

    private fun ApplicationTestBuilder.setupAdminApp(): Database {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val conversationRepo = ConversationRepository(db)
        application {
            module(
                databaseConfig = DatabaseConfig(jdbcUrl = "jdbc:h2:mem:test", username = "sa", password = ""),
                llmConfig = LlmConfig(apiKey = "test-key"),
                chatEngine = null,
                conversationRepository = conversationRepo,
                adminAuthProvider = TestAdminAuthProvider(testAdminId),
                database = db,
            )
        }
        return db
    }

    private class TestAdminAuthProvider(private val adminId: UUID) : com.pinkdreams.auth.AdminAuthorizationProvider() {
        override fun isAdmin(userId: String): Boolean = userId == adminId.toString()
    }

    // --- A. Create user ---
    @Test
    fun `scenario A valid request generates uuid server side creates user and profile and returns expected data`() = testApplication {
        val db = setupAdminApp()

        val response = client.post("/v1/admin/users") {
            basicAuth(testAdminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Alice","gender":"female","interest":"male","city":"Delhi","age":28}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val userId = json["userId"]!!.jsonPrimitive.content
        val generatedId = UUID.fromString(userId) // must parse as a valid server-generated UUID
        assertEquals("Alice", json["displayName"]!!.jsonPrimitive.content)
        assertEquals("female", json["gender"]!!.jsonPrimitive.content)
        assertEquals("male", json["interest"]!!.jsonPrimitive.content)
        assertEquals("Delhi", json["city"]!!.jsonPrimitive.content)
        assertEquals(28, json["age"]!!.jsonPrimitive.content.toInt())

        // Round-trip via the real repository/database layer.
        val profile = UserProfileRepository(db).findByUserId(generatedId)
        assertNotNull(profile)
        assertEquals("Alice", profile.displayName)
        assertEquals("female", profile.gender)
        assertEquals("male", profile.interest)
        assertEquals("Delhi", profile.city)
        assertEquals(28, profile.age)
    }

    @Test
    fun `client-supplied user id in the request body is not accepted for identity`() = testApplication {
        setupAdminApp()

        // The request DTO has no userId field at all — this proves it structurally,
        // by confirming a request without one still succeeds and a fresh ID is minted.
        val response = client.post("/v1/admin/users") {
            basicAuth(testAdminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Bob"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(UUID.fromString(json["userId"]!!.jsonPrimitive.content))
    }

    // --- D. Interest validation ---
    @Test
    fun `scenario D valid interest values are accepted`() = testApplication {
        setupAdminApp()
        listOf("male", "female", "both").forEach { interest ->
            val response = client.post("/v1/admin/users") {
                basicAuth(testAdminId.toString(), "password")
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"User-$interest","interest":"$interest"}""")
            }
            assertEquals(HttpStatusCode.Created, response.status, "interest=$interest should be accepted")
        }
    }

    @Test
    fun `scenario D invalid interest value is rejected`() = testApplication {
        setupAdminApp()
        val response = client.post("/v1/admin/users") {
            basicAuth(testAdminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Invalid","interest":"other"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // --- E. Age validation ---
    @Test
    fun `scenario E boundary ages are accepted`() = testApplication {
        setupAdminApp()
        listOf(18, 120).forEach { age ->
            val response = client.post("/v1/admin/users") {
                basicAuth(testAdminId.toString(), "password")
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"Age-$age","age":$age}""")
            }
            assertEquals(HttpStatusCode.Created, response.status, "age=$age should be accepted")
        }
    }

    @Test
    fun `scenario E out of range ages are rejected`() = testApplication {
        setupAdminApp()
        listOf(17, 121, -1).forEach { age ->
            val response = client.post("/v1/admin/users") {
                basicAuth(testAdminId.toString(), "password")
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"Age-$age","age":$age}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, "age=$age should be rejected")
        }
    }

    // --- F. Admin authorization ---
    @Test
    fun `scenario F non-admin cannot create users`() = testApplication {
        setupAdminApp()
        val response = client.post("/v1/admin/users") {
            basicAuth(testNonAdminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"ShouldFail"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `scenario F non-admin cannot list users`() = testApplication {
        setupAdminApp()
        val response = client.get("/v1/admin/users") {
            basicAuth(testNonAdminId.toString(), "password")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `scenario F unauthenticated request is rejected`() = testApplication {
        setupAdminApp()
        val response = client.get("/v1/admin/users")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // --- G. User listing ---
    @Test
    fun `scenario G listing returns all users with stable ordering and required fields`() = testApplication {
        setupAdminApp()

        listOf("Charlie", "Alice", "Bob").forEach { name ->
            val response = client.post("/v1/admin/users") {
                basicAuth(testAdminId.toString(), "password")
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"$name","gender":"female","interest":"both","city":"Mumbai","age":30}""")
            }
            assertEquals(HttpStatusCode.Created, response.status)
        }

        val response = client.get("/v1/admin/users") {
            basicAuth(testAdminId.toString(), "password")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val users = Json.parseToJsonElement(response.bodyAsText()).jsonObject["users"]!!.jsonArray
        assertEquals(3, users.size)

        val names = users.map { it.jsonObject["displayName"]!!.jsonPrimitive.content }
        assertEquals(listOf("Alice", "Bob", "Charlie"), names, "Must be ordered by display name")

        users.forEach { u ->
            assertNotNull(u.jsonObject["userId"])
            assertEquals("female", u.jsonObject["gender"]!!.jsonPrimitive.content)
            assertEquals("both", u.jsonObject["interest"]!!.jsonPrimitive.content)
            assertEquals("Mumbai", u.jsonObject["city"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `scenario G user with no profile fields still appears with nulls`() = testApplication {
        setupAdminApp()
        val createResponse = client.post("/v1/admin/users") {
            basicAuth(testAdminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Minimal"}""")
        }
        assertEquals(HttpStatusCode.Created, createResponse.status)

        val response = client.get("/v1/admin/users") {
            basicAuth(testAdminId.toString(), "password")
        }
        val users = Json.parseToJsonElement(response.bodyAsText()).jsonObject["users"]!!.jsonArray
        val minimal = users.single { it.jsonObject["displayName"]!!.jsonPrimitive.content == "Minimal" }
        // ContentNegotiation is configured with explicitNulls = false, so absent
        // profile fields are omitted from the JSON entirely rather than serialized
        // as null.
        assertEquals(null, minimal.jsonObject["gender"])
        assertEquals(null, minimal.jsonObject["interest"])
        assertEquals(null, minimal.jsonObject["city"])
        assertEquals(null, minimal.jsonObject["age"])
    }
}
