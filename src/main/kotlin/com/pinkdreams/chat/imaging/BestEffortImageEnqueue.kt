package com.pinkdreams.chat.imaging

import com.pinkdreams.chat.memory.CompletedTurn
import com.pinkdreams.chat.memory.PostDeliveryMemoryExtraction
import com.pinkdreams.imaging.orchestration.ImageGenerationService
import com.pinkdreams.persistence.repositories.MessageRepository
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool

/**
 * Best-effort post-delivery image enqueue.
 *
 * Does NOT block the chat reply. Runs after delivery the same way memory
 * extraction does. Failures never affect [ChatResult.Success].
 *
 * Detection: text heuristic only (no second intent engine). When an
 * image-capable skill key is added later, [looksLikeImageRequest] can be
 * extended to also accept that key from [CompletedTurn.context.selectedSkillKey].
 */
class BestEffortImageEnqueue(
    private val imageGenerationService: ImageGenerationService,
    private val messageRepository: MessageRepository,
    private val executor: Executor = ForkJoinPool.commonPool(),
) : PostDeliveryMemoryExtraction {

    override fun dispatch(turn: CompletedTurn) {
        if (!looksLikeImageRequest(turn.request.content, turn.context.selectedSkillKey)) return
        CompletableFuture.runAsync({
            try {
                val result = imageGenerationService.create(
                    ImageGenerationService.CreateCommand(
                        personaId = turn.request.personaId,
                        idempotencyKey = "chat-${turn.request.requestId}",
                        presentation = turn.request.content.take(200),
                        location = null,
                        conversationId = turn.request.conversationId,
                        turnRequestId = turn.request.requestId,
                        userId = turn.request.userId,
                        candidateCount = 1,
                        widthPx = 512,
                        heightPx = 512,
                    )
                )
                try {
                    messageRepository.mergeMetadata(
                        turn.response.assistantMessageId,
                        mapOf(
                            "imageJobId" to result.job.id.toString(),
                            "imageJobStatus" to result.job.status.name,
                        ),
                    )
                } catch (e: Exception) {
                    System.err.println(
                        "IMAGE_ENQUEUE: metadata merge failed conversation=${turn.request.conversationId}: ${e.message}",
                    )
                }
                System.err.println(
                    "IMAGE_ENQUEUE: conversation=${turn.request.conversationId} requestId=${turn.request.requestId} " +
                        "jobId=${result.job.id} reused=${result.reusedExisting}",
                )
            } catch (e: Exception) {
                System.err.println(
                    "IMAGE_ENQUEUE: failed conversation=${turn.request.conversationId}: " +
                        "${e.javaClass.simpleName}: ${e.message}",
                )
            }
        }, executor)
    }

    companion object {
        private val IMAGE_PHRASES = listOf(
            "show me",
            "send a pic",
            "send a photo",
            "send pic",
            "send photo",
            "selfie",
            "picture of you",
            "photo of you",
            "pic of you",
            "your picture",
            "your photo",
            "generate an image",
            "draw yourself",
        )

        fun looksLikeImageRequest(content: String, selectedSkillKey: String?): Boolean {
            if (selectedSkillKey != null && selectedSkillKey.equals("image_share", ignoreCase = true)) {
                return true
            }
            val lower = content.lowercase()
            return IMAGE_PHRASES.any { lower.contains(it) }
        }
    }
}
