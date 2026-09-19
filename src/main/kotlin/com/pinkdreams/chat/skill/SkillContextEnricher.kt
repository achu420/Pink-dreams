package com.pinkdreams.chat.skill

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.context.CharacterTokenEstimator
import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.chat.context.TokenEstimator
import com.pinkdreams.persistence.repositories.SkillRepository

/**
 * Injects the selected Skill (if any) as its OWN system ContextBlock —
 * never merged into Persona Core, Conversation Engine, or Memory content —
 * positioned after Continuity and before native history, per the Phase B
 * context ordering:
 *
 *   Engine+Persona, Profile, Memory, Sensitive Preferences, Continuity,
 *   Skill, History, Current Message.
 *
 * The insertion point is found structurally (first non-"system"-role block),
 * not by a hardcoded index — this stays correct regardless of which optional
 * leading blocks RepositoryContextAssembler included for this turn, without
 * requiring any change to ChatContext or the assembler itself.
 *
 * Budget: reuses RepositoryContextAssembler's own token budget/estimator
 * rather than a second mechanism. The assembler already fits its own blocks
 * (including the current message) under budget before this runs; adding the
 * Skill block on top is therefore checked here, and if it would push the
 * total over budget, the ENTIRE skill block is dropped (never partially
 * truncated) — current message and everything the assembler already decided
 * to keep are left untouched.
 */
class SkillContextEnricher(
    private val skillRepository: SkillRepository,
    private val tokenBudget: Int = RepositoryContextAssembler.DEFAULT_TOKEN_BUDGET,
    private val tokenEstimator: TokenEstimator = CharacterTokenEstimator,
) {
    fun enrich(context: ChatContext, selection: SkillSelection): ChatContext {
        val skillKey = (selection as? SkillSelection.Selected)?.skillKey ?: return context
        val skill = skillRepository.getActiveForKey(skillKey) ?: return context

        val skillBlock = ContextBlock("system", skillContent(skill))
        val currentTotal = context.blocks.sumOf { tokenEstimator.estimate(it.content) }
        if (currentTotal + tokenEstimator.estimate(skillBlock.content) > tokenBudget) {
            // Drop the whole block under budget pressure — never inject a partial skill.
            return context
        }

        val insertionIndex = context.blocks.indexOfFirst { it.role != "system" }
            .let { if (it == -1) context.blocks.size else it }
        val newBlocks = context.blocks.toMutableList().apply { add(insertionIndex, skillBlock) }
        return context.copy(blocks = newBlocks)
    }

    private fun skillContent(skill: SkillRepository.Skill): String =
        "SELECTED SKILL (${skill.key}):\n${skill.content}"
}
