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
        CompletableFuture.runAsync({
            try {
                val scopedPersonaId = memoryScopeResolver.resolve(turn.request.userId, turn.request.personaId, turn.request.conversationId)
                val result = extractor.extract(turn)
                val candidates = result.newFacts.map {
                    it.copy(source = "llm_extracted", tier = "hot")
                }
                val accepted = memoryService.record(turn.request.userId, scopedPersonaId, candidates)
                // Log the success path too, not only failures: "extracted nothing"
                // and "the hook never ran" are indistinguishable otherwise, which
                // makes a silently empty memory store impossible to diagnose.
                System.err.println(
                    "MEMORY_EXTRACTION: conversation=${turn.request.conversationId} " +
                        "requestId=${turn.request.requestId} proposed=${candidates.size} accepted=${accepted.size}",
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
}