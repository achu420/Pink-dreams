package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.UserProfiles
import com.pinkdreams.persistence.database.defaultNow
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.UUID

class UserProfileRepository(private val db: Database) {
    data class UserProfile(
        val userId: UUID,
        val displayName: String?,
        val preferredLanguage: String?,
        val communicationStyle: String?,
        val updatedAt: LocalDateTime,
    )

    fun create(
        userId: UUID,
        displayName: String? = null,
        preferredLanguage: String? = null,
        communicationStyle: String? = null,
    ): UserProfile = transaction(db) {
        UserProfiles.insert {
            it[UserProfiles.userId] = userId
            it[UserProfiles.displayName] = displayName
            it[UserProfiles.preferredLanguage] = preferredLanguage
            it[UserProfiles.communicationStyle] = communicationStyle
            it[UserProfiles.updatedAt] = defaultNow()
        }
        findByUserId(userId)!!
    }

    fun findByUserId(userId: UUID): UserProfile? = transaction(db) {
        UserProfiles.select { UserProfiles.userId eq userId }
            .map(::rowToModel)
            .singleOrNull()
    }

    private fun rowToModel(row: ResultRow): UserProfile = UserProfile(
        userId = row[UserProfiles.userId],
        displayName = row[UserProfiles.displayName],
        preferredLanguage = row[UserProfiles.preferredLanguage],
        communicationStyle = row[UserProfiles.communicationStyle],
        updatedAt = row[UserProfiles.updatedAt],
    )
}