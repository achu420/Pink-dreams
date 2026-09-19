package com.pinkdreams.baseline

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.PersistedResponse
import com.pinkdreams.chat.memory.CompletedTurn
import com.pinkdreams.chat.memory.LlmMemoryExtractor
import com.pinkdreams.chat.skill.LlmIntentDiscovery
import com.pinkdreams.chat.skill.SkillSelection
import com.pinkdreams.llm.FakeLlmClient
import com.pinkdreams.llm.LlmResponse
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.SkillRepository
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reasoning-prose leak, asserted through the real parsers rather than only
 * the extractor helper — this is the shape that actually caused live memory
 * extraction to record nothing while reporting no error.
 */
class ReasoningProseLeakRegressionTest {

    private fun turn(userMessage: String) = CompletedTurn(
        request = ChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), userMessage),
        context = ChatContext(
            blocks = listOf(ContextBlock("system", "engine"), ContextBlock("user", userMessage)),
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID(),
        ),
        response = PersistedResponse(UUID.randomUUID(), "Noted."),
    )

    @Test
    fun `memory extraction survives reasoning prose before the json`() {
        val leaked = "Let me think. The user explicitly stated a durable dietary preference, so this is " +
            "worth remembering. Returning it now. " +
            """{"facts": [{"fact": "user cannot stand coriander", "factType": "interest", "criticality": "medium"}]}"""
        val extractor = LlmMemoryExtractor(FakeLlmClient(response = LlmResponse(content = leaked, provider = "test")))

        val result = extractor.extract(turn("I really can't stand coriander."))

        assertEquals(1, result.newFacts.size, "A real extraction must not be discarded because reasoning leaked into content")
        assertEquals("user cannot stand coriander", result.newFacts.single().fact)
    }

    @Test
    fun `intent discovery survives reasoning prose before the json`() {
        val db = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }
        val skills = SkillRepository(db)
        val draft = skills.createNextVersion("emotional_support", BaselineConfiguration.SKILLS.first { it.key == "emotional_support" }.content)
        skills.activate(skills.publish(draft.id).id)

        val leaked = "The user is expressing distress and wants to be heard, not advised. That maps to " +
            """emotional_support. {"skillKey": "emotional_support"}"""
        val discovery = LlmIntentDiscovery(FakeLlmClient(response = LlmResponse(content = leaked, provider = "test")), skills)

        val request = ChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "I'm barely holding it together")
        val context = ChatContext(
            blocks = listOf(ContextBlock("system", "engine"), ContextBlock("user", request.content)),
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID(),
        )

        assertEquals(SkillSelection.Selected("emotional_support"), discovery.selectSkill(request, context))
    }

    @Test
    fun `a genuinely unparseable response still yields nothing rather than a crash`() {
        val extractor = LlmMemoryExtractor(
            FakeLlmClient(response = LlmResponse(content = "I would rather not answer that.", provider = "test")),
        )

        val result = extractor.extract(turn("hello"))

        assertTrue(result.newFacts.isEmpty(), "Best-effort behavior is unchanged for a truly malformed response")
    }
}
