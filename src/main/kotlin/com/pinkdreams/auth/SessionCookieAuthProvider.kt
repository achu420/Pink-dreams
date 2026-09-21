package com.pinkdreams.auth

import com.pinkdreams.common.errors.ApiError
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.common.errors.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.response.respond

/**
 * A Ktor authentication provider that accepts a request purely on the
 * strength of a valid [AdminSessionAuth] cookie — no Authorization header
 * involved at all.
 *
 * This exists so a browser session established via the admin login page
 * never also has to satisfy the separate `dev-auth` Basic-auth provider —
 * before this, any admin-ui.html fetch() call that lacked an Authorization
 * header (i.e. every one of them, since the browser session relies solely
 * on the cookie) fell through to `dev-auth`'s own challenge, which Ktor's
 * Basic provider answers with a `WWW-Authenticate: Basic` header — that is
 * exactly what triggers the browser's native login popup. Routes list this
 * provider FIRST in `authenticate("session-auth", "dev-auth")`: on failure,
 * Ktor tries each provider's registered challenge in order and stops at the
 * first one that completes the response, so this provider's plain-JSON,
 * challenge-header-free 401 wins the race and `dev-auth`'s Basic challenge
 * never runs for a plain browser call.
 */
private val sessionAuthChallengeKey: Any = "SessionCookieAuthChallenge"

class SessionCookieAuthProvider internal constructor(config: Config) : AuthenticationProvider(config) {
    private val sessionAuth: AdminSessionAuth = config.sessionAuth

    class Config(name: String?, val sessionAuth: AdminSessionAuth) : AuthenticationProvider.Config(name)

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val call = context.call
        val token = call.request.cookies[AdminSessionAuth.COOKIE_NAME]

        if (sessionAuth.isValidSession(token)) {
            context.principal(UserIdPrincipal(AdminSessionAuth.SESSION_PRINCIPAL_NAME))
            return
        }

        context.challenge(sessionAuthChallengeKey, AuthenticationFailedCause.NoCredentials) { challenge, call2 ->
            call2.respond(
                HttpStatusCode.Unauthorized,
                ErrorResponse(ApiError(ErrorCode.UNAUTHORIZED, "Admin session required", null)),
            )
            challenge.complete()
        }
    }
}

fun AuthenticationConfig.sessionCookie(name: String, sessionAuth: AdminSessionAuth) {
    val provider = SessionCookieAuthProvider(SessionCookieAuthProvider.Config(name, sessionAuth))
    register(provider)
}
