package com.pinkdreams.chat.memoryengine

import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryEngineChangeApplierTest {

    private fun fixture(): Pair<MemoryFactRepository, MemoryEngineChangeApplier> {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val repo = MemoryFactRepository(db)
        return repo to MemoryEngineChangeApplier(repo)
    }

    @Test
    fun `ADD creates a new fact with the correct owner`() {
        val (repo, applier) = fixture()
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()

        val outcome = applier.apply(userId, personaId, "PERSONA", listOf(
            MemoryChange(MemoryChangeAction.ADD, memoryType = "commitment", content = "Simran promised to follow up", criticality = "high"),
        ))

        assertEquals(1, outcome.applied)
        assertEquals(0, outcome.skipped)
        val facts = repo.findForRelationship(userId, personaId)
        assertEquals(1, facts.size)
        assertEquals("PERSONA", facts.single().owner)
        assertEquals("high", facts.single().criticality)
    }

    @Test
    fun `ADD with invalid memory type is skipped not applied`() {
        val (_, applier) = fixture()
        val outcome = applier.apply(UUID.randomUUID(), UUID.randomUUID(), "USER", listOf(
            MemoryChange(MemoryChangeAction.ADD, memoryType = "not_a_real_type", content = "x"),
        ))
        assertEquals(0, outcome.applied)
        assertEquals(1, outcome.skipped)
    }

    @Test
    fun `UPDATE modifies the existing fact content`() {
        val (repo, applier) = fixture()
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val existing = repo.create(userId, personaId, "original", "interest", "medium")

        val outcome = applier.apply(userId, personaId, "USER", listOf(MemoryChange(MemoryChangeAction.UPDATE, memoryId = existing.id, content = "refined")))

        assertEquals(1, outcome.applied)
        assertEquals("refined", repo.findById(existing.id)!!.fact)
    }

    @Test
    fun `SUPERSEDE marks old fact superseded and creates a new one`() {
        val (repo, applier) = fixture()
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val existing = repo.create(userId, personaId, "old fact", "interest", "medium")

        applier.apply(userId, personaId, "USER", listOf(MemoryChange(MemoryChangeAction.SUPERSEDE, memoryId = existing.id, content = "new fact")))

        assertEquals("superseded", repo.findById(existing.id)!!.status)
        assertEquals(2, repo.findForRelationship(userId, personaId).size)
    }

    @Test
    fun `REMOVE soft-deletes the fact`() {
        val (repo, applier) = fixture()
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val existing = repo.create(userId, personaId, "fact", "interest", "medium")

        applier.apply(userId, personaId, "USER", listOf(MemoryChange(MemoryChangeAction.REMOVE, memoryId = existing.id)))

        assertEquals("removed", repo.findById(existing.id)!!.status)
        assertEquals(1, repo.findForRelationship(userId, personaId).size, "REMOVE never deletes the row")
    }

    @Test
    fun `KEEP and IGNORE are no-ops and count as applied`() {
        val (repo, applier) = fixture()
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val existing = repo.create(userId, personaId, "fact", "interest", "medium")

        val outcome = applier.apply(userId, personaId, "USER", listOf(
            MemoryChange(MemoryChangeAction.KEEP, memoryId = existing.id),
            MemoryChange(MemoryChangeAction.IGNORE),
        ))

        assertEquals(2, outcome.applied)
        assertEquals("fact", repo.findById(existing.id)!!.fact, "KEEP must not modify content")
    }

    // --- User/persona isolation: a hallucinated or cross-relationship memoryId must never be mutated ---
    @Test
    fun `UPDATE targeting a fact belonging to a different user is skipped`() {
        val (repo, applier) = fixture()
        val realUser = UUID.randomUUID()
        val otherUser = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val otherUsersFact = repo.create(otherUser, personaId, "other user's fact", "interest", "medium")

        val outcome = applier.apply(realUser, personaId, "USER", listOf(MemoryChange(MemoryChangeAction.UPDATE, memoryId = otherUsersFact.id, content = "hijacked")))

        assertEquals(0, outcome.applied)
        assertEquals(1, outcome.skipped)
        assertEquals("other user's fact", repo.findById(otherUsersFact.id)!!.fact, "Cross-user mutation must never happen")
    }

    @Test
    fun `REMOVE targeting a fact belonging to a different persona is skipped`() {
        val (repo, applier) = fixture()
        val userId = UUID.randomUUID()
        val personaA = UUID.randomUUID()
        val personaB = UUID.randomUUID()
        val personaBsFact = repo.create(userId, personaB, "persona B's fact", "interest", "medium")

        val outcome = applier.apply(userId, personaA, "USER", listOf(MemoryChange(MemoryChangeAction.REMOVE, memoryId = personaBsFact.id)))

        assertEquals(0, outcome.applied)
        assertEquals("open", repo.findById(personaBsFact.id)!!.status, "Cross-persona removal must never happen")
    }

    @Test
    fun `UPDATE targeting a fact owned by the wrong owner is skipped`() {
        val (repo, applier) = fixture()
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val userOwnedFact = repo.create(userId, personaId, "user fact", "interest", "medium", owner = "USER")

        // Attempting to apply as a PERSONA-scope change against a USER-owned fact id.
        val outcome = applier.apply(userId, personaId, "PERSONA", listOf(MemoryChange(MemoryChangeAction.UPDATE, memoryId = userOwnedFact.id, content = "hijacked")))

        assertEquals(0, outcome.applied)
        assertEquals("user fact", repo.findById(userOwnedFact.id)!!.fact)
    }

    @Test
    fun `a nonexistent memoryId is skipped without throwing`() {
        val (_, applier) = fixture()
        val outcome = applier.apply(UUID.randomUUID(), UUID.randomUUID(), "USER", listOf(MemoryChange(MemoryChangeAction.UPDATE, memoryId = UUID.randomUUID(), content = "x")))
        assertEquals(0, outcome.applied)
        assertEquals(1, outcome.skipped)
    }

    @Test
    fun `one invalid change does not abort the rest of the batch`() {
        val (repo, applier) = fixture()
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val valid = repo.create(userId, personaId, "fact", "interest", "medium")

        val outcome = applier.apply(userId, personaId, "USER", listOf(
            MemoryChange(MemoryChangeAction.UPDATE, memoryId = UUID.randomUUID(), content = "x"), // invalid: nonexistent
            MemoryChange(MemoryChangeAction.UPDATE, memoryId = valid.id, content = "updated"),
        ))

        assertEquals(1, outcome.applied)
        assertEquals(1, outcome.skipped)
        assertEquals("updated", repo.findById(valid.id)!!.fact)
    }

    /**
     * Task 25F fix 2. Mirrors the real over-capacity relationship
     * (user 36d344df…, persona 378b82c0…): 15 llm_extracted hot facts already
     * present plus Memory Engine ADDs on top, which previously bypassed
     * MemoryService.record() and therefore MAX_HOT_FACTS entirely, reaching 22
     * live hot facts against a limit of 20.
     */
    @Test
    fun `Task 25F - Memory Engine ADD is subject to MAX_HOT_FACTS`() {
        val (repo, applier) = fixture()
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        repeat(15) { i -> repo.create(userId, personaId, "extracted fact $i", "interest", "medium", source = "llm_extracted") }

        val outcome = applier.apply(
            userId, personaId, "USER",
            (0 until 7).map { i ->
                MemoryChange(MemoryChangeAction.ADD, memoryType = "interest", content = "engine fact $i", criticality = "medium")
            },
        )

        assertEquals(7, outcome.applied, "every valid ADD must still be applied")
        val liveHot = repo.findForRelationship(userId, personaId)
            .filter { it.tier == "hot" && it.status !in setOf("superseded", "removed") }
        assertEquals(
            com.pinkdreams.chat.memory.MemoryService.MAX_HOT_FACTS,
            liveHot.size,
            "hot capacity must be enforced regardless of which write path created the fact",
        )
        assertEquals(22, repo.findForRelationship(userId, personaId).size, "nothing is deleted — the excess is evicted to cold")
    }

    @Test
    fun `Task 25F - a batch with no hot-tier write leaves tiers untouched`() {
        val (repo, applier) = fixture()
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        repeat(25) { i -> repo.create(userId, personaId, "pre-existing $i", "interest", "medium") }

        applier.apply(userId, personaId, "USER", listOf(MemoryChange(MemoryChangeAction.KEEP)))

        // No hot row was added, so this pre-existing (already over-capacity)
        // state is deliberately not rebalanced: the fix enforces capacity on
        // write, it does not introduce a background compactor.
        assertEquals(25, repo.findForRelationship(userId, personaId).count { it.tier == "hot" })
    }
}
