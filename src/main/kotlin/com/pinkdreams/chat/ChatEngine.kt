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
    // --- Task 24 — Complete Pipeline Attribution -------------------------
    // Every field below is a pure CARRIER of a value the producing stage
    // already computed for its own reasons. Nothing here is read back by any
    // behavioral code path: adding them cannot change assembly, selection,
    // enrichment or generation. All default to null, so every pre-Task-24
    // caller/test constructs a byte-identical context.
    //
    // The engine/persona VERSION NUMBERS alongside the existing version IDs:
    // the assembler already holds the full engine/core objects, so carrying
    // the human-meaningful version int costs nothing and spares the admin
    // surface an extra lookup per turn (Part 18).
    val engineVersion: Int? = null,
    val personaCoreVersion: Int? = null,
    // Memory actually INJECTED into this context's memory block — identifiers
    // only, never the fact text (Part 8/19). Null means "no memory attribution
    // available for this context", which is a genuinely different statement
    // from an empty list ("attributed: zero memories were injected").
    val memoryIdsUsed: List<UUID>? = null,
    val memoryCandidateCount: Int? = null,
    /** CONTEXT_ASSEMBLER or SKILL_AWARE_SELECTOR — which stage produced the injected set. */
    val memorySelectionSource: String? = null,
    val userProfilePresent: Boolean? = null,
    val userProfileUpdatedAt: String? = null,
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
    // Task 24 — Complete Pipeline Attribution. Null by default (every existing
    // caller/test), in which case NO attribution record is written and this
    // class behaves exactly as before. Wired to a failure-isolated,
    // repository-backed recorder by ChatEngineFactory for both the production
    // engine and every Test Chat engine.
    private val turnAttributionRecorder: com.pinkdreams.observability.TurnAttributionRecorder? = null,
    // Task 24 — carried onto the record so production and Test Chat turns stay
    // distinguishable, matching llm_exchanges.is_test_chat exactly.
    private val isTestChat: Boolean = false,
) : ChatEngine {
    override fun process(request: ChatRequest): ChatResult {
        if (turnAttributionRecorder == null) return processTurn(request, null)
        // Opened here and removed in the `finally` below so a failed, thrown or
        // abandoned turn can never leak a collector entry (Part 18).
        com.pinkdreams.observability.TurnAttributionScope.open(request.requestId)
        val attribution = TurnAttributionBuilder(request, isTestChat)
        try {
            return processTurn(request, attribution)
        } finally {
            try {
                attribution.mergeCollector(com.pinkdreams.observability.TurnAttributionScope.current(request.requestId))
                if (attribution.shouldRecord()) turnAttributionRecorder.record(attribution.build())
            } catch (e: Exception) {
                // Attribution must never affect the user-facing result.
                System.err.println("TURN_ATTRIBUTION: capture failed for requestId=${request.requestId}: ${e.javaClass.simpleName}")
            } finally {
                com.pinkdreams.observability.TurnAttributionScope.close(request.requestId)
            }
        }
    }

    private fun processTurn(request: ChatRequest, attribution: TurnAttributionBuilder?): ChatResult {
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

        // Local wrapper so every failure exit also stamps the failed stage onto
        // the attribution record — Part 20 requires a failed turn to still be
        // attributable, not to silently vanish from the trace.
        fun fail(
            code: ErrorCode,
            failedState: PipelineState,
            failedStage: PipelineStage,
            markExecutionFailed: Boolean = true,
        ): ChatResult.Failure {
            attribution?.failed(code, failedStage)
            return failure(request, code, failedState, failedStage, markExecutionFailed)
        }

        when (val claim = executionCoordinator.claim(request)) {
            is StageResult.Failed -> return fail(claim.code, state, PipelineStage.RECEIVED, false)
            is StageResult.Succeeded -> when (val execution = claim.value) {
                ExecutionClaim.Winner -> Unit
                // Neither branch runs a pipeline: an idempotent replay returns
                // the ALREADY-attributed original turn's response, and an
                // in-progress claim produced nothing at all. Writing a record
                // here would invent a turn that never executed (Part 16).
                is ExecutionClaim.Completed -> {
                    attribution?.suppress()
                    return deliverExisting(request, execution.response, state)
                }
                ExecutionClaim.Processing -> {
                    attribution?.suppress()
                    return ChatResult.InProgress(request.requestId, state)
                }
            }
        }

        if (entitlementChecker.check(request) == EntitlementDecision.Denied) {
            return fail(ErrorCode.ENTITLEMENT_DENIED, state, PipelineStage.ENTITLEMENT_CHECK)
        }
        val afterEntitlement = state.advance(PipelineStage.ENTITLEMENT_CHECK)

        if (inputModerator.moderate(request) == ModerationDecision.Blocked) {
            return fail(ErrorCode.MODERATION_BLOCKED, afterEntitlement, PipelineStage.INPUT_MODERATION)
        }
        val afterModeration = afterEntitlement.advance(PipelineStage.INPUT_MODERATION)

        val contextResult = timed("context_assembly") { contextAssembler.assemble(request) }
        val context = when (contextResult) {
            is StageResult.Failed -> return fail(contextResult.code, afterModeration, PipelineStage.CONTEXT_ASSEMBLY)
            is StageResult.Succeeded -> contextResult.value
        }
        attribution?.contextAssembled(context)
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
                // The ONE authoritative "skill content was actually injected"
                // signal: an enricher was configured, a skill was selected, and
                // enrichment completed without throwing.
                attribution?.skillContextInjected(skillSelection is SkillSelection.Selected && skillContextEnricher != null)
                enriched.copy(selectedSkillKey = (skillSelection as? SkillSelection.Selected)?.skillKey)
            } catch (e: Exception) {
                System.err.println("SKILL_CONTEXT_ENRICHMENT: failed conversation=${request.conversationId} requestId=${request.requestId}: ${e.javaClass.simpleName}")
                attribution?.skillContextInjected(false)
                memoryEnrichedContext.copy(selectedSkillKey = (skillSelection as? SkillSelection.Selected)?.skillKey)
            }
        }
        attribution?.skillSelected(skillSelection)
        attribution?.memoryContext(skillEnrichedContext)
        val afterSkillSelection = afterContext.advance(PipelineStage.SKILL_SELECTION)

        val generationResult = timed("generation") { generator.generate(request, skillEnrichedContext) }
        val generated = when (generationResult) {
            is StageResult.Failed -> return fail(generationResult.code, afterSkillSelection, PipelineStage.GENERATION)
            is StageResult.Succeeded -> generationResult.value
        }
        val afterGeneration = afterSkillSelection.advance(PipelineStage.GENERATION)

        val firstValidation = try {
            outputValidator.validate(request, generated)
        } catch (_: Exception) {
            return fail(ErrorCode.VALIDATION_FAILED, afterGeneration, PipelineStage.OUTPUT_VALIDATION)
        }
        val afterValidation = afterGeneration.advance(PipelineStage.OUTPUT_VALIDATION)
        var finalState = afterValidation
        val validatedResponse = when (firstValidation) {
            ValidationDecision.Accepted -> generated
            is ValidationDecision.Rejected -> {
                val afterRegeneration = afterValidation.advance(PipelineStage.REGENERATE)
                val afterRegenerationGeneration = afterRegeneration.advance(PipelineStage.GENERATION)
                // Counted here (before the call), so a regeneration that itself
                // fails is still recorded as having been attempted.
                attribution?.regenerationAttempted()
                val regenerationResult = timed("regeneration") { generator.generate(request, skillEnrichedContext) }
                val regenerated = when (regenerationResult) {
                    is StageResult.Failed -> return fail(regenerationResult.code, afterRegeneration, PipelineStage.GENERATION)
                    is StageResult.Succeeded -> regenerationResult.value
                }
                val secondValidation = try {
                    outputValidator.validate(request, regenerated)
                } catch (_: Exception) {
                    return fail(ErrorCode.VALIDATION_FAILED, afterRegenerationGeneration, PipelineStage.OUTPUT_VALIDATION)
                }
                val afterRegenerationValidation = afterRegenerationGeneration.advance(PipelineStage.OUTPUT_VALIDATION)
                when (secondValidation) {
                    ValidationDecision.Accepted -> {
                        finalState = afterRegenerationValidation
                        regenerated
                    }
                    is ValidationDecision.Rejected -> {
                        return fail(ErrorCode.VALIDATION_FAILED, afterRegenerationGeneration, PipelineStage.OUTPUT_VALIDATION)
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
            is StageResult.Failed -> return fail(persistResult.code, finalState, PipelineStage.PERSIST)
            is StageResult.Succeeded -> persistResult.value
        }
        attribution?.persisted(persisted)
        val afterPersistence = finalState.advance(PipelineStage.PERSIST)
        System.err.println(
            "STAGE_TIMINGS: conversation=${request.conversationId} requestId=${request.requestId} " +
                stageTimings.entries.joinToString(" ") { "${it.key}=${it.value}ms" },
        )

        when (val result = delivery.deliver(request, persisted)) {
            is StageResult.Failed -> return fail(result.code, afterPersistence, PipelineStage.DELIVER, false)
            is StageResult.Succeeded -> Unit
        }
        attribution?.succeeded()
        try {
            // Task 25F fix 4: the FINAL context — the one generation actually
            // ran on — not the assembler's pre-enrichment draft. Nothing reads
            // CompletedTurn.context's blocks (its consumers read only
            // engineVersionId/personaCoreVersionId, identical on both), but
            // skillEnrichedContext carries the true injected memoryIdsUsed,
            // which is what BestEffortMemoryExtraction marks as referenced.
            postDeliveryMemoryExtraction.dispatch(CompletedTurn(request, skillEnrichedContext, persisted))
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

/**
 * Task 24 — accumulates one turn's attribution as [PipelineChatEngine] runs,
 * then emits a single immutable [com.pinkdreams.observability.TurnAttributionRecord].
 *
 * Purely a recorder: it is never read by any pipeline decision, and every
 * value it holds is copied verbatim from a runtime object the stage in
 * question already produced. A value the stage did not produce stays null —
 * nothing here derives, guesses or parses (Part 16).
 */
internal class TurnAttributionBuilder(
    private val request: ChatRequest,
    private val isTestChat: Boolean,
) {
    private var suppressed = false
    private var personaVersionId: UUID? = null
    private var personaVersion: Int? = null
    private var engineVersionId: UUID? = null
    private var engineVersion: Int? = null
    private var memoryIdsUsed: List<UUID>? = null
    private var memoryCandidateCount: Int? = null
    private var memorySelectionSource: String? = null
    private var userProfilePresent: Boolean? = null
    private var userProfileUpdatedAt: String? = null
    private var selectedSkillKey: String? = null
    private var skillContextInjected: Boolean? = null
    private var regenerationCount = 0
    private var assistantMessageId: UUID? = null
    private var outcome: String? = null
    private var failedStage: String? = null
    private var collector: com.pinkdreams.observability.TurnAttributionCollector? = null

    fun suppress() { suppressed = true }

    fun contextAssembled(context: ChatContext) {
        personaVersionId = context.personaCoreVersionId
        personaVersion = context.personaCoreVersion
        engineVersionId = context.engineVersionId
        engineVersion = context.engineVersion
        userProfilePresent = context.userProfilePresent
        userProfileUpdatedAt = context.userProfileUpdatedAt
        memoryContext(context)
    }

    /** Re-read after memory enrichment, so the RECORDED set is the one actually injected. */
    fun memoryContext(context: ChatContext) {
        context.memoryIdsUsed?.let { memoryIdsUsed = it }
        context.memoryCandidateCount?.let { memoryCandidateCount = it }
        context.memorySelectionSource?.let { memorySelectionSource = it }
    }

    fun skillSelected(selection: SkillSelection) {
        selectedSkillKey = (selection as? SkillSelection.Selected)?.skillKey
    }

    fun skillContextInjected(injected: Boolean) { skillContextInjected = injected }

    fun regenerationAttempted() { regenerationCount += 1 }

    fun persisted(response: PersistedResponse) { assistantMessageId = response.assistantMessageId }

    fun succeeded() { outcome = "SUCCESS" }

    fun failed(code: ErrorCode, stage: PipelineStage) {
        outcome = "FAILED_${code.name}"
        failedStage = stage.name
    }

    fun mergeCollector(collector: com.pinkdreams.observability.TurnAttributionCollector?) {
        this.collector = collector
    }

    fun build(): com.pinkdreams.observability.TurnAttributionRecord {
        val c = collector
        return com.pinkdreams.observability.TurnAttributionRecord(
            turnRequestId = request.requestId,
            conversationId = request.conversationId,
            userId = request.userId,
            isTestChat = isTestChat,
            personaId = request.personaId,
            personaVersionId = personaVersionId,
            personaVersion = personaVersion,
            conversationEngineId = engineVersionId,
            conversationEngineVersion = engineVersion,
            intentEngineId = c?.intentEngineId,
            intentEngineVersion = c?.intentEngineVersion,
            intentModel = c?.intentModel,
            intentModelSource = c?.intentModelSource,
            intentJsonMode = c?.intentJsonMode,
            intentJsonModeSource = c?.intentJsonModeSource,
            intentMaxOutputTokens = c?.intentMaxOutputTokens,
            intentMaxOutputTokensSource = c?.intentMaxOutputTokensSource,
            intentOutcome = c?.intentOutcome,
            intentResultSkillKey = selectedSkillKey,
            selectedSkillKey = selectedSkillKey,
            skillContextInjected = skillContextInjected,
            memoryIdsUsed = memoryIdsUsed,
            memoryCountUsed = memoryIdsUsed?.size,
            memoryCandidateCount = memoryCandidateCount,
            memorySelectionSource = memorySelectionSource,
            userProfilePresent = userProfilePresent,
            userProfileUpdatedAt = userProfileUpdatedAt,
            generationModel = c?.generationModel,
            generationModelSource = c?.generationModelSource,
            generationTemperature = c?.generationTemperature,
            generationTemperatureSource = c?.generationTemperatureSource,
            generationMaxOutputTokens = c?.generationMaxOutputTokens,
            generationMaxOutputTokensSource = c?.generationMaxOutputTokensSource,
            generationReasoning = c?.generationReasoning,
            generationJsonMode = c?.generationJsonMode,
            generationProviderSort = c?.generationProviderSort,
            generationProviderSortSource = c?.generationProviderSortSource,
            regenerationOccurred = regenerationCount > 0,
            regenerationCount = regenerationCount,
            clientMessageId = request.clientMessageId,
            assistantMessageId = assistantMessageId,
            // A turn that reached neither succeeded() nor failed() exited
            // through a path this builder does not model; say so rather than
            // claiming an outcome it cannot prove.
            outcome = outcome ?: "NOT_CURRENTLY_ATTRIBUTED",
            failedStage = failedStage,
        )
    }

    fun shouldRecord(): Boolean = !suppressed
}

private fun PipelineState.advance(stage: PipelineStage): PipelineState = PipelineState(
    currentStage = stage,
    visitedStages = visitedStages + stage,
)