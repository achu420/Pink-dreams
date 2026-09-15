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
) : PostDeliveryMemoryExtraction {
    override fun dispatch(turn: CompletedTurn) {
        CompletableFuture.runAsync({
            try {
                val result = extractor.extract(turn)
                val candidates = result.newFacts.map {
                    it.copy(source = "llm_extracted", tier = "hot")
                }
                memoryService.record(turn.request.userId, turn.request.personaId, candidates)
                if (result.referencedFactIds.isNotEmpty()) {
                    memoryService.markReferenced(
                        turn.request.userId,
                        turn.request.personaId,
                        result.referencedFactIds,
                        LocalDateTime.now(),
                    )
                }
            } catch (_: Exception) {
                // Extraction is best effort and must not affect the completed chat turn.
            }
        }, executor)
    }
}