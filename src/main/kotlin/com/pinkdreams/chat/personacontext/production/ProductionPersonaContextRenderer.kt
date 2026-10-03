package com.pinkdreams.chat.personacontext.production

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Production-only renderer (Phase 45 §12: a new, separate class from the
 * research `PersonaContextRenderer`, since the production renderer must be
 * policy-parameterized in a way the research renderer was never designed
 * for). Structurally mirrors the research renderer's grouping/heading logic
 * (a proven, correct pattern) but is NOT imported from it — zero coupling
 * to the frozen research code.
 *
 * Only top-level [ScalarRenderingPolicy] application differs by policy;
 * nested object/array rendering is identical regardless of policy, exactly
 * matching the isolation property the research LA/L diagnostics already
 * demonstrated (Phase 16/20).
 */
class ProductionPersonaContextRenderer {

    fun render(
        selectedFields: Map<String, Pair<String, JsonElement>>, // fieldId -> (classification, value)
        policy: ScalarRenderingPolicy,
    ): String {
        if (selectedFields.isEmpty()) return ""

        val grouped = selectedFields.entries.groupBy { it.value.first }.toSortedMap()
        val sections = mutableListOf<String>()
        for ((classification, fields) in grouped) {
            val heading = classificationToHeading(classification)
            val content = fields.mapNotNull { (fieldId, pair) ->
                renderTopLevel(pair.second, fieldId, policy).takeIf { it.isNotBlank() }
            }.joinToString("\n")
            if (content.isNotBlank()) sections.add("## $heading\n\n$content")
        }
        return sections.joinToString("\n\n")
    }

    private fun renderTopLevel(element: JsonElement, fieldId: String, policy: ScalarRenderingPolicy): String =
        when (element) {
            is JsonPrimitive -> policy.renderScalar(fieldId, element)
            is JsonObject -> renderObject(element)
            is JsonArray -> renderArray(element)
            else -> ""
        }

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

    private fun renderValue(element: JsonElement): String = when (element) {
        is JsonPrimitive -> {
            val v = element.content
            if (v.length < 500) v else v.take(500) + "..."
        }
        is JsonObject -> renderObject(element)
        is JsonArray -> renderArray(element)
        else -> ""
    }

    private fun renderObject(obj: JsonObject): String {
        if (obj.isEmpty()) return ""
        val lines = obj.entries.mapNotNull { (key, value) ->
            val rendered = renderValue(value)
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
            val rendered = renderValue(element)
            if (rendered.isNotBlank()) "- $rendered" else null
        }
        return items.joinToString("\n")
    }
}
