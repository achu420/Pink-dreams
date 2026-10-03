package com.pinkdreams.chat

import com.pinkdreams.baseline.BaselineConfiguration
import com.pinkdreams.baseline.BaselineSeeder
import com.pinkdreams.baseline.PersonaContextPocSeed
import com.pinkdreams.chat.context.ResolvedPersonaContextProvider
import com.pinkdreams.chat.personacontext.production.BareScalarRendering
import com.pinkdreams.chat.personacontext.production.PersonaFieldAuthorizer
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.MemoryEngineRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaFieldRegistryRepository
import com.pinkdreams.persistence.repositories.PersonaMasterRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillContextRequirementRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Test

/**
 * PHASE 53 §7 -- zero-cost, character-level comparison of the OLD research
 * variant-B text (ResolvedPersonaContextProvider -> PersonaContextResolver
 * -> PersonaContextRenderer) against the NEW production B text
 * (PersonaFieldAuthorizer -> ProductionPersonaContextRenderer), against the
 * IDENTICAL seed data and IDENTICAL applied fixes, for both Phase 52 scenarios.
 */
class Phase53PayloadDiffTest {

    private fun setup(applyPuneFix: Boolean): Triple<Database, java.util.UUID, java.util.UUID> {
        val db: Database = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }
        val engines = ConversationEngineRepository(db)
        val personas = PersonaRepository(db)
        val cores = PersonaCoreVersionRepository(db)
        val skills = SkillRepository(db)
        val fieldRegistryRepo = PersonaFieldRegistryRepository(db)
        val skillReqRepo = SkillContextRequirementRepository(db)
        val personaMasterRepo = PersonaMasterRepository(db)

        BaselineSeeder(engines, personas, cores, skills, MemoryEngineRepository(db)).seedIfMissing()
        skills.findAll().forEach { skills.activate(skills.publish(it.id).id) }
        val personaId = personas.findAll().first { it.slug == BaselineConfiguration.SIMRAN_SLUG }.id
        val personaCoreVersionId = requireNotNull(personas.findById(personaId)?.activeCoreVersionId)

        if (skills.findByKey("about").isEmpty()) {
            val v = skills.createNextVersion("about", "SKILL: ABOUT")
            skills.activate(skills.publish(v.id).id)
        }

        val seed = PersonaContextPocSeed(db)
        seed.seedPersonaFieldRegistry()
        seed.seedSkillContextRequirements()
        seed.seedSimranMasterDocument(personaCoreVersionId)

        if (applyPuneFix) {
            val about = skillReqRepo.findBySkillKey("about")
            if (about != null) skillReqRepo.update(about.copy(conditionalFields = (about.conditionalFields ?: emptyList()) + "life_context.friends"))
            val gc0 = skillReqRepo.findBySkillKey("general_chat")!!
            skillReqRepo.update(
                gc0.copy(
                    requiredFields = (gc0.requiredFields ?: emptyList()) + listOf("identity.hometown", "identity.current_city"),
                    conditionalFields = (gc0.conditionalFields ?: emptyList()) + listOf("life_context.family", "life_context.friends"),
                ),
            )
            val familyEntry = fieldRegistryRepo.findByFieldId("life_context.family")!!
            fieldRegistryRepo.update(familyEntry.copy(description = "family father mother mom dad parents siblings relatives Anjali Arjun Rajesh Priya Kohli home visit call"))
            val friendsEntry = fieldRegistryRepo.findByFieldId("life_context.friends")!!
            fieldRegistryRepo.update(friendsEntry.copy(description = "friends flatmate roommate live living lives alone together share flat situation Ritu Prashant Neha"))
            val ileEntry = fieldRegistryRepo.findByFieldId("life_context.important_life_events")!!
            fieldRegistryRepo.update(ileEntry.copy(description = "life_context.important_life_events years long since moved arrived came time"))
            val gc = skillReqRepo.findBySkillKey("general_chat")!!
            skillReqRepo.update(gc.copy(conditionalFields = (gc.conditionalFields ?: emptyList()) + listOf("identity.age", "life_context.important_life_events")))
            val ageEntry = fieldRegistryRepo.findByFieldId("identity.age")!!
            fieldRegistryRepo.update(ageEntry.copy(description = "identity.age years long since moved arrived came time"))
        }

        return Triple(db, personaId, personaCoreVersionId)
    }

    private fun compare(scenarioName: String, skill: String, msg: String, applyPuneFix: Boolean) {
        val (db, personaId, personaCoreVersionId) = setup(applyPuneFix)
        val fieldRegistryRepo = PersonaFieldRegistryRepository(db)
        val skillReqRepo = SkillContextRequirementRepository(db)
        val personaMasterRepo = PersonaMasterRepository(db)

        val oldProvider = ResolvedPersonaContextProvider(db, personaMasterRepo, fieldRegistryRepo, skillReqRepo, "B")
        val oldText = oldProvider.getPersonaContext(personaId, personaCoreVersionId, skill, msg)

        val newAuthorizer = PersonaFieldAuthorizer(db, personaMasterRepo, fieldRegistryRepo, skillReqRepo)
        val newResult = newAuthorizer.resolve(personaId, personaCoreVersionId, skill, msg, BareScalarRendering)
        val newText = newResult.renderedText

        println("\n════ $scenarioName -- OLD (research) variant B ════")
        println(oldText)
        println("\n════ $scenarioName -- NEW (production) B ════")
        println(newText)
        println("\n════ $scenarioName -- IDENTICAL? ${oldText == newText} ════")
        if (oldText != newText) {
            val oldLines = oldText.lines()
            val newLines = newText.lines()
            println("Old line count: ${oldLines.size}, New line count: ${newLines.size}")
            val maxLines = maxOf(oldLines.size, newLines.size)
            for (i in 0 until maxLines) {
                val o = oldLines.getOrNull(i)
                val n = newLines.getOrNull(i)
                if (o != n) println("  DIFF line $i: OLD=[$o] NEW=[$n]")
            }
        }
    }

    @Test
    fun `Pune timeline - old vs new variant B exact text`() {
        compare("PUNE", "general_chat", "Been in this city long?", applyPuneFix = true)
    }

    @Test
    fun `Sister age-gap - old vs new variant B exact text`() {
        compare("SISTER", "about", "Is there a big age gap between you and your sister in your family?", applyPuneFix = false)
    }
}
