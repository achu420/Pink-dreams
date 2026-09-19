package com.pinkdreams

import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.util.encodeBase64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Proves that Application.module() — the ACTUAL production wiring used by both
 * normal chat clients and the /admin console (same POST /v1/conversations/{id}/messages
 * route) — constructs the real production pipeline with the configured model, not a
 * disconnected "default" fallback.
 *
 * This exercises the real HTTP route end-to-end (no chatEngine/contextAssembler override),
 * unlike the unit-level ChatContextContractRegressionTest which constructs components directly.
 */
class ApplicationWiringRegressionTest {
    @Test
    fun `module wiring passes configured model into GenerationConfig instead of leaving it null`() = testApplication {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userId = UUID.randomUUID()

        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("wiring-persona-${UUID.randomUUID()}", "WiringPersona", "female", "straight", 30, emptyMap())
        val coreRepository = PersonaCoreVersionRepository(db)
        val core = coreRepository.create(persona.id, 1, "wiring core content", "draft")
        val publishedCore = coreRepository.publishCoreVersion(core.id)
        personaRepository.activateCoreVersion(persona.id, publishedCore.id)

        val engineRepository = ConversationEngineRepository(db)
        val engine = engineRepository.create(1, "wiring engine content", "draft")
        val publishedEngine = engineRepository.publishEngine(engine.id)
        engineRepository.activateEngine(publishedEngine.id)

        val conversationRepository = ConversationRepository(db)
        val conversation = conversationRepository.create(userId, persona.id)

        val configuredModel = "WIRING_TEST_MODEL_123"
        val testLlmConfig = LlmConfig(apiKey = "test-key-no-openrouter-call", model = configuredModel)
        val testDatabaseConfig = DatabaseConfig(jdbcUrl = "jdbc:h2:mem:unused", username = "sa", password = "")

        // Boot the REAL Application.module() wiring: no chatEngine override, no contextAssembler
        // override — this is exactly what both /v1/conversations/{id}/messages (normal chat)
        // and /admin's chat panel invoke.
        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, database = db)
        }

        val idempotencyKey = UUID.randomUUID().toString()
        val authHeader = "Basic ${"${userId}:password".encodeBase64()}"
        val response = client.post("/v1/conversations/${conversation.id}/messages") {
            contentType(ContentType.Application.Json)
            header("Authorization", authHeader)
            header("Idempotency-Key", idempotencyKey)
            setBody("""{"content": "wiring check message"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        // Inspect the ACTUAL persisted diagnostics — the same data source the admin debug panel reads.
        val messageRepository = MessageRepository(db)
        val assistantMessage = messageRepository.findForConversation(conversation.id).single { it.role == "assistant" }

        val metadata = Json.parseToJsonElement(assistantMessage.metadata).jsonObject
        val lvmConfigRaw = metadata["lvm_config"]?.jsonPrimitive?.content
            ?: error("lvm_config missing from persisted diagnostics metadata")
        val lvmConfig = Json.parseToJsonElement(lvmConfigRaw).jsonObject
        val capturedModel = lvmConfig["model"]?.jsonPrimitive?.content

        assertNotEquals("default", capturedModel, "Diagnostics must not fall back to the literal 'default' when LlmConfig has a real configured model")
        assertEquals(configuredModel, capturedModel, "Diagnostics must reflect the actual configured LlmConfig.model")
    }
}
