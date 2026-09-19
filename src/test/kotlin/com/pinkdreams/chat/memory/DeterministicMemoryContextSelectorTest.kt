package com.pinkdreams.chat.memory

import com.pinkdreams.chat.skill.SkillSelection
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeterministicMemoryContextSelectorTest {

    private fun activatedSkillRepo(key: String, content: String): SkillRepository {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val repo = SkillRepository(db)
        repo.activate(repo.publish(repo.createNextVersion(key, content).id).id)
        return repo
    }

    private fun fact(
        text: String,
        factType: String = "interest",
        criticality: String = "medium",
        status: String = "open",
        learnedAt: LocalDateTime = LocalDateTime.now().minusDays(30),
        lastReferencedAt: LocalDateTime? = null,
    ) = MemoryFactRepository.MemoryFact(
        id = UUID.randomUUID(), userId = UUID.randomUUID(), personaId = UUID.randomUUID(),
        fact = text, factType = factType, criticality = criticality, criticalityRank = null,
        tier = "hot", status = status, source = "llm_extracted", learnedAt = learnedAt,
        lastReferencedAt = lastReferencedAt, evictedAt = null,
    )

    // --- A. Skill-aware ranking ---
    @Test
    fun `romantic_conversation skill ranks relationship-relevant memory above unrelated memory`() {
        val repo = activatedSkillRepo(
            "romantic_conversation",
            "PURPOSE: Handle romance and relationship feelings, previous dating experience, romantic preferences.",
        )
        val selector = DeterministicMemoryContextSelector(repo)

        val relationshipMemory = fact("user's previous relationship ended due to trust issues", learnedAt = LocalDateTime.now().minusDays(60))
        val unrelatedMemory = fact("user prefers tea over coffee", learnedAt = LocalDateTime.now().minusDays(60))

        val result = selector.select("can we talk about relationships?", SkillSelection.Selected("romantic_conversation"), listOf(unrelatedMemory, relationshipMemory))

        assertEquals(relationshipMemory.id, result.first().id, "Relationship-relevant memory must rank above the unrelated one for this skill")
    }

    // --- B. General conversation still selects ordinary relevant memories ---
    @Test
    fun `general_chat skill still surfaces memories relevant to the current message`() {
        val repo = activatedSkillRepo("general_chat", "PURPOSE: Ordinary conversation.")
        val selector = DeterministicMemoryContextSelector(repo)

        val relevant = fact("user is planning a trip to Japan", learnedAt = LocalDateTime.now().minusDays(10))
        val irrelevant = fact("user likes spicy food", learnedAt = LocalDateTime.now().minusDays(10))

        val result = selector.select("tell me more about my Japan trip plans", SkillSelection.Selected("general_chat"), listOf(irrelevant, relevant))

        assertEquals(relevant.id, result.first().id)
    }

    // --- Criticality contributes to ranking ---
    @Test
    fun `higher criticality ranks above lower criticality when other signals are equal`() {
        val repo = activatedSkillRepo("general_chat", "content")
        val selector = DeterministicMemoryContextSelector(repo)

        val high = fact("some fact", criticality = "high", learnedAt = LocalDateTime.now().minusDays(90))
        val low = fact("some fact", criticality = "low", learnedAt = LocalDateTime.now().minusDays(90))

        val result = selector.select("hello", SkillSelection.None, listOf(low, high))
        assertEquals(high.id, result.first().id)
    }

    // --- Recency contributes to ranking ---
    @Test
    fun `more recently referenced memory ranks above an older one when other signals are equal`() {
        val repo = activatedSkillRepo("general_chat", "content")
        val selector = DeterministicMemoryContextSelector(repo)

        val recent = fact("some fact", lastReferencedAt = LocalDateTime.now().minusHours(2))
        val old = fact("some fact", lastReferencedAt = LocalDateTime.now().minusDays(60))

        val result = selector.select("hello", SkillSelection.None, listOf(old, recent))
        assertEquals(recent.id, result.first().id)
    }

    // --- Openness (status) contributes to ranking ---
    @Test
    fun `open status memory ranks above resolved status when other signals are equal`() {
        val repo = activatedSkillRepo("general_chat", "content")
        val selector = DeterministicMemoryContextSelector(repo)

        val open = fact("some fact", status = "open")
        val resolved = fact("some fact", status = "resolved")

        val result = selector.select("hello", SkillSelection.None, listOf(resolved, open))
        assertEquals(open.id, result.first().id)
    }

    // --- D. No skill selected still ranks and returns candidates ---
    @Test
    fun `no skill selected still produces a deterministic ranked result`() {
        val repo = activatedSkillRepo("general_chat", "content")
        val selector = DeterministicMemoryContextSelector(repo)

        val a = fact("user lives in Delhi")
        val b = fact("user likes cricket")

        val result = selector.select("hello", SkillSelection.None, listOf(a, b))
        assertEquals(2, result.size)
    }

    // --- G. Candidate vs context limit ---
    @Test
    fun `only contextLimit memories are returned even with more candidates`() {
        val repo = activatedSkillRepo("general_chat", "content")
        val selector = DeterministicMemoryContextSelector(repo, contextLimit = 5)

        val candidates = (1..20).map { fact("fact number $it") }
        val result = selector.select("hello", SkillSelection.None, candidates)

        assertEquals(5, result.size)
        assertTrue(candidates.size > result.size)
    }

    // --- Determinism: identical inputs always produce identical output order ---
    @Test
    fun `selection is deterministic for identical inputs`() {
        val repo = activatedSkillRepo("flirting", "content")
        val selector = DeterministicMemoryContextSelector(repo)
        val candidates = (1..10).map { fact("fact $it", criticality = if (it % 2 == 0) "high" else "low") }

        val first = selector.select("hey there", SkillSelection.Selected("flirting"), candidates)
        val second = selector.select("hey there", SkillSelection.Selected("flirting"), candidates)

        assertEquals(first.map { it.id }, second.map { it.id })
    }

    // --- Skill relevance is derived generically from skill content, no hardcoded per-skill logic ---
    @Test
    fun `skill relevance is derived from the active skill's own content not a hardcoded key mapping`() {
        val repo = activatedSkillRepo("some_future_skill_never_seen_before", "PURPOSE: talk about cooking and recipes.")
        val selector = DeterministicMemoryContextSelector(repo)

        val cookingMemory = fact("user loves cooking pasta recipes")
        val unrelated = fact("user works as an accountant")

        val result = selector.select("what should i cook tonight?", SkillSelection.Selected("some_future_skill_never_seen_before"), listOf(unrelated, cookingMemory))

        assertEquals(cookingMemory.id, result.first().id, "A brand-new skill key must still influence ranking via its own content, with zero code changes")
    }

    @Test
    fun `unknown or inactive selected skill key degrades to zero skill relevance rather than throwing`() {
        val repo = activatedSkillRepo("flirting", "content")
        val selector = DeterministicMemoryContextSelector(repo)

        val a = fact("fact a")
        val result = selector.select("hello", SkillSelection.Selected("a_key_that_was_never_activated"), listOf(a))

        assertEquals(1, result.size)
    }
}
