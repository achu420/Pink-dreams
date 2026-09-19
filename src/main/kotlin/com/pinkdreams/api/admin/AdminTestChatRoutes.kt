package com.pinkdreams.api.admin

import com.pinkdreams.auth.AdminAuthorizationProvider
import com.pinkdreams.chat.ChatResult
import com.pinkdreams.common.errors.ApiError
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.common.errors.ErrorResponse
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.testchat.TestChatService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.util.pipeline.PipelineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

@Serializable
data class CreateTestChatRequest(
    val personaId: String,
    val testUserId: String,
    val conversationEngineVersion: Int? = null,
    val personaCoreVersion: Int? = null,
    val intentEngineVersion: Int? = null,
    val memoryEngineVersion: Int? = null,
    val skillVersions: Map<String, Int>? = null,
    val model: String? = null,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
)

@Serializable
data class ConfigurationSnapshotResponse(
    val conversationEngineVersion: Int,
    val personaCoreVersion: Int,
    val intentEngineVersion: Int,
    val memoryEngineVersion: Int,
    val skillVersions: Map<String, Int>,
    val model: String?,
    val temperature: Double?,
    val maxOutputTokens: Int?,
)

@Serializable
data class TestConversationResponse(
    val conversationId: String,
    val personaId: String,
    val testUserId: String,
    val mode: String,
    val configuration: ConfigurationSnapshotResponse,
)

@Serializable
data class SendTestMessageRequest(
    val content: String,
    val clientMessageId: String? = null,
)

/** Section 39 — enough for the admin UI to identify exactly what ran. */
@Serializable
data class SendTestMessageResponse(
    val conversationId: String,
    val messageId: String,
    val mode: String,
    val configuration: ConfigurationSnapshotResponse,
    val selectedSkill: String?,
    val content: String,
)

@Serializable
data class TestMessageDiagnosticsResponse(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: String,
    /** Present on assistant messages only — the actual generation diagnostics (section 20/21). */
    val contextBlocks: Map<String, String>? = null,
    val generationConfig: Map<String, String>? = null,
    val responseMetadata: Map<String, String>? = null,
    /** Provider request/response with secrets already redacted upstream (OpenRouterLlmClient) — section 57. */
    val providerExchange: Map<String, String>? = null,
)

@Serializable
data class TestConversationDetailResponse(
    val conversationId: String,
    val personaId: String,
    val testUserId: String,
    val mode: String,
    val configuration: ConfigurationSnapshotResponse,
    val messages: List<TestMessageDiagnosticsResponse>,
)

/**
 * Test/Production Chat admin API (Phase ADMIN-3). Deliberately its own route
 * tree under /v1/admin/test-chat, never a flag on the production
 * /v1/conversations routes (section 14) — a normal client has no path that
 * can reach test configuration.
 */
class AdminTestChatRoutes(
    private val testChatService: TestChatService,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val adminAuthorizationProvider: AdminAuthorizationProvider,
) {
    fun register(route: Route) {
        route.authenticate("dev-auth") {
            post("/v1/admin/test-chat/conversations") {
                if (requirePrincipal() == null) return@post
                val request = try {
                    call.receive<CreateTestChatRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid request format", null)))
                    return@post
                }
                val personaId = parseUuid(request.personaId) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid personaId", null)))
                    return@post
                }
                val testUserId = parseUuid(request.testUserId) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid testUserId", null)))
                    return@post
                }

                when (
                    val result = testChatService.create(
                        TestChatService.CreationRequest(
                            personaId = personaId,
                            testUserId = testUserId,
                            conversationEngineVersion = request.conversationEngineVersion,
                            personaCoreVersion = request.personaCoreVersion,
                            intentEngineVersion = request.intentEngineVersion,
                            memoryEngineVersion = request.memoryEngineVersion,
                            skillVersions = request.skillVersions,
                            model = request.model,
                            temperature = request.temperature,
                            maxOutputTokens = request.maxOutputTokens,
                        ),
                    )
                ) {
                    is TestChatService.CreationResult.Created -> {
                        call.respond(HttpStatusCode.Created, toResponse(result.conversation))
                    }
                    is TestChatService.CreationResult.Rejected -> {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, result.reason, null)))
                    }
                }
            }

            get("/v1/admin/test-chat/conversations/{id}") {
                if (requirePrincipal() == null) return@get
                val id = parseId() ?: return@get
                val conversation = testChatService.findConversation(id)
                if (conversation == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Test conversation not found", null)))
                    return@get
                }
                call.respond(HttpStatusCode.OK, toDetailResponse(conversation))
            }

            get("/v1/admin/test-chat/conversations/{id}/configuration") {
                if (requirePrincipal() == null) return@get
                val id = parseId() ?: return@get
                val conversation = testChatService.findConversation(id)
                if (conversation == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Test conversation not found", null)))
                    return@get
                }
                call.respond(HttpStatusCode.OK, toSnapshotResponse(conversation.snapshot!!))
            }

            post("/v1/admin/test-chat/conversations/{id}/messages") {
                if (requirePrincipal() == null) return@post
                val id = parseId() ?: return@post
                val request = try {
                    call.receive<SendTestMessageRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid request format", null)))
                    return@post
                }
                if (request.content.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "content must not be blank", null)))
                    return@post
                }
                val clientMessageId = request.clientMessageId?.let(::parseUuid) ?: UUID.randomUUID()

                when (val result = testChatService.sendMessage(id, clientMessageId, request.content)) {
                    is TestChatService.MessageResult.Rejected -> {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, result.reason, null)))
                    }
                    is TestChatService.MessageResult.Sent -> {
                        when (val chatResult = result.chatResult) {
                            is ChatResult.Success -> {
                                val selectedSkill = selectedSkillForMessage(chatResult.response.assistantMessageId)
                                call.respond(
                                    HttpStatusCode.OK,
                                    SendTestMessageResponse(
                                        conversationId = id.toString(),
                                        messageId = chatResult.response.assistantMessageId.toString(),
                                        mode = "TEST",
                                        configuration = toSnapshotResponse(result.conversation.snapshot!!),
                                        selectedSkill = selectedSkill,
                                        content = chatResult.response.content,
                                    ),
                                )
                            }
                            is ChatResult.Failure -> {
                                call.respond(
                                    HttpStatusCode.BadGateway,
                                    ErrorResponse(ApiError(chatResult.code, "Test chat generation failed: ${chatResult.code}", null)),
                                )
                            }
                            is ChatResult.InProgress -> {
                                call.respond(HttpStatusCode.Accepted, mapOf("requestId" to chatResult.requestId, "status" to "processing"))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun selectedSkillForMessage(assistantMessageId: UUID): String? {
        val message = messageRepository.findById(assistantMessageId) ?: return null
        val contextBlocks = parseMetadataSection(message.metadata, "lvm_context_blocks")
        val skillBlock = contextBlocks?.values?.firstOrNull { it.startsWith("SELECTED SKILL (") } ?: return null
        return skillBlock.substringAfter("SELECTED SKILL (").substringBefore(")")
    }

    private fun toResponse(conversation: ConversationRepository.Conversation): TestConversationResponse {
        val snapshot = requireNotNull(conversation.snapshot)
        return TestConversationResponse(
            conversationId = conversation.id.toString(),
            personaId = conversation.personaId.toString(),
            testUserId = conversation.userId.toString(),
            mode = conversation.executionMode,
            configuration = toSnapshotResponse(snapshot),
        )
    }

    private fun toDetailResponse(conversation: ConversationRepository.Conversation): TestConversationDetailResponse {
        val messages = messageRepository.findForConversation(conversation.id)
        return TestConversationDetailResponse(
            conversationId = conversation.id.toString(),
            personaId = conversation.personaId.toString(),
            testUserId = conversation.userId.toString(),
            mode = conversation.executionMode,
            configuration = toSnapshotResponse(conversation.snapshot!!),
            messages = messages.map { message ->
                TestMessageDiagnosticsResponse(
                    id = message.id.toString(),
                    role = message.role,
                    content = message.content,
                    createdAt = message.createdAt.toString(),
                    contextBlocks = parseMetadataSection(message.metadata, "lvm_context_blocks"),
                    generationConfig = parseMetadataSection(message.metadata, "lvm_config"),
                    responseMetadata = parseMetadataSection(message.metadata, "lvm_response_metadata"),
                    providerExchange = parseMetadataSection(message.metadata, "lvm_provider_exchange"),
                )
            },
        )
    }

    private fun toSnapshotResponse(snapshot: ConversationRepository.ConfigurationSnapshot) = ConfigurationSnapshotResponse(
        conversationEngineVersion = snapshot.conversationEngineVersion,
        personaCoreVersion = snapshot.personaCoreVersion,
        intentEngineVersion = snapshot.intentEngineVersion,
        memoryEngineVersion = snapshot.memoryEngineVersion,
        skillVersions = snapshot.skillVersions,
        model = snapshot.model,
        temperature = snapshot.temperature,
        maxOutputTokens = snapshot.maxOutputTokens,
    )

    /**
     * The stored `messages.metadata` is a JSON object whose values are
     * THEMSELVES JSON-encoded strings (see RepositoryChatPersistence
     * .buildMessageMetadata) — this unwraps one named section into a flat
     * String->String map for the admin UI, reusing the exact diagnostics
     * already persisted rather than recomputing or duplicating them (section 20).
     */
    private fun parseMetadataSection(metadata: String, section: String): Map<String, String>? {
        return try {
            val outer = Json.parseToJsonElement(metadata).jsonObject
            val raw = outer[section]?.jsonPrimitive?.content ?: return null
            Json.parseToJsonElement(raw).jsonObject.mapValues { (_, v) -> elementToString(v) }
        } catch (e: Exception) {
            null
        }
    }

    private fun elementToString(element: JsonElement): String = when (element) {
        is JsonNull -> ""
        is JsonPrimitive -> element.content
        else -> element.toString()
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.requirePrincipal(): UserIdPrincipal? {
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

    private suspend fun PipelineContext<Unit, ApplicationCall>.parseId(): UUID? =
        parseUuid(call.parameters["id"]) ?: run {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid conversation ID format", null)))
            null
        }

    private fun parseUuid(raw: String?): UUID? = try {
        raw?.let(UUID::fromString)
    } catch (e: Exception) {
        null
    }
}
