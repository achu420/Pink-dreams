package com.pinkdreams.api.admin

import com.pinkdreams.auth.AdminAuthorizationProvider
import com.pinkdreams.common.errors.ApiError
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.common.errors.ErrorResponse
import com.pinkdreams.imaging.benchmark.BenchmarkPrompts
import com.pinkdreams.imaging.benchmark.ImageModelBenchmarkService
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Serializable
data class CreateBenchmarkRunHttpRequest(
    val name: String,
    val personaIds: List<String>,
    val slotKeys: List<String> = emptyList(),
    val promptIds: List<String> = emptyList(),
    val notes: String? = null,
    val start: Boolean = false,
)

@Serializable
data class BenchmarkEvaluationHttpRequest(
    val candidateId: String? = null,
    val identityRating: Int? = null,
    val identityRemarks: String? = null,
    val realismRating: Int? = null,
    val realismRemarks: String? = null,
    val adminDecision: String? = null,
    val adminRemarks: String? = null,
)

class AdminImageBenchmarkRoutes(
    private val service: ImageModelBenchmarkService,
    private val adminAuthorizationProvider: AdminAuthorizationProvider,
) {
    fun register(route: Route) {
        route.authenticate("session-auth", "dev-auth") {
            get("/v1/admin/images/benchmarks/readiness") {
                if (!call.requireAdminBench(adminAuthorizationProvider)) return@get
                val r = service.readiness()
                call.respond(
                    buildJsonObject {
                        put("status", r.status)
                        put("productionModel", r.productionModel)
                        put("reasons", buildJsonArray { r.reasons.forEach { add(JsonPrimitive(it)) } })
                        put("checks", buildJsonObject { r.checks.forEach { (k, v) -> put(k, v) } })
                    }
                )
            }

            get("/v1/admin/images/benchmarks/prompts") {
                if (!call.requireAdminBench(adminAuthorizationProvider)) return@get
                call.respond(
                    buildJsonArray {
                        BenchmarkPrompts.ALL.forEach { p ->
                            add(
                                buildJsonObject {
                                    put("promptId", p.promptId)
                                    put("category", p.category)
                                    put("purpose", p.purpose)
                                    put("text", p.text)
                                }
                            )
                        }
                    }
                )
            }

            get("/v1/admin/images/benchmarks/models") {
                if (!call.requireAdminBench(adminAuthorizationProvider)) return@get
                try {
                    call.respond(
                        buildJsonArray {
                            service.discoverSlots().forEach { slot ->
                                add(
                                    buildJsonObject {
                                        put("slotKey", slot.slotKey)
                                        put("displayName", slot.displayName)
                                        put("available", slot.available)
                                        put("modelId", slot.modelId ?: "UNAVAILABLE")
                                        put("provider", slot.provider)
                                        put("maxReferences", slot.maxReferences)
                                        put("maxCandidates", slot.maxCandidates)
                                        put("pricingSource", slot.pricingSource)
                                        put("pricingSnapshot", slot.pricingSnapshot)
                                        put("capabilitySnapshot", slot.capabilitySnapshot)
                                        put("mappingNote", slot.mappingNote)
                                        put(
                                            "supportedResolutions",
                                            buildJsonArray { slot.supportedResolutions.forEach { add(JsonPrimitive(it)) } },
                                        )
                                    }
                                )
                            }
                        }
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse(ApiError(ErrorCode.INTERNAL_SERVER_ERROR, e.message?.take(300) ?: "Discovery failed", null)),
                    )
                }
            }

            get("/v1/admin/images/benchmarks/personas") {
                if (!call.requireAdminBench(adminAuthorizationProvider)) return@get
                call.respond(
                    buildJsonArray {
                        service.listEligiblePersonas().forEach { p ->
                            add(
                                buildJsonObject {
                                    put("personaId", p.personaId.toString())
                                    put("displayName", p.displayName)
                                    put("apparentAge", p.apparentAge)
                                    put("eligible", p.eligible)
                                    put("visualVersionId", p.visualVersionId?.toString())
                                    put("reasons", buildJsonArray { p.reasons.forEach { add(JsonPrimitive(it)) } })
                                    put(
                                        "standardReferenceIds",
                                        buildJsonObject {
                                            p.standardReferenceIds.forEach { (k, v) -> put(k, v.toString()) }
                                        },
                                    )
                                }
                            )
                        }
                    }
                )
            }

            post("/v1/admin/images/benchmarks") {
                if (!call.requireAdminBench(adminAuthorizationProvider)) return@post
                val body = try {
                    call.receive<CreateBenchmarkRunHttpRequest>()
                } catch (_: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid request body", null)))
                    return@post
                }
                try {
                    val id = service.create(
                        ImageModelBenchmarkService.CreateRunCommand(
                            name = body.name,
                            personaIds = body.personaIds.map(UUID::fromString),
                            slotKeys = body.slotKeys,
                            promptIds = body.promptIds,
                            notes = body.notes,
                            createdBy = call.principal<UserIdPrincipal>()?.name,
                            start = body.start,
                        )
                    )
                    call.respond(HttpStatusCode.Created, detailJson(service.getDetail(id)!!))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, e.message ?: "Validation error", null)))
                } catch (e: IllegalStateException) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, e.message ?: "Conflict", null)))
                }
            }

            get("/v1/admin/images/benchmarks") {
                if (!call.requireAdminBench(adminAuthorizationProvider)) return@get
                call.respond(
                    buildJsonArray {
                        service.listRuns().forEach { r ->
                            add(
                                buildJsonObject {
                                    put("id", r.id.toString())
                                    put("name", r.name)
                                    put("status", r.status)
                                    put("createdAt", r.createdAt.toString())
                                    put("createdBy", r.createdBy)
                                    put("notes", r.notes)
                                }
                            )
                        }
                    }
                )
            }

            get("/v1/admin/images/benchmarks/{runId}") {
                if (!call.requireAdminBench(adminAuthorizationProvider)) return@get
                val runId = parseUuid(call.parameters["runId"])
                if (runId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid runId", null)))
                    return@get
                }
                val detail = service.getDetail(runId)
                if (detail == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Benchmark run not found", null)))
                } else {
                    call.respond(detailJson(detail))
                }
            }

            get("/v1/admin/images/benchmarks/{runId}/comparison") {
                if (!call.requireAdminBench(adminAuthorizationProvider)) return@get
                val runId = parseUuid(call.parameters["runId"])
                if (runId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid runId", null)))
                    return@get
                }
                val detail = service.getDetail(runId)
                if (detail == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(ApiError(ErrorCode.NOT_FOUND, "Benchmark run not found", null)))
                } else {
                    call.respond(comparisonJson(detail))
                }
            }

            post("/v1/admin/images/benchmarks/{runId}/start") {
                if (!call.requireAdminBench(adminAuthorizationProvider)) return@post
                val runId = parseUuid(call.parameters["runId"])
                if (runId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid runId", null)))
                    return@post
                }
                try {
                    service.start(runId)
                    call.respond(detailJson(service.getDetail(runId)!!))
                } catch (e: IllegalStateException) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, e.message ?: "Cannot start", null)))
                } catch (e: NoSuchElementException) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(ApiError(ErrorCode.NOT_FOUND, e.message ?: "Not found", null)))
                }
            }

            post("/v1/admin/images/benchmarks/executions/{executionId}/evaluation") {
                if (!call.requireAdminBench(adminAuthorizationProvider)) return@post
                val executionId = parseUuid(call.parameters["executionId"])
                if (executionId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid executionId", null)))
                    return@post
                }
                val body = try {
                    call.receive<BenchmarkEvaluationHttpRequest>()
                } catch (_: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, "Invalid request body", null)))
                    return@post
                }
                try {
                    val row = service.evaluate(
                        executionId = executionId,
                        candidateId = body.candidateId?.let(UUID::fromString),
                        identityRating = body.identityRating,
                        identityRemarks = body.identityRemarks,
                        realismRating = body.realismRating,
                        realismRemarks = body.realismRemarks,
                        adminDecision = body.adminDecision,
                        adminRemarks = body.adminRemarks,
                        evaluatedBy = call.principal<UserIdPrincipal>()?.name,
                    )
                    call.respond(
                        buildJsonObject {
                            put("id", row.id.toString())
                            put("executionId", row.executionId.toString())
                            put("candidateId", row.candidateId?.toString())
                            put("identityRating", row.identityRating)
                            put("identityRemarks", row.identityRemarks)
                            put("realismRating", row.realismRating)
                            put("realismRemarks", row.realismRemarks)
                            put("adminDecision", row.adminDecision)
                            put("adminRemarks", row.adminRemarks)
                            put("evaluatedAt", row.evaluatedAt.toString())
                            put("evaluatedBy", row.evaluatedBy)
                        }
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCode.VALIDATION_ERROR, e.message ?: "Validation error", null)))
                } catch (e: NoSuchElementException) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(ApiError(ErrorCode.NOT_FOUND, e.message ?: "Not found", null)))
                }
            }
        }
    }
}

private fun parseUuid(raw: String?): UUID? =
    raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }

private fun detailJson(detail: ImageModelBenchmarkService.RunDetail): JsonObject {
    val evalByExec = detail.evaluations.associateBy { it.executionId }
    return buildJsonObject {
        put("id", detail.run.id.toString())
        put("name", detail.run.name)
        put("status", detail.run.status)
        put("notes", detail.run.notes)
        put("createdBy", detail.run.createdBy)
        put("createdAt", detail.run.createdAt.toString())
        put("productionModel", detail.productionModel)
        put("productionModelUnchanged", true)
        put(
            "models",
            buildJsonArray {
                detail.models.forEach { m ->
                    add(
                        buildJsonObject {
                            put("slotKey", m.slotKey)
                            put("modelId", m.modelId ?: "UNAVAILABLE")
                            put("displayName", m.displayName)
                            put("provider", m.provider)
                            put("status", m.status)
                            put("capabilitySnapshot", m.capabilitySnapshot)
                            put("pricingSnapshot", m.pricingSnapshot)
                        }
                    )
                }
            },
        )
        put(
            "prompts",
            buildJsonArray {
                detail.prompts.forEach { p ->
                    add(
                        buildJsonObject {
                            put("promptId", p.promptId)
                            put("category", p.category)
                            put("promptText", p.promptText)
                        }
                    )
                }
            },
        )
        put(
            "executions",
            buildJsonArray {
                detail.executions.forEach { e ->
                    val candidates = e.jobId?.let { detail.candidatesByJob[it] }.orEmpty()
                    val ev = evalByExec[e.id]
                    add(
                        buildJsonObject {
                            put("id", e.id.toString())
                            put("personaId", e.personaId.toString())
                            put("visualIdentityVersionId", e.visualIdentityVersionId.toString())
                            put("promptId", e.promptId)
                            put("modelId", e.modelId ?: "UNAVAILABLE")
                            put("slotKey", e.slotKey)
                            put("referencesAvailable", e.referencesAvailable)
                            put("referencesSent", e.referencesSent)
                            put("referencesOmitted", e.referencesOmitted)
                            put("referenceOmitReason", e.referenceOmitReason)
                            put("requestedCandidates", e.requestedCandidates)
                            put("actualCandidates", e.actualCandidates)
                            put("requestedResolution", e.requestedResolution)
                            put("actualResolution", e.actualResolution)
                            put("resolutionDeviation", e.resolutionDeviation)
                            put("jobId", e.jobId?.toString())
                            put("providerRequestId", e.providerRequestId)
                            put("status", e.status)
                            put("providerFailureType", e.providerFailureType)
                            put("providerFailureMessage", e.providerFailureMessage)
                            put("actualCost", e.actualCost?.toPlainString() ?: "UNAVAILABLE")
                            put("currency", e.currency)
                            put(
                                "candidates",
                                buildJsonArray {
                                    candidates.forEach { c ->
                                        add(
                                            buildJsonObject {
                                                put("id", c.id.toString())
                                                put("candidateIndex", c.candidateIndex)
                                                put("status", c.status.name)
                                                put("assetUrl", "/v1/admin/images/candidates/${c.id}/asset")
                                            }
                                        )
                                    }
                                },
                            )
                            if (ev != null) {
                                put(
                                    "evaluation",
                                    buildJsonObject {
                                        put("identityRating", ev.identityRating)
                                        put("identityRemarks", ev.identityRemarks)
                                        put("realismRating", ev.realismRating)
                                        put("realismRemarks", ev.realismRemarks)
                                        put("adminDecision", ev.adminDecision)
                                        put("adminRemarks", ev.adminRemarks)
                                    },
                                )
                            } else {
                                put("evaluation", JsonNull)
                            }
                        }
                    )
                }
            },
        )
    }
}

private fun comparisonJson(detail: ImageModelBenchmarkService.RunDetail): JsonObject {
    val evalByExec = detail.evaluations.associateBy { it.executionId }
    val byPersona = detail.executions.groupBy { it.personaId }
    return buildJsonObject {
        put("runId", detail.run.id.toString())
        put("name", detail.run.name)
        put("note", "No winner or ranking. Parameters: identity, realism, restriction, actual cost.")
        put(
            "personas",
            buildJsonArray {
                byPersona.forEach { (personaId, execs) ->
                    add(
                        buildJsonObject {
                            put("personaId", personaId.toString())
                            put(
                                "prompts",
                                buildJsonArray {
                                    execs.groupBy { it.promptId }.forEach { (promptId, rows) ->
                                        add(
                                            buildJsonObject {
                                                put("promptId", promptId)
                                                put(
                                                    "models",
                                                    buildJsonArray {
                                                        rows.forEach { e ->
                                                            val ev = evalByExec[e.id]
                                                            val candidates = e.jobId?.let { detail.candidatesByJob[it] }.orEmpty()
                                                            add(
                                                                buildJsonObject {
                                                                    put("slotKey", e.slotKey)
                                                                    put("modelId", e.modelId ?: "UNAVAILABLE")
                                                                    put("status", e.status)
                                                                    put("actualCost", e.actualCost?.toPlainString() ?: "UNAVAILABLE")
                                                                    put("identityRating", ev?.identityRating)
                                                                    put("realismRating", ev?.realismRating)
                                                                    put(
                                                                        "candidateUrls",
                                                                        buildJsonArray {
                                                                            candidates.forEach { c ->
                                                                                add(JsonPrimitive("/v1/admin/images/candidates/${c.id}/asset"))
                                                                            }
                                                                        },
                                                                    )
                                                                }
                                                            )
                                                        }
                                                    },
                                                )
                                            }
                                        )
                                    }
                                },
                            )
                        }
                    )
                }
            },
        )
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.put(key: String, value: String?) {
    if (value == null) put(key, JsonNull) else put(key, value)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.put(key: String, value: Int?) {
    if (value == null) put(key, JsonNull) else put(key, value)
}

private suspend fun ApplicationCall.requireAdminBench(auth: AdminAuthorizationProvider): Boolean {
    val principal = principal<UserIdPrincipal>()
    if (principal == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, "Authentication required", null)))
        return false
    }
    if (!auth.isAdmin(principal.name)) {
        respond(HttpStatusCode.Forbidden, ErrorResponse(ApiError(ErrorCode.ENTITLEMENT_DENIED, "Admin access required", null)))
        return false
    }
    return true
}
