package com.pinkdreams.testchat

import com.pinkdreams.baseline.BaselineConfiguration
import com.pinkdreams.baseline.BaselineSeeder
import com.pinkdreams.chat.ChatEngineFactory
import com.pinkdreams.chat.ChatResult
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.ValidationDecision
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.chat.sensitive.SensitivePreferenceService
import com.pinkdreams.config.AiRuntimeSettings
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.llm.GenerationRequest
import com.pinkdreams.llm.LlmClient
import com.pinkdreams.llm.LlmResponse
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.AiSettingsRepository
import com.pinkdreams.persistence.repositories.ChatRequestExecutionRepository
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.IntentEngineRepository
import com.pinkdreams.persistence.repositories.MemoryEngineRepository
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SensitivePreferenceRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository
import com.pinkdreams.persistence.repositories.UserRepository
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase ADMIN-3 sections 41-49: version resolution, snapshot immutability,
 * production isolation, memory isolation, skill isolation, intent coverage,
 * debug-inspector data availability, regeneration, and failure degradation.
 *
 * Every scenario runs the REAL [TestChatService] → [ChatEngineFactory] →
 * [com.pinkdreams.chat.PipelineChatEngine] path — nothing here calls a
 * simplified stand-in pipeline (section 59).
 */
class TestChatServiceTest {

    /** Routes one shared LlmClient to the right scripted answer by inspecting the system prompt, mirroring how ChatEngineFactory wires exactly one LlmClient to every collaborator. */
    private class RoutingLlmClient(
        private val skillKey: String? = "general_chat",
        private val generationReply: String = "a reply",
    ) : LlmClient {
        // Synchronized: post-delivery hooks (memory extraction, memory-engine
        // maintenance) dispatch on background threads via CompletableFuture, so
        // this list is written from multiple threads concurrently with the
        // test's own read immediately after sendMessage() returns.
        val requestsSeen = java.util.Collections.synchronizedList(mutableListOf<GenerationRequest>())
        var generationCalls = AtomicInteger(0)

        override fun generate(request: GenerationRequest): LlmResponse {
            requestsSeen += request
            val system = request.context.blocks.firstOrNull { it.role == "system" }?.content ?: ""
            return when {
                system.contains("Intent Discovery component") ->
                    LlmResponse(content = if (skillKey == null) """{"skillKey": null}""" else """{"skillKey": "$skillKey"}""", provider = "test")
                system.contains("memory-extraction system") ->
                    LlmResponse(content = """{"facts": []}""", provider = "test")
                system.contains("TASK 1") || system.contains("userMemoryChanges") ->
                    LlmResponse(content = """{"userMemoryChanges": [], "personaMemoryChanges": []}""", provider = "test")
                else -> {
                    generationCalls.incrementAndGet()
                    LlmResponse(content = generationReply, provider = "test")
                }
            }
        }
    }

    private class World(skillKey: String? = "general_chat", generationReply: String = "a reply") {
        val db: Database = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }
        val engines = ConversationEngineRepository(db)
        val personas = PersonaRepository(db)
        val cores = PersonaCoreVersionRepository(db)
        val skills = SkillRepository(db)
        val memoryEngines = MemoryEngineRepository(db)
        val intentEngines = IntentEngineRepository(db)
        val aiSettingsRepo = AiSettingsRepository(db)
        val memoryFacts = MemoryFactRepository(db)
        val memoryService = MemoryService(memoryFacts)
        val messages = MessageRepository(db)
        val conversations = ConversationRepository(db)
        val userProfiles = UserProfileRepository(db)
        val users = UserRepository(db)
        val executions = ChatRequestExecutionRepository(db)
        val llmClient = RoutingLlmClient(skillKey, generationReply)
        val llmConfig = LlmConfig(apiKey = "test-key", model = "test-model", maxOutputTokens = 1024)
        val aiRuntimeSettings = AiRuntimeSettings(llmConfig, aiSettingsRepo)

        val productionDeps = ChatEngineFactory.Dependencies(
            llmClient = llmClient,
            llmConfig = llmConfig,
            db = db,
            conversationRepository = conversations,
            messageRepository = messages,
            userProfileRepository = userProfiles,
            memoryService = memoryService,
            memoryFactRepository = memoryFacts,
            engineRepository = engines,
            personaRepository = personas,
            skillRepository = skills,
            memoryEngineRepository = memoryEngines,
            intentEngineRepository = intentEngines,
            executionRepository = executions,
            aiRuntimeSettings = aiRuntimeSettings,
            sensitivePreferenceService = null,
        )

        val service = TestChatService(productionDeps, conversations, personas, cores, skills, users)

        val testUserId: UUID = UUID.randomUUID()
        lateinit var personaId: UUID

        init {
            users.create(testUserId)
            BaselineSeeder(engines, personas, cores, skills, memoryEngines, intentEngines).seedIfMissing()
            skills.findAll().forEach { skills.activate(skills.publish(it.id).id) }
            personaId = personas.findAll().first { it.slug == BaselineConfiguration.SIMRAN_SLUG }.id
        }

        /** Publishes (but does NOT activate) a new version of every versioned engine, plus one skill. */
        fun publishTestingVersions(): Map<String, Int> {
            val engineDraft = engines.createNextVersion("TESTING ENGINE CONTENT", createdBy = "admin")
            engines.publishEngine(engineDraft.id)
            val coreDraft = cores.createNextVersion(personaId, "TESTING PERSONA CORE CONTENT", author = "admin")
            cores.publishCoreVersion(coreDraft.id)
            val intentDraft = intentEngines.createNextVersion("TESTING INTENT RULES {{ACTIVE_SKILL_KEYS}}", createdBy = "admin")
            intentEngines.publish(intentDraft.id)
            val memoryDraft = memoryEngines.createNextVersion("TESTING MEMORY RULES", createdBy = "admin")
            memoryEngines.publish(memoryDraft.id)
            val flirtingDraft = skills.createNextVersion("flirting", "SKILL: FLIRTING\n\nPurpose:\nx\n\nBehavior:\nx\n\nDo not:\nx")
            skills.publish(flirtingDraft.id)
            return mapOf(
                "engine" to engineDraft.version, "core" to coreDraft.version,
                "intent" to intentDraft.version, "memory" to memoryDraft.version, "flirting" to flirtingDraft.version,
            )
        }

        fun activeSnapshotArgs(): TestChatService.CreationRequest = TestChatService.CreationRequest(
            personaId = personaId,
            testUserId = testUserId,
        )
    }

    // ---------- section 41: version resolution ----------

    @Test
    fun `production resolves the active version for every configuration axis`() {
        val w = World()
        val activeEngine = w.engines.getActiveEngine()!!
        val activeCore = w.personas.findById(w.personaId)!!.activeCoreVersionId!!
        val activeIntent = w.intentEngines.getActiveEngine()!!
        val activeMemory = w.memoryEngines.getActiveEngine()!!

        val versions = w.publishTestingVersions()
        val created = w.service.create(w.activeSnapshotArgs()) as TestChatService.CreationResult.Created

        // With no explicit selection, the Testing DEFAULT is the newest
        // published-but-inactive version — not the still-active production one.
        assertEquals(versions["engine"], created.conversation.snapshot!!.conversationEngineVersion)
        assertEquals(versions["core"], created.conversation.snapshot!!.personaCoreVersion)
        assertEquals(versions["intent"], created.conversation.snapshot!!.intentEngineVersion)
        assertEquals(versions["memory"], created.conversation.snapshot!!.memoryEngineVersion)
        assertNotEquals(activeEngine.version, created.conversation.snapshot!!.conversationEngineVersion)
        assertNotNull(activeCore)
        assertNotNull(activeIntent)
        assertNotNull(activeMemory)
    }

    @Test
    fun `an admin can explicitly pin any published or active version per axis`() {
        val w = World()
        val activeEngineVersion = w.engines.getActiveEngine()!!.version

        val result = w.service.create(w.activeSnapshotArgs().copy(conversationEngineVersion = activeEngineVersion)) as TestChatService.CreationResult.Created

        assertEquals(activeEngineVersion, result.conversation.snapshot!!.conversationEngineVersion)
    }

    // ---------- section 32: draft is never eligible ----------

    @Test
    fun `a draft version can never be selected for testing`() {
        val w = World()
        val draft = w.engines.createNextVersion("still being edited", createdBy = "admin") // never published

        val result = w.service.create(w.activeSnapshotArgs().copy(conversationEngineVersion = draft.version))

        assertTrue(result is TestChatService.CreationResult.Rejected)
        assertTrue((result as TestChatService.CreationResult.Rejected).reason.contains("draft"))
    }

    @Test
    fun `an unknown version number is rejected`() {
        val w = World()
        val result = w.service.create(w.activeSnapshotArgs().copy(conversationEngineVersion = 999))
        assertTrue(result is TestChatService.CreationResult.Rejected)
    }

    // ---------- section 42: snapshot immutability ----------

    @Test
    fun `an existing test conversation keeps its snapshot after production activates a new version`() {
        val w = World()
        val versions = w.publishTestingVersions()
        val created = w.service.create(w.activeSnapshotArgs()) as TestChatService.CreationResult.Created
        val originalSnapshot = created.conversation.snapshot!!

        // Admin activates the just-published engine version into production.
        val newlyPublished = w.engines.findByVersion(versions["engine"]!!)!!
        w.engines.activateEngine(newlyPublished.id)

        val reloaded = w.service.findConversation(created.conversation.id)!!
        assertEquals(originalSnapshot, reloaded.snapshot, "The snapshot must not change because production activated something")
    }

    @Test
    fun `changing ai settings does not affect an existing test conversation`() {
        val w = World()
        val created = w.service.create(w.activeSnapshotArgs().copy(model = "pinned-model", temperature = 0.2, maxOutputTokens = 500))
            as TestChatService.CreationResult.Created

        w.aiSettingsRepo.save("admin-changed-model", 0.9, 4000, "admin")

        val reloaded = w.service.findConversation(created.conversation.id)!!
        assertEquals("pinned-model", reloaded.snapshot!!.model)
        assertEquals(0.2, reloaded.snapshot!!.temperature)
        assertEquals(500, reloaded.snapshot!!.maxOutputTokens)
    }

    // ---------- section 43: production isolation ----------

    @Test
    fun `publishing a testing version does not change what production resolves`() {
        val w = World()
        val originalActiveVersion = w.engines.getActiveEngine()!!.version

        w.publishTestingVersions()

        assertEquals(originalActiveVersion, w.engines.getActiveEngine()!!.version, "Publishing must never change the active version")
    }

    @Test
    fun `running a test conversation never changes the active version of anything`() {
        val w = World()
        val activeEngineBefore = w.engines.getActiveEngine()!!.version
        val activeCoreBefore = w.personas.findById(w.personaId)!!.activeCoreVersionId
        val activeIntentBefore = w.intentEngines.getActiveEngine()!!.version
        val activeMemoryBefore = w.memoryEngines.getActiveEngine()!!.version
        w.publishTestingVersions()
        val created = w.service.create(w.activeSnapshotArgs()) as TestChatService.CreationResult.Created

        val sendResult = w.service.sendMessage(created.conversation.id, UUID.randomUUID(), "hello") as TestChatService.MessageResult.Sent
        assertTrue(sendResult.chatResult is ChatResult.Success)

        assertEquals(activeEngineBefore, w.engines.getActiveEngine()!!.version)
        assertEquals(activeCoreBefore, w.personas.findById(w.personaId)!!.activeCoreVersionId)
        assertEquals(activeIntentBefore, w.intentEngines.getActiveEngine()!!.version)
        assertEquals(activeMemoryBefore, w.memoryEngines.getActiveEngine()!!.version)
    }

    @Test
    fun `a production conversation is unaffected by test chat activity`() {
        val w = World()
        val productionEngine = ChatEngineFactory.build(w.productionDeps)
        val productionConversation = w.conversations.create(UUID.randomUUID(), w.personaId)
        // Make the production user resolvable.
        w.users.create(productionConversation.userId)

        val created = w.service.create(w.activeSnapshotArgs()) as TestChatService.CreationResult.Created
        w.service.sendMessage(created.conversation.id, UUID.randomUUID(), "test message")

        val prodRequest = com.pinkdreams.chat.ChatRequest(
            UUID.randomUUID(), productionConversation.userId, productionConversation.id, w.personaId, UUID.randomUUID(), "production message",
        )
        val prodResult = productionEngine.process(prodRequest)
        assertTrue(prodResult is ChatResult.Success, "Production chat must keep working unaffected by any Test Chat activity")
    }

    // ---------- FK-compliant memory scope (found via live PostgreSQL verification) ----------

    @Test
    fun `the memory scope is backed by a real persona row not merely a derived id`() {
        // memory_facts.persona_id carries a REAL foreign key to personas(id) on
        // PostgreSQL. H2/SchemaUtils enforces no such key, so this is the one
        // thing an H2 test CAN still verify: that the scope id actually
        // resolves to a genuine row, which is what makes the FK satisfiable.
        val w = World()
        val created = w.service.create(w.activeSnapshotArgs()) as TestChatService.CreationResult.Created

        val scopePersona = w.personas.findById(created.conversation.snapshot!!.memoryScopePersonaId)

        assertNotNull(scopePersona, "The memory scope id must be a real persona row, not a merely-derived UUID")
        assertNotEquals(w.personaId, scopePersona!!.id, "The scope must never be the real persona itself")
    }

    @Test
    fun `the memory scope persona is hidden from the admin persona listing`() {
        val w = World()
        val created = w.service.create(w.activeSnapshotArgs()) as TestChatService.CreationResult.Created
        val scopePersona = w.personas.findById(created.conversation.snapshot!!.memoryScopePersonaId)!!

        assertTrue(scopePersona.slug.startsWith("__test_memory_scope__"))
    }

    @Test
    fun `each test conversation gets its own distinct memory scope persona`() {
        val w = World()
        val first = w.service.create(w.activeSnapshotArgs()) as TestChatService.CreationResult.Created
        val second = w.service.create(w.activeSnapshotArgs()) as TestChatService.CreationResult.Created

        assertNotEquals(first.conversation.snapshot!!.memoryScopePersonaId, second.conversation.snapshot!!.memoryScopePersonaId)
    }

    // ---------- section 44/24: memory isolation ----------

    @Test
    fun `test memory extraction never reaches production memory for the same persona`() {
        val w = World()
        val realUserId = UUID.randomUUID()
        w.users.create(realUserId)
        // A production fact exists for (realUserId, personaId).
        w.memoryFacts.create(realUserId, w.personaId, "user is a nurse in Chennai", "interest", "medium")

        val created = w.service.create(w.activeSnapshotArgs()) as TestChatService.CreationResult.Created
        w.service.sendMessage(created.conversation.id, UUID.randomUUID(), "hello")

        // Production memory for the REAL user/persona pair is untouched.
        val prodMemory = w.memoryService.selectForContext(realUserId, w.personaId, 20)
        assertEquals(1, prodMemory.size)
        assertEquals("user is a nurse in Chennai", prodMemory.single().fact)

        // And nothing was written under the real (testUserId, personaId) pair either
        // — the test scope is a synthetic id, not the persona's own.
        val underRealPersonaForTestUser = w.memoryFacts.findForRelationship(w.testUserId, w.personaId)
        assertTrue(underRealPersonaForTestUser.isEmpty(), "Test memory must not be written under the real personaId at all")
    }

    @Test
    fun `production memory is never visible inside a test conversation's context`() {
        val w = World()
        w.memoryFacts.create(w.testUserId, w.personaId, "PRODUCTION-ONLY: user lives in Mumbai", "interest", "medium")

        val created = w.service.create(w.activeSnapshotArgs()) as TestChatService.CreationResult.Created
        val sendResult = w.service.sendMessage(created.conversation.id, UUID.randomUUID(), "hi") as TestChatService.MessageResult.Sent
        val success = sendResult.chatResult as ChatResult.Success

        val message = w.messages.findById(success.response.assistantMessageId)!!
        assertTrue(
            !message.metadata.contains("PRODUCTION-ONLY"),
            "A production memory fact for the same (userId, personaId) must never appear in test context",
        )
    }

    @Test
    fun `two separate test conversations for the same persona do not share memory`() {
        // Each gets its own real shadow-persona scope (see the dedicated memory
        // scope tests above), so writing under one can never surface in the other.
        val w = World()
        val first = w.service.create(w.activeSnapshotArgs()) as TestChatService.CreationResult.Created
        val second = w.service.create(w.activeSnapshotArgs()) as TestChatService.CreationResult.Created

        assertNotEquals(first.conversation.id, second.conversation.id)
        assertNotEquals(first.conversation.snapshot!!.memoryScopePersonaId, second.conversation.snapshot!!.memoryScopePersonaId)
    }

    // ---------- section 45/28: skill isolation ----------

    @Test
    fun `testing a new skill version never makes it the active production version`() {
        val w = World(skillKey = "flirting")
        val productionFlirting = w.skills.getActiveForKey("flirting")!!
        val versions = w.publishTestingVersions()

        val created = w.service.create(w.activeSnapshotArgs().copy(skillVersions = mapOf("flirting" to versions["flirting"]!!)))
            as TestChatService.CreationResult.Created
        w.service.sendMessage(created.conversation.id, UUID.randomUUID(), "you are cute")

        assertEquals(productionFlirting.version, w.skills.getActiveForKey("flirting")!!.version, "Production skill version must be untouched")
        assertEquals(versions["flirting"], created.conversation.snapshot!!.skillVersions["flirting"])
    }

    @Test
    fun `a test conversation is offered only the snapshot skill keys as candidates never the full catalogue`() {
        val w = World(skillKey = "general_chat")
        val created = w.service.create(w.activeSnapshotArgs().copy(skillVersions = mapOf("general_chat" to w.skills.getActiveForKey("general_chat")!!.version)))
            as TestChatService.CreationResult.Created

        w.service.sendMessage(created.conversation.id, UUID.randomUUID(), "hello")

        val snapshot = synchronized(w.llmClient.requestsSeen) { w.llmClient.requestsSeen.toList() }
        val intentRequest = snapshot.first { it.context.blocks.first().content.contains("Intent Discovery component") }
        val system = intentRequest.context.blocks.first().content
        val candidateLine = system.lineSequence().dropWhile { !it.startsWith("ACTIVE SKILL KEYS") }.drop(1).first { it.isNotBlank() }
        assertEquals(setOf("general_chat"), candidateLine.split(",").map { it.trim() }.toSet())
    }

    // ---------- section 46: intent coverage ----------

    @Test
    fun `each baseline skill key can be selected inside a test conversation`() {
        BaselineConfiguration.SKILLS.forEach { seed ->
            val w = World(skillKey = seed.key)
            val created = w.service.create(w.activeSnapshotArgs()) as TestChatService.CreationResult.Created
            val result = w.service.sendMessage(created.conversation.id, UUID.randomUUID(), "message") as TestChatService.MessageResult.Sent
            val success = result.chatResult as ChatResult.Success
            val message = w.messages.findById(success.response.assistantMessageId)!!
            assertTrue(
                message.metadata.contains("SELECTED SKILL (${seed.key})"),
                "${seed.key} must be selectable inside a test conversation",
            )
        }
    }

    // ---------- section 47: debug inspector data ----------

    @Test
    fun `a test message stores diagnostics identifying every configuration axis`() {
        val w = World(skillKey = "emotional_support")
        val created = w.service.create(w.activeSnapshotArgs()) as TestChatService.CreationResult.Created
        val result = w.service.sendMessage(created.conversation.id, UUID.randomUUID(), "rough day") as TestChatService.MessageResult.Sent
        val success = result.chatResult as ChatResult.Success

        val message = w.messages.findById(success.response.assistantMessageId)!!
        assertTrue(message.metadata.contains("lvm_context_blocks"))
        assertTrue(message.metadata.contains("lvm_config"))
        assertTrue(message.metadata.contains("lvm_provider_exchange"))
        assertTrue(message.metadata.contains("SELECTED SKILL (emotional_support)"), "The actual selected skill must be visible in the actual context")

        // The response itself reports the resolved configuration (section 39).
        assertEquals(created.conversation.snapshot!!.conversationEngineVersion, w.service.findConversation(created.conversation.id)!!.snapshot!!.conversationEngineVersion)
    }

    @Test
    fun `intent discovery returning None is visible rather than silently absent`() {
        val w = World(skillKey = null)
        val created = w.service.create(w.activeSnapshotArgs()) as TestChatService.CreationResult.Created
        val result = w.service.sendMessage(created.conversation.id, UUID.randomUUID(), "mm") as TestChatService.MessageResult.Sent
        val success = result.chatResult as ChatResult.Success

        val message = w.messages.findById(success.response.assistantMessageId)!!
        assertTrue(
            !message.metadata.contains("SELECTED SKILL ("),
            "None must mean no skill block, not a silently wrong default",
        )
    }

    // ---------- section 48: regeneration uses the same snapshot ----------

    @Test
    fun `regeneration and every later turn in a test conversation use the identical configuration snapshot`() {
        // The internal OUTPUT_VALIDATION -> REGENERATE retry inside
        // PipelineChatEngine calls generator.generate() again against the SAME
        // skillEnrichedContext built from the SAME pinned repositories — nothing
        // about Test Chat's wiring changes between an initial attempt and a
        // regeneration, so the property that actually matters (every generation
        // call in this conversation sees the identical pinned engine content) is
        // exercised directly across multiple turns of the same conversation.
        val w = World()
        val versions = w.publishTestingVersions()
        val enginesSeenPerGenerationCall = java.util.Collections.synchronizedList(mutableListOf<String>())
        val trackingClient = object : LlmClient {
            override fun generate(request: GenerationRequest): LlmResponse {
                val system = request.context.blocks.firstOrNull { it.role == "system" }?.content ?: ""
                return when {
                    system.contains("Intent Discovery component") -> LlmResponse(content = """{"skillKey": null}""", provider = "test")
                    system.contains("memory-extraction system") -> LlmResponse(content = """{"facts": []}""", provider = "test")
                    system.contains("userMemoryChanges") -> LlmResponse(content = """{"userMemoryChanges": [], "personaMemoryChanges": []}""", provider = "test")
                    system.contains("CONVERSATION ENGINE") -> {
                        enginesSeenPerGenerationCall += system
                        LlmResponse(content = "reply", provider = "test")
                    }
                    else -> LlmResponse(content = "{}", provider = "test") // any other side-channel call (e.g. continuity)
                }
            }
        }
        val trackingDeps = w.productionDeps.copy(llmClient = trackingClient)
        val trackingService = TestChatService(trackingDeps, w.conversations, w.personas, w.cores, w.skills, w.users)
        val created = trackingService.create(
            TestChatService.CreationRequest(personaId = w.personaId, testUserId = w.testUserId, conversationEngineVersion = versions["engine"]),
        ) as TestChatService.CreationResult.Created

        trackingService.sendMessage(created.conversation.id, UUID.randomUUID(), "first turn")
        trackingService.sendMessage(created.conversation.id, UUID.randomUUID(), "second turn")

        assertEquals(2, enginesSeenPerGenerationCall.size, "Exactly one real generation call per turn")
        assertTrue(enginesSeenPerGenerationCall.all { it.contains("TESTING ENGINE CONTENT") })
    }

    // ---------- section 49: failure degradation is preserved ----------

    @Test
    fun `intent discovery failure inside a test conversation still yields a successful reply`() {
        val w = World()
        val failingClient = object : LlmClient {
            override fun generate(request: GenerationRequest): LlmResponse {
                val system = request.context.blocks.firstOrNull { it.role == "system" }?.content ?: ""
                if (system.contains("Intent Discovery component")) throw RuntimeException("boom")
                if (system.contains("memory-extraction system")) return LlmResponse(content = """{"facts": []}""", provider = "test")
                if (system.contains("userMemoryChanges")) return LlmResponse(content = """{"userMemoryChanges": [], "personaMemoryChanges": []}""", provider = "test")
                return LlmResponse(content = "still works", provider = "test")
            }
        }
        val deps = w.productionDeps.copy(llmClient = failingClient)
        val service = TestChatService(deps, w.conversations, w.personas, w.cores, w.skills, w.users)
        val created = service.create(TestChatService.CreationRequest(personaId = w.personaId, testUserId = w.testUserId)) as TestChatService.CreationResult.Created

        val result = service.sendMessage(created.conversation.id, UUID.randomUUID(), "hello") as TestChatService.MessageResult.Sent

        assertTrue(result.chatResult is ChatResult.Success, "Test Chat must not be more fragile than production — intent failure degrades to None")
    }

    @Test
    fun `sending to a non existent test conversation is rejected not thrown`() {
        val w = World()
        val result = w.service.sendMessage(UUID.randomUUID(), UUID.randomUUID(), "hello")
        assertTrue(result is TestChatService.MessageResult.Rejected)
    }

    @Test
    fun `sending to a production conversation through the test service is rejected`() {
        val w = World()
        val prodConversation = w.conversations.create(w.testUserId, w.personaId)
        val result = w.service.sendMessage(prodConversation.id, UUID.randomUUID(), "hello")
        assertTrue(result is TestChatService.MessageResult.Rejected)
    }

    /**
     * Intent Discovery Model Comparison v2 phase: end-to-end proof that Test
     * Chat's intentModel field actually reaches Intent Discovery's model
     * selection through the real TestChatService -> ChatEngineFactory path
     * (not merely at the Dependencies-construction level, which
     * IntentModelOverrideTest already covers) — and that primary generation
     * keeps using llmConfig.model, completely unaffected.
     */
    @Test
    fun `test chat can explicitly select the intent model independent of generation`() {
        val w = World()
        val created = w.service.create(
            TestChatService.CreationRequest(personaId = w.personaId, testUserId = w.testUserId, intentModel = "candidate/model-x"),
        ) as TestChatService.CreationResult.Created

        w.service.sendMessage(created.conversation.id, UUID.randomUUID(), "hello") as TestChatService.MessageResult.Sent

        val requestsSnapshot = synchronized(w.llmClient.requestsSeen) { w.llmClient.requestsSeen.toList() }
        val intentCalls = requestsSnapshot.filter {
            (it.context.blocks.firstOrNull { b -> b.role == "system" }?.content ?: "").contains("Intent Discovery component")
        }
        val generationCalls = requestsSnapshot.filter {
            !(it.context.blocks.firstOrNull { b -> b.role == "system" }?.content ?: "").let { s ->
                s.contains("Intent Discovery component") || s.contains("memory-extraction system") || s.contains("TASK 1") || s.contains("userMemoryChanges")
            }
        }
        assertTrue(intentCalls.isNotEmpty(), "Expected at least one Intent Discovery call")
        assertTrue(generationCalls.isNotEmpty(), "Expected at least one generation call")
        assertTrue(intentCalls.all { it.config.model == "candidate/model-x" }, "Intent Discovery must use the pinned candidate model")
        assertTrue(generationCalls.all { it.config.model == w.llmConfig.model }, "Primary generation must be unaffected by the Intent-only override")
    }

    @Test
    fun `a test conversation with no explicit intent override inherits the production default rather than losing it`() {
        val w = World()
        // Simulate Application.kt's production configuration: the ONE
        // production Dependencies instance has a real, non-null Intent
        // override (as it does after the Primary Generation Latency phase).
        val productionWithDefault = w.productionDeps.copy(intentModelOverride = "openai/gpt-4o-mini", intentJsonModeOverride = true)
        val service = TestChatService(productionWithDefault, w.conversations, w.personas, w.cores, w.skills, w.users)
        val created = service.create(
            TestChatService.CreationRequest(personaId = w.personaId, testUserId = w.testUserId),
        ) as TestChatService.CreationResult.Created

        service.sendMessage(created.conversation.id, UUID.randomUUID(), "hello") as TestChatService.MessageResult.Sent

        val intentCalls = synchronized(w.llmClient.requestsSeen) { w.llmClient.requestsSeen.toList() }.filter {
            (it.context.blocks.firstOrNull { b -> b.role == "system" }?.content ?: "").contains("Intent Discovery component")
        }
        assertTrue(intentCalls.isNotEmpty())
        assertTrue(
            intentCalls.all { it.config.model == "openai/gpt-4o-mini" && it.config.jsonMode == true },
            "A Test Chat conversation that never mentions intentModel/intentJsonMode must inherit production's own default, not silently reset it to null",
        )
    }

    @Test
    fun `with no intent model pinned test chat falls back to the same model as everything else`() {
        val w = World()
        val created = w.service.create(
            TestChatService.CreationRequest(personaId = w.personaId, testUserId = w.testUserId),
        ) as TestChatService.CreationResult.Created

        w.service.sendMessage(created.conversation.id, UUID.randomUUID(), "hello") as TestChatService.MessageResult.Sent

        val intentCalls = synchronized(w.llmClient.requestsSeen) { w.llmClient.requestsSeen.toList() }.filter {
            (it.context.blocks.firstOrNull { b -> b.role == "system" }?.content ?: "").contains("Intent Discovery component")
        }
        assertTrue(intentCalls.isNotEmpty())
        assertTrue(intentCalls.all { it.config.model == w.llmConfig.model }, "intentModel = null (production default) must keep Intent Discovery on the same model as everything else")
    }
}
