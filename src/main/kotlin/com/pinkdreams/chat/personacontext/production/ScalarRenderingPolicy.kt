package com.pinkdreams.chat.personacontext.production

import kotlinx.serialization.json.JsonPrimitive

/**
 * Pluggable rendering-policy abstraction (Phase 45 §13, Phase 46 §4).
 *
 * Retrieval/authorization ([PersonaFieldAuthorizer]) has NO knowledge of
 * which policy will be applied — a policy is passed into
 * [ProductionPersonaContextRenderer] only AFTER authorization has already
 * produced a final, safe field set. Swapping policies changes only how an
 * already-authorized scalar's value is displayed; it can never add, remove,
 * or re-authorize a field.
 *
 * No policy is selected as "the one production ships with" here — that is
 * the Phase 45 §14 item 4 human decision, deliberately left open.
 */
interface ScalarRenderingPolicy {
    /** Renders a single top-level scalar's value, given its field ID and JSON value. */
    fun renderScalar(fieldId: String, value: JsonPrimitive): String
}

/** Mirrors the research "B" behavior: bare value, no label. */
object BareScalarRendering : ScalarRenderingPolicy {
    override fun renderScalar(fieldId: String, value: JsonPrimitive): String = value.content
}

/** Mirrors the research "LA" (age-only labeled) diagnostic behavior. */
object AgeOnlyLabeledScalarRendering : ScalarRenderingPolicy {
    override fun renderScalar(fieldId: String, value: JsonPrimitive): String =
        if (fieldId == "identity.age") "Age: ${value.content}" else value.content
}

/** Mirrors the research "L" (all-scalars labeled) diagnostic behavior. */
object AllScalarsLabeledRendering : ScalarRenderingPolicy {
    override fun renderScalar(fieldId: String, value: JsonPrimitive): String {
        val label = fieldId.substringAfterLast(".").replace("_", " ").replaceFirstChar { it.uppercase() }
        return "$label: ${value.content}"
    }
}
