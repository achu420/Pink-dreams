package com.pinkdreams.chat.memoryengine

import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import java.util.UUID

/**
 * Applies a MemoryMaintenanceResult to canonical storage. Every write is
 * scoped and re-validated against (userId, personaId, owner) here — never
 * trusting an LLM-supplied memoryId at face value — so a hallucinated or
 * malformed ID can only ever be silently skipped, never cross a user/persona
 * boundary. One invalid change never aborts the rest of the batch.
 */
class MemoryEngineChangeApplier(
    private val repository: MemoryFactRepository,
    // Task 25F fix 2: the ONE existing owner of the hot-capacity policy. Default
    // wraps the same repository, so no existing call site changes.
    private val memoryService: MemoryService = MemoryService(repository),
) {

    data class ApplyOutcome(val applied: Int, val skipped: Int)

    fun apply(userId: UUID, personaId: UUID, owner: String, changes: List<MemoryChange>): ApplyOutcome {
        require(owner in MemoryService.OWNERS) { "Invalid memory owner: $owner" }
        var applied = 0
        var skipped = 0
        var createdHotFact = false
        changes.forEach { change ->
            val ok = try {
                applyOne(userId, personaId, owner, change)
            } catch (e: Exception) {
                System.err.println("MEMORY_ENGINE: change application failed action=${change.action} owner=$owner: ${e.javaClass.simpleName}")
                false
            }
            if (ok) applied++ else skipped++
            if (ok && change.action in TIER_AFFECTING_ACTIONS) createdHotFact = true
        }
        // Task 25F fix 2: this applier writes through MemoryFactRepository
        // directly, bypassing MemoryService.record() — the only place
        // MAX_HOT_FACTS was ever enforced. A real relationship therefore reached
        // 22 live hot facts (15 llm_extracted + 7 memory_engine) against the
        // 20-slot limit. Enforce the SAME existing policy after a batch that
        // added hot rows, rather than restating the limit here: the threshold,
        // the eviction comparator and the Memory Engine's own ADD/UPDATE/
        // SUPERSEDE/REMOVE/KEEP/IGNORE decisions are all unchanged. Once per
        // batch, not per change — capacity is a property of the resulting set,
        // and eviction reads the post-batch state either way. Best-effort, in
        // keeping with every other write in this class: a failure here must not
        // turn applied changes into a reported failure.
        if (createdHotFact) {
            try {
                memoryService.enforceHotCapacity(userId, personaId)
            } catch (e: Exception) {
                System.err.println("MEMORY_ENGINE: hot capacity enforcement failed: ${e.javaClass.simpleName}")
            }
        }
        return ApplyOutcome(applied, skipped)
    }

    private fun applyOne(userId: UUID, personaId: UUID, owner: String, change: MemoryChange): Boolean = when (change.action) {
        MemoryChangeAction.ADD -> {
            val content = change.content?.trim()
            val type = change.memoryType?.trim()?.lowercase()
            if (content.isNullOrBlank() || content.length > MemoryService.MAX_FACT_LENGTH || type !in MemoryService.FACT_TYPES) {
                false
            } else {
                val criticality = change.criticality?.trim()?.lowercase()?.takeIf { it in MemoryService.CRITICALITIES } ?: "medium"
                repository.create(
                    userId = userId,
                    personaId = personaId,
                    fact = content,
                    factType = type!!,
                    criticality = criticality,
                    tier = "hot",
                    status = "open",
                    source = "memory_engine",
                    owner = owner,
                )
                true
            }
        }
        MemoryChangeAction.UPDATE -> {
            val target = ownedFact(userId, personaId, owner, change.memoryId)
            val content = change.content?.trim()
            if (target == null || content.isNullOrBlank() || content.length > MemoryService.MAX_FACT_LENGTH) {
                false
            } else {
                repository.updateContent(target.id, content)
                true
            }
        }
        MemoryChangeAction.SUPERSEDE -> {
            val target = ownedFact(userId, personaId, owner, change.memoryId)
            val content = change.content?.trim()
            val type = change.memoryType?.trim()?.lowercase()
            if (target == null || content.isNullOrBlank() || content.length > MemoryService.MAX_FACT_LENGTH || (type != null && type !in MemoryService.FACT_TYPES)) {
                false
            } else {
                val criticality = change.criticality?.trim()?.lowercase()?.takeIf { it in MemoryService.CRITICALITIES }
                repository.supersede(target.id, content, factType = type, criticality = criticality, owner = owner)
                true
            }
        }
        MemoryChangeAction.REMOVE -> {
            val target = ownedFact(userId, personaId, owner, change.memoryId)
            if (target == null) {
                false
            } else {
                repository.remove(target.id)
                true
            }
        }
        MemoryChangeAction.KEEP, MemoryChangeAction.IGNORE -> true // explicit no-op, not a failure
    }

    private companion object {
        // ADD inserts a new hot row; SUPERSEDE inserts a replacement row at the
        // target's tier (which is hot for any fact the engine can see). UPDATE,
        // REMOVE, KEEP and IGNORE never add to the hot set.
        val TIER_AFFECTING_ACTIONS = setOf(MemoryChangeAction.ADD, MemoryChangeAction.SUPERSEDE)
    }

    /** Re-validates the target belongs to this exact user+persona+owner relationship before any mutation. */
    private fun ownedFact(userId: UUID, personaId: UUID, owner: String, memoryId: UUID?): MemoryFactRepository.MemoryFact? {
        if (memoryId == null) return null
        val fact = repository.findById(memoryId) ?: return null
        return fact.takeIf { it.userId == userId && it.personaId == personaId && it.owner == owner }
    }
}
