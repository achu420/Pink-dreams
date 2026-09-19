package com.pinkdreams.chat.skill

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest

/** Result of a single turn's skill-selection attempt. `None` is a normal outcome, not an error. */
sealed interface SkillSelection {
    data class Selected(val skillKey: String) : SkillSelection
    data object None : SkillSelection
}

/**
 * Selects at most one Skill describing "what kind of interaction is
 * happening right now", separate from and after context assembly. See
 * RepositoryContextAssembler for Engine/Persona/Profile/Memory/Sensitive/
 * Continuity/History assembly — this stage never touches that.
 */
fun interface IntentDiscovery {
    fun selectSkill(request: ChatRequest, context: ChatContext): SkillSelection
}

/** Default when no Skill infrastructure is configured — chat proceeds exactly as before Phase B. */
object NoopIntentDiscovery : IntentDiscovery {
    override fun selectSkill(request: ChatRequest, context: ChatContext): SkillSelection = SkillSelection.None
}
