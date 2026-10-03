package com.pinkdreams.chat.personacontext.production

import com.pinkdreams.chat.personacontext.PersonaFieldRelevanceScorer
import com.pinkdreams.persistence.repositories.PersonaFieldRegistryEntry
import com.pinkdreams.persistence.repositories.PersonaFieldRegistryRepository
import com.pinkdreams.persistence.repositories.PersonaMasterRepository
import com.pinkdreams.persistence.repositories.SkillContextRequirementRepository
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.Database
import java.util.UUID

/**
 * Production Persona Field Authorizer (Phase 45 §1-§2/§4, Phase 46 §2).
 *
 * This is the ONLY production component permitted to read
 * `PersonaMasterDocuments` for the purpose of building model-bound persona
 * content. It has exactly one job: produce an authorized, structured field
 * selection, or a well-defined non-usable [PersonaContextResult] — it has
 * NO concept of "fallback content." Deliberately does NOT depend on, call,
 * or reuse `PersonaContextResolver`/`resolveVariantA()` (the frozen research
 * class) — this is new, independent production code, per Phase 45 §2's
 * "reject Approach A/B/C" decision.
 *
 * Fail-closed guarantee: a field can reach [PersonaContextResult.includedFieldIds]
 * ONLY if it passes [canAccessField] below, evaluated once, in this class,
 * and nowhere else. There is no code path in this class capable of
 * serializing an entire unfiltered document.
 */
class PersonaFieldAuthorizer(
    private val db: Database,
    private val personaMasterRepo: PersonaMasterRepository,
    private val fieldRegistryRepo: PersonaFieldRegistryRepository,
    private val skillContextReqRepo: SkillContextRequirementRepository,
    private val scorer: PersonaFieldRelevanceScorer = PersonaFieldRelevanceScorer(),
    private val renderer: ProductionPersonaContextRenderer = ProductionPersonaContextRenderer(),
) {

    fun resolve(
        personaId: UUID,
        personaCoreVersionId: UUID,
        skillKey: String,
        currentUserMessage: String,
        renderingPolicy: ScalarRenderingPolicy,
    ): PersonaContextResult {
        fun unusable(status: PersonaContextStatus): PersonaContextResult {
            log(status, personaId, skillKey, fieldCount = 0)
            return PersonaContextResult(
                status = status,
                renderedText = "",
                includedFieldIds = emptyList(),
                personaId = personaId,
                personaVersion = personaCoreVersionId,
                skillKey = skillKey,
            )
        }

        return try {
            val masterDoc = personaMasterRepo.findByPersonaCoreVersionId(personaCoreVersionId)
                ?: return unusable(PersonaContextStatus.DOCUMENT_NOT_FOUND)

            if (masterDoc.contentJson.isEmpty()) {
                return unusable(PersonaContextStatus.DOCUMENT_EMPTY)
            }

            val fieldRegistry = fieldRegistryRepo.findAll().associateBy { it.fieldId }
            if (fieldRegistry.isEmpty()) {
                return unusable(PersonaContextStatus.REGISTRY_EMPTY)
            }

            val skillReq = skillContextReqRepo.findBySkillKey(skillKey)
                ?: return unusable(PersonaContextStatus.SKILL_MAPPING_MISSING)

            val selectedFieldIds = mutableListOf<String>()

            skillReq.requiredFields?.forEach { fieldId ->
                if (canAccessField(fieldId, skillKey, fieldRegistry) && !selectedFieldIds.contains(fieldId)) {
                    selectedFieldIds.add(fieldId)
                }
            }
            skillReq.recommendedFields?.forEach { fieldId ->
                if (canAccessField(fieldId, skillKey, fieldRegistry) && !selectedFieldIds.contains(fieldId)) {
                    selectedFieldIds.add(fieldId)
                }
            }
            skillReq.conditionalFields?.forEach { fieldId ->
                if (canAccessField(fieldId, skillKey, fieldRegistry) && !selectedFieldIds.contains(fieldId)) {
                    val entry = fieldRegistry[fieldId]
                    val relevance = scorer.scoreConditionalFields(
                        listOf(fieldId),
                        currentUserMessage,
                        mapOf(fieldId to entry?.description.orEmpty()),
                    ).firstOrNull()
                    if (relevance != null && scorer.shouldIncludeConditional(relevance.score)) {
                        selectedFieldIds.add(fieldId)
                    }
                }
            }

            if (selectedFieldIds.isEmpty()) {
                return unusable(PersonaContextStatus.ZERO_FIELDS_SELECTED)
            }

            val fieldValueMap = linkedMapOf<String, Pair<String, JsonElement>>()
            selectedFieldIds.forEach { fieldId ->
                val entry = fieldRegistry[fieldId] ?: return@forEach
                val value = personaMasterRepo.resolvePath(masterDoc.contentJson, entry.path)
                if (value is JsonElement) {
                    fieldValueMap[fieldId] = Pair(entry.classification, value)
                }
            }

            val renderedText = renderer.render(fieldValueMap, renderingPolicy)
            if (renderedText.isBlank()) {
                return unusable(PersonaContextStatus.ZERO_FIELDS_SELECTED)
            }

            log(PersonaContextStatus.USABLE, personaId, skillKey, fieldCount = selectedFieldIds.size)
            PersonaContextResult(
                status = PersonaContextStatus.USABLE,
                renderedText = renderedText,
                includedFieldIds = selectedFieldIds,
                personaId = personaId,
                personaVersion = personaCoreVersionId,
                skillKey = skillKey,
            )
        } catch (e: Exception) {
            System.err.println("PERSONA_AUTHORIZER: exception personaId=$personaId skillKey=$skillKey error=${e.javaClass.simpleName}")
            unusable(PersonaContextStatus.RESOLVER_ERROR)
        }
    }

    /**
     * Content-free observability at the authorizer boundary (final
     * production-readiness pass, Step 4). Logs status/field-COUNT only —
     * never field values, never `renderedText`, never any persona document
     * content, and never the current user's message. `personaId`/`skillKey`
     * are not sensitive; a field count of 0 for a restricted-only request is
     * itself the "restricted field denied" signal (distinguishable from a
     * genuine `SKILL_MAPPING_MISSING`/`DOCUMENT_NOT_FOUND` by `status`).
     */
    private fun log(status: PersonaContextStatus, personaId: UUID, skillKey: String, fieldCount: Int) {
        System.err.println("PERSONA_AUTHORIZER: status=$status personaId=$personaId skillKey=$skillKey fieldCount=$fieldCount")
    }

    /**
     * The single authorization gate. Non-restricted fields are always
     * accessible if selected; restricted fields require the skill key to
     * appear in the field's own allowedContexts list. Logically identical
     * to the research resolver's `canAccessField()` (0 leakage, Phases
     * 10C-30) — reimplemented independently here, not called from the
     * research class, per Phase 46's "must not depend on the historical
     * research resolver" constraint.
     */
    private fun canAccessField(
        fieldId: String,
        skillKey: String,
        fieldRegistry: Map<String, PersonaFieldRegistryEntry>,
    ): Boolean {
        val field = fieldRegistry[fieldId] ?: return false
        if (field.sensitivity != "restricted" && field.sensitivity != "private") return true
        val allowedContexts = field.allowedContexts ?: return false
        return allowedContexts.contains(skillKey)
    }
}
