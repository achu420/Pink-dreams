package com.pinkdreams.chat.skill

import com.pinkdreams.baseline.BaselineConfiguration
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fix The Live Intent Engine phase: locks in that the canonical Intent
 * Engine seed content ([IntentEngineDefaultContent.CONTENT]) actually
 * documents every active skill and covers the routing distinctions that
 * were measured to matter, without duplicating Skill/Persona/Engine bodies
 * into the router. This is the guard against the exact defect this phase
 * fixed: the seed content silently falling behind the real skill roster.
 */
class IntentEngineCompletenessTest {

    private val content = IntentEngineDefaultContent.CONTENT

    // ---------- roster completeness ----------

    @Test
    fun `every active skill key is documented in the glossary`() {
        val undocumented = BaselineConfiguration.SKILLS.filter { skill ->
            !content.contains(Regex("(?m)^${Regex.escape(skill.key)}\\s+="))
        }
        assertTrue(undocumented.isEmpty(), "Undocumented skill keys: ${undocumented.map { it.key }}")
    }

    @Test
    fun `the glossary lists no key outside the real skill roster`() {
        val glossaryKeys = Regex("(?m)^([a-z_]+)\\s+=").findAll(content).map { it.groupValues[1] }.toList()
        val realKeys = BaselineConfiguration.SKILLS.map { it.key }.toSet()
        val invented = glossaryKeys.filter { it !in realKeys }
        assertTrue(invented.isEmpty(), "Glossary documents keys that are not real active skills: $invented")
    }

    @Test
    fun `the glossary has no duplicate keys`() {
        val glossaryKeys = Regex("(?m)^([a-z_]+)\\s+=").findAll(content).map { it.groupValues[1] }.toList()
        assertEquals(glossaryKeys.size, glossaryKeys.toSet().size, "Duplicate keys in the glossary: $glossaryKeys")
    }

    // ---------- structure: router, not a behavior engine ----------

    @Test
    fun `no full skill body is embedded in the intent engine`() {
        BaselineConfiguration.SKILLS.forEach { skill ->
            assertTrue(!content.contains(skill.content), "${skill.key}'s full skill body must never be embedded in the Intent Engine")
        }
    }

    @Test
    fun `no persona core or conversation engine content is embedded`() {
        assertTrue(!content.contains(BaselineConfiguration.CONVERSATION_ENGINE), "Conversation Engine content must never be embedded in the Intent Engine")
    }

    // ---------- output contract ----------

    @Test
    fun `the output contract is unchanged`() {
        assertTrue(content.contains("""{"skillKey": "..."}"""), "Prompt must declare the selected-key shape")
        assertTrue(content.contains("""{"skillKey": null}"""), "Prompt must declare the null shape")
    }

    // ---------- disambiguation coverage (measured gaps from the model-latency investigation) ----------

    @Test
    fun `required disambiguation pairs are explicitly covered`() {
        val requiredPhraseFragments = listOf(
            "flirting_practice", // flirting vs flirting_practice
            "romantic_intimacy vs foreplay",
            "foreplay vs sexual_stimulation",
            "relationship_discussion vs relationship_guidance",
            "confidence_building vs emotional_support",
            "encouragement vs emotional_support",
            "dating vs confidence_building",
            "dating vs social_practice",
            "friendship vs companionship",
            "advice vs problem_solving",
            "playful_teasing vs general_chat",
        )
        requiredPhraseFragments.forEach { fragment ->
            assertTrue(content.contains(fragment), "Missing required disambiguation coverage: \"$fragment\"")
        }
    }

    @Test
    fun `practice versus real interaction is explicitly addressed`() {
        assertTrue(content.contains("REAL INTERACTION FROM A REQUEST TO PRACTICE"), "Must explicitly distinguish real interaction from a request to practice it")
    }

    @Test
    fun `filler and bare acknowledgements are explicitly routed toward null`() {
        assertTrue(content.contains("FILLER", ignoreCase = true), "Must explicitly address filler/bare-acknowledgement handling")
        assertTrue(content.contains("\"lol\"") || content.contains("'lol'"), "Must give a concrete filler example")
    }
}
