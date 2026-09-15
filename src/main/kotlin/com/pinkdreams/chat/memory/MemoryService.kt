package com.pinkdreams.chat.memory

import com.pinkdreams.persistence.repositories.MemoryFactRepository
import java.time.LocalDateTime
import java.util.UUID

data class MemoryCandidate(
    val fact: String,
    val factType: String,
    val criticality: String,
    val source: String = "llm_extracted",
    val tier: String = "hot",
)

class MemoryService(private val repository: MemoryFactRepository) {
    companion object {
        const val MAX_HOT_FACTS = 20
        const val DEFAULT_SELECTION_LIMIT = 10
        const val MAX_FACT_LENGTH = 240

        val FACT_TYPES = setOf(
            "past_event", "future_event", "mindset", "weakness", "aspiration",
            "desire", "habit", "want", "interest",
        )
        val CRITICALITIES = setOf("low", "medium", "high")
        val SOURCES = setOf("llm_extracted", "manual")
        val TIERS = setOf("hot", "cold")
    }

    fun record(userId: UUID, personaId: UUID, candidates: List<MemoryCandidate>): List<MemoryFactRepository.MemoryFact> {
        val normalized = candidates.map(::normalizeAndValidate)
        val existingHot = repository.findForRelationship(userId, personaId)
            .filter { it.tier == "hot" }
            .toMutableList()
        val accepted = mutableListOf<MemoryFactRepository.MemoryFact>()

        normalized.forEach { candidate ->
            if (existingHot.none { isDuplicate(it, candidate) }) {
                val created = repository.create(
                    userId = userId,
                    personaId = personaId,
                    fact = candidate.fact,
                    factType = candidate.factType,
                    criticality = candidate.criticality,
                    tier = candidate.tier,
                    source = candidate.source,
                )
                accepted += created
                if (created.tier == "hot") existingHot += created
                enforceHotCapacity(userId, personaId)
            }
        }
        return accepted
    }

    fun selectForContext(
        userId: UUID,
        personaId: UUID,
        limit: Int = DEFAULT_SELECTION_LIMIT,
    ): List<MemoryFactRepository.MemoryFact> {
        require(limit >= 0) { "Selection limit cannot be negative" }
        return repository.findForRelationship(userId, personaId)
            .filter { it.tier == "hot" }
            .sortedWith(selectionComparator())
            .take(limit)
    }

    fun markReferenced(
        userId: UUID,
        personaId: UUID,
        factIds: Collection<UUID>,
        referencedAt: LocalDateTime,
    ) {
        factIds.forEach { repository.markReferenced(userId, personaId, it, referencedAt) }
    }

    fun findForRelationship(userId: UUID, personaId: UUID): List<MemoryFactRepository.MemoryFact> =
        repository.findForRelationship(userId, personaId)

    private fun normalizeAndValidate(candidate: MemoryCandidate): MemoryCandidate {
        val fact = candidate.fact.trim().replace(Regex("\\s+"), " ")
        val factType = candidate.factType.trim().lowercase()
        val criticality = candidate.criticality.trim().lowercase()
        val source = candidate.source.trim().lowercase()
        val tier = candidate.tier.trim().lowercase()
        require(fact.isNotEmpty()) { "Memory fact cannot be empty" }
        require(fact.length <= MAX_FACT_LENGTH) { "Memory fact exceeds $MAX_FACT_LENGTH characters" }
        require(factType in FACT_TYPES) { "Invalid memory fact type: $factType" }
        require(criticality in CRITICALITIES) { "Invalid memory criticality: $criticality" }
        require(source in SOURCES) { "Invalid memory source: $source" }
        require(tier in TIERS) { "Invalid memory tier: $tier" }
        return MemoryCandidate(fact, factType, criticality, source, tier)
    }

    private fun isDuplicate(existing: MemoryFactRepository.MemoryFact, candidate: MemoryCandidate): Boolean =
        existing.factType == candidate.factType && comparable(existing.fact) == comparable(candidate.fact)

    private fun comparable(value: String): String = value
        .trim()
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trimEnd('.', '!', '?')

    private fun enforceHotCapacity(userId: UUID, personaId: UUID) {
        val hotFacts = repository.findForRelationship(userId, personaId).filter { it.tier == "hot" }
        hotFacts.sortedWith(evictionComparator())
            .take((hotFacts.size - MAX_HOT_FACTS).coerceAtLeast(0))
            .forEach { repository.moveToCold(userId, personaId, it.id) }
    }

    private fun rank(fact: MemoryFactRepository.MemoryFact): Int =
        fact.criticalityRank?.toInt() ?: when (fact.criticality) {
            "high" -> 3
            "medium" -> 2
            else -> 1
        }

    private fun selectionComparator(): Comparator<MemoryFactRepository.MemoryFact> = Comparator { left, right ->
        compareValuesBy(left, right,
            { if (it.status == "open") 0 else 1 },
            { -rank(it) },
        ).takeIf { it != 0 }
            ?: compareNullableDescending(left.lastReferencedAt, right.lastReferencedAt)
                .takeIf { it != 0 }
            ?: compareValuesBy(right, left, { it.learnedAt }, { it.id.toString() })
    }

    private fun evictionComparator(): Comparator<MemoryFactRepository.MemoryFact> = Comparator { left, right ->
        compareValuesBy(left, right,
            { if (it.status == "resolved") 0 else 1 },
            { rank(it) },
        ).takeIf { it != 0 }
            ?: compareNullableAscending(left.lastReferencedAt, right.lastReferencedAt)
                .takeIf { it != 0 }
            ?: compareValuesBy(left, right, { it.learnedAt }, { it.id.toString() })
    }

    private fun compareNullableAscending(left: LocalDateTime?, right: LocalDateTime?): Int = when {
        left == null && right == null -> 0
        left == null -> -1
        right == null -> 1
        else -> left.compareTo(right)
    }

    private fun compareNullableDescending(left: LocalDateTime?, right: LocalDateTime?): Int =
        compareNullableAscending(right, left)
}