package com.pinkdreams.imaging.config

/**
 * Resolves the effective image provider/model for a generation.
 *
 * Precedence (deterministic):
 * 1. Explicit request modelId (Admin evaluation / override)
 * 2. Admin DB settings when enabled
 * 3. Environment (IMAGE_PROVIDER / OPENROUTER_IMAGE_MODEL)
 * 4. Application defaults
 *
 * Persona identity is never consulted here.
 */
class ImageRuntimeConfig(
    private val settingsRepository: ImageProviderSettingsRepository? = null,
) {
    enum class Source { REQUEST, DATABASE, ENVIRONMENT, DEFAULT }

    data class Resolved(
        val provider: String,
        val modelId: String,
        val providerSource: Source,
        val modelSource: Source,
        val adminConfiguredModel: String?,
        val environmentModel: String,
        val enabled: Boolean,
    )

    fun resolve(explicitModelId: String? = null): Resolved {
        val stored = settingsRepository?.get() ?: ImageProviderSettingsRepository.Stored.EMPTY
        val envProvider = System.getenv("IMAGE_PROVIDER")?.takeIf { it.isNotBlank() }
        val envModel = System.getenv("OPENROUTER_IMAGE_MODEL")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MODEL

        val explicit = explicitModelId?.takeIf { it.isNotBlank() }
        val (model, modelSource) = when {
            explicit != null -> explicit to Source.REQUEST
            stored.enabled && !stored.modelId.isNullOrBlank() -> stored.modelId to Source.DATABASE
            System.getenv("OPENROUTER_IMAGE_MODEL")?.isNotBlank() == true ->
                System.getenv("OPENROUTER_IMAGE_MODEL")!! to Source.ENVIRONMENT
            else -> DEFAULT_MODEL to Source.DEFAULT
        }

        val (provider, providerSource) = when {
            stored.enabled && !stored.provider.isNullOrBlank() -> stored.provider to Source.DATABASE
            envProvider != null -> envProvider to Source.ENVIRONMENT
            else -> DEFAULT_PROVIDER to Source.DEFAULT
        }

        return Resolved(
            provider = provider,
            modelId = model,
            providerSource = providerSource,
            modelSource = modelSource,
            adminConfiguredModel = stored.modelId,
            environmentModel = envModel,
            enabled = stored.enabled,
        )
    }

    companion object {
        const val DEFAULT_PROVIDER = "openrouter"
        const val DEFAULT_MODEL = "openai/gpt-image-2.5-flare"
    }
}
