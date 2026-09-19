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
        val gender: String? = null,
        val interest: String? = null,
        val city: String? = null,
        val age: Int? = null,
    )

    fun create(
        userId: UUID,
        displayName: String? = null,
        preferredLanguage: String? = null,
        communicationStyle: String? = null,
        gender: String? = null,
        interest: String? = null,
        city: String? = null,
        age: Int? = null,
    ): UserProfile {
        val normalizedInterest = interest?.trim()?.lowercase()?.also {
            require(it in VALID_INTERESTS) { "Invalid interest: $it (must be one of $VALID_INTERESTS)" }
        }
        age?.let {
            require(it in MIN_AGE..MAX_AGE) { "Invalid age: $it (must be between $MIN_AGE and $MAX_AGE)" }
        }

        return transaction(db) {
            UserProfiles.insert {
                it[UserProfiles.userId] = userId
                it[UserProfiles.displayName] = displayName
                it[UserProfiles.preferredLanguage] = preferredLanguage
                it[UserProfiles.communicationStyle] = communicationStyle
                it[UserProfiles.gender] = gender
                it[UserProfiles.interest] = normalizedInterest
                it[UserProfiles.city] = city
                it[UserProfiles.age] = age
                it[UserProfiles.updatedAt] = defaultNow()
            }
            findByUserId(userId)!!
        }
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
        gender = row[UserProfiles.gender],
        interest = row[UserProfiles.interest],
        city = row[UserProfiles.city],
        age = row[UserProfiles.age],
        updatedAt = row[UserProfiles.updatedAt],
    )

    companion object {
        val VALID_INTERESTS = setOf("male", "female", "both")
        const val MIN_AGE = 18
        const val MAX_AGE = 120
    }
}
