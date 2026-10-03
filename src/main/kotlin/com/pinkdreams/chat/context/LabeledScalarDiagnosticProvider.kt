package com.pinkdreams.chat.context

import com.pinkdreams.chat.personacontext.PersonaContextResolver
import com.pinkdreams.persistence.repositories.PersonaFieldRegistryRepository
import com.pinkdreams.persistence.repositories.PersonaMasterRepository
import com.pinkdreams.persistence.repositories.SkillContextRequirementRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.Database
import java.util.UUID

/**
 * Phase 16 diagnostic ONLY: identical to variant B (same resolver selection,
 * verbatim reuse of PersonaContextResolver.resolve(..., "B") — never
 * re-derives or re-scores selection) and identical rendering structure to
 * PersonaContextRenderer (same classification grouping, same "## Heading"
 * sections, same nested-object/array rendering, copied verbatim) with
 * EXACTLY ONE intentional difference: a top-level scalar (JsonPrimitive)
 * value is now prefixed with a human-readable label derived from its field
 * ID, instead of being rendered as a bare, unlabeled line (the gap
 * identified in PHASE15_TIMELINE_B_ROOT_CAUSE.md, in
 * PersonaContextRenderer.renderValue()'s JsonPrimitive branch).
 *
 * PersonaContextRenderer.kt itself is NOT modified — this class exists
 * purely as an isolated, temporary diagnostic and is not wired into
 * production or into any frozen A/B/C/D/X/FR path.
 */
class LabeledScalarDiagnosticProvider(
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
        // Verbatim reuse of variant B's own selection — no re-derivation.
        val projection = resolver.resolve(personaId, personaCoreVersionId, skillKey, currentUserMessage, "B")
        val masterDoc = personaMasterRepo.findByPersonaCoreVersionId(personaCoreVersionId) ?: return ""
        val fieldRegistry = fieldRegistryRepo.findAll().associateBy { it.fieldId }

        // Same fieldId -> (classification, value) construction B's own resolver path uses internally.
        val fieldValueMap = linkedMapOf<String, Pair<String, JsonElement>>()
        projection.includedFieldIds.forEach { fieldId ->
            val registryEntry = fieldRegistry[fieldId] ?: return@forEach
            val value = personaMasterRepo.resolvePath(masterDoc.contentJson, registryEntry.path)
            if (value is JsonElement) {
                fieldValueMap[fieldId] = Pair(registryEntry.classification, value)
            }
        }

        // Same classification-grouping and heading logic as PersonaContextRenderer,
        // copied (not imported) so the shared production renderer is never touched.
        val grouped = fieldValueMap.entries.groupBy { it.value.first }.toSortedMap()
        val sections = mutableListOf<String>()
        for ((classification, fields) in grouped) {
            val heading = classificationToHeading(classification)
            val content = fields.mapNotNull { (fieldId, pair) ->
                val (_, jsonValue) = pair
                renderTopLevelValue(jsonValue, fieldId).takeIf { it.isNotBlank() }
            }.joinToString("\n")
            if (content.isNotBlank()) sections.add("## $heading\n\n$content")
        }
        return sections.joinToString("\n\n")
    }

    /**
     * The ONE intentional difference from PersonaContextRenderer: a
     * top-level JsonPrimitive is now labeled with its human-readable field
     * name, e.g. "Age: 27" instead of a bare "27". Nested objects/arrays
     * render identically to PersonaContextRenderer (unchanged copy).
     */
    private fun renderTopLevelValue(element: JsonElement, fieldId: String): String = when (element) {
        is JsonPrimitive -> {
            val label = fieldId.substringAfterLast(".").replace("_", " ").replaceFirstChar { it.uppercase() }
            "$label: ${element.content}"
        }
        is JsonObject -> renderObject(element)
        is JsonArray -> renderArray(element)
        else -> ""
    }

    // --- Everything below is an unmodified copy of PersonaContextRenderer's
    // --- nested-object/array rendering, so non-scalar fields are byte-for-byte
    // --- identical to variant B's own output.

    private fun classificationToHeading(classification: String): String = when (classification) {
        "identity" -> "Identity"
        "behavioral" -> "Personality & Behavior"
        "background" -> "Background & Context"
        "worldview" -> "Worldview & Values"
        "preference" -> "Preferences"
        "relational" -> "Relationships & Attachment"
        "narrative" -> "Current Life & Arcs"
        "visual" -> "Visual Identity"
        "private" -> "Private Details"
        "intimacy" -> "Intimacy Profile"
        "public" -> "Public Expression"
        "aspiration" -> "Aspirations & Goals"
        "decision" -> "Decision Patterns"
        "asset" -> "Assets & Media"
        "administrative" -> "Administrative"
        else -> classification.replaceFirstChar { it.uppercase() }
    }

    private fun renderValue(element: JsonElement, fieldId: String): String = when (element) {
        is JsonPrimitive -> {
            val value = element.content
            if (value.length < 500) value else value.take(500) + "..."
        }
        is JsonObject -> renderObject(element)
        is JsonArray -> renderArray(element)
        else -> ""
    }

    private fun renderObject(obj: JsonObject): String {
        if (obj.isEmpty()) return ""
        val lines = obj.entries.mapNotNull { (key, value) ->
            val rendered = renderValue(value, key)
            if (rendered.isBlank()) {
                null
            } else if (value is JsonObject || value is JsonArray) {
                "**${key.replaceFirstChar { it.uppercase() }}:**\n$rendered"
            } else {
                "**${key.replaceFirstChar { it.uppercase() }}:** $rendered"
            }
        }
        return lines.joinToString("\n\n")
    }

    private fun renderArray(arr: JsonArray): String {
        if (arr.isEmpty()) return ""
        val items = arr.mapNotNull { element ->
            val rendered = renderValue(element, "")
            if (rendered.isNotBlank()) "- $rendered" else null
        }
        return items.joinToString("\n")
    }
}
