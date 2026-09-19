package com.pinkdreams.chat.skill

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.llm.GenerationConfig
import com.pinkdreams.llm.GenerationRequest
import com.pinkdreams.llm.LlmClient
import com.pinkdreams.persistence.repositories.IntentEngineRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * LLM-backed intent discovery, following the same architectural shape as
 * LlmMemoryExtractor: a small, focused, dedicated LLM call — never the
 * persona generation prompt, never the complete conversation, never the full
 * skill content catalogue (only candidate KEYS, so the model can't invent
 * behavior text, only choose an identifier).
 *
 * Every failure mode (timeout, exception, malformed JSON, an unknown/inactive
 * key) resolves to SkillSelection.None — this call must never be able to
 * block or fail a chat turn.
 */
class LlmIntentDiscovery(
    private val client: LlmClient,
    private val skillRepository: SkillRepository,
    private val config: GenerationConfig = GenerationConfig(maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS),
    private val recentHistoryLimit: Int = DEFAULT_RECENT_HISTORY_LIMIT,
    // Phase ADMIN-2: the decision rules now come from the ACTIVE Intent Engine
    // version rather than a Kotlin string. Null (tests, or a deployment with no
    // Intent Engine seeded) keeps the previous behavior of using the seed text,
    // so wiring this in changed no existing test's expectations.
    private val intentEngineRepository: IntentEngineRepository? = null,
) : IntentDiscovery {

    @Serializable
    private data class SkillSelectionDto(val skillKey: String? = null)

    override fun selectSkill(request: ChatRequest, context: ChatContext): SkillSelection {
        return try {
            val candidateKeys = skillRepository.findAllActiveKeys()
            if (candidateKeys.isEmpty()) return SkillSelection.None

            val recent = recentHistoryBlocks(context, recentHistoryLimit)
            val instructions = resolveInstructions(candidateKeys) ?: return SkillSelection.None
            val discoveryContext = ChatContext(
                blocks = listOf(ContextBlock("system", instructions)) +
                    recent +
                    listOf(ContextBlock("user", request.content)),
                // Reused only to satisfy GenerationRequest's provenance fields for this
                // side-channel call; this is not persona generation and writes no message.
                engineVersionId = context.engineVersionId,
                personaCoreVersionId = context.personaCoreVersionId,
            )
            val engineVersionId = discoveryContext.engineVersionId ?: return SkillSelection.None
            val personaCoreVersionId = discoveryContext.personaCoreVersionId ?: return SkillSelection.None

            val generationRequest = GenerationRequest(
                requestId = request.requestId,
                userId = request.userId,
                conversationId = request.conversationId,
                personaId = request.personaId,
                engineVersionId = engineVersionId,
                personaCoreVersionId = personaCoreVersionId,
                context = discoveryContext,
                config = config,
            )

            val response = client.generate(generationRequest)
            val selectedKey = parseSkillKey(response.content) ?: return SkillSelection.None
            if (selectedKey !in candidateKeys) return SkillSelection.None

            SkillSelection.Selected(selectedKey)
        } catch (_: Exception) {
            // Intent discovery is best-effort and must never prevent normal generation.
            SkillSelection.None
        }
    }

    /**
     * Native user/assistant blocks only (never the leading system blocks or
     * the trailing current-message block, which is passed separately) —
     * identified structurally, the same way SkillContextEnricher locates the
     * history/current boundary, so this stays correct even as
     * RepositoryContextAssembler's own leading-block count evolves.
     */
    private fun recentHistoryBlocks(context: ChatContext, limit: Int): List<ContextBlock> {
        val firstNonSystemIndex = context.blocks.indexOfFirst { it.role != "system" }
        if (firstNonSystemIndex == -1) return emptyList()
        // Last element is always the current message (appended separately by the
        // assembler) — history is everything between the leading system blocks
        // and that final element.
        val history = context.blocks.subList(firstNonSystemIndex, context.blocks.size - 1)
        return history.takeLast(limit)
    }

    private fun parseSkillKey(rawContent: String): String? {
        // Reasoning models routinely prepend prose to the JSON in `content`; take
        // the JSON object out of the response rather than requiring the whole
        // response to be JSON. See JsonResponseExtractor.
        val cleaned = com.pinkdreams.llm.JsonResponseExtractor.extractJsonObject(rawContent) ?: stripMarkdownFences(rawContent)
        return try {
            val key = json.decodeFromString<SkillSelectionDto>(cleaned).skillKey?.trim()?.lowercase()
            key?.takeIf { it.isNotBlank() && it != "none" && it != "null" }
        } catch (_: Exception) {
            null
        }
    }

    private fun stripMarkdownFences(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.startsWith("```")) {
            trimmed.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        } else {
            trimmed
        }
    }

    /**
     * The decision rules come from the ACTIVE Intent Engine version; the
     * candidate keys are always supplied by the runtime from the ACTIVE skills.
     *
     * Returns null when an Intent Engine repository is configured but no
     * version is active — intent discovery then degrades to "no skill
     * selected" WITHOUT calling the LLM, exactly as the Memory Engine degrades
     * to doing nothing. There is deliberately no fallback to the seed text:
     * a second, invisible source of prompt truth is precisely what the
     * configuration layer exists to eliminate.
     */
    private fun resolveInstructions(candidateKeys: List<String>): String? {
        val template = if (intentEngineRepository == null) {
            IntentEngineDefaultContent.CONTENT
        } else {
            val active = intentEngineRepository.getActiveEngine()
            if (active == null) {
                System.err.println(
                    "INTENT_ENGINE: no active version — skill selection is disabled until an admin activates one.",
                )
                return null
            }
            active.content
        }
        return injectCandidateKeys(template, candidateKeys)
    }

    /**
     * Substitutes the placeholder, or appends the candidate section when an
     * edited prompt no longer contains it. The model is never left without the
     * list of keys it is allowed to return.
     */
    private fun injectCandidateKeys(template: String, candidateKeys: List<String>): String {
        val keyList = candidateKeys.joinToString(", ")
        return if (template.contains(IntentEngineDefaultContent.ACTIVE_SKILL_KEYS_PLACEHOLDER)) {
            template.replace(IntentEngineDefaultContent.ACTIVE_SKILL_KEYS_PLACEHOLDER, keyList)
        } else {
            "$template\n\nACTIVE SKILL KEYS\n$keyList"
        }
    }

    companion object {
        private const val DEFAULT_MAX_OUTPUT_TOKENS = 64
        private const val DEFAULT_RECENT_HISTORY_LIMIT = 4
        private val json = Json { ignoreUnknownKeys = true }
    }
}
