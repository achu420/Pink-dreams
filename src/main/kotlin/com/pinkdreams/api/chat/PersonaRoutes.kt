package com.pinkdreams.api.chat

import com.pinkdreams.persistence.repositories.PersonaRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

@kotlinx.serialization.Serializable
data class PublicPersonaResponse(
    val id: String,
    val slug: String,
    val displayName: String,
    val gender: String,
    val orientation: String,
    val apparentAge: Int,
)

@kotlinx.serialization.Serializable
data class PublicPersonaListResponse(
    val personas: List<PublicPersonaResponse>,
)

class PersonaRoutes(
    private val personaRepository: PersonaRepository,
) {
    fun register(route: Route) {
        route.authenticate("dev-auth") {
            // GET /v1/personas — list active personas visible to a user.
            get("/v1/personas") {
                val personas = personaRepository.findAll()
                    .filter { it.status == "active" }
                call.respond(
                    HttpStatusCode.OK,
                    PublicPersonaListResponse(
                        personas = personas.map { p ->
                            PublicPersonaResponse(
                                id = p.id.toString(),
                                slug = p.slug,
                                displayName = p.displayName,
                                gender = p.gender,
                                orientation = p.orientation,
                                apparentAge = p.apparentAge,
                            )
                        },
                    ),
                )
            }
        }
    }
}
