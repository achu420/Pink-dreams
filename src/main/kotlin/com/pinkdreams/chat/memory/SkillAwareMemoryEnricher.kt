package com.pinkdreams.chat.memory

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.context.CharacterTokenEstimator
import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.chat.context.TokenEstimator
import com.pinkdreams.chat.skill.SkillSelection

/**
 * Phase C bridge between Memory and Skill. Independently retrieves a wider
 * CANDIDATE pool (candidateLimit, default 20) from MemoryService — separate
 * from RepositoryContextAssembler's own default CONTEXT-sized fetch
 * (contextLimit, default 10, unchanged from Phase 4A/4B) — applies the
 * skill-aware selector, and REPLACES the assembler's already-built
 * "RETRIEVED MEMORY:" block with the re-ranked result.
 *
 * That replacement is wholesale: anything the assembler appended AFTER its
 * rendered memory list is dropped too. Today that is exactly one thing — the
 * "continuity: Last message at <timestamp>" suffix in
 * RepositoryContextAssembler.block2Content — which therefore never reaches the
 * model in production (Task 25F fix 5; see that method's own comment for the
 * live evidence). Documented, not changed: restoring it would be a new prompt
 * change, not a repair.
 *
 * Never deletes canonical memory: MemoryFactRepository/MemoryService are
 * never written here, only read. "Not selected" means "not sent this turn."
 *
 * Runs strictly after skill selection (needs the selected skill as a
 * relevance signal) and before skill context injection, per the Phase C
 * pipeline order. If anything here fails, the caller (PipelineChatEngine)
 * catches it and the ORIGINAL context — already containing the assembler's
 * correct, skill-agnostic top-N memory block — is used unchanged. That
 * default block is therefore the fail-open fallback, not a separate code path.
 */
class SkillAwareMemoryEnricher(
    private val memoryService: MemoryService,
    private val selector: MemoryContextSelector,
    private val candidateLimit: Int = DEFAULT_CANDIDATE_LIMIT,
    private val tokenBudget: Int = RepositoryContextAssembler.DEFAULT_TOKEN_BUDGET,
    private val tokenEstimator: TokenEstimator = CharacterTokenEstimator,
    // Phase ADMIN-3: which (userId, personaId) memory is actually read from.
    // Defaults to the real persona (pre-ADMIN-3 behavior, unchanged for every
    // existing caller) — a TEST conversation supplies TestMemoryScope instead.
    private val memoryScopeResolver: MemoryScopeResolver = ProductionMemoryScope,
) {
    fun enrich(context: ChatContext, request: ChatRequest, selection: SkillSelection): ChatContext {
        val memoryBlockIndex = context.blocks.indexOfFirst { it.role == "system" && it.content.startsWith("RETRIEVED MEMORY:") }
        if (memoryBlockIndex == -1) return context // defensive: no memory block to replace (e.g. hand-built test context)

        val scopedPersonaId = memoryScopeResolver.resolve(request.userId, request.personaId, request.conversationId)
        val candidates = memoryService.selectForContext(request.userId, scopedPersonaId, candidateLimit)
        // Best-first, per MemoryContextSelector's contract — trimming (below) drops
        // from the end, i.e. the lowest-ranked selected memories, mirroring
        // RepositoryContextAssembler's own "drop memory facts one at a time" step
        // rather than introducing a second, competing budget mechanism.
        val selected = selector.select(request.content, selection, candidates).toMutableList()

        val otherBlocksTotal = context.blocks.filterIndexed { i, _ -> i != memoryBlockIndex }.sumOf { tokenEstimator.estimate(it.content) }
        while (selected.isNotEmpty() && otherBlocksTotal + tokenEstimator.estimate(MemoryContextFormat.render(selected)) > tokenBudget) {
            selected.removeAt(selected.lastIndex)
        }

        val newBlock = ContextBlock("system", MemoryContextFormat.render(selected))
        val newBlocks = context.blocks.toMutableList().apply { set(memoryBlockIndex, newBlock) }
        return context.copy(
            blocks = newBlocks,
            // Task 24 Part 8 — the four memory populations are genuinely
            // different and this is the ONLY point where the last one is known:
            // `candidates` is what was RETRIEVED from MemoryService,
            // `selector.select(...)` is what was SELECTED, and `selected` after
            // budget trimming is what is ACTUALLY INJECTED — which is what these
            // identifiers record. (What is AVAILABLE in total is not attributed:
            // counting it would need a second, unbudgeted repository query on the
            // critical path, which Part 18 forbids.) IDs only, never fact text.
            memoryIdsUsed = selected.map { it.id },
            memoryCandidateCount = candidates.size,
            memorySelectionSource = "SKILL_AWARE_SELECTOR",
        )
    }

    companion object {
        const val DEFAULT_CANDIDATE_LIMIT = 20
    }
}
