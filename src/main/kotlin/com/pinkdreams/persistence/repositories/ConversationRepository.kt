package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.Conversations
import com.pinkdreams.persistence.database.defaultNow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID

open class ConversationRepository(private val db: Database) {
    /**
     * Phase ADMIN-3 — the immutable configuration a TEST conversation was
     * created with. Present only when `executionMode == "TEST"`. Every field
     * is a VERSION NUMBER (never an id), because version numbers are what an
     * admin picks from a dropdown and what the inspector displays — resolving
     * a version to its content/row happens later, at read time, via the
     * matching repository's `findByVersion`.
     */
    data class ConfigurationSnapshot(
        val conversationEngineVersion: Int,
        val personaCoreVersion: Int,
        val intentEngineVersion: Int,
        val memoryEngineVersion: Int,
        /** skill key -> version. Only these keys are candidates for this test conversation's Intent Engine. */
        val skillVersions: Map<String, Int>,
        val model: String?,
        val temperature: Double?,
        val maxOutputTokens: Int?,
        /** A real, hidden Personas row scoping this test conversation's memory — see MemoryScopeResolver.TestMemoryScope. */
        val memoryScopePersonaId: UUID,
    )

    data class Conversation(
        val id: UUID,
        val userId: UUID,
        val personaId: UUID,
        val state: String,
        val lastMessageAt: LocalDateTime?,
        val createdAt: LocalDateTime,
        val continuitySummary: String? = null,
        val continuitySummaryCoveredCount: Int = 0,
        val memoryEngineProcessedCount: Int = 0,
        val executionMode: String = "PRODUCTION",
        val snapshot: ConfigurationSnapshot? = null,
    ) {
        val isTest: Boolean get() = executionMode == "TEST"
    }

    fun create(userId: UUID, personaId: UUID, state: String = "active"): Conversation = transaction(db) {
        require(state in setOf("active", "idle", "archived")) { "Invalid conversation state: $state" }
        val id = UUID.randomUUID()
        Conversations.insert {
            it[Conversations.id] = id
            it[Conversations.userId] = userId
            it[Conversations.personaId] = personaId
            it[Conversations.state] = state
            it[Conversations.lastMessageAt] = null
            it[Conversations.createdAt] = defaultNow()
            it[Conversations.executionMode] = "PRODUCTION"
        }
        findById(id)!!
    }

    /**
     * Creates a TEST conversation with its configuration snapshot fixed at
     * creation time. Nothing about this snapshot is ever mutated afterward —
     * see [ConfigurationSnapshot] and Phase ADMIN-3 section 30 (immutability).
     */
    fun createTest(userId: UUID, personaId: UUID, snapshot: ConfigurationSnapshot): Conversation = transaction(db) {
        val id = UUID.randomUUID()
        Conversations.insert {
            it[Conversations.id] = id
            it[Conversations.userId] = userId
            it[Conversations.personaId] = personaId
            it[Conversations.state] = "active"
            it[Conversations.lastMessageAt] = null
            it[Conversations.createdAt] = defaultNow()
            it[Conversations.executionMode] = "TEST"
            it[Conversations.snapshotConversationEngineVersion] = snapshot.conversationEngineVersion
            it[Conversations.snapshotPersonaCoreVersion] = snapshot.personaCoreVersion
            it[Conversations.snapshotIntentEngineVersion] = snapshot.intentEngineVersion
            it[Conversations.snapshotMemoryEngineVersion] = snapshot.memoryEngineVersion
            it[Conversations.snapshotSkillVersionsJson] = encodeSkillVersions(snapshot.skillVersions)
            it[Conversations.snapshotModel] = snapshot.model
            it[Conversations.snapshotTemperature] = snapshot.temperature
            it[Conversations.snapshotMaxOutputTokens] = snapshot.maxOutputTokens
            it[Conversations.snapshotMemoryScopePersonaId] = snapshot.memoryScopePersonaId
        }
        findById(id)!!
    }

    private fun encodeSkillVersions(versions: Map<String, Int>): String =
        buildJsonObject { versions.forEach { (key, version) -> put(key, JsonPrimitive(version)) } }.toString()

    private fun decodeSkillVersions(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            Json.parseToJsonElement(raw).jsonObject.mapValues { (_, v) -> v.jsonPrimitive.int }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun findById(id: UUID): Conversation? = transaction(db) {
        Conversations.select { Conversations.id eq id }
            .map(::rowToModel)
            .singleOrNull()
    }

    open fun findByIdForUser(id: UUID, userId: UUID): Conversation? = transaction(db) {
        Conversations.select { (Conversations.id eq id) and (Conversations.userId eq userId) }
            .map(::rowToModel)
            .singleOrNull()
    }

    open fun findAllForUser(userId: UUID, limit: Int = 20, offset: Int = 0, personaId: UUID? = null): List<Conversation> = transaction(db) {
        val condition = if (personaId != null) {
            (Conversations.userId eq userId) and (Conversations.personaId eq personaId)
        } else {
            Conversations.userId eq userId
        }
        Conversations.select { condition }
            .orderBy(Conversations.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC, Conversations.id to org.jetbrains.exposed.sql.SortOrder.DESC)
            .limit(limit, offset.toLong())
            .map(::rowToModel)
    }

    fun updateLastMessageAt(id: UUID, timestamp: LocalDateTime = defaultNow()): Conversation = transaction(db) {
        require(findById(id) != null) { "Conversation not found: $id" }
        Conversations.update({ Conversations.id eq id }) {
            it[Conversations.lastMessageAt] = timestamp
        }
        findById(id)!!
    }

    /**
     * Overwrites the conversation's continuity summary and the count of messages
     * (from the start, chronological) already folded into it. Always a full
     * replace on a single row — there is no append-only log, so retried/duplicate
     * calls cannot create duplicate continuity records, only redundant writes of
     * the same or an updated value.
     */
    fun updateContinuitySummary(id: UUID, summary: String, coveredCount: Int): Conversation = transaction(db) {
        require(findById(id) != null) { "Conversation not found: $id" }
        Conversations.update({ Conversations.id eq id }) {
            it[Conversations.continuitySummary] = summary
            it[Conversations.continuitySummaryCoveredCount] = coveredCount
        }
        findById(id)!!
    }

    /**
     * Advances the Memory Engine's independent batch cursor. Distinct from
     * updateContinuitySummary — the two cursors track unrelated windows over
     * the same message history and must never be conflated.
     */
    fun updateMemoryEngineProcessedCount(id: UUID, processedCount: Int): Conversation = transaction(db) {
        require(findById(id) != null) { "Conversation not found: $id" }
        Conversations.update({ Conversations.id eq id }) {
            it[Conversations.memoryEngineProcessedCount] = processedCount
        }
        findById(id)!!
    }

    /**
     * Atomic compare-and-swap claim on the Memory Engine batch cursor: only
     * succeeds (returns true) if the cursor still equals [expectedProcessedCount]
     * at the moment of the UPDATE. Two concurrent workers racing to process the
     * same batch will have exactly one succeed — the other observes the row no
     * longer matches its expected value and gets false, meaning "someone else
     * already claimed this batch, do not process it." This is what prevents
     * duplicate memory writes under concurrent post-delivery hook execution,
     * without any in-memory lock (Phase D section 34).
     */
    fun claimMemoryEngineBatch(id: UUID, expectedProcessedCount: Int, newProcessedCount: Int): Boolean = transaction(db) {
        val updated = Conversations.update({
            (Conversations.id eq id) and (Conversations.memoryEngineProcessedCount eq expectedProcessedCount)
        }) {
            it[Conversations.memoryEngineProcessedCount] = newProcessedCount
        }
        updated == 1
    }

    private fun rowToModel(row: ResultRow): Conversation {
        val executionMode = row[Conversations.executionMode]
        val snapshot = if (executionMode == "TEST") {
            ConfigurationSnapshot(
                conversationEngineVersion = row[Conversations.snapshotConversationEngineVersion] ?: 0,
                personaCoreVersion = row[Conversations.snapshotPersonaCoreVersion] ?: 0,
                intentEngineVersion = row[Conversations.snapshotIntentEngineVersion] ?: 0,
                memoryEngineVersion = row[Conversations.snapshotMemoryEngineVersion] ?: 0,
                skillVersions = decodeSkillVersions(row[Conversations.snapshotSkillVersionsJson]),
                model = row[Conversations.snapshotModel],
                temperature = row[Conversations.snapshotTemperature],
                maxOutputTokens = row[Conversations.snapshotMaxOutputTokens],
                memoryScopePersonaId = row[Conversations.snapshotMemoryScopePersonaId] ?: row[Conversations.personaId],
            )
        } else {
            null
        }
        return Conversation(
            id = row[Conversations.id],
            userId = row[Conversations.userId],
            personaId = row[Conversations.personaId],
            state = row[Conversations.state],
            lastMessageAt = row[Conversations.lastMessageAt],
            createdAt = row[Conversations.createdAt],
            continuitySummary = row[Conversations.continuitySummary],
            continuitySummaryCoveredCount = row[Conversations.continuitySummaryCoveredCount],
            memoryEngineProcessedCount = row[Conversations.memoryEngineProcessedCount],
            executionMode = executionMode,
            snapshot = snapshot,
        )
    }
}