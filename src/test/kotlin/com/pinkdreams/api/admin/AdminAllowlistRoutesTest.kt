package com.pinkdreams.api.admin

import com.pinkdreams.auth.AdminAuthorizationProvider
import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.module
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.AdminAllowlistRepository
import io.ktor.client.request.basicAuth
import io.ktor.client.request.delete
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
import kotlin.test.assertTrue

/**
 * DB-backed admin allowlist: repository behavior, the union semantics in
 * AdminAuthorizationProvider (additive — env var unchanged), the route auth
 * gate, and the last-admin lockout guard.
 */
class AdminAllowlistRoutesTest {
    private val adminId = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
    private val nonAdminId = UUID.fromString("00000000-0000-0000-0000-0000000000e2")

    private class TestAdminAuthProvider(private val adminId: UUID) : AdminAuthorizationProvider() {
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

    @Test
    fun `repository adds lists and removes idempotently`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val repo = AdminAllowlistRepository(db)
        val target = UUID.randomUUID()

        assertFalse(repo.contains(target))
        repo.add(target, note = "ops", addedBy = "tester")
        repo.add(target, note = "ops again", addedBy = "tester") // idempotent
        assertEquals(1, repo.list().size)
        assertEquals(1L, repo.count())
        assertTrue(repo.contains(target))
        assertEquals(setOf(target), repo.listUserIds())

        assertTrue(repo.remove(target))
        assertFalse(repo.remove(target))
        assertFalse(repo.contains(target))
    }

    @Test
    fun `authorization provider without a repository keeps env-only behavior`() {
        // Every pre-existing call site constructs it exactly like this.
        val provider = AdminAuthorizationProvider()
        assertFalse(provider.isAdmin(UUID.randomUUID().toString()))
        assertFalse(provider.isAdmin("not-a-uuid"))
        assertFalse(provider.isAdmin(""))
    }

    @Test
    fun `authorization provider grants admin from the database allowlist`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val repo = AdminAllowlistRepository(db)
        val provider = AdminAuthorizationProvider(repo)
        val target = UUID.randomUUID()

        assertFalse(provider.isAdmin(target.toString()))
        repo.add(target)
        assertTrue(provider.isAdmin(target.toString()))
        // A non-UUID or empty principal is never admin, whatever the table holds.
        assertFalse(provider.isAdmin("not-a-uuid"))
        assertFalse(provider.isAdmin(""))
        assertFalse(provider.isAdmin("*"))
    }

    @Test
    fun `allowlist routes reject unauthenticated and non-admin callers`() = testApplication {
        setup()
        val someId = UUID.randomUUID()

        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/allowlist").status)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/v1/admin/allowlist") { basicAuth(nonAdminId.toString(), "x") }.status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            client.delete("/v1/admin/allowlist/$someId") { basicAuth(nonAdminId.toString(), "x") }.status,
        )
        val postForbidden = client.post("/v1/admin/allowlist") {
            basicAuth(nonAdminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"$someId"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, postForbidden.status)
    }

    @Test
    fun `add list and remove over http`() = testApplication {
        val db = setup()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()

        listOf(first, second).forEach { id ->
            val created = client.post("/v1/admin/allowlist") {
                basicAuth(adminId.toString(), "x")
                contentType(ContentType.Application.Json)
                setBody("""{"userId":"$id","note":"ops"}""")
            }
            assertEquals(HttpStatusCode.Created, created.status)
        }

        val listed = client.get("/v1/admin/allowlist") { basicAuth(adminId.toString(), "x") }
        assertEquals(HttpStatusCode.OK, listed.status)
        val body = json(listed.bodyAsText())
        assertEquals(2, body["entries"]!!.jsonArray.size)
        // Env ids themselves are never exposed — only a count.
        assertTrue(body.containsKey("envAllowlistCount"))

        val removed = client.delete("/v1/admin/allowlist/$second") { basicAuth(adminId.toString(), "x") }
        assertEquals(HttpStatusCode.NoContent, removed.status)
        assertFalse(AdminAllowlistRepository(db).contains(second))
    }

    @Test
    fun `malformed user id is rejected on add and delete`() = testApplication {
        setup()
        val bad = client.post("/v1/admin/allowlist") {
            basicAuth(adminId.toString(), "x")
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"   "}""")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)

        assertEquals(
            HttpStatusCode.BadRequest,
            client.delete("/v1/admin/allowlist/not-a-uuid") { basicAuth(adminId.toString(), "x") }.status,
        )
    }

    @Test
    fun `refuses to remove the last admin when the env var is empty`() = testApplication {
        val db = setup()
        val only = UUID.randomUUID()
        AdminAllowlistRepository(db).add(only)

        // The test JVM does not set ADMIN_USER_IDS, so the env set is empty and
        // the guard must engage on the final remaining row.
        val refused = client.delete("/v1/admin/allowlist/$only") { basicAuth(adminId.toString(), "x") }
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertTrue(AdminAllowlistRepository(db).contains(only))

        // With a second admin present, removal is allowed again.
        val second = UUID.randomUUID()
        AdminAllowlistRepository(db).add(second)
        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/v1/admin/allowlist/$only") { basicAuth(adminId.toString(), "x") }.status,
        )
    }

    @Test
    fun `removing an entry that does not exist is a 404`() = testApplication {
        val db = setup()
        // Keep two rows so the lockout guard is not what answers this request.
        AdminAllowlistRepository(db).add(UUID.randomUUID())
        AdminAllowlistRepository(db).add(UUID.randomUUID())

        val missing = client.delete("/v1/admin/allowlist/${UUID.randomUUID()}") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }
}
