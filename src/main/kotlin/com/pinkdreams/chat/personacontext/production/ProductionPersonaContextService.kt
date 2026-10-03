package com.pinkdreams.chat.personacontext.production

import java.util.UUID

/**
 * The production-facing facade (Phase 47 Part B).
 *
 * Owns the full sequence: authorize/select/render (via [PersonaFieldAuthorizer])
 * → classify (via [PersonaContextResult.isUsable]) → safely fall back to the
 * caller-supplied baseline content when the result is not usable (via
 * [resolvePersonaContent]).
 *
 * Deliberately contains NO raw-document fallback, NO unrestricted
 * serialization, and NO research-variant/benchmark logic — it does nothing
 * but call [PersonaFieldAuthorizer] and apply the strict-substitution
 * fallback rule. It is the single intended call site for any future
 * production caller (e.g. `RepositoryContextAssembler`) to obtain persona
 * content — see PHASE47_PRODUCTION_INTEGRATION.md §3 for why this class,
 * rather than `RepositoryContextAssembler` itself, owns this sequence.
 *
 * NOT wired into `RepositoryContextAssembler` or `Application.kt` in this
 * phase — see PHASE47_PRODUCTION_INTEGRATION.md §5/§12/§13 for why
 * (unresolved skill-timing decision).
 */
class ProductionPersonaContextService(
    private val authorizer: PersonaFieldAuthorizer,
    /**
     * TECHNICAL DEFAULT ONLY — NOT a product decision. `BareScalarRendering`
     * is used here only because a compiling default is required for this
     * class to be constructible/testable; it most closely matches today's
     * literal (no-labeling) production behavior if this facade were ever
     * activated as-is. Selecting the actual production rendering policy
     * remains Phase 45 §14 item 4, explicitly unresolved. Callers may
     * inject any [ScalarRenderingPolicy] at construction time.
     */
    private val renderingPolicy: ScalarRenderingPolicy = BareScalarRendering,
) {

    /**
     * Resolves the persona-content string a caller should use for this
     * request: the authorized, rendered Persona Context if usable, or
     * [coreContent] (the caller's existing `PersonaCoreVersions.content`
     * baseline) otherwise. Never throws — any internal failure is treated
     * as non-usable and falls back to [coreContent], per Phase 45 D2's
     * strict-substitution invariant, generalized to include exceptions
     * (Phase 45 §3's "provider/resolver failure" row).
     */
    fun resolvePersonaContent(
        personaId: UUID,
        personaCoreVersionId: UUID,
        skillKey: String,
        currentUserMessage: String,
        coreContent: String,
    ): String {
        val result = try {
            authorizer.resolve(personaId, personaCoreVersionId, skillKey, currentUserMessage, renderingPolicy)
        } catch (e: Exception) {
            null
        }
        return resolvePersonaContent(result, coreContent)
    }

    /** Exposes the raw [PersonaContextResult] for callers that need the status/diagnostics, not just the final string. */
    fun resolve(
        personaId: UUID,
        personaCoreVersionId: UUID,
        skillKey: String,
        currentUserMessage: String,
    ): PersonaContextResult? = try {
        authorizer.resolve(personaId, personaCoreVersionId, skillKey, currentUserMessage, renderingPolicy)
    } catch (e: Exception) {
        null
    }
}
