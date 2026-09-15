package com.pinkdreams.api.conversation

import com.pinkdreams.common.errors.ApiError
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.common.errors.ErrorResponse
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.auth.authenticate
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ConversationListItemResponse(
    val id: String,
    val state: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class ConversationListResponse(
    val conversations: List<ConversationListItemResponse>,
)

@Serializable
data class ConversationDetailResponse(
    val id: String,
    val state: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class MessageListItemResponse(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: String,
)

@Serializable
data class MessageListResponse(
    val messages: List<MessageListItemResponse>,
)

class ConversationHistoryRoutes(
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
) {
    companion object {
        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 100
    }

    fun register(route: Route) {
        route.authenticate("dev-auth") {
            // GET /v1/conversations
            get("/v1/conversations") {
                val principal = call.principal<UserIdPrincipal>()
                if (principal == null) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, "Authentication required", null)),
                    )
                    return@get
                }

                val authenticatedUserId = UUID.fromString(principal.name)
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                if (limit <= 0 || limit > MAX_LIMIT || offset < 0) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid pagination parameters", null)),
                    )
                    return@get
                }

                val conversations = conversationRepository.findAllForUser(authenticatedUserId, limit, offset)
                val response = ConversationListResponse(
                    conversations = conversations.map { conv ->
                        ConversationListItemResponse(
                            id = conv.id.toString(),
                            state = conv.state,
                            createdAt = conv.createdAt.toString(),
                            updatedAt = conv.lastMessageAt?.toString() ?: conv.createdAt.toString(),
                        )
                    },
                )
                call.respond(HttpStatusCode.OK, response)
            }

            // GET /v1/conversations/{conversationId}
            get("/v1/conversations/{conversationId}") {
                val principal = call.principal<UserIdPrincipal>()
                if (principal == null) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, "Authentication required", null)),
                    )
                    return@get
                }

                val authenticatedUserId = UUID.fromString(principal.name)
                val conversationId = try {
                    UUID.fromString(call.parameters["conversationId"])
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid conversation ID format", null)),
                    )
                    return@get
                }

                val conversation = conversationRepository.findByIdForUser(conversationId, authenticatedUserId)
                if (conversation == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Conversation not found", null)),
                    )
                    return@get
                }

                val response = ConversationDetailResponse(
                    id = conversation.id.toString(),
                    state = conversation.state,
                    createdAt = conversation.createdAt.toString(),
                    updatedAt = conversation.lastMessageAt?.toString() ?: conversation.createdAt.toString(),
                )
                call.respond(HttpStatusCode.OK, response)
            }

            // GET /v1/conversations/{conversationId}/messages
            get("/v1/conversations/{conversationId}/messages") {
                val principal = call.principal<UserIdPrincipal>()
                if (principal == null) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, "Authentication required", null)),
                    )
                    return@get
                }

                val authenticatedUserId = UUID.fromString(principal.name)
                val conversationId = try {
                    UUID.fromString(call.parameters["conversationId"])
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid conversation ID format", null)),
                    )
                    return@get
                }

                val conversation = conversationRepository.findByIdForUser(conversationId, authenticatedUserId)
                if (conversation == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Conversation not found", null)),
                    )
                    return@get
                }

                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                if (limit <= 0 || limit > MAX_LIMIT || offset < 0) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid pagination parameters", null)),
                    )
                    return@get
                }

                val messages = messageRepository.findForConversationPaginated(conversationId, limit, offset)
                val response = MessageListResponse(
                    messages = messages
                        .filter { it.role != "system" }
                        .map { msg ->
                            MessageListItemResponse(
                                id = msg.id.toString(),
                                role = msg.role,
                                content = msg.content,
                                createdAt = msg.createdAt.toString(),
                            )
                        },
                )
                call.respond(HttpStatusCode.OK, response)
            }
        }
    }
}
