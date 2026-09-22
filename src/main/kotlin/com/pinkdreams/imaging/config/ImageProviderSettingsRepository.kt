package com.pinkdreams.imaging.config

import com.pinkdreams.persistence.database.ImageProviderSettings
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime

/**
 * Admin-editable image provider/model settings (singleton).
 *
 * PRECEDENCE for the effective model (see [ImageRuntimeConfig]):
 *   1. Explicit per-request modelId (evaluation / Admin override)
 *   2. DB row when enabled and model_id set
 *   3. OPENROUTER_IMAGE_MODEL env
 *   4. Application default
 *
 * API keys are NEVER stored here — only provider/model identifiers.
 */
class ImageProviderSettingsRepository(private val db: Database) {

    data class Stored(
        val provider: String?,
        val modelId: String?,
        val enabled: Boolean,
        val notes: String?,
        val updatedAt: LocalDateTime?,
        val updatedBy: String?,
    ) {
        companion object {
            val EMPTY = Stored(null, null, true, null, null, null)
        }
    }

    fun get(): Stored = transaction(db) {
        ImageProviderSettings.select { ImageProviderSettings.id eq ImageProviderSettings.SINGLETON_ID }
            .singleOrNull()
            ?.let {
                Stored(
                    provider = it[ImageProviderSettings.provider],
                    modelId = it[ImageProviderSettings.modelId],
                    enabled = it[ImageProviderSettings.enabled],
                    notes = it[ImageProviderSettings.notes],
                    updatedAt = it[ImageProviderSettings.updatedAt],
                    updatedBy = it[ImageProviderSettings.updatedBy],
                )
            }
            ?: Stored.EMPTY
    }

    fun save(
        provider: String?,
        modelId: String?,
        enabled: Boolean = true,
        notes: String? = null,
        updatedBy: String?,
    ): Stored = transaction(db) {
        require(modelId == null || modelId.isNotBlank()) { "modelId cannot be blank" }
        require(modelId == null || modelId.length <= 255) { "modelId too long" }
        require(provider == null || provider.length <= 100) { "provider too long" }
        val now = LocalDateTime.now()
        val exists = ImageProviderSettings.select {
            ImageProviderSettings.id eq ImageProviderSettings.SINGLETON_ID
        }.any()
        if (exists) {
            ImageProviderSettings.update({ ImageProviderSettings.id eq ImageProviderSettings.SINGLETON_ID }) {
                it[ImageProviderSettings.provider] = provider
                it[ImageProviderSettings.modelId] = modelId
                it[ImageProviderSettings.enabled] = enabled
                it[ImageProviderSettings.notes] = notes
                it[ImageProviderSettings.updatedAt] = now
                it[ImageProviderSettings.updatedBy] = updatedBy
            }
        } else {
            ImageProviderSettings.insert {
                it[ImageProviderSettings.id] = ImageProviderSettings.SINGLETON_ID
                it[ImageProviderSettings.provider] = provider
                it[ImageProviderSettings.modelId] = modelId
                it[ImageProviderSettings.enabled] = enabled
                it[ImageProviderSettings.notes] = notes
                it[ImageProviderSettings.updatedAt] = now
                it[ImageProviderSettings.updatedBy] = updatedBy
            }
        }
        get()
    }
}
