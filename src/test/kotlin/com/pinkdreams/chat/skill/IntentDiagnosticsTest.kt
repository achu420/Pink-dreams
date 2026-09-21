package com.pinkdreams.chat.skill

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.llm.GenerationRequest
import com.pinkdreams.llm.LlmClient
import com.pinkdreams.llm.LlmResponse
import com.pinkdreams.llm.OpenRouterBudgetExhaustionException
import com.pinkdreams.persistence.repositories.IntentEngineRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Live Intent Model Comparison phase: the minimal diagnostics added to
 * [LlmIntentDiscovery] (an INTENT_DIAGNOSTICS log line, and a structured
 * [OpenRouterBudgetExhaustionException] instead of a generic one) must not
 * change routing behavior — every outcome must still resolve exactly as it
 * did before this instrumentation existed.
 */
class IntentDiagnosticsTest {

    private fun request() = ChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "hello")

    private fun context() = ChatContext(
        blocks = listOf(ContextBlock("system", "engine"), ContextBlock("user", "hello")),
        engineVersionId = UUID.randomUUID(), personaCoreVersionId = UUID.randomUUID(),
    )

    @Test
    fun `a successful classification still resolves normally with diagnostics logged`() {
        val skills = com.pinkdreams.persistence.repositories.SkillRepository(
            com.pinkdreams.persistence.database.DatabaseFactory.connectInMemory().also { com.pinkdreams.persistence.database.DatabaseFactory.initializeSchema(it) },
        )
        skills.activate(skills.publish(skills.createNextVersion("general_chat", "SKILL: GENERAL_CHAT").id).id)
        val client = LlmClient { LlmResponse(content = """{"skillKey": "general_chat"}""", provider = "test", metadata = mapOf("finish_reason" to "stop", "reasoning_tokens" to "12")) }
        val discovery = LlmIntentDiscovery(client, skills, intentEngineRepository = null)

        val result = discovery.selectSkill(request(), context())

        assertEquals(SkillSelection.Selected("general_chat"), result)
    }

    @Test
    fun `budget exhaustion still degrades to None exactly as before`() {
        val skills = com.pinkdreams.persistence.repositories.SkillRepository(
            com.pinkdreams.persistence.database.DatabaseFactory.connectInMemory().also { com.pinkdreams.persistence.database.DatabaseFactory.initializeSchema(it) },
        )
        skills.activate(skills.publish(skills.createNextVersion("general_chat", "SKILL: GENERAL_CHAT").id).id)
        val client = LlmClient { throw OpenRouterBudgetExhaustionException(finishReason = "length", completionTokens = 600, reasoningTokens = 600) }
        val discovery = LlmIntentDiscovery(client, skills, intentEngineRepository = null)

        val result = discovery.selectSkill(request(), context())

        assertEquals(SkillSelection.None, result)
    }

    @Test
    fun `malformed output still degrades to None exactly as before`() {
        val skills = com.pinkdreams.persistence.repositories.SkillRepository(
            com.pinkdreams.persistence.database.DatabaseFactory.connectInMemory().also { com.pinkdreams.persistence.database.DatabaseFactory.initializeSchema(it) },
        )
        skills.activate(skills.publish(skills.createNextVersion("general_chat", "SKILL: GENERAL_CHAT").id).id)
        val client = LlmClient { LlmResponse(content = "The user seems to be flirting.", provider = "test", metadata = mapOf("finish_reason" to "stop")) }
        val discovery = LlmIntentDiscovery(client, skills, intentEngineRepository = null)

        val result = discovery.selectSkill(request(), context())

        assertEquals(SkillSelection.None, result)
    }
}
