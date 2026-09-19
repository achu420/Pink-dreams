package com.pinkdreams.chat.memoryengine

import com.pinkdreams.chat.memory.CompletedTurn
import com.pinkdreams.chat.memory.MemoryScopeResolver
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.chat.memory.PostDeliveryMemoryExtraction
import com.pinkdreams.chat.memory.ProductionMemoryScope
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MemoryEngineRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool

/**
 * Best-effort, post-delivery, asynchronous Memory Engine batch maintenance.
 * Reuses the exact PostDeliveryMemoryExtraction interface/execution shape
 * already established for memory extraction and continuity summarization —
 * same isolation guarantees, no new execution framework.
 *
 * Deliberately lazy, mirroring BestEffortContinuitySummarization: does
 * nothing at all until a full, previously-unprocessed batch of [batchSize]
 * messages is available. The batch cursor (Conversations.memory_engine_
 * processed_count) is INDEPENDENT of continuity's own cursor — see
 * ConversationRepository.claimMemoryEngineBatch.
 *
 * Concurrency-safe: the batch is atomically claimed via a compare-and-swap
 * on the cursor BEFORE any LLM call or memory write, so two racing workers
 * can never both process the same batch. The tradeoff (documented, accepted):
 * if the LLM call fails AFTER a successful claim, that specific batch's
 * memory update is lost — it is not retried — trading a rare missed update
 * for a hard guarantee against duplicate memory writes.
 */
class BestEffortMemoryEngineMaintenance(
    private val maintainer: MemoryEngineMaintainer,
    private val applier: MemoryEngineChangeApplier,
    private val memoryService: MemoryService,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val workingMemoryTarget: Int = DEFAULT_WORKING_MEMORY_TARGET,
    private val executor: Executor = ForkJoinPool.commonPool(),
    // When supplied, the ACTIVE Memory Engine version's own configuration wins
    // over the constructor defaults — activating a version activates its
    // batch/target settings with it. Null (tests, or no active version) simply
    // falls back to the constructor values, so behavior is unchanged.
    private val memoryEngineRepository: MemoryEngineRepository? = null,
    // Phase ADMIN-3: see MemoryScopeResolver. Default preserves pre-ADMIN-3
    // behavior exactly for every existing caller.
    private val memoryScopeResolver: MemoryScopeResolver = ProductionMemoryScope,
) : PostDeliveryMemoryExtraction {
    override fun dispatch(turn: CompletedTurn) {
        CompletableFuture.runAsync({
            try {
                val scopedPersonaId = memoryScopeResolver.resolve(turn.request.userId, turn.request.personaId, turn.request.conversationId)
                val activeConfig = runCatching { memoryEngineRepository?.getActiveEngine() }.getOrNull()
                val effectiveBatchSize = activeConfig?.batchSize ?: batchSize
                val effectiveWorkingMemoryTarget = activeConfig?.relevantMemoryTarget ?: workingMemoryTarget

                val conversationId = turn.request.conversationId
                val conversation = conversationRepository.findById(conversationId) ?: return@runAsync
                val allMessages = messageRepository.findForConversation(conversationId) // oldest -> newest
                val alreadyProcessed = conversation.memoryEngineProcessedCount.coerceIn(0, allMessages.size)
                val available = allMessages.size - alreadyProcessed

                if (available < effectiveBatchSize) return@runAsync // wait for a full batch — never process a partial one automatically

                val batchEnd = alreadyProcessed + effectiveBatchSize
                val claimed = conversationRepository.claimMemoryEngineBatch(conversationId, alreadyProcessed, batchEnd)
                if (!claimed) return@runAsync // another worker already claimed this batch

                val batch = allMessages.subList(alreadyProcessed, batchEnd)
                val userWorkingMemory = memoryService.selectWorkingSet(turn.request.userId, scopedPersonaId, "USER", effectiveWorkingMemoryTarget)
                val personaWorkingMemory = memoryService.selectWorkingSet(turn.request.userId, scopedPersonaId, "PERSONA", effectiveWorkingMemoryTarget)

                val result = maintainer.maintain(turn, batch, userWorkingMemory, personaWorkingMemory)

                val userOutcome = applier.apply(turn.request.userId, scopedPersonaId, "USER", result.userMemoryChanges)
                val personaOutcome = applier.apply(turn.request.userId, scopedPersonaId, "PERSONA", result.personaMemoryChanges)

                System.err.println(
                    "MEMORY_ENGINE: conversation=$conversationId batch=[$alreadyProcessed,$batchEnd) " +
                        "userApplied=${userOutcome.applied} userSkipped=${userOutcome.skipped} " +
                        "personaApplied=${personaOutcome.applied} personaSkipped=${personaOutcome.skipped}",
                )
            } catch (e: Exception) {
                // Memory Engine maintenance is best-effort and must not affect the completed chat turn.
                System.err.println(
                    "MEMORY_ENGINE: maintenance failed for conversation=${turn.request.conversationId} " +
                        "requestId=${turn.request.requestId}: ${e.javaClass.simpleName}: ${e.message}",
                )
            }
        }, executor)
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 10
        const val DEFAULT_WORKING_MEMORY_TARGET = 20
    }
}
