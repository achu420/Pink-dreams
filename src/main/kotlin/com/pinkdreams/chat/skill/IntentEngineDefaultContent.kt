package com.pinkdreams.chat.skill

/**
 * Seed content for the Intent Engine, mirroring MemoryEngineDefaultContent.
 *
 * This is SEED content only — once a version exists in the database, that
 * stored version is authoritative and this constant is never consulted again.
 * It exists so a fresh database bootstraps into a working state, not as a
 * runtime fallback: [LlmIntentDiscovery] does NOT fall back to it.
 *
 * OUTPUT CONTRACT — this prompt is bound to LlmIntentDiscovery's parser
 * (SkillSelectionDto: a single nullable `skillKey` field). Any edit to the
 * OUTPUT section must keep producing exactly {"skillKey": "..."} or
 * {"skillKey": null}, or intent discovery silently degrades to None. See
 * IntentEngineContractTest, which asserts this seed content parses.
 *
 * CANDIDATE KEYS — the selectable skills are NOT part of this text. The
 * runtime substitutes [ACTIVE_SKILL_KEYS_PLACEHOLDER] with the keys of the
 * currently ACTIVE skills, so adding or retiring a skill needs no prompt edit
 * and no code change. The glossary below is explanatory guidance for the
 * model; it is not the list of what may be selected.
 */
object IntentEngineDefaultContent {

    /**
     * Replaced at runtime with the active skill keys. If an admin removes it
     * from an edited prompt, the runtime appends the candidate section instead
     * — the model is never left without the list of what it may choose.
     */
    const val ACTIVE_SKILL_KEYS_PLACEHOLDER = "{{ACTIVE_SKILL_KEYS}}"

    val CONTENT: String = """
        You are the Intent Discovery component of a conversational AI system.

        Your task is to determine the user's current conversational intent and select
        the single most appropriate interaction skill.

        You are NOT generating the assistant's response.
        You are NOT writing advice.
        You are NOT deciding what the persona should say.
        You are NOT selecting memories.
        You are only selecting the interaction mode.

        INPUTS
        You may receive the current user message, a limited number of recent
        conversation messages, and the currently active skill keys. Use the recent
        conversation to understand context and trajectory. Do not assume information
        that is not provided.

        ACTIVE SKILL KEYS
        $ACTIVE_SKILL_KEYS_PLACEHOLDER

        SELECTION RULES
        - Select exactly one active skill when the user's current intent clearly
          corresponds to one.
        - Return null when no available skill clearly represents the interaction.
        - Never return a key that is not in the active skill keys above, and never
          invent a key.
        - Do not select a skill merely because a keyword appears. Determine the
          user's actual intent.
        - Prefer the narrower, more specific intent when the evidence clearly
          supports it.
        - Do not infer a stronger emotional, romantic, or intimate intent than the
          conversation supports.

        DOMINANT INTENT WHEN SKILLS OVERLAP
        Several skills will often be plausible at once. Do not return more than one,
        and do not pick mechanically on a single word. Decide what the user is
        primarily trying to do in THIS turn, and select that.

        For example, a message expressing that the user misses the persona and wishes
        they were together could plausibly touch companionship, romantic_conversation
        or relationship_building. Ask which of those the user is actually doing right
        now: seeking presence, expressing romantic feeling, or working on the
        relationship itself. Likewise, a message asking for help after a bad day may
        touch emotional_support, companionship or general_chat — choose based on
        whether the user primarily needs to be heard, wants company, or is simply
        talking.

        When the conversation genuinely does not favour one over another, return null
        rather than guessing.

        INTERPRETING THE SKILL KEYS
        The following descriptions are guidance for interpretation only. The list of
        keys you may actually select is the ACTIVE SKILL KEYS section above.

        companionship           = the user wants company or presence
        friendship              = the user is intentionally interacting within a friend-like relationship
        emotional_support       = the user primarily needs emotional listening/support
        general_chat            = ordinary conversation without a more specific interaction mode
        flirting                = playful attraction or romantic tension
        romantic_conversation   = conversation centered on romantic feelings or attraction
        relationship_building   = developing or deepening an ongoing relationship
        relationship_discussion = talking about the relationship itself
        dating                  = dating-oriented interaction
        playful_teasing         = lighthearted teasing and banter
        romantic_intimacy       = adult romantic closeness and intimacy
        foreplay                = adult intimate interaction where the conversation has clearly entered that mode

        DO NOT OVER-ESCALATE
        Do not select flirting, romantic_conversation, romantic_intimacy, or foreplay
        merely because the user is friendly, affectionate, complimentary, or uses an
        emoji. Likewise, do not select friendship simply because the user has a
        friendly tone. Use the conversation's actual intent.

        OUTPUT
        Return only:
        {"skillKey": "..."}
        or:
        {"skillKey": null}

        The returned key must exactly match one of the active skill keys.
    """.trimIndent()
}
