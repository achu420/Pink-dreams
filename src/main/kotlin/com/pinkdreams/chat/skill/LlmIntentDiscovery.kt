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
    // Task 9 — Admin AI Runtime Controls. Resolved PER CALL rather than
    // captured at construction, mirroring LlmGenerator's own
    // `configProvider` pattern exactly (Phase ADMIN-2) — this is what lets
    // an admin's persisted intentModel/intentJsonMode/intentMaxOutputTokens
    // override take effect on the next turn instead of the next redeploy.
    // Defaults to the constructor config, so every existing caller/test
    // behaves exactly as before.
    private val configProvider: () -> GenerationConfig = { config },
    private val recentHistoryLimit: Int = DEFAULT_RECENT_HISTORY_LIMIT,
    // Phase ADMIN-2: the decision rules now come from the ACTIVE Intent Engine
    // version rather than a Kotlin string. Null (tests, or a deployment with no
    // Intent Engine seeded) keeps the previous behavior of using the seed text,
    // so wiring this in changed no existing test's expectations.
    private val intentEngineRepository: IntentEngineRepository? = null,
    // LLM Observability and Raw Exchange Capture phase: optional so every
    // existing caller/test is unaffected. Used only to reclassify the
    // exchange row this call just wrote (via ObservableLlmClient) as
    // MALFORMED when the response was a valid HTTP success but did not
    // parse into a JSON object at all — a distinction the client-level
    // decorator cannot make on its own since it doesn't know Intent's
    // expected content shape.
    private val exchangeRepository: com.pinkdreams.persistence.repositories.LlmExchangeRepository? = null,
) : IntentDiscovery {

    @Serializable
    private data class SkillSelectionDto(val skillKey: String? = null)

    override fun selectSkill(request: ChatRequest, context: ChatContext): SkillSelection {
        // A failure resolving admin settings must never block skill selection:
        // fall back to the constructor config, which is always valid — same
        // convention as LlmGenerator.generate().
        val config = runCatching { configProvider() }.getOrDefault(config)
        return try {
            val candidateKeys = skillRepository.findAllActiveKeys()
            if (candidateKeys.isEmpty()) return SkillSelection.None

            val recent = recentHistoryBlocks(context, recentHistoryLimit)
            val baseInstructions = resolveInstructions(candidateKeys) ?: return SkillSelection.None
            // Make Intent Discovery Fast + Reliable phase: appended at
            // request-construction time only — the stored Intent Engine
            // content itself is never modified. Required for two reasons:
            // (1) it is the explicit output-contract reinforcement that
            // measurably eliminated GPT-4o-mini's malformed-output rate
            // (13.3% -> 0% in an 85-case validation), and (2) OpenRouter's
            // json_object response_format requires the literal word "json"
            // to appear somewhere in the request messages, which the stored
            // v3 content does not otherwise contain.
            val instructions = if (config.jsonMode == true) {
                baseInstructions + "\n\n" + JSON_MODE_REINFORCEMENT
            } else {
                baseInstructions
            }
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
            val foundJsonObject = com.pinkdreams.llm.JsonResponseExtractor.extractJsonObject(response.content) != null
            val selectedKey = parseSkillKey(response.content)
            logDiagnostics(request, response.metadata, parsedOk = foundJsonObject)
            if (!foundJsonObject) {
                try {
                    exchangeRepository?.markMalformed(request.requestId, "intent_discovery")
                } catch (_: Exception) {
                    // Diagnostics enrichment must never affect routing.
                }
            }
            if (selectedKey == null) return SkillSelection.None
            if (selectedKey !in candidateKeys) return SkillSelection.None

            SkillSelection.Selected(selectedKey)
        } catch (e: com.pinkdreams.llm.OpenRouterBudgetExhaustionException) {
            // Live Intent Model Comparison phase: structured diagnostic for the
            // one failure mode that matters most to distinguish from a genuine
            // None decision — never inferred from latency, only from the
            // provider's own finish_reason/token evidence.
            System.err.println(
                "INTENT_DIAGNOSTICS: conversation=${request.conversationId} requestId=${request.requestId} " +
                    "outcome=BUDGET_EXHAUSTION finishReason=${e.finishReason} completionTokens=${e.completionTokens} " +
                    "reasoningTokens=${e.reasoningTokens}",
            )
            SkillSelection.None
        } catch (_: Exception) {
            // Intent discovery is best-effort and must never prevent normal generation.
            SkillSelection.None
        }
    }

    private fun logDiagnostics(request: ChatRequest, metadata: Map<String, String>?, parsedOk: Boolean) {
        System.err.println(
            "INTENT_DIAGNOSTICS: conversation=${request.conversationId} requestId=${request.requestId} " +
                "outcome=${if (parsedOk) "COMPLETED" else "MALFORMED"} " +
                "finishReason=${metadata?.get("finish_reason")} promptTokens=${metadata?.get("prompt_tokens")} " +
                "completionTokens=${metadata?.get("completion_tokens")} reasoningTokens=${metadata?.get("reasoning_tokens")}",
        )
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

        // Make Intent Discovery Fast + Reliable phase. Deliberately code-owned,
        // not part of the versioned Intent Engine content — see the call site.
        const val JSON_MODE_REINFORCEMENT =
            "Respond with a single JSON object only, exactly {\"skillKey\": \"...\"} or " +
                "{\"skillKey\": null} — no prose, no explanation, no markdown fences, nothing else."

        // Intent Context Minimization & Routing Optimization phase: measured
        // four smaller alternatives against this 4-turn window using a
        // controlled 85-case context-free benchmark plus 8 realistic
        // growing-conversation scenarios covering the named ambiguous skill
        // pairs (emotional_support/confidence_building, flirting/
        // flirting_practice, foreplay/sexual_stimulation, dating/
        // social_practice, relationship_discussion/relationship_guidance,
        // etc.), all run with reasoning left ON (required — see the Runtime
        // Quality + Latency Verification phase).
        //
        // Result: USER_ONLY (no history), RECENT_2, and RELEVANT_CONTEXT
        // (last 1 turn) each dropped scenario accuracy from 7/8 to 6/8,
        // reproducibly missing the exact cases the history window exists to
        // resolve — e.g. "are you flirting with me right now?" needs the
        // preceding flirting_practice roleplay setup to disambiguate from
        // flirting, and a bare "yeah" needs the preceding turns to correctly
        // resolve to no skill rather than a false positive. A
        // COMPACT_SUMMARY strategy (reusing the existing
        // LlmContinuitySummarizer, per architecture rules — no new summary
        // engine was created) matched this window's 7/8 accuracy but gave
        // no material prompt-size or latency reduction: the ~1550-1600
        // token Intent prompt is dominated by the fixed routing
        // instructions and active-skill-key list, not by conversation
        // history (removing all history saved only ~60 tokens, ~4% of the
        // prompt). Reasoning-token generation, not context size, is what
        // drives Intent latency variance (0-900+ reasoning tokens per call
        // in the benchmark) — this matches the prior phase's finding that
        // reasoning must stay enabled. Conclusion: 4 native turns is
        // already the smallest context that does not measurably harm
        // routing quality, and no alternative offered a material
        // efficiency gain to justify the switch. Left unchanged.
        private const val DEFAULT_RECENT_HISTORY_LIMIT = 4
        private val json = Json { ignoreUnknownKeys = true }
    }
}
