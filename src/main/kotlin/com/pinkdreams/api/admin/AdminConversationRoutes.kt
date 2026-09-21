package com.pinkdreams.api.admin

import com.pinkdreams.auth.AdminAuthorizationProvider
import com.pinkdreams.common.errors.ApiError
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.common.errors.ErrorResponse
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.LlmExchangeRepository
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import java.time.format.DateTimeFormatter
import java.util.UUID

@Serializable
data class AdminConversationSummaryResponse(
    val id: String,
    val userId: String,
    val personaId: String,
    val personaDisplayName: String?,
    val state: String,
    val executionMode: String,
    val lastMessageAt: String?,
    val createdAt: String,
)

@Serializable
data class AdminConversationListResponse(
    val conversations: List<AdminConversationSummaryResponse>,
)

@Serializable
data class AdminMessageResponse(
    val id: String,
    val role: String,
    val content: String,
    val requestId: String?,
    val engineVersionId: String?,
    val personaCoreVersionId: String?,
    val engineVersionNumber: Int?,
    val personaCoreVersionNumber: Int?,
    val clientMessageId: String?,
    val createdAt: String,
)

@Serializable
data class AdminMemoryFactResponse(
    val id: String,
    val fact: String,
    val factType: String,
    val criticality: String,
    val tier: String,
    val status: String,
    val source: String,
    val learnedAt: String,
)

@Serializable
data class AdminConversationDetailResponse(
    val conversation: AdminConversationSummaryResponse,
    val messages: List<AdminMessageResponse>,
    // Judgment call (Module 07): memory facts learned for this user+persona
    // relationship — not scoped to just this conversation, since facts are
    // relationship-scoped, not conversation-scoped (see MemoryFactRepository).
    // Surfaced so an admin debugging a conversation can see what the Memory
    // Engine believes about this user/persona pair while reading the transcript.
    val memoryFacts: List<AdminMemoryFactResponse>,
)

@Serializable
data class AdminLlmExchangeSummaryResponse(
    val id: String,
    val turnRequestId: String,
    val workload: String,
    val isTestChat: Boolean,
    val model: String?,
    val provider: String?,
    val latencyMs: Long,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val reasoningTokens: Int?,
    val totalTokens: Int?,
    val finishReason: String?,
    val httpStatusCode: Int?,
    val outcome: String,
    val errorClass: String?,
    val errorMessage: String?,
    val skillKey: String?,
    val requestBody: String?,
    val responseBody: String?,
    val createdAt: String,
)

@Serializable
data class AdminMessageExecutionResponse(
    val messageId: String,
    val requestId: String?,
    // All LLM exchanges sharing this message's turnRequestId — Intent
    // Discovery, primary generation, memory extraction, continuity, memory
    // engine maintenance can all fire within one turn (see
    // LlmExchangeRepository's doc comment), so a single message can surface
    // several exchange rows here, not just the one that produced its text.
    val exchanges: List<AdminLlmExchangeSummaryResponse>,
)

/**
 * Module 07 — Conversations Inspector (admin), backend routes.
 *
 * Deliberately thin: every read goes through the existing repositories
 * (ConversationRepository, MessageRepository, LlmExchangeRepository,
 * MemoryFactRepository) with no new query logic beyond
 * [ConversationRepository.findAllAdmin] (admin-wide listing) and resolving a
 * message's LLM exchanges via its `requestId` (== LlmExchanges.turnRequestId,
 * see RepositoryChatPersistence). No statistics/aggregation logic is
 * duplicated from AdminObservabilityRoutes.
 */
class AdminConversationRoutes(
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val memoryFactRepository: MemoryFactRepository,
    private val exchangeRepository: LlmExchangeRepository,
    private val personaRepository: PersonaRepository,
    private val personaCoreVersionRepository: PersonaCoreVersionRepository,
    private val conversationEngineRepository: ConversationEngineRepository,
    private val adminAuthorizationProvider: AdminAuthorizationProvider,
) {
    private val timestampFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    fun register(route: Route) {
        route.authenticate("session-auth", "dev-auth") {
            get("/v1/admin/conversations") {
                if (requirePrincipal() == null) return@get
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val userId = call.request.queryParameters["userId"]?.let { parseUuidOrNull(it) }
                val personaId = call.request.queryParameters["personaId"]?.let { parseUuidOrNull(it) }

                val conversations = conversationRepository.findAllAdmin(
                    limit = limit,
                    offset = offset,
                    userId = userId,
                    personaId = personaId,
                )
                call.respond(
                    HttpStatusCode.OK,
                    AdminConversationListResponse(conversations.map { it.toSummary() }),
                )
            }

            get("/v1/admin/conversations/{conversationId}") {
                if (requirePrincipal() == null) return@get
                val conversationId = call.parameters["conversationId"]?.let { parseUuidOrNull(it) }
                if (conversationId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid conversationId", null)))
                    return@get
                }
                val conversation = conversationRepository.findById(conversationId)
                if (conversation == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Conversation not found", null)))
                    return@get
                }
                val messages = messageRepository.findForConversation(conversationId).map { it.toResponse() }
                val memoryFacts = memoryFactRepository.findForRelationship(conversation.userId, conversation.personaId)
                    .map { it.toResponse() }
                call.respond(
                    HttpStatusCode.OK,
                    AdminConversationDetailResponse(
                        conversation = conversation.toSummary(),
                        messages = messages,
                        memoryFacts = memoryFacts,
                    ),
                )
            }

            get("/v1/admin/conversations/{conversationId}/messages/{messageId}/execution") {
                if (requirePrincipal() == null) return@get
                val conversationId = call.parameters["conversationId"]?.let { parseUuidOrNull(it) }
                val messageId = call.parameters["messageId"]?.let { parseUuidOrNull(it) }
                if (conversationId == null || messageId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid id", null)))
                    return@get
                }
                val message = messageRepository.findById(messageId)
                if (message == null || message.conversationId != conversationId) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Message not found", null)))
                    return@get
                }
                val exchanges = message.requestId?.let { exchangeRepository.findForTurn(it) } ?: emptyList()
                call.respond(
                    HttpStatusCode.OK,
                    AdminMessageExecutionResponse(
                        messageId = message.id.toString(),
                        requestId = message.requestId?.toString(),
                        exchanges = exchanges.map { it.toResponse() },
                    ),
                )
            }
        }
    }

    private suspend fun io.ktor.util.pipeline.PipelineContext<Unit, io.ktor.server.application.ApplicationCall>.requirePrincipal(): UserIdPrincipal? {
        val principal = call.principal<UserIdPrincipal>()
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, "Authentication required", null)))
            return null
        }
        if (!adminAuthorizationProvider.isAdmin(principal.name)) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse(ApiError(ErrorCode.ENTITLEMENT_DENIED, "Admin access required", null)))
            return null
        }
        return principal
    }

    private fun parseUuidOrNull(raw: String): UUID? = try {
        UUID.fromString(raw)
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun ConversationRepository.Conversation.toSummary(): AdminConversationSummaryResponse {
        val personaName = personaRepository.findById(personaId)?.displayName
        return AdminConversationSummaryResponse(
            id = id.toString(),
            userId = userId.toString(),
            personaId = personaId.toString(),
            personaDisplayName = personaName,
            state = state,
            executionMode = executionMode,
            lastMessageAt = lastMessageAt?.format(timestampFormatter),
            createdAt = createdAt.format(timestampFormatter),
        )
    }

    private fun MessageRepository.Message.toResponse(): AdminMessageResponse {
        // Resolve version IDs to human-meaningful version numbers for the
        // inspector; falls back to null if the version row is gone (never
        // happens per schema invariant "version histories never deleted",
        // but the endpoint should not blow up if it somehow did).
        val engineVersionNumber = engineVersionId?.let { conversationEngineRepository.findById(it)?.version }
        val personaCoreVersionNumber = personaCoreVersionId?.let { personaCoreVersionRepository.findById(it)?.version }
        return AdminMessageResponse(
            id = id.toString(),
            role = role,
            content = content,
            requestId = requestId?.toString(),
            engineVersionId = engineVersionId?.toString(),
            personaCoreVersionId = personaCoreVersionId?.toString(),
            engineVersionNumber = engineVersionNumber,
            personaCoreVersionNumber = personaCoreVersionNumber,
            clientMessageId = clientMessageId?.toString(),
            createdAt = createdAt.format(timestampFormatter),
        )
    }

    private fun MemoryFactRepository.MemoryFact.toResponse(): AdminMemoryFactResponse = AdminMemoryFactResponse(
        id = id.toString(),
        fact = fact,
        factType = factType,
        criticality = criticality,
        tier = tier,
        status = status,
        source = source,
        learnedAt = learnedAt.format(timestampFormatter),
    )

    private fun LlmExchangeRepository.Exchange.toResponse(): AdminLlmExchangeSummaryResponse = AdminLlmExchangeSummaryResponse(
        id = id.toString(),
        turnRequestId = turnRequestId.toString(),
        workload = workload,
        isTestChat = isTestChat,
        model = model,
        provider = provider,
        latencyMs = latencyMs,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        reasoningTokens = reasoningTokens,
        totalTokens = totalTokens,
        finishReason = finishReason,
        httpStatusCode = httpStatusCode,
        outcome = outcome.name,
        errorClass = errorClass,
        errorMessage = errorMessage,
        skillKey = skillKey,
        requestBody = requestBody,
        responseBody = responseBody,
        createdAt = createdAt.format(timestampFormatter),
    )
}
