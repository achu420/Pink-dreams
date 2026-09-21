package com.pinkdreams.llm

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import java.util.UUID

data class GenerationConfig(
    val model: String? = null,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    // Runtime Quality + Latency Verification phase finding: reasoning-capable
    // models "think" before answering EVERY call, including trivial
    // classification-style side-channel calls (intent discovery, memory
    // extraction, memory-engine maintenance, continuity summarization) that
    // don't need it. Live-measured: the identical intent-discovery prompt
    // took 2.4s-20.5s with reasoning on, ~2s consistently with it off, same
    // correct answer. Null (the default) leaves the provider's default
    // behavior untouched — this must stay null for primary generation, where
    // reasoning is part of the established response quality/behavior and is
    // not something this phase touches.
    val reasoningEnabled: Boolean? = null,
    // Make Intent Discovery Fast + Reliable phase: requests the provider's
    // native JSON-object structured-output mode (OpenAI-family models via
    // OpenRouter). Null/false leaves the provider's default behavior
    // untouched — this must stay null everywhere except Intent Discovery
    // when using a model that supports it (verified: gpt-4o-mini). The
    // provider requires the literal word "json" to appear in the request
    // messages when this is enabled; see LlmIntentDiscovery's
    // JSON_MODE_REINFORCEMENT, added at the request-construction level (not
    // stored Intent Engine content) specifically to satisfy that.
    val jsonMode: Boolean? = null,
    // Primary Generation Latency phase: requests OpenRouter's
    // latency-optimized provider routing (`provider: {sort: "latency"}`).
    // Same model, same weights — this only biases which upstream host
    // serves the request. Evidence: a live 30-turn conversation showed
    // generation latency ranging 368ms-50077ms for structurally similar
    // requests with no prompt/completion-size correlation; a direct replay
    // of the exact slow request reproduced the same wide swing (368ms-
    // 4546ms across 5 calls with no provider preference), while repeated
    // sort=latency calls stayed tight (648-1192ms across 15 calls) —
    // though a later larger no-preference sample also stayed tight,
    // suggesting the worst spikes are transient provider-side incidents,
    // not a deterministic routing defect this setting is guaranteed to
    // eliminate. Null (the default) leaves provider routing unchanged.
    val providerSort: String? = null,
    // LLM Observability and Raw Exchange Capture phase: an explicit,
    // code-owned tag identifying which workload this call belongs to
    // (e.g. "intent_discovery", "primary_generation", "memory_extraction",
    // "continuity_summarization", "memory_engine_maintenance"). Set once at
    // each call site in ChatEngineFactory — deliberately explicit rather
    // than inferred from system-prompt content (the ad-hoc pattern this
    // codebase's own tests and log lines previously relied on), so exchange
    // records never depend on prompt wording staying stable.
    val workload: String? = null,
)

data class GenerationRequest(
    val requestId: UUID,
    val userId: UUID,
    val conversationId: UUID,
    val personaId: UUID,
    val engineVersionId: UUID,
    val personaCoreVersionId: UUID,
    val context: ChatContext,
    val config: GenerationConfig,
    // Task 8 Part 4 — the skill SELECTED for this turn at the time this call
    // was made (null for SkillSelection.None, or any call independent of
    // skill selection, e.g. intent_discovery itself). Sourced from
    // ChatContext.selectedSkillKey so it reaches every downstream call that
    // reuses the same enriched context — today, primary generation.
    val skillKey: String? = null,
) {
    companion object {
        fun from(request: ChatRequest, context: ChatContext, config: GenerationConfig): GenerationRequest {
            val engineVersionId = context.engineVersionId
                ?: throw IllegalArgumentException("Context is missing the engine version ID")
            val personaCoreVersionId = context.personaCoreVersionId
                ?: throw IllegalArgumentException("Context is missing the persona core version ID")
            return GenerationRequest(
                requestId = request.requestId,
                userId = request.userId,
                conversationId = request.conversationId,
                personaId = request.personaId,
                engineVersionId = engineVersionId,
                personaCoreVersionId = personaCoreVersionId,
                context = context,
                config = config,
                skillKey = context.selectedSkillKey,
            )
        }
    }
}

data class LlmResponse(
    val content: String,
    val provider: String? = null,
    val model: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val providerExchange: ProviderExchange? = null,
)

fun interface LlmClient {
    fun generate(request: GenerationRequest): LlmResponse
}

class FakeLlmClient(
    private val response: LlmResponse? = null,
    private val failure: RuntimeException? = null,
) : LlmClient {
    var lastRequest: GenerationRequest? = null
        private set

    override fun generate(request: GenerationRequest): LlmResponse {
        lastRequest = request
        failure?.let { throw it }
        return response ?: LlmResponse(content = "fake response", provider = "fake")
    }
}