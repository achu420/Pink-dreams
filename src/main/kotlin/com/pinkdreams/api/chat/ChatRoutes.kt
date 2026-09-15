package com.pinkdreams.api.chat

import com.pinkdreams.chat.ChatEngine
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ChatResult
import com.pinkdreams.common.errors.ApiError
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.common.errors.ErrorResponse
import com.pinkdreams.persistence.repositories.ConversationRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.auth.authenticate
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class SendMessageRequest(
    val content: String,
)

@Serializable
data class MessageResponse(
    val id: String,
    val role: String,
    val content: String,
)

@Serializable
data class ConversationResponse(
    val id: String,
    val state: String,
)

@Serializable
data class SendMessageResponse(
    val requestId: String,
    val message: MessageResponse,
    val conversation: ConversationResponse,
)

class ChatRoutes(
    private val chatEngine: ChatEngine,
    private val conversationRepository: ConversationRepository,
) {
    fun register(route: Route) {
        route.authenticate("dev-auth") {
            post("/v1/conversations/{conversationId}/messages") {
                // Authentication
                val principal = call.principal<UserIdPrincipal>()
            if (principal == null) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, "Authentication required", null)),
                )
                return@post
            }

            val authenticatedUserId = UUID.fromString(principal.name)
            val conversationId = try {
                UUID.fromString(call.parameters["conversationId"])
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid conversation ID format", null)),
                )
                return@post
            }

            // Conversation ownership check
            val conversation = conversationRepository.findByIdForUser(conversationId, authenticatedUserId)
            if (conversation == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Conversation not found", null)),
                )
                return@post
            }

            // Request parsing
            val request = try {
                call.receive<SendMessageRequest>()
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid request format", null)),
                )
                return@post
            }

            // Request validation
            if (request.content.isBlank()) {
                call.response.status(HttpStatusCode.BadRequest)
                return@post
            }

            val idempotencyKey = call.request.headers["Idempotency-Key"]
            if (idempotencyKey.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Idempotency-Key header is required", null)),
                )
                return@post
            }

            val clientMessageId = try {
                UUID.fromString(idempotencyKey)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Idempotency-Key must be a valid UUID", null)),
                )
                return@post
            }

            // ChatEngine integration
            val chatRequest = ChatRequest(
                requestId = UUID.randomUUID(),
                userId = authenticatedUserId,
                conversationId = conversationId,
                personaId = conversation.personaId,
                clientMessageId = clientMessageId,
                content = request.content,
            )

            val result = chatEngine.process(chatRequest)

            // ChatResult → HTTP mapping
            when (result) {
                is ChatResult.Success -> {
                    call.respond(
                        HttpStatusCode.OK,
                        SendMessageResponse(
                            requestId = result.requestId.toString(),
                            message = MessageResponse(
                                id = result.response.assistantMessageId.toString(),
                                role = "assistant",
                                content = result.response.content,
                            ),
                            conversation = ConversationResponse(
                                id = conversationId.toString(),
                                state = conversation.state,
                            ),
                        ),
                    )
                }
                is ChatResult.Failure -> {
                    val (statusCode, code) = when (result.code) {
                        ErrorCode.ENTITLEMENT_DENIED -> HttpStatusCode.Forbidden to result.code
                        ErrorCode.MODERATION_BLOCKED -> HttpStatusCode.BadRequest to result.code
                        ErrorCode.GENERATION_FAILED -> HttpStatusCode.BadGateway to result.code
                        ErrorCode.VALIDATION_FAILED -> HttpStatusCode.InternalServerError to result.code
                        ErrorCode.PERSIST_FAILED -> HttpStatusCode.InternalServerError to result.code
                        ErrorCode.DELIVERY_FAILED -> HttpStatusCode.InternalServerError to result.code
                        else -> HttpStatusCode.InternalServerError to ErrorCode.INTERNAL_SERVER_ERROR
                    }
                    call.respond(
                        statusCode,
                        ErrorResponse(ApiError(code, code.toString(), result.requestId.toString())),
                    )
                }
                is ChatResult.InProgress -> {
                    call.respond(
                        HttpStatusCode.Accepted,
                        mapOf(
                            "requestId" to result.requestId,
                            "status" to "processing",
                        ),
                    )
                }
            }
        }
        }
    }
}
