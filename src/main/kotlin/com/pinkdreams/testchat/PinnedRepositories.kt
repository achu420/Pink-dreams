package com.pinkdreams.testchat

import com.pinkdreams.persistence.repositories.AiSettingsRepository
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.IntentEngineRepository
import com.pinkdreams.persistence.repositories.MemoryEngineRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import org.jetbrains.exposed.sql.Database
import java.util.UUID

/**
 * "Pinned" repository views — Phase ADMIN-3's entire mechanism for running the
 * real pipeline against a fixed, non-active configuration.
 *
 * Every production business-logic class (RepositoryContextAssembler,
 * LlmIntentDiscovery, SkillContextEnricher, DeterministicMemoryContextSelector,
 * LlmMemoryEngineMaintainer, LlmGenerator/AiRuntimeSettings) resolves "what to
 * use" by calling exactly one method on its repository: `getActiveEngine()`,
 * `getActiveCoreVersion(personaId)`, or `getActiveForKey(key)`/
 * `findAllActiveKeys()`. None of those classes were changed for this phase —
 * each "Pinned*" type below is a subclass that overrides ONLY that one method
 * to answer with a fixed, already-published version instead of the row
 * flagged `is_active`. Handed a Pinned repository, the exact same production
 * code becomes "test mode" — this is what section 59 means by "the same
 * pipeline running against a controlled configuration snapshot," not a
 * parallel implementation.
 *
 * None of these ever write: activate/publish/archive are inherited unchanged
 * but Test Chat never calls them, so a test conversation can never promote its
 * pinned version to production (Phase ADMIN-3 sections 25-28).
 */

class PinnedConversationEngineRepository(
    db: Database,
    private val pinnedVersion: Int,
) : ConversationEngineRepository(db) {
    override fun getActiveEngine(): ConversationEngine? = findByVersion(pinnedVersion)
}

class PinnedPersonaRepository(
    db: Database,
    private val pinnedCoreVersionId: UUID,
) : PersonaRepository(db) {
    // Ignores the requested personaId deliberately: the test snapshot already
    // committed to one specific persona + Persona Core version at creation
    // time (Phase ADMIN-3 section 25), so there is nothing left to resolve.
    private val coreVersionRepository = PersonaCoreVersionRepository(db)

    override fun getActiveCoreVersion(personaId: UUID): PersonaCoreVersionRepository.PersonaCoreVersion? =
        coreVersionRepository.findById(pinnedCoreVersionId)
}

class PinnedMemoryEngineRepository(
    db: Database,
    private val pinnedVersion: Int,
) : MemoryEngineRepository(db) {
    override fun getActiveEngine(): MemoryEngine? = findByVersion(pinnedVersion)
}

class PinnedIntentEngineRepository(
    db: Database,
    private val pinnedVersion: Int,
) : IntentEngineRepository(db) {
    override fun getActiveEngine(): IntentEngine? = findByVersion(pinnedVersion)
}

/**
 * Pins EXACTLY the skill versions named in the snapshot as both the active
 * candidate set (for Intent Engine discovery) and the resolvable content (for
 * skill context enrichment / memory relevance scoring) — never the full
 * historical catalogue (Phase ADMIN-3 section 8/53).
 */
class PinnedSkillRepository(
    db: Database,
    private val pinnedVersions: Map<String, Int>,
) : SkillRepository(db) {
    override fun findAllActiveKeys(): List<String> = pinnedVersions.keys.sorted()

    override fun getActiveForKey(key: String): Skill? {
        val version = pinnedVersions[key] ?: return null
        return findByKey(key).firstOrNull { it.version == version }
    }
}

class PinnedAiSettingsRepository(
    db: Database,
    private val pinnedModel: String?,
    private val pinnedTemperature: Double?,
    private val pinnedMaxOutputTokens: Int?,
) : AiSettingsRepository(db) {
    override fun get(): StoredAiSettings = StoredAiSettings(
        model = pinnedModel,
        temperature = pinnedTemperature,
        maxOutputTokens = pinnedMaxOutputTokens,
        updatedAt = null,
        updatedBy = null,
    )
}
