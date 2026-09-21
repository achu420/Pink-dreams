package com.pinkdreams.api.admin

import com.pinkdreams.auth.AdminAuthorizationProvider
import com.pinkdreams.common.errors.ApiError
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.common.errors.ErrorResponse
import com.pinkdreams.config.AiRuntimeSettings
import com.pinkdreams.persistence.repositories.AiSettingsRepository
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.IntentEngineRepository
import com.pinkdreams.persistence.repositories.MemoryEngineRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable

@Serializable
data class UpdateAiSettingsRequest(
    // Null clears the override and falls back to the environment/default. The
    // UI always sends the full desired state, so "absent" is not "unchanged".
    val model: String? = null,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    // Task 9 — Admin AI Runtime Controls. Same null-clears-the-override
    // convention as the three fields above.
    val intentModel: String? = null,
    val intentJsonMode: Boolean? = null,
    val intentMaxOutputTokens: Int? = null,
    val generationProviderSort: String? = null,
    /**
     * Required acknowledgement: these settings affect EVERY generation, so a
     * change cannot be made accidentally by a malformed or exploratory PUT.
     */
    val confirm: Boolean = false,
)

@Serializable
data class AiSettingsResponse(
    val model: String,
    val modelSource: String,
    val temperature: Double?,
    val temperatureSource: String,
    val maxOutputTokens: Int,
    val maxOutputTokensSource: String,
    val storedModel: String?,
    val storedTemperature: Double?,
    val storedMaxOutputTokens: Int?,
    // Task 9 — Admin AI Runtime Controls.
    val intentModel: String,
    val intentModelSource: String,
    val intentJsonMode: Boolean?,
    val intentJsonModeSource: String,
    val intentMaxOutputTokens: Int,
    val intentMaxOutputTokensSource: String,
    val generationProviderSort: String?,
    val generationProviderSortSource: String,
    val storedIntentModel: String?,
    val storedIntentJsonMode: Boolean?,
    val storedIntentMaxOutputTokens: Int?,
    val storedGenerationProviderSort: String?,
    val updatedAt: String?,
    val updatedBy: String?,
)

@Serializable
data class VersionSummary(
    val version: Int?,
    val status: String?,
    val changelogNote: String? = null,
    val createdBy: String? = null,
)

/**
 * Section 29 — the answer to "exactly which AI configuration is currently
 * running?" without opening the database.
 */
@Serializable
data class AiConfigurationOverviewResponse(
    val conversationEngine: VersionSummary,
    val intentEngine: VersionSummary,
    val memoryEngine: VersionSummary,
    val memoryBatchSize: Int?,
    val relevantMemoryTarget: Int?,
    val personaCores: List<PersonaCoreSummary>,
    val activeSkillCount: Int,
    val activeSkillKeys: List<String>,
    val runtime: AiSettingsResponse,
)

@Serializable
data class PersonaCoreSummary(
    val slug: String,
    val displayName: String,
    val personaStatus: String,
    val activeCoreVersion: Int?,
)

class AdminAiSettingsRoutes(
    private val aiSettingsRepository: AiSettingsRepository,
    private val aiRuntimeSettings: AiRuntimeSettings,
    private val conversationEngineRepository: ConversationEngineRepository,
    private val intentEngineRepository: IntentEngineRepository,
    private val memoryEngineRepository: MemoryEngineRepository,
    private val skillRepository: SkillRepository,
    private val personaRepository: PersonaRepository,
    private val personaCoreVersionRepository: PersonaCoreVersionRepository,
    private val adminAuthorizationProvider: AdminAuthorizationProvider,
) {
    fun register(route: Route) {
        route.authenticate("dev-auth") {
            get("/v1/admin/ai-settings") {
                if (requirePrincipal() == null) return@get
                call.respond(HttpStatusCode.OK, settingsResponse())
            }

            put("/v1/admin/ai-settings") {
                val principal = requirePrincipal() ?: return@put
                val request = try {
                    call.receive<UpdateAiSettingsRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid request format", null)))
                    return@put
                }
                if (!request.confirm) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            ApiError(
                                ErrorCode.VALIDATION_ERROR,
                                "These settings affect all generation. Re-send with confirm=true to apply.",
                                null,
                            ),
                        ),
                    )
                    return@put
                }
                try {
                    aiSettingsRepository.save(
                        model = request.model?.trim()?.ifBlank { null },
                        temperature = request.temperature,
                        maxOutputTokens = request.maxOutputTokens,
                        updatedBy = principal.name,
                        intentModel = request.intentModel?.trim()?.ifBlank { null },
                        intentJsonMode = request.intentJsonMode,
                        intentMaxOutputTokens = request.intentMaxOutputTokens,
                        generationProviderSort = request.generationProviderSort?.trim()?.ifBlank { null },
                    )
                    call.respond(HttpStatusCode.OK, settingsResponse())
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, e.message ?: "Invalid AI settings", null)))
                }
            }

            get("/v1/admin/ai-configuration") {
                if (requirePrincipal() == null) return@get
                val activeMemoryEngine = memoryEngineRepository.getActiveEngine()
                val activeKeys = skillRepository.findAllActiveKeys().sorted()
                call.respond(
                    HttpStatusCode.OK,
                    AiConfigurationOverviewResponse(
                        conversationEngine = conversationEngineRepository.getActiveEngine()
                            ?.let { VersionSummary(it.version, it.status, it.changelogNote, it.createdBy) }
                            ?: VersionSummary(null, null),
                        intentEngine = intentEngineRepository.getActiveEngine()
                            ?.let { VersionSummary(it.version, it.status, it.changelogNote, it.createdBy) }
                            ?: VersionSummary(null, null),
                        memoryEngine = activeMemoryEngine
                            ?.let { VersionSummary(it.version, it.status, it.changelogNote, it.createdBy) }
                            ?: VersionSummary(null, null),
                        memoryBatchSize = activeMemoryEngine?.batchSize,
                        relevantMemoryTarget = activeMemoryEngine?.relevantMemoryTarget,
                        personaCores = personaRepository.findAll().map { persona ->
                            PersonaCoreSummary(
                                slug = persona.slug,
                                displayName = persona.displayName,
                                personaStatus = persona.status,
                                activeCoreVersion = persona.activeCoreVersionId
                                    ?.let { personaCoreVersionRepository.findById(it)?.version },
                            )
                        },
                        activeSkillCount = activeKeys.size,
                        activeSkillKeys = activeKeys,
                        runtime = settingsResponse(),
                    ),
                )
            }
        }
    }

    private fun settingsResponse(): AiSettingsResponse {
        val resolved = aiRuntimeSettings.resolve()
        val stored = runCatching { aiSettingsRepository.get() }.getOrNull()
        return AiSettingsResponse(
            model = resolved.model,
            modelSource = resolved.modelSource.name,
            temperature = resolved.temperature,
            temperatureSource = resolved.temperatureSource.name,
            maxOutputTokens = resolved.maxOutputTokens,
            maxOutputTokensSource = resolved.maxOutputTokensSource.name,
            storedModel = stored?.model,
            storedTemperature = stored?.temperature,
            storedMaxOutputTokens = stored?.maxOutputTokens,
            intentModel = resolved.intentModel,
            intentModelSource = resolved.intentModelSource.name,
            intentJsonMode = resolved.intentJsonMode,
            intentJsonModeSource = resolved.intentJsonModeSource.name,
            intentMaxOutputTokens = resolved.intentMaxOutputTokens,
            intentMaxOutputTokensSource = resolved.intentMaxOutputTokensSource.name,
            generationProviderSort = resolved.generationProviderSort,
            generationProviderSortSource = resolved.generationProviderSortSource.name,
            storedIntentModel = stored?.intentModel,
            storedIntentJsonMode = stored?.intentJsonMode,
            storedIntentMaxOutputTokens = stored?.intentMaxOutputTokens,
            storedGenerationProviderSort = stored?.generationProviderSort,
            updatedAt = stored?.updatedAt?.toString(),
            updatedBy = stored?.updatedBy,
        )
    }

    private suspend fun io.ktor.util.pipeline.PipelineContext<Unit, io.ktor.server.application.ApplicationCall>.requirePrincipal(): UserIdPrincipal? {
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
}
