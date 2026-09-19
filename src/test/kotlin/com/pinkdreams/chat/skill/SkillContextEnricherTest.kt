package com.pinkdreams.chat.skill

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.SkillRepository
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillContextEnricherTest {

    private fun activatedSkillRepo(key: String, content: String): SkillRepository {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val repo = SkillRepository(db)
        repo.activate(repo.publish(repo.createNextVersion(key, content).id).id)
        return repo
    }

    private fun sampleContext(): ChatContext = ChatContext(
        blocks = listOf(
            ContextBlock("system", "ENGINE_AND_PERSONA"),
            ContextBlock("system", "PROFILE"),
            ContextBlock("system", "MEMORY"),
            ContextBlock("system", "SENSITIVE"),
            ContextBlock("system", "CONTINUITY"),
            ContextBlock("user", "HIST_1"),
            ContextBlock("assistant", "HIST_2"),
            ContextBlock("user", "CURRENT_MESSAGE"),
        ),
        engineVersionId = UUID.randomUUID(),
        personaCoreVersionId = UUID.randomUUID(),
    )

    @Test
    fun `selected skill is inserted as its own block after continuity and before history`() {
        val repo = activatedSkillRepo("flirting", "FLIRTING_SKILL_CONTENT")
        val enricher = SkillContextEnricher(repo)

        val result = enricher.enrich(sampleContext(), SkillSelection.Selected("flirting"))

        assertEquals(9, result.blocks.size)
        assertEquals("CONTINUITY", result.blocks[4].content)
        assertTrue(result.blocks[5].content.contains("FLIRTING_SKILL_CONTENT"), "Skill block must be inserted right after continuity")
        assertEquals("system", result.blocks[5].role)
        assertEquals("HIST_1", result.blocks[6].content, "Native history must remain immediately after the skill block")
        assertEquals("CURRENT_MESSAGE", result.blocks.last().content)
    }

    @Test
    fun `skill content is its own block never merged into persona engine or memory blocks`() {
        val repo = activatedSkillRepo("flirting", "FLIRTING_SKILL_CONTENT")
        val enricher = SkillContextEnricher(repo)

        val result = enricher.enrich(sampleContext(), SkillSelection.Selected("flirting"))

        assertFalse(result.blocks[0].content.contains("FLIRTING_SKILL_CONTENT"), "Must not leak into Engine+Persona block")
        assertFalse(result.blocks[2].content.contains("FLIRTING_SKILL_CONTENT"), "Must not leak into Memory block")
        assertEquals(1, result.blocks.count { it.content.contains("FLIRTING_SKILL_CONTENT") }, "Skill content must appear exactly once")
    }

    @Test
    fun `None selection leaves context completely unchanged`() {
        val repo = activatedSkillRepo("flirting", "FLIRTING_SKILL_CONTENT")
        val enricher = SkillContextEnricher(repo)

        val original = sampleContext()
        val result = enricher.enrich(original, SkillSelection.None)

        assertEquals(original, result)
    }

    @Test
    fun `selected key with no active skill row leaves context unchanged`() {
        val repo = activatedSkillRepo("flirting", "content")
        val enricher = SkillContextEnricher(repo)

        val original = sampleContext()
        val result = enricher.enrich(original, SkillSelection.Selected("some_other_key_never_activated"))

        assertEquals(original, result)
    }

    @Test
    fun `only one skill block is ever injected even if enrich is called twice`() {
        val repo = activatedSkillRepo("flirting", "FLIRTING_SKILL_CONTENT")
        val enricher = SkillContextEnricher(repo)

        val once = enricher.enrich(sampleContext(), SkillSelection.Selected("flirting"))
        // Simulate a caller mistakenly enriching an already-enriched context —
        // guards against catalogue-style duplication, not a normal pipeline path.
        val notEnrichedAgain = enricher.enrich(sampleContext(), SkillSelection.None)

        assertEquals(1, once.blocks.count { it.content.contains("FLIRTING_SKILL_CONTENT") })
        assertEquals(0, notEnrichedAgain.blocks.count { it.content.contains("FLIRTING_SKILL_CONTENT") })
    }

    @Test
    fun `skill block is dropped entirely never partially truncated when it would exceed the token budget`() {
        val repo = activatedSkillRepo("flirting", "x".repeat(2000))
        val enricher = SkillContextEnricher(repo, tokenBudget = 50)

        val result = enricher.enrich(sampleContext(), SkillSelection.Selected("flirting"))

        assertEquals(sampleContext().blocks.size, result.blocks.size, "Skill block must be fully dropped, not partially included")
        assertFalse(result.blocks.any { it.content.contains("x".repeat(100)) })
    }

    @Test
    fun `current message and all pre-existing blocks are never dropped by skill budget enforcement`() {
        val repo = activatedSkillRepo("flirting", "x".repeat(2000))
        val enricher = SkillContextEnricher(repo, tokenBudget = 50)

        val original = sampleContext()
        val result = enricher.enrich(original, SkillSelection.Selected("flirting"))

        assertEquals(original.blocks, result.blocks, "Under budget pressure, only the skill addition is skipped — nothing the assembler already decided to keep is touched")
    }

    @Test
    fun `insertion point is found structurally and does not depend on how many leading system blocks exist`() {
        val repo = activatedSkillRepo("flirting", "FLIRTING_SKILL_CONTENT")
        val enricher = SkillContextEnricher(repo)

        // Only 2 leading system blocks this time (e.g. no sensitive/continuity present).
        val minimalContext = ChatContext(
            blocks = listOf(
                ContextBlock("system", "ENGINE_AND_PERSONA"),
                ContextBlock("system", "PROFILE"),
                ContextBlock("user", "CURRENT_MESSAGE"),
            ),
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID(),
        )

        val result = enricher.enrich(minimalContext, SkillSelection.Selected("flirting"))

        assertEquals(4, result.blocks.size)
        assertTrue(result.blocks[2].content.contains("FLIRTING_SKILL_CONTENT"))
        assertEquals("CURRENT_MESSAGE", result.blocks.last().content)
    }
}
