package com.pinkdreams.api.admin

import com.pinkdreams.benchmark.PersonaContextPocBenchmarkService
import com.pinkdreams.common.errors.ApiError
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.common.errors.ErrorResponse
import com.pinkdreams.persistence.repositories.PersonaRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.util.UUID

class AdminPersonaContextPocRoutes(
    private val benchmarkService: PersonaContextPocBenchmarkService,
    private val personaRepository: PersonaRepository,
) {
    fun register(route: Route) {
        route.apply {
            // CRUD: Scenarios
            post("/v1/admin/persona-context-poc/scenarios") {
                try {
                    val request = call.receive<CreateScenarioRequest>()
                    val scenario = PersonaContextPocBenchmarkService.ScenarioDefinition(
                        skillKey = request.skillKey,
                        category = request.category,
                        userMessage = request.userMessage,
                        seedHistory = request.seedHistory,
                        seedMemoryFacts = request.seedMemoryFacts,
                        notes = request.notes,
                    )
                    // TODO: Persist scenario to persona_poc_scenario table
                    call.respond(HttpStatusCode.Created, ScenarioResponse(scenario.scenarioId.toString()))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_FAILED, e.message ?: "Invalid request", null)),
                    )
                }
            }

            get("/v1/admin/persona-context-poc/scenarios") {
                try {
                    // TODO: Query all scenarios from persona_poc_scenario table
                    call.respond(emptyList<ScenarioResponse>())
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(ApiError(ErrorCode.INTERNAL_SERVER_ERROR, e.message ?: "Error", null)),
                    )
                }
            }

            // Run management
            post("/v1/admin/persona-context-poc/runs") {
                try {
                    val request = call.receive<CreateRunRequest>()
                    val persona = personaRepository.findById(UUID.fromString(request.personaId))
                        ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Persona not found", null)))

                    val run = benchmarkService.createRun(
                        personaId = UUID.fromString(request.personaId),
                        scenarioIds = request.scenarioIds.map { UUID.fromString(it) },
                        variants = request.variants ?: listOf("A", "B", "C"),
                    )
                    call.respond(HttpStatusCode.Created, RunResponse(run.runId.toString(), run.status))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_FAILED, e.message ?: "Invalid request", null)),
                    )
                }
            }

            post("/v1/admin/persona-context-poc/runs/{runId}/start") {
                try {
                    val runId = call.parameters["runId"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_FAILED, "Missing runId", null)))
                    // TODO: Call benchmarkService.startRun()
                    call.respond(HttpStatusCode.OK, mapOf("status" to "RUNNING", "runId" to runId))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(ApiError(ErrorCode.INTERNAL_SERVER_ERROR, e.message ?: "Error", null)),
                    )
                }
            }

            // Blinded execution review
            get("/v1/admin/persona-context-poc/runs/{runId}/executions") {
                try {
                    val runId = call.parameters["runId"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_FAILED, "Missing runId", null)))
                    // TODO: Call benchmarkService.getBlindedExecutions()
                    call.respond(emptyList<BlindedExecutionResponse>())
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(ApiError(ErrorCode.INTERNAL_SERVER_ERROR, e.message ?: "Error", null)),
                    )
                }
            }

            // Evaluation scoring
            post("/v1/admin/persona-context-poc/executions/{executionId}/evaluate") {
                try {
                    val executionId = call.parameters["executionId"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_FAILED, "Missing executionId", null)))
                    val request = call.receive<EvaluationRequest>()

                    val scores = PersonaContextPocBenchmarkService.EvaluationScore(
                        fidelityScore = request.fidelityScore.toShort(),
                        factualAccuracyScore = request.factualAccuracyScore.toShort(),
                        skillAdherenceScore = request.skillAdherenceScore.toShort(),
                        relationshipContinuityScore = request.relationshipContinuityScore.toShort(),
                        narrativeContinuityScore = request.narrativeContinuityScore.toShort(),
                        naturalnessScore = request.naturalnessScore.toShort(),
                        contextPrecisionScore = request.contextPrecisionScore.toShort(),
                        restrictedLeakage = request.restrictedLeakage,
                        notes = request.notes,
                    )
                    benchmarkService.submitEvaluation(
                        executionId = UUID.fromString(executionId),
                        evaluator = "admin", // TODO: Get from auth context
                        scores = scores,
                    )
                    call.respond(HttpStatusCode.OK, mapOf("status" to "SCORED"))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(ApiError(ErrorCode.VALIDATION_FAILED, e.message ?: "Invalid request", null)),
                    )
                }
            }

            // Results dashboard
            get("/v1/admin/persona-context-poc/runs/{runId}/results") {
                try {
                    val runId = call.parameters["runId"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_FAILED, "Missing runId", null)))
                    val results = benchmarkService.getRunResults(UUID.fromString(runId))
                    call.respond(ResultsResponse(
                        variantAMetrics = results.variantAMetrics,
                        variantBMetrics = results.variantBMetrics,
                        variantCMetrics = results.variantCMetrics,
                        successMatrix = results.successMatrix,
                    ))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(ApiError(ErrorCode.INTERNAL_SERVER_ERROR, e.message ?: "Error", null)),
                    )
                }
            }
        }
    }
}

@Serializable
data class CreateScenarioRequest(
    val skillKey: String,
    val category: String, // A-H
    val userMessage: String,
    val seedHistory: String? = null,
    val seedMemoryFacts: String? = null,
    val notes: String? = null,
)

@Serializable
data class ScenarioResponse(val scenarioId: String)

@Serializable
data class CreateRunRequest(
    val personaId: String,
    val scenarioIds: List<String>,
    val variants: List<String>? = null,
)

@Serializable
data class RunResponse(val runId: String, val status: String)

@Serializable
data class BlindedExecutionResponse(
    val executionId: String,
    val scenarioId: String,
    val blindLabel: String, // "Response 1/2/3"
    val generatedResponse: String,
    val scenarioUserMessage: String,
    val skillKey: String,
)

@Serializable
data class EvaluationRequest(
    val fidelityScore: Int,
    val factualAccuracyScore: Int,
    val skillAdherenceScore: Int,
    val relationshipContinuityScore: Int,
    val narrativeContinuityScore: Int,
    val naturalnessScore: Int,
    val contextPrecisionScore: Int,
    val restrictedLeakage: Boolean,
    val notes: String? = null,
)

@Serializable
data class ResultsResponse(
    val variantAMetrics: PersonaContextPocBenchmarkService.VariantMetrics,
    val variantBMetrics: PersonaContextPocBenchmarkService.VariantMetrics,
    val variantCMetrics: PersonaContextPocBenchmarkService.VariantMetrics,
    val successMatrix: PersonaContextPocBenchmarkService.SuccessMatrix,
)
