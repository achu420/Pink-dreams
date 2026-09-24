package com.pinkdreams.chat.personacontext

/**
 * Deterministic scorer for conditional persona fields. Reuses the pattern
 * from DeterministicMemoryContextSelector: word overlap + scoring, no LLM,
 * no randomness, deterministic for same inputs.
 */
class PersonaFieldRelevanceScorer {

    data class FieldRelevance(
        val fieldId: String,
        val score: Double,
    )

    fun scoreConditionalFields(
        conditionalFieldIds: List<String>,
        userMessage: String,
        fieldDescriptions: Map<String, String>,
    ): List<FieldRelevance> {
        if (conditionalFieldIds.isEmpty() || userMessage.isBlank()) return emptyList()

        val messageWords = userMessage.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }
        if (messageWords.isEmpty()) return emptyList()

        return conditionalFieldIds.mapNotNull { fieldId ->
            val description = fieldDescriptions[fieldId] ?: return@mapNotNull null
            val descriptionWords = description.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }

            val overlap = messageWords.intersect(descriptionWords.toSet()).size
            val contextRelevance = overlap.coerceAtMost(3).toDouble() / 3.0 // 0-1

            // Recency bias: fields mentioned at start of message score higher
            val startWeightBonus = if (messageWords.take(5).intersect(descriptionWords.toSet()).isNotEmpty()) 0.1 else 0.0

            val score = (contextRelevance * 0.8) + (startWeightBonus * 0.2)

            if (score > 0.1) {
                FieldRelevance(fieldId, score)
            } else {
                null
            }
        }
    }

    fun shouldIncludeConditional(
        score: Double,
        threshold: Double = 0.25,
    ): Boolean = score >= threshold
}
