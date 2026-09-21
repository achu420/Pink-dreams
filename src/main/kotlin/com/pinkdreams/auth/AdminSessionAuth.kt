package com.pinkdreams.auth

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple static-credential admin session gate, added on top of the existing
 * bearer/basic-auth (`AdminAuthorizationProvider`) mechanism.
 *
 * TEMPORARY: the username/password below are hardcoded static credentials
 * to be replaced with a real admin identity system later. They are never
 * logged and never returned in any response body — only an opaque random
 * session token is ever sent to the browser, in an HTTP-only cookie.
 *
 * Sessions are held in-memory only. This is intentional: the admin console
 * runs as a single process, and a session that does not survive a restart
 * (or get shared across a cluster) is an acceptable tradeoff for a "log in
 * with a fixed password" placeholder — it is not meant to be a durable
 * identity system.
 */
open class AdminSessionAuth {
    companion object {
        const val COOKIE_NAME = "admin_session"

        // The identity used for the Ktor principal set by SessionCookieAuthProvider
        // once a request's cookie is verified valid. Not a real user id — it only
        // needs to satisfy AdminAuthorizationProvider.isAdmin(), which special-cases
        // this exact string. Never derived from client input.
        const val SESSION_PRINCIPAL_NAME = "admin-session-principal"

        private const val SESSION_TTL_MILLIS = 24L * 60 * 60 * 1000

        // TEMPORARY static credentials — replace with a real admin identity
        // system. Do not log, echo, or otherwise expose these.
        private const val STATIC_USERNAME = "achal"
        private const val STATIC_PASSWORD = "Iamachal"
    }

    private val sessions = ConcurrentHashMap<String, Long>() // token -> expiry epoch millis
    private val random = SecureRandom()

    open fun validateCredentials(username: String, password: String): Boolean =
        username == STATIC_USERNAME && password == STATIC_PASSWORD

    open fun createSession(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        sessions[token] = Instant.now().toEpochMilli() + SESSION_TTL_MILLIS
        return token
    }

    open fun isValidSession(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val expiry = sessions[token] ?: return false
        if (expiry < Instant.now().toEpochMilli()) {
            sessions.remove(token)
            return false
        }
        return true
    }

    open fun invalidate(token: String?) {
        if (!token.isNullOrBlank()) sessions.remove(token)
    }
}
