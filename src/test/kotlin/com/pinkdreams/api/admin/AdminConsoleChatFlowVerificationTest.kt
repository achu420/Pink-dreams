package com.pinkdreams.api.admin

import com.pinkdreams.module
import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Practical, implementation-level verification of the Admin Console Phase 2B
 * Chat tab flow, in lieu of browser-level automated testing (no such framework
 * exists in this project — see the Phase 2B report). This exercises the exact
 * HTTP sequence admin-ui.html's JavaScript performs, in order, through the real
 * server (testApplication + real repositories, only the LLM client is fake):
 *
 *   1. Admin creates a test user + profile           (Setup tab)
 *   2. Admin lists users                              (Chat tab dropdown populate)
 *   3. Console looks up an existing user+persona conversation, authenticated
 *      AS THE SELECTED USER via Basic Auth            (auto-load check)
 *   4. None exists -> console creates one, as that same user
 *   5. Console sends a message, as that same user
 *   6. Console re-runs the same lookup                (simulating a later
 *      re-selection of the same user+persona) and must now find exactly the
 *      conversation just created, with the message inside it, in order.
 */
class AdminConsoleChatFlowVerificationTest {
    private val adminId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

    private class TestAdminAuthProvider(private val adminId: UUID) : com.pinkdreams.auth.AdminAuthorizationProvider() {
        override fun isAdmin(userId: String): Boolean = userId == adminId.toString()
    }

    @Test
    fun `full admin console chat flow from user creation through conversation auto-load`() = testApplication {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("flow-persona-${UUID.randomUUID()}", "Simran", "female", "straight", 26, emptyMap())
        val coreRepository = PersonaCoreVersionRepository(db)
        val core = coreRepository.create(persona.id, 1, "core content", "draft")
        val publishedCore = coreRepository.publishCoreVersion(core.id)
        personaRepository.activateCoreVersion(persona.id, publishedCore.id)

        val engineRepository = ConversationEngineRepository(db)
        val engine = engineRepository.create(1, "engine rules", "draft")
        val publishedEngine = engineRepository.publishEngine(engine.id)
        engineRepository.activateEngine(publishedEngine.id)

        val conversationRepo = ConversationRepository(db)

        application {
            module(
                databaseConfig = DatabaseConfig(jdbcUrl = "jdbc:h2:mem:test", username = "sa", password = ""),
                llmConfig = LlmConfig(apiKey = "test-key"),
                chatEngine = null,
                conversationRepository = conversationRepo,
                adminAuthProvider = TestAdminAuthProvider(adminId),
                database = db,
            )
        }

        // 1. Setup tab: admin creates a test user + profile.
        val createUserResponse = client.post("/v1/admin/users") {
            basicAuth(adminId.toString(), "password")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Test Tara","gender":"female","interest":"male","city":"Delhi","age":28}""")
        }
        assertEquals(HttpStatusCode.Created, createUserResponse.status)
        val testUserId = Json.parseToJsonElement(createUserResponse.bodyAsText()).jsonObject["userId"]!!.jsonPrimitive.content

        // 2. Chat tab: dropdown populate.
        val listUsersResponse = client.get("/v1/admin/users") {
            basicAuth(adminId.toString(), "password")
        }
        assertEquals(HttpStatusCode.OK, listUsersResponse.status)
        val users = Json.parseToJsonElement(listUsersResponse.bodyAsText()).jsonObject["users"]!!.jsonArray
        assertTrue(users.any { it.jsonObject["userId"]!!.jsonPrimitive.content == testUserId }, "Newly created user must appear in the dropdown-populating list")

        // 3. Selecting user + persona: console checks for an existing conversation,
        // authenticated AS THE SELECTED USER (not the admin), via the existing
        // DevAuth Basic-Auth convention.
        val testUserAuth = "Basic " + java.util.Base64.getEncoder().encodeToString("$testUserId:admin-console".toByteArray())
        val firstLookup = client.get("/v1/conversations?personaId=${persona.id}") {
            header("Authorization", testUserAuth)
        }
        assertEquals(HttpStatusCode.OK, firstLookup.status)
        val firstLookupConversations = Json.parseToJsonElement(firstLookup.bodyAsText()).jsonObject["conversations"]!!.jsonArray
        assertEquals(0, firstLookupConversations.size, "No conversation should exist yet for this fresh user+persona pair")

        // 4. None exists -> console creates one, as that same user, exactly once.
        val createConvResponse = client.post("/v1/conversations") {
            header("Authorization", testUserAuth)
            contentType(ContentType.Application.Json)
            setBody("""{"personaId":"${persona.id}"}""")
        }
        assertEquals(HttpStatusCode.Created, createConvResponse.status)
        val conversationId = Json.parseToJsonElement(createConvResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        // 5. Console sends a message, as that same user.
        val sendResponse = client.post("/v1/conversations/$conversationId/messages") {
            header("Authorization", testUserAuth)
            header("Idempotency-Key", UUID.randomUUID().toString())
            contentType(ContentType.Application.Json)
            setBody("""{"content":"Hello Simran"}""")
        }
        assertEquals(HttpStatusCode.OK, sendResponse.status)

        // 6. A later re-selection of the same user+persona must find exactly this
        // conversation (auto-load, not manual paste), and its history must be
        // retrievable in persisted order.
        val secondLookup = client.get("/v1/conversations?personaId=${persona.id}") {
            header("Authorization", testUserAuth)
        }
        val secondLookupConversations = Json.parseToJsonElement(secondLookup.bodyAsText()).jsonObject["conversations"]!!.jsonArray
        assertEquals(1, secondLookupConversations.size)
        assertEquals(conversationId, secondLookupConversations.single().jsonObject["id"]!!.jsonPrimitive.content)

        val historyResponse = client.get("/v1/conversations/$conversationId") {
            header("Authorization", testUserAuth)
        }
        assertEquals(HttpStatusCode.OK, historyResponse.status)
        val messages = Json.parseToJsonElement(historyResponse.bodyAsText()).jsonObject["messages"]!!.jsonArray
        assertEquals(2, messages.size, "user message + assistant reply")
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("Hello Simran", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("assistant", messages[1].jsonObject["role"]!!.jsonPrimitive.content)

        // Cross-user isolation: a different user must never see this conversation.
        val otherUserAuth = "Basic " + java.util.Base64.getEncoder().encodeToString("${UUID.randomUUID()}:admin-console".toByteArray())
        val otherLookup = client.get("/v1/conversations?personaId=${persona.id}") {
            header("Authorization", otherUserAuth)
        }
        val otherConversations = Json.parseToJsonElement(otherLookup.bodyAsText()).jsonObject["conversations"]!!.jsonArray
        assertEquals(0, otherConversations.size)
    }
}
