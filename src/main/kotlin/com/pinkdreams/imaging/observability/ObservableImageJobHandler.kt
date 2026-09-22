package com.pinkdreams.imaging.observability

import com.pinkdreams.imaging.job.ImageJob
import com.pinkdreams.imaging.job.ImageJobHandler
import com.pinkdreams.imaging.job.ImageJobResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

/**
 * Decorator that records image job outcomes without affecting handler result.
 */
class ObservableImageJobHandler(
    private val delegate: ImageJobHandler,
    private val eventRepository: ImageGenerationEventRepository,
    private val providerId: String,
    private val model: String?,
) : ImageJobHandler {

    private val json = Json { ignoreUnknownKeys = true }

    override fun handle(job: ImageJob): ImageJobResult {
        val started = LocalDateTime.now()
        val queueLatency = Duration.between(job.createdAt, started).toMillis().coerceAtLeast(0)
        val result = try {
            delegate.handle(job)
        } catch (e: Exception) {
            record(job, started, queueLatency, ImageJobResult.Failure(e.message ?: "handler exception", retryable = true))
            throw e
        }
        record(job, started, queueLatency, result)
        return result
    }

    private fun record(
        job: ImageJob,
        started: LocalDateTime,
        queueLatency: Long,
        result: ImageJobResult,
    ) {
        val total = Duration.between(started, LocalDateTime.now()).toMillis()
        val (outcome, errorClass, errorMessage, assetCount) = when (result) {
            is ImageJobResult.Success -> Quad("SUCCESS", null, null, parseAssetCount(result.metadata))
            is ImageJobResult.Failure -> Quad(
                "FAILURE",
                if (result.retryable) "RETRYABLE" else "PERMANENT",
                result.errorMessage,
                null,
            )
        }
        val attribution = extractAttribution(job.requestPayload)
        val resolvedModel = attribution.modelId ?: model
        eventRepository.record(
            ImageGenerationEvent(
                id = UUID.randomUUID(),
                imageJobId = job.id,
                turnRequestId = attribution.turnRequestId,
                conversationId = attribution.conversationId,
                personaId = attribution.personaId,
                personaVisualVersionId = job.personaVisualVersionId,
                provider = providerId,
                model = resolvedModel,
                attempt = job.attemptCount,
                outcome = outcome,
                errorClass = errorClass,
                errorMessage = errorMessage,
                queueLatencyMs = queueLatency,
                generationLatencyMs = total,
                downloadLatencyMs = null,
                persistenceLatencyMs = null,
                totalLatencyMs = queueLatency + total,
                assetCount = assetCount,
                createdAt = LocalDateTime.now(),
            )
        )
    }

    private fun extractAttribution(requestPayload: String): Attribution {
        return try {
            val root = json.parseToJsonElement(requestPayload).jsonObject
            val meta = root["metadata"]?.jsonObject
            fun uuidAt(vararg keys: String): UUID? {
                for (k in keys) {
                    val raw = meta?.get(k)?.jsonPrimitive?.content
                        ?: root[k]?.jsonPrimitive?.content
                    if (raw != null) {
                        return try {
                            UUID.fromString(raw)
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
                return null
            }
            Attribution(
                conversationId = uuidAt("conversationId"),
                turnRequestId = uuidAt("turnRequestId", "requestId"),
                personaId = uuidAt("personaId"),
                modelId = root["modelId"]?.jsonPrimitive?.content
                    ?: meta?.get("modelId")?.jsonPrimitive?.content,
            )
        } catch (_: Exception) {
            Attribution(null, null, null, null)
        }
    }

    private fun parseAssetCount(metadata: String): Int? {
        val match = Regex(""""candidateCount"\s*:\s*"?(\d+)""").find(metadata)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private data class Attribution(
        val conversationId: UUID?,
        val turnRequestId: UUID?,
        val personaId: UUID?,
        val modelId: String?,
    )

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
