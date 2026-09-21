package com.pinkdreams.config

import com.pinkdreams.llm.GenerationConfig
import com.pinkdreams.persistence.repositories.AiSettingsRepository

/**
 * The single authoritative resolution of AI runtime settings.
 *
 * PRECEDENCE (Phase ADMIN-2, section 24):
 *
 *     1. DB setting            — admin-configured, non-null value in ai_settings
 *     2. environment variable  — already folded into [LlmConfig] by LlmConfig.from()
 *     3. application default   — the default arguments of LlmConfig
 *
 * [LlmConfig] already collapses steps 2 and 3, so this class only has to decide
 * between "the admin set it" and "whatever LlmConfig resolved". That keeps the
 * existing env-var deployment path completely intact: with no row in
 * ai_settings, every value here equals what the application used before this
 * layer existed.
 *
 * Resolution happens PER REQUEST rather than at startup, so activating a new
 * setting from the admin panel takes effect without a redeploy — matching how
 * the engines are resolved from their active version on each use.
 *
 * Temperature has no environment variable and no LlmConfig field: it was
 * previously always null (i.e. the provider's own default). It therefore stays
 * null unless an admin sets it, which preserves current behavior exactly.
 */
class AiRuntimeSettings(
    private val llmConfig: LlmConfig,
    private val repository: AiSettingsRepository? = null,
    // Primary Generation Latency phase: an isolated, reversible override for
    // primary generation's OpenRouter provider-routing preference —
    // deliberately NOT part of the admin-configurable AiSettingsRepository
    // precedence chain above (this is a code-level default/experiment knob,
    // not a durable admin-editable setting). Public so TestChatService can
    // read the production instance's own value as a fallback when a Test
    // Chat conversation doesn't explicitly pin its own — otherwise a plain
    // `.copy()` would silently null out the production default for every
    // conversation that doesn't override it, which is exactly backwards for
    // "Test Chat runs the same pipeline as production" (ADMIN-3).
    val generationProviderSortOverride: String? = null,
    // Task 9 — Admin AI Runtime Controls. The CODE-LEVEL default for each
    // Intent Discovery knob, consulted by resolve() only when no DB override
    // exists — the exact same role generationProviderSortOverride already
    // played above, just extended to the three settings that used to live
    // only as literal Application.kt constructor args on
    // ChatEngineFactory.Dependencies (see that class's own now-narrowed doc
    // comment). Null (every existing test's default) means "no code-level
    // default beyond the ultimate fallback below" — intentModelDefault null
    // falls all the way to llmConfig.model, matching the exact pre-Task-9
    // behavior of `deps.intentModelOverride ?: llmConfig.model`.
    val intentModelDefault: String? = null,
    val intentJsonModeDefault: Boolean? = null,
    val intentMaxOutputTokensDefault: Int? = null,
) {
    // Task 8 Part 2 — Effective Configuration. Exposes the raw
    // environment/default model (never DB-resolved) so the admin UI can
    // accurately report the true effective value for workloads that do NOT
    // go through resolve() today. This is a genuine, pre-existing
    // discrepancy this task surfaces rather than silently fixes (per Part
    // 11 — no behavior changes beyond what's explicitly requested):
    // ChatEngineFactory builds memory_extraction/continuity_summarization/
    // memory_engine_maintenance's GenerationConfig directly from
    // llmConfig.model, and intent_discovery from
    // `intentModelOverride ?: llmConfig.model` — NEITHER path calls
    // resolve() or sideChannelConfig(), so an admin's persisted `model`
    // override in ai_settings currently affects ONLY primary_generation,
    // not the four other workloads, despite AiSettingsResponse's own
    // documentation previously claiming otherwise.
    val environmentModel: String get() = llmConfig.model

    data class Resolved(
        val model: String,
        val temperature: Double?,
        val maxOutputTokens: Int,
        val modelSource: Source,
        val temperatureSource: Source,
        val maxOutputTokensSource: Source,
        // Task 9 — Admin AI Runtime Controls. Resolved with the exact same
        // DB-override-then-code-default precedence as the three fields
        // above, just for Intent Discovery's own knobs and primary
        // generation's provider-sort preference.
        val intentModel: String,
        val intentModelSource: Source,
        val intentJsonMode: Boolean?,
        val intentJsonModeSource: Source,
        val intentMaxOutputTokens: Int,
        val intentMaxOutputTokensSource: Source,
        val generationProviderSort: String?,
        val generationProviderSortSource: Source,
    )

    /** Where a resolved value actually came from — surfaced to the admin UI. */
    enum class Source { DATABASE, CODE_DEFAULT, ENVIRONMENT_OR_DEFAULT, PROVIDER_DEFAULT }

    fun resolve(): Resolved {
        // A repository failure must never take down generation: fall through to
        // the environment-resolved configuration, which is the pre-ADMIN-2
        // behavior and is always valid.
        val stored = runCatching { repository?.get() }.getOrNull()

        val intentModel = stored?.intentModel ?: intentModelDefault ?: llmConfig.model
        val intentModelSource = when {
            stored?.intentModel != null -> Source.DATABASE
            intentModelDefault != null -> Source.CODE_DEFAULT
            else -> Source.ENVIRONMENT_OR_DEFAULT
        }
        val intentJsonMode = stored?.intentJsonMode ?: intentJsonModeDefault
        val intentJsonModeSource = when {
            stored?.intentJsonMode != null -> Source.DATABASE
            intentJsonModeDefault != null -> Source.CODE_DEFAULT
            else -> Source.PROVIDER_DEFAULT
        }
        // 600 is the one pre-Task-9 hardcoded ultimate fallback
        // (ChatEngineFactory's own literal, `deps.intentMaxOutputTokensOverride
        // ?: 600`) — preserved exactly, never a behavior change.
        val intentMaxOutputTokens = stored?.intentMaxOutputTokens ?: intentMaxOutputTokensDefault ?: 600
        val intentMaxOutputTokensSource = when {
            stored?.intentMaxOutputTokens != null -> Source.DATABASE
            else -> Source.CODE_DEFAULT
        }
        val generationProviderSort = stored?.generationProviderSort ?: generationProviderSortOverride
        val generationProviderSortSource = when {
            stored?.generationProviderSort != null -> Source.DATABASE
            generationProviderSortOverride != null -> Source.CODE_DEFAULT
            else -> Source.PROVIDER_DEFAULT
        }

        return Resolved(
            model = stored?.model ?: llmConfig.model,
            temperature = stored?.temperature,
            maxOutputTokens = stored?.maxOutputTokens ?: llmConfig.maxOutputTokens,
            modelSource = if (stored?.model != null) Source.DATABASE else Source.ENVIRONMENT_OR_DEFAULT,
            temperatureSource = if (stored?.temperature != null) Source.DATABASE else Source.ENVIRONMENT_OR_DEFAULT,
            maxOutputTokensSource = if (stored?.maxOutputTokens != null) Source.DATABASE else Source.ENVIRONMENT_OR_DEFAULT,
            intentModel = intentModel,
            intentModelSource = intentModelSource,
            intentJsonMode = intentJsonMode,
            intentJsonModeSource = intentJsonModeSource,
            intentMaxOutputTokens = intentMaxOutputTokens,
            intentMaxOutputTokensSource = intentMaxOutputTokensSource,
            generationProviderSort = generationProviderSort,
            generationProviderSortSource = generationProviderSortSource,
        )
    }

    /**
     * The generation config for a primary chat response.
     *
     * Only the PRIMARY generation call is governed by these settings. The
     * side-channel calls (memory extraction, intent discovery, continuity,
     * memory-engine maintenance) keep their own dedicated, measured token
     * budgets: those budgets exist because reasoning models exhaust small
     * budgets before emitting content, and letting an admin lower them from
     * this screen would silently break memory and skill selection. Their model
     * still follows the admin's choice, so a model switch applies everywhere.
     *
     * reasoningEnabled = false — Primary Generation Latency + Response Quality
     * Hardening phase. Unlike Intent Discovery (a discrete classification
     * judgment, proven to need reasoning: disabling it there previously
     * collapsed live routing from 92% to 24%), primary generation is a
     * conversational/stylistic task. A live 35-turn accumulating-conversation
     * benchmark measured generation averaging 11.2s (49% reasoning-driven,
     * p95 ~31s, one turn spiking to 44s) with reasoning ON. A paired
     * reasoning ON/OFF replay of 6 of that conversation's real captured
     * prompts (casual chat, companionship, "I freeze around girls" coaching,
     * flirting, an AI-nature question, and intimacy pacing) showed reasoning
     * OFF cut average latency roughly 3-4x (one pathological 17.3s call
     * dropped to 1.3s) with no observed loss of persona warmth, naturalness,
     * or capability-discovery quality — if anything, several OFF responses
     * were more naturally concise, better matching this phase's own
     * conciseness goals. A follow-up live Test Chat conversation with this
     * flag set confirmed the latency win end-to-end (see the phase report).
     * Intent Discovery, memory extraction, continuity, and memory-engine
     * maintenance are untouched by this — none of their GenerationConfig
     * construction sites reference this class.
     */
    fun generationConfig(): GenerationConfig {
        val resolved = resolve()
        return GenerationConfig(
            model = resolved.model,
            temperature = resolved.temperature,
            maxOutputTokens = resolved.maxOutputTokens,
            reasoningEnabled = false,
            // Task 9 — now DB-aware (via resolve()) rather than reading the
            // constructor field directly, so an admin's persisted override
            // takes effect on the next request with no restart. Falls back
            // to generationProviderSortOverride exactly as before when no
            // DB row exists.
            providerSort = resolved.generationProviderSort,
            workload = "primary_generation",
        )
    }

    /** Model-only config for a side-channel call that owns its token budget. */
    fun sideChannelConfig(maxOutputTokens: Int): GenerationConfig = GenerationConfig(
        model = resolve().model,
        temperature = null,
        maxOutputTokens = maxOutputTokens,
    )
}
