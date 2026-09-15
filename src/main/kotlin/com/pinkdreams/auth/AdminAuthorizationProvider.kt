package com.pinkdreams.auth

import java.util.UUID

open class AdminAuthorizationProvider {
    private val adminUserIds: Set<UUID> = loadAdminUserIds()

    open fun isAdmin(userId: String): Boolean {
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
