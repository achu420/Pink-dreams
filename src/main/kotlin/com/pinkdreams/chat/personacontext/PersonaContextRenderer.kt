package com.pinkdreams.chat.personacontext

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Renders selected persona fields to readable prose, grouped by classification.
 * Deterministic: same fields → same prose every time. No LLM, no randomness.
 */
class PersonaContextRenderer {

    fun render(
        selectedFields: Map<String, Pair<String, JsonElement>>, // fieldId -> (classification, jsonValue)
        personaName: String = "Simran",
    ): String {
        if (selectedFields.isEmpty()) return ""

        // Group by classification
        val grouped = selectedFields
            .entries
            .groupBy { it.value.first } // classification
            .toSortedMap()

        val sections = mutableListOf<String>()

        for ((classification, fields) in grouped) {
            val heading = classificationToHeading(classification)
            val content = fields.map { (fieldId, pair) ->
                val (_, jsonValue) = pair
                renderValue(jsonValue, fieldId)
            }.filter { it.isNotBlank() }.joinToString("\n")

            if (content.isNotBlank()) {
                sections.add("## $heading\n\n$content")
            }
        }

        return sections.joinToString("\n\n")
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

    private fun renderValue(element: JsonElement, fieldId: String): String {
        return when (element) {
            is kotlinx.serialization.json.JsonPrimitive -> {
                val value = element.jsonPrimitive.content
                if (value.length < 500) {
                    value
                } else {
                    value.take(500) + "..."
                }
            }
            is JsonObject -> renderObject(element)
            is kotlinx.serialization.json.JsonArray -> renderArray(element)
            else -> ""
        }
    }

    private fun renderObject(obj: JsonObject): String {
        if (obj.isEmpty()) return ""

        val lines = obj.entries.mapNotNull { (key, value) ->
            val rendered = renderValue(value, key)
            if (rendered.isBlank()) {
                null
            } else if (value is JsonObject || value is kotlinx.serialization.json.JsonArray) {
                "**${key.replaceFirstChar { it.uppercase() }}:**\n$rendered"
            } else {
                "**${key.replaceFirstChar { it.uppercase() }}:** $rendered"
            }
        }

        return lines.joinToString("\n\n")
    }

    private fun renderArray(arr: kotlinx.serialization.json.JsonArray): String {
        if (arr.isEmpty()) return ""

        val items = arr.mapNotNull { element ->
            val rendered = renderValue(element, "")
            if (rendered.isNotBlank()) "- $rendered" else null
        }

        return items.joinToString("\n")
    }
}
