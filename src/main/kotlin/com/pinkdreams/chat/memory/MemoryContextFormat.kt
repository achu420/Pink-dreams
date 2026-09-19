package com.pinkdreams.chat.memory

import com.pinkdreams.persistence.repositories.MemoryFactRepository

/**
 * Shared rendering for the "RETRIEVED MEMORY:" system block — used by both
 * RepositoryContextAssembler's default (skill-agnostic) memory block and the
 * Phase C skill-aware memory re-render (SkillAwareMemoryEnricher), so both
 * paths produce byte-identical formatting for the same fact list. Exposes
 * only factType/fact — never id, timestamps, status, or ranking metadata to
 * the LLM (see Phase C section 22).
 */
object MemoryContextFormat {
    fun render(facts: List<MemoryFactRepository.MemoryFact>): String = buildString {
        append("RETRIEVED MEMORY:\n")
        if (facts.isEmpty()) {
            append("(no memory facts for this user/persona relationship)\n")
        } else {
            facts.forEach { append(it.factType).append(": ").append(it.fact).append('\n') }
        }
    }
}
