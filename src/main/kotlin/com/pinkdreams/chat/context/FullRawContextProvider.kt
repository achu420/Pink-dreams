package com.pinkdreams.chat.context

import com.pinkdreams.persistence.repositories.PersonaMasterRepository
import java.util.UUID

/**
 * Phase 10C ("variant FR", Full Raw): the complete, unfiltered persona
 * document — same source as D and X — serialized as raw, unstructured JSON
 * instead of being passed through PersonaContextRenderer. This is the cell
 * of the structure/selection matrix that was missing entirely: no prior
 * variant ever actually sent this combination to a real generation call.
 * `resolveVariantA()` computes byte-identical content but is only ever
 * invoked from a discarded diagnostic call (see
 * PHASE10A_A_BASELINE_CORRECTION.md) — this class exists so the SAME
 * content can be wired as an explicit, non-null PersonaContextProvider and
 * therefore actually reach RepositoryContextAssembler.
 *
 * No field selection, no filtering, no renderer — mirrors
 * StructuredFullDocumentContextProvider exactly except for the final
 * serialization step. Not wired into production or into the frozen
 * A/B/C/D/X paths — used only by an explicitly-labeled Phase 10 experiment.
 */
class FullRawContextProvider(
    private val personaMasterRepo: PersonaMasterRepository,
) : PersonaContextProvider {

    override fun getPersonaContext(
        personaId: UUID,
        personaCoreVersionId: UUID,
        skillKey: String,
        currentUserMessage: String,
    ): String {
        val masterDoc = personaMasterRepo.findByPersonaCoreVersionId(personaCoreVersionId)
            ?: return ""
        return masterDoc.contentJson.toString()
    }
}
