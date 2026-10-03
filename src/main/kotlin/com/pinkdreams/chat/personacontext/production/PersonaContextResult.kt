package com.pinkdreams.chat.personacontext.production

import java.util.UUID

/**
 * Production Persona Context result contract (Phase 45 §5, Phase 46 §1).
 *
 * `requestedVariant`/`returnedVariant` (a hardcoded, always-equal
 * `"SELECTIVE"` pair inherited from the research A/B/C variant concept, which
 * has no production equivalent) were removed in the production-readiness
 * pass — Phase 45 §5/§7 and Phase 50 §12 both already identified them as
 * structurally unnecessary; this app is not yet deployed, so backward
 * compatibility with that earlier contract shape was not a constraint.
 *
 * For every non-USABLE status, `renderedText` is "" and `includedFieldIds`
 * is empty — enforced by [PersonaFieldAuthorizer], not merely a convention a
 * caller must remember (Phase 45 §5's "hard to accidentally misuse" goal).
 */
data class PersonaContextResult(
    val status: PersonaContextStatus,
    val renderedText: String,
    val includedFieldIds: List<String>,
    val personaId: UUID,
    val personaVersion: UUID,
    val skillKey: String,
)

/** The single authoritative usability predicate. USABLE is the only safe-to-use status. */
fun PersonaContextResult.isUsable(): Boolean = status == PersonaContextStatus.USABLE
