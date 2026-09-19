package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.AiSettings
import com.pinkdreams.persistence.database.defaultNow
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime

/**
 * The single row of admin-editable AI runtime settings.
 *
 * Every field is nullable and means "not configured here". Resolution order is
 * defined in [com.pinkdreams.config.AiRuntimeSettings]:
 *
 *     DB setting  →  environment variable  →  application default
 *
 * so an empty table reproduces current behavior exactly and adding this layer
 * cannot silently change a running deployment.
 *
 * Deliberately NOT versioned: unlike the engines, these are live operational
 * knobs rather than reviewed prompt content, and draft/publish/activate would
 * add ceremony with no meaning. Auditability is preserved through updatedAt /
 * updatedBy instead.
 */
open class AiSettingsRepository(private val db: Database) {

    data class StoredAiSettings(
        val model: String?,
        val temperature: Double?,
        val maxOutputTokens: Int?,
        val updatedAt: LocalDateTime?,
        val updatedBy: String?,
    ) {
        companion object {
            /** Nothing configured — every field falls through to env/default. */
            val EMPTY = StoredAiSettings(null, null, null, null, null)
        }
    }

    open fun get(): StoredAiSettings = transaction(db) {
        AiSettings.select { AiSettings.id eq AiSettings.SINGLETON_ID }
            .map(::rowToModel)
            .singleOrNull()
            ?: StoredAiSettings.EMPTY
    }

    /**
     * Upserts the one row. A field passed as null is STORED as null, i.e.
     * "clear this override and fall back" — that is the only way an admin can
     * hand a setting back to the environment, so it must not be confused with
     * "leave unchanged". Callers that want partial updates read first and pass
     * the full desired state.
     */
    fun save(
        model: String?,
        temperature: Double?,
        maxOutputTokens: Int?,
        updatedBy: String?,
    ): StoredAiSettings = transaction(db) {
        validate(temperature, maxOutputTokens)
        val exists = AiSettings.select { AiSettings.id eq AiSettings.SINGLETON_ID }.any()
        if (exists) {
            AiSettings.update({ AiSettings.id eq AiSettings.SINGLETON_ID }) {
                it[AiSettings.model] = model
                it[AiSettings.temperature] = temperature
                it[AiSettings.maxOutputTokens] = maxOutputTokens
                it[AiSettings.updatedAt] = defaultNow()
                it[AiSettings.updatedBy] = updatedBy
            }
        } else {
            AiSettings.insert {
                it[AiSettings.id] = AiSettings.SINGLETON_ID
                it[AiSettings.model] = model
                it[AiSettings.temperature] = temperature
                it[AiSettings.maxOutputTokens] = maxOutputTokens
                it[AiSettings.updatedAt] = defaultNow()
                it[AiSettings.updatedBy] = updatedBy
            }
        }
        get()
    }

    private fun validate(temperature: Double?, maxOutputTokens: Int?) {
        if (temperature != null) {
            require(temperature in MIN_TEMPERATURE..MAX_TEMPERATURE) {
                "Temperature must be between $MIN_TEMPERATURE and $MAX_TEMPERATURE"
            }
        }
        if (maxOutputTokens != null) {
            require(maxOutputTokens in MIN_MAX_OUTPUT_TOKENS..MAX_MAX_OUTPUT_TOKENS) {
                "Max output tokens must be between $MIN_MAX_OUTPUT_TOKENS and $MAX_MAX_OUTPUT_TOKENS"
            }
        }
    }

    private fun rowToModel(row: ResultRow) = StoredAiSettings(
        model = row[AiSettings.model],
        temperature = row[AiSettings.temperature],
        maxOutputTokens = row[AiSettings.maxOutputTokens],
        updatedAt = row[AiSettings.updatedAt],
        updatedBy = row[AiSettings.updatedBy],
    )

    companion object {
        const val MIN_TEMPERATURE = 0.0
        const val MAX_TEMPERATURE = 2.0
        const val MIN_MAX_OUTPUT_TOKENS = 1
        // Generous upper bound: this guards against a typo costing real money,
        // not against any particular model's context window.
        const val MAX_MAX_OUTPUT_TOKENS = 32000
    }
}
