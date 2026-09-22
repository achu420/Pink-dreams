package com.pinkdreams.visual.identity

import kotlinx.serialization.Serializable

/**
 * Separate from [PhysicalGuide]. Admin-only / generation-authorized.
 * Must never be exposed via public persona or ordinary user APIs.
 */
@Serializable
data class PrivateVisualGuide(
    val breastDescription: String? = null,
    val nippleDescription: String? = null,
    val buttDescription: String? = null,
    val genitalDescription: String? = null,
    val chestDescription: String? = null,
    val otherPrivateNotes: String? = null,
) {
    fun isEmpty(): Boolean =
        breastDescription.isNullOrBlank() &&
            nippleDescription.isNullOrBlank() &&
            buttDescription.isNullOrBlank() &&
            genitalDescription.isNullOrBlank() &&
            chestDescription.isNullOrBlank() &&
            otherPrivateNotes.isNullOrBlank()
}
