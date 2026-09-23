package com.pinkdreams.imaging.benchmark

import com.pinkdreams.visual.identity.ReferenceImage
import com.pinkdreams.visual.identity.ReferenceRole

/**
 * Standard identity set in fixed order. When a model cannot accept all five,
 * omit from the end of the list (BACK first). Never random.
 */
object BenchmarkReferencePolicy {
    val STANDARD_ORDER: List<ReferenceRole> = ReferenceRole.STANDARD_SLOTS

    data class Selection(
        val available: List<ReferenceImage>,
        val sent: List<ReferenceImage>,
        val omitted: List<ReferenceImage>,
        val reason: String,
    )

    fun select(availableStandard: List<ReferenceImage>, maxReferences: Int?): Selection {
        val byRole = STANDARD_ORDER.mapNotNull { role ->
            availableStandard.firstOrNull { it.role == role }
        }
        if (maxReferences == null) {
            return Selection(
                available = byRole,
                sent = byRole,
                omitted = emptyList(),
                reason = "MODEL_REFERENCE_LIMIT_UNKNOWN_SEND_ALL_STANDARD",
            )
        }
        if (maxReferences <= 0) {
            return Selection(
                available = byRole,
                sent = emptyList(),
                omitted = byRole,
                reason = "MODEL_DOES_NOT_SUPPORT_REFERENCES",
            )
        }
        val sent = byRole.take(maxReferences)
        val omitted = byRole.drop(maxReferences)
        val reason = if (omitted.isEmpty()) {
            "ALL_STANDARD_REFERENCES_SUPPORTED"
        } else {
            "MODEL_REFERENCE_LIMIT_$maxReferences"
        }
        return Selection(available = byRole, sent = sent, omitted = omitted, reason = reason)
    }
}
