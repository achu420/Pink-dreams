package com.pinkdreams.chat

import com.pinkdreams.chat.context.CharacterTokenEstimator
import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.chat.context.TokenEstimator
import com.pinkdreams.chat.personacontext.production.ProductionPersonaContextService
import com.pinkdreams.chat.personacontext.production.resolvePersonaContent
import java.util.UUID

/**
 * Resolves persona context after skill selection, using the real,
 * model-selected skill key that only becomes available at this point in the
 * pipeline (Phase 48 Strategy D; Phase 49 implementation).
 *
 * Replaces ONLY the [ContextBlockType.PERSONA]-tagged block, produced by
 * [RepositoryContextAssembler] (populated there with today's unauthorized
 * `PersonaCoreVersions.content` baseline). Every other block — ENGINE,
 * PROFILE, MEMORY, SENSITIVE, CONTINUITY, SKILL, HISTORY, CURRENT_MESSAGE —
 * is left byte-for-byte untouched; this class never reconstructs or mutates
 * them.
 *
 * Delegates all authorization/rendering/fallback logic to
 * [ProductionPersonaContextService] (Phase 46/47, independently tested,
 * with NO dependency on the research `PersonaContextResolver` or
 * `resolveVariantA()`). This class's own job is narrowly: find the PERSONA
 * block, call the service with the real skill key and personaId, and decide
 * whether the result is safe to install given the turn's token budget.
 *
 * Never throws: any internal failure returns the input [ChatContext]
 * unchanged, so a failure here can never affect generation (the same
 * guarantee every other optional pipeline stage in [PipelineChatEngine]
 * already provides for skill selection, memory enrichment, and skill
 * context enrichment).
 */
class PersonaContextResolutionStage(
    private val personaContextService: ProductionPersonaContextService,
    private val tokenBudget: Int = RepositoryContextAssembler.DEFAULT_TOKEN_BUDGET,
    private val tokenEstimator: TokenEstimator = CharacterTokenEstimator,
) {
    /**
     * @param personaId the request's own personaId (never inferred, never a
     *   placeholder — callers must pass `request.personaId` directly).
     * @param currentUserMessage the request's own message content, used only
     *   as a relevance signal for conditional-field scoring (identical to
     *   how [RepositoryContextAssembler]'s own POC hook already uses it).
     */
    fun resolve(context: ChatContext, personaId: UUID, currentUserMessage: String): ChatContext {
        val skillKey = context.selectedSkillKey ?: return context
        val personaCoreVersionId = context.personaCoreVersionId ?: return context
        val personaIndex = context.blocks.indexOfFirst { it.blockType == ContextBlockType.PERSONA }
        if (personaIndex == -1) return context

        return try {
            val baselineBlock = context.blocks[personaIndex]
            // Resolve once via the diagnostic-carrying overload so this stage
            // can log status/field-count (observability) without a second
            // authorization pass — resolvePersonaContent(result, baseline) is
            // the exact same strict-substitution logic the string-only facade
            // method uses internally (PersonaContextFallback.kt), applied here
            // to the already-computed PersonaContextResult.
            val result = personaContextService.resolve(personaId, personaCoreVersionId, skillKey, currentUserMessage)
            val resolvedText = resolvePersonaContent(result, baselineBlock.content)

            logResolution(personaId, skillKey, result, usedReplacement = resolvedText != baselineBlock.content)

            // resolvedText == baselineBlock.content whenever the result was
            // not usable (ProductionPersonaContextService's own strict
            // substitution, Phase 45 D2) — nothing to replace, and no budget
            // check is needed since the block did not change.
            if (resolvedText == baselineBlock.content) return context

            val candidateBlocks = context.blocks.toMutableList().apply {
                set(personaIndex, baselineBlock.copy(content = resolvedText))
            }
            // Phase 49 Part 8: the replacement must never push the turn over
            // the same budget RepositoryContextAssembler already enforced.
            // Authorized/selective content is ordinarily smaller than the
            // full core.content baseline, but this is not guaranteed for
            // every future rendering policy — so re-check explicitly rather
            // than assume it. On overflow, keep the pre-resolution baseline
            // rather than truncate authorized content (truncation would
            // bypass the authorizer's own field-boundary decisions).
            if (totalTokens(candidateBlocks) > tokenBudget) {
                System.err.println("PERSONA_CONTEXT: budget_rejected personaId=$personaId skillKey=$skillKey")
                return context
            }

            context.copy(blocks = candidateBlocks)
        } catch (e: Exception) {
            System.err.println("PERSONA_CONTEXT: resolution_failed personaId=$personaId skillKey=$skillKey error=${e.javaClass.simpleName}")
            context
        }
    }

    /**
     * Structured, content-free observability (Phase 51/production-readiness
     * Part 13): status/field-count/IDs only — NEVER renderedText, NEVER any
     * persona document content, so this can never leak restricted field
     * VALUES even though it may log which field IDs (not values) were
     * included, which is itself non-sensitive metadata already visible in
     * persona_field_registry.
     */
    private fun logResolution(
        personaId: UUID,
        skillKey: String,
        result: com.pinkdreams.chat.personacontext.production.PersonaContextResult?,
        usedReplacement: Boolean,
    ) {
        if (result == null) {
            System.err.println("PERSONA_CONTEXT: resolve_null personaId=$personaId skillKey=$skillKey fallback=baseline")
            return
        }
        System.err.println(
            "PERSONA_CONTEXT: status=${result.status} personaId=$personaId skillKey=$skillKey " +
                "fieldCount=${result.includedFieldIds.size} usedReplacement=$usedReplacement",
        )
    }

    private fun totalTokens(blocks: List<ContextBlock>): Int = blocks.sumOf { tokenEstimator.estimate(it.content) }
}
