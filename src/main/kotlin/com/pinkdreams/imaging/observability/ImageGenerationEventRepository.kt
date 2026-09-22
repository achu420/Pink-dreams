package com.pinkdreams.imaging.observability

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import java.time.LocalDateTime
import java.util.UUID

object ImageGenerationEvents : Table("image_generation_events") {
    val id = uuid("id")
    val imageJobId = uuid("image_job_id")
    val turnRequestId = uuid("turn_request_id").nullable()
    val conversationId = uuid("conversation_id").nullable()
    val personaId = uuid("persona_id").nullable()
    val personaVisualVersionId = uuid("persona_visual_version_id").nullable()
    val provider = varchar("provider", 64).nullable()
    val model = varchar("model", 255).nullable()
    val attempt = integer("attempt").default(0)
    val outcome = varchar("outcome", 32)
    val errorClass = varchar("error_class", 64).nullable()
    val errorMessage = text("error_message").nullable()
    val queueLatencyMs = long("queue_latency_ms").nullable()
    val generationLatencyMs = long("generation_latency_ms").nullable()
    val downloadLatencyMs = long("download_latency_ms").nullable()
    val persistenceLatencyMs = long("persistence_latency_ms").nullable()
    val totalLatencyMs = long("total_latency_ms").nullable()
    val assetCount = integer("asset_count").nullable()
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

data class ImageGenerationEvent(
    val id: UUID,
    val imageJobId: UUID,
    val turnRequestId: UUID?,
    val conversationId: UUID?,
    val personaId: UUID?,
    val personaVisualVersionId: UUID?,
    val provider: String?,
    val model: String?,
    val attempt: Int,
    val outcome: String,
    val errorClass: String?,
    val errorMessage: String?,
    val queueLatencyMs: Long?,
    val generationLatencyMs: Long?,
    val downloadLatencyMs: Long?,
    val persistenceLatencyMs: Long?,
    val totalLatencyMs: Long?,
    val assetCount: Int?,
    val createdAt: LocalDateTime,
)

/**
 * Best-effort image observability. Persistence failures must never break
 * the user-facing image path — callers should swallow exceptions from [record].
 */
class ImageGenerationEventRepository(private val db: Database) {

    fun record(event: ImageGenerationEvent): Boolean = try {
        transaction(db) {
            ImageGenerationEvents.insert {
                it[id] = event.id
                it[imageJobId] = event.imageJobId
                it[turnRequestId] = event.turnRequestId
                it[conversationId] = event.conversationId
                it[personaId] = event.personaId
                it[personaVisualVersionId] = event.personaVisualVersionId
                it[provider] = event.provider
                it[model] = event.model
                it[attempt] = event.attempt
                it[outcome] = event.outcome
                it[errorClass] = event.errorClass
                it[errorMessage] = redactSecrets(event.errorMessage)
                it[queueLatencyMs] = event.queueLatencyMs
                it[generationLatencyMs] = event.generationLatencyMs
                it[downloadLatencyMs] = event.downloadLatencyMs
                it[persistenceLatencyMs] = event.persistenceLatencyMs
                it[totalLatencyMs] = event.totalLatencyMs
                it[assetCount] = event.assetCount
                it[createdAt] = event.createdAt
            }
        }
        true
    } catch (_: Exception) {
        false
    }

    fun findByJob(imageJobId: UUID): List<ImageGenerationEvent> = transaction(db) {
        ImageGenerationEvents.select { ImageGenerationEvents.imageJobId eq imageJobId }
            .map(::rowToModel)
    }

    fun findRecent(limit: Int = 100): List<ImageGenerationEvent> = transaction(db) {
        ImageGenerationEvents.selectAll()
            .orderBy(ImageGenerationEvents.createdAt to SortOrder.DESC)
            .limit(limit)
            .map(::rowToModel)
    }

    fun findSince(since: LocalDateTime, limit: Int = 5000): List<ImageGenerationEvent> = transaction(db) {
        ImageGenerationEvents.select { ImageGenerationEvents.createdAt greaterEq since }
            .orderBy(ImageGenerationEvents.createdAt to SortOrder.DESC)
            .limit(limit)
            .map(::rowToModel)
    }

    private fun rowToModel(row: org.jetbrains.exposed.sql.ResultRow) = ImageGenerationEvent(
        id = row[ImageGenerationEvents.id],
        imageJobId = row[ImageGenerationEvents.imageJobId],
        turnRequestId = row[ImageGenerationEvents.turnRequestId],
        conversationId = row[ImageGenerationEvents.conversationId],
        personaId = row[ImageGenerationEvents.personaId],
        personaVisualVersionId = row[ImageGenerationEvents.personaVisualVersionId],
        provider = row[ImageGenerationEvents.provider],
        model = row[ImageGenerationEvents.model],
        attempt = row[ImageGenerationEvents.attempt],
        outcome = row[ImageGenerationEvents.outcome],
        errorClass = row[ImageGenerationEvents.errorClass],
        errorMessage = row[ImageGenerationEvents.errorMessage],
        queueLatencyMs = row[ImageGenerationEvents.queueLatencyMs],
        generationLatencyMs = row[ImageGenerationEvents.generationLatencyMs],
        downloadLatencyMs = row[ImageGenerationEvents.downloadLatencyMs],
        persistenceLatencyMs = row[ImageGenerationEvents.persistenceLatencyMs],
        totalLatencyMs = row[ImageGenerationEvents.totalLatencyMs],
        assetCount = row[ImageGenerationEvents.assetCount],
        createdAt = row[ImageGenerationEvents.createdAt],
    )

    companion object {
        fun redactSecrets(message: String?): String? {
            if (message == null) return null
            var m = message
            m = Regex("""(?i)(api[_-]?key|authorization|bearer)\s*[:=]\s*\S+""")
                .replace(m, "$1=[REDACTED]")
            m = Regex("""(?i)github_pat_[A-Za-z0-9_]+""").replace(m, "[REDACTED_PAT]")
            m = Regex("""(?i)sk-[A-Za-z0-9]{10,}""").replace(m, "[REDACTED_KEY]")
            return m.take(2000)
        }
    }
}
