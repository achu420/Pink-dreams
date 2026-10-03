package com.pinkdreams.chat.skill

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.llm.LlmClient
import com.pinkdreams.llm.LlmResponse
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.SkillRepository
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * PHASE 59 Step 2 -- deterministic tests for the Failure A fix (Phase 58 §4/§12,
 * option A1). `LlmIntentDiscovery` delegates classification entirely to the
 * LLM (no fixed keyword heuristics), so these tests prove two things
 * deterministically, and no more:
 *
 * 1. The seed prompt text (`IntentEngineDefaultContent.CONTENT`) now contains
 *    the new PERSONA-BIOGRAPHICAL QUESTIONS rule, with affirmative coverage
 *    of the exact example question types Phase 59 §2 requires, and the
 *    existing "return null when uncertain" rule is unmodified (byte-level,
 *    same discipline as Phase 54's ablation-diff test).
 * 2. The routing/parsing plumbing (`LlmIntentDiscovery.selectSkill`) still
 *    correctly returns whatever key a classifier decides, for every one of
 *    the Step 2 test cases A-G, given a deterministic fake LLM response --
 *    this proves the WIRING is intact, not that a real model will choose
 *    correctly. Real behavioral confirmation is Phase 59 Step 6's job
 *    (real API calls), not this file's.
 */
class PersonaBiographicalIntentTest {

    // ===================== 1. Seed text content assertions =====================

    @Test
    fun `seed prompt contains the new persona-biographical rule with required example coverage`() {
        // The raw constant hard-wraps prose across lines, so phrases spanning a
        // wrap point (e.g. "your\nbackground?") would never match a literal
        // substring check. Collapse whitespace runs to single spaces first,
        // matching how the text actually reads once rendered/sent.
        val content = IntentEngineDefaultContent.CONTENT.replace(Regex("\\s+"), " ")
        assertTrue(content.contains("PERSONA-BIOGRAPHICAL QUESTIONS"), "new rule section must exist")

        val requiredExamples = listOf(
            "Where are you from?", "Where did you grow up?", "Where do you live?",
            "What do you do?", "Tell me about yourself", "What's your background?",
            "Where did you study?", "What is your family like?", "What is your day-to-day life like?",
        )
        requiredExamples.forEach { example ->
            assertTrue(content.contains(example), "required example missing from the seed prompt: $example")
        }

        assertTrue(
            content.contains("select general_chat"),
            "the rule must affirmatively direct general_chat for these questions",
        )
        assertTrue(
            content.contains("Do not select general_chat merely because a persona-related word appears"),
            "the rule must NOT turn every persona-related word into general_chat",
        )
    }

    @Test
    fun `existing return-null-when-uncertain rule is unmodified`() {
        val content = IntentEngineDefaultContent.CONTENT.replace(Regex("\\s+"), " ")
        assertTrue(content.contains("Return null when no available skill clearly represents the interaction."))
        assertTrue(content.contains("When the conversation genuinely does not favour one over another, return null"))
    }

    @Test
    fun `new rule is placed after SELECTION RULES and before DOMINANT INTENT WHEN SKILLS OVERLAP, other sections unmoved`() {
        val content = IntentEngineDefaultContent.CONTENT
        val selectionRulesIdx = content.indexOf("SELECTION RULES")
        val newRuleIdx = content.indexOf("PERSONA-BIOGRAPHICAL QUESTIONS")
        val dominantIntentIdx = content.indexOf("DOMINANT INTENT WHEN SKILLS OVERLAP")
        val interpretingIdx = content.indexOf("INTERPRETING THE SKILL KEYS")
        assertTrue(selectionRulesIdx < newRuleIdx, "new rule must come after SELECTION RULES")
        assertTrue(newRuleIdx < dominantIntentIdx, "new rule must come before DOMINANT INTENT WHEN SKILLS OVERLAP")
        assertTrue(dominantIntentIdx < interpretingIdx, "unrelated section order must be unchanged")
    }

    // ===================== 2. Wiring/routing tests (mocked LLM, Step 2 A-G) =====================

    private class World {
        val db = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }
        val skills = SkillRepository(db)

        init {
            listOf("general_chat", "romantic_intimacy", "advice", "friendship").forEach { key ->
                val v = skills.createNextVersion(key, "SKILL: ${key.uppercase()}")
                skills.activate(skills.publish(v.id).id)
            }
        }
    }

    private fun discoveryWithFakeResponse(world: World, skillKeyJson: String?): LlmIntentDiscovery {
        val client = LlmClient {
            val body = if (skillKeyJson == null) """{"skillKey": null}""" else """{"skillKey": "$skillKeyJson"}"""
            LlmResponse(content = body, provider = "test")
        }
        return LlmIntentDiscovery(client, world.skills)
    }

    private fun requestAndContext(message: String): Pair<ChatRequest, ChatContext> {
        val request = ChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), message)
        val context = ChatContext(
            blocks = listOf(ContextBlock("system", "engine"), ContextBlock("user", message)),
            engineVersionId = UUID.randomUUID(), personaCoreVersionId = UUID.randomUUID(),
        )
        return request to context
    }

    // A-D: biographical questions -- wiring correctly returns general_chat when the classifier says so.
    @Test
    fun `A - Where are you from routes to general_chat when the classifier selects it`() {
        val world = World()
        val (request, context) = requestAndContext("Where are you from?")
        val result = discoveryWithFakeResponse(world, "general_chat").selectSkill(request, context)
        assertEquals(SkillSelection.Selected("general_chat"), result)
    }

    @Test
    fun `B - Where did you grow up routes to general_chat when the classifier selects it`() {
        val world = World()
        val (request, context) = requestAndContext("Where did you grow up?")
        val result = discoveryWithFakeResponse(world, "general_chat").selectSkill(request, context)
        assertEquals(SkillSelection.Selected("general_chat"), result)
    }

    @Test
    fun `C - What do you do for work routes to general_chat when the classifier selects it`() {
        val world = World()
        val (request, context) = requestAndContext("What do you do for work?")
        val result = discoveryWithFakeResponse(world, "general_chat").selectSkill(request, context)
        assertEquals(SkillSelection.Selected("general_chat"), result)
    }

    @Test
    fun `D - Tell me about yourself routes to general_chat when the classifier selects it`() {
        val world = World()
        val (request, context) = requestAndContext("Tell me about yourself.")
        val result = discoveryWithFakeResponse(world, "general_chat").selectSkill(request, context)
        assertEquals(SkillSelection.Selected("general_chat"), result)
    }

    // E: a clearly romantic request still routes to romantic_intimacy -- new rule does not steal this.
    @Test
    fun `E - a clearly romantic request still selects romantic_intimacy, unaffected by the new rule`() {
        val world = World()
        val (request, context) = requestAndContext("I want to be intimate with you tonight")
        val result = discoveryWithFakeResponse(world, "romantic_intimacy").selectSkill(request, context)
        assertEquals(SkillSelection.Selected("romantic_intimacy"), result)
    }

    // F: a clearly advice-oriented request still routes to advice.
    @Test
    fun `F - a clearly advice-oriented request still selects advice, unaffected by the new rule`() {
        val world = World()
        val (request, context) = requestAndContext("Should I take the new job offer or stay?")
        val result = discoveryWithFakeResponse(world, "advice").selectSkill(request, context)
        assertEquals(SkillSelection.Selected("advice"), result)
    }

    // G: a genuinely ambiguous request can still return null -- new rule did not remove this path.
    @Test
    fun `G - a genuinely ambiguous request can still return null`() {
        val world = World()
        val (request, context) = requestAndContext("hm")
        val result = discoveryWithFakeResponse(world, null).selectSkill(request, context)
        assertEquals(SkillSelection.None, result)
    }

    // Unknown-key rejection and malformed-response fallback remain intact (existing safety properties, re-verified).
    @Test
    fun `unknown skill key is still rejected, falls back to None`() {
        val world = World()
        val (request, context) = requestAndContext("Where are you from?")
        val result = discoveryWithFakeResponse(world, "not_a_real_skill").selectSkill(request, context)
        assertIs<SkillSelection.None>(result)
    }
}
