package com.pinkdreams.chat.context

import com.pinkdreams.chat.personacontext.PersonaContextRenderer
import com.pinkdreams.persistence.repositories.PersonaMasterRepository
import java.util.UUID

/**
 * Phase 7 scientific probe (Candidate F, "variant D"): the SAME complete
 * persona content as variant A (nothing filtered, nothing selected), but
 * passed through the same PersonaContextRenderer that variants B/C use
 * instead of Variant A's raw JsonObject.toString() dump. Isolates whether
 * A's Phase 6 factuality gap is driven by context VOLUME or by context
 * STRUCTURE/FORMATTING, without changing variant A, B, or C in any way.
 *
 * Not wired into production or into the frozen A/B/C benchmark path —
 * used only by a separate, explicitly-labeled Phase 7 experiment.
 */
class StructuredFullDocumentContextProvider(
    private val personaMasterRepo: PersonaMasterRepository,
    private val renderer: PersonaContextRenderer = PersonaContextRenderer(),
) : PersonaContextProvider {

    override fun getPersonaContext(
        personaId: UUID,
        personaCoreVersionId: UUID,
        skillKey: String,
        currentUserMessage: String,
    ): String {
        val masterDoc = personaMasterRepo.findByPersonaCoreVersionId(personaCoreVersionId)
            ?: return ""
        // Render the ENTIRE document as one top-level field so nothing is
        // filtered — the renderer recursively expands nested objects/arrays
        // into headed, labeled markdown exactly as it does for B/C's
        // selected fields.
        return renderer.render(
            mapOf("full_document" to Pair("identity", masterDoc.contentJson as kotlinx.serialization.json.JsonElement)),
        )
    }
}
