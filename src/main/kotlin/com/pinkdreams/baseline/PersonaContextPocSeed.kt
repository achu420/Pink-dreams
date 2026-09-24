package com.pinkdreams.baseline

import org.jetbrains.exposed.sql.Database
import java.util.UUID

/**
 * Seeds field registry, skill context requirements, and test scenarios for Persona Context POC.
 * Called once via admin bootstrap endpoint, not auto-run in production (per Pink Dreams convention).
 */
class PersonaContextPocSeed(private val db: Database) {

    /**
     * Seeds ~24 test scenarios covering categories A-H across representative skills.
     * Each scenario has userMessage, optional seedHistory/memoryFacts, and explicit skill key.
     */
    fun seedScenarios() {
        val scenarios = listOf(
            // Category A: Simple recall from identity
            ScenarioRow(skill = "general_chat", category = "A", message = "Tell me about yourself"),
            ScenarioRow(skill = "general_chat", category = "A", message = "Who are you?"),
            ScenarioRow(skill = "about", category = "A", message = "What's your background?"),

            // Category B: Behavioral signature / decision patterns
            ScenarioRow(skill = "general_chat", category = "B", message = "How do you usually handle conflict?"),
            ScenarioRow(skill = "general_chat", category = "B", message = "What do you value most in friends?"),
            ScenarioRow(skill = "friendship", category = "B", message = "Tell me about your decision-making style"),

            // Category C: Worldview / aspirations
            ScenarioRow(skill = "perspective_of", category = "C", message = "What do you think about career ambition?"),
            ScenarioRow(skill = "perspective_of", category = "C", message = "What matters to you in life?"),
            ScenarioRow(skill = "general_chat", category = "C", message = "What are your goals?"),

            // Category D: Relational / continuity
            ScenarioRow(skill = "friendship", category = "D", message = "Tell me about Ritu"),
            ScenarioRow(skill = "romantic_conversation", category = "D", message = "What's important in a relationship?"),
            ScenarioRow(skill = "friendship", category = "D", message = "How would you describe your close friends?"),

            // Category E: Narrative state / timeline
            ScenarioRow(skill = "general_chat", category = "E", message = "What are you working on these days?"),
            ScenarioRow(skill = "general_chat", category = "E", message = "What happened on your Ladakh trip?"),
            ScenarioRow(skill = "about", category = "E", message = "Walk me through a typical week"),

            // Category F: Preference / conditional fields
            ScenarioRow(skill = "general_chat", category = "F", message = "What's your favorite thing to do on weekends?"),
            ScenarioRow(skill = "general_chat", category = "F", message = "Do you prefer beaches or mountains?"),
            ScenarioRow(skill = "romantic_conversation", category = "F", message = "What attracts you most?"),

            // Category G: Restricted fields / access gating
            ScenarioRow(skill = "romantic_intimacy", category = "G", message = "Tell me about your intimacy preferences"),
            ScenarioRow(skill = "romantic_intimacy", category = "G", message = "What's your approach to physical intimacy?"),
            ScenarioRow(skill = "foreplay", category = "G", message = "How do you like to be touched?"),

            // Category H: Cross-skill consistency
            ScenarioRow(skill = "friendship", category = "H", message = "What makes a good friend?"),
            ScenarioRow(skill = "romantic_conversation", category = "H", message = "What makes a good partner?"),
            ScenarioRow(skill = "general_chat", category = "H", message = "How do you balance your personal and professional life?"),
            ScenarioRow(skill = "perspective_of", category = "H", message = "What's your take on relationships?"),
        )
        // TODO: Insert scenarios into persona_poc_scenario table
    }

    /**
     * Seeds skill context requirements for POC-specific skills and existing skills.
     * Maps skill key → required/recommended/conditional/restricted field lists.
     */
    fun seedSkillContextRequirements() {
        val requirements = listOf(
            // New POC test skills
            SkillContextRequirementRow(
                skillKey = "about",
                requiredFields = listOf("identity.age", "identity.name", "life_context.origin", "life_context.current_location"),
                recommendedFields = listOf("background.education", "background.occupation", "personality.core_traits"),
                conditionalFields = listOf("worldview.strong_opinions", "aspirations.career"),
                restrictedFields = null,
                purpose = "Persona self-introduction and background overview",
                whenToUse = "When user asks for basic info about persona",
            ),
            SkillContextRequirementRow(
                skillKey = "perspective_of",
                requiredFields = listOf("worldview.values", "personality.core_traits"),
                recommendedFields = listOf("decision_patterns", "aspirations", "relational.attachment_style"),
                conditionalFields = listOf("background.life_events", "narrative_state.current_arcs"),
                restrictedFields = null,
                purpose = "Share perspective/opinion on topics",
                whenToUse = "When user asks 'what do you think about...'",
            ),

            // Existing skills with field mappings (per Master Instruction §8)
            SkillContextRequirementRow(
                skillKey = "general_chat",
                requiredFields = listOf("identity", "personality.core_traits"),
                recommendedFields = listOf("communication.voice", "narrative_state.current_arcs", "relational"),
                conditionalFields = listOf("worldview.strong_opinions", "preferences"),
                restrictedFields = null,
                purpose = "General conversation",
                whenToUse = "Default skill for most conversations",
            ),
            SkillContextRequirementRow(
                skillKey = "friendship",
                requiredFields = listOf("identity", "personality.core_traits", "relational.attachment_style"),
                recommendedFields = listOf("communication.voice", "decision_patterns", "background.friends"),
                conditionalFields = listOf("preferences.activities", "background.life_events"),
                restrictedFields = null,
                purpose = "Friendship and peer relationships",
                whenToUse = "When conversation is about friendship or close relationships",
            ),
            SkillContextRequirementRow(
                skillKey = "romantic_conversation",
                requiredFields = listOf("identity", "personality.core_traits", "relational.romantic_style", "preferences.romantic"),
                recommendedFields = listOf("communication.voice", "worldview.values", "aspirations"),
                conditionalFields = listOf("decision_patterns", "narrative_state.relationship_arcs"),
                restrictedFields = null,
                purpose = "Romantic relationship dynamics (non-sexual)",
                whenToUse = "When conversation involves romantic interest or relationship dynamics",
            ),
            SkillContextRequirementRow(
                skillKey = "romantic_intimacy",
                requiredFields = listOf("identity", "relational.romantic_style", "intimacy.arousal_response"),
                recommendedFields = listOf("intimacy.comfort_and_boundaries", "communication.voice"),
                conditionalFields = null,
                restrictedFields = listOf("intimate_anatomy", "adult_intimacy"),
                purpose = "Sexual intimacy and desire",
                whenToUse = "When conversation explicitly addresses sexual attraction or intimacy",
            ),
            SkillContextRequirementRow(
                skillKey = "foreplay",
                requiredFields = listOf("intimate_anatomy", "adult_intimacy.flirting_style"),
                recommendedFields = listOf("adult_intimacy.intimate_pacing", "communication.voice"),
                conditionalFields = null,
                restrictedFields = listOf("intimate_anatomy.specific_character", "adult_intimacy"),
                purpose = "Flirtation and foreplay",
                whenToUse = "When conversation is explicitly flirtatious or building sexual tension",
            ),
        )
        // TODO: Insert requirements into skill_context_requirements table
    }

    data class ScenarioRow(
        val skill: String,
        val category: String,
        val message: String,
        val notes: String? = null,
    )

    data class SkillContextRequirementRow(
        val skillKey: String,
        val requiredFields: List<String>?,
        val recommendedFields: List<String>?,
        val conditionalFields: List<String>?,
        val restrictedFields: List<String>?,
        val purpose: String?,
        val whenToUse: String?,
    )
}
