package com.pinkdreams.baseline

import com.pinkdreams.chat.skill.IntentEngineDefaultContent
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.IntentEngineRepository
import com.pinkdreams.persistence.repositories.MemoryEngineRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillRepository

/**
 * Seeds the canonical baseline AI configuration.
 *
 * Idempotency is CONTENT-ADDRESSED, not merely existence-based: a component is
 * seeded only when no version of it already carries the canonical content.
 * That gives three properties at once —
 *
 *  1. Re-running startup creates nothing new (the canonical version is found).
 *  2. A manually edited ACTIVE version is never overwritten or mutated; a new
 *     DRAFT is created beside it for an admin to review and activate.
 *  3. Updating the canonical text here produces exactly one new draft, once.
 *
 * Bootstrap vs. respect-existing: when there is NO active version of a
 * component at all, the seeded version is published and activated so a fresh
 * database yields a working system. When an active version already exists, the
 * seed stops at draft — activation stays an explicit admin decision.
 *
 * Skills are the deliberate exception: they are always seeded as draft and
 * inactive, and are never auto-activated, because which interaction modes are
 * permitted is a product decision rather than a bootstrap concern.
 */
class BaselineSeeder(
    private val conversationEngineRepository: ConversationEngineRepository,
    private val personaRepository: PersonaRepository,
    private val personaCoreVersionRepository: PersonaCoreVersionRepository,
    private val skillRepository: SkillRepository,
    private val memoryEngineRepository: MemoryEngineRepository,
    // Phase ADMIN-2. Optional so existing constructions (tests written before
    // the Intent Engine existed) keep compiling and behaving identically.
    private val intentEngineRepository: IntentEngineRepository? = null,
) {
    data class SeedReport(
        val conversationEngine: String,
        val persona: String,
        val personaCore: String,
        val skillsCreated: List<String>,
        val skillsAlreadyPresent: List<String>,
        val memoryEngine: String,
        val intentEngine: String = "skipped (no repository)",
    )

    fun seedIfMissing(): SeedReport {
        val engineOutcome = seedConversationEngine()
        val personaOutcome = seedPersona()
        val personaCoreOutcome = seedPersonaCore()
        // Evaluated exactly once — calling seedSkills() per field would seed twice.
        val (skillsCreated, skillsAlreadyPresent) = seedSkills()
        val memoryEngineOutcome = seedMemoryEngine()
        val intentEngineOutcome = seedIntentEngine()
        return SeedReport(
            conversationEngine = engineOutcome,
            persona = personaOutcome,
            personaCore = personaCoreOutcome,
            skillsCreated = skillsCreated,
            skillsAlreadyPresent = skillsAlreadyPresent,
            memoryEngine = memoryEngineOutcome,
            intentEngine = intentEngineOutcome,
        )
    }

    /**
     * Same bootstrap-vs-respect-existing rule as the other engines.
     *
     * Worth being explicit about why this is safe for EXISTING deployments:
     * intent_engines is a brand-new table, so every database — fresh or
     * long-running — starts with zero rows and therefore no active version.
     * The seed is consequently published and activated on first startup
     * everywhere, which keeps skill selection working exactly as it did when
     * the prompt lived in Kotlin. Nothing is overwritten, because there is
     * nothing yet to overwrite. Once a version exists, this only ever adds a
     * draft.
     */
    private fun seedIntentEngine(): String {
        val repository = intentEngineRepository ?: return "skipped (no repository)"
        val canonical = IntentEngineDefaultContent.CONTENT
        if (repository.findAll().any { it.content == canonical }) return "already present"

        val draft = repository.createNextVersion(
            content = canonical,
            changelogNote = "Canonical baseline Intent Engine",
            createdBy = SEED_AUTHOR,
        )
        if (repository.getActiveEngine() != null) return "draft v${draft.version} created (existing active version untouched)"

        val published = repository.publish(draft.id)
        repository.activate(published.id)
        return "v${draft.version} created and activated (no active version existed)"
    }

    private fun seedConversationEngine(): String {
        val canonical = BaselineConfiguration.CONVERSATION_ENGINE
        val existing = conversationEngineRepository.findAll()
        if (existing.any { it.content == canonical }) return "already present"

        val draft = conversationEngineRepository.createNextVersion(
            content = canonical,
            changelogNote = "Canonical baseline Conversation Engine",
            createdBy = SEED_AUTHOR,
        )
        if (conversationEngineRepository.getActiveEngine() != null) return "draft v${draft.version} created (existing active version untouched)"

        val published = conversationEngineRepository.publishEngine(draft.id)
        conversationEngineRepository.activateEngine(published.id)
        return "v${draft.version} created and activated (no active version existed)"
    }

    private fun seedPersona(): String {
        val existing = personaRepository.findAll().firstOrNull { it.slug == BaselineConfiguration.SIMRAN_SLUG }
        if (existing != null) return "already present"

        personaRepository.create(
            slug = BaselineConfiguration.SIMRAN_SLUG,
            displayName = BaselineConfiguration.SIMRAN_DISPLAY_NAME,
            gender = "female",
            orientation = "straight",
            apparentAge = 26,
            languageProfile = emptyMap(),
        )
        return "created"
    }

    private fun seedPersonaCore(): String {
        val persona = personaRepository.findAll().firstOrNull { it.slug == BaselineConfiguration.SIMRAN_SLUG }
            ?: return "skipped (persona missing)"
        val canonical = BaselineConfiguration.SIMRAN_PERSONA_CORE
        val existing = personaCoreVersionRepository.findForPersona(persona.id)
        if (existing.any { it.content == canonical }) return "already present"

        val draft = personaCoreVersionRepository.createNextVersion(
            personaId = persona.id,
            content = canonical,
            changelogNote = "Canonical baseline Simran Persona Core",
            author = SEED_AUTHOR,
        )
        if (persona.activeCoreVersionId != null) return "draft v${draft.version} created (existing active version untouched)"

        val published = personaCoreVersionRepository.publishCoreVersion(draft.id)
        personaRepository.activateCoreVersion(persona.id, published.id)
        return "v${draft.version} created and activated (no active version existed)"
    }

    /** Returns (createdKeys, alreadyPresentKeys). Always draft + inactive. */
    private fun seedSkills(): Pair<List<String>, List<String>> {
        val created = mutableListOf<String>()
        val alreadyPresent = mutableListOf<String>()
        BaselineConfiguration.SKILLS.forEach { seed ->
            val versions = skillRepository.findByKey(seed.key)
            if (versions.any { it.content == seed.content }) {
                alreadyPresent += seed.key
            } else {
                skillRepository.createNextVersion(
                    key = seed.key,
                    content = seed.content,
                    changelogNote = "Canonical baseline skill",
                    author = SEED_AUTHOR,
                )
                created += seed.key
            }
        }
        return created to alreadyPresent
    }

    private fun seedMemoryEngine(): String {
        val canonical = BaselineConfiguration.MEMORY_ENGINE
        val existing = memoryEngineRepository.findAll()
        if (existing.any { it.content == canonical }) return "already present"

        val draft = memoryEngineRepository.createNextVersion(
            content = canonical,
            changelogNote = "Canonical baseline Memory Engine",
            createdBy = SEED_AUTHOR,
            batchSize = BaselineConfiguration.MEMORY_BATCH_SIZE,
            relevantMemoryTarget = BaselineConfiguration.RELEVANT_MEMORY_TARGET,
        )
        if (memoryEngineRepository.getActiveEngine() != null) return "draft v${draft.version} created (existing active version untouched)"

        val published = memoryEngineRepository.publish(draft.id)
        memoryEngineRepository.activate(published.id)
        return "v${draft.version} created and activated (no active version existed)"
    }

    companion object {
        const val SEED_AUTHOR = "baseline-seed"
    }
}
