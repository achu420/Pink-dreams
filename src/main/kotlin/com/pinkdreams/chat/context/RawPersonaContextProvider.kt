package com.pinkdreams.chat.context

import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import org.jetbrains.exposed.sql.Database
import java.util.UUID

/**
 * Production behavior: returns the raw persona core version content unchanged.
 * This is the current behavior in RepositoryContextAssembler.engineAndPersonaContent().
 */
class RawPersonaContextProvider(
    private val db: Database,
    private val personaCoreVersionRepo: PersonaCoreVersionRepository,
) : PersonaContextProvider {

    override fun getPersonaContext(
        personaId: UUID,
        personaCoreVersionId: UUID,
        skillKey: String,
        currentUserMessage: String,
    ): String {
        val version = personaCoreVersionRepo.findById(personaCoreVersionId) ?: return ""
        return version.content
    }
}
