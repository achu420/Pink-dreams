package com.pinkdreams.auth

import java.util.UUID

open class AdminAuthorizationProvider {
    private val adminUserIds: Set<UUID> = loadAdminUserIds()

    open fun isAdmin(userId: String): Boolean {
        // A request authenticated via the admin console's session cookie
        // (SessionCookieAuthProvider) carries this exact synthetic identity —
        // the session gate itself (a valid, unexpired cookie issued only after
        // the static-credential login) is the authorization check for that
        // path, so it is always treated as admin here.
        if (userId == AdminSessionAuth.SESSION_PRINCIPAL_NAME) {
            return true
        }
        return try {
            val uuid = UUID.fromString(userId)
            adminUserIds.contains(uuid)
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun loadAdminUserIds(): Set<UUID> {
        val adminEnv = System.getenv("ADMIN_USER_IDS") ?: ""
        if (adminEnv.isBlank()) {
            return emptySet()
        }
        return adminEnv.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { idStr ->
                try {
                    UUID.fromString(idStr)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            .toSet()
    }
}
