package com.pinkdreams.api.health

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

class HealthRoutes(
    /** Extra non-sensitive fields (e.g. imageStorageMode). Must not include secrets or absolute paths. */
    private val extras: () -> Map<String, String> = { emptyMap() },
) {
    fun register(route: Route) {
        route.get("/health") {
            val body = linkedMapOf<String, String>("status" to "ok")
            body.putAll(extras())
            call.respond(HttpStatusCode.OK, body)
        }
    }
}
