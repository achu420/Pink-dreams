package com.pinkdreams.testchat

import com.pinkdreams.chat.ChatEngineFactory
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ChatResult
import com.pinkdreams.chat.memory.TestMemoryScope
import com.pinkdreams.config.AiRuntimeSettings
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.IntentEngineRepository
import com.pinkdreams.persistence.repositories.MemoryEngineRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import com.pinkdreams.persistence.repositories.UserRepository
import java.util.UUID

/**
 * Test Chat orchestration (Phase ADMIN-3).
 *
 * ARCHITECTURE (section 59): this class does not run any AI logic itself. It
 * only (a) resolves an admin's version selections into an immutable
 * [ConversationRepository.ConfigurationSnapshot], validating that everything
 * chosen is eligible (published or active, never draft — section 32), and
 * (b) builds a per-conversation [com.pinkdreams.chat.ChatEngine] from that
 * snapshot via [ChatEngineFactory] + "Pinned" repositories. Sending a message
 * then calls that engine's `.process()` exactly like production's ChatRoutes
 * calls the shared production engine — same [ChatRequest], same
 * [PipelineChatEngine][com.pinkdreams.chat.PipelineChatEngine], same
 * generation/memory/skill pipeline.
 */
class TestChatService(
    private val productionDependencies: ChatEngineFactory.Dependencies,
    private val conversationRepository: ConversationRepository,
    private val personaRepository: PersonaRepository,
    private val personaCoreVersionRepository: PersonaCoreVersionRepository,
    private val skillRepository: SkillRepository,
    private val userRepository: UserRepository,
) {
    sealed interface CreationResult {
        data class Created(
            val conversation: ConversationRepository.Conversation,
        ) : CreationResult

        data class Rejected(val reason: String) : CreationResult
    }

    /**
     * Everything an admin may choose. A null field means "use the Testing
     * default for that component" (section 17/52) — resolved below to the
     * newest PUBLISHED, non-active version, or to the active version if no
     * published-but-inactive one exists (so a fresh install with only one
     * version per engine can still be tested). Never resolved to a draft.
     */
    data class CreationRequest(
        val personaId: UUID,
        val testUserId: UUID,
        val conversationEngineVersion: Int? = null,
        val personaCoreVersion: Int? = null,
        val intentEngineVersion: Int? = null,
        val memoryEngineVersion: Int? = null,
        /** skill key -> requested version. A key absent here is simply not offered to this test's Intent Engine. */
        val skillVersions: Map<String, Int>? = null,
        val model: String? = null,
        val temperature: Double? = null,
        val maxOutputTokens: Int? = null,
    )

    fun create(request: CreationRequest): CreationResult {
        if (!userRepository.exists(request.testUserId)) {
            return CreationResult.Rejected("Test user not found: ${request.testUserId}")
        }
        val persona = personaRepository.findById(request.personaId)
            ?: return CreationResult.Rejected("Persona not found: ${request.personaId}")

        val engineVersion = request.conversationEngineVersion
            ?: testingDefaultEngineVersion()
            ?: return CreationResult.Rejected("No eligible (published or active) Conversation Engine version exists")
        val engine = productionDependencies.engineRepository.findByVersion(engineVersion)
            ?: return CreationResult.Rejected("Conversation Engine v$engineVersion does not exist")
        if (!isEligibleForTesting(engine.status, engine.isActive)) {
            return CreationResult.Rejected("Conversation Engine v$engineVersion is ${engine.status} and not eligible for testing — it must be published or active")
        }

        val coreVersion = request.personaCoreVersion
            ?: testingDefaultPersonaCoreVersion(persona.id)
            ?: return CreationResult.Rejected("No eligible (published or active) Persona Core version exists for this persona")
        val core = personaCoreVersionRepository.findByPersonaAndVersion(persona.id, coreVersion)
            ?: return CreationResult.Rejected("Persona Core v$coreVersion does not exist for this persona")
        val coreIsActive = persona.activeCoreVersionId == core.id
        if (!isEligibleForTesting(core.status, coreIsActive)) {
            return CreationResult.Rejected("Persona Core v$coreVersion is ${core.status} and not eligible for testing — it must be published or active")
        }

        val intentVersion = request.intentEngineVersion
            ?: testingDefaultIntentEngineVersion()
            ?: return CreationResult.Rejected("No eligible (published or active) Intent Engine version exists")
        val intentEngine = productionDependencies.intentEngineRepository.findByVersion(intentVersion)
            ?: return CreationResult.Rejected("Intent Engine v$intentVersion does not exist")
        if (!isEligibleForTesting(intentEngine.status, intentEngine.isActive)) {
            return CreationResult.Rejected("Intent Engine v$intentVersion is ${intentEngine.status} and not eligible for testing — it must be published or active")
        }

        val memoryVersion = request.memoryEngineVersion
            ?: testingDefaultMemoryEngineVersion()
            ?: return CreationResult.Rejected("No eligible (published or active) Memory Engine version exists")
        val memoryEngine = productionDependencies.memoryEngineRepository.findByVersion(memoryVersion)
            ?: return CreationResult.Rejected("Memory Engine v$memoryVersion does not exist")
        if (!isEligibleForTesting(memoryEngine.status, memoryEngine.isActive)) {
            return CreationResult.Rejected("Memory Engine v$memoryVersion is ${memoryEngine.status} and not eligible for testing — it must be published or active")
        }

        val skillVersions = request.skillVersions ?: testingDefaultSkillVersions()
        if (skillVersions.isEmpty()) {
            return CreationResult.Rejected("No skill versions selected and no eligible default skill set exists")
        }
        for ((key, version) in skillVersions) {
            val skill = skillRepository.findByKey(key).firstOrNull { it.version == version }
                ?: return CreationResult.Rejected("Skill '$key' v$version does not exist")
            val skillIsActive = skillRepository.getActiveForKey(key)?.id == skill.id
            if (!isEligibleForTesting(skill.status, skillIsActive)) {
                return CreationResult.Rejected("Skill '$key' v$version is ${skill.status} and not eligible for testing — it must be published or active")
            }
        }

        // A real Personas row dedicated to this one test conversation's memory,
        // never shown or usable as an ordinary persona (status stays "draft",
        // it is never activated, and it has no active core version). Required
        // because memory_facts.persona_id is a real foreign key to
        // personas(id) on PostgreSQL — see MemoryScopeResolver.TestMemoryScope.
        val memoryScopePersona = personaRepository.create(
            slug = "__test_memory_scope__${UUID.randomUUID()}",
            displayName = "Test Chat memory scope",
            gender = persona.gender,
            orientation = persona.orientation,
            apparentAge = persona.apparentAge,
            languageProfile = emptyMap(),
        )

        val snapshot = ConversationRepository.ConfigurationSnapshot(
            conversationEngineVersion = engineVersion,
            personaCoreVersion = coreVersion,
            intentEngineVersion = intentVersion,
            memoryEngineVersion = memoryVersion,
            skillVersions = skillVersions,
            model = request.model,
            temperature = request.temperature,
            maxOutputTokens = request.maxOutputTokens,
            memoryScopePersonaId = memoryScopePersona.id,
        )

        val conversation = conversationRepository.createTest(
            userId = request.testUserId,
            personaId = request.personaId,
            snapshot = snapshot,
        )
        return CreationResult.Created(conversation)
    }

    sealed interface MessageResult {
        data class Sent(val chatResult: ChatResult, val conversation: ConversationRepository.Conversation) : MessageResult
        data class Rejected(val reason: String) : MessageResult
    }

    /**
     * Sends a message through a TEST conversation's own pinned engine —
     * exactly the same [ChatRequest]/[com.pinkdreams.chat.PipelineChatEngine]
     * shape production uses, just built from a different set of repositories.
     * Never touches the production engine or its repositories.
     */
    fun sendMessage(conversationId: UUID, clientMessageId: UUID, content: String): MessageResult {
        val conversation = conversationRepository.findById(conversationId)
            ?: return MessageResult.Rejected("Conversation not found: $conversationId")
        if (!conversation.isTest) {
            return MessageResult.Rejected("Conversation $conversationId is not a Test Chat conversation")
        }
        val engine = buildEngineFor(conversation)
        val chatRequest = ChatRequest(
            requestId = UUID.randomUUID(),
            userId = conversation.userId,
            conversationId = conversation.id,
            personaId = conversation.personaId,
            clientMessageId = clientMessageId,
            content = content,
        )
        val result = engine.process(chatRequest)
        System.err.println(
            "TEST_CHAT: executionMode=TEST conversationId=$conversationId testUserId=${conversation.userId} " +
                "personaId=${conversation.personaId} conversationEngineVersion=${conversation.snapshot?.conversationEngineVersion} " +
                "personaCoreVersion=${conversation.snapshot?.personaCoreVersion} intentEngineVersion=${conversation.snapshot?.intentEngineVersion} " +
                "memoryEngineVersion=${conversation.snapshot?.memoryEngineVersion} requestId=${chatRequest.requestId}",
        )
        return MessageResult.Sent(result, conversation)
    }

    fun findConversation(conversationId: UUID): ConversationRepository.Conversation? {
        val conversation = conversationRepository.findById(conversationId) ?: return null
        return conversation.takeIf { it.isTest }
    }

    /**
     * Builds a fresh, per-call [com.pinkdreams.chat.ChatEngine] pinned to this
     * conversation's stored snapshot. Cheap: every "Pinned*" repository is a
     * thin wrapper over the shared `db` handle, so nothing here is a
     * persistent resource — there is deliberately no cache, because the
     * snapshot is read from the conversation itself on every call, which is
     * what makes "activate a new production version, then send another
     * message in the existing Test Chat" (section 50, Test 4) work without
     * any extra bookkeeping.
     */
    private fun buildEngineFor(conversation: ConversationRepository.Conversation): com.pinkdreams.chat.ChatEngine {
        val snapshot = requireNotNull(conversation.snapshot) { "Test conversation is missing its configuration snapshot" }
        val db = productionDependencies.db

        val pinnedPersonaCore = personaCoreVersionRepository.findByPersonaAndVersion(conversation.personaId, snapshot.personaCoreVersion)
            ?: error("Snapshot Persona Core v${snapshot.personaCoreVersion} no longer exists")

        val pinnedDependencies = productionDependencies.copy(
            engineRepository = PinnedConversationEngineRepository(db, snapshot.conversationEngineVersion),
            personaRepository = PinnedPersonaRepository(db, pinnedPersonaCore.id),
            skillRepository = PinnedSkillRepository(db, snapshot.skillVersions),
            memoryEngineRepository = PinnedMemoryEngineRepository(db, snapshot.memoryEngineVersion),
            intentEngineRepository = PinnedIntentEngineRepository(db, snapshot.intentEngineVersion),
            aiRuntimeSettings = AiRuntimeSettings(
                productionDependencies.llmConfig,
                PinnedAiSettingsRepository(db, snapshot.model, snapshot.temperature, snapshot.maxOutputTokens),
            ),
            // Phase ADMIN-3 section 11: test memory never touches production
            // memory for this persona — see MemoryScopeResolver.
            memoryScopeResolver = TestMemoryScope(snapshot.memoryScopePersonaId),
        )
        return ChatEngineFactory.build(pinnedDependencies)
    }

    // --- Testing defaults (section 52): newest published-but-not-active version,
    // falling back to the active version so a single-version deployment can
    // still be tested. Never a draft. ---

    private fun testingDefaultEngineVersion(): Int? {
        val all = productionDependencies.engineRepository.findAll()
        return all.filter { it.status == "published" && !it.isActive }.maxByOrNull { it.version }?.version
            ?: all.firstOrNull { it.isActive }?.version
    }

    private fun testingDefaultPersonaCoreVersion(personaId: UUID): Int? {
        val all = personaCoreVersionRepository.findForPersona(personaId)
        val activeId = personaRepository.findById(personaId)?.activeCoreVersionId
        return all.filter { it.status == "published" && it.id != activeId }.maxByOrNull { it.version }?.version
            ?: all.firstOrNull { it.id == activeId }?.version
    }

    private fun testingDefaultIntentEngineVersion(): Int? {
        val all = productionDependencies.intentEngineRepository.findAll()
        return all.filter { it.status == "published" && !it.isActive }.maxByOrNull { it.version }?.version
            ?: all.firstOrNull { it.isActive }?.version
    }

    private fun testingDefaultMemoryEngineVersion(): Int? {
        val all = productionDependencies.memoryEngineRepository.findAll()
        return all.filter { it.status == "published" && !it.isActive }.maxByOrNull { it.version }?.version
            ?: all.firstOrNull { it.isActive }?.version
    }

    /** One default per currently-active skill key — never the full historical catalogue (section 8/53). */
    private fun testingDefaultSkillVersions(): Map<String, Int> =
        skillRepository.findAllActiveKeys().associateWith { key -> skillRepository.getActiveForKey(key)!!.version }

    private fun isEligibleForTesting(status: String, isActive: Boolean): Boolean =
        status == "published" || isActive
}
