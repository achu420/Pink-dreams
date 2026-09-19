package com.pinkdreams.baseline

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.skill.LlmIntentDiscovery
import com.pinkdreams.chat.skill.SkillSelection
import com.pinkdreams.llm.GenerationRequest
import com.pinkdreams.llm.LlmClient
import com.pinkdreams.llm.LlmResponse
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.MemoryEngineRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Part Q: Intent Discovery against the full canonical baseline skill set.
 *
 * These tests use a scripted LlmClient rather than a live model: they verify
 * the CONTRACT (candidate keys offered, output parsed, unknown keys rejected,
 * nothing hard-coded in production logic), not the model's judgment. The
 * example sentences from the specification are used as inputs so the mapping
 * they describe is exercised end to end, but the sentences appear only in test
 * code — production logic never matches on them.
 */
class BaselineIntentDiscoveryTest {

    private fun seededSkillRepo(activateAll: Boolean = true): SkillRepository {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val skills = SkillRepository(db)
        BaselineSeeder(
            ConversationEngineRepository(db), PersonaRepository(db), PersonaCoreVersionRepository(db),
            skills, MemoryEngineRepository(db),
        ).seedIfMissing()
        if (activateAll) {
            // Activation is an explicit admin action; the baseline seeds drafts.
            skills.findAll().forEach { skills.activate(skills.publish(it.id).id) }
        }
        return skills
    }

    private fun requestAndContext(message: String): Pair<ChatRequest, ChatContext> {
        val request = ChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), message)
        val context = ChatContext(
            blocks = listOf(ContextBlock("system", "engine+persona"), ContextBlock("user", message)),
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID(),
        )
        return request to context
    }

    /** Scripted client: returns the given key, and records the prompt it was offered. */
    private class ScriptedClient(private val key: String?) : LlmClient {
        var promptSeen: String? = null
        override fun generate(request: GenerationRequest): LlmResponse {
            promptSeen = request.context.blocks.first().content
            val body = if (key == null) """{"skillKey": null}""" else """{"skillKey": "$key"}"""
            return LlmResponse(content = body, provider = "test")
        }
    }

    @Test
    fun `each specification example maps to its skill when the model returns that key`() {
        val skills = seededSkillRepo()
        val cases = listOf(
            "Hey, I just want someone to hang out with." to "companionship",
            "I feel like we've become really good friends." to "friendship",
            "I'm having a really difficult day." to "emotional_support",
            "What do you think about travelling?" to "general_chat",
            "You look really cute today." to "flirting",
            "I think I'm developing feelings for you." to "romantic_conversation",
            "I want us to become closer." to "relationship_building",
            "What exactly are we?" to "relationship_discussion",
            "Would you go on a date with me?" to "dating",
            "You're impossible 😂" to "playful_teasing",
        )

        cases.forEach { (message, expectedKey) ->
            val client = ScriptedClient(expectedKey)
            val discovery = LlmIntentDiscovery(client, skills)
            val (request, context) = requestAndContext(message)

            assertEquals(
                SkillSelection.Selected(expectedKey),
                discovery.selectSkill(request, context),
                "\"$message\" should resolve to $expectedKey",
            )
        }
    }

    @Test
    fun `ambiguous input returning null yields None and chat continues normally`() {
        val skills = seededSkillRepo()
        val discovery = LlmIntentDiscovery(ScriptedClient(null), skills)
        val (request, context) = requestAndContext("hmm")

        assertEquals(SkillSelection.None, discovery.selectSkill(request, context))
    }

    @Test
    fun `all twelve active skill keys are offered as candidates and nothing else`() {
        val skills = seededSkillRepo()
        val client = ScriptedClient("general_chat")
        val discovery = LlmIntentDiscovery(client, skills)
        val (request, context) = requestAndContext("what do you think about travelling?")

        discovery.selectSkill(request, context)

        val prompt = client.promptSeen!!
        val candidateLine = prompt.lineSequence()
            .dropWhile { !it.contains("ACTIVE SKILL KEYS") }
            .drop(1)
            .first { it.isNotBlank() }
        val offered = candidateLine.split(",").map { it.trim() }.toSet()
        assertEquals(
            BaselineConfiguration.SKILLS.map { it.key }.toSet(),
            offered,
            "Exactly the twelve active baseline skill keys must be offered as candidates",
        )
        assertTrue(prompt.contains("Intent Discovery component"), "The canonical Intent Discovery prompt must be used")
        assertTrue(prompt.contains("You are NOT generating the assistant's response"), "Classifier must be told it is not generating")
        assertTrue(prompt.contains("DO NOT OVER-ESCALATE"), "The anti-escalation rule is part of the canonical prompt")
    }

    @Test
    fun `an inactive skill is never offered as a candidate nor selectable`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val skills = SkillRepository(db)
        BaselineSeeder(
            ConversationEngineRepository(db), PersonaRepository(db), PersonaCoreVersionRepository(db),
            skills, MemoryEngineRepository(db),
        ).seedIfMissing()
        // Activate only general_chat; everything else stays draft.
        val generalChat = skills.findByKey("general_chat").single()
        skills.activate(skills.publish(generalChat.id).id)

        val client = ScriptedClient("foreplay") // model tries to pick an inactive skill
        val discovery = LlmIntentDiscovery(client, skills)
        val (request, context) = requestAndContext("anything")

        assertEquals(SkillSelection.None, discovery.selectSkill(request, context), "An inactive skill must never be selectable")

        // The candidate list is the authoritative offer; the glossary below it is
        // static canonical prompt text and is not a list of selectable keys.
        val candidateLine = client.promptSeen!!.lineSequence()
            .dropWhile { !it.contains("ACTIVE SKILL KEYS") }
            .drop(1)
            .first { it.isNotBlank() }
        assertEquals("general_chat", candidateLine.trim(), "Only active skills may be offered as candidates")
    }

    @Test
    fun `specification example sentences appear nowhere in production logic`() {
        // Guards the "do not hard-code these sentences into production logic"
        // requirement: the classifier must generalize, not pattern-match.
        val productionSources = java.io.File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.readText() }
            .toList()

        val forbiddenSamples = listOf(
            "I just want someone to hang out with",
            "I'm having a really difficult day",
            "You look really cute today",
            "Would you go on a date with me",
            "What exactly are we?",
        )
        forbiddenSamples.forEach { sample ->
            assertTrue(
                productionSources.none { it.contains(sample) },
                "Test sentence \"$sample\" must not be hard-coded into production logic",
            )
        }
    }
}
