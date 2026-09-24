package com.pinkdreams.chat.context

import com.pinkdreams.chat.personacontext.PersonaContextResolver
import com.pinkdreams.persistence.repositories.PersonaFieldRegistryRepository
import com.pinkdreams.persistence.repositories.PersonaMasterRepository
import com.pinkdreams.persistence.repositories.SkillContextRequirementRepository
import org.jetbrains.exposed.sql.Database
import java.util.UUID

/**
 * POC behavior: resolves persona fields based on skill requirements and user message relevance.
 * Supports variants A (full/raw), B (selective), and C (selective + behavioral core).
 */
class ResolvedPersonaContextProvider(
    private val db: Database,
    private val personaMasterRepo: PersonaMasterRepository,
    private val fieldRegistryRepo: PersonaFieldRegistryRepository,
    private val skillContextReqRepo: SkillContextRequirementRepository,
    private val variant: String = "B", // default to variant B if not specified
) : PersonaContextProvider {

    private val resolver = PersonaContextResolver(
        db,
        personaMasterRepo,
        fieldRegistryRepo,
        skillContextReqRepo,
    )

    override fun getPersonaContext(
        personaId: UUID,
        personaCoreVersionId: UUID,
        skillKey: String,
        currentUserMessage: String,
    ): String {
        val projection = resolver.resolve(
            personaId,
            personaCoreVersionId,
            skillKey,
            currentUserMessage,
            variant,
        )
        return projection.renderedText
    }
}
