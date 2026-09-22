package com.pinkdreams.chat.memory

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.PersistedResponse
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool

data class CompletedTurn(
    val request: ChatRequest,
    val context: ChatContext,
    val response: PersistedResponse,
)

data class ExtractionResult(
    val newFacts: List<MemoryCandidate>,
    val referencedFactIds: List<UUID> = emptyList(),
)

fun interface MemoryExtractor {
    fun extract(turn: CompletedTurn): ExtractionResult
}

fun interface PostDeliveryMemoryExtraction {
    fun dispatch(turn: CompletedTurn)
}

object NoopPostDeliveryMemoryExtraction : PostDeliveryMemoryExtraction {
    override fun dispatch(turn: CompletedTurn) = Unit
}

class BestEffortMemoryExtraction(
    private val extractor: MemoryExtractor,
    private val memoryService: MemoryService,
    private val executor: Executor = ForkJoinPool.commonPool(),
    // Phase ADMIN-3: see MemoryScopeResolver. Default preserves pre-ADMIN-3
    // behavior exactly for every existing caller.
    private val memoryScopeResolver: MemoryScopeResolver = ProductionMemoryScope,
) : PostDeliveryMemoryExtraction {
    override fun dispatch(turn: CompletedTurn) {
        // Runtime Quality + Latency Verification phase: dispatchedAtNanos is
        // captured on the CALLING thread, before handing off to the executor —
        // this is what proves the user-facing response never waited for this
        // work: the caller (PipelineChatEngine) returns immediately after this
        // method returns, and this method returns before extraction even starts.
        val dispatchedAtNanos = System.nanoTime()
        CompletableFuture.runAsync({
            val startedAtNanos = System.nanoTime()
            val queueDelayMs = (startedAtNanos - dispatchedAtNanos) / 1_000_000
            try {
                val scopedPersonaId = memoryScopeResolver.resolve(turn.request.userId, turn.request.personaId, turn.request.conversationId)
                // Task 25F fix 4: the write side of memory-reference tracking was
                // structurally dead — MemoryFactRepository.markReferenced existed
                // and MemoryService.markReferenced existed, but the only caller
                // was gated on ExtractionResult.referencedFactIds, which no
                // MemoryExtractor ever populates. Result: 0 of 280 real rows had
                // last_referenced_at set, so the eviction comparator's
                // "keep recently used memory" tiebreak and the selector's recency
                // term both silently degraded to learnedAt alone.
                //
                // The facts actually INJECTED into this turn's prompt are already
                // known: ChatContext.memoryIdsUsed (recorded for Task 24's turn
                // attribution). Marking those is the originally intended write —
                // no new ranking algorithm, no new query on the hot path, and no
                // change to the comparator/selector that consume the signal.
                // Runs here, on the existing best-effort async extraction path,
                // and in its own try/catch so a fact that is no longer hot (a
                // repository precondition) cannot abort extraction itself. Done
                // BEFORE record(), so this turn's own eviction cannot race it.
                markInjectedAsReferenced(turn, scopedPersonaId)
                val result = extractor.extract(turn)
                val candidates = result.newFacts.map {
                    it.copy(source = "llm_extracted", tier = "hot")
                }
                val accepted = memoryService.record(turn.request.userId, scopedPersonaId, candidates)
                val durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000
                // Log the success path too, not only failures: "extracted nothing"
                // and "the hook never ran" are indistinguishable otherwise, which
                // makes a silently empty memory store impossible to diagnose.
                System.err.println(
                    "MEMORY_EXTRACTION: conversation=${turn.request.conversationId} " +
                        "requestId=${turn.request.requestId} proposed=${candidates.size} accepted=${accepted.size} " +
                        "queueDelayMs=$queueDelayMs durationMs=$durationMs",
                )
                if (result.referencedFactIds.isNotEmpty()) {
                    memoryService.markReferenced(
                        turn.request.userId,
                        scopedPersonaId,
                        result.referencedFactIds,
                        LocalDateTime.now(),
                    )
                }
            } catch (e: Exception) {
                // Extraction is best effort and must not affect the completed chat turn.
                System.err.println(
                    "MEMORY_EXTRACTION: Extraction failed for conversation=${turn.request.conversationId} " +
                        "requestId=${turn.request.requestId}: ${e.javaClass.simpleName}: ${e.message}",
                )
            }
        }, executor)
    }

    private fun markInjectedAsReferenced(turn: CompletedTurn, scopedPersonaId: UUID) {
        val injected = turn.context.memoryIdsUsed.orEmpty()
        if (injected.isEmpty()) return
        val now = LocalDateTime.now()
        injected.forEach { factId ->
            try {
                memoryService.markReferenced(turn.request.userId, scopedPersonaId, listOf(factId), now)
            } catch (e: Exception) {
                // Per-fact isolation: a fact that was evicted or removed between
                // selection and now is simply not marked, and never affects the
                // rest of the set or the extraction that follows.
                System.err.println(
                    "MEMORY_REFERENCE: could not mark fact as referenced conversation=${turn.request.conversationId}: ${e.javaClass.simpleName}",
                )
            }
        }
    }
}