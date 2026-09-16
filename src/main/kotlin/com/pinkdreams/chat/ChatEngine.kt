package com.pinkdreams.chat

import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.chat.memory.CompletedTurn
import com.pinkdreams.chat.memory.NoopPostDeliveryMemoryExtraction
import com.pinkdreams.chat.memory.PostDeliveryMemoryExtraction
import java.util.UUID

data class ChatRequest(
    val requestId: UUID,
    val userId: UUID,
    val conversationId: UUID,
    val personaId: UUID,
    val clientMessageId: UUID,
    val content: String,
)

enum class PipelineStage {
    RECEIVED,
    ENTITLEMENT_CHECK,
    INPUT_MODERATION,
    CONTEXT_ASSEMBLY,
    GENERATION,
    OUTPUT_VALIDATION,
    REGENERATE,
    PERSIST,
    DELIVER,
}

data class PipelineState(
    val currentStage: PipelineStage,
    val visitedStages: List<PipelineStage>,
)

sealed interface ChatResult {
    val requestId: UUID

    data class Success(
        override val requestId: UUID,
        val response: PersistedResponse,
        val state: PipelineState,
    ) : ChatResult

    data class Failure(
        override val requestId: UUID,
        val code: ErrorCode,
        val state: PipelineState,
    ) : ChatResult

    data class InProgress(
        override val requestId: UUID,
        val state: PipelineState,
    ) : ChatResult
}

data class ChatContext(
    val blocks: List<ContextBlock>,
    val engineVersionId: UUID? = null,
    val personaCoreVersionId: UUID? = null,
)

data class ContextBlock(val role: String, val content: String)

data class LlmExecutionDiagnostics(
    val contextBlocks: List<ContextBlock>?,
    val generationConfig: Map<String, String>?,
    val llmResponseMetadata: Map<String, String>?,
)

data class GenerationResponse(
    val content: String,
    val engineVersionId: UUID? = null,
    val personaCoreVersionId: UUID? = null,
    val providerMetadata: Map<String, String> = emptyMap(),
    val executionDiagnostics: LlmExecutionDiagnostics? = null,
)

data class PersistedResponse(
    val assistantMessageId: UUID,
    val content: String,
)

sealed interface StageResult<out T> {
    data class Succeeded<T>(val value: T) : StageResult<T>

    data class Failed(val code: ErrorCode) : StageResult<Nothing>
}

sealed interface EntitlementDecision {
    data object Allowed : EntitlementDecision

    data object Denied : EntitlementDecision
}

sealed interface ModerationDecision {
    data object Allowed : ModerationDecision

    data object Blocked : ModerationDecision
}

sealed interface ValidationDecision {
    data object Accepted : ValidationDecision

    data class Rejected(val reason: String = "") : ValidationDecision
}

fun interface EntitlementChecker {
    fun check(request: ChatRequest): EntitlementDecision
}

fun interface InputModerator {
    fun moderate(request: ChatRequest): ModerationDecision
}

fun interface ContextAssembler {
    fun assemble(request: ChatRequest): StageResult<ChatContext>
}

fun interface Generator {
    fun generate(request: ChatRequest, context: ChatContext): StageResult<GenerationResponse>
}

fun interface OutputValidator {
    fun validate(request: ChatRequest, response: GenerationResponse): ValidationDecision
}

fun interface ChatPersistence {
    fun persist(request: ChatRequest, response: GenerationResponse): StageResult<PersistedResponse>
}

fun interface ChatDelivery {
    fun deliver(request: ChatRequest, response: PersistedResponse): StageResult<Unit>
}

sealed interface ExecutionClaim {
    data object Winner : ExecutionClaim

    data class Completed(val response: PersistedResponse) : ExecutionClaim

    data object Processing : ExecutionClaim
}

interface ChatExecutionCoordinator {
    fun claim(request: ChatRequest): StageResult<ExecutionClaim>

    fun fail(request: ChatRequest, code: ErrorCode)
}

object NoopChatExecutionCoordinator : ChatExecutionCoordinator {
    override fun claim(request: ChatRequest): StageResult<ExecutionClaim> =
        StageResult.Succeeded(ExecutionClaim.Winner)

    override fun fail(request: ChatRequest, code: ErrorCode) = Unit
}

interface ChatEngine {
    fun process(request: ChatRequest): ChatResult
}

class PipelineChatEngine(
    private val entitlementChecker: EntitlementChecker,
    private val inputModerator: InputModerator,
    private val contextAssembler: ContextAssembler,
    private val generator: Generator,
    private val outputValidator: OutputValidator,
    private val persistence: ChatPersistence,
    private val delivery: ChatDelivery,
    private val executionCoordinator: ChatExecutionCoordinator = NoopChatExecutionCoordinator,
    private val postDeliveryMemoryExtraction: PostDeliveryMemoryExtraction = NoopPostDeliveryMemoryExtraction,
) : ChatEngine {
    override fun process(request: ChatRequest): ChatResult {
        val state = PipelineState(PipelineStage.RECEIVED, listOf(PipelineStage.RECEIVED))

        when (val claim = executionCoordinator.claim(request)) {
            is StageResult.Failed -> return failure(request, claim.code, state, PipelineStage.RECEIVED, false)
            is StageResult.Succeeded -> when (val execution = claim.value) {
                ExecutionClaim.Winner -> Unit
                is ExecutionClaim.Completed -> return deliverExisting(request, execution.response, state)
                ExecutionClaim.Processing -> return ChatResult.InProgress(request.requestId, state)
            }
        }

        if (entitlementChecker.check(request) == EntitlementDecision.Denied) {
            return failure(request, ErrorCode.ENTITLEMENT_DENIED, state, PipelineStage.ENTITLEMENT_CHECK)
        }
        val afterEntitlement = state.advance(PipelineStage.ENTITLEMENT_CHECK)

        if (inputModerator.moderate(request) == ModerationDecision.Blocked) {
            return failure(request, ErrorCode.MODERATION_BLOCKED, afterEntitlement, PipelineStage.INPUT_MODERATION)
        }
        val afterModeration = afterEntitlement.advance(PipelineStage.INPUT_MODERATION)

        val context = when (val result = contextAssembler.assemble(request)) {
            is StageResult.Failed -> return failure(request, result.code, afterModeration, PipelineStage.CONTEXT_ASSEMBLY)
            is StageResult.Succeeded -> result.value
        }
        val afterContext = afterModeration.advance(PipelineStage.CONTEXT_ASSEMBLY)

        val generated = when (val result = generator.generate(request, context)) {
            is StageResult.Failed -> return failure(request, result.code, afterContext, PipelineStage.GENERATION)
            is StageResult.Succeeded -> result.value
        }
        val afterGeneration = afterContext.advance(PipelineStage.GENERATION)

        val firstValidation = try {
            outputValidator.validate(request, generated)
        } catch (_: Exception) {
            return failure(request, ErrorCode.VALIDATION_FAILED, afterGeneration, PipelineStage.OUTPUT_VALIDATION)
        }
        val afterValidation = afterGeneration.advance(PipelineStage.OUTPUT_VALIDATION)
        var finalState = afterValidation
        val validatedResponse = when (firstValidation) {
            ValidationDecision.Accepted -> generated
            is ValidationDecision.Rejected -> {
                val afterRegeneration = afterValidation.advance(PipelineStage.REGENERATE)
                val afterRegenerationGeneration = afterRegeneration.advance(PipelineStage.GENERATION)
                val regenerated = when (val result = generator.generate(request, context)) {
                    is StageResult.Failed -> return failure(request, result.code, afterRegeneration, PipelineStage.GENERATION)
                    is StageResult.Succeeded -> result.value
                }
                val secondValidation = try {
                    outputValidator.validate(request, regenerated)
                } catch (_: Exception) {
                    return failure(request, ErrorCode.VALIDATION_FAILED, afterRegenerationGeneration, PipelineStage.OUTPUT_VALIDATION)
                }
                val afterRegenerationValidation = afterRegenerationGeneration.advance(PipelineStage.OUTPUT_VALIDATION)
                when (secondValidation) {
                    ValidationDecision.Accepted -> {
                        finalState = afterRegenerationValidation
                        regenerated
                    }
                    is ValidationDecision.Rejected -> {
                        return failure(request, ErrorCode.VALIDATION_FAILED, afterRegenerationGeneration, PipelineStage.OUTPUT_VALIDATION)
                    }
                }
            }
        }

        val persisted = when (val result = persistence.persist(request, validatedResponse)) {
            is StageResult.Failed -> return failure(request, result.code, finalState, PipelineStage.PERSIST)
            is StageResult.Succeeded -> result.value
        }
        val afterPersistence = finalState.advance(PipelineStage.PERSIST)

        when (val result = delivery.deliver(request, persisted)) {
            is StageResult.Failed -> return failure(request, result.code, afterPersistence, PipelineStage.DELIVER, false)
            is StageResult.Succeeded -> Unit
        }
        try {
            postDeliveryMemoryExtraction.dispatch(CompletedTurn(request, context, persisted))
        } catch (_: Exception) {
            // Memory extraction is best-effort and isolated from the main result
        }
        return ChatResult.Success(request.requestId, persisted, afterPersistence.advance(PipelineStage.DELIVER))
    }

    private fun failure(
        request: ChatRequest,
        code: ErrorCode,
        state: PipelineState,
        failedStage: PipelineStage,
        markExecutionFailed: Boolean = true,
    ): ChatResult.Failure {
        if (markExecutionFailed) executionCoordinator.fail(request, code)
        return ChatResult.Failure(request.requestId, code, state.advance(failedStage))
    }

    private fun deliverExisting(request: ChatRequest, response: PersistedResponse, state: PipelineState): ChatResult {
        val afterPersistence = state.advance(PipelineStage.PERSIST)
        return when (val result = delivery.deliver(request, response)) {
            is StageResult.Failed -> failure(request, result.code, afterPersistence, PipelineStage.DELIVER, false)
            is StageResult.Succeeded -> ChatResult.Success(
                request.requestId,
                response,
                afterPersistence.advance(PipelineStage.DELIVER),
            )
        }
    }
}

private fun PipelineState.advance(stage: PipelineStage): PipelineState = PipelineState(
    currentStage = stage,
    visitedStages = visitedStages + stage,
)