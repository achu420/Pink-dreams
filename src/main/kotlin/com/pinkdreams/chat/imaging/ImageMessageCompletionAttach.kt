package com.pinkdreams.chat.imaging

import com.pinkdreams.imaging.job.ImageJob
import com.pinkdreams.imaging.orchestration.GeneratedCandidateRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * After a worker completes (or permanently fails) an image job, best-effort
 * merge of status + asset IDs onto assistant messages that already carry
 * [imageJobId] (written by [BestEffortImageEnqueue]).
 *
 * Failures are logged and never affect job terminal status.
 */
class ImageMessageCompletionAttach(
    private val messageRepository: MessageRepository,
    private val candidateRepository: GeneratedCandidateRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun onSucceeded(job: ImageJob) {
        attach(
            job = job,
            status = "SUCCEEDED",
            includeAssets = true,
        )
    }

    fun onFailed(job: ImageJob) {
        attach(
            job = job,
            status = "FAILED",
            includeAssets = false,
        )
    }

    private fun attach(job: ImageJob, status: String, includeAssets: Boolean) {
        try {
            val conversationId = extractConversationId(job.requestPayload) ?: return
            val jobIdStr = job.id.toString()
            val messages = messageRepository.findForConversation(conversationId)
                .filter { it.role == "assistant" && it.metadata.contains(jobIdStr) }
            if (messages.isEmpty()) return

            val additions = mutableMapOf(
                "imageJobId" to jobIdStr,
                "imageJobStatus" to status,
            )
            if (includeAssets) {
                val assetIds = candidateRepository.findByImageJob(job.id).map { it.id.toString() }
                if (assetIds.isNotEmpty()) {
                    additions["imageAssetIds"] = assetIds.joinToString(",")
                    additions["imageAssetUrls"] = assetIds.joinToString(",") { "/v1/images/assets/$it" }
                }
            }
            for (message in messages) {
                try {
                    messageRepository.mergeMetadata(message.id, additions)
                } catch (e: Exception) {
                    System.err.println(
                        "IMAGE_COMPLETE_ATTACH: merge failed message=${message.id} job=${job.id}: ${e.message}",
                    )
                }
            }
        } catch (e: Exception) {
            System.err.println(
                "IMAGE_COMPLETE_ATTACH: failed job=${job.id}: ${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    private fun extractConversationId(requestPayload: String): UUID? {
        return try {
            val root = json.parseToJsonElement(requestPayload).jsonObject
            val meta = root["metadata"]?.jsonObject
            val raw = meta?.get("conversationId")?.jsonPrimitive?.content
                ?: root["conversationId"]?.jsonPrimitive?.content
            raw?.let(UUID::fromString)
        } catch (_: Exception) {
            null
        }
    }
}
