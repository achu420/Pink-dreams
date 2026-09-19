package com.pinkdreams.persistence

import com.pinkdreams.persistence.repositories.UserProfileRepository
import com.pinkdreams.persistence.repositories.UserRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * Composes UserRepository + UserProfileRepository into one atomic operation for
 * admin-console test-user creation. Mirrors the existing cross-repository
 * transaction pattern already used by RepositoryChatPersistence: both inserts
 * run inside a single Exposed transaction(db) block, so if profile creation
 * fails (e.g. validation) the user row is rolled back too — no partially
 * created user is ever left behind.
 */
class AdminUserProvisioning(
    private val db: Database,
    private val userRepository: UserRepository,
    private val userProfileRepository: UserProfileRepository,
) {
    data class CreatedUser(
        val userId: UUID,
        val profile: UserProfileRepository.UserProfile,
    )

    fun createUserWithProfile(
        displayName: String,
        gender: String?,
        interest: String?,
        city: String?,
        age: Int?,
    ): CreatedUser = transaction(db) {
        val userId = UUID.randomUUID()
        userRepository.create(userId)
        val profile = userProfileRepository.create(
            userId = userId,
            displayName = displayName,
            gender = gender,
            interest = interest,
            city = city,
            age = age,
        )
        CreatedUser(userId, profile)
    }
}
