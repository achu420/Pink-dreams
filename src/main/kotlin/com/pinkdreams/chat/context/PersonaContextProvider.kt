package com.pinkdreams.chat.context

import java.util.UUID

/**
 * Abstraction for how persona context is selected and rendered for a conversation.
 * Two implementations: raw (production behavior) and resolved (POC variants B/C).
 */
interface PersonaContextProvider {
    fun getPersonaContext(
        personaId: UUID,
        personaCoreVersionId: UUID,
        skillKey: String,
        currentUserMessage: String,
    ): String
}
