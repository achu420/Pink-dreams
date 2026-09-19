package com.pinkdreams.chat.sensitive

import com.pinkdreams.persistence.repositories.SensitivePreferenceRepository
import java.util.UUID

/**
 * The only write path for sensitive/intimacy preferences. There is deliberately
 * no method here that accepts provenance="inferred" — an ambiguous or inferred
 * statement must never automatically become a durable sensitive preference, so
 * that capability simply does not exist in this API (a compile-time guarantee,
 * not a runtime check the caller could get wrong). If a lower-confidence,
 * reviewable "inferred" pathway is ever needed, it must be added as an explicit,
 * separate, non-default flow.
 */
class SensitivePreferenceService(private val repository: SensitivePreferenceRepository) {

    fun recordExplicit(
        userId: UUID,
        personaId: UUID,
        category: String,
        preferenceType: String,
        content: String,
        source: String = "user_stated",
    ): SensitivePreferenceRepository.SensitivePreference =
        repository.recordSuperseding(
            userId = userId,
            personaId = personaId,
            category = category,
            preferenceType = preferenceType,
            content = content,
            provenance = "explicit",
            source = source,
        )

    fun deactivate(id: UUID): SensitivePreferenceRepository.SensitivePreference = repository.deactivate(id)

    /** Current active state only, deterministically ordered for stable context assembly. */
    fun selectForContext(userId: UUID, personaId: UUID): List<SensitivePreferenceRepository.SensitivePreference> =
        repository.findActiveForRelationship(userId, personaId)
            .sortedWith(compareBy({ it.category }, { it.preferenceType }, { it.createdAt }))
}
