package com.pinkdreams.chat.memoryengine

import java.util.UUID

/** memoryOwner — kept as a top-level string constant pair rather than an enum to reuse MemoryService.OWNERS directly. */
enum class MemoryChangeAction { ADD, UPDATE, SUPERSEDE, REMOVE, KEEP, IGNORE }

/**
 * One decision the Memory Engine made about a single memory. Not every field
 * applies to every action — see MemoryEngineChangeApplier for exactly which
 * fields each action consumes. sourceMessageIndexes/reason are accepted from
 * the LLM for grounding but are not persisted (no corresponding DB columns);
 * they may appear in diagnostic logs only, never sensitive content.
 */
data class MemoryChange(
    val action: MemoryChangeAction,
    val memoryId: UUID? = null,
    val memoryType: String? = null,
    val content: String? = null,
    val criticality: String? = null,
)

/** The Memory Engine's full decision for one maintenance batch, split by owner per Phase D's mandatory USER/PERSONA separation. */
data class MemoryMaintenanceResult(
    val userMemoryChanges: List<MemoryChange> = emptyList(),
    val personaMemoryChanges: List<MemoryChange> = emptyList(),
)
