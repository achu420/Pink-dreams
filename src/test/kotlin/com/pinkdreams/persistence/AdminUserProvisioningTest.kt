package com.pinkdreams.persistence

import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.UserProfileRepository
import com.pinkdreams.persistence.repositories.UserRepository
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class AdminUserProvisioningTest {

    private fun fixture(): Triple<AdminUserProvisioning, UserRepository, UserProfileRepository> {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val userRepository = UserRepository(db)
        val profileRepository = UserProfileRepository(db)
        return Triple(AdminUserProvisioning(db, userRepository, profileRepository), userRepository, profileRepository)
    }

    @Test
    fun `successful creation persists both the user and the profile with a server generated id`() {
        val (provisioning, userRepository, profileRepository) = fixture()

        val created = provisioning.createUserWithProfile(
            displayName = "Alice",
            gender = "female",
            interest = "male",
            city = "Delhi",
            age = 28,
        )

        assertNotNull(created.userId)
        assertEquals(true, userRepository.exists(created.userId))
        val profile = profileRepository.findByUserId(created.userId)
        assertNotNull(profile)
        assertEquals("Alice", profile.displayName)
        assertEquals("female", profile.gender)
        assertEquals("male", profile.interest)
        assertEquals("Delhi", profile.city)
        assertEquals(28, profile.age)
    }

    // --- C. Atomicity: profile-creation failure must not leave an orphaned user row ---
    @Test
    fun `scenario C profile validation failure rolls back the already-inserted user row`() {
        val (provisioning, userRepository, _) = fixture()
        val userCountBefore = userRepository.findAll().size

        assertFailsWith<IllegalArgumentException> {
            provisioning.createUserWithProfile(
                displayName = "Invalid",
                gender = null,
                interest = null,
                city = null,
                age = 999, // out of the valid 18..120 range -> UserProfileRepository.create() throws
            )
        }

        val userCountAfter = userRepository.findAll().size
        assertEquals(userCountBefore, userCountAfter, "A failed profile creation must not leave an orphaned user row behind")
    }

    @Test
    fun `scenario C invalid interest also rolls back the user row`() {
        val (provisioning, userRepository, _) = fixture()
        val userCountBefore = userRepository.findAll().size

        assertFailsWith<IllegalArgumentException> {
            provisioning.createUserWithProfile(
                displayName = "Invalid",
                gender = null,
                interest = "not-a-real-value",
                city = null,
                age = null,
            )
        }

        assertEquals(userCountBefore, userRepository.findAll().size)
    }
}
