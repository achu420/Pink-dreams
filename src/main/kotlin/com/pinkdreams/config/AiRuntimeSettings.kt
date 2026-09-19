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
) {
    data class Resolved(
        val model: String,
        val temperature: Double?,
        val maxOutputTokens: Int,
        val modelSource: Source,
        val temperatureSource: Source,
        val maxOutputTokensSource: Source,
    )

    /** Where a resolved value actually came from — surfaced to the admin UI. */
    enum class Source { DATABASE, ENVIRONMENT_OR_DEFAULT }

    fun resolve(): Resolved {
        // A repository failure must never take down generation: fall through to
        // the environment-resolved configuration, which is the pre-ADMIN-2
        // behavior and is always valid.
        val stored = runCatching { repository?.get() }.getOrNull()

        return Resolved(
            model = stored?.model ?: llmConfig.model,
            temperature = stored?.temperature,
            maxOutputTokens = stored?.maxOutputTokens ?: llmConfig.maxOutputTokens,
            modelSource = if (stored?.model != null) Source.DATABASE else Source.ENVIRONMENT_OR_DEFAULT,
            temperatureSource = if (stored?.temperature != null) Source.DATABASE else Source.ENVIRONMENT_OR_DEFAULT,
            maxOutputTokensSource = if (stored?.maxOutputTokens != null) Source.DATABASE else Source.ENVIRONMENT_OR_DEFAULT,
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
     */
    fun generationConfig(): GenerationConfig {
        val resolved = resolve()
        return GenerationConfig(
            model = resolved.model,
            temperature = resolved.temperature,
            maxOutputTokens = resolved.maxOutputTokens,
        )
    }

    /** Model-only config for a side-channel call that owns its token budget. */
    fun sideChannelConfig(maxOutputTokens: Int): GenerationConfig = GenerationConfig(
        model = resolve().model,
        temperature = null,
        maxOutputTokens = maxOutputTokens,
    )
}
