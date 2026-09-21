package com.pinkdreams.observability

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Task 24 — Complete Pipeline Attribution.
 *
 * ONE conceptual Turn Attribution Record per conversational turn: the
 * authoritative answer to "which configuration and which components produced
 * this exact response". Everything here is captured from the actual runtime
 * objects the pipeline already computes — never inferred from prompt text,
 * model names, filenames or response content (Task 24 Part 16).
 *
 * A field that the running system genuinely cannot prove stays NULL, and the
 * admin surface renders it as NOT CURRENTLY ATTRIBUTED. False attribution is
 * worse than missing attribution.
 *
 * Deliberately does NOT duplicate anything that already exists elsewhere:
 *  - per-call model/provider/latency/tokens/outcome/raw bodies live in
 *    `llm_exchanges` (written by ObservableLlmClient) and are joined by
 *    turn_request_id;
 *  - wall-clock stage timings live in `messages.metadata`'s `lvm_stage_timings`;
 *  - memory TEXT is never copied here — only memory IDs and counts (Part 8/19).
 */
data class TurnAttributionRecord(
    val turnRequestId: UUID,
    val conversationId: UUID,
    val userId: UUID,
    val isTestChat: Boolean,
    // --- Persona (Part 3) -------------------------------------------------
    val personaId: UUID?,
    val personaVersionId: UUID?,
    val personaVersion: Int?,
    // --- Conversation Engine (Part 4) -------------------------------------
    val conversationEngineId: UUID?,
    val conversationEngineVersion: Int?,
    // --- Intent Engine + result (Parts 5, 6) ------------------------------
    val intentEngineId: UUID?,
    val intentEngineVersion: Int?,
    val intentModel: String?,
    val intentModelSource: String?,
    val intentJsonMode: Boolean?,
    val intentJsonModeSource: String?,
    val intentMaxOutputTokens: Int?,
    val intentMaxOutputTokensSource: String?,
    /** The authoritative structured outcome of Intent Discovery for this turn. */
    val intentOutcome: String?,
    val intentResultSkillKey: String?,
    // --- Skill (Part 7) ---------------------------------------------------
    val selectedSkillKey: String?,
    /**
     * Whether skill CONTENT was actually injected into the generation context
     * (an enricher was configured AND a skill was selected AND enrichment did
     * not fail). Distinct from [selectedSkillKey] being non-null. The "why was
     * this skill selected" question has no authoritative answer in the current
     * system and is therefore deliberately absent — see the final report.
     */
    val skillContextInjected: Boolean?,
    // --- Memory (Part 8) --------------------------------------------------
    /** Ordered memory fact IDs ACTUALLY rendered into the generation context. */
    val memoryIdsUsed: List<UUID>?,
    val memoryCountUsed: Int?,
    /** Size of the candidate pool the selector chose from, when a selector ran. */
    val memoryCandidateCount: Int?,
    /** Which stage produced the injected set: CONTEXT_ASSEMBLER or SKILL_AWARE_SELECTOR. */
    val memorySelectionSource: String?,
    // --- User profile (Part 9) --------------------------------------------
    val userProfilePresent: Boolean?,
    /** The profile has no version column; its updatedAt is the only stable revision marker that exists. */
    val userProfileUpdatedAt: String?,
    // --- Generation configuration (Part 10) -------------------------------
    val generationModel: String?,
    val generationModelSource: String?,
    val generationTemperature: Double?,
    val generationTemperatureSource: String?,
    val generationMaxOutputTokens: Int?,
    val generationMaxOutputTokensSource: String?,
    val generationReasoning: Boolean?,
    val generationJsonMode: Boolean?,
    val generationProviderSort: String?,
    val generationProviderSortSource: String?,
    // --- Regeneration (Part 11) -------------------------------------------
    val regenerationOccurred: Boolean,
    val regenerationCount: Int,
    // --- Input / final response references (Part 12) ----------------------
    val clientMessageId: UUID?,
    val assistantMessageId: UUID?,
    // --- Outcome ----------------------------------------------------------
    val outcome: String,
    val failedStage: String?,
)

/** Persists one [TurnAttributionRecord]. Implementations must be failure-isolated at the call site. */
fun interface TurnAttributionRecorder {
    fun record(record: TurnAttributionRecord)
}

/**
 * Everything the pipeline learns about a turn while it runs, accumulated in
 * one mutable per-turn holder.
 *
 * Why a holder rather than widening every interface: Intent Discovery and the
 * generation config provider resolve their configuration DEEP inside call
 * sites whose signatures (`IntentDiscovery.selectSkill`, `() ->
 * GenerationConfig`) are fixed contracts with many existing implementations
 * and tests. Threading five new return values through them would be a much
 * larger, riskier behavioral change than recording into a holder the engine
 * already owns. Writes are plain field assignments — no I/O, no locking, no
 * added latency (Part 18).
 */
class TurnAttributionCollector(val turnRequestId: UUID) {
    @Volatile var intentEngineId: UUID? = null
    @Volatile var intentEngineVersion: Int? = null
    @Volatile var intentModel: String? = null
    @Volatile var intentModelSource: String? = null
    @Volatile var intentJsonMode: Boolean? = null
    @Volatile var intentJsonModeSource: String? = null
    @Volatile var intentMaxOutputTokens: Int? = null
    @Volatile var intentMaxOutputTokensSource: String? = null
    @Volatile var intentOutcome: String? = null

    @Volatile var generationModel: String? = null
    @Volatile var generationModelSource: String? = null
    @Volatile var generationTemperature: Double? = null
    @Volatile var generationTemperatureSource: String? = null
    @Volatile var generationMaxOutputTokens: Int? = null
    @Volatile var generationMaxOutputTokensSource: String? = null
    @Volatile var generationReasoning: Boolean? = null
    @Volatile var generationJsonMode: Boolean? = null
    @Volatile var generationProviderSort: String? = null
    @Volatile var generationProviderSortSource: String? = null
}

/**
 * The per-turn registry that lets deep call sites reach their own turn's
 * collector without a signature change. Scoped strictly by turnRequestId and
 * always removed in a `finally` (see PipelineChatEngine.process) so a failed
 * or abandoned turn can never leak an entry.
 *
 * Every accessor is null-safe and non-throwing: attribution must never be able
 * to affect the chat result.
 */
object TurnAttributionScope {
    private val collectors = ConcurrentHashMap<UUID, TurnAttributionCollector>()

    fun open(turnRequestId: UUID): TurnAttributionCollector =
        TurnAttributionCollector(turnRequestId).also { collectors[turnRequestId] = it }

    fun current(turnRequestId: UUID): TurnAttributionCollector? = collectors[turnRequestId]

    fun close(turnRequestId: UUID) {
        collectors.remove(turnRequestId)
    }

    /** Best-effort mutation of the turn's collector; silently does nothing outside a turn. */
    inline fun update(turnRequestId: UUID?, block: (TurnAttributionCollector) -> Unit) {
        if (turnRequestId == null) return
        try {
            current(turnRequestId)?.let(block)
        } catch (_: Exception) {
            // Attribution capture must never affect pipeline behavior.
        }
    }

    /** Test/diagnostic only. */
    fun activeCount(): Int = collectors.size
}
