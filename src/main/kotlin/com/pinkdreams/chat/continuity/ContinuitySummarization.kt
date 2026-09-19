package com.pinkdreams.chat.continuity

import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.memory.CompletedTurn
import com.pinkdreams.chat.memory.PostDeliveryMemoryExtraction
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool

/**
 * Produces an updated continuity summary given the previous summary (if any)
 * and the older messages that have just scrolled outside the active
 * recent-history window. Returning null/blank means "no update needed".
 */
fun interface ContinuitySummarizer {
    fun summarize(turn: CompletedTurn, previousSummary: String?, olderMessages: List<ContextBlock>): String?
}

/**
 * Best-effort, post-delivery, asynchronous continuity summarization — reuses
 * the exact PostDeliveryMemoryExtraction interface/execution pattern already
 * established for memory extraction (same shape, same isolation guarantees),
 * rather than inventing a second execution framework.
 *
 * Deliberately lazy: it only does any work (including calling the summarizer)
 * when the conversation has actually grown past the active history window AND
 * there are messages beyond what was already folded into the summary. Short
 * conversations that never exceed the window trigger zero work here, ever.
 */
class BestEffortContinuitySummarization(
    private val summarizer: ContinuitySummarizer,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val activeWindowSize: Int,
    private val maxSummaryLength: Int = DEFAULT_MAX_SUMMARY_LENGTH,
    private val executor: Executor = ForkJoinPool.commonPool(),
) : PostDeliveryMemoryExtraction {
    override fun dispatch(turn: CompletedTurn) {
        CompletableFuture.runAsync({
            try {
                val conversationId = turn.request.conversationId
                val conversation = conversationRepository.findById(conversationId) ?: return@runAsync
                val allMessages = messageRepository.findForConversation(conversationId) // oldest -> newest
                val activeWindowStart = (allMessages.size - activeWindowSize).coerceAtLeast(0)
                val alreadyCovered = conversation.continuitySummaryCoveredCount.coerceIn(0, allMessages.size)

                if (alreadyCovered >= activeWindowStart) return@runAsync // nothing new has left the window yet

                val newlyOutOfWindow = allMessages.subList(alreadyCovered, activeWindowStart)
                if (newlyOutOfWindow.isEmpty()) return@runAsync

                val olderBlocks = newlyOutOfWindow.map { ContextBlock(it.role, it.content) }
                val updated = summarizer.summarize(turn, conversation.continuitySummary, olderBlocks)

                val boundedSummary = (updated?.trim().takeUnless { it.isNullOrBlank() } ?: conversation.continuitySummary ?: "")
                    .take(maxSummaryLength)

                // Always advance the covered pointer even if the summarizer returned
                // nothing new, so the same already-processed messages are never
                // re-sent to the summarizer on a later turn.
                conversationRepository.updateContinuitySummary(conversationId, boundedSummary, activeWindowStart)
            } catch (e: Exception) {
                System.err.println(
                    "CONTINUITY_SUMMARIZATION: Failed for conversation=${turn.request.conversationId} " +
                        "requestId=${turn.request.requestId}: ${e.javaClass.simpleName}: ${e.message}",
                )
            }
        }, executor)
    }

    companion object {
        const val DEFAULT_MAX_SUMMARY_LENGTH = 800
    }
}
