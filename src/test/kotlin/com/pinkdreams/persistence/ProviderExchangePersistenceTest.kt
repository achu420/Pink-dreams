package com.pinkdreams.persistence

import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.GenerationResponse
import com.pinkdreams.chat.LlmExecutionDiagnostics
import com.pinkdreams.chat.StageResult
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ChatRequestExecutionRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Proves the captured provider exchange (HTTP boundary diagnostics) is persisted
 * into Message.metadata via the existing lvm_* debug mechanism, and that secrets
 * never reach the persisted record — using the real RepositoryChatPersistence path,
 * not a reconstruction.
 */
class ProviderExchangePersistenceTest {
    @Test
    fun `persisted assistant message metadata contains the captured provider exchange without secrets`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userId = UUID.randomUUID()
        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("provider-exchange-persona-${UUID.randomUUID()}", "Test", "female", "straight", 30, emptyMap())
        val coreRepository = PersonaCoreVersionRepository(db)
        val core = coreRepository.create(persona.id, 1, "core", "draft")
        val publishedCore = coreRepository.publishCoreVersion(core.id)
        personaRepository.activateCoreVersion(persona.id, publishedCore.id)

        val conversation = ConversationRepository(db).create(userId, persona.id)
        val executions = ChatRequestExecutionRepository(db)
        val messages = MessageRepository(db)
        val persistence = RepositoryChatPersistence(db, executions)

        val request = ChatRequest(
            requestId = UUID.randomUUID(),
            userId = userId,
            conversationId = conversation.id,
            personaId = persona.id,
            clientMessageId = UUID.randomUUID(),
            content = "hello",
        )
        executions.claim(request.conversationId, request.clientMessageId, request.requestId)

        val response = GenerationResponse(
            content = "assistant reply",
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = publishedCore.id,
            executionDiagnostics = LlmExecutionDiagnostics(
                contextBlocks = null,
                generationConfig = mapOf("model" to "deepseek/deepseek-v4.1-flash"),
                llmResponseMetadata = mapOf("total_tokens" to "42"),
                providerExchange = mapOf(
                    "httpMethod" to "POST",
                    "endpoint" to "https://openrouter.ai/api/v1/chat/completions",
                    "model" to "deepseek/deepseek-v4.1-flash",
                    "requestHeaders" to "Content-Type: application/json; Authorization: [REDACTED]",
                    "requestBody" to """{"model":"deepseek/deepseek-v4.1-flash","messages":[{"role":"user","content":"hello"}],"max_tokens":1024,"stream":false}""",
                    "responseStatusCode" to "200",
                    "responseBody" to """{"id":"req-persist-1","model":"deepseek/deepseek-v4.1-flash","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"assistant reply"}}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""",
                    "providerRequestId" to "req-persist-1",
                    "isError" to "false",
                ),
            ),
        )

        val result = persistence.persist(request, response)
        assertIs<StageResult.Succeeded<*>>(result)

        val assistantMessage = messages.findForConversation(conversation.id).single { it.role == "assistant" }
        val metadata = Json.parseToJsonElement(assistantMessage.metadata).jsonObject
        val providerExchangeRaw = metadata["lvm_provider_exchange"]?.jsonPrimitive?.content
        kotlin.test.assertNotNull(providerExchangeRaw, "lvm_provider_exchange must be present in persisted metadata")

        val providerExchange = Json.parseToJsonElement(providerExchangeRaw).jsonObject
        assertEquals("POST", providerExchange["httpMethod"]?.jsonPrimitive?.content)
        assertEquals("200", providerExchange["responseStatusCode"]?.jsonPrimitive?.content)
        assertEquals("req-persist-1", providerExchange["providerRequestId"]?.jsonPrimitive?.content)
        assertTrue(providerExchange["requestBody"]!!.jsonPrimitive.content.contains("hello"))
        assertTrue(providerExchange["responseBody"]!!.jsonPrimitive.content.contains("assistant reply"))

        // The full persisted metadata blob must never contain a raw Authorization/secret value.
        assertFalse(assistantMessage.metadata.contains("Bearer "))
        assertEquals("Content-Type: application/json; Authorization: [REDACTED]", providerExchange["requestHeaders"]?.jsonPrimitive?.content)
    }
}
