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
    val executionRepo = ChatRequestExecutionRepository(db)
    val memoryFactRepo = MemoryFactRepository(db)
    val memoryService = MemoryService(memoryFactRepo)

    // Initialize ChatEngine if not provided
    val engine = chatEngine ?: run {
        val llmClient = com.pinkdreams.llm.OpenRouterLlmClient(
            apiKey = llmConfig.apiKey,
            endpoint = llmConfig.endpoint,
            model = llmConfig.model,
            maxOutputTokens = llmConfig.maxOutputTokens,
            timeoutSeconds = llmConfig.timeoutSeconds,
        )
        val generationConfig = com.pinkdreams.llm.GenerationConfig(
            maxOutputTokens = llmConfig.maxOutputTokens
        )
        val llmGenerator = LlmGenerator(llmClient, config = generationConfig)
        PipelineChatEngine(
            entitlementChecker = { com.pinkdreams.chat.EntitlementDecision.Allowed },
            inputModerator = { com.pinkdreams.chat.ModerationDecision.Allowed },
            contextAssembler = RepositoryContextAssembler(
                conversationRepository = conversationRepo,
                messageRepository = messageRepo,
                userProfileRepository = userProfileRepo,
                memoryService = memoryService,
                engineRepository = engineRepo,
                personaRepository = personaRepo,
            ),
            generator = llmGenerator,
            outputValidator = { _, _ -> com.pinkdreams.chat.ValidationDecision.Accepted },
            persistence = RepositoryChatPersistence(db, executionRepo),
            delivery = { _, _ -> com.pinkdreams.chat.StageResult.Succeeded(Unit) },
            executionCoordinator = RepositoryChatExecutionCoordinator(executionRepo, conversationRepo, messageRepo),
        )
    }

    val authProvider = adminAuthProvider ?: AdminAuthorizationProvider()

    routing {
        HealthRoutes().register(this)
        ChatRoutes(engine, conversationRepo).register(this)
        ConversationHistoryRoutes(conversationRepo, messageRepo).register(this)
        AdminEngineRoutes(engineRepo, authProvider).register(this)
        AdminPersonaRoutes(personaRepo, coreVersionRepo, authProvider).register(this)
    }
}
