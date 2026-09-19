package com.pinkdreams.chat.memory

import com.pinkdreams.chat.skill.SkillSelection
import com.pinkdreams.persistence.repositories.MemoryFactRepository

/**
 * Selects the subset of CANDIDATE memories to actually inject into this
 * turn's context (Phase C). Never touches canonical storage — "not selected"
 * means "not sent this turn," not "deleted." Implementations must be
 * deterministic for the same inputs (see DeterministicMemoryContextSelector);
 * an LLM-backed implementation is an explicitly-deferred future option (see
 * chat/memory/README notes in the Phase C completion report), not required now.
 */
fun interface MemoryContextSelector {
    fun select(
        currentMessage: String,
        selectedSkill: SkillSelection,
        candidates: List<MemoryFactRepository.MemoryFact>,
    ): List<MemoryFactRepository.MemoryFact>
}
