package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.DatabaseFactory
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SensitivePreferenceRepositoryTest {

    private fun fixture() = SensitivePreferenceRepository(DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) })

    @Test
    fun `explicit preference is persisted with correct provenance and status`() {
        val repo = fixture()
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()

        val created = repo.create(
            userId = userId,
            personaId = personaId,
            category = "romantic",
            preferenceType = "preference",
            content = "prefers slow, gradual romantic escalation",
            provenance = "explicit",
        )

        assertEquals("explicit", created.provenance)
        assertEquals("active", created.status)
        assertEquals("romantic", created.category)
        assertEquals("preference", created.preferenceType)
        assertNull(created.supersedesId)
    }

    @Test
    fun `boundary is stored distinctly from preference for the same category`() {
        val repo = fixture()
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()

        val preference = repo.create(userId, personaId, "sexual", "preference", "likes X", "explicit")
        val boundary = repo.create(userId, personaId, "sexual", "boundary", "does not want Y", "explicit")

        val active = repo.findActiveForRelationship(userId, personaId)
        assertEquals(2, active.size)
        assertTrue(active.any { it.id == preference.id && it.preferenceType == "preference" })
        assertTrue(active.any { it.id == boundary.id && it.preferenceType == "boundary" })
    }

    @Test
    fun `history type is stored separately from preference and boundary`() {
        val repo = fixture()
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()

        val history = repo.create(userId, personaId, "intimacy", "history", "previously experienced Z", "explicit")

        assertEquals("history", history.preferenceType)
        val active = repo.findActiveForRelationship(userId, personaId)
        assertEquals(1, active.size)
        assertEquals("history", active.single().preferenceType)
    }

    @Test
    fun `invalid category type or provenance is rejected`() {
        val repo = fixture()
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()

        assertFailsWith<IllegalArgumentException> { repo.create(userId, personaId, "not-a-category", "preference", "x", "explicit") }
        assertFailsWith<IllegalArgumentException> { repo.create(userId, personaId, "romantic", "not-a-type", "x", "explicit") }
        assertFailsWith<IllegalArgumentException> { repo.create(userId, personaId, "romantic", "preference", "x", "not-a-provenance") }
    }

    @Test
    fun `update supersedes the previous active preference rather than duplicating it`() {
        val repo = fixture()
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()

        val original = repo.recordSuperseding(userId, personaId, "romantic", "preference", "likes X", "explicit")
        val updated = repo.recordSuperseding(userId, personaId, "romantic", "preference", "no longer likes X, prefers Y", "explicit")

        assertEquals(updated.id, repo.findActiveOneForTest(userId, personaId, "romantic", "preference")?.id)
        val reloadedOriginal = repo.findById(original.id)!!
        assertEquals("superseded", reloadedOriginal.status)
        assertEquals(updated.supersedesId, original.id)

        val all = repo.findForRelationship(userId, personaId)
        assertEquals(2, all.size, "History must be preserved, not deleted, on update")
        val active = repo.findActiveForRelationship(userId, personaId)
        assertEquals(1, active.size, "Only the newest statement is active")
        assertEquals("no longer likes X, prefers Y", active.single().content)
    }

    @Test
    fun `deactivate soft deletes a preference without removing its row`() {
        val repo = fixture()
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val created = repo.create(userId, personaId, "romantic", "preference", "likes X", "explicit")

        val deleted = repo.deactivate(created.id)

        assertEquals("deleted", deleted.status)
        assertTrue(repo.findActiveForRelationship(userId, personaId).isEmpty())
        assertEquals(1, repo.findForRelationship(userId, personaId).size, "Row must still exist after soft delete")
    }

    @Test
    fun `one user's preferences never appear in another user's relationship query`() {
        val repo = fixture()
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        repo.create(userA, personaId, "romantic", "preference", "USER_A_PREFERENCE", "explicit")

        assertTrue(repo.findActiveForRelationship(userB, personaId).isEmpty())
    }

    @Test
    fun `preferences for one persona never appear in another persona's relationship query`() {
        val repo = fixture()
        val userId = UUID.randomUUID()
        val personaA = UUID.randomUUID()
        val personaB = UUID.randomUUID()
        repo.create(userId, personaA, "romantic", "preference", "PERSONA_A_SCOPED_PREFERENCE", "explicit")

        assertTrue(repo.findActiveForRelationship(userId, personaB).isEmpty())
    }

    // Small helper exposed only for this test's readability, mirroring the
    // repository's own private lookup used by recordSuperseding.
    private fun SensitivePreferenceRepository.findActiveOneForTest(
        userId: UUID,
        personaId: UUID,
        category: String,
        preferenceType: String,
    ) = findActiveForRelationship(userId, personaId).singleOrNull { it.category == category && it.preferenceType == preferenceType }
}
