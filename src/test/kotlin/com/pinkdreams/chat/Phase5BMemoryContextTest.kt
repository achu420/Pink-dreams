package com.pinkdreams.chat

import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.chat.memory.MemoryCandidate
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Phase5BMemoryContextTest {
    @Test
    fun `memory validates normalizes deduplicates and isolates relationships`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val repository = MemoryFactRepository(db)
        val service = MemoryService(repository)
        val user = UUID.randomUUID()
        val personaA = UUID.randomUUID()
        val personaB = UUID.randomUUID()

        val accepted = service.record(user, personaA, listOf(MemoryCandidate("  Likes   tea. ", "MINDSET", "HIGH")))
        assertEquals(1, accepted.size)
        assertEquals("Likes tea.", accepted.single().fact)
        assertEquals(0, service.record(user, personaA, listOf(MemoryCandidate("likes tea", "mindset", "high"))).size)
        service.record(user, personaB, listOf(MemoryCandidate("likes tea", "mindset", "high")))
        assertEquals(1, service.selectForContext(user, personaB).size)
        assertFailsWith<IllegalArgumentException> { service.record(user, personaA, listOf(MemoryCandidate("x", "invalid", "high"))) }
        assertFailsWith<IllegalArgumentException> { service.record(user, personaA, listOf(MemoryCandidate("", "mindset", "high"))) }
        assertFailsWith<IllegalArgumentException> { service.record(user, personaA, listOf(MemoryCandidate("x", "mindset", "invalid"))) }
        assertFailsWith<IllegalArgumentException> { service.record(user, personaA, listOf(MemoryCandidate("x", "mindset", "high", source = "invalid"))) }
        assertEquals(1, service.record(user, personaA, listOf(MemoryCandidate("x".repeat(240), "mindset", "high"))).size)
        assertFailsWith<IllegalArgumentException> { service.record(user, personaA, listOf(MemoryCandidate("x".repeat(241), "mindset", "high"))) }
    }

    @Test
    fun `selection orders open before resolved and criticality by generated rank semantics`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val repository = MemoryFactRepository(db)
        val service = MemoryService(repository)
        val user = UUID.randomUUID()
        val persona = UUID.randomUUID()
        val learnedAt = LocalDateTime.of(2026, 1, 1, 0, 0)

        repository.create(user, persona, "resolved-high", "habit", "high", status = "resolved", learnedAt = learnedAt)
        repository.create(user, persona, "open-low", "habit", "low", learnedAt = learnedAt)
        repository.create(user, persona, "open-medium", "habit", "medium", learnedAt = learnedAt)
        repository.create(user, persona, "open-high", "habit", "high", learnedAt = learnedAt)

        val first = service.selectForContext(user, persona)
        val second = service.selectForContext(user, persona)
        assertEquals(listOf("open-high", "open-medium", "open-low", "resolved-high"), first.map { it.fact })
        assertEquals(first.map { it.id }, second.map { it.id })
    }

    @Test
    fun `hot eviction prefers lower criticality among equally open facts`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val repository = MemoryFactRepository(db)
        val service = MemoryService(repository)
        val user = UUID.randomUUID()
        val persona = UUID.randomUUID()
        val learnedAt = LocalDateTime.of(2026, 1, 1, 0, 0)

        repository.create(user, persona, "high", "habit", "high", learnedAt = learnedAt)
        repository.create(user, persona, "medium", "habit", "medium", learnedAt = learnedAt)
        repeat(18) { repository.create(user, persona, "low-$it", "habit", "low", learnedAt = learnedAt) }

        service.record(user, persona, listOf(MemoryCandidate("new-high", "habit", "high")))

        val facts = repository.findForRelationship(user, persona)
        assertEquals(1, facts.count { it.fact.startsWith("low-") && it.tier == "cold" })
        assertEquals("hot", facts.single { it.fact == "high" }.tier)
        assertEquals("hot", facts.single { it.fact == "medium" }.tier)
        assertEquals(20, facts.count { it.tier == "hot" })
    }

    @Test
    fun `hot capacity evicts resolved low and old facts while preserving cold history`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val repository = MemoryFactRepository(db)
        val service = MemoryService(repository)
        val user = UUID.randomUUID()
        val persona = UUID.randomUUID()
        val base = LocalDateTime.of(2026, 1, 1, 0, 0)
        repeat(20) { index ->
            val fact = repository.create(user, persona, "fact-$index", "habit", "low", learnedAt = base.plusDays(index.toLong()))
            if (index == 0) repository.markReferenced(user, persona, fact.id, base.plusDays(100))
        }
        val resolved = repository.create(user, persona, "resolved", "habit", "high", status = "resolved", learnedAt = base.plusDays(50))
        service.record(user, persona, listOf(MemoryCandidate("new", "habit", "high", source = "manual")))

        val storedResolved = repository.findById(resolved.id)
        assertNotNull(storedResolved)
        assertEquals("cold", storedResolved.tier)
        assertNotNull(storedResolved.evictedAt)
        assertEquals(20, repository.findForRelationship(user, persona).count { it.tier == "hot" })
        assertEquals(22, repository.findForRelationship(user, persona).size)
    }

    /**
     * Task 23 regression. Superseded/removed rows keep tier="hot" (supersede()
     * and remove() never touch the tier — history is preserved in place) but
     * are permanently unselectable. Counting them against MAX_HOT_FACTS used to
     * evict LIVE, selectable memory to cold to make room for dead rows.
     * Observed live: 17 live + 3 removed rows exactly filling the 20-slot hot
     * budget on the largest real user/persona relationship.
     */
    @Test
    fun `hot capacity ignores superseded and removed rows so live memory is not evicted for dead rows`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val repository = MemoryFactRepository(db)
        val service = MemoryService(repository)
        val user = UUID.randomUUID()
        val persona = UUID.randomUUID()
        val base = LocalDateTime.of(2026, 1, 1, 0, 0)

        // 17 live hot facts + 3 dead-but-still-hot rows == the old 20-slot budget.
        repeat(17) { index -> repository.create(user, persona, "live-$index", "habit", "high", learnedAt = base.plusDays(index.toLong())) }
        val removed = repository.create(user, persona, "dead-removed", "habit", "low", learnedAt = base)
        repository.remove(removed.id)
        val supersededA = repository.create(user, persona, "dead-superseded-a", "habit", "low", learnedAt = base)
        repository.supersede(supersededA.id, "replacement-a")
        val supersededB = repository.create(user, persona, "dead-superseded-b", "habit", "low", learnedAt = base)
        repository.supersede(supersededB.id, "replacement-b")

        service.record(user, persona, listOf(MemoryCandidate("brand-new", "habit", "high")))

        val facts = repository.findForRelationship(user, persona)
        // Nothing live was evicted: every live-* fact, both replacements and the
        // new fact are still hot (17 + 2 + 1 == 20 live hot rows).
        assertTrue(facts.filter { it.fact.startsWith("live-") }.all { it.tier == "hot" })
        assertEquals("hot", facts.single { it.fact == "brand-new" }.tier)
        assertEquals(20, facts.count { it.tier == "hot" && it.status !in setOf("superseded", "removed") })
        assertEquals(0, facts.count { it.tier == "cold" })
        // And the dead rows are still hot-tier history — this fix changes only
        // what is COUNTED, never what is stored.
        assertEquals("removed", facts.single { it.fact == "dead-removed" }.status)
        assertEquals(3, facts.count { it.status in setOf("superseded", "removed") })
    }

    @Test
    fun `selection is hot only limited open first high first and injection does not reference`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val repository = MemoryFactRepository(db)
        val service = MemoryService(repository)
        val user = UUID.randomUUID()
        val persona = UUID.randomUUID()
        repeat(11) { index -> repository.create(user, persona, "memory-$index", "habit", if (index == 0) "high" else "low", learnedAt = LocalDateTime.of(2026, 1, 1, index, 0)) }
        val selected = service.selectForContext(user, persona)
        assertEquals(10, selected.size)
        assertEquals("high", selected.first().criticality)
        assertTrue(selected.all { it.tier == "hot" })
        assertTrue(selected.all { it.lastReferencedAt == null })
        service.markReferenced(user, persona, listOf(selected.first().id), LocalDateTime.of(2026, 2, 1, 0, 0))
        assertNotNull(repository.findById(selected.first().id)?.lastReferencedAt)
    }

    @Test
    fun `context assembles native history blocks and shrinks memory before messages under a tight budget`() {
        val fixture = fixture(tokenBudget = 40)
        val context = assertIs<StageResult.Succeeded<ChatContext>>(fixture.assembler.assemble(fixture.request)).value

        // The three system sections always come first.
        assertEquals("system", context.blocks[0].role)
        assertTrue(context.blocks[0].content.contains("engine"))
        assertTrue(context.blocks[0].content.contains("core"))
        assertEquals("system", context.blocks[1].role)
        assertTrue(context.blocks[1].content.contains("Asha"))
        assertEquals("system", context.blocks[2].role)
        assertTrue(context.blocks[2].content.length <= 40 * 4, "Memory block must respect the token budget")

        // The final block is always the current message, never folded into history.
        val currentBlock = context.blocks.last()
        assertEquals("user", currentBlock.role)
        assertEquals(fixture.request.content, currentBlock.content)

        // Everything between the memory block and the current message must be NATIVE
        // per-message history blocks — never a single flattened transcript block.
        val historyBlocks = context.blocks.subList(3, context.blocks.size - 1)
        assertTrue(historyBlocks.size < 20, "Aggressive token budget must shrink history below the full 20 persisted messages")
        historyBlocks.forEach { block ->
            assertEquals("user", block.role, "All persisted messages in this fixture are user messages")
            assertTrue(block.content.startsWith("message-"), "History content must be exact persisted text, not a flattened transcript")
        }

        assertEquals(fixture.engineId, context.engineVersionId)
        assertEquals(fixture.coreId, context.personaCoreVersionId)
    }

    private data class Fixture(
        val assembler: RepositoryContextAssembler,
        val request: ChatRequest,
        val engineId: UUID,
        val coreId: UUID,
    )

    private fun fixture(tokenBudget: Int): Fixture {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val user = UUID.randomUUID()
        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("fixture-${UUID.randomUUID()}", "Fixture", "female", "straight", 30, emptyMap())
        val coreRepository = PersonaCoreVersionRepository(db)
        val core = coreRepository.create(persona.id, 1, "core", "draft")
        val publishedCore = coreRepository.publishCoreVersion(core.id)
        personaRepository.activateCoreVersion(persona.id, publishedCore.id)
        val engineRepository = ConversationEngineRepository(db)
        val engine = engineRepository.create(1, "engine", "draft")
        val publishedEngine = engineRepository.publishEngine(engine.id)
        engineRepository.activateEngine(publishedEngine.id)
        val conversation = ConversationRepository(db).create(user, persona.id)
        UserProfileRepository(db).create(user, "Asha", "en", "warm")
        val memoryService = MemoryService(MemoryFactRepository(db))
        repeat(10) { memoryService.record(user, persona.id, listOf(MemoryCandidate("memory-$it", "habit", "low"))) }
        val messages = MessageRepository(db)
        repeat(20) { messages.createUserMessage(conversation.id, "message-$it", null, null, createdAt = LocalDateTime.of(2026, 1, 1, 0, it)) }
        val request = ChatRequest(UUID.randomUUID(), user, conversation.id, persona.id, UUID.randomUUID(), "hello")
        return Fixture(
            RepositoryContextAssembler(ConversationRepository(db), messages, UserProfileRepository(db), memoryService, engineRepository, personaRepository, tokenBudget = tokenBudget),
            request,
            publishedEngine.id,
            publishedCore.id,
        )
    }
}