package com.pinkdreams.api.admin

import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.module
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationRepository
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Session-cookie admin auth gate (temporary static credentials — see
 * AdminSessionAuth). Verifies:
 *  - correct static credentials establish a session (cookie set, 200)
 *  - wrong credentials are rejected (401, no cookie)
 *  - an unauthenticated browser navigation to /admin redirects to the login page
 *  - an unauthenticated API call to an admin endpoint gets 401 (never a redirect)
 *  - the existing bearer/basic-auth mechanism still works for admin routes with
 *    no session cookie at all (test-harness compatibility — every other admin
 *    route test in this suite authenticates this way with no session cookie)
 *  - a valid session cookie alone (no Authorization header) is accepted
 */
class AdminAuthGateTest {
    private val adminId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")

    private class TestAdminAuthProvider(private val adminId: UUID) : com.pinkdreams.auth.AdminAuthorizationProvider() {
        override fun isAdmin(userId: String): Boolean = userId == adminId.toString()
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.setup() {
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
    }

    @Test
    fun `login with correct static credentials succeeds and sets a session cookie`() = testApplication {
        setup()
        val response = client.post("/v1/admin/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"achal","password":"Iamachal"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val setCookie = response.headers["Set-Cookie"]
        assertTrue(setCookie != null && setCookie.contains("admin_session="), "Expected an admin_session cookie to be set")
    }

    @Test
    fun `login with wrong credentials is rejected without a session cookie`() = testApplication {
        setup()
        val response = client.post("/v1/admin/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"achal","password":"wrong"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(null, response.headers["Set-Cookie"])
    }

    @Test
    fun `unauthenticated browser navigation to admin page redirects to login`() = testApplication {
        setup()
        val noRedirectClient = createClient { followRedirects = false }
        val response = noRedirectClient.get("/admin")
        assertEquals(HttpStatusCode.Found, response.status)
        assertTrue(response.headers["Location"]?.contains("admin-login.html") == true)
    }

    @Test
    fun `unauthenticated API call returns 401 not a redirect`() = testApplication {
        setup()
        val noRedirectClient = createClient { followRedirects = false }
        val response = noRedirectClient.get("/v1/admin/conversations")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `existing basic-auth mechanism still works for admin routes with no session cookie`() = testApplication {
        setup()
        val response = client.get("/v1/admin/conversations") { basicAuth(adminId.toString(), "x") }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `a valid session cookie alone grants access to the admin page`() = testApplication {
        setup()
        val loginResponse = client.post("/v1/admin/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"achal","password":"Iamachal"}""")
        }
        val setCookie = loginResponse.headers["Set-Cookie"]!!
        val cookiePair = setCookie.substringBefore(";")

        val noRedirectClient = createClient { followRedirects = false }
        val response = noRedirectClient.get("/admin") {
            header("Cookie", cookiePair)
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `logout clears the session so the admin page redirects again`() = testApplication {
        setup()
        val loginResponse = client.post("/v1/admin/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"achal","password":"Iamachal"}""")
        }
        val cookiePair = loginResponse.headers["Set-Cookie"]!!.substringBefore(";")

        client.post("/v1/admin/auth/logout") { header("Cookie", cookiePair) }

        val noRedirectClient = createClient { followRedirects = false }
        val response = noRedirectClient.get("/admin") { header("Cookie", cookiePair) }
        assertEquals(HttpStatusCode.Found, response.status)
    }
}
