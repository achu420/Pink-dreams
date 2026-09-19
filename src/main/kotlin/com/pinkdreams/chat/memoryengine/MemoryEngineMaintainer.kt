package com.pinkdreams.chat.memoryengine

import com.pinkdreams.chat.memory.CompletedTurn
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.MessageRepository

/**
 * Decides how memory should evolve given a batch of conversation messages and
 * the current working memory for both owners. Pure decision — no repository
 * writes happen here (see MemoryEngineChangeApplier for that), matching the
 * existing MemoryExtractor/ContinuitySummarizer shape (decide, then a
 * separate step applies). Takes the same CompletedTurn already used by
 * MemoryExtractor/ContinuitySummarizer so implementations can reuse
 * turn.context.engineVersionId/personaCoreVersionId for GenerationRequest's
 * provenance fields, exactly as LlmMemoryExtractor already does — this is not
 * real generation provenance, just satisfying that side-channel requirement.
 */
fun interface MemoryEngineMaintainer {
    fun maintain(
        turn: CompletedTurn,
        batch: List<MessageRepository.Message>,
        userWorkingMemory: List<MemoryFactRepository.MemoryFact>,
        personaWorkingMemory: List<MemoryFactRepository.MemoryFact>,
    ): MemoryMaintenanceResult
}
