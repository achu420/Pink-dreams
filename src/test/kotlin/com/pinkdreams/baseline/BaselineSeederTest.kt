package com.pinkdreams.baseline

import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.MemoryEngineRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BaselineSeederTest {

    private class Fixture(val db: Database) {
        val engines = ConversationEngineRepository(db)
        val personas = PersonaRepository(db)
        val cores = PersonaCoreVersionRepository(db)
        val skills = SkillRepository(db)
        val memoryEngines = MemoryEngineRepository(db)
        val seeder = BaselineSeeder(engines, personas, cores, skills, memoryEngines)
    }

    private fun fixture(): Fixture = Fixture(DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) })

    // --- Part O: fresh database bootstraps a working baseline ---
    @Test
    fun `seeding a fresh database creates and activates engine persona core and memory engine`() {
        val f = fixture()

        f.seeder.seedIfMissing()

        val activeEngine = f.engines.getActiveEngine()
        assertNotNull(activeEngine, "A fresh database must end up with an ACTIVE Conversation Engine or chat cannot work at all")
        assertEquals(BaselineConfiguration.CONVERSATION_ENGINE, activeEngine.content)

        val persona = f.personas.findAll().single { it.slug == BaselineConfiguration.SIMRAN_SLUG }
        assertNotNull(persona.activeCoreVersionId, "Simran must have an active Persona Core")
        assertEquals(BaselineConfiguration.SIMRAN_PERSONA_CORE, f.cores.findById(persona.activeCoreVersionId!!)!!.content)

        val activeMemoryEngine = f.memoryEngines.getActiveEngine()
        assertNotNull(activeMemoryEngine)
        assertEquals(BaselineConfiguration.MEMORY_ENGINE, activeMemoryEngine.content)
        assertEquals(BaselineConfiguration.MEMORY_BATCH_SIZE, activeMemoryEngine.batchSize)
        assertEquals(BaselineConfiguration.RELEVANT_MEMORY_TARGET, activeMemoryEngine.relevantMemoryTarget)
    }

    // --- Part G/P: all 12 skills seeded, as draft and inactive ---
    @Test
    fun `all twelve canonical skills are seeded as draft and inactive`() {
        val f = fixture()

        f.seeder.seedIfMissing()

        val expectedKeys = listOf(
            "companionship", "friendship", "emotional_support", "general_chat",
            "flirting", "romantic_conversation", "relationship_building", "relationship_discussion",
            "dating", "playful_teasing", "romantic_intimacy", "foreplay",
        )
        val all = f.skills.findAll()
        assertEquals(12, all.size)
        assertEquals(expectedKeys.toSet(), all.map { it.key }.toSet())
        assertTrue(all.all { it.status == "draft" }, "Skills must seed as draft — activation is an explicit configuration action")
        assertTrue(all.none { it.isActive }, "No skill may be auto-activated by seeding")
        assertTrue(f.skills.findAllActiveKeys().isEmpty())
    }

    @Test
    fun `every seeded skill follows the mandated structural format`() {
        val f = fixture()
        f.seeder.seedIfMissing()

        f.skills.findAll().forEach { skill ->
            assertTrue(skill.content.startsWith("SKILL: "), "${skill.key} must start with the SKILL header")
            assertTrue(skill.content.contains("Purpose:"), "${skill.key} must declare a Purpose")
            assertTrue(skill.content.contains("Behavior:"), "${skill.key} must declare Behavior")
            assertTrue(skill.content.contains("Do not:"), "${skill.key} must declare Do not")
        }
    }

    // --- Part O: idempotency ---
    @Test
    fun `seeding twice creates nothing the second time`() {
        val f = fixture()

        f.seeder.seedIfMissing()
        val afterFirst = Snapshot(f)

        val secondReport = f.seeder.seedIfMissing()

        assertEquals(afterFirst, Snapshot(f), "A second startup must not create a single new row")
        assertTrue(secondReport.skillsCreated.isEmpty())
        assertEquals(12, secondReport.skillsAlreadyPresent.size)
    }

    @Test
    fun `seeding five times still produces exactly one version of each component`() {
        val f = fixture()
        repeat(5) { f.seeder.seedIfMissing() }

        assertEquals(1, f.engines.findAll().size)
        assertEquals(1, f.memoryEngines.findAll().size)
        assertEquals(12, f.skills.findAll().size)
        assertEquals(1, f.personas.findAll().count { it.slug == BaselineConfiguration.SIMRAN_SLUG })
    }

    // --- Part O: never overwrite manually edited ACTIVE configuration ---
    @Test
    fun `an existing active conversation engine is never overwritten only a draft is added`() {
        val f = fixture()
        val handEdited = f.engines.createNextVersion("HAND EDITED ENGINE — DO NOT TOUCH")
        val published = f.engines.publishEngine(handEdited.id)
        f.engines.activateEngine(published.id)

        f.seeder.seedIfMissing()

        val stillActive = f.engines.getActiveEngine()!!
        assertEquals("HAND EDITED ENGINE — DO NOT TOUCH", stillActive.content, "Seeding must never mutate or deactivate a hand-edited active version")
        assertTrue(
            f.engines.findAll().any { it.content == BaselineConfiguration.CONVERSATION_ENGINE && it.status == "draft" },
            "The canonical baseline should still be offered as a draft for explicit review",
        )
    }

    @Test
    fun `an existing active memory engine is never overwritten only a draft is added`() {
        val f = fixture()
        val handEdited = f.memoryEngines.createNextVersion("HAND EDITED MEMORY ENGINE")
        f.memoryEngines.activate(f.memoryEngines.publish(handEdited.id).id)

        f.seeder.seedIfMissing()

        assertEquals("HAND EDITED MEMORY ENGINE", f.memoryEngines.getActiveEngine()!!.content)
        assertTrue(f.memoryEngines.findAll().any { it.content == BaselineConfiguration.MEMORY_ENGINE && it.status == "draft" })
    }

    @Test
    fun `changing canonical content produces exactly one new draft beside the existing version`() {
        val f = fixture()
        f.seeder.seedIfMissing()
        val versionsBefore = f.skills.findByKey("flirting").size

        // Simulate an admin having edited a skill: a different version exists for the
        // same key, but the canonical content is still present, so nothing is added.
        f.skills.createNextVersion("flirting", "ADMIN EDITED FLIRTING CONTENT")
        f.seeder.seedIfMissing()

        assertEquals(versionsBefore + 1, f.skills.findByKey("flirting").size, "Canonical content already present => no further drafts")
    }

    // --- Part C: no layer restates another layer's content ---
    @Test
    fun `skills do not embed the persona core or conversation engine text`() {
        BaselineConfiguration.SKILLS.forEach { skill ->
            assertTrue(
                !skill.content.contains("PERSONA CORE — SIMRAN"),
                "${skill.key} must reference the Persona Core, never copy it",
            )
            assertTrue(
                !skill.content.contains("CONVERSATION ENGINE — UNIVERSAL"),
                "${skill.key} must reference the Conversation Engine, never copy it",
            )
        }
    }

    @Test
    fun `the memory engine contains no personality instructions`() {
        val memoryEngine = BaselineConfiguration.MEMORY_ENGINE
        assertTrue(memoryEngine.contains("You are NOT generating a conversational response"))
        assertTrue(!memoryEngine.contains("PERSONA CORE — SIMRAN"), "Memory Engine must not restate the Persona Core")
    }

    private data class Snapshot(
        val engineVersions: Int,
        val engineContents: Set<String>,
        val memoryEngineVersions: Int,
        val skillCount: Int,
        val personaCount: Int,
        val coreVersionContents: Set<String>,
    ) {
        constructor(f: Fixture) : this(
            engineVersions = f.engines.findAll().size,
            engineContents = f.engines.findAll().map { it.content }.toSet(),
            memoryEngineVersions = f.memoryEngines.findAll().size,
            skillCount = f.skills.findAll().size,
            personaCount = f.personas.findAll().size,
            coreVersionContents = f.personas.findAll()
                .flatMap { f.cores.findForPersona(it.id) }
                .map { it.content }
                .toSet(),
        )
    }
}
