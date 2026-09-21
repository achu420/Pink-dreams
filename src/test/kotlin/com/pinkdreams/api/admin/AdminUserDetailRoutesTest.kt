package com.pinkdreams.api.admin

import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.module
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.UserRepository
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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

/**
 * User Detail page backend: single-user read, this user's conversations, and
 * this user's memory facts ACROSS every persona. All read-only.
 */
class AdminUserDetailRoutesTest {
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
                conversationRepository = ConversationRepository(db),
                adminAuthProvider = TestAdminAuthProvider(adminId),
                database = db,
            )
        }
        return db
    }

    private fun json(body: String) = Json.parseToJsonElement(body).jsonObject

    private fun seedPersona(db: Database, name: String) = PersonaRepository(db).create(
        slug = "ud-persona-${UUID.randomUUID()}",
        displayName = name,
        gender = "female",
        orientation = "straight",
        apparentAge = 24,
        languageProfile = emptyMap(),
    ).id

    @Test
    fun `single user endpoint returns profile and 404 for unknown user`() = testApplication {
        val db = setup()
        val userId = UUID.randomUUID()
        UserRepository(db).create(userId)

        val ok = client.get("/v1/admin/users/$userId") { basicAuth(adminId.toString(), "x") }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(userId.toString(), json(ok.bodyAsText())["userId"]!!.jsonPrimitive.content)

        val missing = client.get("/v1/admin/users/${UUID.randomUUID()}") { basicAuth(adminId.toString(), "x") }
        assertEquals(HttpStatusCode.NotFound, missing.status)

        val malformed = client.get("/v1/admin/users/not-a-uuid") { basicAuth(adminId.toString(), "x") }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
    }

    @Test
    fun `user detail endpoints reject non-admin and unauthenticated callers`() = testApplication {
        val db = setup()
        val userId = UUID.randomUUID()
        UserRepository(db).create(userId)

        listOf("", "/conversations", "/memory").forEach { suffix ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/users/$userId$suffix").status)
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/v1/admin/users/$userId$suffix") { basicAuth(nonAdminId.toString(), "x") }.status,
            )
        }
    }

    @Test
    fun `conversations endpoint returns only this users conversations`() = testApplication {
        val db = setup()
        val conversationRepo = ConversationRepository(db)
        val personaId = seedPersona(db, "Ada")
        val userA = UUID.randomUUID().also { UserRepository(db).create(it) }
        val userB = UUID.randomUUID().also { UserRepository(db).create(it) }
        conversationRepo.create(userA, personaId)
        conversationRepo.create(userA, personaId)
        conversationRepo.create(userB, personaId)

        val res = client.get("/v1/admin/users/$userA/conversations") { basicAuth(adminId.toString(), "x") }
        assertEquals(HttpStatusCode.OK, res.status)
        val rows = json(res.bodyAsText())["conversations"]!!.jsonArray
        assertEquals(2, rows.size)
        assertEquals("Ada", rows[0].jsonObject["personaDisplayName"]!!.jsonPrimitive.content)
    }

    @Test
    fun `memory endpoint returns facts across every persona for this user only`() = testApplication {
        val db = setup()
        val memoryRepo = MemoryFactRepository(db)
        val personaOne = seedPersona(db, "Ada")
        val personaTwo = seedPersona(db, "Bea")
        val userA = UUID.randomUUID().also { UserRepository(db).create(it) }
        val userB = UUID.randomUUID().also { UserRepository(db).create(it) }
        memoryRepo.create(userA, personaOne, fact = "Likes hiking", factType = "preference", criticality = "low")
        memoryRepo.create(userA, personaTwo, fact = "Has a cat", factType = "fact", criticality = "medium")
        memoryRepo.create(userB, personaOne, fact = "Other user fact", factType = "fact", criticality = "low")

        val res = client.get("/v1/admin/users/$userA/memory") { basicAuth(adminId.toString(), "x") }
        assertEquals(HttpStatusCode.OK, res.status)
        val facts = json(res.bodyAsText())["memoryFacts"]!!.jsonArray
        assertEquals(2, facts.size)
        val texts = facts.map { it.jsonObject["fact"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("Likes hiking", "Has a cat"), texts)
        val personaNames = facts.map { it.jsonObject["personaDisplayName"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("Ada", "Bea"), personaNames)
    }

    @Test
    fun `memory repository findAllForUser spans relationships and excludes other users`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val repo = MemoryFactRepository(db)
        val personaOne = seedPersona(db, "Ada")
        val personaTwo = seedPersona(db, "Bea")
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        repo.create(userA, personaOne, fact = "A1", factType = "fact", criticality = "low")
        repo.create(userA, personaTwo, fact = "A2", factType = "fact", criticality = "low")
        repo.create(userB, personaOne, fact = "B1", factType = "fact", criticality = "low")

        val all = repo.findAllForUser(userA)
        assertEquals(2, all.size)
        assertTrue(all.all { it.userId == userA })
        // Existing relationship-scoped query is unchanged.
        assertEquals(1, repo.findForRelationship(userA, personaOne).size)
    }
}
