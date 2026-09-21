package com.pinkdreams.api.admin

import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.module
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.AdminStatsRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.UserRepository
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
import kotlin.test.assertTrue

/**
 * Module 05 — Dashboard activity counters. Covers the auth gate (401 for no
 * principal, 403 for an authenticated non-admin) and that the counts returned
 * are the REAL row counts from the database, not constants.
 */
class AdminStatsRoutesTest {
    private val testAdminId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val testNonAdminId = UUID.fromString("00000000-0000-0000-0000-0000000000c2")

    private class TestAdminAuthProvider(private val adminId: UUID) : com.pinkdreams.auth.AdminAuthorizationProvider() {
        override fun isAdmin(userId: String): Boolean = userId == adminId.toString()
    }

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

    @Test
    fun `unauthenticated request is rejected`() = testApplication {
        setupAdminApp()
        val response = client.get("/v1/admin/stats/activity")
        assertTrue(
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden,
            "expected the stats endpoint to refuse an unauthenticated caller, got ${response.status}",
        )
    }

    @Test
    fun `authenticated non-admin is forbidden`() = testApplication {
        setupAdminApp()
        val response = client.get("/v1/admin/stats/activity") {
            basicAuth(testNonAdminId.toString(), "password")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `admin gets real counts reflecting actual rows`() = testApplication {
        val db = setupAdminApp()

        // Empty database first — every counter must be a genuine zero.
        val empty = Json.parseToJsonElement(
            client.get("/v1/admin/stats/activity") { basicAuth(testAdminId.toString(), "password") }.bodyAsText(),
        ).jsonObject
        assertEquals(0, empty["totalConversations"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, empty["totalMessages"]!!.jsonPrimitive.content.toInt())

        // Now insert real rows through the real repositories.
        val userRepo = UserRepository(db)
        val personaRepo = PersonaRepository(db)
        val conversationRepo = ConversationRepository(db)
        val messageRepo = MessageRepository(db)

        val userId = UUID.randomUUID()
        userRepo.create(userId)
        val persona = personaRepo.create(
            slug = "stats-test-persona",
            displayName = "Stats Tester",
            gender = "female",
            orientation = "straight",
            apparentAge = 25,
            languageProfile = emptyMap(),
        )
        val conversation = conversationRepo.create(userId = userId, personaId = persona.id)
        messageRepo.createUserMessage(
            conversationId = conversation.id,
            content = "hello",
            clientMessageId = UUID.randomUUID(),
            requestId = UUID.randomUUID(),
        )

        val response = client.get("/v1/admin/stats/activity") {
            basicAuth(testAdminId.toString(), "password")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals(1, json["totalConversations"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, json["productionConversations"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, json["testConversations"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, json["conversationsLast24h"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, json["totalMessages"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, json["messagesLast24h"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, json["userMessagesLast24h"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `additional dashboard KPIs report real counts`() = testApplication {
        val db = setupAdminApp()
        val userRepo = UserRepository(db)
        val personaRepo = PersonaRepository(db)
        val conversationRepo = ConversationRepository(db)

        val userId = UUID.randomUUID()
        userRepo.create(userId)
        val busy = personaRepo.create(
            slug = "kpi-busy-persona",
            displayName = "Busy",
            gender = "female",
            orientation = "straight",
            apparentAge = 25,
            languageProfile = emptyMap(),
        )
        val quiet = personaRepo.create(
            slug = "kpi-quiet-persona",
            displayName = "Quiet",
            gender = "female",
            orientation = "straight",
            apparentAge = 25,
            languageProfile = emptyMap(),
        )
        conversationRepo.create(userId = userId, personaId = busy.id)
        conversationRepo.create(userId = userId, personaId = busy.id)
        conversationRepo.create(userId = userId, personaId = quiet.id)

        val memoryRepo = com.pinkdreams.persistence.repositories.MemoryFactRepository(db)
        memoryRepo.create(userId = userId, personaId = busy.id, fact = "likes trekking", factType = "preference", criticality = "medium")
        memoryRepo.create(
            userId = userId,
            personaId = busy.id,
            fact = "moved cities",
            factType = "event",
            criticality = "low",
            status = "resolved",
        )

        val skillRepo = com.pinkdreams.persistence.repositories.SkillRepository(db)
        val draft = skillRepo.create(key = "kpi-skill", version = 1, content = "{}")
        skillRepo.publish(draft.id)
        skillRepo.activate(draft.id)
        skillRepo.create(key = "kpi-skill-2", version = 1, content = "{}")

        val json = Json.parseToJsonElement(
            client.get("/v1/admin/stats/activity") { basicAuth(testAdminId.toString(), "password") }.bodyAsText(),
        ).jsonObject

        assertEquals(2, json["totalMemoryFacts"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, json["openMemoryFacts"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, json["skillsInProduction"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, json["skillsInDraft"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, json["skillsInTesting"]!!.jsonPrimitive.content.toInt())
        assertEquals(busy.id.toString(), json["topPersonaId"]!!.jsonPrimitive.content)
        assertEquals(2, json["topPersonaConversations"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `per-user and per-persona activity endpoints return real grouped counts`() = testApplication {
        val db = setupAdminApp()
        val userRepo = UserRepository(db)
        val personaRepo = PersonaRepository(db)
        val conversationRepo = ConversationRepository(db)

        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        userRepo.create(userA)
        userRepo.create(userB)
        val persona = personaRepo.create(
            slug = "activity-persona",
            displayName = "Activity",
            gender = "female",
            orientation = "straight",
            apparentAge = 25,
            languageProfile = emptyMap(),
        )
        conversationRepo.create(userId = userA, personaId = persona.id)
        conversationRepo.create(userId = userA, personaId = persona.id)
        conversationRepo.create(userId = userB, personaId = persona.id)

        val users = Json.parseToJsonElement(
            client.get("/v1/admin/stats/users") { basicAuth(testAdminId.toString(), "password") }.bodyAsText(),
        ).jsonObject["users"]!!.jsonArray
        val rowA = users.single { it.jsonObject["userId"]!!.jsonPrimitive.content == userA.toString() }
        assertEquals(2, rowA.jsonObject["conversations"]!!.jsonPrimitive.content.toInt())
        val rowB = users.single { it.jsonObject["userId"]!!.jsonPrimitive.content == userB.toString() }
        assertEquals(1, rowB.jsonObject["conversations"]!!.jsonPrimitive.content.toInt())

        val personas = Json.parseToJsonElement(
            client.get("/v1/admin/stats/personas") { basicAuth(testAdminId.toString(), "password") }.bodyAsText(),
        ).jsonObject["personas"]!!.jsonArray
        assertEquals(1, personas.size)
        assertEquals(3, personas[0].jsonObject["conversations"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `new stats endpoints enforce the same auth gate`() = testApplication {
        setupAdminApp()
        listOf("/v1/admin/stats/users", "/v1/admin/stats/personas").forEach { path ->
            val anon = client.get(path)
            assertTrue(
                anon.status == HttpStatusCode.Unauthorized || anon.status == HttpStatusCode.Forbidden,
                "$path must refuse an unauthenticated caller, got ${anon.status}",
            )
            val nonAdmin = client.get(path) { basicAuth(testNonAdminId.toString(), "password") }
            assertEquals(HttpStatusCode.Forbidden, nonAdmin.status, "$path must 403 an authenticated non-admin")
        }
    }

    @Test
    fun `24h window excludes older rows`() = testApplication {
        val db = setupAdminApp()
        val userRepo = UserRepository(db)
        val personaRepo = PersonaRepository(db)
        val conversationRepo = ConversationRepository(db)

        val userId = UUID.randomUUID()
        userRepo.create(userId)
        val persona = personaRepo.create(
            slug = "stats-window-persona",
            displayName = "Window Tester",
            gender = "female",
            orientation = "straight",
            apparentAge = 25,
            languageProfile = emptyMap(),
        )
        conversationRepo.create(userId = userId, personaId = persona.id)

        // Move the window's anchor two days into the future: the row just
        // created now falls OUTSIDE the trailing 24h window, proving the
        // range predicate is really applied rather than counting everything.
        val stats = AdminStatsRepository(db)
        val future = java.time.LocalDateTime.now().plusDays(2)
        val counts = stats.activityCounts(now = future)
        assertEquals(1, counts.totalConversations.toInt())
        assertEquals(0, counts.conversationsLast24h.toInt())
    }
}
