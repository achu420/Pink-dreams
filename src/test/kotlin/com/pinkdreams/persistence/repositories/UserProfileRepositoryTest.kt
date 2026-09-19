package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.DatabaseFactory
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UserProfileRepositoryTest {

    private fun repository(): UserProfileRepository {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        return UserProfileRepository(db)
    }

    // --- B. Profile persistence: all fields round-trip through the real DB layer ---
    @Test
    fun `scenario B all profile fields round trip through the real repository and database`() {
        val repository = repository()
        val userId = UUID.randomUUID()

        repository.create(
            userId = userId,
            displayName = "Alice",
            preferredLanguage = "en",
            communicationStyle = "warm",
            gender = "female",
            interest = "male",
            city = "Delhi",
            age = 28,
        )

        val loaded = repository.findByUserId(userId)
        assertNotNull(loaded)
        assertEquals("Alice", loaded.displayName)
        assertEquals("en", loaded.preferredLanguage)
        assertEquals("warm", loaded.communicationStyle)
        assertEquals("female", loaded.gender)
        assertEquals("male", loaded.interest)
        assertEquals("Delhi", loaded.city)
        assertEquals(28, loaded.age)
    }

    @Test
    fun `existing three field callers remain fully compatible with no new fields supplied`() {
        val repository = repository()
        val userId = UUID.randomUUID()

        // Matches the original pre-Phase-2A positional call shape.
        repository.create(userId, "Asha", "en", "warm")

        val loaded = repository.findByUserId(userId)
        assertNotNull(loaded)
        assertEquals("Asha", loaded.displayName)
        assertEquals("en", loaded.preferredLanguage)
        assertEquals("warm", loaded.communicationStyle)
        assertNull(loaded.gender)
        assertNull(loaded.interest)
        assertNull(loaded.city)
        assertNull(loaded.age)
    }

    // --- D. Interest validation at the repository/domain boundary ---
    @Test
    fun `interest is distinct from gender and restricted to the closed attraction set`() {
        val repository = repository()

        repository.create(UUID.randomUUID(), interest = "male")
        repository.create(UUID.randomUUID(), interest = "female")
        repository.create(UUID.randomUUID(), interest = "both")

        assertFailsWith<IllegalArgumentException> {
            repository.create(UUID.randomUUID(), interest = "female-ish")
        }
    }

    @Test
    fun `interest and gender are independent fields`() {
        val repository = repository()
        val userId = UUID.randomUUID()
        repository.create(userId, gender = "male", interest = "male")

        val loaded = repository.findByUserId(userId)!!
        assertEquals("male", loaded.gender)
        assertEquals("male", loaded.interest, "interest represents attraction, not the user's own gender, and may coincide but is stored independently")
    }

    // --- E. Age validation at the repository/domain boundary ---
    @Test
    fun `age boundaries are accepted and out of range values are rejected`() {
        val repository = repository()

        repository.create(UUID.randomUUID(), age = UserProfileRepository.MIN_AGE)
        repository.create(UUID.randomUUID(), age = UserProfileRepository.MAX_AGE)

        assertFailsWith<IllegalArgumentException> {
            repository.create(UUID.randomUUID(), age = UserProfileRepository.MIN_AGE - 1)
        }
        assertFailsWith<IllegalArgumentException> {
            repository.create(UUID.randomUUID(), age = UserProfileRepository.MAX_AGE + 1)
        }
    }
}
