package com.pinkdreams.auth

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.basic

class DevAuthProvider {
    fun install(application: Application, sessionAuth: AdminSessionAuth) {
        application.install(Authentication) {
            basic("dev-auth") {
                realm = "Pink Dreams Dev"
                validate { credentials ->
                    if (credentials.name.isNotBlank() && credentials.password.isNotBlank()) {
                        UserIdPrincipal(credentials.name)
                    } else {
                        null
                    }
                }
            }
            // See SessionCookieAuthProvider's doc comment for why this exists
            // and why admin routes list it before "dev-auth".
            sessionCookie("session-auth", sessionAuth)
        }
    }
}
