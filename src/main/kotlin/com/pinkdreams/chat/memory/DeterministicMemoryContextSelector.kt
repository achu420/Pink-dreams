package com.pinkdreams.chat.memory

import com.pinkdreams.chat.skill.SkillSelection
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Deterministic, explainable memory relevance scoring (Phase C section 8).
 * No ML/embedding/vector infrastructure — a small set of bounded, testable
 * signals summed and sorted. Skill relevance is computed generically from the
 * SELECTED skill's own content text (word overlap against each candidate
 * fact) — never a per-skill-key special case (section 7) — so a newly
 * authored skill automatically participates without any code change here.
 */
class DeterministicMemoryContextSelector(
    private val skillRepository: SkillRepository,
    private val contextLimit: Int = MemoryService.DEFAULT_SELECTION_LIMIT,
) : MemoryContextSelector {

    data class ScoredMemory(
        val memory: MemoryFactRepository.MemoryFact,
        val contextRelevance: Int,
        val skillRelevance: Int,
        val criticality: Int,
        val recency: Int,
        val openness: Int,
    ) {
        val total: Int get() = contextRelevance + skillRelevance + criticality + recency + openness
    }

    override fun select(
        currentMessage: String,
        selectedSkill: SkillSelection,
        candidates: List<MemoryFactRepository.MemoryFact>,
    ): List<MemoryFactRepository.MemoryFact> {
        val skillContent = skillContentFor(selectedSkill)
        val messageTokens = tokenize(currentMessage)
        val skillTokens = tokenize(skillContent)

        return score(candidates, messageTokens, skillTokens)
            .sortedWith(
                compareByDescending<ScoredMemory> { it.total }
                    .thenByDescending { it.memory.learnedAt }
                    .thenBy { it.memory.id.toString() },
            )
            .take(contextLimit)
            .map { it.memory }
    }

    /** Exposed for tests/observability that want the score breakdown, not just the final subset. */
    fun score(
        candidates: List<MemoryFactRepository.MemoryFact>,
        messageTokens: Set<String>,
        skillTokens: Set<String>,
    ): List<ScoredMemory> = candidates.map { memory ->
        val factTokens = tokenize(memory.fact)
        ScoredMemory(
            memory = memory,
            contextRelevance = overlapScore(messageTokens, factTokens),
            skillRelevance = overlapScore(skillTokens, factTokens),
            criticality = criticalityScore(memory.criticality),
            recency = recencyScore(memory.lastReferencedAt ?: memory.learnedAt),
            openness = if (memory.status == "open") 1 else 0,
        )
    }

    private fun skillContentFor(selection: SkillSelection): String = when (selection) {
        is SkillSelection.Selected -> runCatching { skillRepository.getActiveForKey(selection.skillKey)?.content }.getOrNull() ?: ""
        SkillSelection.None -> ""
    }

    /** 0..3 — bounded word-overlap count between two token sets. */
    private fun overlapScore(a: Set<String>, b: Set<String>): Int = a.intersect(b).size.coerceAtMost(MAX_OVERLAP_SCORE)

    private fun criticalityScore(criticality: String): Int = when (criticality) {
        "high" -> 2
        "medium" -> 1
        else -> 0
    }

    /** 0..2 — more recent activity on this fact scores higher. */
    private fun recencyScore(reference: LocalDateTime): Int {
        val daysAgo = ChronoUnit.DAYS.between(reference, LocalDateTime.now())
        return when {
            daysAgo < 1 -> 2
            daysAgo < 7 -> 1
            else -> 0
        }
    }

    private fun tokenize(text: String): Set<String> = text
        .lowercase()
        .split(Regex("[^a-z0-9']+"))
        .filter { it.length >= 3 }
        .toSet()

    companion object {
        private const val MAX_OVERLAP_SCORE = 3
    }
}
