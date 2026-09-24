package com.pinkdreams.chat

import com.pinkdreams.chat.context.PersonaContextProvider

/**
 * Resolves persona context after skill selection (with actual selected skill key).
 * NOTE: This is a test/POC-only stage. Production chat doesn't use this.
 * For now, this is a no-op stub. Real implementation requires plumbing personaId
 * through to this stage or using a different approach.
 */
class PersonaContextResolutionStage(
    private val personaContextProvider: PersonaContextProvider?,
) {
    /**
     * Refine persona context in ChatContext using the now-known selected skill.
     * Returns updated context with refined persona block (or unchanged if provider is null).
     *
     * TODO: This is stubbed for now. Full implementation requires:
     * 1. Passing personaId through ChatContext (currently only has personaCoreVersionId)
     * 2. OR: Refactoring PersonaContextProvider to work with just personaCoreVersionId
     * 3. OR: Using a different mechanism to access personaId at this stage
     *
     * For POC purposes, the selective persona resolution happens at context-assembly time
     * (in RepositoryContextAssembler) where we have access to personaId.
     */
    fun resolve(context: ChatContext): ChatContext {
        // Stub: No-op for now. Real implementation blocked by personaId availability.
        return context
    }
}
