package com.pinkdreams.chat.context

import com.pinkdreams.chat.personacontext.PersonaContextResolver
import com.pinkdreams.persistence.repositories.PersonaFieldRegistryRepository
import com.pinkdreams.persistence.repositories.PersonaMasterRepository
import com.pinkdreams.persistence.repositories.SkillContextRequirementRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.Database
import java.util.UUID

/**
 * Phase 10 scientific probe ("variant X"): the exact same field SELECTION as
 * variant B — obtained by calling PersonaContextResolver.resolve(..., "B")
 * and consuming its `includedFieldIds` as-is, never re-deriving or
 * re-scoring which fields are relevant — but rendered as raw, unstructured
 * JSON (mirroring variant A's `JsonObject.toString()` presentation) instead
 * of through PersonaContextRenderer's headed markdown.
 *
 * This isolates STRUCTURE from SELECTION: X differs from B only in
 * rendering format, and differs from A only in which fields are present.
 * PersonaContextResolver, PersonaFieldRelevanceScorer, PersonaContextRenderer,
 * and the field registry/skill mappings are used exactly as-is and are never
 * modified or duplicated by this class.
 *
 * Not wired into production or into the frozen A/B/C/D paths — used only by
 * a separate, explicitly-labeled Phase 10 experiment.
 */
class SelectiveRawContextProvider(
    private val db: Database,
    private val personaMasterRepo: PersonaMasterRepository,
    private val fieldRegistryRepo: PersonaFieldRegistryRepository,
    private val skillContextReqRepo: SkillContextRequirementRepository,
) : PersonaContextProvider {

    private val resolver = PersonaContextResolver(db, personaMasterRepo, fieldRegistryRepo, skillContextReqRepo)

    override fun getPersonaContext(
        personaId: UUID,
        personaCoreVersionId: UUID,
        skillKey: String,
        currentUserMessage: String,
    ): String {
        // Reuse the resolver's OWN variant-B selection verbatim. Nothing here
        // re-implements or re-scores relevance — includedFieldIds is consumed
        // exactly as the resolver produced it.
        val projection = resolver.resolve(personaId, personaCoreVersionId, skillKey, currentUserMessage, "B")
        val masterDoc = personaMasterRepo.findByPersonaCoreVersionId(personaCoreVersionId) ?: return ""
        val fieldRegistry = fieldRegistryRepo.findAll().associateBy { it.fieldId }

        val selected = mutableMapOf<String, JsonElement>()
        projection.includedFieldIds.forEach { fieldId ->
            val registryEntry = fieldRegistry[fieldId] ?: return@forEach
            val value = personaMasterRepo.resolvePath(masterDoc.contentJson, registryEntry.path)
            if (value is JsonElement) {
                selected[fieldId] = value
            }
        }
        // Raw JSON, no headers, no bold labels, no descriptions, no reordering
        // beyond whatever order includedFieldIds/map iteration already gives —
        // the same unstructured presentation style as variant A's raw dump.
        return JsonObject(selected).toString()
    }
}
