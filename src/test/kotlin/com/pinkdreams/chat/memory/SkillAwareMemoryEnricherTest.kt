package com.pinkdreams.chat.memory

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.skill.SkillSelection
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillAwareMemoryEnricherTest {

    private fun sampleContext(): ChatContext = ChatContext(
        blocks = listOf(
            ContextBlock("system", "ENGINE_AND_PERSONA"),
            ContextBlock("system", "PROFILE"),
            ContextBlock("system", MemoryContextFormat.render(emptyList())),
            ContextBlock("user", "HIST_1"),
            ContextBlock("user", "CURRENT_MESSAGE"),
        ),
        engineVersionId = UUID.randomUUID(),
        personaCoreVersionId = UUID.randomUUID(),
    )

    private fun request(userId: UUID, personaId: UUID, content: String = "hello") =
        ChatRequest(UUID.randomUUID(), userId, UUID.randomUUID(), personaId, UUID.randomUUID(), content)

    // --- G. Candidate vs context limit ---
    @Test
    fun `more candidates are retrieved than end up in the final context block`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val memoryFactRepository = MemoryFactRepository(db)
        val memoryService = MemoryService(memoryFactRepository)
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        (1..20).forEach { i -> memoryService.record(userId, personaId, listOf(MemoryCandidate("fact number $i", "interest", "medium"))) }

        val skillRepo = SkillRepository(db)
        val selector = DeterministicMemoryContextSelector(skillRepo, contextLimit = 10)
        val enricher = SkillAwareMemoryEnricher(memoryService, selector, candidateLimit = 20)

        val result = enricher.enrich(sampleContext(), request(userId, personaId), SkillSelection.None)

        val memoryBlock = result.blocks.first { it.content.startsWith("RETRIEVED MEMORY:") }
        val selectedCount = memoryBlock.content.lines().count { it.startsWith("interest: fact number ") }
        assertEquals(10, selectedCount, "Only the context-limit number of facts should appear in the final block")
    }

    // --- H. Canonical memory preservation ---
    @Test
    fun `memories excluded from selection remain in canonical storage`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val memoryFactRepository = MemoryFactRepository(db)
        val memoryService = MemoryService(memoryFactRepository)
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        (1..20).forEach { i -> memoryService.record(userId, personaId, listOf(MemoryCandidate("fact number $i", "interest", "medium"))) }

        val skillRepo = SkillRepository(db)
        val enricher = SkillAwareMemoryEnricher(memoryService, DeterministicMemoryContextSelector(skillRepo, contextLimit = 5), candidateLimit = 20)

        enricher.enrich(sampleContext(), request(userId, personaId), SkillSelection.None)

        assertEquals(20, memoryFactRepository.findForRelationship(userId, personaId).size, "Selection must never delete canonical memory rows")
    }

    // --- J. Persona isolation ---
    @Test
    fun `memory belonging to a different persona is never selected`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val memoryFactRepository = MemoryFactRepository(db)
        val memoryService = MemoryService(memoryFactRepository)
        val userId = UUID.randomUUID()
        val personaA = UUID.randomUUID()
        val personaB = UUID.randomUUID()
        memoryService.record(userId, personaA, listOf(MemoryCandidate("PERSONA_A_ONLY_FACT", "interest", "medium")))
        memoryService.record(userId, personaB, listOf(MemoryCandidate("PERSONA_B_ONLY_FACT", "interest", "medium")))

        val skillRepo = SkillRepository(db)
        val enricher = SkillAwareMemoryEnricher(memoryService, DeterministicMemoryContextSelector(skillRepo))

        val result = enricher.enrich(sampleContext(), request(userId, personaA), SkillSelection.None)
        val memoryBlock = result.blocks.first { it.content.startsWith("RETRIEVED MEMORY:") }

        assertTrue(memoryBlock.content.contains("PERSONA_A_ONLY_FACT"))
        assertFalse(memoryBlock.content.contains("PERSONA_B_ONLY_FACT"), "Persona B's memory must never leak into Persona A's context")
    }

    @Test
    fun `memory belonging to a different user is never selected`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val memoryFactRepository = MemoryFactRepository(db)
        val memoryService = MemoryService(memoryFactRepository)
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        memoryService.record(userA, personaId, listOf(MemoryCandidate("USER_A_ONLY_FACT", "interest", "medium")))
        memoryService.record(userB, personaId, listOf(MemoryCandidate("USER_B_ONLY_FACT", "interest", "medium")))

        val skillRepo = SkillRepository(db)
        val enricher = SkillAwareMemoryEnricher(memoryService, DeterministicMemoryContextSelector(skillRepo))

        val result = enricher.enrich(sampleContext(), request(userA, personaId), SkillSelection.None)
        val memoryBlock = result.blocks.first { it.content.startsWith("RETRIEVED MEMORY:") }

        assertTrue(memoryBlock.content.contains("USER_A_ONLY_FACT"))
        assertFalse(memoryBlock.content.contains("USER_B_ONLY_FACT"))
    }

    // --- Block replacement mechanics: exactly one memory block, same position, rest unchanged ---
    @Test
    fun `only the memory block is replaced everything else in context is untouched`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val memoryFactRepository = MemoryFactRepository(db)
        val memoryService = MemoryService(memoryFactRepository)
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        memoryService.record(userId, personaId, listOf(MemoryCandidate("SOME_FACT", "interest", "medium")))

        val skillRepo = SkillRepository(db)
        val enricher = SkillAwareMemoryEnricher(memoryService, DeterministicMemoryContextSelector(skillRepo))

        val original = sampleContext()
        val result = enricher.enrich(original, request(userId, personaId), SkillSelection.None)

        assertEquals(original.blocks.size, result.blocks.size)
        assertEquals("ENGINE_AND_PERSONA", result.blocks[0].content)
        assertEquals("PROFILE", result.blocks[1].content)
        assertEquals("HIST_1", result.blocks[3].content)
        assertEquals("CURRENT_MESSAGE", result.blocks[4].content)
        assertTrue(result.blocks[2].content.contains("SOME_FACT"))
        assertEquals(1, result.blocks.count { it.content.startsWith("RETRIEVED MEMORY:") }, "Exactly one memory block must exist")
    }

    // --- No memory block present (defensive) ---
    @Test
    fun `context without a memory block is returned unchanged`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val memoryService = MemoryService(MemoryFactRepository(db))
        val skillRepo = SkillRepository(db)
        val enricher = SkillAwareMemoryEnricher(memoryService, DeterministicMemoryContextSelector(skillRepo))

        val noMemoryContext = ChatContext(
            blocks = listOf(ContextBlock("system", "ENGINE"), ContextBlock("user", "CURRENT")),
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID(),
        )
        val result = enricher.enrich(noMemoryContext, request(UUID.randomUUID(), UUID.randomUUID()), SkillSelection.None)

        assertEquals(noMemoryContext, result)
    }

    // --- Token budget: dropping lowest-ranked selected memories rather than corrupting a record ---
    @Test
    fun `oversized memory selection is trimmed under budget pressure never partially truncating a fact`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val memoryFactRepository = MemoryFactRepository(db)
        val memoryService = MemoryService(memoryFactRepository)
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        (1..10).forEach { i -> memoryService.record(userId, personaId, listOf(MemoryCandidate("x".repeat(200) + "_$i", "interest", "medium"))) }

        val skillRepo = SkillRepository(db)
        val enricher = SkillAwareMemoryEnricher(memoryService, DeterministicMemoryContextSelector(skillRepo, contextLimit = 10), candidateLimit = 20, tokenBudget = 60)

        val result = enricher.enrich(sampleContext(), request(userId, personaId), SkillSelection.None)
        val memoryBlock = result.blocks.first { it.content.startsWith("RETRIEVED MEMORY:") }

        val includedCount = memoryBlock.content.lines().count { it.startsWith("interest: ") }
        assertTrue(includedCount < 10, "Budget pressure must reduce the selected count")
        // No fact of exactly 200 x's should appear truncated to a partial length.
        assertFalse(memoryBlock.content.contains("x".repeat(199)) && !memoryBlock.content.contains("x".repeat(200)))
    }
}
