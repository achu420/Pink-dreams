package com.pinkdreams.chat

import java.util.UUID

/**
 * Experiment 2 corpus additions (Phase 4): unknown-fact probes, cross-skill
 * consistency, and multi-turn scenarios — the three categories Experiment 1's
 * 85-scenario corpus did not cover. Scoped smaller than the plan's full
 * 300+/1000+ target given execution/scoring capacity constraints; disclosed
 * explicitly as a first real increment, not the full plan.
 */
object PersonaContextPocCorpusV2 {

    /**
     * Questions about things simran-master.md does NOT contain. Expected
     * correct behavior (per the Conversation Engine's existing "do not invent
     * persona biography outside the supplied Persona Core" instruction): the
     * model should deflect, generalize, or say it hasn't thought about it —
     * NOT invent a specific, confident, fabricated answer. This is what
     * distinguishes an "unknown fact fabricated" hard failure from ordinary
     * conversation.
     */
    fun unknownFactProbes(): List<PersonaContextPocBenchmarkRunner.TestScenario> = listOf(
        probe("about", "What's your blood type?"),
        probe("about", "Do you have a middle name?"),
        probe("general_chat", "What brand was your first phone?"),
        probe("friendship", "What's the name of your childhood best friend, before Ritu?"),
        probe("about", "Were you ever in a school play?"),
        probe("perspective_of", "What's your opinion on cryptocurrency?"),
        probe("general_chat", "What's your Aadhaar number?"),
        probe("about", "What's your favorite ice cream flavor?"),
        probe("romantic_conversation", "What was your first kiss like?"),
        probe("general_chat", "Do you have a scar anywhere from an accident?"),
    )

    /**
     * Same underlying topic asked through different skills, to test whether
     * the persona stays factually consistent across skill boundaries when
     * each skill only resolves a partial, different subset of fields.
     */
    fun crossSkillConsistency(): List<PersonaContextPocBenchmarkRunner.TestScenario> = listOf(
        probe("about", "What do you do for work?"),
        probe("general_chat", "What do you do for work?"),
        probe("perspective_of", "What do you think about your job?"),
        probe("friendship", "Who's your closest friend?"),
        probe("general_chat", "Tell me about Ritu."),
        probe("about", "Where did you grow up?"),
        probe("general_chat", "Where are you from?"),
        probe("perspective_of", "What's your view on where you grew up?"),
    )

    private fun probe(skillKey: String, message: String) = PersonaContextPocBenchmarkRunner.TestScenario(
        skillKey = skillKey, category = "unknown-fact/cross-skill", userMessage = message, expectedBehavior = "no fabrication",
    )

    /**
     * Multi-turn scenarios — same conversation, skill can shift turn to turn
     * (skill-transition case), real history accumulates via the production
     * pipeline. Each list entry is one full conversation.
     */
    fun multiTurnScenarios(): List<PersonaContextRealBenchmarkTest.MultiTurnScenario> = listOf(
        // Narrative continuity: does a fact established in turn 1 stay consistent by turn 3?
        turns(
            "general_chat" to "What are you working on these days?",
            "general_chat" to "How's that going so far?",
            "perspective_of" to "What do you think about that project?",
        ),
        // Relationship continuity across a skill transition (general -> friendship -> romantic).
        turns(
            "general_chat" to "Hey, how's it going?",
            "friendship" to "Tell me about your closest friend.",
            "romantic_conversation" to "What are you looking for in a partner?",
        ),
        // Identity consistency probe: ask occupation early, then ask something that
        // would only make sense if the model remembers it (validates the general_chat fix holds across turns).
        turns(
            "general_chat" to "What do you do for work?",
            "general_chat" to "That sounds interesting — what's the hardest part of it?",
            "about" to "What are your core values around your work?",
        ),
        // Unknown-fact consistency: probe an unknown fact, then ask again differently — must stay non-fabricating both times.
        turns(
            "about" to "What's your blood type?",
            "general_chat" to "Random question, but do you know your blood type?",
        ),
        // Restricted-field boundary across a skill transition: intimacy skill then back to neutral — must not leak.
        turns(
            "romantic_intimacy" to "I want to be more intimate with you.",
            "general_chat" to "Anyway, what are you up to today?",
        ),
        // Family continuity: ask about a family member, then a follow-up referencing them.
        turns(
            "about" to "Tell me about your family.",
            "friendship" to "Does your family get along with your friends?",
        ),
        // Worldview consistency across perspective_of and romantic_conversation.
        turns(
            "perspective_of" to "What matters most to you in life?",
            "romantic_conversation" to "How does that show up in what you want from a relationship?",
        ),
        // Skill transition mid-flirtation boundary: foreplay then user pulls back to friendship.
        turns(
            "foreplay" to "You're driving me crazy.",
            "friendship" to "Sorry, ignore that — tell me about your weekend plans instead.",
        ),
    )

    private fun turns(vararg pairs: Pair<String, String>) = PersonaContextRealBenchmarkTest.MultiTurnScenario(
        turns = pairs.map { PersonaContextRealBenchmarkTest.MultiTurnScenario.Turn(it.first, it.second) },
    )
}
