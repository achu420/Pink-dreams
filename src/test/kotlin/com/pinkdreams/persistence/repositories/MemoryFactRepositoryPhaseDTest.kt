package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.DatabaseFactory
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MemoryFactRepositoryPhaseDTest {

    private fun fixture(): MemoryFactRepository = MemoryFactRepository(DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) })

    @Test
    fun `existing rows and default create default to owner USER`() {
        val repo = fixture()
        val created = repo.create(UUID.randomUUID(), UUID.randomUUID(), "some fact", "interest", "medium")
        assertEquals("USER", created.owner)
        assertNull(created.supersedesId)
        assertNull(created.updatedAt)
    }

    @Test
    fun `persona owned facts can be created explicitly`() {
        val repo = fixture()
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val created = repo.create(userId, personaId, "Simran promised to update the user", "commitment", "medium", owner = "PERSONA")
        assertEquals("PERSONA", created.owner)
    }

    @Test
    fun `updateContent changes the fact text in place and stamps updatedAt`() {
        val repo = fixture()
        val created = repo.create(UUID.randomUUID(), UUID.randomUUID(), "original text", "interest", "medium")
        val updated = repo.updateContent(created.id, "refined text")
        assertEquals("refined text", updated.fact)
        assertEquals(created.id, updated.id)
        assert(updated.updatedAt != null)
    }

    @Test
    fun `supersede marks the old row superseded and creates a new linked row preserving history`() {
        val repo = fixture()
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val original = repo.create(userId, personaId, "user works at Company A", "interest", "medium")

        val superseding = repo.supersede(original.id, "user now works at Company B")

        val reloadedOriginal = repo.findById(original.id)!!
        assertEquals("superseded", reloadedOriginal.status)
        assertEquals("user works at Company A", reloadedOriginal.fact, "Old content must be preserved, not overwritten")
        assertEquals(original.id, superseding.supersedesId)
        assertEquals("user now works at Company B", superseding.fact)
        assertEquals("open", superseding.status)

        assertEquals(2, repo.findForRelationship(userId, personaId).size, "History must be preserved as two rows, not overwritten in place")
    }

    @Test
    fun `remove sets status to removed and does not delete the row`() {
        val repo = fixture()
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val created = repo.create(userId, personaId, "some fact", "interest", "medium")

        val removed = repo.remove(created.id)

        assertEquals("removed", removed.status)
        assertEquals(1, repo.findForRelationship(userId, personaId).size, "Row must still exist after REMOVE — this is a soft state change, never a SQL DELETE")
    }

    @Test
    fun `updateContent on a nonexistent id throws rather than silently succeeding`() {
        val repo = fixture()
        assertFailsWith<IllegalArgumentException> { repo.updateContent(UUID.randomUUID(), "x") }
    }
}
