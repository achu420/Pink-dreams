package com.pinkdreams.chat.personacontext

import com.pinkdreams.persistence.repositories.PersonaFieldRegistryRepository
import com.pinkdreams.persistence.repositories.PersonaMasterRepository
import com.pinkdreams.persistence.repositories.SkillContextRequirementRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.Database
import java.util.UUID

data class PersonaContextProjection(
    val renderedText: String,
    val includedFieldIds: List<String>,
    val excludedFieldIds: List<String>,
    val personaTokenEstimate: Int,
    val variant: String, // "A", "B", or "C"
)

class PersonaContextResolver(
    private val db: Database,
    private val personaMasterRepo: PersonaMasterRepository,
    private val fieldRegistryRepo: PersonaFieldRegistryRepository,
    private val skillContextReqRepo: SkillContextRequirementRepository,
    private val renderer: PersonaContextRenderer = PersonaContextRenderer(),
    private val scorer: PersonaFieldRelevanceScorer = PersonaFieldRelevanceScorer(),
) {

    fun resolve(
        personaId: UUID,
        personaCoreVersionId: UUID,
        skillKey: String,
        currentUserMessage: String,
        variant: String, // "A", "B", or "C"
    ): PersonaContextProjection {
        // Variant A: raw persona content unchanged
        if (variant == "A") {
            return resolveVariantA(personaCoreVersionId)
        }

        // Variants B & C: selective retrieval
        val masterDoc = personaMasterRepo.findByPersonaCoreVersionId(personaCoreVersionId)
            ?: return resolveVariantA(personaCoreVersionId)

        val skillReq = skillContextReqRepo.findBySkillKey(skillKey)
            ?: return resolveVariantA(personaCoreVersionId)

        // Get all registry entries for field metadata
        val fieldRegistry = fieldRegistryRepo.findAll()
            .associateBy { it.fieldId }

        val selectedFieldIds = mutableListOf<String>()
        val excludedFieldIds = mutableListOf<String>()

        // Always include required fields
        skillReq.requiredFields?.forEach { fieldId ->
            if (canAccessField(fieldId, skillKey, fieldRegistry)) {
                selectedFieldIds.add(fieldId)
            } else {
                excludedFieldIds.add(fieldId)
            }
        }

        // Always include recommended fields
        skillReq.recommendedFields?.forEach { fieldId ->
            if (canAccessField(fieldId, skillKey, fieldRegistry) && !selectedFieldIds.contains(fieldId)) {
                selectedFieldIds.add(fieldId)
            } else if (!selectedFieldIds.contains(fieldId)) {
                excludedFieldIds.add(fieldId)
            }
        }

        // Include conditional fields only if relevant
        skillReq.conditionalFields?.forEach { fieldId ->
            if (canAccessField(fieldId, skillKey, fieldRegistry)) {
                val fieldRegistry = fieldRegistry[fieldId]
                val relevance = scorer.scoreConditionalFields(
                    listOf(fieldId),
                    currentUserMessage,
                    mapOf(fieldId to fieldRegistry?.description.orEmpty()),
                ).firstOrNull()

                if (relevance != null && scorer.shouldIncludeConditional(relevance.score)) {
                    selectedFieldIds.add(fieldId)
                }
            }
        }

        // Variant C: add behavioral core fields
        if (variant == "C") {
            fieldRegistryRepo.findBehavioralCore()
                .filter { !selectedFieldIds.contains(it.fieldId) }
                .forEach { selectedFieldIds.add(it.fieldId) }
        }

        // Build field map with values from persona JSON
        val fieldValueMap = mutableMapOf<String, Pair<String, JsonElement>>()
        selectedFieldIds.forEach { fieldId ->
            val registryEntry = fieldRegistry[fieldId] ?: return@forEach
            val value = personaMasterRepo.resolvePath(masterDoc.contentJson, registryEntry.path)
            if (value is JsonElement) {
                fieldValueMap[fieldId] = Pair(registryEntry.classification, value)
            }
        }

        val renderedText = renderer.render(fieldValueMap)
        val tokenEstimate = estimateTokens(renderedText)

        return PersonaContextProjection(
            renderedText = renderedText,
            includedFieldIds = selectedFieldIds,
            excludedFieldIds = excludedFieldIds,
            personaTokenEstimate = tokenEstimate,
            variant = variant,
        )
    }

    private fun resolveVariantA(personaCoreVersionId: UUID): PersonaContextProjection {
        // Variant A: return raw persona content unchanged (production behavior)
        val masterDoc = personaMasterRepo.findByPersonaCoreVersionId(personaCoreVersionId)
        val rawText = masterDoc?.contentJson?.toString() ?: ""
        val tokenEstimate = estimateTokens(rawText)

        return PersonaContextProjection(
            renderedText = rawText,
            includedFieldIds = emptyList(),
            excludedFieldIds = emptyList(),
            personaTokenEstimate = tokenEstimate,
            variant = "A",
        )
    }

    private fun canAccessField(
        fieldId: String,
        skillKey: String,
        fieldRegistry: Map<String, com.pinkdreams.persistence.repositories.PersonaFieldRegistryEntry>,
    ): Boolean {
        val field = fieldRegistry[fieldId] ?: return false

        // Non-restricted fields are always accessible
        if (field.sensitivity != "restricted" && field.sensitivity != "private") {
            return true
        }

        // Restricted fields: check allowed_contexts
        val allowedContexts = field.allowedContexts ?: return false
        return allowedContexts.contains(skillKey)
    }

    private fun estimateTokens(text: String): Int {
        // Rough estimate: 1 token ≈ 4 characters (OpenAI ratio)
        return (text.length / 4).coerceAtLeast(1)
    }
}
