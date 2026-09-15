package com.pinkdreams.auth

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.basic

class DevAuthProvider {
    fun install(application: Application) {
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
        }
    }
}
