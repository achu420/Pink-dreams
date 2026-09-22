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

        // Original (pre-Phase-D) taxonomy, unchanged — used for USER-owned
        // memory extracted from ordinary conversation. "preference"-shaped
        // content maps onto the existing "interest" type per the extraction
        // prompt's own established convention; it is NOT a new type.
        val USER_FACT_TYPES = setOf(
            "past_event", "future_event", "mindset", "weakness", "aspiration",
            "desire", "habit", "want", "interest",
        )
        // Phase D additions — PERSONA-owned memory has no equivalent in the
        // original taxonomy (it was never designed to represent what the
        // persona itself said/promised), so these four are genuinely new.
        val PERSONA_FACT_TYPES = setOf("relationship", "commitment", "promise", "interaction_context")
        // Superset accepted by validation — additive only; every value ever
        // valid before Phase D remains valid.
        val FACT_TYPES = USER_FACT_TYPES + PERSONA_FACT_TYPES
        val CRITICALITIES = setOf("low", "medium", "high")
        val SOURCES = setOf("llm_extracted", "manual", "memory_engine")
        val TIERS = setOf("hot", "cold")
        // "USER" | "PERSONA" — see MemoryFactRepository.MemoryFact.owner.
        val OWNERS = setOf("USER", "PERSONA")
        // Statuses that must never be selectable into any context — superseded
        // history and removed facts remain in the table (never deleted) but are
        // dead for retrieval purposes.
        private val INACTIVE_STATUSES = setOf("superseded", "removed")
    }

    fun record(userId: UUID, personaId: UUID, candidates: List<MemoryCandidate>): List<MemoryFactRepository.MemoryFact> {
        val normalized = candidates.map(::normalizeAndValidate)
        // Task 25F fix 3: duplicate detection used to look at HOT facts only, so
        // once a fact was evicted to cold the extractor could re-propose it and
        // a brand-new hot row was created — observed live as "user has a best
        // friend" existing three times, all cold: created, evicted, recreated,
        // evicted, recreated. The dedup criteria are deliberately UNCHANGED
        // (same factType + same normalized text, exact match only — no fuzzy or
        // semantic equivalence, and never across factTypes); only the set they
        // are applied to is widened to the whole relationship. A cold duplicate
        // is treated exactly as a hot one always was — the candidate is dropped
        // — rather than promoting the cold row back to hot, which would be a new
        // retrieval policy this code never expressed.
        val existing = repository.findForRelationship(userId, personaId).toMutableList()
        val accepted = mutableListOf<MemoryFactRepository.MemoryFact>()

        normalized.forEach { candidate ->
            if (existing.none { isDuplicate(it, candidate) }) {
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
                existing += created
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
            .filter { it.tier == "hot" && it.status !in INACTIVE_STATUSES }
            .sortedWith(selectionComparator())
            .take(limit)
    }

    /**
     * The Memory Engine's own "current working memory" input for a single
     * owner (USER or PERSONA) — the same hot/active fact pool selectForContext
     * draws from, filtered to one owner. This is a DERIVED view, not a
     * separately persisted working-memory table (see Phase D completion
     * report section on working-memory storage): tier="hot" + status not in
     * {superseded, removed} already IS the working set.
     */
    fun selectWorkingSet(
        userId: UUID,
        personaId: UUID,
        owner: String,
        limit: Int = DEFAULT_SELECTION_LIMIT,
    ): List<MemoryFactRepository.MemoryFact> {
        require(owner in OWNERS) { "Invalid memory owner: $owner" }
        return repository.findForRelationship(userId, personaId)
            .filter { it.tier == "hot" && it.status !in INACTIVE_STATUSES && it.owner == owner }
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

    /**
     * Task 25F fix 2: MAX_HOT_FACTS must hold regardless of which code path
     * created a hot fact. [record] (the extraction path) has always enforced it,
     * but MemoryEngineChangeApplier writes to MemoryFactRepository directly, so
     * its ADD/SUPERSEDE rows never counted — leaving 22 live hot facts on one
     * real relationship against a limit of 20. Exposed (rather than duplicated)
     * so there is still exactly ONE capacity policy in the codebase.
     */
    fun enforceHotCapacity(userId: UUID, personaId: UUID) {
        // Only facts that can actually be SELECTED occupy hot capacity. A
        // superseded/removed row keeps tier="hot" (neither supersede() nor
        // remove() touches the tier — history is preserved in place), but it is
        // permanently unselectable: selectForContext/selectWorkingSet both
        // exclude INACTIVE_STATUSES. Counting such dead rows against
        // MAX_HOT_FACTS therefore evicted *live, selectable* memory to cold to
        // make room for rows that can never be used again — observed live on
        // the largest real relationship (17 live + 3 removed rows filling the
        // 20-slot budget exactly, with 28 facts already pushed to cold).
        // The threshold itself is unchanged; only which rows are counted is.
        val hotFacts = repository.findForRelationship(userId, personaId)
            .filter { it.tier == "hot" && it.status !in INACTIVE_STATUSES }
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