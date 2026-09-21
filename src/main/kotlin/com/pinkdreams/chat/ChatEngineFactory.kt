package com.pinkdreams.chat

import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.chat.memory.BestEffortMemoryExtraction
import com.pinkdreams.chat.memory.DeterministicMemoryContextSelector
import com.pinkdreams.chat.memory.LlmMemoryExtractor
import com.pinkdreams.chat.memory.MemoryScopeResolver
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.chat.memory.ProductionMemoryScope
import com.pinkdreams.chat.memory.SkillAwareMemoryEnricher
import com.pinkdreams.chat.memoryengine.BestEffortMemoryEngineMaintenance
import com.pinkdreams.chat.memoryengine.LlmMemoryEngineMaintainer
import com.pinkdreams.chat.memoryengine.MemoryEngineChangeApplier
import com.pinkdreams.chat.sensitive.SensitivePreferenceService
import com.pinkdreams.chat.skill.LlmIntentDiscovery
import com.pinkdreams.chat.skill.SkillContextEnricher
import com.pinkdreams.config.AiRuntimeSettings
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.llm.GenerationConfig
import com.pinkdreams.llm.LlmClient
import com.pinkdreams.llm.LlmGenerator
import com.pinkdreams.llm.observability.ObservableLlmClient
import com.pinkdreams.persistence.RepositoryChatExecutionCoordinator
import com.pinkdreams.persistence.RepositoryChatPersistence
import com.pinkdreams.persistence.repositories.ChatRequestExecutionRepository
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.IntentEngineRepository
import com.pinkdreams.persistence.repositories.LlmExchangeRepository
import com.pinkdreams.persistence.repositories.MemoryEngineRepository
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository
import org.jetbrains.exposed.sql.Database

/**
 * The single construction path for a [PipelineChatEngine]. Used for BOTH the
 * one production engine built at startup and every per-snapshot engine built
 * for a TEST conversation (Phase ADMIN-3) — this is the literal mechanism
 * behind "Test Chat is the same AI pipeline running against a controlled
 * configuration snapshot" (section 59): the two callers differ only in which
 * repository instances they pass in ([Dependencies.engineRepository] etc. are
 * "Pinned*" subclasses for TEST — see `testchat/PinnedRepositories.kt`) and
 * which [MemoryScopeResolver] they use — every business-logic class here is
 * constructed and wired identically either way.
 */
object ChatEngineFactory {

    /**
     * Everything needed to build one [PipelineChatEngine]. Grouped into a data
     * class (rather than a long parameter list) so a TEST build can start from
     * the production `Dependencies` and `.copy()` only the handful of fields a
     * snapshot actually pins.
     */
    data class Dependencies(
        val llmClient: LlmClient,
        val llmConfig: LlmConfig,
        val db: Database,
        val conversationRepository: ConversationRepository,
        val messageRepository: MessageRepository,
        val userProfileRepository: UserProfileRepository,
        val memoryService: MemoryService,
        val memoryFactRepository: MemoryFactRepository,
        val engineRepository: ConversationEngineRepository,
        val personaRepository: PersonaRepository,
        val skillRepository: SkillRepository,
        val memoryEngineRepository: MemoryEngineRepository,
        val intentEngineRepository: IntentEngineRepository,
        val executionRepository: ChatRequestExecutionRepository,
        val aiRuntimeSettings: AiRuntimeSettings,
        val sensitivePreferenceService: SensitivePreferenceService? = null,
        // Phase ADMIN-3: PRODUCTION for the one startup engine, a
        // per-conversation TestMemoryScope for every TEST engine.
        val memoryScopeResolver: MemoryScopeResolver = ProductionMemoryScope,
        // Intent Discovery Model Latency Investigation phase: an isolated,
        // reversible per-workload override for Intent Discovery's model ONLY
        // — independent of llmConfig.model and of AiRuntimeSettings (which
        // governs primary generation and side-channel calls, but was never
        // read by Intent Discovery). Null (the default, always true for the
        // one production engine) means Intent Discovery keeps using
        // llmConfig.model exactly as before this field existed. Wired
        // through Test Chat's per-conversation snapshot (see
        // ConversationRepository.ConfigurationSnapshot.intentModel) so a
        // candidate model can be pinned and benchmarked live without
        // touching the production default.
        val intentModelOverride: String? = null,
        // Intent Discovery Budget Investigation phase: same pattern as
        // intentModelOverride above — an isolated, reversible per-workload
        // override for Intent Discovery's maxOutputTokens ONLY. Null (the
        // default, always true for the one production engine) means Intent
        // Discovery keeps using 600 exactly as before this field existed.
        val intentMaxOutputTokensOverride: Int? = null,
        // Make Intent Discovery Fast + Reliable phase: same pattern again —
        // an isolated, reversible override for Intent Discovery's
        // GenerationConfig.jsonMode ONLY. Null (the default) means Intent
        // Discovery's request is byte-identical to before this field existed.
        val intentJsonModeOverride: Boolean? = null,
        // LLM Observability and Raw Exchange Capture phase: explicit,
        // consistent with the other overrides above — false for the one
        // production engine, true for every Test Chat engine (set in
        // TestChatService.buildEngineFor()'s `.copy()`), so every persisted
        // exchange row records which traffic it came from without needing to
        // sniff repository types.
        val isTestChat: Boolean = false,
    )

    fun build(deps: Dependencies): PipelineChatEngine {
        val llmConfig = deps.llmConfig
        // LLM Observability and Raw Exchange Capture phase: the SAME shared
        // client every call site already used, wrapped exactly once here so
        // no call site (Intent, generation, memory extraction, continuity,
        // memory-engine maintenance) needs to change how it invokes the
        // client. See ObservableLlmClient's doc comment.
        val exchangeRepository = LlmExchangeRepository(deps.db)
        val llmClient: LlmClient = ObservableLlmClient(deps.llmClient, exchangeRepository, deps.isTestChat)

        val generationConfig = GenerationConfig(model = llmConfig.model, maxOutputTokens = llmConfig.maxOutputTokens, workload = "primary_generation")
        // Resolved per request (not captured here), so an admin change applies on
        // the next message rather than the next deploy.
        val llmGenerator = LlmGenerator(
            llmClient,
            config = generationConfig,
            configProvider = { deps.aiRuntimeSettings.generationConfig() },
        )

        // Dedicated, measured token budgets for every side-channel call — see
        // Phase D/ADMIN-2 history for why these values specifically. Test Chat
        // reuses them unchanged (section 58: no extra LLM call, no different
        // budget just because it's a test).
        //
        // Reasoning is intentionally left at the provider default (null) for
        // memory extraction, continuity, and memory-engine maintenance: all
        // three are async, post-delivery work — never on the user-facing
        // critical path — and each involves a genuine judgment call
        // ("is this durable/contradictory/worth remembering") that reasoning
        // may meaningfully help with. Disabling reasoning here would trade
        // background-call cost/duration for a real quality risk, for zero
        // user-facing latency benefit. See intentDiscovery below, where the
        // same tradeoff comes out the opposite way.
        val memoryExtractor = LlmMemoryExtractor(llmClient, GenerationConfig(model = llmConfig.model, maxOutputTokens = 1200, workload = "memory_extraction"))
        val memoryExtractionHook = BestEffortMemoryExtraction(
            extractor = memoryExtractor,
            memoryService = deps.memoryService,
            memoryScopeResolver = deps.memoryScopeResolver,
        )

        val continuitySummarizer = com.pinkdreams.chat.continuity.LlmContinuitySummarizer(
            llmClient, GenerationConfig(model = llmConfig.model, maxOutputTokens = 1000, workload = "continuity_summarization"),
        )
        val continuitySummarizationHook = com.pinkdreams.chat.continuity.BestEffortContinuitySummarization(
            summarizer = continuitySummarizer,
            conversationRepository = deps.conversationRepository,
            messageRepository = deps.messageRepository,
            activeWindowSize = RepositoryContextAssembler.DEFAULT_MESSAGE_LIMIT,
        )

        val memoryEngineMaintainer = LlmMemoryEngineMaintainer(
            llmClient, deps.memoryEngineRepository, GenerationConfig(model = llmConfig.model, maxOutputTokens = 6000, workload = "memory_engine_maintenance"),
        )
        val memoryEngineChangeApplier = MemoryEngineChangeApplier(deps.memoryFactRepository)
        val memoryEngineMaintenanceHook = BestEffortMemoryEngineMaintenance(
            maintainer = memoryEngineMaintainer,
            applier = memoryEngineChangeApplier,
            memoryService = deps.memoryService,
            conversationRepository = deps.conversationRepository,
            messageRepository = deps.messageRepository,
            memoryEngineRepository = deps.memoryEngineRepository,
            memoryScopeResolver = deps.memoryScopeResolver,
        )

        val postDeliveryMemoryExtraction = CompositePostDeliveryHook(
            listOf(memoryExtractionHook, continuitySummarizationHook, memoryEngineMaintenanceHook),
        )

        // Runtime Quality + Latency Verification phase — investigated and
        // REVERTED: reasoningEnabled=false (and, tried as a mitigation,
        // temperature=0.0) for Intent Discovery.
        //
        // A controlled, context-free A/B (25 cases, identical input, only
        // `reasoning` differing) showed no accuracy difference (92% both) and
        // a real latency win for reasoning-off — but that test never included
        // real recent-conversation history. A full live conversation of the
        // same 25 turns, WITH the real accumulating history Intent Discovery
        // actually receives in production, told a different story:
        // reasoning-ON selected a skill on 23/25 turns (92%); reasoning-OFF
        // (even after adding temperature=0 as a determinism fix) selected a
        // skill on only 6/25 (24%) — the model became far more likely to
        // return null once there was real prior context to weigh, and
        // per-call latency was still highly variable (up to ~13s), not the
        // consistent ~1-2s seen in the context-free test. temperature=0 made
        // this WORSE, not better, ruling out "wrong sampling config" as the
        // fix. See RuntimeLatencyReasoningTest / the phase report for the
        // full measurement trail. Net: reasoning is genuinely load-bearing
        // for this classifier once realistic context is involved, so both
        // knobs are left at the provider default (null) here. The
        // reasoning/temperature wiring in OpenRouterLlmClient itself is kept
        // — it is correct infrastructure and worth having available — it is
        // simply not exercised for this workload.
        val intentDiscoveryConfig = GenerationConfig(
            model = deps.intentModelOverride ?: llmConfig.model,
            maxOutputTokens = deps.intentMaxOutputTokensOverride ?: 600,
            jsonMode = deps.intentJsonModeOverride,
            workload = "intent_discovery",
        )
        val intentDiscovery = LlmIntentDiscovery(
            llmClient,
            deps.skillRepository,
            config = intentDiscoveryConfig,
            // Task 9 — Admin AI Runtime Controls. Resolved PER CALL (see
            // LlmIntentDiscovery's own doc comment), the same way
            // LlmGenerator resolves primary generation's config. Precedence:
            // deps.intentModelOverride/etc. is the highest-priority pin for
            // THIS ENGINE INSTANCE — for the one production engine it is
            // always null (Application.kt no longer sets a literal here; see
            // its own comment), so production always reaches
            // aiRuntimeSettings.resolve() and picks up the live DB override.
            // For a TEST CHAT engine, TestChatService.buildEngineFor() sets
            // this to the conversation's explicit pin (if any) or the
            // CURRENT resolved production value (if not) — either way it is
            // non-null and short-circuits here, so a Test Chat conversation
            // is never affected by an admin changing the DB setting
            // mid-conversation (it already captured the production value at
            // creation-equivalent resolution time via its own fallback).
            configProvider = {
                val resolved = deps.aiRuntimeSettings.resolve()
                GenerationConfig(
                    model = deps.intentModelOverride ?: resolved.intentModel,
                    maxOutputTokens = deps.intentMaxOutputTokensOverride ?: resolved.intentMaxOutputTokens,
                    jsonMode = deps.intentJsonModeOverride ?: resolved.intentJsonMode,
                    workload = "intent_discovery",
                )
            },
            intentEngineRepository = deps.intentEngineRepository,
            exchangeRepository = exchangeRepository,
        )
        val skillContextEnricher = SkillContextEnricher(deps.skillRepository)
        val memoryContextSelector = DeterministicMemoryContextSelector(deps.skillRepository)
        val memoryContextEnricher = SkillAwareMemoryEnricher(
            deps.memoryService,
            memoryContextSelector,
            memoryScopeResolver = deps.memoryScopeResolver,
        )

        return PipelineChatEngine(
            entitlementChecker = { EntitlementDecision.Allowed },
            inputModerator = { ModerationDecision.Allowed },
            contextAssembler = RepositoryContextAssembler(
                conversationRepository = deps.conversationRepository,
                messageRepository = deps.messageRepository,
                userProfileRepository = deps.userProfileRepository,
                memoryService = deps.memoryService,
                engineRepository = deps.engineRepository,
                personaRepository = deps.personaRepository,
                sensitivePreferenceService = deps.sensitivePreferenceService,
                memoryScopeResolver = deps.memoryScopeResolver,
            ),
            generator = llmGenerator,
            outputValidator = { _, _ -> ValidationDecision.Accepted },
            persistence = RepositoryChatPersistence(deps.db, deps.executionRepository),
            delivery = { _, _ -> StageResult.Succeeded(Unit) },
            executionCoordinator = RepositoryChatExecutionCoordinator(deps.executionRepository, deps.conversationRepository, deps.messageRepository),
            postDeliveryMemoryExtraction = postDeliveryMemoryExtraction,
            intentDiscovery = intentDiscovery,
            skillContextEnricher = skillContextEnricher,
            memoryContextEnricher = memoryContextEnricher,
        )
    }
}
