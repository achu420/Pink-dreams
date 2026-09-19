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
import com.pinkdreams.persistence.RepositoryChatExecutionCoordinator
import com.pinkdreams.persistence.RepositoryChatPersistence
import com.pinkdreams.persistence.repositories.ChatRequestExecutionRepository
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.IntentEngineRepository
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
    )

    fun build(deps: Dependencies): PipelineChatEngine {
        val llmClient = deps.llmClient
        val llmConfig = deps.llmConfig

        val generationConfig = GenerationConfig(model = llmConfig.model, maxOutputTokens = llmConfig.maxOutputTokens)
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
        val memoryExtractor = LlmMemoryExtractor(llmClient, GenerationConfig(model = llmConfig.model, maxOutputTokens = 1200))
        val memoryExtractionHook = BestEffortMemoryExtraction(
            extractor = memoryExtractor,
            memoryService = deps.memoryService,
            memoryScopeResolver = deps.memoryScopeResolver,
        )

        val continuitySummarizer = com.pinkdreams.chat.continuity.LlmContinuitySummarizer(
            llmClient, GenerationConfig(model = llmConfig.model, maxOutputTokens = 1000),
        )
        val continuitySummarizationHook = com.pinkdreams.chat.continuity.BestEffortContinuitySummarization(
            summarizer = continuitySummarizer,
            conversationRepository = deps.conversationRepository,
            messageRepository = deps.messageRepository,
            activeWindowSize = RepositoryContextAssembler.DEFAULT_MESSAGE_LIMIT,
        )

        val memoryEngineMaintainer = LlmMemoryEngineMaintainer(
            llmClient, deps.memoryEngineRepository, GenerationConfig(model = llmConfig.model, maxOutputTokens = 6000),
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

        val intentDiscovery = LlmIntentDiscovery(
            llmClient,
            deps.skillRepository,
            GenerationConfig(model = llmConfig.model, maxOutputTokens = 600),
            intentEngineRepository = deps.intentEngineRepository,
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
