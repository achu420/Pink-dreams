package com.pinkdreams.auth

import com.pinkdreams.persistence.repositories.AdminAllowlistRepository
import java.util.UUID

/**
 * Admin authorization = the UNION of two sources:
 *
 *   1. the ADMIN_USER_IDS environment variable (unchanged, still authoritative
 *      on its own), and
 *   2. the DB-backed admin_allowlist table, when a repository is supplied.
 *
 * The repository parameter defaults to null so every existing call site and
 * test that constructs `AdminAuthorizationProvider()` without a database keeps
 * exactly its previous behavior — env var only. The DB check is additive: it
 * can only ever GRANT admin, never revoke what the env var already grants, and
 * a failing DB read degrades to "not in the DB set" rather than throwing out of
 * an authorization check.
 */
open class AdminAuthorizationProvider(
    private val allowlistRepository: AdminAllowlistRepository? = null,
) {
    private val adminUserIds: Set<UUID> = loadAdminUserIds()

    /** The env-var set, for callers that must reason about lockout safety. */
    fun envAdminUserIds(): Set<UUID> = adminUserIds

    open fun isAdmin(userId: String): Boolean {
        // A request authenticated via the admin console's session cookie
        // (SessionCookieAuthProvider) carries this exact synthetic identity —
        // the session gate itself (a valid, unexpired cookie issued only after
        // the static-credential login) is the authorization check for that
        // path, so it is always treated as admin here.
        if (userId == AdminSessionAuth.SESSION_PRINCIPAL_NAME) {
            return true
        }
        val uuid = try {
            UUID.fromString(userId)
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (adminUserIds.contains(uuid)) {
            return true
        }
        val repository = allowlistRepository ?: return false
        return try {
            repository.contains(uuid)
        } catch (e: Exception) {
            // A database problem must never accidentally grant admin.
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
