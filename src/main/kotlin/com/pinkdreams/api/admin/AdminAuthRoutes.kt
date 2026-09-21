package com.pinkdreams.api.admin

import com.pinkdreams.auth.AdminSessionAuth
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class AdminLoginRequest(val username: String, val password: String)

@Serializable
data class AdminLoginResponse(val success: Boolean, val message: String? = null)

/**
 * Admin login/logout — the temporary static-credential session gate (see
 * [AdminSessionAuth]). These two endpoints are the only admin-scoped routes
 * reachable without a valid session already, since a session must first be
 * established somewhere.
 */
class AdminAuthRoutes(private val sessionAuth: AdminSessionAuth) {
    fun register(route: Route) {
        route.post("/v1/admin/auth/login") {
            val body = try {
                call.receive<AdminLoginRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, AdminLoginResponse(false, "Invalid request body"))
                return@post
            }

            if (sessionAuth.validateCredentials(body.username, body.password)) {
                val token = sessionAuth.createSession()
                call.response.cookies.append(
                    Cookie(
                        name = AdminSessionAuth.COOKIE_NAME,
                        value = token,
                        httpOnly = true,
                        path = "/",
                        maxAge = 24 * 60 * 60,
                        extensions = mapOf("SameSite" to "Lax"),
                    ),
                )
                call.respond(HttpStatusCode.OK, AdminLoginResponse(true))
            } else {
                call.respond(HttpStatusCode.Unauthorized, AdminLoginResponse(false, "Invalid credentials"))
            }
        }

        route.post("/v1/admin/auth/logout") {
            val token = call.request.cookies[AdminSessionAuth.COOKIE_NAME]
            sessionAuth.invalidate(token)
            call.response.cookies.append(
                Cookie(name = AdminSessionAuth.COOKIE_NAME, value = "", path = "/", maxAge = 0),
            )
            call.respond(HttpStatusCode.OK, AdminLoginResponse(true))
        }
    }
}
