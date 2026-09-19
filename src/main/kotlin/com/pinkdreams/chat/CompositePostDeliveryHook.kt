package com.pinkdreams.chat

import com.pinkdreams.chat.memory.CompletedTurn
import com.pinkdreams.chat.memory.PostDeliveryMemoryExtraction

/**
 * Combines multiple independent post-delivery hooks (memory extraction,
 * continuity summarization, ...) behind the single PostDeliveryMemoryExtraction
 * slot PipelineChatEngine already exposes — reusing that existing execution
 * pattern instead of adding a second hook parameter/framework to the pipeline.
 *
 * Each hook already isolates its own failures internally (see
 * BestEffortMemoryExtraction / BestEffortContinuitySummarization); this adds
 * one more layer of isolation so a defect in composing them, or in a hook that
 * somehow throws synchronously, can never prevent the others from running.
 */
class CompositePostDeliveryHook(
    private val hooks: List<PostDeliveryMemoryExtraction>,
) : PostDeliveryMemoryExtraction {
    override fun dispatch(turn: CompletedTurn) {
        hooks.forEach { hook ->
            try {
                hook.dispatch(turn)
            } catch (e: Exception) {
                System.err.println(
                    "POST_DELIVERY_HOOK: ${hook.javaClass.simpleName} failed for conversation=${turn.request.conversationId}: " +
                        "${e.javaClass.simpleName}: ${e.message}",
                )
            }
        }
    }
}
