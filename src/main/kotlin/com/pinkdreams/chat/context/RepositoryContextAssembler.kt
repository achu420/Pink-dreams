package com.pinkdreams.chat.context

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ContextAssembler
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.StageResult
import com.pinkdreams.chat.memory.MemoryScopeResolver
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.chat.memory.ProductionMemoryScope
import com.pinkdreams.chat.sensitive.SensitivePreferenceRelevance
import com.pinkdreams.chat.sensitive.SensitivePreferenceService
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SensitivePreferenceRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository

class RepositoryContextAssembler(
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val userProfileRepository: UserProfileRepository,
    private val memoryService: MemoryService,
    private val engineRepository: ConversationEngineRepository,
    private val personaRepository: PersonaRepository,
    private val memoryLimit: Int = MemoryService.DEFAULT_SELECTION_LIMIT,
    private val messageLimit: Int = DEFAULT_MESSAGE_LIMIT,
    private val tokenBudget: Int = DEFAULT_TOKEN_BUDGET,
    private val tokenEstimator: TokenEstimator = CharacterTokenEstimator,
    // Optional so every pre-existing caller/test is unaffected: absent (null)
    // service or an irrelevant current message both mean no sensitive-preference
    // block is added at all — never an empty placeholder — so block count/order
    // for callers that don't wire this in is byte-identical to before Phase 4B.
    private val sensitivePreferenceService: SensitivePreferenceService? = null,
    // Phase ADMIN-3: see MemoryScopeResolver. Default preserves pre-ADMIN-3
    // behavior exactly for every existing caller/test.
    private val memoryScopeResolver: MemoryScopeResolver = ProductionMemoryScope,
) : ContextAssembler {
    override fun assemble(request: ChatRequest): StageResult<ChatContext> {
        val conversation = conversationRepository.findByIdForUser(request.conversationId, request.userId)
            ?: run {
                System.err.println("CONTEXT_ASSEMBLY: Conversation not found for conversationId=${request.conversationId} userId=${request.userId}")
                return StageResult.Failed(com.pinkdreams.common.errors.ErrorCode.VALIDATION_FAILED)
            }
        require(conversation.personaId == request.personaId) { "Conversation persona mismatch" }

        val engine = engineRepository.getActiveEngine()
            ?: run {
                System.err.println("CONTEXT_ASSEMBLY: No active engine found")
                return StageResult.Failed(com.pinkdreams.common.errors.ErrorCode.VALIDATION_FAILED)
            }
        val core = personaRepository.getActiveCoreVersion(request.personaId)
            ?: run {
                System.err.println("CONTEXT_ASSEMBLY: No active core version found for personaId=${request.personaId}")
                return StageResult.Failed(com.pinkdreams.common.errors.ErrorCode.VALIDATION_FAILED)
            }
        val profile = userProfileRepository.findByUserId(request.userId)
        val scopedPersonaId = memoryScopeResolver.resolve(request.userId, request.personaId, request.conversationId)
        val memories = memoryService.selectForContext(request.userId, scopedPersonaId, memoryLimit)
        val messages = messageRepository.findForConversation(request.conversationId).takeLast(messageLimit)

        val block0 = ContextBlock("system", engineAndPersonaContent(engine.content, core.content))
        val block1 = ContextBlock("system", profileContent(profile))
        val block2Facts = memories.toMutableList()
        val block3Messages = messages.toMutableList()
        // Absent entirely (not an empty placeholder block) unless the conversation has
        // actually exceeded the active history window at least once — short
        // conversations therefore assemble byte-identical blocks to before this existed.
        var continuityBlock: ContextBlock? = conversation.continuitySummary
            ?.takeIf { it.isNotBlank() }
            ?.let { ContextBlock("system", continuityContent(it)) }

        // Absent (not an empty placeholder) unless a service is wired in AND this
        // turn's current message gives a concrete reason to surface it — normal
        // conversation must not receive the full sensitive-preference set merely
        // because it exists (see SensitivePreferenceRelevance).
        var sensitiveBlock: ContextBlock? = sensitivePreferenceService
            ?.takeIf { SensitivePreferenceRelevance.isRelevant(request.content) }
            ?.selectForContext(request.userId, request.personaId)
            ?.takeIf { it.isNotEmpty() }
            ?.let { ContextBlock("system", sensitivePreferenceContent(it)) }

        val blockCurrent = ContextBlock("user", request.content)

        // The current message is mandatory and always included, but its size must
        // still count against the budget — otherwise a large current message could
        // push the real total past tokenBudget even though the loop reported "fits".
        while (totalTokens(
                listOfNotNull(
                    block0,
                    block1,
                    ContextBlock("system", block2Content(block2Facts, conversation.lastMessageAt)),
                    sensitiveBlock,
                    continuityBlock,
                ) + historyBlocks(block3Messages) + listOf(blockCurrent),
            ) > tokenBudget
        ) {
            if (block2Facts.isNotEmpty()) {
                block2Facts.removeAt(block2Facts.lastIndex)
            } else if (sensitiveBlock != null) {
                // Dropped as a whole unit (never a partially-truncated preference
                // record) once memory is exhausted, before continuity or history.
                sensitiveBlock = null
            } else if (continuityBlock != null) {
                // Continuity is trimmed as a whole unit (not per-sentence) after memory
                // is exhausted, before touching native history — it remains durably
                // stored regardless and will reappear once budget allows on a later turn.
                continuityBlock = null
            } else if (block3Messages.isNotEmpty()) {
                block3Messages.removeAt(0)
            } else {
                break
            }
        }

        val block2 = ContextBlock("system", block2Content(block2Facts, conversation.lastMessageAt))
        return StageResult.Succeeded(
            ChatContext(
                blocks = listOfNotNull(block0, block1, block2, sensitiveBlock, continuityBlock) + historyBlocks(block3Messages) + listOf(blockCurrent),
                engineVersionId = engine.id,
                personaCoreVersionId = core.id,
                // Task 24 — pure attribution carriers, all read off objects this
                // method already loaded for assembly. No extra query, no change
                // to any block's content or order.
                engineVersion = engine.version,
                personaCoreVersion = core.version,
                // The memory facts that SURVIVED budget trimming, i.e. the ones
                // actually rendered into block2 above — identifiers only.
                memoryIdsUsed = block2Facts.map { it.id },
                memoryCandidateCount = memories.size,
                memorySelectionSource = "CONTEXT_ASSEMBLER",
                userProfilePresent = profile != null,
                userProfileUpdatedAt = profile?.updatedAt?.toString(),
            ),
        )
    }

    private fun continuityContent(summary: String): String = buildString {
        append("EARLIER CONVERSATION CONTEXT (compact summary of older messages, for continuity only):\n")
        append(summary)
        append("\nThis summary is background only — any more recent message below is authoritative if it conflicts with anything here.")
    }

    /**
     * Converts persisted history rows into native provider-role blocks — one
     * ContextBlock per message, role taken verbatim from the persisted row.
     * Never wraps history into a single flattened system transcript, and never
     * infers or rewrites a role from message content.
     */
    private fun historyBlocks(messages: List<MessageRepository.Message>): List<ContextBlock> =
        messages.map { ContextBlock(it.role, it.content) }

    private fun engineAndPersonaContent(engineContent: String, personaCoreContent: String): String = buildString {
        append("CONVERSATION ENGINE — UNIVERSAL RULES:\n")
        append(engineContent)
        append("\n\nPERSONA CORE:\n")
        append(personaCoreContent)
    }

    private fun profileContent(profile: UserProfileRepository.UserProfile?): String = buildString {
        append("USER PROFILE:\n")
        if (profile == null) {
            append("(no profile record exists for this user)")
        } else {
            append("display_name: ").append(profile.displayName ?: "(not set)")
            append("\nage: ").append(profile.age?.toString() ?: "(not set)")
            append("\ngender: ").append(profile.gender ?: "(not set)")
            append("\ncity: ").append(profile.city ?: "(not set)")
            append("\ninterest: ").append(profile.interest ?: "(not set)")
            append("\npreferred_language: ").append(profile.preferredLanguage ?: "(not set)")
            append("\ncommunication_style: ").append(profile.communicationStyle ?: "(not set)")
        }
    }

    private fun sensitivePreferenceContent(
        preferences: List<SensitivePreferenceRepository.SensitivePreference>,
    ): String = buildString {
        append("SENSITIVE USER PREFERENCES (explicit, user-stated; distinct from persona preferences and from ordinary memory):\n")
        preferences.forEach { pref ->
            append(pref.category).append(' ').append(pref.preferenceType).append(": ").append(pref.content).append('\n')
        }
    }

    private fun block2Content(
        facts: List<com.pinkdreams.persistence.repositories.MemoryFactRepository.MemoryFact>,
        lastMessageAt: java.time.LocalDateTime?,
    ): String = buildString {
        append(com.pinkdreams.chat.memory.MemoryContextFormat.render(facts))
        // DEAD IN PRODUCTION (documented by Task 25F fix 5, deliberately left
        // in place rather than removed or rewired). SkillAwareMemoryEnricher
        // replaces this whole block wholesale with its own re-ranked
        // MemoryContextFormat.render(selected), which carries no continuity
        // suffix — and that enricher is configured on every production path, so
        // this line never reaches the model. Verified against real traffic:
        // "continuity: Last message at" appears in 0 of 40 stored
        // primary_generation request bodies, while "RETRIEVED MEMORY:" appears
        // in all 40. No test anywhere asserts this string in a FINAL prompt, and
        // nothing documents an intent that it survive enrichment, so the wiring
        // was NOT changed: making it reach the model would be a new prompt
        // change, not a repair. It still renders in the assembler's own output,
        // which remains the fail-open fallback if enrichment throws.
        if (lastMessageAt != null) append("continuity: Last message at ").append(lastMessageAt)
    }

    private fun totalTokens(blocks: List<ContextBlock>): Int = blocks.sumOf { tokenEstimator.estimate(it.content) }

    companion object {
        const val DEFAULT_MESSAGE_LIMIT = 10
        const val DEFAULT_TOKEN_BUDGET = 8192
    }
}

fun interface TokenEstimator {
    fun estimate(content: String): Int
}

object CharacterTokenEstimator : TokenEstimator {
    override fun estimate(content: String): Int = (content.length + 3) / 4
}