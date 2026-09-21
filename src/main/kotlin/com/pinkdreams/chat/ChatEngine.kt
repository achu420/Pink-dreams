package com.pinkdreams.chat

import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.chat.memory.CompletedTurn
import com.pinkdreams.chat.memory.NoopPostDeliveryMemoryExtraction
import com.pinkdreams.chat.memory.PostDeliveryMemoryExtraction
import com.pinkdreams.chat.memory.SkillAwareMemoryEnricher
import com.pinkdreams.chat.skill.IntentDiscovery
import com.pinkdreams.chat.skill.NoopIntentDiscovery
import com.pinkdreams.chat.skill.SkillContextEnricher
import com.pinkdreams.chat.skill.SkillSelection
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
    SKILL_SELECTION,
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
    // Task 8 Part 4 — stamped onto the context AFTER skill selection runs
    // (ChatEngine.process(), the SKILL_SELECTION stage) so the downstream
    // call that reuses this context — primary generation — carries the
    // actual selected skill through to GenerationRequest.from(), with no
    // per-call-site plumbing. Null means SkillSelection.None (a genuine "no
    // skill" outcome), never "not yet known" — only ever set once, after
    // selection has already completed.
    val selectedSkillKey: String? = null,
)

data class ContextBlock(val role: String, val content: String)

data class LlmExecutionDiagnostics(
    val contextBlocks: List<ContextBlock>?,
    val generationConfig: Map<String, String>?,
    val llmResponseMetadata: Map<String, String>?,
    // Flattened, provider-agnostic view of the actual HTTP exchange with the LLM
    // provider (method/endpoint/model/headers/body for request and response).
    // Populated by the LlmClient implementation via LlmResponse.providerExchange;
    // this type deliberately stays a plain Map so the chat core never depends on
    // any provider-specific (e.g. OpenRouter) type.
    val providerExchange: Map<String, String>? = null,
    // Runtime Quality + Latency Verification phase — measured wall-clock
    // duration (milliseconds, as a string) of each user-facing pipeline
    // stage for THIS turn, keyed by stage name. Populated by
    // PipelineChatEngine, merged into the same persisted diagnostics as
    // everything else above (never a second diagnostics store), and
    // deliberately excludes async post-delivery work (memory extraction,
    // continuity, memory-engine maintenance) — those are fire-and-forget by
    // design (section 6) and are timed/logged separately, not on the
    // user-facing critical path this map exists to measure.
    val stageTimingsMs: Map<String, String>? = null,
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
    // Phase B — Skill Foundation. Both default to no-ops so every pre-existing
    // caller/test is unaffected: with the defaults, skill selection always
    // resolves to None and the context passed to generation is byte-identical
    // to before this stage existed.
    private val intentDiscovery: IntentDiscovery = NoopIntentDiscovery,
    private val skillContextEnricher: SkillContextEnricher? = null,
    // Phase C — Memory + Skill Integration. Null by default so every pre-Phase-C
    // caller/test is unaffected: with no enricher configured, the memory block
    // stays exactly what RepositoryContextAssembler already built.
    private val memoryContextEnricher: SkillAwareMemoryEnricher? = null,
) : ChatEngine {
    override fun process(request: ChatRequest): ChatResult {
        val turnStartNanos = System.nanoTime()
        val state = PipelineState(PipelineStage.RECEIVED, listOf(PipelineStage.RECEIVED))
        // Runtime Quality + Latency Verification phase: measured wall-clock
        // duration of each user-facing stage for this one turn, in the order
        // they actually run. Never on the critical path itself — timing a
        // block adds a System.nanoTime() call before/after, not a new stage.
        val stageTimings = linkedMapOf<String, Long>()
        fun <T> timed(label: String, block: () -> T): T {
            val start = System.nanoTime()
            try {
                return block()
            } finally {
                stageTimings[label] = (System.nanoTime() - start) / 1_000_000
            }
        }

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

        val contextResult = timed("context_assembly") { contextAssembler.assemble(request) }
        val context = when (contextResult) {
            is StageResult.Failed -> return failure(request, contextResult.code, afterModeration, PipelineStage.CONTEXT_ASSEMBLY)
            is StageResult.Succeeded -> contextResult.value
        }
        val afterContext = afterModeration.advance(PipelineStage.CONTEXT_ASSEMBLY)

        // Skill selection is a distinct, best-effort stage: any failure here
        // (LLM timeout/exception, invalid output, unknown/inactive key, repository
        // failure) must never block or alter normal chat delivery — it degrades to
        // "no skill selected" and generation proceeds against the assembled
        // context unchanged.
        val skillSelection = timed("intent_discovery") {
            try {
                val selection = intentDiscovery.selectSkill(request, context)
                when (selection) {
                    is SkillSelection.Selected ->
                        System.err.println("SKILL_SELECTION: selected='${selection.skillKey}' conversation=${request.conversationId} requestId=${request.requestId}")
                    SkillSelection.None ->
                        System.err.println("SKILL_SELECTION: none conversation=${request.conversationId} requestId=${request.requestId}")
                }
                selection
            } catch (e: Exception) {
                // Never log message/skill content here — only that a failure occurred.
                System.err.println("SKILL_SELECTION: failed conversation=${request.conversationId} requestId=${request.requestId}: ${e.javaClass.simpleName}")
                SkillSelection.None
            }
        }

        // Phase C — skill-aware memory context selection runs strictly after skill
        // selection (it uses the selected skill as one relevance signal) and
        // strictly before skill context injection. Failure here is independent of
        // skill-selection failure/success: memory selection must work even when
        // skill selection failed or returned None (Phase C section 18/19), and it
        // must never turn into a hard dependency for successful generation — any
        // exception here silently keeps the context UNCHANGED, i.e. the
        // assembler's own already-correct, skill-agnostic top-N memory block,
        // which is the existing/original MemoryService selection.
        val memoryEnrichedContext = timed("memory_context_selection") {
            try {
                val enriched = memoryContextEnricher?.enrich(context, request, skillSelection) ?: context
                System.err.println(
                    "MEMORY_CONTEXT_SELECTION: ${if (memoryContextEnricher != null) "applied" else "skipped (not configured)"} " +
                        "conversation=${request.conversationId} requestId=${request.requestId}",
                )
                enriched
            } catch (e: Exception) {
                System.err.println("MEMORY_CONTEXT_SELECTION: failed conversation=${request.conversationId} requestId=${request.requestId}: ${e.javaClass.simpleName}")
                context
            }
        }

        val skillEnrichedContext = timed("skill_context_enrichment") {
            try {
                val enriched = if (skillSelection is SkillSelection.Selected && skillContextEnricher != null) {
                    skillContextEnricher.enrich(memoryEnrichedContext, skillSelection)
                } else {
                    memoryEnrichedContext
                }
                // Task 8 — stamp the ACTUAL selection outcome regardless of whether
                // an enricher is configured, so exchange attribution (which skill
                // was in effect for this generation) never depends on whether a
                // content-injecting enricher happens to be wired up.
                enriched.copy(selectedSkillKey = (skillSelection as? SkillSelection.Selected)?.skillKey)
            } catch (e: Exception) {
                System.err.println("SKILL_CONTEXT_ENRICHMENT: failed conversation=${request.conversationId} requestId=${request.requestId}: ${e.javaClass.simpleName}")
                memoryEnrichedContext.copy(selectedSkillKey = (skillSelection as? SkillSelection.Selected)?.skillKey)
            }
        }
        val afterSkillSelection = afterContext.advance(PipelineStage.SKILL_SELECTION)

        val generationResult = timed("generation") { generator.generate(request, skillEnrichedContext) }
        val generated = when (generationResult) {
            is StageResult.Failed -> return failure(request, generationResult.code, afterSkillSelection, PipelineStage.GENERATION)
            is StageResult.Succeeded -> generationResult.value
        }
        val afterGeneration = afterSkillSelection.advance(PipelineStage.GENERATION)

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
                val regenerationResult = timed("regeneration") { generator.generate(request, skillEnrichedContext) }
                val regenerated = when (regenerationResult) {
                    is StageResult.Failed -> return failure(request, regenerationResult.code, afterRegeneration, PipelineStage.GENERATION)
                    is StageResult.Succeeded -> regenerationResult.value
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

        val totalSoFarMs = (System.nanoTime() - turnStartNanos) / 1_000_000
        stageTimings["total_before_persist"] = totalSoFarMs
        val responseWithTimings = validatedResponse.copy(
            executionDiagnostics = (validatedResponse.executionDiagnostics ?: LlmExecutionDiagnostics(null, null, null))
                .copy(stageTimingsMs = stageTimings.mapValues { it.value.toString() }),
        )

        val persistResult = timed("persist") { persistence.persist(request, responseWithTimings) }
        val persisted = when (persistResult) {
            is StageResult.Failed -> return failure(request, persistResult.code, finalState, PipelineStage.PERSIST)
            is StageResult.Succeeded -> persistResult.value
        }
        val afterPersistence = finalState.advance(PipelineStage.PERSIST)
        System.err.println(
            "STAGE_TIMINGS: conversation=${request.conversationId} requestId=${request.requestId} " +
                stageTimings.entries.joinToString(" ") { "${it.key}=${it.value}ms" },
        )

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