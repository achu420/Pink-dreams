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
class MemoryEngineChangeApplier(private val repository: MemoryFactRepository) {

    data class ApplyOutcome(val applied: Int, val skipped: Int)

    fun apply(userId: UUID, personaId: UUID, owner: String, changes: List<MemoryChange>): ApplyOutcome {
        require(owner in MemoryService.OWNERS) { "Invalid memory owner: $owner" }
        var applied = 0
        var skipped = 0
        changes.forEach { change ->
            val ok = try {
                applyOne(userId, personaId, owner, change)
            } catch (e: Exception) {
                System.err.println("MEMORY_ENGINE: change application failed action=${change.action} owner=$owner: ${e.javaClass.simpleName}")
                false
            }
            if (ok) applied++ else skipped++
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

    /** Re-validates the target belongs to this exact user+persona+owner relationship before any mutation. */
    private fun ownedFact(userId: UUID, personaId: UUID, owner: String, memoryId: UUID?): MemoryFactRepository.MemoryFact? {
        if (memoryId == null) return null
        val fact = repository.findById(memoryId) ?: return null
        return fact.takeIf { it.userId == userId && it.personaId == personaId && it.owner == owner }
    }
}
