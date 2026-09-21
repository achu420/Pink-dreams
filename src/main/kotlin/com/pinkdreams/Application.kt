package com.pinkdreams

import com.pinkdreams.api.admin.AdminEngineRoutes
import com.pinkdreams.api.admin.AdminPersonaRoutes
import com.pinkdreams.api.chat.ChatRoutes
import com.pinkdreams.api.conversation.ConversationHistoryRoutes
import com.pinkdreams.api.health.HealthRoutes
import com.pinkdreams.auth.AdminAuthorizationProvider
import com.pinkdreams.auth.DevAuthProvider
import com.pinkdreams.chat.ChatEngine
import com.pinkdreams.chat.PipelineChatEngine
import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.chat.memory.BestEffortMemoryExtraction
import com.pinkdreams.chat.memory.MemoryExtractor
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.persistence.RepositoryChatExecutionCoordinator
import com.pinkdreams.persistence.RepositoryChatPersistence
import com.pinkdreams.common.errors.ApiError
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.common.errors.ErrorResponse
import com.pinkdreams.config.AppConfig
import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.llm.FakeLlmClient
import com.pinkdreams.llm.LlmGenerator
import com.pinkdreams.persistence.database.DatabaseFactory
import org.jetbrains.exposed.sql.Database
import com.pinkdreams.persistence.repositories.ChatRequestExecutionRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository
import com.pinkdreams.persistence.repositories.UserRepository
import java.util.concurrent.Executor
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json

fun main() {
    val appConfig = AppConfig.load()
    val databaseConfig = DatabaseConfig.from(appConfig)
    val llmConfig = LlmConfig.from(appConfig)

    embeddedServer(Netty, port = appConfig.port, host = appConfig.host) {
        module(databaseConfig = databaseConfig, llmConfig = llmConfig)
    }.start(wait = true)
}

fun Application.module(
    databaseConfig: DatabaseConfig = DatabaseConfig.from(AppConfig.load()),
    llmConfig: LlmConfig = LlmConfig.from(AppConfig.load()),
    chatEngine: ChatEngine? = null,
    conversationRepository: ConversationRepository? = null,
    adminAuthProvider: AdminAuthorizationProvider? = null,
    database: Database? = null,
) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            explicitNulls = false
        })
    }

    install(CallLogging) {
        filter { call -> call.request.path().startsWith("/v1") || call.request.path() == "/health" }
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            val apiError = when (cause) {
                is ApiError -> cause
                else -> ApiError(
                    code = ErrorCode.INTERNAL_SERVER_ERROR,
                    message = "An unexpected error occurred",
                    requestId = call.request.headers["X-Request-Id"],
                )
            }
            call.respond(HttpStatusCode.fromValue(apiError.httpStatus.value), ErrorResponse(apiError))
        }
    }

    DevAuthProvider().install(this)

    // Initialize database and repositories if not provided
    val db = database ?: DatabaseFactory.connect(databaseConfig)
    val conversationRepo = conversationRepository ?: ConversationRepository(db)
    val personaRepo = PersonaRepository(db)
    val coreVersionRepo = PersonaCoreVersionRepository(db)
    val engineRepo = ConversationEngineRepository(db)
    val messageRepo = MessageRepository(db)
    val userProfileRepo = UserProfileRepository(db)
    val userRepo = UserRepository(db)
    val executionRepo = ChatRequestExecutionRepository(db)
    val memoryFactRepo = MemoryFactRepository(db)
    val memoryService = MemoryService(memoryFactRepo)
    val sensitivePreferenceRepo = com.pinkdreams.persistence.repositories.SensitivePreferenceRepository(db)
    val sensitivePreferenceService = com.pinkdreams.chat.sensitive.SensitivePreferenceService(sensitivePreferenceRepo)
    val skillRepo = com.pinkdreams.persistence.repositories.SkillRepository(db)
    val memoryEngineRepo = com.pinkdreams.persistence.repositories.MemoryEngineRepository(db)
    val intentEngineRepo = com.pinkdreams.persistence.repositories.IntentEngineRepository(db)
    val aiSettingsRepo = com.pinkdreams.persistence.repositories.AiSettingsRepository(db)
    // Phase ADMIN-2 — one authoritative resolution of AI runtime settings:
    // DB setting → environment variable → application default. With no row in
    // ai_settings this yields exactly the previous values, so adding this layer
    // cannot change a running deployment's behavior. Constructed once here and
    // shared by generation and the admin routes, so both report the same thing.
    // Primary Generation Latency phase: promoted from Test-Chat-validated
    // candidate to production default. A live 30-turn A/B (same model, same
    // weights — this only changes which upstream host OpenRouter routes to)
    // showed generation p50 5026ms->2280ms, p95 34871ms->12728ms, and
    // eliminated all >20s turns (16.7%->0%) with no observed quality
    // change. See AiRuntimeSettings.generationConfig() for how this reaches
    // only primary generation, never side-channel calls.
    // Task 9 — Admin AI Runtime Controls. The four *Default params below are
    // the exact same production-validated literals that used to live only as
    // hardcoded ChatEngineFactory.Dependencies constructor args further down
    // this file (intentModelOverride = "openai/gpt-4o-mini", etc.) — moved
    // here so AiRuntimeSettings.resolve() is the ONE place that decides
    // "DB override, or this code default" for every one of these settings,
    // rather than that precedence being duplicated across Application.kt,
    // ChatEngineFactory, and TestChatService. With an empty ai_settings
    // table, resolve() yields exactly these same values — no behavior change
    // for a fresh deployment.
    val aiRuntimeSettings = com.pinkdreams.config.AiRuntimeSettings(
        llmConfig,
        aiSettingsRepo,
        generationProviderSortOverride = "latency",
        intentModelDefault = "openai/gpt-4o-mini",
        intentJsonModeDefault = true,
        intentMaxOutputTokensDefault = 600,
    )
    // Only seed for a genuine application startup (no database explicitly
    // injected) — never for tests, which pass their own isolated `database`
    // and must see empty configuration tables unless they seed them themselves.
    if (database == null) try {
        val report = com.pinkdreams.baseline.BaselineSeeder(
            conversationEngineRepository = engineRepo,
            personaRepository = personaRepo,
            personaCoreVersionRepository = coreVersionRepo,
            skillRepository = skillRepo,
            memoryEngineRepository = memoryEngineRepo,
            intentEngineRepository = intentEngineRepo,
        ).seedIfMissing()
        System.err.println(
            "BASELINE_SEED: conversationEngine=${report.conversationEngine} persona=${report.persona} " +
                "personaCore=${report.personaCore} memoryEngine=${report.memoryEngine} " +
                "intentEngine=${report.intentEngine} " +
                "skillsCreated=${report.skillsCreated.size} skillsAlreadyPresent=${report.skillsAlreadyPresent.size}",
        )
    } catch (e: Exception) {
        // Seeding is a startup convenience only — never block application startup.
        System.err.println("BASELINE_SEED: failed, continuing without seeding: ${e.javaClass.simpleName}: ${e.message}")
    }

    val llmClient = if (System.getenv("OPENROUTER_API_KEY") != null) {
        com.pinkdreams.llm.OpenRouterLlmClient(
            apiKey = llmConfig.apiKey,
            endpoint = llmConfig.endpoint,
            model = llmConfig.model,
            maxOutputTokens = llmConfig.maxOutputTokens,
            timeoutSeconds = llmConfig.timeoutSeconds,
        )
    } else {
        com.pinkdreams.llm.FakeLlmClient()
    }
    // Phase ADMIN-3: production and every Test Chat engine now share one
    // construction path (ChatEngineFactory) — see its doc comment. This
    // Dependencies bundle IS the production configuration; Test Chat builds its
    // own per-conversation engine by copying this bundle and swapping in
    // "Pinned" repositories (testchat/PinnedRepositories.kt) plus a
    // TestMemoryScope, never by duplicating this wiring.
    val chatEngineDependencies = com.pinkdreams.chat.ChatEngineFactory.Dependencies(
        llmClient = llmClient,
        llmConfig = llmConfig,
        db = db,
        conversationRepository = conversationRepo,
        messageRepository = messageRepo,
        userProfileRepository = userProfileRepo,
        memoryService = memoryService,
        memoryFactRepository = memoryFactRepo,
        engineRepository = engineRepo,
        personaRepository = personaRepo,
        skillRepository = skillRepo,
        memoryEngineRepository = memoryEngineRepo,
        intentEngineRepository = intentEngineRepo,
        executionRepository = executionRepo,
        aiRuntimeSettings = aiRuntimeSettings,
        sensitivePreferenceService = sensitivePreferenceService,
        // Task 9 — Admin AI Runtime Controls. These three stay null for the
        // ONE production engine (previously "openai/gpt-4o-mini/true/null"
        // literals here — see the Make Intent Discovery Fast + Reliable
        // phase history for why those values were chosen; they are
        // unchanged, just relocated to aiRuntimeSettings's intentModelDefault/
        // intentJsonModeDefault above). Null here means "no per-instance pin
        // for this engine" — the production engine always resolves Intent
        // config live through `aiRuntimeSettings.resolve()` inside
        // ChatEngineFactory.build() instead, which is what makes an admin's
        // DB change apply on the next request with no redeploy. This field
        // is still used by Test Chat's own per-conversation pin — see
        // TestChatService.buildEngineFor().
        intentModelOverride = null,
        intentJsonModeOverride = null,
    )

    // Initialize ChatEngine if not provided
    val engine = chatEngine ?: com.pinkdreams.chat.ChatEngineFactory.build(chatEngineDependencies)

    // Phase ADMIN-3 — Test Chat. Shares `chatEngineDependencies` (the same
    // production configuration) and builds its own per-conversation engine
    // from it per message — see TestChatService.
    val testChatService = com.pinkdreams.testchat.TestChatService(
        productionDependencies = chatEngineDependencies,
        conversationRepository = conversationRepo,
        personaRepository = personaRepo,
        personaCoreVersionRepository = coreVersionRepo,
        skillRepository = skillRepo,
        userRepository = userRepo,
    )

    val authProvider = adminAuthProvider ?: AdminAuthorizationProvider()

    routing {
        HealthRoutes().register(this)
        ChatRoutes(engine, conversationRepo, memoryFactRepo).register(this)
        ConversationHistoryRoutes(conversationRepo, messageRepo, userRepository = userRepo).register(this)
        AdminEngineRoutes(engineRepo, authProvider).register(this)
        AdminPersonaRoutes(personaRepo, coreVersionRepo, authProvider).register(this)
        com.pinkdreams.api.admin.AdminSkillRoutes(skillRepo, authProvider).register(this)
        com.pinkdreams.api.admin.AdminMemoryEngineRoutes(memoryEngineRepo, authProvider).register(this)
        com.pinkdreams.api.admin.AdminIntentEngineRoutes(intentEngineRepo, skillRepo, authProvider).register(this)
        com.pinkdreams.api.admin.AdminAiSettingsRoutes(
            aiSettingsRepository = aiSettingsRepo,
            aiRuntimeSettings = aiRuntimeSettings,
            conversationEngineRepository = engineRepo,
            intentEngineRepository = intentEngineRepo,
            memoryEngineRepository = memoryEngineRepo,
            skillRepository = skillRepo,
            personaRepository = personaRepo,
            personaCoreVersionRepository = coreVersionRepo,
            adminAuthorizationProvider = authProvider,
        ).register(this)
        com.pinkdreams.api.admin.AdminUserRoutes(
            userRepository = userRepo,
            userProfileRepository = userProfileRepo,
            provisioning = com.pinkdreams.persistence.AdminUserProvisioning(db, userRepo, userProfileRepo),
            adminAuthorizationProvider = authProvider,
        ).register(this)
        com.pinkdreams.api.admin.AdminTestChatRoutes(
            testChatService = testChatService,
            conversationRepository = conversationRepo,
            messageRepository = messageRepo,
            adminAuthorizationProvider = authProvider,
        ).register(this)
        com.pinkdreams.api.admin.AdminObservabilityRoutes(
            exchangeRepository = com.pinkdreams.persistence.repositories.LlmExchangeRepository(db),
            metricsRepository = com.pinkdreams.persistence.repositories.PerformanceMetricsRepository(db),
            adminAuthorizationProvider = authProvider,
            // Task 9 — aiRuntimeSettings.resolve() is now the sole source for
            // Effective Configuration; no separate override fields needed.
            aiRuntimeSettings = aiRuntimeSettings,
        ).register(this)
    }
}
