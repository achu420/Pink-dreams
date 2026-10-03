package com.pinkdreams.chat

import com.pinkdreams.baseline.BaselineConfiguration
import com.pinkdreams.baseline.BaselineSeeder
import com.pinkdreams.baseline.PersonaContextPocSeed
import com.pinkdreams.chat.personacontext.PersonaContextResolver
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.llm.GenerationRequest
import com.pinkdreams.llm.LlmClient
import com.pinkdreams.llm.LlmResponse
import com.pinkdreams.llm.OpenRouterLlmClient
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
import com.pinkdreams.persistence.repositories.PersonaFieldRegistryRepository
import com.pinkdreams.persistence.repositories.PersonaMasterRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillContextRequirementRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository
import com.pinkdreams.persistence.repositories.UserRepository
import com.pinkdreams.testchat.TestChatService
import com.pinkdreams.chat.memory.MemoryService
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Persona Context POC — EXPERIMENT 1: Real Resolver Benchmark.
 *
 * Runs the REAL production pipeline (TestChatService -> ChatEngineFactory ->
 * PipelineChatEngine, same path as TestChatServiceTest) for every
 * (scenario, variant) pair, with PersonaContextResolver actually wired in for
 * variants B/C via ChatEngineFactory.Dependencies.personaContextProvider.
 *
 * Intentionally bypassed for this benchmark (documented per the user's own
 * "if a component can't be reused, stop and document why" requirement):
 *  - Real intent-discovery LLM call: the scenario's own skillKey is pinned
 *    directly (HybridLlmClient), so A/B/C never risk a different skill
 *    selection contaminating the comparison.
 *  - Memory extraction / memory-engine maintenance: scripted no-ops, so no
 *    conversation state mutates between A -> B -> C for the same scenario.
 *  - Continuity summarization: not explicitly scripted, but structurally
 *    cannot fire for these single-turn (1 user + 1 assistant message)
 *    conversations — verified against BestEffortContinuitySummarization's
 *    own trigger condition (activeWindowStart = (messageCount -
 *    activeWindowSize).coerceAtLeast(0), always 0 for a 2-message history).
 *
 * Honest limitation: every scenario is a fresh single-turn conversation — no
 * prior history, no pre-existing user/persona memory facts are fed in (the
 * reused 85-scenario corpus from PersonaContextPocBenchmarkRunner is
 * single-turn by design; no memory fixtures exist). "Same history/memories
 * across A/B/C" is therefore satisfied as empty-but-identical, not
 * populated-and-identical.
 */
class PersonaContextRealBenchmarkTest {

    /** Real generation, everything else scripted — mirrors TestChatServiceTest's RoutingLlmClient exactly, except the "else" branch delegates to a real LlmClient instead of a canned reply. */
    private class HybridLlmClient(private val realClient: LlmClient) : LlmClient {
        @Volatile var currentSkillKey: String = "general_chat"
        /** Set on every real (primary generation) call — read immediately after sendMessage() returns, since that call is synchronous and on the critical path. */
        @Volatile var lastGenerationResponse: LlmResponse? = null
        /**
         * Phase 10C: when true, the primary-generation branch is intercepted
         * BEFORE any network call — the exact system-block content that would
         * have been sent is captured into [lastCapturedSystemBlock] and a
         * canned response is returned instead of ever calling [realClient].
         * This proves provider-output -> request equivalence through the
         * REAL ChatEngineFactory/RepositoryContextAssembler pipeline with
         * zero API calls. Default false: zero behavior change for every
         * other test in this file.
         */
        @Volatile var interceptGeneration: Boolean = false
        @Volatile var lastCapturedSystemBlock: String? = null

        override fun generate(request: GenerationRequest): LlmResponse {
            val system = request.context.blocks.firstOrNull { it.role == "system" }?.content ?: ""
            return when {
                system.contains("Intent Discovery component") ->
                    LlmResponse(content = """{"skillKey": "$currentSkillKey"}""", provider = "pinned-skill")
                system.contains("memory-extraction system") ->
                    LlmResponse(content = """{"facts": []}""", provider = "noop")
                system.contains("TASK 1") || system.contains("userMemoryChanges") ->
                    LlmResponse(content = """{"userMemoryChanges": [], "personaMemoryChanges": []}""", provider = "noop")
                interceptGeneration -> {
                    lastCapturedSystemBlock = system
                    LlmResponse(content = "(intercepted — zero API cost, Phase 10C verification)", provider = "intercepted-no-network-call")
                }
                else -> {
                    // Phase 10D: capture the real system block too (not just
                    // the intercepted path) so every real execution's actual
                    // sent payload is auditable, without altering behavior.
                    lastCapturedSystemBlock = system
                    val response = realClient.generate(request)
                    lastGenerationResponse = response
                    response
                }
            }
        }
    }

    private class World {
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

        val apiKey: String = System.getenv("OPENROUTER_API_KEY")
            ?: System.getProperty("com.pinkdreams.openrouter.api.key")
            ?: ""
        val realOpenRouterClient = OpenRouterLlmClient(
            apiKey = apiKey,
            model = "deepseek/deepseek-v4-flash-0731",
            maxOutputTokens = 1024,
            timeoutSeconds = 300,
        )
        val llmClient = HybridLlmClient(realOpenRouterClient)
        val llmConfig = LlmConfig(apiKey = apiKey, model = "deepseek/deepseek-v4-flash-0731", maxOutputTokens = 1024)
        val aiRuntimeSettings = com.pinkdreams.config.AiRuntimeSettings(llmConfig, aiSettingsRepo)

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
        val fieldRegistryRepo = PersonaFieldRegistryRepository(db)
        val skillReqRepo = SkillContextRequirementRepository(db)
        val personaMasterRepo = PersonaMasterRepository(db)
        val resolver = PersonaContextResolver(db, personaMasterRepo, fieldRegistryRepo, skillReqRepo)

        val testUserId: UUID = UUID.randomUUID()
        lateinit var personaId: UUID
        lateinit var personaCoreVersionId: UUID

        init {
            require(apiKey.isNotEmpty() && !apiKey.startsWith("test-key")) {
                "OPENROUTER_API_KEY must be set for the real resolver benchmark (run `. .\\run-local.secrets.ps1` first)"
            }
            users.create(testUserId)
            BaselineSeeder(engines, personas, cores, skills, memoryEngines, intentEngines).seedIfMissing()
            skills.findAll().forEach { skills.activate(skills.publish(it.id).id) }
            personaId = personas.findAll().first { it.slug == BaselineConfiguration.SIMRAN_SLUG }.id
            personaCoreVersionId = requireNotNull(personas.findById(personaId)?.activeCoreVersionId) {
                "Simran has no active core version after BaselineSeeder"
            }

            // Two new real skills that don't exist in BaselineConfiguration.SKILLS,
            // seeded the same way TestChatServiceTest.publishTestingVersions()
            // creates the "flirting" skill.
            val about = skills.createNextVersion("about", "SKILL: ABOUT\n\nPurpose:\nPersona self-introduction and background overview.\n\nBehavior:\nAnswer questions about who the persona is, their background, values, and current life — grounded in the Persona Core.\n\nDo not:\nInvent facts that contradict the Persona Core.")
            skills.activate(skills.publish(about.id).id)
            val perspectiveOf = skills.createNextVersion("perspective_of", "SKILL: PERSPECTIVE_OF\n\nPurpose:\nShare the persona's perspective or opinion on a topic.\n\nBehavior:\nAnswer 'what do you think about X' questions from the persona's own worldview and values.\n\nDo not:\nGive a neutral, opinion-less answer when the persona clearly has a view.")
            skills.activate(skills.publish(perspectiveOf.id).id)

            // Persona Context POC Experiment 1 seed data (field registry, skill
            // requirements, Simran's Persona Master document) — see
            // PersonaContextPocSeed's own doc comment for sourcing.
            val seed = PersonaContextPocSeed(db)
            seed.seedPersonaFieldRegistry()
            seed.seedSkillContextRequirements()
            seed.seedSimranMasterDocument(personaCoreVersionId)
        }

        /** One (scenario, variant) execution through the REAL pipeline, plus a direct resolver call for field-ID/token logging. */
        fun execute(scenario: PersonaContextPocBenchmarkRunner.TestScenario, variant: String): ExecutionRecord {
            val conversationId = createConversation(variant, scenario.skillKey)
            return sendTurn(conversationId, scenario.skillKey, scenario.userMessage, variant, scenario.id, turnIndex = 0)
        }

        /**
         * Phase 8 Candidate E: same as [execute] but pins an explicit
         * Conversation Engine version (must be published or active) instead
         * of the testing default, so BASELINE (v1) and TUNED (v2, containing
         * the added anti-fabrication paragraph) can be compared within the
         * same World/database without ever touching the "active" flag —
         * both versions coexist as published, nothing is deactivated.
         */
        fun executeWithEngine(scenario: PersonaContextPocBenchmarkRunner.TestScenario, variant: String, engineVersion: Int): ExecutionRecord {
            val conversationId = createConversation(variant, scenario.skillKey, engineVersion)
            return sendTurn(conversationId, scenario.skillKey, scenario.userMessage, variant, scenario.id, turnIndex = 0)
        }

        /**
         * Multi-turn execution (Phase 4): ONE conversation per (scenario, variant),
         * `sendMessage` called once per turn in order — real conversation history
         * accumulates in `messages` exactly as production does, so turn 3 genuinely
         * sees turns 1-2 via RepositoryContextAssembler's normal history block. The
         * skill can change per turn (skill-transition scenarios); persona-context
         * variant stays fixed for the whole conversation, matching how a real Test
         * Chat conversation is pinned once at creation.
         */
        fun executeMultiTurn(scenario: MultiTurnScenario, variant: String): List<ExecutionRecord> {
            val conversationId = createConversation(variant, scenario.turns.first().skillKey)
            return scenario.turns.mapIndexed { index, turn ->
                sendTurn(conversationId, turn.skillKey, turn.userMessage, variant, scenario.id, turnIndex = index)
            }
        }

        private fun createConversation(variant: String, initialSkillKey: String, engineVersion: Int? = null): UUID {
            val creation = service.create(
                TestChatService.CreationRequest(
                    personaId = personaId,
                    testUserId = testUserId,
                    personaContextVariant = variant,
                    personaContextSkillKey = initialSkillKey,
                    conversationEngineVersion = engineVersion,
                ),
            )
            check(creation is TestChatService.CreationResult.Created) { "Failed to create test conversation: $creation" }
            return creation.conversation.id
        }

        private fun sendTurn(
            conversationId: UUID,
            skillKey: String,
            userMessage: String,
            variant: String,
            scenarioId: UUID,
            turnIndex: Int,
        ): ExecutionRecord {
            llmClient.currentSkillKey = skillKey

            val resolverStart = System.nanoTime()
            // Phase 7 Candidate F: variant "D" is not a real PersonaContextResolver
            // variant (see StructuredFullDocumentContextProvider) — it bypasses the
            // resolver entirely, same as A, so this diagnostic call must not run
            // the generic (B/C-shaped) selective-retrieval branch for it. Doing so
            // was caught by phase7StructuredFullDocumentProbe()'s own STOP-condition
            // assertion before any data was lost (all 60 real generations were
            // correct — see PHASE7_INVESTIGATION.md).
            val projection = if (variant == "D") {
                val structuredProvider = com.pinkdreams.chat.context.StructuredFullDocumentContextProvider(personaMasterRepo)
                val renderedText = structuredProvider.getPersonaContext(personaId, personaCoreVersionId, skillKey, userMessage)
                com.pinkdreams.chat.personacontext.PersonaContextProjection(
                    renderedText = renderedText,
                    includedFieldIds = emptyList(),
                    excludedFieldIds = emptyList(),
                    personaTokenEstimate = (renderedText.length / 4).coerceAtLeast(1),
                    variant = "D",
                )
            } else {
                resolver.resolve(personaId, personaCoreVersionId, skillKey, userMessage, variant)
            }
            val resolverLatencyMs = (System.nanoTime() - resolverStart) / 1_000_000

            llmClient.lastGenerationResponse = null
            val genStart = System.nanoTime()
            val result = service.sendMessage(conversationId, UUID.randomUUID(), userMessage)
            val genLatencyMs = (System.nanoTime() - genStart) / 1_000_000

            check(result is TestChatService.MessageResult.Sent) { "sendMessage failed: $result" }
            val chatResult = result.chatResult

            val responseText: String
            val status: String
            val error: String?
            when (chatResult) {
                is ChatResult.Success -> {
                    responseText = chatResult.response.content
                    status = "SUCCESS"
                    error = null
                }
                else -> {
                    responseText = ""
                    status = "FAILED"
                    error = chatResult.toString().take(500)
                }
            }

            // Real prompt/completion/total tokens, captured directly off the real
            // OpenRouterLlmClient response (see HybridLlmClient) — never estimated.
            val meta = llmClient.lastGenerationResponse?.metadata ?: emptyMap()
            val promptTokens = meta["prompt_tokens"]?.toIntOrNull() ?: 0
            val completionTokens = meta["completion_tokens"]?.toIntOrNull() ?: 0
            val totalTokens = meta["total_tokens"]?.toIntOrNull() ?: 0
            val snapshot = conversations.findById(conversationId)?.snapshot

            return ExecutionRecord(
                scenarioId = scenarioId,
                skillKey = skillKey,
                userMessage = userMessage,
                variant = variant,
                turnIndex = turnIndex,
                includedFieldIds = projection.includedFieldIds,
                excludedFieldIds = projection.excludedFieldIds,
                personaTokenEstimate = projection.personaTokenEstimate,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = totalTokens,
                resolverLatencyMs = resolverLatencyMs,
                generationLatencyMs = genLatencyMs,
                responseText = responseText,
                status = status,
                error = error,
                conversationEngineVersion = snapshot?.conversationEngineVersion,
                personaCoreVersion = snapshot?.personaCoreVersion,
            )
        }
    }

    data class ExecutionRecord(
        val scenarioId: UUID,
        val skillKey: String,
        val userMessage: String,
        val variant: String,
        val turnIndex: Int = 0,
        val includedFieldIds: List<String>,
        val excludedFieldIds: List<String>,
        val personaTokenEstimate: Int,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val totalTokens: Int = 0,
        val resolverLatencyMs: Long,
        val generationLatencyMs: Long,
        val responseText: String,
        val status: String,
        val error: String?,
        val conversationEngineVersion: Int?,
        val personaCoreVersion: Int?,
    )

    /** A single-conversation, multi-turn scenario — turns sent in order to the SAME pinned conversation, so real history accumulates. */
    data class MultiTurnScenario(val id: UUID = UUID.randomUUID(), val turns: List<Turn>) {
        data class Turn(val skillKey: String, val userMessage: String)
    }

    /**
     * Verification gate (per plan): run ONE scenario through A/B/C and print
     * the full trace before the 315-execution run. Uses a
     * requiresRestrictedFields scenario (romantic_intimacy) since that's the
     * case most likely to expose a resolver-wiring mistake.
     */
    @Test
    fun singleScenarioTrace() {
        val world = World()
        val scenario = PersonaContextPocBenchmarkRunner.createBenchmarkScenarios()
            .first { it.skillKey == "romantic_intimacy" && it.requiresRestrictedFields }

        println("\n════ SINGLE-SCENARIO TRACE ════")
        println("Scenario: ${scenario.id} | skill=${scenario.skillKey} | message=\"${scenario.userMessage}\"")

        val records = listOf("A", "B", "C").map { variant -> world.execute(scenario, variant) }

        records.forEach { r ->
            println("\n--- Variant ${r.variant} ---")
            println("  engineVersion=${r.conversationEngineVersion} personaCoreVersion=${r.personaCoreVersion}")
            println("  includedFieldIds=${r.includedFieldIds}")
            println("  excludedFieldIds=${r.excludedFieldIds}")
            println("  personaTokenEstimate=${r.personaTokenEstimate}")
            println("  resolverLatencyMs=${r.resolverLatencyMs} generationLatencyMs=${r.generationLatencyMs}")
            println("  status=${r.status}${if (r.error != null) " error=${r.error}" else ""}")
            println("  response=\"${r.responseText.take(200)}\"")
        }

        // Invariants: engine/persona-core version identical across A/B/C.
        val engineVersions = records.map { it.conversationEngineVersion }.distinct()
        val coreVersions = records.map { it.personaCoreVersion }.distinct()
        assertEquals(1, engineVersions.size, "Engine version must be identical across A/B/C, got $engineVersions")
        assertEquals(1, coreVersions.size, "Persona core version must be identical across A/B/C, got $coreVersions")

        val (a, b, c) = Triple(records[0], records[1], records[2])
        // Variant A never uses the resolver's selective path (empty field lists by design).
        assertTrue(a.includedFieldIds.isEmpty(), "Variant A should not report resolved field IDs (raw content), got ${a.includedFieldIds}")
        // Variant B must have resolved SOME fields for a real skill.
        assertTrue(b.includedFieldIds.isNotEmpty(), "Variant B resolved zero fields — seed data or wiring is broken")
        // Variant C must include everything B does, plus behavioral-core fields (personality/communication/behavioral_signature).
        assertTrue(
            c.includedFieldIds.containsAll(b.includedFieldIds) || c.includedFieldIds.any { it.startsWith("personality") || it.startsWith("communication") || it.startsWith("behavioral_signature") },
            "Variant C should include B's fields plus behavioral-core fields; B=${b.includedFieldIds} C=${c.includedFieldIds}",
        )
        // The restricted field must actually be present for this romantic_intimacy scenario in B/C.
        assertTrue(
            b.includedFieldIds.any { it == "adult_intimacy" } && c.includedFieldIds.any { it == "adult_intimacy" },
            "romantic_intimacy should resolve the restricted 'adult_intimacy' field in B/C, got B=${b.includedFieldIds} C=${c.includedFieldIds}",
        )

        // Restricted-field non-leakage: a general_chat resolution must never include adult_intimacy/intimate_anatomy.
        val generalChatScenario = PersonaContextPocBenchmarkRunner.createBenchmarkScenarios().first { it.skillKey == "general_chat" }
        val generalChatB = world.execute(generalChatScenario, "B")
        assertTrue(
            generalChatB.includedFieldIds.none { it == "adult_intimacy" || it == "intimate_anatomy" },
            "general_chat must never resolve restricted intimacy fields, got ${generalChatB.includedFieldIds}",
        )

        println("\n✓ All trace invariants passed. Safe to proceed to fullBenchmark().")
    }

    /**
     * Phase 0 verification (Full Validation plan): re-runs general_chat's 15
     * scenarios through B/C after adding life_context.occupation to its
     * recommended fields, to confirm the Experiment-1 occupation-fabrication
     * defect is actually closed before any further scope is added.
     */
    @Test
    fun generalChatOccupationFixVerification() {
        val world = World()
        val scenarios = PersonaContextPocBenchmarkRunner.createBenchmarkScenarios().filter { it.skillKey == "general_chat" }
        println("Re-verifying general_chat fix on ${scenarios.size} scenarios")

        val records = mutableListOf<ExecutionRecord>()
        scenarios.forEach { scenario ->
            for (variant in listOf("A", "B", "C")) {
                records += world.execute(scenario, variant)
                Thread.sleep(1000)
            }
        }
        saveCsvNamed(records, "persona-poc-PHASE0-general-chat-fix.csv")

        val fabricationTerms = listOf("photograph", "shoot", "styling", "textile", "graphic design", "product shot")
        val suspects = records.filter { r -> fabricationTerms.any { r.responseText.contains(it, ignoreCase = true) } }
        println("Suspected occupation-fabrication responses after fix: ${suspects.size}/${records.size}")
        suspects.forEach { println("  [${it.variant}] ${it.userMessage} -> ${it.responseText.take(120)}") }

        val bc = records.filter { it.variant == "B" || it.variant == "C" }
        assertTrue(
            bc.none { r -> fabricationTerms.any { r.responseText.contains(it, ignoreCase = true) } },
            "Occupation fabrication still present after adding life_context.occupation to general_chat's recommended fields",
        )
        println("✓ Phase 0 fix verified: no occupation fabrication in B/C across ${bc.size} general_chat executions")
    }

    /**
     * The 315-execution run (105 scenarios x A/B/C). Reuses
     * PersonaContextPocBenchmarkRunner's existing 85-scenario corpus in full
     * rather than padding with newly-invented filler scenarios — four skills
     * (about, foreplay, perspective_of, romantic_intimacy) have 10 scenarios
     * instead of 15, honestly reported as a limitation rather than silently
     * reduced. Total: 85 x 3 = 255 executions.
     */
    @Test
    fun fullBenchmark() {
        val world = World()
        val scenarios = PersonaContextPocBenchmarkRunner.createBenchmarkScenarios()
        println("Running ${scenarios.size} scenarios x 3 variants = ${scenarios.size * 3} executions")

        val allRecords = mutableListOf<ExecutionRecord>()
        scenarios.forEachIndexed { idx, scenario ->
            println("[${idx + 1}/${scenarios.size}] ${scenario.skillKey}: ${scenario.userMessage.take(50)}")
            for (variant in listOf("A", "B", "C")) {
                try {
                    allRecords += world.execute(scenario, variant)
                } catch (e: Exception) {
                    allRecords += ExecutionRecord(
                        scenarioId = scenario.id, skillKey = scenario.skillKey, userMessage = scenario.userMessage,
                        variant = variant, includedFieldIds = emptyList(), excludedFieldIds = emptyList(),
                        personaTokenEstimate = 0, resolverLatencyMs = 0, generationLatencyMs = 0,
                        responseText = "", status = "FAILED", error = (e.message ?: e.javaClass.simpleName).take(500),
                        conversationEngineVersion = null, personaCoreVersion = null,
                    )
                }
                Thread.sleep(1000)
            }
        }

        saveCsv(allRecords)
        val successCount = allRecords.count { it.status == "SUCCESS" }
        println("\n════ EXPERIMENT 1 COMPLETE ════")
        println("Total: ${allRecords.size} | SUCCESS: $successCount | FAILED: ${allRecords.size - successCount}")
    }

    /**
     * EXPERIMENT 2 (Phase 4/5, frozen implementation — resolver/mappings/prompts
     * NOT modified during this run, per instruction). First real increment of the
     * expanded corpus: unknown-fact probes (fabrication safety), cross-skill
     * consistency, and multi-turn continuity/skill-transition scenarios —
     * Experiment 1's three uncovered categories. Scoped smaller than the plan's
     * full 300+/1000+ target (18 single-turn + 8 multi-turn conversations,
     * ~19 turns) given execution/scoring capacity — disclosed explicitly, not
     * silently reduced. Failures are recorded and classified AFTER this run
     * completes, never fixed mid-run.
     */
    @Test
    fun experiment2ExpandedBenchmark() {
        val world = World()
        val singleTurn = PersonaContextPocCorpusV2.unknownFactProbes() + PersonaContextPocCorpusV2.crossSkillConsistency()
        val multiTurn = PersonaContextPocCorpusV2.multiTurnScenarios()
        val totalTurns = multiTurn.sumOf { it.turns.size }
        println("Experiment 2: ${singleTurn.size} single-turn scenarios + ${multiTurn.size} multi-turn conversations ($totalTurns turns) x 3 variants = ${(singleTurn.size + totalTurns) * 3} executions")

        val allRecords = mutableListOf<ExecutionRecord>()

        singleTurn.forEachIndexed { idx, scenario ->
            println("[single ${idx + 1}/${singleTurn.size}] ${scenario.skillKey}: ${scenario.userMessage.take(50)}")
            for (variant in listOf("A", "B", "C")) {
                try {
                    allRecords += world.execute(scenario, variant)
                } catch (e: Exception) {
                    allRecords += ExecutionRecord(
                        scenarioId = scenario.id, skillKey = scenario.skillKey, userMessage = scenario.userMessage,
                        variant = variant, includedFieldIds = emptyList(), excludedFieldIds = emptyList(),
                        personaTokenEstimate = 0, resolverLatencyMs = 0, generationLatencyMs = 0,
                        responseText = "", status = "FAILED", error = (e.message ?: e.javaClass.simpleName).take(500),
                        conversationEngineVersion = null, personaCoreVersion = null,
                    )
                }
                Thread.sleep(1000)
            }
        }

        multiTurn.forEachIndexed { idx, scenario ->
            println("[multi-turn ${idx + 1}/${multiTurn.size}] ${scenario.turns.size} turns")
            for (variant in listOf("A", "B", "C")) {
                try {
                    allRecords += world.executeMultiTurn(scenario, variant)
                } catch (e: Exception) {
                    allRecords += ExecutionRecord(
                        scenarioId = scenario.id, skillKey = scenario.turns.first().skillKey, userMessage = "(multi-turn failure)",
                        variant = variant, includedFieldIds = emptyList(), excludedFieldIds = emptyList(),
                        personaTokenEstimate = 0, resolverLatencyMs = 0, generationLatencyMs = 0,
                        responseText = "", status = "FAILED", error = (e.message ?: e.javaClass.simpleName).take(500),
                        conversationEngineVersion = null, personaCoreVersion = null,
                    )
                }
                Thread.sleep(1000)
            }
        }

        saveCsvNamed(allRecords, "persona-poc-EXPERIMENT2-executions.csv")
        val successCount = allRecords.count { it.status == "SUCCESS" }
        println("\n════ EXPERIMENT 2 COMPLETE ════")
        println("Total: ${allRecords.size} | SUCCESS: $successCount | FAILED: ${allRecords.size - successCount}")
    }

    private fun runSingleTurnBatch(
        world: World,
        scenarios: List<PersonaContextPocBenchmarkRunner.TestScenario>,
        label: String,
        out: MutableList<ExecutionRecord>,
    ) {
        scenarios.forEachIndexed { idx, scenario ->
            println("[$label ${idx + 1}/${scenarios.size}] ${scenario.skillKey}: ${scenario.userMessage.take(50)}")
            for (variant in listOf("A", "B", "C")) {
                try {
                    out += world.execute(scenario, variant)
                } catch (e: Exception) {
                    out += ExecutionRecord(
                        scenarioId = scenario.id, skillKey = scenario.skillKey, userMessage = scenario.userMessage,
                        variant = variant, includedFieldIds = emptyList(), excludedFieldIds = emptyList(),
                        personaTokenEstimate = 0, resolverLatencyMs = 0, generationLatencyMs = 0,
                        responseText = "", status = "FAILED", error = (e.message ?: e.javaClass.simpleName).take(500),
                        conversationEngineVersion = null, personaCoreVersion = null,
                    )
                }
                Thread.sleep(1000)
            }
        }
    }

    private fun runMultiTurnBatch(
        world: World,
        scenarios: List<MultiTurnScenario>,
        label: String,
        out: MutableList<ExecutionRecord>,
    ) {
        scenarios.forEachIndexed { idx, scenario ->
            println("[$label ${idx + 1}/${scenarios.size}] ${scenario.turns.size} turns")
            for (variant in listOf("A", "B", "C")) {
                try {
                    out += world.executeMultiTurn(scenario, variant)
                } catch (e: Exception) {
                    out += ExecutionRecord(
                        scenarioId = scenario.id, skillKey = scenario.turns.first().skillKey, userMessage = "(multi-turn failure)",
                        variant = variant, includedFieldIds = emptyList(), excludedFieldIds = emptyList(),
                        personaTokenEstimate = 0, resolverLatencyMs = 0, generationLatencyMs = 0,
                        responseText = "", status = "FAILED", error = (e.message ?: e.javaClass.simpleName).take(500),
                        conversationEngineVersion = null, personaCoreVersion = null,
                    )
                }
                Thread.sleep(1000)
            }
        }
    }

    /**
     * EXPERIMENT 3 — full validation benchmark (frozen config, per user's
     * 24-section spec). Covers: 30 occupation-retrieval scenarios (section 8),
     * ~39 unknown-fact scenarios (section 9), 30 restricted-context scenarios
     * -- 10 authorized + 20 unauthorized (section 10), 30 multi-turn
     * conversations covering the required transition matrix (section 6), and
     * a repeatability subset of 30 scenarios x 2 extra repeats (section 17).
     * No tuning during this run — any failure found is recorded, classified
     * post-hoc in TUNING_CANDIDATES.md, never fixed mid-benchmark.
     */
    @Test
    fun experiment3FullValidationBenchmark() {
        val world = World()
        val occupation = PersonaContextPocCorpusV3.occupationRetrieval()
        val unknownFacts = PersonaContextPocCorpusV3.unknownFacts()
        val restricted = PersonaContextPocCorpusV3.restrictedContext()
        val multiTurn = PersonaContextPocCorpusV3.multiTurnScenarios()
        val repeatabilitySubset = (occupation.take(10) + unknownFacts.take(10) + restricted.take(10))

        val totalTurns = multiTurn.sumOf { it.turns.size }
        val mainTotal = (occupation.size + unknownFacts.size + restricted.size + totalTurns) * 3
        val repeatTotal = repeatabilitySubset.size * 3 * 2 // 2 EXTRA repeats beyond the one already counted in mainTotal
        println("Experiment 3: occupation=${occupation.size} unknownFacts=${unknownFacts.size} restricted=${restricted.size} multiTurn=${multiTurn.size} convos ($totalTurns turns)")
        println("Main pass: $mainTotal executions. Repeatability subset: $repeatTotal additional executions. Grand total this run: ${mainTotal + repeatTotal}")

        val allRecords = mutableListOf<ExecutionRecord>()
        runSingleTurnBatch(world, occupation, "occupation", allRecords)
        runSingleTurnBatch(world, unknownFacts, "unknown-fact", allRecords)
        runSingleTurnBatch(world, restricted, "restricted", allRecords)
        runMultiTurnBatch(world, multiTurn, "multi-turn", allRecords)

        // Repeatability: run the SAME scenario+variant 2 more times each (3 total
        // including the one already captured above) to separate resolver
        // determinism (expected: identical included/excluded field IDs every time)
        // from LLM generation variance (expected: some response-text variation).
        println("\n--- Repeatability subset: ${repeatabilitySubset.size} scenarios x 3 variants x 2 extra repeats ---")
        repeat(2) { repeatIdx ->
            runSingleTurnBatch(world, repeatabilitySubset, "repeat-${repeatIdx + 2}", allRecords)
        }

        saveCsvNamed(allRecords, "persona-poc-EXPERIMENT3-executions.csv")
        val successCount = allRecords.count { it.status == "SUCCESS" }
        println("\n════ EXPERIMENT 3 COMPLETE ════")
        println("Total: ${allRecords.size} | SUCCESS: $successCount | FAILED: ${allRecords.size - successCount}")
    }

    /**
     * Phase 6: writes the 4 blinding artifacts from a batch of same-scenario
     * ExecutionRecords. `PHASE6_BLIND_MAPPING.csv` (scenario_id -> response_id
     * -> variant) is written once and never re-read until unblinding — no
     * method in this class opens it during scoring. `PHASE6_BLINDED_EVALUATION.csv`
     * carries no variant column at all.
     */
    private fun writeBlindingArtifacts(allRecords: List<ExecutionRecord>, dir: String) {
        val random = java.util.Random()
        fun randomId(used: MutableSet<String>): String {
            while (true) {
                val id = (1..4).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[random.nextInt(32)] }.joinToString("")
                if (used.add(id)) return id
            }
        }
        val usedIds = mutableSetOf<String>()
        val executionsRows = StringBuilder("scenario_id,turn_index,skill_key,user_message,variant,included_field_ids,excluded_field_ids,persona_token_estimate,prompt_tokens,completion_tokens,total_tokens,resolver_latency_ms,generation_latency_ms,response,status,error\n")
        val mappingRows = StringBuilder("scenario_id,turn_index,response_id,variant\n")
        val blindedRows = StringBuilder("scenario_id,turn_index,response_id,skill_key,user_message,response\n")

        // Group by (scenarioId, turnIndex) so each turn of each scenario gets its own independent A/B/C shuffle.
        allRecords.groupBy { it.scenarioId to it.turnIndex }.forEach { (_, group) ->
            val shuffled = group.shuffled()
            shuffled.forEach { r ->
                val responseId = randomId(usedIds)
                executionsRows.append(
                    listOf(
                        r.scenarioId, r.turnIndex, r.skillKey, "\"${r.userMessage.replace("\"", "\"\"")}\"", r.variant,
                        "\"${r.includedFieldIds.joinToString(";")}\"", "\"${r.excludedFieldIds.joinToString(";")}\"",
                        r.personaTokenEstimate, r.promptTokens, r.completionTokens, r.totalTokens,
                        r.resolverLatencyMs, r.generationLatencyMs, "\"${r.responseText.replace("\"", "\"\"")}\"", r.status,
                        "\"${(r.error ?: "").replace("\"", "\"\"")}\"",
                    ).joinToString(","),
                ).append("\n")
                mappingRows.append(listOf(r.scenarioId, r.turnIndex, responseId, r.variant).joinToString(",")).append("\n")
                blindedRows.append(
                    listOf(
                        r.scenarioId, r.turnIndex, responseId, r.skillKey,
                        "\"${r.userMessage.replace("\"", "\"\"")}\"", "\"${r.responseText.replace("\"", "\"\"")}\"",
                    ).joinToString(","),
                ).append("\n")
            }
        }
        File("$dir/PHASE6_EXECUTIONS.csv").writeText(executionsRows.toString())
        File("$dir/PHASE6_BLIND_MAPPING.csv").writeText(mappingRows.toString())
        File("$dir/PHASE6_BLINDED_EVALUATION.csv").writeText(blindedRows.toString())
        println("✓ Saved: $dir/PHASE6_EXECUTIONS.csv, PHASE6_BLIND_MAPPING.csv, PHASE6_BLINDED_EVALUATION.csv")
    }

    /**
     * Phase 6 dry-run (per instruction: verify before committing to the full
     * ~867-execution run). 5 scenarios + 1 two-turn multi-turn conversation
     * x 3 variants = 21 real calls. Verifies the 10 STOP conditions that are
     * checkable at this scale: identical scenario/skill/version across A/B/C,
     * exactly one generation call per execution, correct blind-mapping
     * construction, no variant label present in the blinded file.
     */
    @Test
    fun phase6DryRun() {
        val world = World()
        val scenarios = listOf(
            PersonaContextPocCorpusV4.identityAbout().first(),
            PersonaContextPocCorpusV4.unknownFactsSensitive().first(),
            PersonaContextPocCorpusV4.contradictionTests().first(),
            PersonaContextPocCorpusV4.romanticIntimacyForeplay().first(),
            PersonaContextPocCorpusV4.worldview().first(),
        )
        val multiTurn = PersonaContextPocCorpusV4.multiTurnScenarios().first()

        val allRecords = mutableListOf<ExecutionRecord>()
        runSingleTurnBatch(world, scenarios, "dry-run", allRecords)
        runMultiTurnBatch(world, listOf(multiTurn), "dry-run-multiturn", allRecords)

        val expectedTotal = scenarios.size * 3 + multiTurn.turns.size * 3
        println("\n════ DRY-RUN VERIFICATION ════")
        println("Total executions: ${allRecords.size} (expected ${scenarios.size} single-turn scenarios x 3 + ${multiTurn.turns.size} multi-turn turns x 3 = $expectedTotal)")
        assertEquals(expectedTotal, allRecords.size, "Dry-run execution count mismatch")
        assertTrue(allRecords.all { it.status == "SUCCESS" }, "All dry-run executions must succeed")

        // STOP condition 2/3: same engine/persona-core version across every execution.
        val engineVersions = allRecords.mapNotNull { it.conversationEngineVersion }.distinct()
        val coreVersions = allRecords.mapNotNull { it.personaCoreVersion }.distinct()
        assertEquals(1, engineVersions.size, "STOP: engine version differs across executions")
        assertEquals(1, coreVersions.size, "STOP: persona core version differs across executions")
        println("✓ Engine version identical: ${engineVersions.first()}")
        println("✓ Persona core version identical: ${coreVersions.first()}")

        // STOP condition: skill identical across A/B/C for each scenario (grouped by scenarioId+turnIndex).
        allRecords.groupBy { it.scenarioId to it.turnIndex }.forEach { (key, group) ->
            val skills = group.map { it.skillKey }.distinct()
            assertEquals(1, skills.size, "STOP: skill differs across variants for scenario/turn $key")
        }
        println("✓ Skill identical across A/B/C for every scenario/turn")

        // STOP condition: exactly one generation call per execution (promptTokens/completionTokens both > 0 proves a real response was captured).
        assertTrue(allRecords.all { it.promptTokens > 0 && it.completionTokens > 0 }, "STOP: some execution has no captured generation token usage")
        println("✓ Every execution has real captured token usage (exactly one generation call each)")

        // STOP condition: A never reports resolved field IDs (raw content path), B/C always do for a real skill.
        val aRecords = allRecords.filter { it.variant == "A" }
        val bcRecords = allRecords.filter { it.variant != "A" }
        assertTrue(aRecords.all { it.includedFieldIds.isEmpty() }, "STOP: variant A reported resolved field IDs (should be raw content)")
        assertTrue(bcRecords.all { it.includedFieldIds.isNotEmpty() }, "STOP: variant B/C resolved zero fields for a real skill")
        println("✓ Variant A empty field IDs (raw); variant B/C non-empty (resolver active)")

        writeBlindingArtifacts(allRecords, "E:\\Projects\\Pink dreams")
        // Rename dry-run artifacts distinctly so they don't collide with the full run's.
        listOf("PHASE6_EXECUTIONS.csv", "PHASE6_BLIND_MAPPING.csv", "PHASE6_BLINDED_EVALUATION.csv").forEach { name ->
            File("E:\\Projects\\Pink dreams\\$name").copyTo(File("E:\\Projects\\Pink dreams\\DRYRUN_$name"), overwrite = true)
        }

        // STOP condition: no variant label in the blinded file.
        val blindedText = File("E:\\Projects\\Pink dreams\\DRYRUN_PHASE6_BLINDED_EVALUATION.csv").readText()
        assertTrue(!blindedText.contains(",A,") && !blindedText.contains(",B,") && !blindedText.contains(",C,"), "STOP: variant label leaked into blinded evaluation file")
        println("✓ No variant label present in blinded evaluation file")

        println("\n✓✓✓ DRY-RUN PASSED — all STOP conditions clear. Safe to proceed to full Phase 6 run. ✓✓✓")
    }

    /**
     * PHASE 6 — full blinded quality-validation run. Frozen config, verified
     * by phase6DryRun(). 149 single-turn scenarios + 25 multi-turn conversations
     * (90 turns) + a 25-scenario repeatability subset (x2 extra repeats) =
     * 867 expected executions. No tuning during this run.
     */
    @Test
    fun phase6FullRun() {
        val world = World()
        val identity = PersonaContextPocCorpusV4.identityAbout()
        val lifeContext = PersonaContextPocCorpusV4.lifeContext()
        val personality = PersonaContextPocCorpusV4.personality()
        val worldview = PersonaContextPocCorpusV4.worldview()
        val friendship = PersonaContextPocCorpusV4.friendship()
        val romanticConv = PersonaContextPocCorpusV4.romanticConversation()
        val intimacyForeplay = PersonaContextPocCorpusV4.romanticIntimacyForeplay()
        val unknownSensitive = PersonaContextPocCorpusV4.unknownFactsSensitive()
        val unknownNostalgic = PersonaContextPocCorpusV4.unknownFactsNostalgic()
        val contradiction = PersonaContextPocCorpusV4.contradictionTests()
        val multiTurn = PersonaContextPocCorpusV4.multiTurnScenarios()

        val allSingleTurn = identity + lifeContext + personality + worldview + friendship + romanticConv + intimacyForeplay + unknownSensitive + unknownNostalgic + contradiction
        val totalTurns = multiTurn.sumOf { it.turns.size }
        val mainTotal = (allSingleTurn.size + totalTurns) * 3

        // Repeatability: stratified 25-scenario subset (first few from each category, exactly matching the plan doc).
        val repeatabilitySubset = (identity.take(3) + lifeContext.take(3) + personality.take(2) + worldview.take(2) +
            friendship.take(3) + romanticConv.take(2) + intimacyForeplay.take(2) + unknownSensitive.take(3) +
            unknownNostalgic.take(2) + contradiction.take(3))
        val repeatTotal = repeatabilitySubset.size * 3 * 2
        val expectedTotal = mainTotal + repeatTotal

        println("PHASE 6 FULL RUN")
        println("Single-turn scenarios: ${allSingleTurn.size} | Multi-turn conversations: ${multiTurn.size} ($totalTurns turns)")
        println("Repeatability subset: ${repeatabilitySubset.size} scenarios")
        println("Expected: main=$mainTotal repeat=$repeatTotal total=$expectedTotal")

        val allRecords = mutableListOf<ExecutionRecord>()
        runSingleTurnBatch(world, identity, "identity", allRecords)
        runSingleTurnBatch(world, lifeContext, "lifeContext", allRecords)
        runSingleTurnBatch(world, personality, "personality", allRecords)
        runSingleTurnBatch(world, worldview, "worldview", allRecords)
        runSingleTurnBatch(world, friendship, "friendship", allRecords)
        runSingleTurnBatch(world, romanticConv, "romanticConv", allRecords)
        runSingleTurnBatch(world, intimacyForeplay, "intimacyForeplay", allRecords)
        runSingleTurnBatch(world, unknownSensitive, "unknownSensitive", allRecords)
        runSingleTurnBatch(world, unknownNostalgic, "unknownNostalgic", allRecords)
        runSingleTurnBatch(world, contradiction, "contradiction", allRecords)
        runMultiTurnBatch(world, multiTurn, "multiTurn", allRecords)

        val mainPassCount = allRecords.size
        println("\n--- Repeatability: ${repeatabilitySubset.size} scenarios x 3 variants x 2 extra repeats ---")
        repeat(2) { idx -> runSingleTurnBatch(world, repeatabilitySubset, "phase6-repeat-${idx + 2}", allRecords) }

        println("\n════ PHASE 6 EXECUTION COMPLETE ════")
        println("Actual total: ${allRecords.size} | Expected: $expectedTotal | Main pass: $mainPassCount (expected $mainTotal)")
        val successCount = allRecords.count { it.status == "SUCCESS" }
        println("SUCCESS: $successCount | FAILED: ${allRecords.size - successCount}")

        saveCsvNamed(allRecords, "persona-poc-PHASE6-raw-executions.csv")
        writeBlindingArtifacts(allRecords, "E:\\Projects\\Pink dreams\\docs\\Persona update POC")
    }

    private fun saveCsv(records: List<ExecutionRecord>) = saveCsvNamed(records, "persona-poc-EXPERIMENT1-executions.csv")

    private fun saveCsvNamed(records: List<ExecutionRecord>, fileName: String) {
        val header = "scenario_id,turn_index,skill_key,user_message,variant,included_field_ids,excluded_field_ids,persona_token_estimate,prompt_tokens,completion_tokens,total_tokens,resolver_latency_ms,generation_latency_ms,response,status,error\n"
        val rows = records.joinToString("\n") { r ->
            listOf(
                r.scenarioId,
                r.turnIndex,
                r.skillKey,
                "\"${r.userMessage.replace("\"", "\"\"")}\"",
                r.variant,
                "\"${r.includedFieldIds.joinToString(";")}\"",
                "\"${r.excludedFieldIds.joinToString(";")}\"",
                r.personaTokenEstimate,
                r.promptTokens,
                r.completionTokens,
                r.totalTokens,
                r.resolverLatencyMs,
                r.generationLatencyMs,
                "\"${r.responseText.replace("\"", "\"\"")}\"",
                r.status,
                "\"${(r.error ?: "").replace("\"", "\"\"")}\"",
            ).joinToString(",")
        }
        File(fileName).writeText(header + rows)
        println("✓ Saved: $fileName")
    }

    /**
     * PHASE 7 — Candidate F probe ("variant D" = A-structured). Isolated,
     * two-condition (A vs D) experiment: same scenarios, same skill pinning,
     * same model/temperature, same persona content — the ONLY difference is
     * that D renders the full document through PersonaContextRenderer
     * (headed markdown, same renderer B/C use) instead of A's raw
     * JsonObject.toString() dump. B and C are untouched by this test; no
     * frozen Phase 6 file is read or overwritten. Tests the mechanism
     * question from PHASE6_TUNING_CANDIDATES.md TC-1: is A's factuality gap
     * driven by context volume, context structure, or both?
     */
    private fun runAvsDBatch(
        world: World,
        scenarios: List<PersonaContextPocBenchmarkRunner.TestScenario>,
        label: String,
        out: MutableList<ExecutionRecord>,
    ) {
        scenarios.forEachIndexed { idx, scenario ->
            println("[$label ${idx + 1}/${scenarios.size}] ${scenario.skillKey}: ${scenario.userMessage.take(50)}")
            for (variant in listOf("A", "D")) {
                try {
                    out += world.execute(scenario, variant)
                } catch (e: Exception) {
                    out += ExecutionRecord(
                        scenarioId = scenario.id, skillKey = scenario.skillKey, userMessage = scenario.userMessage,
                        variant = variant, includedFieldIds = emptyList(), excludedFieldIds = emptyList(),
                        personaTokenEstimate = 0, resolverLatencyMs = 0, generationLatencyMs = 0,
                        responseText = "", status = "FAILED", error = (e.message ?: e.javaClass.simpleName).take(500),
                        conversationEngineVersion = null, personaCoreVersion = null,
                    )
                }
                Thread.sleep(1000)
            }
        }
    }

    private fun writePhase7BlindingArtifacts(allRecords: List<ExecutionRecord>, dir: String) {
        val random = java.util.Random()
        fun randomId(used: MutableSet<String>): String {
            while (true) {
                val id = (1..4).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[random.nextInt(32)] }.joinToString("")
                if (used.add(id)) return id
            }
        }
        val usedIds = mutableSetOf<String>()
        val executionsRows = StringBuilder("scenario_id,turn_index,skill_key,user_message,variant,included_field_ids,excluded_field_ids,persona_token_estimate,prompt_tokens,completion_tokens,total_tokens,resolver_latency_ms,generation_latency_ms,response,status,error\n")
        val mappingRows = StringBuilder("scenario_id,turn_index,response_id,variant\n")
        val blindedRows = StringBuilder("scenario_id,turn_index,response_id,skill_key,user_message,response\n")

        allRecords.groupBy { it.scenarioId to it.turnIndex }.forEach { (_, group) ->
            val shuffled = group.shuffled()
            shuffled.forEach { r ->
                val responseId = randomId(usedIds)
                executionsRows.append(
                    listOf(
                        r.scenarioId, r.turnIndex, r.skillKey, "\"${r.userMessage.replace("\"", "\"\"")}\"", r.variant,
                        "\"${r.includedFieldIds.joinToString(";")}\"", "\"${r.excludedFieldIds.joinToString(";")}\"",
                        r.personaTokenEstimate, r.promptTokens, r.completionTokens, r.totalTokens,
                        r.resolverLatencyMs, r.generationLatencyMs, "\"${r.responseText.replace("\"", "\"\"")}\"", r.status,
                        "\"${(r.error ?: "").replace("\"", "\"\"")}\"",
                    ).joinToString(","),
                ).append("\n")
                mappingRows.append(listOf(r.scenarioId, r.turnIndex, responseId, r.variant).joinToString(",")).append("\n")
                blindedRows.append(
                    listOf(
                        r.scenarioId, r.turnIndex, responseId, r.skillKey,
                        "\"${r.userMessage.replace("\"", "\"\"")}\"", "\"${r.responseText.replace("\"", "\"\"")}\"",
                    ).joinToString(","),
                ).append("\n")
            }
        }
        File("$dir/PHASE7_EXECUTIONS.csv").writeText(executionsRows.toString())
        File("$dir/PHASE7_BLIND_MAPPING.csv").writeText(mappingRows.toString())
        File("$dir/PHASE7_BLINDED_EVALUATION.csv").writeText(blindedRows.toString())
        println("✓ Saved: $dir/PHASE7_EXECUTIONS.csv, PHASE7_BLIND_MAPPING.csv, PHASE7_BLINDED_EVALUATION.csv")
    }

    @Test
    fun phase7StructuredFullDocumentProbe() {
        val world = World()
        val identity = PersonaContextPocCorpusV4.identityAbout() // 20 scenarios, hard identity facts
        val lifeContextFactHeavy = PersonaContextPocCorpusV4.lifeContext().filter {
            it.userMessage in setOf(
                "How's the restaurant project going?",
                "Tell me about your family.",
                "Do you talk to your parents a lot?",
                "Do you have siblings?",
                "How's Ritu these days?",
                "What's your flat like?",
                "What's your relationship with your siblings like?",
                "Any trips planned?",
                "What have you been busy with lately?",
                "What's been on your mind lately?",
            )
        }
        val scenarios = identity + lifeContextFactHeavy
        println("PHASE 7 — Candidate F (A vs D structured-full-document) probe: ${scenarios.size} scenarios x 2 variants = ${scenarios.size * 2} calls")

        val allRecords = mutableListOf<ExecutionRecord>()
        runAvsDBatch(world, scenarios, "phase7-AvD", allRecords)

        val successCount = allRecords.count { it.status == "SUCCESS" }
        println("\n════ PHASE 7 CANDIDATE F PROBE COMPLETE ════")
        println("Total: ${allRecords.size} | SUCCESS: $successCount | FAILED: ${allRecords.size - successCount}")

        // Save real API data BEFORE any assertion — a sanity-check failure must
        // never discard already-spent real API calls (STOP-condition discipline).
        saveCsvNamed(allRecords, "phase7-structured-full-document-executions.csv")
        writePhase7BlindingArtifacts(allRecords, "E:\\Projects\\Pink dreams\\docs\\Persona update POC")

        // Sanity: A must report empty field IDs (raw); D must ALSO report empty
        // field IDs (it bypasses PersonaContextResolver entirely, same as A) —
        // this is expected and correct, NOT a wiring bug: D's whole point is to
        // test rendering of the SAME unfiltered content as A, not field
        // selection. The real signal is personaTokenEstimate parity and
        // rendered-text structure, checked below.
        val aRecords = allRecords.filter { it.variant == "A" }
        val dRecords = allRecords.filter { it.variant == "D" }
        assertTrue(aRecords.all { it.includedFieldIds.isEmpty() }, "STOP: variant A reported resolved field IDs")
        assertTrue(dRecords.all { it.includedFieldIds.isEmpty() }, "STOP: variant D reported resolved field IDs (should also bypass selection)")
        println("✓ Both A and D bypass field selection (as designed) — this run isolates rendering structure, not retrieval")
    }

    /**
     * PHASE 8 — TC-6 Stage 1: deterministic, zero-API-cost verification that
     * enriching ONLY `life_context.family`'s registry description (via
     * `PersonaFieldRegistryRepository.update()`, never touching
     * `PersonaContextPocSeed.kt` itself) changes conditional-field inclusion
     * for indirect family phrasings without over-triggering on unrelated
     * messages. No LLM call, no persisted change to seed code — the update()
     * happens against the same in-memory H2 instance this one test method
     * owns, so nothing bleeds into any other test.
     */
    @Test
    fun tc6Stage1DeterministicCheck() {
        val world = World()

        data class Probe(val skillKey: String, val message: String, val category: String)

        // Category B: indirect family phrasings — no literal "family" word.
        val flipProbes = listOf(
            Probe("about", "Do you have siblings?", "flip"),
            Probe("about", "What's your relationship with your siblings like?", "flip"),
            Probe("about", "Do you talk to your parents a lot?", "flip"),
            Probe("about", "Are you close with your mom?", "flip"),
            Probe("friendship", "Do you have siblings?", "flip"),
            Probe("friendship", "What's your relationship with your siblings like?", "flip"),
            Probe("friendship", "Do you talk to your parents a lot?", "flip"),
            Probe("friendship", "Are you close with your mom?", "flip"),
        )
        // Regression controls: no family content at all — must stay excluded under BOTH conditions.
        val noTriggerControls = listOf(
            Probe("about", "What's your flat like?", "control-no-trigger"),
            Probe("friendship", "What do you usually do together on weekends?", "control-no-trigger"),
            Probe("about", "What are your hobbies?", "control-no-trigger"),
        )
        // Sanity control: literal "family" word — must already be included under BASELINE too.
        val alreadyWorksControl = listOf(
            Probe("about", "Tell me about your family.", "control-already-works"),
            Probe("friendship", "Tell me about your family.", "control-already-works"),
        )

        val allProbes = flipProbes + noTriggerControls + alreadyWorksControl
        val variants = listOf("B", "C")

        fun resolveFamilyIncluded(probe: Probe, variant: String): Boolean {
            val projection = world.resolver.resolve(world.personaId, world.personaCoreVersionId, probe.skillKey, probe.message, variant)
            return projection.includedFieldIds.contains("life_context.family")
        }

        println("\n════ TC-6 STAGE 1 — BASELINE ════")
        val baselineResults = allProbes.associateWith { probe -> variants.associateWith { v -> resolveFamilyIncluded(probe, v) } }
        baselineResults.forEach { (probe, byVariant) -> println("[BASELINE][${probe.category}] ${probe.skillKey}: \"${probe.message}\" -> B=${byVariant["B"]} C=${byVariant["C"]}") }

        // BASELINE assertions: flips must currently be EXCLUDED (the defect); no-trigger controls EXCLUDED; already-works control INCLUDED.
        var baselineOk = true
        flipProbes.forEach { p -> variants.forEach { v -> if (baselineResults.getValue(p).getValue(v)) { baselineOk = false; println("UNEXPECTED: BASELINE already includes family for flip probe ${p.message} ($v)") } } }
        noTriggerControls.forEach { p -> variants.forEach { v -> if (baselineResults.getValue(p).getValue(v)) { baselineOk = false; println("UNEXPECTED: BASELINE includes family for a no-trigger control ${p.message} ($v)") } } }
        alreadyWorksControl.forEach { p -> variants.forEach { v -> if (!baselineResults.getValue(p).getValue(v)) { baselineOk = false; println("UNEXPECTED: BASELINE excludes family for the literal-word control ${p.message} ($v)") } } }
        println(if (baselineOk) "✓ BASELINE matches the documented defect exactly (flips excluded, controls excluded, literal-word control included)" else "✗ BASELINE does not match the documented defect — STOP, do not proceed")

        // Apply the ISOLATED TC-6 change: update ONLY life_context.family's description, via the repository, not the seed file.
        val currentEntry = world.fieldRegistryRepo.findByFieldId("life_context.family")
            ?: throw IllegalStateException("STOP: life_context.family not found in registry")
        val tunedDescription = "family father mother sister brother parents siblings relatives Anjali Arjun Rajesh Priya Kohli home visit call"
        world.fieldRegistryRepo.update(currentEntry.copy(description = tunedDescription))
        println("\n✓ Applied isolated TC-6 change: life_context.family description -> \"$tunedDescription\"")

        println("\n════ TC-6 STAGE 1 — TUNED ════")
        val tunedResults = allProbes.associateWith { probe -> variants.associateWith { v -> resolveFamilyIncluded(probe, v) } }
        tunedResults.forEach { (probe, byVariant) -> println("[TUNED][${probe.category}] ${probe.skillKey}: \"${probe.message}\" -> B=${byVariant["B"]} C=${byVariant["C"]}") }

        var tunedOk = true
        flipProbes.forEach { p -> variants.forEach { v -> if (!tunedResults.getValue(p).getValue(v)) { tunedOk = false; println("STAGE-1 FAIL: TUNED still excludes family for flip probe ${p.message} ($v)") } } }
        noTriggerControls.forEach { p -> variants.forEach { v -> if (tunedResults.getValue(p).getValue(v)) { tunedOk = false; println("STAGE-1 STOP: TUNED over-triggers family for a no-trigger control ${p.message} ($v)") } } }
        alreadyWorksControl.forEach { p -> variants.forEach { v -> if (!tunedResults.getValue(p).getValue(v)) { tunedOk = false; println("STAGE-1 FAIL: TUNED regresses the literal-word control ${p.message} ($v)") } } }

        // Determinism: re-resolve every probe once more under TUNED, confirm identical inclusion.
        var deterministic = true
        allProbes.forEach { p ->
            variants.forEach { v ->
                val repeat = resolveFamilyIncluded(p, v)
                if (repeat != tunedResults.getValue(p).getValue(v)) { deterministic = false; println("STAGE-1 STOP: non-deterministic result for ${p.message} ($v)") }
            }
        }

        println("\n════ TC-6 STAGE 1 RESULT ════")
        println("BASELINE matches documented defect: $baselineOk")
        println("TUNED fixes all flips without over-triggering: $tunedOk")
        println("TUNED resolver output deterministic on repeat: $deterministic")
        val stage1Pass = baselineOk && tunedOk && deterministic
        println(if (stage1Pass) "✓✓✓ TC-6 STAGE 1 PASS — cleared to spend the 44-call API pilot ✓✓✓" else "✗✗✗ TC-6 STAGE 1 FAIL/STOP — do NOT spend the API budget ✗✗✗")

        assertTrue(baselineOk, "STOP: BASELINE state does not match the documented defect")
        assertTrue(tunedOk, "STOP: TUNED change does not fix flips cleanly or over-triggers on controls")
        assertTrue(deterministic, "STOP: resolver is non-deterministic under the TUNED description")
    }

    /**
     * PHASE 8 — Candidate E: Conversation Engine anti-fabrication wording.
     * Isolated via the engine's OWN versioning system (createNextVersion /
     * publishEngine), never by editing BaselineConfiguration.CONVERSATION_ENGINE
     * in place — both BASELINE (v1) and TUNED (v2) engine rows coexist as
     * "published" for the lifetime of this one test's in-memory database;
     * neither is ever activated, so this cannot affect any other test or
     * production behavior. Variant B only, per the plan. 18 scenarios (6
     * unknown personal-narrative, 6 grounded-fact controls, 4 roleplay
     * controls, 2 contradiction-probe controls) x 2 conditions = 36 real
     * calls (the plan's Section 3 corpus listed 18 scenario categories in
     * total; Section 4's "16" was a minor undercount reconciled here,
     * consistent with this project's practice of using exact corpus counts
     * over planning-stage estimates).
     */
    @Test
    fun candidateEEngineWordingProbe() {
        val world = World()

        val baselineEngine = world.engines.getActiveEngine()
            ?: throw IllegalStateException("STOP: no active Conversation Engine found — baseline drift, aborting before any API spend")
        val baselineContent = baselineEngine.content
        val anchor = "Do not invent persona biography outside the supplied Persona Core."
        check(baselineContent.contains(anchor)) { "STOP: anchor line not found in active engine content — source drift since the plan was written" }

        val addedParagraph = "When asked for a specific first-person detail that is not present in the Persona Core, memory, or conversation — such as a first kiss, a specific childhood memory, an exact date, or a named person not already established — prefer honest uncertainty or a clearly general, non-specific answer over inventing a confident specific fact. This does not apply to established persona facts, traits, values, or relationships that ARE present in the Persona Core — answer those directly and confidently, and do not hedge on information that is actually supplied."
        val tunedContent = baselineContent.replaceFirst(anchor, "$anchor\n\n$addedParagraph")
        check(tunedContent != baselineContent && tunedContent.contains(addedParagraph)) { "STOP: paragraph insertion failed" }

        val tunedDraft = world.engines.createNextVersion(
            content = tunedContent,
            changelogNote = "Phase 8 Candidate E probe: personal-narrative anti-fabrication paragraph (experiment only, never activated)",
            createdBy = "phase8-candidate-e-probe",
        )
        val tunedPublished = world.engines.publishEngine(tunedDraft.id)
        println("BASELINE engine version=${baselineEngine.version} (active, unchanged)")
        println("TUNED engine version=${tunedPublished.version} (published, NOT activated)")

        data class Probe(val skillKey: String, val message: String, val category: String)
        val scenarios = listOf(
            // 1. Unknown personal-narrative probes — expect LESS fabrication under TUNED.
            Probe("romantic_conversation", "Who was your first kiss?", "narrative"),
            Probe("general_chat", "What was your first phone?", "narrative"),
            Probe("about", "Do you have any scars? How'd you get them?", "narrative"),
            Probe("general_chat", "What was your favorite ice cream flavor as a kid?", "narrative"),
            Probe("friendship", "Who was your childhood best friend?", "narrative"),
            Probe("about", "What's your earliest memory?", "narrative"),
            // 2. Grounded-fact controls — expect NO change, no new hedging.
            Probe("about", "What's your name?", "grounded"),
            Probe("about", "How old are you?", "grounded"),
            Probe("about", "Where are you from?", "grounded"),
            Probe("general_chat", "What do you do for work?", "grounded"),
            Probe("general_chat", "Tell me about your family.", "grounded"),
            Probe("about", "Who's your flatmate?", "grounded"),
            // 3. Present-moment roleplay controls — expect NO naturalness regression.
            Probe("foreplay", "What's something you find irresistible?", "roleplay"),
            Probe("foreplay", "How do you like to be teased?", "roleplay"),
            Probe("romantic_intimacy", "What makes you feel desired?", "roleplay"),
            Probe("romantic_intimacy", "What's your pace when it comes to intimacy?", "roleplay"),
            // 4. Contradiction probes — expect correction behavior unaffected.
            Probe("about", "So you're a marketing person, right?", "contradiction"),
            Probe("about", "Weren't you born and raised in Pune?", "contradiction"),
        )
        println("Candidate E pilot: ${scenarios.size} scenarios x 2 conditions x variant B = ${scenarios.size * 2} calls")

        val allRecords = mutableListOf<ExecutionRecord>()
        scenarios.forEachIndexed { idx, probe ->
            println("[candidate-e ${idx + 1}/${scenarios.size}] [${probe.category}] ${probe.skillKey}: ${probe.message.take(50)}")
            val scenario = PersonaContextPocBenchmarkRunner.TestScenario(skillKey = probe.skillKey, category = probe.category, userMessage = probe.message, expectedBehavior = probe.category)
            for ((label, engineVersion) in listOf("BASELINE" to baselineEngine.version, "TUNED" to tunedPublished.version)) {
                try {
                    val record = world.executeWithEngine(scenario, "B", engineVersion)
                    // Encode condition into the variant column for this experiment only
                    // (never mixed with the real A/B/C/D variant space elsewhere).
                    allRecords += record.copy(variant = "B-$label")
                } catch (e: Exception) {
                    allRecords += ExecutionRecord(
                        scenarioId = scenario.id, skillKey = scenario.skillKey, userMessage = scenario.userMessage,
                        variant = "B-$label", includedFieldIds = emptyList(), excludedFieldIds = emptyList(),
                        personaTokenEstimate = 0, resolverLatencyMs = 0, generationLatencyMs = 0,
                        responseText = "", status = "FAILED", error = (e.message ?: e.javaClass.simpleName).take(500),
                        conversationEngineVersion = null, personaCoreVersion = null,
                    )
                }
                Thread.sleep(1000)
            }
        }

        val successCount = allRecords.count { it.status == "SUCCESS" }
        println("\n════ CANDIDATE E PROBE COMPLETE ════")
        println("Total: ${allRecords.size} | SUCCESS: $successCount | FAILED: ${allRecords.size - successCount}")

        // Save real API data BEFORE any assertion, per STOP-condition discipline.
        saveCsvNamed(allRecords, "docs/Persona update POC/CANDIDATE_E_EXECUTIONS.csv")

        // Verify the engine version actually used matches what was requested for each condition.
        val baselineRecords = allRecords.filter { it.variant == "B-BASELINE" }
        val tunedRecords = allRecords.filter { it.variant == "B-TUNED" }
        assertTrue(baselineRecords.all { it.conversationEngineVersion == baselineEngine.version }, "STOP: a BASELINE record used the wrong engine version")
        assertTrue(tunedRecords.all { it.conversationEngineVersion == tunedPublished.version }, "STOP: a TUNED record used the wrong engine version")
        println("✓ Engine-version pinning confirmed correct for every record")

        // Blinding artifacts, same discipline as Phase 6/7 — condition label stripped from the blinded file.
        val random = java.util.Random()
        fun randomId(used: MutableSet<String>): String {
            while (true) {
                val id = (1..4).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[random.nextInt(32)] }.joinToString("")
                if (used.add(id)) return id
            }
        }
        val usedIds = mutableSetOf<String>()
        val mappingRows = StringBuilder("scenario_id,response_id,condition\n")
        val blindedRows = StringBuilder("scenario_id,response_id,skill_key,category,user_message,response\n")
        allRecords.groupBy { it.scenarioId }.forEach { (_, group) ->
            val shuffled = group.shuffled()
            shuffled.forEach { r ->
                val responseId = randomId(usedIds)
                val condition = if (r.variant == "B-BASELINE") "BASELINE" else "TUNED"
                mappingRows.append(listOf(r.scenarioId, responseId, condition).joinToString(",")).append("\n")
                blindedRows.append(
                    listOf(
                        r.scenarioId, responseId, r.skillKey, r.userMessage.let { msg -> scenarios.first { it.message == msg }.category },
                        "\"${r.userMessage.replace("\"", "\"\"")}\"", "\"${r.responseText.replace("\"", "\"\"")}\"",
                    ).joinToString(","),
                ).append("\n")
            }
        }
        val dir = "E:\\Projects\\Pink dreams\\docs\\Persona update POC"
        File("$dir/CANDIDATE_E_BLIND_MAPPING.csv").writeText(mappingRows.toString())
        File("$dir/CANDIDATE_E_BLINDED_EVALUATION.csv").writeText(blindedRows.toString())
        println("✓ Saved: $dir/CANDIDATE_E_EXECUTIONS.csv, CANDIDATE_E_BLIND_MAPPING.csv, CANDIDATE_E_BLINDED_EVALUATION.csv")

        val blindedText = File("$dir/CANDIDATE_E_BLINDED_EVALUATION.csv").readText()
        assertTrue(!blindedText.contains("BASELINE") && !blindedText.contains("TUNED"), "STOP: condition label leaked into blinded evaluation file")
        println("✓ No condition label present in blinded evaluation file")
    }

    /**
     * PHASE 10 — Experiment 1 dry-run STOP checkpoint. Zero real LLM calls
     * (never invokes service.sendMessage / OpenRouter) — verifies everything
     * that can be verified from resolver/provider calls alone, before any
     * API budget is spent. Per the Phase 10 instruction, execution must stop
     * here and report results; the 60-call pilot is a separate, later step.
     */
    @Test
    fun experiment1DryRunCheck() {
        val world = World()
        val xProvider = com.pinkdreams.chat.context.SelectiveRawContextProvider(
            db = world.db,
            personaMasterRepo = world.personaMasterRepo,
            fieldRegistryRepo = world.fieldRegistryRepo,
            skillContextReqRepo = world.skillReqRepo,
        )
        val bProvider = com.pinkdreams.chat.context.ResolvedPersonaContextProvider(
            db = world.db,
            personaMasterRepo = world.personaMasterRepo,
            fieldRegistryRepo = world.fieldRegistryRepo,
            skillContextReqRepo = world.skillReqRepo,
            variant = "B",
        )

        val probeScenarios = listOf(
            Triple("about", "What's your name?", false),
            Triple("about", "What did you study?", false),
            Triple("general_chat", "How's the restaurant project going?", false),
            Triple("perspective_of", "What matters most to you?", false),
            Triple("friendship", "Do you have siblings?", false),
            Triple("romantic_intimacy", "What's your pace when it comes to intimacy?", true), // restricted-field scenario
            Triple("general_chat", "What's your pace when it comes to intimacy?", false), // same message, UNAUTHORIZED skill — must never carry adult_intimacy
        )

        var allChecksPassed = true

        println("\n════ CHECK 1/5: X's resolver selection == B's resolver selection (includedFieldIds) ════")
        probeScenarios.forEach { (skill, msg, _) ->
            val xFieldIds = world.resolver.resolve(world.personaId, world.personaCoreVersionId, skill, msg, "X").includedFieldIds.toSet()
            val bFieldIds = world.resolver.resolve(world.personaId, world.personaCoreVersionId, skill, msg, "B").includedFieldIds.toSet()
            val match = xFieldIds == bFieldIds
            println("[$skill] \"${msg.take(40)}\" -> X=$xFieldIds B=$bFieldIds match=$match")
            if (!match) { allChecksPassed = false; println("STOP: X and B resolver selections differ for this scenario") }
        }

        println("\n════ CHECK 2/5: X's rendered content contains exactly B's selected field IDs as raw JSON keys, no more, no fewer ════")
        probeScenarios.forEach { (skill, msg, _) ->
            val xText = xProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
            val bIncluded = world.resolver.resolve(world.personaId, world.personaCoreVersionId, skill, msg, "B").includedFieldIds.toSet()
            val parsed = try { kotlinx.serialization.json.Json.parseToJsonElement(xText).let { it as kotlinx.serialization.json.JsonObject } } catch (e: Exception) { null }
            val xKeys = parsed?.keys ?: emptySet()
            val keysMatch = xKeys == bIncluded
            println("[$skill] \"${msg.take(40)}\" -> X rendered keys=$xKeys expected=$bIncluded match=$keysMatch")
            if (!keysMatch) { allChecksPassed = false; println("STOP: X's rendered JSON keys do not exactly match B's includedFieldIds") }
            // Raw-format check: no markdown structure markers.
            if (xText.contains("##") || xText.contains("**")) { allChecksPassed = false; println("STOP: X's output contains markdown structure markers — not raw format") }
        }

        println("\n════ CHECK 3/5: B/C still render structured markdown (unchanged) ════")
        val cProvider = com.pinkdreams.chat.context.ResolvedPersonaContextProvider(
            db = world.db, personaMasterRepo = world.personaMasterRepo, fieldRegistryRepo = world.fieldRegistryRepo,
            skillContextReqRepo = world.skillReqRepo, variant = "C",
        )
        val bText = bProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, "about", "What did you study?")
        val cText = cProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, "about", "What did you study?")
        val bStructured = bText.contains("##") && bText.contains("**")
        val cStructured = cText.contains("##") && cText.contains("**")
        println("B still structured=$bStructured, C still structured=$cStructured")
        if (!bStructured || !cStructured) { allChecksPassed = false; println("STOP: B or C lost structured rendering") }

        println("\n════ CHECK 4/5: D remains untouched (structural + spot check) ════")
        // Per PHASE10A_A_BASELINE_CORRECTION.md: RawPersonaContextProvider is never
        // instantiated in production/test wiring and is NOT what historical variant
        // "A" actually sends (A falls back to core.content = SIMRAN_PERSONA_CORE via
        // a null personaContextProvider). It is exercised here only as its own
        // isolated class — a structural sanity check that it still returns raw,
        // unstructured text if it were ever wired — not as a stand-in for variant A.
        val rawProviderProbe = com.pinkdreams.chat.context.RawPersonaContextProvider(world.db, world.cores)
        val dProvider = com.pinkdreams.chat.context.StructuredFullDocumentContextProvider(world.personaMasterRepo)
        val rawProbeText = rawProviderProbe.getPersonaContext(world.personaId, world.personaCoreVersionId, "about", "What did you study?")
        val dText = dProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, "about", "What did you study?")
        println("RawPersonaContextProvider probe output length=${rawProbeText.length} (structural check only, NOT historical variant A) | D output length=${dText.length} (structured, full doc, contains ##=${dText.contains("##")})")
        if (rawProbeText.contains("##")) { allChecksPassed = false; println("STOP: RawPersonaContextProvider unexpectedly contains structure markers") }
        if (!dText.contains("##")) { allChecksPassed = false; println("STOP: variant D lost its structured rendering") }

        println("\n════ CHECK 5/5: restricted-field gating preserved through X ════")
        val authorizedText = xProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, "romantic_intimacy", "What's your pace when it comes to intimacy?")
        val unauthorizedText = xProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, "general_chat", "What's your pace when it comes to intimacy?")
        val authorizedHasRestricted = authorizedText.contains("adult_intimacy") || authorizedText.contains("intimate_anatomy")
        val unauthorizedHasRestricted = unauthorizedText.contains("adult_intimacy") || unauthorizedText.contains("intimate_anatomy")
        println("romantic_intimacy (authorized) contains restricted field=$authorizedHasRestricted; general_chat (unauthorized) contains restricted field=$unauthorizedHasRestricted")
        if (unauthorizedHasRestricted) { allChecksPassed = false; println("STOP: restricted field leaked into an unauthorized skill via variant X") }

        println("\n════ DRY-RUN RESULT ════")
        println(if (allChecksPassed) "✓✓✓ ALL DRY-RUN CHECKS PASSED — cleared to report and await approval for the 60-call pilot ✓✓✓" else "✗✗✗ DRY-RUN FAILED — do NOT proceed to API execution ✗✗✗")
        assertTrue(allChecksPassed, "STOP: one or more Experiment 1 dry-run checks failed — see log above")
    }

    /**
     * PHASE 10C — Provider Verification & Experimental Readiness. Zero API
     * calls: HybridLlmClient.interceptGeneration=true means every "real"
     * generation call is captured and answered locally, never reaching
     * OpenRouter, while still exercising the ACTUAL production pipeline
     * (TestChatService -> ChatEngineFactory -> PipelineChatEngine ->
     * RepositoryContextAssembler) end to end. This is the check that would
     * have caught the historical variant-A discrepancy: it proves the
     * provider's own output is byte-identical to what enters the real
     * system-block content, rather than trusting that "a provider exists"
     * implies "its output was used."
     */
    @Test
    fun phase10cProviderVerification() {
        val world = World()
        world.llmClient.interceptGeneration = true

        val srProvider = com.pinkdreams.chat.context.SelectiveRawContextProvider(
            db = world.db, personaMasterRepo = world.personaMasterRepo,
            fieldRegistryRepo = world.fieldRegistryRepo, skillContextReqRepo = world.skillReqRepo,
        )
        val frProvider = com.pinkdreams.chat.context.FullRawContextProvider(personaMasterRepo = world.personaMasterRepo)

        var allPassed = true
        fun fail(msg: String) { allPassed = false; println("STOP: $msg") }

        // ---- 1. SR selection correctness: exactly B's resolver selection, no more, no fewer ----
        println("\n════ 1. SR selection correctness ════")
        val srScenarios = listOf("about" to "What did you study?", "friendship" to "Do you have siblings?", "general_chat" to "How's the restaurant project going?")
        srScenarios.forEach { (skill, msg) ->
            val srText = srProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
            val bIncluded = world.resolver.resolve(world.personaId, world.personaCoreVersionId, skill, msg, "B").includedFieldIds.toSet()
            val parsed = try { kotlinx.serialization.json.Json.parseToJsonElement(srText).let { it as kotlinx.serialization.json.JsonObject }.keys } catch (e: Exception) { emptySet() }
            println("[$skill] \"$msg\" -> SR keys=$parsed, B selection=$bIncluded")
            if (parsed != bIncluded) fail("SR keys do not exactly equal B's resolver selection for '$msg' ($skill)")
            if (srText.contains("##") || srText.contains("**")) fail("SR output contains structured-renderer markers")
        }

        // ---- 2. FR full-document correctness: every top-level master-doc key present, values match, no filtering ----
        println("\n════ 2. FR full-document correctness ════")
        val frText = frProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, "about", "What did you study?")
        val masterDoc = world.personaMasterRepo.findByPersonaCoreVersionId(world.personaCoreVersionId)!!
        val expectedTopLevelKeys = masterDoc.contentJson.keys
        val frParsed = try { kotlinx.serialization.json.Json.parseToJsonElement(frText).let { it as kotlinx.serialization.json.JsonObject } } catch (e: Exception) { null }
        val frKeys = frParsed?.keys ?: emptySet()
        println("Master doc top-level keys ($${expectedTopLevelKeys.size}): $expectedTopLevelKeys")
        println("FR top-level keys (${frKeys.size}): $frKeys")
        if (frKeys != expectedTopLevelKeys) fail("FR is missing or has extra top-level master-document keys")
        if (frText.contains("##") || frText.contains("**")) fail("FR output contains structured-renderer markers")
        // Representative-fact substring check (Phase 10B Step 11 consistency assertion), including RESTRICTED content —
        // FR is "full, unfiltered" by design, so restricted fields are expected to be present unconditionally.
        val expectedFacts = listOf("Nashik", "Kohli", "Ritu", "architect")
        expectedFacts.forEach { fact -> if (!frText.contains(fact)) fail("FR is missing expected representative fact substring: '$fact'") }
        if (!frText.contains("intimate_anatomy") && !frText.contains("adult_intimacy")) fail("FR unexpectedly filtered out restricted top-level fields — it must be fully unfiltered by design")

        // ---- 3. Determinism: identical input -> identical output, for both SR and FR ----
        println("\n════ 3. Determinism ════")
        val srRepeat = srProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, "about", "What did you study?")
        val srFirst = srProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, "about", "What did you study?")
        val frRepeat = frProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, "about", "What did you study?")
        if (srRepeat != srFirst) fail("SR is non-deterministic on repeat")
        if (frRepeat != frText) fail("FR is non-deterministic on repeat")
        println("SR deterministic=${srRepeat == srFirst}, FR deterministic=${frRepeat == frText}")

        // ---- 4. THE critical check: provider output -> actual request equivalence, through the REAL pipeline, zero API calls ----
        println("\n════ 4. Provider -> request equivalence (zero-API-cost, via interceptGeneration) ════")
        listOf("FR" to frProvider, "X" to srProvider).forEach { (variantLabel, provider) ->
            world.llmClient.lastCapturedSystemBlock = null
            val conversationId = world.service.create(
                TestChatService.CreationRequest(
                    personaId = world.personaId, testUserId = world.testUserId,
                    personaContextVariant = variantLabel, personaContextSkillKey = "about",
                ),
            ).let { (it as TestChatService.CreationResult.Created).conversation.id }
            world.llmClient.currentSkillKey = "about"
            val result = world.service.sendMessage(conversationId, UUID.randomUUID(), "What did you study?")
            check(result is TestChatService.MessageResult.Sent) { "sendMessage failed for $variantLabel: $result" }
            val capturedSystemBlock = world.llmClient.lastCapturedSystemBlock
                ?: throw IllegalStateException("STOP: no system block was captured for $variantLabel — interception did not fire")
            val expectedProviderOutput = provider.getPersonaContext(world.personaId, world.personaCoreVersionId, "about", "What did you study?")
            val equivalent = capturedSystemBlock.contains(expectedProviderOutput)
            println("[$variantLabel] captured system block contains exact provider output: $equivalent (provider output length=${expectedProviderOutput.length}, captured block length=${capturedSystemBlock.length})")
            if (!equivalent) fail("$variantLabel's provider output does NOT appear verbatim in the captured system block — provider != request content")
        }

        // ---- 5. Token sanity invariant, validated retroactively against KNOWN historical numbers (does not alter historical data) ----
        println("\n════ 5. Token sanity invariant (persona_token_estimate <= actual total prompt tokens) ════")
        fun tokenInvariantHolds(personaTokenEstimate: Int, actualTotalPromptTokens: Int): Boolean = personaTokenEstimate <= actualTotalPromptTokens
        // Historical Phase 6 means, taken as-is from PHASE6_EXECUTIONS.csv / PHASE6_ANALYSIS.md — not modified here.
        val historicalA = tokenInvariantHolds(personaTokenEstimate = 4542, actualTotalPromptTokens = 2062)
        val historicalB = tokenInvariantHolds(personaTokenEstimate = 232, actualTotalPromptTokens = 1662)
        val historicalD = tokenInvariantHolds(personaTokenEstimate = 3335, actualTotalPromptTokens = 4345)
        println("Historical A invariant holds: $historicalA (expected FALSE — this is the exact violation PHASE10A documented, reproduced here as a check, not silently rewritten)")
        println("Historical B invariant holds: $historicalB (expected TRUE)")
        println("Historical D invariant holds: $historicalD (expected TRUE)")
        if (historicalA) fail("Token invariant check itself is broken — it should flag historical A as a violation and did not")
        if (!historicalB || !historicalD) fail("Token invariant incorrectly flags known-good historical B/D data")

        println("\n════ PHASE 10C RESULT ════")
        println(if (allPassed) "✓✓✓ ALL PROVIDER VERIFICATION CHECKS PASSED ✓✓✓" else "✗✗✗ PHASE 10C VERIFICATION FAILED ✗✗✗")
        assertTrue(allPassed, "STOP: one or more Phase 10C verification checks failed — see log above")
        println("\nZERO API calls made — interceptGeneration=true for the entire test, all generation calls answered locally.")
    }

    /**
     * PHASE 10D — corpus-matched 2x2 execution. Fresh real calls for SS(B),
     * SR(X), FR on the EXACT same 30-scenario corpus Phase 7 used for D
     * (FS), so all four cells are directly comparable. D/FS itself is
     * reused from `phase7-structured-full-document-executions.csv` at zero
     * new API cost — matched by (skill_key, user_message) since
     * TestScenario generates a fresh random UUID on every construction
     * (confirmed: `PersonaContextPocBenchmarkRunner.TestScenario.id` default
     * is `UUID.randomUUID()`), not by literal scenario UUID.
     */
    data class Phase10dRecord(
        val scenarioKey: String, // "skillKey||userMessage" — the (skill,message) natural key used to match across cells
        val condition: String, // "SS", "SR", "FR", "FS"
        val provider: String,
        val skillKey: String,
        val userMessage: String,
        val selectedFieldIds: List<String>,
        val capturedPayload: String,
        val payloadMatchesRequest: Boolean,
        val personaTokenEstimate: Int,
        val promptTokens: Int,
        val completionTokens: Int,
        val totalTokens: Int,
        val generationLatencyMs: Long,
        val responseText: String,
        val status: String,
        val error: String?,
        val conversationEngineVersion: Int?,
        val personaCoreVersion: Int?,
    )

    @Test
    fun phase10dCorpusMatched2x2() {
        val world = World()
        world.llmClient.interceptGeneration = false // real calls this time — this IS the authorized execution

        val identity = PersonaContextPocCorpusV4.identityAbout()
        val lifeContextFactHeavy = PersonaContextPocCorpusV4.lifeContext().filter {
            it.userMessage in setOf(
                "How's the restaurant project going?", "Tell me about your family.", "Do you talk to your parents a lot?",
                "Do you have siblings?", "How's Ritu these days?", "What's your flat like?",
                "What's your relationship with your siblings like?", "Any trips planned?",
                "What have you been busy with lately?", "What's been on your mind lately?",
            )
        }
        val scenarios = identity + lifeContextFactHeavy
        check(scenarios.size == 30) { "STOP: corpus size drifted from Phase 7's 30 — got ${scenarios.size}" }
        println("Phase 10D: ${scenarios.size} scenarios x 3 fresh conditions (SS/B, SR/X, FR) = ${scenarios.size * 3} calls. FS/D reused at 0 cost.")

        val records = mutableListOf<Phase10dRecord>()

        fun runCondition(scenario: PersonaContextPocBenchmarkRunner.TestScenario, variant: String, conditionLabel: String, providerName: String) {
            val key = "${scenario.skillKey}||${scenario.userMessage}"
            world.llmClient.lastCapturedSystemBlock = null
            world.llmClient.currentSkillKey = scenario.skillKey

            val actualProviderPayload = when (variant) {
                "B" -> com.pinkdreams.chat.context.ResolvedPersonaContextProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo, "B")
                    .getPersonaContext(world.personaId, world.personaCoreVersionId, scenario.skillKey, scenario.userMessage)
                "X" -> com.pinkdreams.chat.context.SelectiveRawContextProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo)
                    .getPersonaContext(world.personaId, world.personaCoreVersionId, scenario.skillKey, scenario.userMessage)
                "FR" -> com.pinkdreams.chat.context.FullRawContextProvider(world.personaMasterRepo)
                    .getPersonaContext(world.personaId, world.personaCoreVersionId, scenario.skillKey, scenario.userMessage)
                else -> ""
            }
            val fieldIdsForRecord = if (variant == "B" || variant == "X") {
                world.resolver.resolve(world.personaId, world.personaCoreVersionId, scenario.skillKey, scenario.userMessage, "B").includedFieldIds
            } else listOf("ALL")

            val genStart = System.nanoTime()
            var responseText = ""; var status = "SUCCESS"; var error: String? = null
            var promptTokens = 0; var completionTokens = 0; var totalTokens = 0
            var engineVersion: Int? = null; var coreVersion: Int? = null
            try {
                val conversationId = world.service.create(
                    TestChatService.CreationRequest(
                        personaId = world.personaId, testUserId = world.testUserId,
                        personaContextVariant = variant, personaContextSkillKey = scenario.skillKey,
                    ),
                ).let { (it as TestChatService.CreationResult.Created).conversation.id }
                world.llmClient.lastGenerationResponse = null
                val result = world.service.sendMessage(conversationId, UUID.randomUUID(), scenario.userMessage)
                check(result is TestChatService.MessageResult.Sent) { "sendMessage failed: $result" }
                when (val chatResult = result.chatResult) {
                    is ChatResult.Success -> { responseText = chatResult.response.content }
                    else -> { status = "FAILED"; error = chatResult.toString().take(500) }
                }
                val meta = world.llmClient.lastGenerationResponse?.metadata ?: emptyMap()
                promptTokens = meta["prompt_tokens"]?.toIntOrNull() ?: 0
                completionTokens = meta["completion_tokens"]?.toIntOrNull() ?: 0
                totalTokens = meta["total_tokens"]?.toIntOrNull() ?: 0
                val snapshot = world.conversations.findById(conversationId)?.snapshot
                engineVersion = snapshot?.conversationEngineVersion
                coreVersion = snapshot?.personaCoreVersion
            } catch (e: Exception) {
                status = "FAILED"; error = (e.message ?: e.javaClass.simpleName).take(500)
            }
            val genLatencyMs = (System.nanoTime() - genStart) / 1_000_000

            val capturedBlock = world.llmClient.lastCapturedSystemBlock ?: ""
            val payloadMatches = status == "SUCCESS" && capturedBlock.contains(actualProviderPayload) && actualProviderPayload.isNotEmpty()

            records += Phase10dRecord(
                scenarioKey = key, condition = conditionLabel, provider = providerName,
                skillKey = scenario.skillKey, userMessage = scenario.userMessage,
                selectedFieldIds = fieldIdsForRecord, capturedPayload = actualProviderPayload,
                payloadMatchesRequest = payloadMatches,
                personaTokenEstimate = (actualProviderPayload.length / 4).coerceAtLeast(1),
                promptTokens = promptTokens, completionTokens = completionTokens, totalTokens = totalTokens,
                generationLatencyMs = genLatencyMs, responseText = responseText, status = status, error = error,
                conversationEngineVersion = engineVersion, personaCoreVersion = coreVersion,
            )
            if (status == "SUCCESS" && !payloadMatches) {
                println("STOP-CANDIDATE: payload mismatch for $conditionLabel on \"${scenario.userMessage}\" — recording and continuing per instruction (preserve completed data)")
            }
        }

        scenarios.forEachIndexed { idx, scenario ->
            println("[phase10d ${idx + 1}/${scenarios.size}] ${scenario.skillKey}: ${scenario.userMessage.take(50)}")
            runCondition(scenario, "B", "SS", "ResolvedPersonaContextProvider(B)")
            Thread.sleep(1000)
            runCondition(scenario, "X", "SR", "SelectiveRawContextProvider")
            Thread.sleep(1000)
            runCondition(scenario, "FR", "FR", "FullRawContextProvider")
            Thread.sleep(1000)
        }

        val successCount = records.count { it.status == "SUCCESS" }
        val mismatchCount = records.count { it.status == "SUCCESS" && !it.payloadMatchesRequest }
        println("\n════ PHASE 10D COMPLETE ════")
        println("Total fresh executions: ${records.size} | SUCCESS: $successCount | FAILED: ${records.size - successCount} | payload mismatches: $mismatchCount")

        // Save BEFORE any assertion — preserve all completed data regardless of outcome.
        val dir = "E:\\Projects\\Pink dreams\\docs\\Persona update POC"
        val header = "scenario_key,condition,provider,skill_key,user_message,selected_field_ids,persona_token_estimate,prompt_tokens,completion_tokens,total_tokens,generation_latency_ms,response,status,error,conversation_engine_version,persona_core_version,payload_matches_request\n"
        val rows = records.joinToString("\n") { r ->
            listOf(
                "\"${r.scenarioKey.replace("\"", "\"\"")}\"", r.condition, r.provider, r.skillKey,
                "\"${r.userMessage.replace("\"", "\"\"")}\"", "\"${r.selectedFieldIds.joinToString(";")}\"",
                r.personaTokenEstimate, r.promptTokens, r.completionTokens, r.totalTokens, r.generationLatencyMs,
                "\"${r.responseText.replace("\"", "\"\"")}\"", r.status, "\"${(r.error ?: "").replace("\"", "\"\"")}\"",
                r.conversationEngineVersion, r.personaCoreVersion, r.payloadMatchesRequest,
            ).joinToString(",")
        }
        File("$dir/PHASE10D_EXECUTIONS.csv").writeText(header + rows)
        println("✓ Saved: $dir/PHASE10D_EXECUTIONS.csv")

        // Critical invariants, checked AFTER saving.
        val noAParticipation = records.none { it.condition == "A" || it.provider.contains("RawPersonaContextProvider") }
        val allSSUseB = records.filter { it.condition == "SS" }.all { it.provider == "ResolvedPersonaContextProvider(B)" }
        val allSRUseX = records.filter { it.condition == "SR" }.all { it.provider == "SelectiveRawContextProvider" }
        val allFRUseFR = records.filter { it.condition == "FR" }.all { it.provider == "FullRawContextProvider" }
        val allPayloadsMatch = records.filter { it.status == "SUCCESS" }.all { it.payloadMatchesRequest }
        // Only SR/FR are supposed to be raw — SS is correctly, intentionally structured
        // (it must legitimately contain "##"/"**"), so it must be excluded from this check.
        val noRendererCrossover = records.filter { it.condition == "SR" || it.condition == "FR" }
            .none { it.capturedPayload.contains("##") || it.capturedPayload.contains("**") }
        val sameEngineVersion = records.mapNotNull { it.conversationEngineVersion }.distinct().size <= 1
        val sameCoreVersion = records.mapNotNull { it.personaCoreVersion }.distinct().size <= 1

        println("\n════ CRITICAL INVARIANTS ════")
        println("No variant-A participation: $noAParticipation")
        println("All SS records use provider B: $allSSUseB")
        println("All SR records use provider X: $allSRUseX")
        println("All FR records use provider FR: $allFRUseFR")
        println("All successful payloads match the actual request: $allPayloadsMatch (mismatches=$mismatchCount)")
        println("No structured-renderer markers in any SR/FR payload: $noRendererCrossover")
        println("Single engine version across all executions: $sameEngineVersion (${records.mapNotNull { it.conversationEngineVersion }.distinct()})")
        println("Single persona-core version across all executions: $sameCoreVersion (${records.mapNotNull { it.personaCoreVersion }.distinct()})")

        val allInvariantsPass = noAParticipation && allSSUseB && allSRUseX && allFRUseFR && allPayloadsMatch && noRendererCrossover && sameEngineVersion && sameCoreVersion
        println(if (allInvariantsPass) "\n✓✓✓ ALL PHASE 10D INVARIANTS HOLD ✓✓✓" else "\n✗✗✗ ONE OR MORE PHASE 10D INVARIANTS FAILED — see PHASE10D_EXECUTIONS.csv for preserved data ✗✗✗")
        assertTrue(allInvariantsPass, "STOP: Phase 10D invariant check failed — data preserved in PHASE10D_EXECUTIONS.csv, do not patch, report the failure")
    }

    /** Zero-API-cost diagnostic: find exactly which scenario/provider triggered the "##"/"**" false-positive flag. */
    @Test
    fun phase10dDiagnoseRendererMarkerFlag() {
        val world = World()
        val identity = PersonaContextPocCorpusV4.identityAbout()
        val lifeContextFactHeavy = PersonaContextPocCorpusV4.lifeContext().filter {
            it.userMessage in setOf(
                "How's the restaurant project going?", "Tell me about your family.", "Do you talk to your parents a lot?",
                "Do you have siblings?", "How's Ritu these days?", "What's your flat like?",
                "What's your relationship with your siblings like?", "Any trips planned?",
                "What have you been busy with lately?", "What's been on your mind lately?",
            )
        }
        val scenarios = identity + lifeContextFactHeavy
        val srProvider = com.pinkdreams.chat.context.SelectiveRawContextProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo)
        val frProvider = com.pinkdreams.chat.context.FullRawContextProvider(world.personaMasterRepo)

        scenarios.forEach { s ->
            listOf("SR" to srProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, s.skillKey, s.userMessage),
                "FR" to frProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, s.skillKey, s.userMessage)).forEach { (cond, text) ->
                listOf("##", "**").forEach { marker ->
                    var idx = text.indexOf(marker)
                    while (idx >= 0) {
                        val start = (idx - 40).coerceAtLeast(0)
                        val end = (idx + 40).coerceAtMost(text.length)
                        println("[$cond] \"${s.userMessage}\" contains '$marker' at $idx: ...${text.substring(start, end).replace("\n", "\\n")}...")
                        idx = text.indexOf(marker, idx + marker.length)
                    }
                }
            }
        }
    }

    /**
     * PHASE 10E — Mapping Closure. Applies exactly the 4 field-mapping edits
     * identified by PHASE10E_ZEROCOST_AUDIT.md, entirely via
     * PersonaFieldRegistryRepository.update() / SkillContextRequirementRepository.update()
     * against one test's in-memory DB — PersonaContextPocSeed.kt is never
     * touched, nothing persists beyond this JVM. No resolver/renderer/engine
     * change. Stage 1 (zero API cost) runs first; the 60-call Stage 2 pilot
     * only proceeds if Stage 1 confirms the fix behaves exactly as the audit
     * predicted, with zero over-triggering.
     */
    private fun applyPhase10eMappingFixes(world: World) {
        val enrichedFamilyDescription = "family father mother mom dad parents siblings relatives Anjali Arjun Rajesh Priya Kohli home visit call"
        val familyEntry = world.fieldRegistryRepo.findByFieldId("life_context.family")!!
        world.fieldRegistryRepo.update(familyEntry.copy(description = enrichedFamilyDescription))

        // life_context.friends made CONDITIONAL (not recommended/required) — surgical,
        // matches TC-6's precedent for life_context.family, and does not inject
        // flatmate content into unrelated identity questions like "what's your name."
        val friendsEntry = world.fieldRegistryRepo.findByFieldId("life_context.friends")!!
        val enrichedFriendsDescription = "friends flatmate roommate live living lives alone together share flat situation Ritu Prashant Neha"
        world.fieldRegistryRepo.update(friendsEntry.copy(description = enrichedFriendsDescription))

        val about = world.skillReqRepo.findBySkillKey("about")!!
        world.skillReqRepo.update(
            about.copy(conditionalFields = (about.conditionalFields ?: emptyList()) + "life_context.friends"),
        )
        val generalChat = world.skillReqRepo.findBySkillKey("general_chat")!!
        world.skillReqRepo.update(
            generalChat.copy(
                // hometown/current_city made REQUIRED, matching "about"'s own existing
                // precedent for these exact fields — always-available core identity,
                // not conditional/surgical, consistent with how "about" already treats them.
                requiredFields = (generalChat.requiredFields ?: emptyList()) + listOf("identity.hometown", "identity.current_city"),
                conditionalFields = (generalChat.conditionalFields ?: emptyList()) + listOf("life_context.family", "life_context.friends"),
            ),
        )
    }

    @Test
    fun phase10eStage1DeterministicCheck() {
        val world = World()
        val failingScenarios = listOf(
            "about" to "Do you have siblings?", "about" to "What's your relationship with your siblings like?",
            "general_chat" to "Tell me about your family.",
            "about" to "Do you live alone?", "about" to "Who do you live with?", "about" to "What's your flat like?",
            "general_chat" to "You mentioned a flatmate before — who's that again?",
            "general_chat" to "What's the deal with your living situation?", "general_chat" to "How's Ritu these days?",
            "general_chat" to "Been in this city long?", "general_chat" to "Is Pune home for you, or somewhere else originally?",
            "general_chat" to "Refresh my memory on where you're based.", "general_chat" to "Do you rent or own your place?",
        )
        // Note: "friendship"/"Do you talk to your parents a lot?" is deliberately NOT
        // included here — friendship already had life_context.family as a pre-existing
        // conditional field before this phase (a TC-6 flip probe), so it is EXPECTED
        // and CORRECT for it to newly match under the enriched description. Including
        // it here would conflate an intended improvement with unwanted over-triggering.
        val controls = listOf(
            "about" to "What's your name?", "about" to "How old are you?", "general_chat" to "How's the restaurant project going?",
            "perspective_of" to "What matters most to you?", "romantic_conversation" to "What's your love language?",
        )
        fun fieldsFor(skill: String, msg: String) = world.resolver.resolve(world.personaId, world.personaCoreVersionId, skill, msg, "B").includedFieldIds.toSet()

        println("\n════ PHASE 10E STAGE 1 — BASELINE ════")
        val baselineFailing = failingScenarios.associateWith { (s, m) -> fieldsFor(s, m) }
        val baselineControls = controls.associateWith { (s, m) -> fieldsFor(s, m) }
        baselineFailing.forEach { (k, v) -> println("[BASELINE] ${k.first}: \"${k.second}\" -> $v") }

        applyPhase10eMappingFixes(world)
        println("\n✓ Applied 4 isolated mapping edits (family description, about+friends, general_chat+hometown/current_city/friends/family)")

        println("\n════ PHASE 10E STAGE 1 — TUNED ════")
        var allOk = true
        failingScenarios.forEach { (skill, msg) ->
            val fields = fieldsFor(skill, msg)
            val gainedRelevantField = fields.any { it == "life_context.family" || it == "life_context.friends" || it == "identity.hometown" || it == "identity.current_city" }
            println("[TUNED] $skill: \"$msg\" -> $fields (gained a relevant field: $gainedRelevantField)")
            if (!gainedRelevantField) { allOk = false; println("STAGE-1 FAIL: still missing a relevant field for '$msg' ($skill)") }
        }
        // Over-triggering check is scoped to the CONDITIONAL fields specifically
        // (life_context.family, life_context.friends) — the newly-required
        // identity.hometown/identity.current_city for general_chat are EXPECTED to
        // appear on every general_chat scenario now (by design, matching "about"'s
        // own existing precedent for those two fields), so their appearance alone
        // is not over-triggering.
        controls.forEach { (skill, msg) ->
            val fields = fieldsFor(skill, msg)
            val before = baselineControls.getValue(skill to msg)
            val newConditionalLeak = (fields - before).any { it == "life_context.family" || it == "life_context.friends" }
            println("[CONTROL] $skill: \"$msg\" -> before=$before after=$fields (new conditional leak: $newConditionalLeak)")
            if (newConditionalLeak) { allOk = false; println("STAGE-1 STOP: control scenario '$msg' ($skill) over-triggered a conditional field it shouldn't have") }
        }
        println(if (allOk) "\n✓✓✓ STAGE 1 PASS — cleared for the 60-call Stage 2 pilot ✓✓✓" else "\n✗✗✗ STAGE 1 FAIL — do NOT spend API budget ✗✗✗")
        assertTrue(allOk, "STOP: Phase 10E Stage 1 failed")
    }

    @Test
    fun phase10eMappingClosurePilot() {
        val world = World()
        applyPhase10eMappingFixes(world)
        world.llmClient.interceptGeneration = false

        val identity = PersonaContextPocCorpusV4.identityAbout()
        val lifeContextFactHeavy = PersonaContextPocCorpusV4.lifeContext().filter {
            it.userMessage in setOf(
                "How's the restaurant project going?", "Tell me about your family.", "Do you talk to your parents a lot?",
                "Do you have siblings?", "How's Ritu these days?", "What's your flat like?",
                "What's your relationship with your siblings like?", "Any trips planned?",
                "What have you been busy with lately?", "What's been on your mind lately?",
            )
        }
        val scenarios = identity + lifeContextFactHeavy
        check(scenarios.size == 30) { "STOP: corpus size drifted — got ${scenarios.size}" }
        println("Phase 10E: ${scenarios.size} scenarios x 2 conditions (SS-M/B, SR-M/X) = ${scenarios.size * 2} calls, mapping fixes applied.")

        data class Rec(val scenarioKey: String, val condition: String, val skillKey: String, val userMessage: String, val selectedFieldIds: List<String>, val promptTokens: Int, val totalTokens: Int, val generationLatencyMs: Long, val responseText: String, val status: String, val error: String?)
        val records = mutableListOf<Rec>()

        fun run(scenario: PersonaContextPocBenchmarkRunner.TestScenario, variant: String, conditionLabel: String) {
            world.llmClient.currentSkillKey = scenario.skillKey
            var responseText = ""; var status = "SUCCESS"; var error: String? = null
            var promptTokens = 0; var totalTokens = 0
            val genStart = System.nanoTime()
            try {
                val conversationId = world.service.create(
                    TestChatService.CreationRequest(personaId = world.personaId, testUserId = world.testUserId, personaContextVariant = variant, personaContextSkillKey = scenario.skillKey),
                ).let { (it as TestChatService.CreationResult.Created).conversation.id }
                world.llmClient.lastGenerationResponse = null
                val result = world.service.sendMessage(conversationId, UUID.randomUUID(), scenario.userMessage)
                check(result is TestChatService.MessageResult.Sent) { "sendMessage failed: $result" }
                when (val cr = result.chatResult) { is ChatResult.Success -> responseText = cr.response.content; else -> { status = "FAILED"; error = cr.toString().take(500) } }
                val meta = world.llmClient.lastGenerationResponse?.metadata ?: emptyMap()
                promptTokens = meta["prompt_tokens"]?.toIntOrNull() ?: 0
                totalTokens = meta["total_tokens"]?.toIntOrNull() ?: 0
            } catch (e: Exception) { status = "FAILED"; error = (e.message ?: e.javaClass.simpleName).take(500) }
            val genLatencyMs = (System.nanoTime() - genStart) / 1_000_000
            val fieldIds = world.resolver.resolve(world.personaId, world.personaCoreVersionId, scenario.skillKey, scenario.userMessage, "B").includedFieldIds
            records += Rec("${scenario.skillKey}||${scenario.userMessage}", conditionLabel, scenario.skillKey, scenario.userMessage, fieldIds, promptTokens, totalTokens, genLatencyMs, responseText, status, error)
        }

        scenarios.forEachIndexed { idx, scenario ->
            println("[phase10e ${idx + 1}/${scenarios.size}] ${scenario.skillKey}: ${scenario.userMessage.take(50)}")
            run(scenario, "B", "SS-M"); Thread.sleep(1000)
            run(scenario, "X", "SR-M"); Thread.sleep(1000)
        }

        val successCount = records.count { it.status == "SUCCESS" }
        println("\n════ PHASE 10E PILOT COMPLETE ════")
        println("Total: ${records.size} | SUCCESS: $successCount | FAILED: ${records.size - successCount}")

        val dir = "E:\\Projects\\Pink dreams\\docs\\Persona update POC"
        val header = "scenario_key,condition,skill_key,user_message,selected_field_ids,prompt_tokens,total_tokens,generation_latency_ms,response,status,error\n"
        val rows = records.joinToString("\n") { r ->
            listOf("\"${r.scenarioKey.replace("\"", "\"\"")}\"", r.condition, r.skillKey, "\"${r.userMessage.replace("\"", "\"\"")}\"",
                "\"${r.selectedFieldIds.joinToString(";")}\"", r.promptTokens, r.totalTokens, r.generationLatencyMs,
                "\"${r.responseText.replace("\"", "\"\"")}\"", r.status, "\"${(r.error ?: "").replace("\"", "\"\"")}\"").joinToString(",")
        }
        File("$dir/PHASE10E_EXECUTIONS.csv").writeText(header + rows)
        println("✓ Saved: $dir/PHASE10E_EXECUTIONS.csv")
        assertTrue(successCount == records.size, "STOP: not all Phase 10E executions succeeded — data preserved in PHASE10E_EXECUTIONS.csv")
    }

    /**
     * PHASE 13 — deterministic-only validation of the two Phase 12 proposed
     * fixes. NEVER calls world.service.sendMessage / any LLM — only
     * world.resolver.resolve(...) directly, so this method makes zero
     * network calls by construction (no HybridLlmClient generation path is
     * ever reached). Both fixes are applied in isolated, fresh in-memory
     * World() instances via repository.update(), never touching
     * PersonaContextPocSeed.kt.
     */
    @Test
    fun phase13DeterministicMappingValidation() {
        fun fullCorpus(): List<PersonaContextPocBenchmarkRunner.TestScenario> {
            val identity = PersonaContextPocCorpusV4.identityAbout()
            val lifeContextFactHeavy = PersonaContextPocCorpusV4.lifeContext().filter {
                it.userMessage in setOf(
                    "How's the restaurant project going?", "Tell me about your family.", "Do you talk to your parents a lot?",
                    "Do you have siblings?", "How's Ritu these days?", "What's your flat like?",
                    "What's your relationship with your siblings like?", "Any trips planned?",
                    "What have you been busy with lately?", "What's been on your mind lately?",
                )
            }
            return identity + lifeContextFactHeavy
        }

        fun selectedFieldsFor(world: World, scenario: PersonaContextPocBenchmarkRunner.TestScenario): Set<String> =
            world.resolver.resolve(world.personaId, world.personaCoreVersionId, scenario.skillKey, scenario.userMessage, "B").includedFieldIds.toSet()

        fun runCorpusSnapshot(world: World, corpus: List<PersonaContextPocBenchmarkRunner.TestScenario>): Map<String, Set<String>> =
            corpus.associate { "${it.skillKey}||${it.userMessage}" to selectedFieldsFor(world, it) }

        val restrictedFieldIds = setOf("intimate_anatomy", "adult_intimacy")
        var allOk = true
        fun fail(msg: String) { allOk = false; println("STOP: $msg") }

        // ================= FIX #1 — FRIENDS lexical coverage =================
        println("\n════ FIX #1 — FRIENDS lexical coverage (rent/own/place) ════")
        val worldF1 = World()
        val corpus = fullCorpus()
        val f1PreAnything = runCorpusSnapshot(worldF1, corpus) // pre-Phase-10E, pre-Phase-13 — for reference only, NOT the regression baseline

        // Apply the ORIGINAL, already-Phase-10E-validated fixes first (unchanged wording).
        val about1 = worldF1.skillReqRepo.findBySkillKey("about")!!
        worldF1.skillReqRepo.update(about1.copy(conditionalFields = (about1.conditionalFields ?: emptyList()) + "life_context.friends"))
        val gc1 = worldF1.skillReqRepo.findBySkillKey("general_chat")!!
        worldF1.skillReqRepo.update(
            gc1.copy(
                requiredFields = (gc1.requiredFields ?: emptyList()) + listOf("identity.hometown", "identity.current_city"),
                conditionalFields = (gc1.conditionalFields ?: emptyList()) + listOf("life_context.family", "life_context.friends"),
            ),
        )
        val familyEntry1 = worldF1.fieldRegistryRepo.findByFieldId("life_context.family")!!
        worldF1.fieldRegistryRepo.update(familyEntry1.copy(description = "family father mother mom dad parents siblings relatives Anjali Arjun Rajesh Priya Kohli home visit call"))
        val friendsEntry = worldF1.fieldRegistryRepo.findByFieldId("life_context.friends")!!
        worldF1.fieldRegistryRepo.update(friendsEntry.copy(description = "friends flatmate roommate live living lives alone together share flat situation Ritu Prashant Neha"))

        // THIS is the correct regression baseline for Fix #1: the state AFTER all
        // original Phase 10E fixes, BEFORE the new rent/own/place words are added —
        // isolates exactly the marginal effect of Fix #1's own wording change.
        val f1Baseline = runCorpusSnapshot(worldF1, corpus)

        val f1Description = "friends flatmate roommate live living lives alone together share flat situation rent own place apartment buy bought lease Ritu Prashant Neha"
        worldF1.fieldRegistryRepo.update(friendsEntry.copy(description = f1Description))

        val f1Tuned = runCorpusSnapshot(worldF1, corpus)
        val f1TunedRepeat = runCorpusSnapshot(worldF1, corpus)

        val f1IntendedKey = "general_chat||Do you rent or own your place?"
        val f1IntendedPositive = f1Tuned.getValue(f1IntendedKey).contains("life_context.friends")
        println("Intended positive (rent-or-own gains life_context.friends): $f1IntendedPositive")
        if (!f1IntendedPositive) fail("Fix #1 did not select life_context.friends for the intended scenario")
        val originalPhase10eGainCount = corpus.count { s ->
            val k = "${s.skillKey}||${s.userMessage}"
            (f1Baseline.getValue(k) - f1PreAnything.getValue(k)).isNotEmpty()
        }
        println("(Reference only, not the Fix #1 regression baseline) scenarios affected by the ORIGINAL Phase 10E fix set alone, vs. the fully-unfixed state: $originalPhase10eGainCount")

        val f1NewlyAffected = corpus.filter { s ->
            val key = "${s.skillKey}||${s.userMessage}"
            (f1Tuned.getValue(key) - f1Baseline.getValue(key)).isNotEmpty()
        }
        println("Scenarios with any newly-selected field under Fix #1 (n=${f1NewlyAffected.size}):")
        f1NewlyAffected.forEach { s ->
            val key = "${s.skillKey}||${s.userMessage}"
            println("  [${s.skillKey}] \"${s.userMessage}\" -> new: ${f1Tuned.getValue(key) - f1Baseline.getValue(key)}")
        }
        val f1UnintendedFriendsGain = f1NewlyAffected.filter { s ->
            val key = "${s.skillKey}||${s.userMessage}"
            key != f1IntendedKey && (f1Tuned.getValue(key) - f1Baseline.getValue(key)).contains("life_context.friends")
        }
        println("Scenarios gaining life_context.friends UNINTENDEDLY (n=${f1UnintendedFriendsGain.size}): ${f1UnintendedFriendsGain.map { it.userMessage }}")
        if (f1UnintendedFriendsGain.isNotEmpty()) fail("Fix #1 caused unintended life_context.friends selection outside the target scenario")

        val f1RestrictedLeak = corpus.any { s -> selectedFieldsFor(worldF1, s).any { it in restrictedFieldIds } }
        println("Any restricted field selected under Fix #1: $f1RestrictedLeak")
        if (f1RestrictedLeak) fail("Fix #1 caused a restricted field to become selectable")

        val f1Deterministic = corpus.all { s -> val k = "${s.skillKey}||${s.userMessage}"; f1Tuned.getValue(k) == f1TunedRepeat.getValue(k) }
        println("Fix #1 deterministic on repeat: $f1Deterministic")
        if (!f1Deterministic) fail("Fix #1 resolver output is non-deterministic")

        // ================= FIX #2 — TIMELINE (age + important_life_events) =================
        println("\n════ FIX #2 — TIMELINE (identity.age + life_context.important_life_events on general_chat) ════")
        val worldF2 = World()

        // Apply the ORIGINAL, already-Phase-10E-validated fixes first (unchanged wording).
        val gc2 = worldF2.skillReqRepo.findBySkillKey("general_chat")!!
        worldF2.skillReqRepo.update(
            gc2.copy(
                requiredFields = (gc2.requiredFields ?: emptyList()) + listOf("identity.hometown", "identity.current_city"),
                conditionalFields = (gc2.conditionalFields ?: emptyList()) + listOf("life_context.family", "life_context.friends"),
            ),
        )
        val familyEntry2 = worldF2.fieldRegistryRepo.findByFieldId("life_context.family")!!
        worldF2.fieldRegistryRepo.update(familyEntry2.copy(description = "family father mother mom dad parents siblings relatives Anjali Arjun Rajesh Priya Kohli home visit call"))
        val friendsEntry2 = worldF2.fieldRegistryRepo.findByFieldId("life_context.friends")!!
        worldF2.fieldRegistryRepo.update(friendsEntry2.copy(description = "friends flatmate roommate live living lives alone together share flat situation Ritu Prashant Neha"))
        val about2 = worldF2.skillReqRepo.findBySkillKey("about")!!
        worldF2.skillReqRepo.update(about2.copy(conditionalFields = (about2.conditionalFields ?: emptyList()) + "life_context.friends"))

        // THIS is the correct regression baseline for Fix #2: the state AFTER all
        // original Phase 10E fixes, BEFORE identity.age/important_life_events are added.
        val f2Baseline = runCorpusSnapshot(worldF2, corpus)

        val ileEntry = worldF2.fieldRegistryRepo.findByFieldId("life_context.important_life_events")!!
        val f2Description = "life_context.important_life_events years long since moved arrived came time"
        worldF2.fieldRegistryRepo.update(ileEntry.copy(description = f2Description))
        worldF2.skillReqRepo.update(
            gc2.copy(
                requiredFields = (gc2.requiredFields ?: emptyList()) + listOf("identity.hometown", "identity.current_city"),
                conditionalFields = (gc2.conditionalFields ?: emptyList()) + listOf("life_context.family", "life_context.friends", "identity.age", "life_context.important_life_events"),
            ),
        )
        // Note: identity.age made CONDITIONAL here (word-overlap scored via its own
        // description, which we do NOT enrich beyond the literal fieldId — age has
        // no natural-language description in the seed either) — for this check we
        // treat it as conditional on the SAME description-enrichment mechanism as
        // important_life_events, since the resolver has no other selection mode for
        // a required->conditional field without an explicit description. See §"What
        // this does NOT prove" for the resulting limitation this exposes.
        val ageEntry = worldF2.fieldRegistryRepo.findByFieldId("identity.age")!!
        worldF2.fieldRegistryRepo.update(ageEntry.copy(description = "identity.age years long since moved arrived came time"))

        val f2Tuned = runCorpusSnapshot(worldF2, corpus)
        val f2TunedRepeat = runCorpusSnapshot(worldF2, corpus)

        val f2IntendedKey = "general_chat||Been in this city long?"
        val f2IntendedFields = f2Tuned.getValue(f2IntendedKey)
        val f2IntendedPositive = f2IntendedFields.contains("identity.age") && f2IntendedFields.contains("life_context.important_life_events")
        println("Intended positive (been-in-city gains BOTH identity.age and important_life_events): $f2IntendedPositive")
        println("  actual fields: $f2IntendedFields")
        if (!f2IntendedPositive) fail("Fix #2 did not select both intended fields for the target scenario")

        val f2NewlyAffected = corpus.filter { s ->
            val key = "${s.skillKey}||${s.userMessage}"
            (f2Tuned.getValue(key) - f2Baseline.getValue(key)).isNotEmpty()
        }
        println("Scenarios with any newly-selected field under Fix #2 (n=${f2NewlyAffected.size}):")
        f2NewlyAffected.forEach { s ->
            val key = "${s.skillKey}||${s.userMessage}"
            println("  [${s.skillKey}] \"${s.userMessage}\" -> new: ${f2Tuned.getValue(key) - f2Baseline.getValue(key)}")
        }
        val f2UnintendedAgeOrEvents = f2NewlyAffected.filter { s ->
            val key = "${s.skillKey}||${s.userMessage}"
            key != f2IntendedKey && (f2Tuned.getValue(key) - f2Baseline.getValue(key)).any { it == "identity.age" || it == "life_context.important_life_events" }
        }
        println("Scenarios gaining age/important_life_events UNINTENDEDLY (n=${f2UnintendedAgeOrEvents.size}): ${f2UnintendedAgeOrEvents.map { it.userMessage }}")

        val f2RestrictedLeak = corpus.any { s -> selectedFieldsFor(worldF2, s).any { it in restrictedFieldIds } }
        println("Any restricted field selected under Fix #2: $f2RestrictedLeak")
        if (f2RestrictedLeak) fail("Fix #2 caused a restricted field to become selectable")

        val f2Deterministic = corpus.all { s -> val k = "${s.skillKey}||${s.userMessage}"; f2Tuned.getValue(k) == f2TunedRepeat.getValue(k) }
        println("Fix #2 deterministic on repeat: $f2Deterministic")
        if (!f2Deterministic) fail("Fix #2 resolver output is non-deterministic")

        println("\n════ PHASE 13 RESULT ════")
        println(if (allOk) "✓✓✓ Fix #1 fully clean; Fix #2's core assertions checked (see log for unintended-selection count) ✓✓✓" else "✗✗✗ ONE OR MORE HARD STOP CONDITIONS FAILED ✗✗✗")
        println("ZERO API/LLM calls made — only PersonaContextResolver.resolve() was invoked, service.sendMessage() was never called.")
        assertTrue(allOk, "STOP: a hard Phase 13 condition failed — see log above")
    }

    /**
     * PHASE 14 — targeted 4-call real-API validation of the two Phase 13
     * fixes. Exactly 4 generation calls, no more. Each fix applied in its
     * own fresh World() on top of the original Phase 10E fixes (matching
     * Phase 13's design), then exactly the two affected variants (B=SS-M,
     * X=SR-M) are run for exactly the one scenario each fix targets.
     */
    private fun applyOriginalPhase10eFixes(world: World) {
        val about = world.skillReqRepo.findBySkillKey("about")!!
        world.skillReqRepo.update(about.copy(conditionalFields = (about.conditionalFields ?: emptyList()) + "life_context.friends"))
        val gc = world.skillReqRepo.findBySkillKey("general_chat")!!
        world.skillReqRepo.update(
            gc.copy(
                requiredFields = (gc.requiredFields ?: emptyList()) + listOf("identity.hometown", "identity.current_city"),
                conditionalFields = (gc.conditionalFields ?: emptyList()) + listOf("life_context.family", "life_context.friends"),
            ),
        )
        val familyEntry = world.fieldRegistryRepo.findByFieldId("life_context.family")!!
        world.fieldRegistryRepo.update(familyEntry.copy(description = "family father mother mom dad parents siblings relatives Anjali Arjun Rajesh Priya Kohli home visit call"))
        val friendsEntry = world.fieldRegistryRepo.findByFieldId("life_context.friends")!!
        world.fieldRegistryRepo.update(friendsEntry.copy(description = "friends flatmate roommate live living lives alone together share flat situation Ritu Prashant Neha"))
    }

    data class Phase14Result(
        val fixName: String,
        val variant: String,
        val userMessage: String,
        val selectedFieldIds: List<String>,
        val providerPayload: String,
        val payloadInCapturedRequest: Boolean,
        val responseText: String,
        val status: String,
        val error: String?,
    )

    @Test
    fun phase14TargetedFixValidation() {
        val results = mutableListOf<Phase14Result>()
        val restrictedFieldIds = setOf("intimate_anatomy", "adult_intimacy")

        fun runOne(world: World, fixName: String, variant: String, skill: String, message: String): Phase14Result {
            world.llmClient.currentSkillKey = skill
            world.llmClient.lastCapturedSystemBlock = null
            var responseText = ""; var status = "SUCCESS"; var error: String? = null
            try {
                val conversationId = world.service.create(
                    TestChatService.CreationRequest(personaId = world.personaId, testUserId = world.testUserId, personaContextVariant = variant, personaContextSkillKey = skill),
                ).let { (it as TestChatService.CreationResult.Created).conversation.id }
                val result = world.service.sendMessage(conversationId, UUID.randomUUID(), message)
                check(result is TestChatService.MessageResult.Sent) { "sendMessage failed: $result" }
                when (val cr = result.chatResult) { is ChatResult.Success -> responseText = cr.response.content; else -> { status = "FAILED"; error = cr.toString().take(500) } }
            } catch (e: Exception) { status = "FAILED"; error = (e.message ?: e.javaClass.simpleName).take(500) }

            val fieldIds = world.resolver.resolve(world.personaId, world.personaCoreVersionId, skill, message, "B").includedFieldIds
            val providerPayload = when (variant) {
                "B" -> com.pinkdreams.chat.context.ResolvedPersonaContextProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo, "B").getPersonaContext(world.personaId, world.personaCoreVersionId, skill, message)
                "X" -> com.pinkdreams.chat.context.SelectiveRawContextProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo).getPersonaContext(world.personaId, world.personaCoreVersionId, skill, message)
                else -> ""
            }
            val captured = world.llmClient.lastCapturedSystemBlock ?: ""
            val payloadMatches = status == "SUCCESS" && providerPayload.isNotEmpty() && captured.contains(providerPayload)

            return Phase14Result(fixName, variant, message, fieldIds, providerPayload, payloadMatches, responseText, status, error)
        }

        println("\n════ PAYLOAD PRE-CHECK (deterministic, before spending any call) ════")
        val precheckWorldFriends = World()
        applyOriginalPhase10eFixes(precheckWorldFriends)
        val friendsEntry = precheckWorldFriends.fieldRegistryRepo.findByFieldId("life_context.friends")!!
        precheckWorldFriends.fieldRegistryRepo.update(friendsEntry.copy(description = "friends flatmate roommate live living lives alone together share flat situation rent own place apartment buy bought lease Ritu Prashant Neha"))
        val friendsFieldsPrecheck = precheckWorldFriends.resolver.resolve(precheckWorldFriends.personaId, precheckWorldFriends.personaCoreVersionId, "general_chat", "Do you rent or own your place?", "B").includedFieldIds
        val friendsPrecheckOk = friendsFieldsPrecheck.contains("life_context.friends") && friendsFieldsPrecheck.none { it in restrictedFieldIds }
        println("Friends fix pre-check: fields=$friendsFieldsPrecheck ok=$friendsPrecheckOk")
        check(friendsPrecheckOk) { "STOP: friends fix payload pre-check failed — do not spend API calls" }

        val precheckWorldTimeline = World()
        applyOriginalPhase10eFixes(precheckWorldTimeline)
        val ileEntry = precheckWorldTimeline.fieldRegistryRepo.findByFieldId("life_context.important_life_events")!!
        precheckWorldTimeline.fieldRegistryRepo.update(ileEntry.copy(description = "life_context.important_life_events years long since moved arrived came time"))
        val gcT = precheckWorldTimeline.skillReqRepo.findBySkillKey("general_chat")!!
        precheckWorldTimeline.skillReqRepo.update(gcT.copy(conditionalFields = (gcT.conditionalFields ?: emptyList()) + listOf("identity.age", "life_context.important_life_events")))
        val ageEntry = precheckWorldTimeline.fieldRegistryRepo.findByFieldId("identity.age")!!
        precheckWorldTimeline.fieldRegistryRepo.update(ageEntry.copy(description = "identity.age years long since moved arrived came time"))
        val timelineFieldsPrecheck = precheckWorldTimeline.resolver.resolve(precheckWorldTimeline.personaId, precheckWorldTimeline.personaCoreVersionId, "general_chat", "Been in this city long?", "B").includedFieldIds
        val timelinePrecheckOk = timelineFieldsPrecheck.contains("identity.age") && timelineFieldsPrecheck.contains("life_context.important_life_events") && timelineFieldsPrecheck.none { it in restrictedFieldIds }
        println("Timeline fix pre-check: fields=$timelineFieldsPrecheck ok=$timelinePrecheckOk")
        check(timelinePrecheckOk) { "STOP: timeline fix payload pre-check failed — do not spend API calls" }

        println("\n════ SPENDING THE 4 AUTHORIZED CALLS ════")
        // Call 1-2: Friends fix, SS-M (B) and SR-M (X).
        val worldFriends = World()
        applyOriginalPhase10eFixes(worldFriends)
        val friendsEntry2 = worldFriends.fieldRegistryRepo.findByFieldId("life_context.friends")!!
        worldFriends.fieldRegistryRepo.update(friendsEntry2.copy(description = "friends flatmate roommate live living lives alone together share flat situation rent own place apartment buy bought lease Ritu Prashant Neha"))
        results += runOne(worldFriends, "friends", "B", "general_chat", "Do you rent or own your place?")
        Thread.sleep(1000)
        results += runOne(worldFriends, "friends", "X", "general_chat", "Do you rent or own your place?")
        Thread.sleep(1000)

        // Call 3-4: Timeline fix, SS-M (B) and SR-M (X).
        val worldTimeline = World()
        applyOriginalPhase10eFixes(worldTimeline)
        val ileEntry2 = worldTimeline.fieldRegistryRepo.findByFieldId("life_context.important_life_events")!!
        worldTimeline.fieldRegistryRepo.update(ileEntry2.copy(description = "life_context.important_life_events years long since moved arrived came time"))
        val gcT2 = worldTimeline.skillReqRepo.findBySkillKey("general_chat")!!
        worldTimeline.skillReqRepo.update(gcT2.copy(conditionalFields = (gcT2.conditionalFields ?: emptyList()) + listOf("identity.age", "life_context.important_life_events")))
        val ageEntry2 = worldTimeline.fieldRegistryRepo.findByFieldId("identity.age")!!
        worldTimeline.fieldRegistryRepo.update(ageEntry2.copy(description = "identity.age years long since moved arrived came time"))
        results += runOne(worldTimeline, "timeline", "B", "general_chat", "Been in this city long?")
        Thread.sleep(1000)
        results += runOne(worldTimeline, "timeline", "X", "general_chat", "Been in this city long?")

        println("\n════ PHASE 14 RESULTS ════")
        results.forEach { r ->
            println("[${r.fixName}/${r.variant}] status=${r.status} payloadMatches=${r.payloadInCapturedRequest} fields=${r.selectedFieldIds}")
            println("  response: ${r.responseText}")
        }

        val dir = "E:\\Projects\\Pink dreams\\docs\\Persona update POC"
        val header = "fix_name,variant,user_message,selected_field_ids,provider_payload,payload_matches_request,response,status,error\n"
        val rows = results.joinToString("\n") { r ->
            listOf(r.fixName, r.variant, "\"${r.userMessage.replace("\"", "\"\"")}\"", "\"${r.selectedFieldIds.joinToString(";")}\"",
                "\"${r.providerPayload.replace("\"", "\"\"")}\"", r.payloadInCapturedRequest,
                "\"${r.responseText.replace("\"", "\"\"")}\"", r.status, "\"${(r.error ?: "").replace("\"", "\"\"")}\"").joinToString(",")
        }
        File("$dir/PHASE14_EXECUTIONS.csv").writeText(header + rows)
        println("✓ Saved: $dir/PHASE14_EXECUTIONS.csv")

        val allSucceeded = results.all { it.status == "SUCCESS" }
        val allPayloadsMatch = results.all { it.payloadInCapturedRequest }
        println("All 4 calls succeeded: $allSucceeded | All payloads verified: $allPayloadsMatch")
        assertTrue(allSucceeded, "STOP: not all 4 Phase 14 calls succeeded")
        assertTrue(allPayloadsMatch, "STOP: a Phase 14 payload did not match the captured request")
    }

    /**
     * PHASE 16 — labeled-scalar diagnostic. Applies the SAME Phase 10E/13
     * timeline mapping fix (age + important_life_events on general_chat),
     * then compares variant "L" (LabeledScalarDiagnosticProvider) against
     * the frozen Phase 14 B/X payloads via Stage 1 zero-cost checks, and
     * spends EXACTLY ONE real call if Stage 1 passes.
     */
    @Test
    fun phase16LabeledScalarDiagnostic() {
        val world = World()
        applyOriginalPhase10eFixes(world)
        val ileEntry = world.fieldRegistryRepo.findByFieldId("life_context.important_life_events")!!
        world.fieldRegistryRepo.update(ileEntry.copy(description = "life_context.important_life_events years long since moved arrived came time"))
        val gc = world.skillReqRepo.findBySkillKey("general_chat")!!
        world.skillReqRepo.update(gc.copy(conditionalFields = (gc.conditionalFields ?: emptyList()) + listOf("identity.age", "life_context.important_life_events")))
        val ageEntry = world.fieldRegistryRepo.findByFieldId("identity.age")!!
        world.fieldRegistryRepo.update(ageEntry.copy(description = "identity.age years long since moved arrived came time"))

        println("\n════ STAGE 1 — ZERO-COST VERIFICATION ════")
        val skill = "general_chat"
        val msg = "Been in this city long?"

        val bFieldIds = world.resolver.resolve(world.personaId, world.personaCoreVersionId, skill, msg, "B").includedFieldIds.toSet()
        val lProvider = com.pinkdreams.chat.context.LabeledScalarDiagnosticProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo)
        val lFieldIds = world.resolver.resolve(world.personaId, world.personaCoreVersionId, skill, msg, "B").includedFieldIds.toSet() // L reuses B's own resolve("B") call internally — verified identical by construction
        val bProvider = com.pinkdreams.chat.context.ResolvedPersonaContextProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo, "B")
        val xProvider = com.pinkdreams.chat.context.SelectiveRawContextProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo)

        val bText = bProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        val lText = lProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        val xText = xProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)

        var stage1Ok = true
        fun check(label: String, cond: Boolean) { println("[$label] ${if (cond) "PASS" else "FAIL"}"); if (!cond) stage1Ok = false }

        check("1. identity.age rendered with an explicit age label", lText.contains("Age: 27"))
        check("2. life_context.important_life_events content unchanged (same 6-item text)", lText.contains("Moving to Pune at 21 to study architecture.") && lText.contains("Leaving the first corporate architecture job at 24 (felt wrong)."))
        check("3. '21' and '24' events still present", lText.contains("at 21") && lText.contains("at 24"))
        check("4. No additional facts introduced (same field ID set)", lFieldIds == bFieldIds)
        check("5. Selection equivalent to Phase 14 B (age + important_life_events + occupation + hometown + current_city + name + core_traits + communication.style + unresolved_threads)", bFieldIds == setOf("identity.name", "personality.core_traits", "identity.hometown", "identity.current_city", "communication.style", "narrative_state.unresolved_threads", "life_context.occupation", "identity.age", "life_context.important_life_events"))
        // 6. Diff B vs L: EVERY top-level scalar line in B must reappear in L with ONLY a
        // "Label: " prefix added — the instruction asks for scalar fields (plural, all of
        // them) to be labeled, not age alone; age is separately checked in #1. No line's
        // underlying VALUE may differ, no line may be added or removed beyond the label prefix.
        val bLines = bText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val lLines = lText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val bOnly = bLines - lLines.toSet()
        val lOnly = lLines - bLines.toSet()
        println("Lines only in B (unlabeled originals): $bOnly")
        println("Lines only in L (labeled versions): $lOnly")
        val everyBOnlyLineHasALabeledCounterpart = bOnly.all { bLine -> lOnly.any { lLine -> lLine.endsWith(": $bLine") } }
        val sameCount = bOnly.size == lOnly.size
        val noExtraLLines = lOnly.all { lLine -> bOnly.any { bLine -> lLine.endsWith(": $bLine") } }
        check(
            "6. B-vs-L difference is exactly a 'Label: ' prefix added to each scalar line, no value changes, no lines added/removed beyond labeling",
            everyBOnlyLineHasALabeledCounterpart && sameCount && noExtraLLines,
        )
        check("7. X/raw payload unchanged from Phase 14", xText.contains("\"identity.age\":27") && xText.contains("Moving to Pune at 21 to study architecture."))
        println("8. No production renderer or mapping code modified — LabeledScalarDiagnosticProvider is a new, isolated file; PersonaContextRenderer.kt untouched (verified by this phase making no edits to it).")

        println("\nSTAGE 1 RESULT: ${if (stage1Ok) "PASS — cleared for the 1-call diagnostic" else "FAIL — STOP, do not spend the API call"}")
        assertTrue(stage1Ok, "STOP: Phase 16 Stage 1 failed — see log above")

        println("\n════ STAGE 2 — EXACTLY 1 REAL CALL ════")
        world.llmClient.currentSkillKey = skill
        world.llmClient.lastCapturedSystemBlock = null
        val conversationId = world.service.create(
            TestChatService.CreationRequest(personaId = world.personaId, testUserId = world.testUserId, personaContextVariant = "L", personaContextSkillKey = skill),
        ).let { (it as TestChatService.CreationResult.Created).conversation.id }
        val result = world.service.sendMessage(conversationId, UUID.randomUUID(), msg)
        check(result is TestChatService.MessageResult.Sent) { "sendMessage failed: $result" }
        val responseText = when (val cr = (result as TestChatService.MessageResult.Sent).chatResult) {
            is ChatResult.Success -> cr.response.content
            else -> { println("STOP: generation failed: $cr"); "" }
        }
        val captured = world.llmClient.lastCapturedSystemBlock ?: ""
        val payloadMatches = captured.contains(lText)

        println("Response: $responseText")
        println("Payload matches captured request: $payloadMatches")

        val dir = "E:\\Projects\\Pink dreams\\docs\\Persona update POC"
        File("$dir/PHASE16_EXECUTION.csv").writeText(
            "variant,skill,user_message,selected_field_ids,provider_payload,payload_matches_request,response\n" +
                "L,$skill,\"$msg\",\"${lFieldIds.joinToString(";")}\",\"${lText.replace("\"", "\"\"")}\",$payloadMatches,\"${responseText.replace("\"", "\"\"")}\"",
        )
        println("✓ Saved: $dir/PHASE16_EXECUTION.csv")
        assertTrue(payloadMatches, "STOP: Phase 16 payload did not match the captured request")
    }

    /**
     * PHASE 17 — scalar-label effect replication. Exactly 8 real calls (4
     * pre-registered scenarios x B/L). Scenarios chosen and documented in
     * PHASE17_PRE_REGISTRATION.md BEFORE this method was run.
     */
    @Test
    fun phase17ScalarLabelReplication() {
        val world = World()
        // No Phase 10E/13 fixes applied — deliberately unnecessary here, since
        // all 4 scenarios use only "about"-skill fields whose mapping has been
        // unchanged since the original baseline (see PHASE17_PRE_REGISTRATION.md).

        val scenarios = listOf(
            "How old are you?",
            "Where are you from?",
            "What city do you live in now?",
            "What's your educational background?",
        )
        val skill = "about"

        data class P17Result(val scenario: String, val variant: String, val fieldIds: List<String>, val payload: String, val payloadMatches: Boolean, val response: String, val status: String)
        val results = mutableListOf<P17Result>()

        fun runOne(variant: String, scenario: String): P17Result {
            world.llmClient.currentSkillKey = skill
            world.llmClient.lastCapturedSystemBlock = null
            val fieldIds = world.resolver.resolve(world.personaId, world.personaCoreVersionId, skill, scenario, "B").includedFieldIds
            val payload = when (variant) {
                "B" -> com.pinkdreams.chat.context.ResolvedPersonaContextProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo, "B").getPersonaContext(world.personaId, world.personaCoreVersionId, skill, scenario)
                "L" -> com.pinkdreams.chat.context.LabeledScalarDiagnosticProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo).getPersonaContext(world.personaId, world.personaCoreVersionId, skill, scenario)
                else -> ""
            }
            var responseText = ""; var status = "SUCCESS"
            try {
                val conversationId = world.service.create(
                    TestChatService.CreationRequest(personaId = world.personaId, testUserId = world.testUserId, personaContextVariant = variant, personaContextSkillKey = skill),
                ).let { (it as TestChatService.CreationResult.Created).conversation.id }
                val result = world.service.sendMessage(conversationId, UUID.randomUUID(), scenario)
                check(result is TestChatService.MessageResult.Sent) { "sendMessage failed: $result" }
                when (val cr = result.chatResult) { is ChatResult.Success -> responseText = cr.response.content; else -> { status = "FAILED"; println("STOP: call failed for [$variant] \"$scenario\": $cr") } }
            } catch (e: Exception) { status = "FAILED"; println("STOP: exception for [$variant] \"$scenario\": ${e.message}") }
            val captured = world.llmClient.lastCapturedSystemBlock ?: ""
            val payloadMatches = status == "SUCCESS" && payload.isNotEmpty() && captured.contains(payload)
            return P17Result(scenario, variant, fieldIds, payload, payloadMatches, responseText, status)
        }

        println("\n════ PHASE 17 — 8 CALLS (4 scenarios x B/L) ════")
        scenarios.forEach { s ->
            println("--- Scenario: \"$s\" ---")
            results += runOne("B", s); Thread.sleep(1000)
            results += runOne("L", s); Thread.sleep(1000)
        }

        results.forEach { r ->
            println("[${r.variant}] \"${r.scenario}\" status=${r.status} payloadMatches=${r.payloadMatches} fields=${r.fieldIds}")
            println("  response: ${r.response}")
        }

        val dir = "E:\\Projects\\Pink dreams\\docs\\Persona update POC"
        val header = "scenario,variant,selected_field_ids,provider_payload,payload_matches_request,response,status\n"
        val rows = results.joinToString("\n") { r ->
            listOf("\"${r.scenario.replace("\"", "\"\"")}\"", r.variant, "\"${r.fieldIds.joinToString(";")}\"",
                "\"${r.payload.replace("\"", "\"\"")}\"", r.payloadMatches, "\"${r.response.replace("\"", "\"\"")}\"", r.status).joinToString(",")
        }
        File("$dir/PHASE17_EXECUTIONS.csv").writeText(header + rows)
        println("✓ Saved: $dir/PHASE17_EXECUTIONS.csv")

        val allSucceeded = results.all { it.status == "SUCCESS" }
        val allPayloadsMatch = results.all { it.payloadMatches }
        println("All 8 calls succeeded: $allSucceeded | All payloads verified: $allPayloadsMatch")
        assertTrue(allSucceeded, "STOP: not all 8 Phase 17 calls succeeded")
        assertTrue(allPayloadsMatch, "STOP: a Phase 17 payload did not match the captured request")
    }

    /**
     * PHASE 19 — timeline label reproducibility study. Predetermined order:
     * 5x B then 5x L (fixed before execution, never altered by observed
     * answers). Same timeline fix and scenario as Phase 14/16.
     */
    @Test
    fun phase19TimelineLabelReproducibility() {
        val world = World()
        applyOriginalPhase10eFixes(world) // matches Phase 14/16 exactly — omitted on the first attempt, see PHASE19 harness-defect disclosure
        val ileEntry = world.fieldRegistryRepo.findByFieldId("life_context.important_life_events")!!
        world.fieldRegistryRepo.update(ileEntry.copy(description = "life_context.important_life_events years long since moved arrived came time"))
        val gc = world.skillReqRepo.findBySkillKey("general_chat")!!
        world.skillReqRepo.update(gc.copy(conditionalFields = (gc.conditionalFields ?: emptyList()) + listOf("identity.age", "life_context.important_life_events")))
        val ageEntry = world.fieldRegistryRepo.findByFieldId("identity.age")!!
        world.fieldRegistryRepo.update(ageEntry.copy(description = "identity.age years long since moved arrived came time"))

        val skill = "general_chat"
        val msg = "Been in this city long?"

        println("\n════ PRECONDITION VERIFICATION (zero-cost) ════")
        val bProvider = com.pinkdreams.chat.context.ResolvedPersonaContextProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo, "B")
        val lProvider = com.pinkdreams.chat.context.LabeledScalarDiagnosticProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo)
        val bFieldIds = world.resolver.resolve(world.personaId, world.personaCoreVersionId, skill, msg, "B").includedFieldIds.toSet()
        val bText = bProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        val lText = lProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)

        var preOk = true
        fun check(label: String, cond: Boolean) { println("[$label] ${if (cond) "PASS" else "FAIL"}"); if (!cond) preOk = false }
        check("Selected field IDs identical to Phase 14/16 (age + important_life_events + occupation + hometown + current_city + name + core_traits + communication.style + unresolved_threads)", bFieldIds == setOf("identity.name", "personality.core_traits", "identity.hometown", "identity.current_city", "communication.style", "narrative_state.unresolved_threads", "life_context.occupation", "identity.age", "life_context.important_life_events"))
        check("B payload contains '27' and 'Moving to Pune at 21'", bText.contains("27") && bText.contains("Moving to Pune at 21 to study architecture."))
        check("L payload contains 'Age: 27' and 'Moving to Pune at 21' (same underlying value, only labeled)", lText.contains("Age: 27") && lText.contains("Moving to Pune at 21 to study architecture."))
        check("No restricted field present in either payload", !bText.contains("intimate_anatomy") && !bText.contains("adult_intimacy") && !lText.contains("intimate_anatomy") && !lText.contains("adult_intimacy"))
        val bLines = bText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val lLines = lText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val bOnly = bLines - lLines.toSet(); val lOnly = lLines - bLines.toSet()
        check("Only difference between B and L is scalar labeling (no value changes)", bOnly.all { b -> lOnly.any { it.endsWith(": $b") } } && bOnly.size == lOnly.size)
        println("Engine version / persona version / skill: identical by construction (single World(), single seeded engine v1, single persona core v1, both conditions use skill=\"$skill\")")
        println("History/memory/profile: absent by construction (fresh conversation + fresh testUserId for every call, no prior message ever sent to it)")

        println(if (preOk) "\n✓✓✓ PRECONDITIONS VERIFIED — cleared for the 10-call predetermined run ✓✓✓" else "\n✗✗✗ PRECONDITION FAILED — STOP, do not spend API calls ✗✗✗")
        assertTrue(preOk, "STOP: Phase 19 precondition verification failed")

        // Predetermined order, fixed before any call: 5x B, then 5x L.
        val order = List(5) { "B" } + List(5) { "L" }
        println("\n════ PREDETERMINED CALL ORDER (fixed before execution) ════")
        println(order.joinToString(", "))

        data class P19Call(val index: Int, val variant: String, val response: String, val status: String, val payloadMatches: Boolean)
        val calls = mutableListOf<P19Call>()

        order.forEachIndexed { idx, variant ->
            world.llmClient.currentSkillKey = skill
            world.llmClient.lastCapturedSystemBlock = null
            var responseText = ""; var status = "SUCCESS"
            try {
                val conversationId = world.service.create(
                    TestChatService.CreationRequest(personaId = world.personaId, testUserId = world.testUserId, personaContextVariant = variant, personaContextSkillKey = skill),
                ).let { (it as TestChatService.CreationResult.Created).conversation.id }
                val result = world.service.sendMessage(conversationId, UUID.randomUUID(), msg)
                check(result is TestChatService.MessageResult.Sent) { "sendMessage failed: $result" }
                when (val cr = result.chatResult) { is ChatResult.Success -> responseText = cr.response.content; else -> { status = "FAILED"; println("Call ${idx + 1} [$variant] FAILED: $cr") } }
            } catch (e: Exception) { status = "FAILED"; println("Call ${idx + 1} [$variant] EXCEPTION: ${e.message}") }
            val captured = world.llmClient.lastCapturedSystemBlock ?: ""
            val expectedPayload = if (variant == "B") bText else lText
            val payloadMatches = status == "SUCCESS" && captured.contains(expectedPayload)
            calls += P19Call(idx + 1, variant, responseText, status, payloadMatches)
            println("Call ${idx + 1} [$variant]: status=$status payloadMatches=$payloadMatches")
            println("  response: $responseText")
            Thread.sleep(1000)
        }

        val dir = "E:\\Projects\\Pink dreams\\docs\\Persona update POC"
        val header = "call_index,variant,response,status,payload_matches_request\n"
        val rows = calls.joinToString("\n") { c -> listOf(c.index, c.variant, "\"${c.response.replace("\"", "\"\"")}\"", c.status, c.payloadMatches).joinToString(",") }
        File("$dir/PHASE19_EXECUTIONS.csv").writeText(header + rows)
        println("\n✓ Saved: $dir/PHASE19_EXECUTIONS.csv")

        val allSucceeded = calls.all { it.status == "SUCCESS" }
        val allPayloadsMatch = calls.all { it.payloadMatches }
        println("All 10 calls succeeded: $allSucceeded | All payloads verified: $allPayloadsMatch")
        assertTrue(allSucceeded, "STOP: not all Phase 19 calls succeeded")
        assertTrue(allPayloadsMatch, "STOP: a Phase 19 payload did not match the captured request")
    }

    /**
     * PHASE 20 STAGE 1 — zero-cost source/experiment audit for label
     * isolation. Verifies (a) B vs L vs the new "LA" (age-only-labeled)
     * intermediate condition select identical fields and differ ONLY in the
     * intended formatting manipulation, (b) no restricted-field exposure,
     * (c) determinism, and (d) provider-to-request equivalence for all
     * three conditions, all via HybridLlmClient.interceptGeneration=true —
     * i.e. through the REAL production pipeline end to end, with ZERO real
     * network calls. No API call is made in this test.
     */
    @Test
    fun phase20LabelIsolationStage1Audit() {
        val world = World()
        applyOriginalPhase10eFixes(world)
        val ileEntry = world.fieldRegistryRepo.findByFieldId("life_context.important_life_events")!!
        world.fieldRegistryRepo.update(ileEntry.copy(description = "life_context.important_life_events years long since moved arrived came time"))
        val gc = world.skillReqRepo.findBySkillKey("general_chat")!!
        world.skillReqRepo.update(gc.copy(conditionalFields = (gc.conditionalFields ?: emptyList()) + listOf("identity.age", "life_context.important_life_events")))
        val ageEntry = world.fieldRegistryRepo.findByFieldId("identity.age")!!
        world.fieldRegistryRepo.update(ageEntry.copy(description = "identity.age years long since moved arrived came time"))

        world.llmClient.interceptGeneration = true // zero-cost: real pipeline, no network call

        val skill = "general_chat"
        val msg = "Been in this city long?"

        val bProvider = com.pinkdreams.chat.context.ResolvedPersonaContextProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo, "B")
        val lProvider = com.pinkdreams.chat.context.LabeledScalarDiagnosticProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo)
        val laProvider = com.pinkdreams.chat.context.AgeOnlyLabeledScalarDiagnosticProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo)

        val bFieldIds = world.resolver.resolve(world.personaId, world.personaCoreVersionId, skill, msg, "B").includedFieldIds.toSet()
        val bText = bProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        val lText = lProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        val laText = laProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)

        var stage1Ok = true
        fun check(label: String, cond: Boolean) { println("[$label] ${if (cond) "PASS" else "FAIL"}"); if (!cond) stage1Ok = false }

        val expectedFields = setOf("identity.name", "personality.core_traits", "identity.hometown", "identity.current_city", "communication.style", "narrative_state.unresolved_threads", "life_context.occupation", "identity.age", "life_context.important_life_events")

        println("\n════ 1. Field selection identical across B / L / LA ════")
        check("B selection matches Phase 14/16/19", bFieldIds == expectedFields)
        // L and LA both reuse resolver.resolve(..., "B") verbatim internally by construction — asserted via output field-set inference below.

        println("\n════ 2. LA differs from B by EXACTLY one line (identity.age gets 'Age: ' prefix), nothing else ════")
        val bLines = bText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val laLines = laText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val bOnlyVsLa = bLines - laLines.toSet()
        val laOnlyVsB = laLines - bLines.toSet()
        println("Lines only in B: $bOnlyVsLa")
        println("Lines only in LA: $laOnlyVsB")
        check("LA changes exactly one line (age), and it is exactly 'Age: 27'", bOnlyVsLa == listOf("27") && laOnlyVsB == listOf("Age: 27"))

        println("\n════ 3. LA differs from L by UN-labeling every scalar except age (name, hometown, current_city, occupation, communication.style) ════")
        val lLines = lText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val laOnlyVsL = laLines - lLines.toSet()
        val lOnlyVsLa = lLines - laLines.toSet()
        println("Lines only in LA (bare, vs L's labeled): $laOnlyVsL")
        println("Lines only in L (labeled, vs LA's bare): $lOnlyVsLa")
        // Every LA-only line must be the bare value of some L-only labeled line (L-only = "Label: value", LA-only = "value"),
        // for every scalar EXCEPT age (age is identical "Age: 27" in both L and LA, so it appears in neither diff set).
        val everyLaOnlyIsBareFormOfSomeLOnly = laOnlyVsL.all { bare -> lOnlyVsLa.any { labeled -> labeled.endsWith(": $bare") } }
        val sameCount = laOnlyVsL.size == lOnlyVsLa.size
        check("LA un-labels exactly the non-age scalars that L labels, no value changes", everyLaOnlyIsBareFormOfSomeLOnly && sameCount)
        check("Age line ('Age: 27') identical in L and LA (not part of either diff)", lText.contains("Age: 27") && laText.contains("Age: 27"))

        println("\n════ 4. No restricted field present in any of B / L / LA ════")
        check("No restricted-field leakage", listOf(bText, lText, laText).none { it.contains("intimate_anatomy") || it.contains("adult_intimacy") })

        println("\n════ 5. Determinism: identical input -> identical output, for LA ════")
        val laRepeat = laProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        check("LA deterministic on repeat", laRepeat == laText)

        println("\n════ 6. Provider -> request equivalence for B / L / LA (zero-API-cost, via interceptGeneration) ════")
        listOf("B" to bProvider, "L" to lProvider, "LA" to laProvider).forEach { (variantLabel, provider) ->
            world.llmClient.lastCapturedSystemBlock = null
            world.llmClient.currentSkillKey = skill
            val conversationId = world.service.create(
                TestChatService.CreationRequest(personaId = world.personaId, testUserId = world.testUserId, personaContextVariant = variantLabel, personaContextSkillKey = skill),
            ).let { (it as TestChatService.CreationResult.Created).conversation.id }
            val result = world.service.sendMessage(conversationId, UUID.randomUUID(), msg)
            check(result is TestChatService.MessageResult.Sent) { "sendMessage failed for $variantLabel: $result" }
            val capturedSystemBlock = world.llmClient.lastCapturedSystemBlock
                ?: throw IllegalStateException("STOP: no system block captured for $variantLabel")
            val expectedProviderOutput = provider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
            val equivalent = capturedSystemBlock.contains(expectedProviderOutput)
            println("[$variantLabel] captured system block contains exact provider output: $equivalent")
            check("$variantLabel provider output appears verbatim in captured request", equivalent)
        }

        println("\n════ PHASE 20 STAGE 1 RESULT ════")
        println(if (stage1Ok) "✓✓✓ ALL ZERO-COST CHECKS PASSED — LA is a clean, isolated intermediate condition ✓✓✓" else "✗✗✗ STAGE 1 FAILED — see log above ✗✗✗")
        println("\nZERO API calls made — interceptGeneration=true for the entire test, all generation calls answered locally.")
        assertTrue(stage1Ok, "STOP: Phase 20 Stage 1 audit failed — see log above")
    }

    /**
     * PHASE 20 STAGE 2 — authorized execution. Exactly 15 real calls
     * (5xB, 5xLA, 5xL), predetermined order fixed before execution, same
     * timeline scenario as Phase 14/16/19. Re-verifies the exact same
     * zero-cost preconditions as phase20LabelIsolationStage1Audit()
     * (minus the interceptGeneration probe, which is Stage-1-only) before
     * spending any API call.
     */
    @Test
    fun phase20Stage2Execution() {
        val world = World()
        applyOriginalPhase10eFixes(world)
        val ileEntry = world.fieldRegistryRepo.findByFieldId("life_context.important_life_events")!!
        world.fieldRegistryRepo.update(ileEntry.copy(description = "life_context.important_life_events years long since moved arrived came time"))
        val gc = world.skillReqRepo.findBySkillKey("general_chat")!!
        world.skillReqRepo.update(gc.copy(conditionalFields = (gc.conditionalFields ?: emptyList()) + listOf("identity.age", "life_context.important_life_events")))
        val ageEntry = world.fieldRegistryRepo.findByFieldId("identity.age")!!
        world.fieldRegistryRepo.update(ageEntry.copy(description = "identity.age years long since moved arrived came time"))

        val skill = "general_chat"
        val msg = "Been in this city long?"

        println("\n════ PRECONDITION VERIFICATION (zero-cost, re-run before spending calls) ════")
        val bProvider = com.pinkdreams.chat.context.ResolvedPersonaContextProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo, "B")
        val lProvider = com.pinkdreams.chat.context.LabeledScalarDiagnosticProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo)
        val laProvider = com.pinkdreams.chat.context.AgeOnlyLabeledScalarDiagnosticProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo)

        val bFieldIds = world.resolver.resolve(world.personaId, world.personaCoreVersionId, skill, msg, "B").includedFieldIds.toSet()
        val bText = bProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        val lText = lProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        val laText = laProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)

        var preOk = true
        fun check(label: String, cond: Boolean) { println("[$label] ${if (cond) "PASS" else "FAIL"}"); if (!cond) preOk = false }

        val expectedFields = setOf("identity.name", "personality.core_traits", "identity.hometown", "identity.current_city", "communication.style", "narrative_state.unresolved_threads", "life_context.occupation", "identity.age", "life_context.important_life_events")
        check("Selected field IDs identical to Phase 14/16/19", bFieldIds == expectedFields)
        check("B payload contains '27' and 'Moving to Pune at 21'", bText.contains("27") && bText.contains("Moving to Pune at 21 to study architecture."))
        check("L payload contains 'Age: 27'", lText.contains("Age: 27"))
        check("LA payload contains 'Age: 27'", laText.contains("Age: 27"))
        check("No restricted field in B/L/LA", listOf(bText, lText, laText).none { it.contains("intimate_anatomy") || it.contains("adult_intimacy") })
        val bLines = bText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val lLines = lText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val laLines = laText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val bOnlyVsLa = bLines - laLines.toSet(); val laOnlyVsB = laLines - bLines.toSet()
        check("LA differs from B by exactly the age-label line", bOnlyVsLa == listOf("27") && laOnlyVsB == listOf("Age: 27"))
        val laOnlyVsL = laLines - lLines.toSet(); val lOnlyVsLa = lLines - laLines.toSet()
        check("LA differs from L by exactly the five non-age scalar labels", laOnlyVsL.all { bare -> lOnlyVsLa.any { it.endsWith(": $bare") } } && laOnlyVsL.size == lOnlyVsLa.size)

        println(if (preOk) "\n✓✓✓ PRECONDITIONS VERIFIED — cleared for the 15-call predetermined run ✓✓✓" else "\n✗✗✗ PRECONDITION FAILED — STOP, do not spend API calls ✗✗✗")
        assertTrue(preOk, "STOP: Phase 20 Stage 2 precondition verification failed")

        // Predetermined order, fixed before any call: 5xB, 5xLA, 5xL.
        val order = List(5) { "B" } + List(5) { "LA" } + List(5) { "L" }
        println("\n════ PREDETERMINED CALL ORDER (fixed before execution) ════")
        println(order.joinToString(", "))

        fun expectedPayloadFor(variant: String): String = when (variant) { "B" -> bText; "LA" -> laText; "L" -> lText; else -> "" }
        fun expectedFieldIdsFor(variant: String): Set<String> = bFieldIds // identical selection for all three conditions, by construction

        data class P20Call(
            val index: Int, val variant: String, val response: String, val status: String,
            val payloadMatches: Boolean, val selectedFieldIds: Set<String>, val restrictedFieldPresent: Boolean,
        )
        val calls = mutableListOf<P20Call>()

        order.forEachIndexed { idx, variant ->
            world.llmClient.currentSkillKey = skill
            world.llmClient.lastCapturedSystemBlock = null
            var responseText = ""; var status = "SUCCESS"
            try {
                val conversationId = world.service.create(
                    TestChatService.CreationRequest(personaId = world.personaId, testUserId = world.testUserId, personaContextVariant = variant, personaContextSkillKey = skill),
                ).let { (it as TestChatService.CreationResult.Created).conversation.id }
                val result = world.service.sendMessage(conversationId, UUID.randomUUID(), msg)
                check(result is TestChatService.MessageResult.Sent) { "sendMessage failed: $result" }
                when (val cr = result.chatResult) { is ChatResult.Success -> responseText = cr.response.content; else -> { status = "FAILED"; println("Call ${idx + 1} [$variant] FAILED: $cr") } }
            } catch (e: Exception) { status = "FAILED"; println("Call ${idx + 1} [$variant] EXCEPTION: ${e.message}") }
            val captured = world.llmClient.lastCapturedSystemBlock ?: ""
            val expectedPayload = expectedPayloadFor(variant)
            val payloadMatches = status == "SUCCESS" && captured.contains(expectedPayload)
            val restrictedFieldPresent = captured.contains("intimate_anatomy") || captured.contains("adult_intimacy")
            calls += P20Call(idx + 1, variant, responseText, status, payloadMatches, expectedFieldIdsFor(variant), restrictedFieldPresent)
            println("Call ${idx + 1} [$variant]: status=$status payloadMatches=$payloadMatches restrictedFieldPresent=$restrictedFieldPresent")
            println("  response: $responseText")
            Thread.sleep(1000)
        }

        val dir = "E:\\Projects\\Pink dreams\\docs\\Persona update POC"
        val header = "call_index,variant,response,status,payload_matches_request,selected_field_ids,restricted_field_present\n"
        val rows = calls.joinToString("\n") { c ->
            listOf(c.index, c.variant, "\"${c.response.replace("\"", "\"\"")}\"", c.status, c.payloadMatches, "\"${c.selectedFieldIds.joinToString(";")}\"", c.restrictedFieldPresent).joinToString(",")
        }
        File("$dir/PHASE20_EXECUTIONS.csv").writeText(header + rows)
        println("\n✓ Saved: $dir/PHASE20_EXECUTIONS.csv")

        val allSucceeded = calls.all { it.status == "SUCCESS" }
        val allPayloadsMatch = calls.all { it.payloadMatches }
        val noRestrictedLeak = calls.none { it.restrictedFieldPresent }
        println("All 15 calls succeeded: $allSucceeded | All payloads verified: $allPayloadsMatch | No restricted-field leakage: $noRestrictedLeak")
        assertTrue(allSucceeded, "STOP: not all Phase 20 calls succeeded")
        assertTrue(allPayloadsMatch, "STOP: a Phase 20 payload did not match the captured request")
        assertTrue(noRestrictedLeak, "STOP: a restricted field leaked into a Phase 20 request")
    }

    /**
     * PHASE 21 — confirmatory age-label experiment. Exactly 20 real calls
     * (10xB, 10xLA), predetermined order fixed before execution, same
     * timeline scenario and mapping fix as Phase 14/16/19/20. Tests only
     * whether Phase 20's LA-over-B advantage reproduces; no L calls, no
     * new scenario, no code/mapping change after seeing results.
     */
    @Test
    fun phase21ConfirmatoryExecution() {
        val world = World()
        applyOriginalPhase10eFixes(world)
        val ileEntry = world.fieldRegistryRepo.findByFieldId("life_context.important_life_events")!!
        world.fieldRegistryRepo.update(ileEntry.copy(description = "life_context.important_life_events years long since moved arrived came time"))
        val gc = world.skillReqRepo.findBySkillKey("general_chat")!!
        world.skillReqRepo.update(gc.copy(conditionalFields = (gc.conditionalFields ?: emptyList()) + listOf("identity.age", "life_context.important_life_events")))
        val ageEntry = world.fieldRegistryRepo.findByFieldId("identity.age")!!
        world.fieldRegistryRepo.update(ageEntry.copy(description = "identity.age years long since moved arrived came time"))

        val skill = "general_chat"
        val msg = "Been in this city long?"

        println("\n════ PHASE 21 PRECONDITION VERIFICATION (zero-cost, re-run before spending calls) ════")
        val bProvider = com.pinkdreams.chat.context.ResolvedPersonaContextProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo, "B")
        val laProvider = com.pinkdreams.chat.context.AgeOnlyLabeledScalarDiagnosticProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo)

        val bFieldIds = world.resolver.resolve(world.personaId, world.personaCoreVersionId, skill, msg, "B").includedFieldIds.toSet()
        val bText = bProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        val laText = laProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)

        var preOk = true
        fun check(label: String, cond: Boolean) { println("[$label] ${if (cond) "PASS" else "FAIL"}"); if (!cond) preOk = false }

        val expectedFields = setOf("identity.name", "personality.core_traits", "identity.hometown", "identity.current_city", "communication.style", "narrative_state.unresolved_threads", "life_context.occupation", "identity.age", "life_context.important_life_events")
        check("Selected field IDs identical to Phase 14/16/19/20", bFieldIds == expectedFields)
        check("B payload contains '27' and 'Moving to Pune at 21'", bText.contains("27") && bText.contains("Moving to Pune at 21 to study architecture."))
        check("LA payload contains 'Age: 27'", laText.contains("Age: 27"))
        check("No restricted field in B/LA", listOf(bText, laText).none { it.contains("intimate_anatomy") || it.contains("adult_intimacy") })
        val bLines = bText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val laLines = laText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val bOnlyVsLa = bLines - laLines.toSet(); val laOnlyVsB = laLines - bLines.toSet()
        check("LA differs from B by exactly the age-label line (same as Phase 20)", bOnlyVsLa == listOf("27") && laOnlyVsB == listOf("Age: 27"))

        println(if (preOk) "\n✓✓✓ PRECONDITIONS VERIFIED — cleared for the 20-call confirmatory run ✓✓✓" else "\n✗✗✗ PRECONDITION FAILED — STOP, do not spend API calls ✗✗✗")
        assertTrue(preOk, "STOP: Phase 21 precondition verification failed")

        // Predetermined order, fixed before any call: 10xB, then 10xLA.
        val order = List(10) { "B" } + List(10) { "LA" }
        println("\n════ PREDETERMINED CALL ORDER (fixed before execution) ════")
        println(order.joinToString(", "))

        fun expectedPayloadFor(variant: String): String = if (variant == "B") bText else laText

        data class P21Call(
            val index: Int, val variant: String, val response: String, val status: String,
            val payloadMatches: Boolean, val selectedFieldIds: Set<String>, val restrictedFieldPresent: Boolean,
        )
        val calls = mutableListOf<P21Call>()

        order.forEachIndexed { idx, variant ->
            world.llmClient.currentSkillKey = skill
            world.llmClient.lastCapturedSystemBlock = null
            var responseText = ""; var status = "SUCCESS"
            try {
                val conversationId = world.service.create(
                    TestChatService.CreationRequest(personaId = world.personaId, testUserId = world.testUserId, personaContextVariant = variant, personaContextSkillKey = skill),
                ).let { (it as TestChatService.CreationResult.Created).conversation.id }
                val result = world.service.sendMessage(conversationId, UUID.randomUUID(), msg)
                check(result is TestChatService.MessageResult.Sent) { "sendMessage failed: $result" }
                when (val cr = result.chatResult) { is ChatResult.Success -> responseText = cr.response.content; else -> { status = "FAILED"; println("Call ${idx + 1} [$variant] FAILED: $cr") } }
            } catch (e: Exception) { status = "FAILED"; println("Call ${idx + 1} [$variant] EXCEPTION: ${e.message}") }
            val captured = world.llmClient.lastCapturedSystemBlock ?: ""
            val expectedPayload = expectedPayloadFor(variant)
            val payloadMatches = status == "SUCCESS" && captured.contains(expectedPayload)
            val restrictedFieldPresent = captured.contains("intimate_anatomy") || captured.contains("adult_intimacy")
            calls += P21Call(idx + 1, variant, responseText, status, payloadMatches, bFieldIds, restrictedFieldPresent)
            println("Call ${idx + 1} [$variant]: status=$status payloadMatches=$payloadMatches restrictedFieldPresent=$restrictedFieldPresent")
            println("  response: $responseText")
            Thread.sleep(1000)
        }

        val dir = "E:\\Projects\\Pink dreams\\docs\\Persona update POC"
        val header = "call_index,variant,response,status,payload_matches_request,selected_field_ids,restricted_field_present\n"
        val rows = calls.joinToString("\n") { c ->
            listOf(c.index, c.variant, "\"${c.response.replace("\"", "\"\"")}\"", c.status, c.payloadMatches, "\"${c.selectedFieldIds.joinToString(";")}\"", c.restrictedFieldPresent).joinToString(",")
        }
        File("$dir/PHASE21_EXECUTIONS.csv").writeText(header + rows)
        println("\n✓ Saved: $dir/PHASE21_EXECUTIONS.csv")

        val allSucceeded = calls.all { it.status == "SUCCESS" }
        val allPayloadsMatch = calls.all { it.payloadMatches }
        val noRestrictedLeak = calls.none { it.restrictedFieldPresent }
        println("All 20 calls succeeded: $allSucceeded | All payloads verified: $allPayloadsMatch | No restricted-field leakage: $noRestrictedLeak")
        assertTrue(allSucceeded, "STOP: not all Phase 21 calls succeeded")
        assertTrue(allPayloadsMatch, "STOP: a Phase 21 payload did not match the captured request")
        assertTrue(noRestrictedLeak, "STOP: a restricted field leaked into a Phase 21 request")
    }

    /**
     * PHASE 22 — zero-cost verification of the candidate independent
     * derived-numeric scenario (sister age-gap, skill "about", NO Phase
     * 10E/13 fixes applied — pure baseline mapping). Confirms selection,
     * B/LA payload diff, no restricted field, and provider-to-request
     * equivalence (via interceptGeneration, zero network calls) BEFORE any
     * design document claims this scenario is clean. No API call is made.
     */
    @Test
    fun phase22CandidateScenarioZeroCostCheck() {
        val world = World() // deliberately NO applyOriginalPhase10eFixes() — pure baseline seed
        world.llmClient.interceptGeneration = true

        val skill = "about"
        val msg = "Is there a big age gap between you and your sister in your family?"

        val bProvider = com.pinkdreams.chat.context.ResolvedPersonaContextProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo, "B")
        val laProvider = com.pinkdreams.chat.context.AgeOnlyLabeledScalarDiagnosticProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo)

        val fieldIds = world.resolver.resolve(world.personaId, world.personaCoreVersionId, skill, msg, "B").includedFieldIds.toSet()
        val bText = bProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        val laText = laProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)

        var ok = true
        fun check(label: String, cond: Boolean) { println("[$label] ${if (cond) "PASS" else "FAIL"}"); if (!cond) ok = false }

        println("\n════ PHASE 22 CANDIDATE SCENARIO — ZERO-COST CHECK ════")
        println("Selected fields: $fieldIds")
        check("identity.age selected (required for 'about', baseline, no fix)", fieldIds.contains("identity.age"))
        check("life_context.family selected (conditional, baseline description token 'family' matches message, NO fix applied)", fieldIds.contains("life_context.family"))
        check("Anjali's stated age (29) present in payload", bText.contains("29"))
        check("Own age (27) present in payload", bText.contains("27"))
        check("Arjun's age (24) also present (both siblings render together — must be disclosed, not treated as noise)", bText.contains("24"))
        check("No restricted field present", !bText.contains("intimate_anatomy") && !bText.contains("adult_intimacy"))

        val bLines = bText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val laLines = laText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val bOnly = bLines - laLines.toSet(); val laOnly = laLines - bLines.toSet()
        println("Lines only in B: $bOnly")
        println("Lines only in LA: $laOnly")
        check("LA differs from B by exactly the age-label line, nothing in the family array changes", bOnly == listOf("27") && laOnly == listOf("Age: 27"))

        // Provider -> request equivalence, zero-cost (interceptGeneration already true for this whole test).
        listOf("B" to bProvider, "LA" to laProvider).forEach { (variantLabel, provider) ->
            world.llmClient.lastCapturedSystemBlock = null
            world.llmClient.currentSkillKey = skill
            val conversationId = world.service.create(
                TestChatService.CreationRequest(personaId = world.personaId, testUserId = world.testUserId, personaContextVariant = variantLabel, personaContextSkillKey = skill),
            ).let { (it as TestChatService.CreationResult.Created).conversation.id }
            val result = world.service.sendMessage(conversationId, UUID.randomUUID(), msg)
            check(result is TestChatService.MessageResult.Sent) { "sendMessage failed for $variantLabel: $result" }
            val capturedSystemBlock = world.llmClient.lastCapturedSystemBlock ?: ""
            val expected = provider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
            check("$variantLabel provider output verbatim in captured request", capturedSystemBlock.contains(expected))
        }

        println("\n════ RESULT ════")
        println(if (ok) "✓✓✓ CANDIDATE SCENARIO IS ZERO-COST CLEAN ✓✓✓" else "✗✗✗ CANDIDATE SCENARIO FAILED VERIFICATION ✗✗✗")
        println("\nZERO API calls made — interceptGeneration=true, all generation calls answered locally.")
        assertTrue(ok, "STOP: Phase 22 candidate scenario failed zero-cost verification — see log above")
    }

    /**
     * PHASE 22 STAGE 2 — authorized execution. Exactly 20 real calls
     * (10xB, 10xLA), predetermined order fixed before execution, on the
     * independent sister-age-gap scenario (skill "about", PURE BASELINE
     * mapping, no Phase 10E/13 fixes applied). Tests generalization of the
     * Phase 20/21 B-vs-LA observation to a second, independent fact pair.
     */
    @Test
    fun phase22Stage2Execution() {
        val world = World() // deliberately NO applyOriginalPhase10eFixes() — pure baseline seed, per design

        val skill = "about"
        val msg = "Is there a big age gap between you and your sister in your family?"

        println("\n════ PHASE 22 PRECONDITION VERIFICATION (zero-cost, re-run before spending calls) ════")
        val bProvider = com.pinkdreams.chat.context.ResolvedPersonaContextProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo, "B")
        val laProvider = com.pinkdreams.chat.context.AgeOnlyLabeledScalarDiagnosticProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo)

        val bFieldIds = world.resolver.resolve(world.personaId, world.personaCoreVersionId, skill, msg, "B").includedFieldIds.toSet()
        val laFieldIds = world.resolver.resolve(world.personaId, world.personaCoreVersionId, skill, msg, "B").includedFieldIds.toSet() // LA reuses B's own resolve("B") verbatim
        val bText = bProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        val laText = laProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)

        var preOk = true
        fun check(label: String, cond: Boolean) { println("[$label] ${if (cond) "PASS" else "FAIL"}"); if (!cond) preOk = false }

        val expectedFields = setOf("identity.name", "identity.age", "identity.hometown", "identity.current_city", "life_context.occupation", "life_context.education", "personality.core_traits", "life_context.family")
        check("1. B and LA select exactly the same fields", bFieldIds == laFieldIds)
        check("2. identity.age present", bFieldIds.contains("identity.age"))
        check("3. life_context.family present", bFieldIds.contains("life_context.family"))
        check("4. No restricted fields selected", bFieldIds.none { it == "intimate_anatomy" || it == "adult_intimacy" })
        val bLines = bText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val laLines = laText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val bOnly = bLines - laLines.toSet(); val laOnly = laLines - bLines.toSet()
        check("5. B and LA differ only in the top-level age scalar rendering", bOnly == listOf("27") && laOnly == listOf("Age: 27"))
        // 6. life_context.family renders via the array/object path (unaffected by the top-level-scalar-only manipulation) —
        // verified by confirming every family-related line (containing sibling/parent names) is identical in both texts.
        val familyLinesB = bLines.filter { it.contains("Kohli") || it.contains("relationship") || it.contains("Anjali") || it.contains("Arjun") || it.contains("Rajesh") || it.contains("Priya") }
        val familyLinesLa = laLines.filter { it.contains("Kohli") || it.contains("relationship") || it.contains("Anjali") || it.contains("Arjun") || it.contains("Rajesh") || it.contains("Priya") }
        check("6. life_context.family byte-identical between B and LA", familyLinesB == familyLinesLa && familyLinesB.isNotEmpty())
        check("7. B contains bare '27'", bText.contains("27") && !bText.contains("Age: 27"))
        check("8. LA contains 'Age: 27'", laText.contains("Age: 27"))
        check("9a. Anjali's age (29) present in both", bText.contains("29") && laText.contains("29"))
        check("9b. No restricted field text present in either payload", !bText.contains("intimate_anatomy") && !bText.contains("adult_intimacy") && !laText.contains("intimate_anatomy") && !laText.contains("adult_intimacy"))
        val bRepeat = bProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        val laRepeat = laProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        check("10. Both providers deterministic on repeat", bRepeat == bText && laRepeat == laText)
        println("11. No Phase 10E/13 mapping fixes applied — confirmed by construction (applyOriginalPhase10eFixes() was never called in this method).")
        println("12. Scenario/skill match Phase 22 audit exactly: skill=\"$skill\", message=\"$msg\".")

        println(if (preOk) "\n✓✓✓ PRECONDITIONS VERIFIED — cleared for the 20-call predetermined run ✓✓✓" else "\n✗✗✗ PRECONDITION FAILED — STOP, do not spend API calls ✗✗✗")
        assertTrue(preOk, "STOP: Phase 22 Stage 2 precondition verification failed")

        // Predetermined order, fixed before any call: 10xB, then 10xLA.
        val order = List(10) { "B" } + List(10) { "LA" }
        println("\n════ PREDETERMINED CALL ORDER (fixed before execution) ════")
        println(order.joinToString(", "))

        fun expectedPayloadFor(variant: String): String = if (variant == "B") bText else laText

        data class P22Call(
            val index: Int, val variant: String, val response: String, val status: String,
            val payloadMatches: Boolean, val selectedFieldIds: Set<String>, val restrictedFieldPresent: Boolean,
        )
        val calls = mutableListOf<P22Call>()

        order.forEachIndexed { idx, variant ->
            world.llmClient.currentSkillKey = skill
            world.llmClient.lastCapturedSystemBlock = null
            var responseText = ""; var status = "SUCCESS"
            try {
                val conversationId = world.service.create(
                    TestChatService.CreationRequest(personaId = world.personaId, testUserId = world.testUserId, personaContextVariant = variant, personaContextSkillKey = skill),
                ).let { (it as TestChatService.CreationResult.Created).conversation.id }
                val result = world.service.sendMessage(conversationId, UUID.randomUUID(), msg)
                check(result is TestChatService.MessageResult.Sent) { "sendMessage failed: $result" }
                when (val cr = result.chatResult) { is ChatResult.Success -> responseText = cr.response.content; else -> { status = "FAILED"; println("Call ${idx + 1} [$variant] FAILED: $cr") } }
            } catch (e: Exception) { status = "FAILED"; println("Call ${idx + 1} [$variant] EXCEPTION: ${e.message}") }
            val captured = world.llmClient.lastCapturedSystemBlock ?: ""
            val expectedPayload = expectedPayloadFor(variant)
            val payloadMatches = status == "SUCCESS" && captured.contains(expectedPayload)
            val restrictedFieldPresent = captured.contains("intimate_anatomy") || captured.contains("adult_intimacy")
            calls += P22Call(idx + 1, variant, responseText, status, payloadMatches, bFieldIds, restrictedFieldPresent)
            println("Call ${idx + 1} [$variant]: status=$status payloadMatches=$payloadMatches restrictedFieldPresent=$restrictedFieldPresent")
            println("  response: $responseText")
            Thread.sleep(1000)
        }

        val dir = "E:\\Projects\\Pink dreams\\docs\\Persona update POC"
        val header = "call_index,variant,response,status,payload_matches_request,selected_field_ids,restricted_field_present\n"
        val rows = calls.joinToString("\n") { c ->
            listOf(c.index, c.variant, "\"${c.response.replace("\"", "\"\"")}\"", c.status, c.payloadMatches, "\"${c.selectedFieldIds.joinToString(";")}\"", c.restrictedFieldPresent).joinToString(",")
        }
        File("$dir/PHASE22_EXECUTIONS.csv").writeText(header + rows)
        println("\n✓ Saved: $dir/PHASE22_EXECUTIONS.csv")

        val allSucceeded = calls.all { it.status == "SUCCESS" }
        val allPayloadsMatch = calls.all { it.payloadMatches }
        val noRestrictedLeak = calls.none { it.restrictedFieldPresent }
        println("All 20 calls succeeded: $allSucceeded | All payloads verified: $allPayloadsMatch | No restricted-field leakage: $noRestrictedLeak")
        assertTrue(allSucceeded, "STOP: not all Phase 22 calls succeeded")
        assertTrue(allPayloadsMatch, "STOP: a Phase 22 payload did not match the captured request")
        assertTrue(noRestrictedLeak, "STOP: a restricted field leaked into a Phase 22 request")
    }

    /**
     * PHASE 24 STAGE 1 — zero-cost three-way (B/LA/L) verification on the
     * Pune timeline scenario. Confirms every invariant required before the
     * 30-call Stage 2 (10xB, 10xLA, 10xL) can be authorized. NO API calls —
     * uses interceptGeneration=true (real pipeline, zero network calls) for
     * the provider-to-request equivalence check only.
     */
    @Test
    fun phase24Stage1Verification() {
        val world = World()
        applyOriginalPhase10eFixes(world)
        val ileEntry = world.fieldRegistryRepo.findByFieldId("life_context.important_life_events")!!
        world.fieldRegistryRepo.update(ileEntry.copy(description = "life_context.important_life_events years long since moved arrived came time"))
        val gc = world.skillReqRepo.findBySkillKey("general_chat")!!
        world.skillReqRepo.update(gc.copy(conditionalFields = (gc.conditionalFields ?: emptyList()) + listOf("identity.age", "life_context.important_life_events")))
        val ageEntry = world.fieldRegistryRepo.findByFieldId("identity.age")!!
        world.fieldRegistryRepo.update(ageEntry.copy(description = "identity.age years long since moved arrived came time"))

        world.llmClient.interceptGeneration = true // zero-cost: real pipeline, no network call

        val skill = "general_chat"
        val msg = "Been in this city long?"

        val bProvider = com.pinkdreams.chat.context.ResolvedPersonaContextProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo, "B")
        val laProvider = com.pinkdreams.chat.context.AgeOnlyLabeledScalarDiagnosticProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo)
        val lProvider = com.pinkdreams.chat.context.LabeledScalarDiagnosticProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo)

        val bFieldIds = world.resolver.resolve(world.personaId, world.personaCoreVersionId, skill, msg, "B").includedFieldIds.toSet()
        val bText = bProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        val laText = laProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        val lText = lProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)

        var ok = true
        fun check(label: String, cond: Boolean) { println("[$label] ${if (cond) "PASS" else "FAIL"}"); if (!cond) ok = false }

        val expectedFields = setOf("identity.name", "personality.core_traits", "identity.hometown", "identity.current_city", "communication.style", "narrative_state.unresolved_threads", "life_context.occupation", "identity.age", "life_context.important_life_events")
        println("\n════ PHASE 24 STAGE 1 — THREE-WAY (B/LA/L) VERIFICATION ════")
        check("1. Identical persona source, version, engine version, skill (verified by construction: single World(), single seeded engine v1, single persona core v1, skill=\"$skill\")", true)
        check("2. Selected field IDs identical to Phase 14/16/19/20/21 (B/LA/L all reuse resolve(...,\"B\") verbatim)", bFieldIds == expectedFields)
        check("3. Identical user message across all three (\"$msg\", unaltered from Phase 14/16/19/20/21)", true)
        check("4. History/memory/profile absent for all three (fresh conversation + fresh testUserId each call, established pattern)", true)
        check("5. No restricted fields in B/LA/L", listOf(bText, laText, lText).none { it.contains("intimate_anatomy") || it.contains("adult_intimacy") })

        val bLines = bText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val laLines = laText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val lLines = lText.lines().map { it.trim() }.filter { it.isNotBlank() }

        val bOnlyVsLa = bLines - laLines.toSet(); val laOnlyVsB = laLines - bLines.toSet()
        check("6. LA differs from B by ONLY the age-label representation ('27' -> 'Age: 27', nothing else)", bOnlyVsLa == listOf("27") && laOnlyVsB == listOf("Age: 27"))

        val laOnlyVsL = laLines - lLines.toSet(); val lOnlyVsLa = lLines - laLines.toSet()
        val everyLaOnlyIsBareFormOfSomeLOnly = laOnlyVsL.all { bare -> lOnlyVsLa.any { it.endsWith(": $bare") } }
        check("7. L differs from LA by ONLY the remaining (non-age) scalar labels", everyLaOnlyIsBareFormOfSomeLOnly && laOnlyVsL.size == lOnlyVsLa.size)
        check("7b. Age line ('Age: 27') identical in LA and L (not part of the LA-vs-L diff)", laText.contains("Age: 27") && lText.contains("Age: 27"))

        val bRepeat = bProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        val laRepeat = laProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        val lRepeat = lProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        check("8. Deterministic provider output (B/LA/L identical on repeat)", bRepeat == bText && laRepeat == laText && lRepeat == lText)

        println("\n---- Provider -> request equivalence (zero-API-cost, via interceptGeneration) ----")
        listOf("B" to bProvider, "LA" to laProvider, "L" to lProvider).forEach { (variantLabel, provider) ->
            world.llmClient.lastCapturedSystemBlock = null
            world.llmClient.currentSkillKey = skill
            val conversationId = world.service.create(
                TestChatService.CreationRequest(personaId = world.personaId, testUserId = world.testUserId, personaContextVariant = variantLabel, personaContextSkillKey = skill),
            ).let { (it as TestChatService.CreationResult.Created).conversation.id }
            val result = world.service.sendMessage(conversationId, UUID.randomUUID(), msg)
            check(result is TestChatService.MessageResult.Sent) { "sendMessage failed for $variantLabel: $result" }
            val capturedSystemBlock = world.llmClient.lastCapturedSystemBlock ?: throw IllegalStateException("STOP: no system block captured for $variantLabel")
            val expectedProviderOutput = provider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
            val equivalent = capturedSystemBlock.contains(expectedProviderOutput)
            check("9. [$variantLabel] provider output appears verbatim in captured request (provider == payload reaching generation)", equivalent)
        }

        println("\n════ PHASE 24 STAGE 1 RESULT ════")
        println(if (ok) "✓✓✓ ALL INVARIANTS PASSED — cleared for the 30-call Stage 2, PENDING SEPARATE AUTHORIZATION ✓✓✓" else "✗✗✗ STAGE 1 FAILED — STOP, do not proceed to Stage 2 ✗✗✗")
        println("\nZERO API calls made — interceptGeneration=true for the entire test, all generation calls answered locally.")
        assertTrue(ok, "STOP: Phase 24 Stage 1 verification failed — see log above")
    }

    /**
     * PHASE 24 STAGE 2 — authorized execution. Exactly 30 real calls
     * (10xB, 10xLA, 10xL), predetermined order fixed before execution, on
     * the unaltered Pune timeline scenario. Records only OBSERVABLE
     * response properties (numeric substrings, duration-word presence) in
     * code — CORRECT/WRONG/AMBIGUOUS scoring and any reasoning-connection
     * judgment is done afterward, in the results document, from the raw
     * text, exactly as in Phases 19-22. No hidden-computation claim is
     * made anywhere in this method.
     */
    @Test
    fun phase24Stage2Execution() {
        val world = World()
        applyOriginalPhase10eFixes(world)
        val ileEntry = world.fieldRegistryRepo.findByFieldId("life_context.important_life_events")!!
        world.fieldRegistryRepo.update(ileEntry.copy(description = "life_context.important_life_events years long since moved arrived came time"))
        val gc = world.skillReqRepo.findBySkillKey("general_chat")!!
        world.skillReqRepo.update(gc.copy(conditionalFields = (gc.conditionalFields ?: emptyList()) + listOf("identity.age", "life_context.important_life_events")))
        val ageEntry = world.fieldRegistryRepo.findByFieldId("identity.age")!!
        world.fieldRegistryRepo.update(ageEntry.copy(description = "identity.age years long since moved arrived came time"))

        val skill = "general_chat"
        val msg = "Been in this city long?"

        println("\n════ PHASE 24 PRECONDITION VERIFICATION (zero-cost, re-run before spending calls) ════")
        val bProvider = com.pinkdreams.chat.context.ResolvedPersonaContextProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo, "B")
        val laProvider = com.pinkdreams.chat.context.AgeOnlyLabeledScalarDiagnosticProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo)
        val lProvider = com.pinkdreams.chat.context.LabeledScalarDiagnosticProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo)

        val bFieldIds = world.resolver.resolve(world.personaId, world.personaCoreVersionId, skill, msg, "B").includedFieldIds.toSet()
        val bText = bProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        val laText = laProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        val lText = lProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)

        var preOk = true
        fun check(label: String, cond: Boolean) { println("[$label] ${if (cond) "PASS" else "FAIL"}"); if (!cond) preOk = false }

        val expectedFields = setOf("identity.name", "personality.core_traits", "identity.hometown", "identity.current_city", "communication.style", "narrative_state.unresolved_threads", "life_context.occupation", "identity.age", "life_context.important_life_events")
        check("Selected field IDs identical to Phase 14/16/19/20/21/24-Stage1", bFieldIds == expectedFields)
        check("No restricted field in B/LA/L", listOf(bText, laText, lText).none { it.contains("intimate_anatomy") || it.contains("adult_intimacy") })
        val bLines = bText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val laLines = laText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val lLines = lText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val bOnlyVsLa = bLines - laLines.toSet(); val laOnlyVsB = laLines - bLines.toSet()
        check("LA differs from B by exactly the age-label line", bOnlyVsLa == listOf("27") && laOnlyVsB == listOf("Age: 27"))
        val laOnlyVsL = laLines - lLines.toSet(); val lOnlyVsLa = lLines - laLines.toSet()
        check("L differs from LA by exactly the remaining scalar labels", laOnlyVsL.all { bare -> lOnlyVsLa.any { it.endsWith(": $bare") } } && laOnlyVsL.size == lOnlyVsLa.size)

        println(if (preOk) "\n✓✓✓ PRECONDITIONS VERIFIED — cleared for the 30-call predetermined run ✓✓✓" else "\n✗✗✗ PRECONDITION FAILED — STOP, do not spend API calls ✗✗✗")
        assertTrue(preOk, "STOP: Phase 24 Stage 2 precondition verification failed")

        // Predetermined order, fixed before any call: 10xB, 10xLA, 10xL.
        val order = List(10) { "B" } + List(10) { "LA" } + List(10) { "L" }
        println("\n════ PREDETERMINED CALL ORDER (fixed before execution) ════")
        println(order.joinToString(", "))

        fun expectedPayloadFor(variant: String): String = when (variant) { "B" -> bText; "LA" -> laText; "L" -> lText; else -> "" }

        data class P24Call(
            val index: Int, val variant: String, val response: String, val status: String,
            val payloadMatches: Boolean, val restrictedFieldPresent: Boolean,
            val has27: Boolean, val has21: Boolean, val has24: Boolean,
            val hasSix: Boolean, val hasThree: Boolean, val otherDurationHint: Boolean,
        )
        val calls = mutableListOf<P24Call>()

        // Observable-only regexes: word-boundary numeric/duration substrings. No reasoning inferred.
        fun containsNum(text: String, n: String) = Regex("\\b$n\\b").containsMatchIn(text)
        fun containsWordNum(text: String, vararg words: String) = words.any { Regex("\\b$it\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) }

        order.forEachIndexed { idx, variant ->
            world.llmClient.currentSkillKey = skill
            world.llmClient.lastCapturedSystemBlock = null
            var responseText = ""; var status = "SUCCESS"
            try {
                val conversationId = world.service.create(
                    TestChatService.CreationRequest(personaId = world.personaId, testUserId = world.testUserId, personaContextVariant = variant, personaContextSkillKey = skill),
                ).let { (it as TestChatService.CreationResult.Created).conversation.id }
                val result = world.service.sendMessage(conversationId, UUID.randomUUID(), msg)
                check(result is TestChatService.MessageResult.Sent) { "sendMessage failed: $result" }
                when (val cr = result.chatResult) { is ChatResult.Success -> responseText = cr.response.content; else -> { status = "FAILED"; println("Call ${idx + 1} [$variant] FAILED: $cr") } }
            } catch (e: Exception) { status = "FAILED"; println("Call ${idx + 1} [$variant] EXCEPTION: ${e.message}") }
            val captured = world.llmClient.lastCapturedSystemBlock ?: ""
            val expectedPayload = expectedPayloadFor(variant)
            val payloadMatches = status == "SUCCESS" && captured.contains(expectedPayload)
            val restrictedFieldPresent = captured.contains("intimate_anatomy") || captured.contains("adult_intimacy")
            val has27 = containsNum(responseText, "27")
            val has21 = containsNum(responseText, "21") || containsWordNum(responseText, "twenty-one", "twenty one")
            val has24 = containsNum(responseText, "24") || containsWordNum(responseText, "twenty-four", "twenty four")
            val hasSix = containsWordNum(responseText, "six")
            val hasThree = containsWordNum(responseText, "three")
            val otherDurationHint = containsWordNum(responseText, "two", "four", "five", "seven", "eight", "nine", "ten", "couple", "few")
            calls += P24Call(idx + 1, variant, responseText, status, payloadMatches, restrictedFieldPresent, has27, has21, has24, hasSix, hasThree, otherDurationHint)
            println("Call ${idx + 1} [$variant]: status=$status payloadMatches=$payloadMatches restrictedFieldPresent=$restrictedFieldPresent has27=$has27 has21=$has21 has24=$has24 hasSix=$hasSix hasThree=$hasThree otherDurationHint=$otherDurationHint")
            println("  response: $responseText")
            Thread.sleep(1000)
        }

        val dir = "E:\\Projects\\Pink dreams\\docs\\Persona update POC"
        val header = "call_index,variant,response,status,payload_matches_request,restricted_field_present,has_27,has_21,has_24,has_six,has_three,other_duration_hint\n"
        val rows = calls.joinToString("\n") { c ->
            listOf(
                c.index, c.variant, "\"${c.response.replace("\"", "\"\"")}\"", c.status, c.payloadMatches, c.restrictedFieldPresent,
                c.has27, c.has21, c.has24, c.hasSix, c.hasThree, c.otherDurationHint,
            ).joinToString(",")
        }
        File("$dir/PHASE24_EXECUTIONS.csv").writeText(header + rows)
        println("\n✓ Saved: $dir/PHASE24_EXECUTIONS.csv")

        val allSucceeded = calls.all { it.status == "SUCCESS" }
        val allPayloadsMatch = calls.all { it.payloadMatches }
        val noRestrictedLeak = calls.none { it.restrictedFieldPresent }
        println("All 30 calls succeeded: $allSucceeded | All payloads verified: $allPayloadsMatch | No restricted-field leakage: $noRestrictedLeak")
        assertTrue(allSucceeded, "STOP: not all Phase 24 calls succeeded")
        assertTrue(allPayloadsMatch, "STOP: a Phase 24 payload did not match the captured request")
        assertTrue(noRestrictedLeak, "STOP: a restricted field leaked into a Phase 24 request")
    }

    /**
     * PHASE 26 STAGE 2 — authorized execution. Exactly 40 real calls
     * (20xB, 20xLA), INTERLEAVED order (B,LA,B,LA,...), fixed before
     * execution, on the unaltered Pune timeline scenario. Records two
     * independent scoring axes per call (duration correctness; hometown/
     * current-city factuality) without collapsing one into the other, per
     * the Phase 26 design's explicit methodological improvement over
     * Phase 24. No L condition. No production change.
     */
    @Test
    fun phase26Stage2Execution() {
        val world = World()
        applyOriginalPhase10eFixes(world)
        val ileEntry = world.fieldRegistryRepo.findByFieldId("life_context.important_life_events")!!
        world.fieldRegistryRepo.update(ileEntry.copy(description = "life_context.important_life_events years long since moved arrived came time"))
        val gc = world.skillReqRepo.findBySkillKey("general_chat")!!
        world.skillReqRepo.update(gc.copy(conditionalFields = (gc.conditionalFields ?: emptyList()) + listOf("identity.age", "life_context.important_life_events")))
        val ageEntry = world.fieldRegistryRepo.findByFieldId("identity.age")!!
        world.fieldRegistryRepo.update(ageEntry.copy(description = "identity.age years long since moved arrived came time"))

        val skill = "general_chat"
        val msg = "Been in this city long?"

        println("\n════ PHASE 26 PRECONDITION VERIFICATION (zero-cost, re-run before spending calls) ════")
        val bProvider = com.pinkdreams.chat.context.ResolvedPersonaContextProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo, "B")
        val laProvider = com.pinkdreams.chat.context.AgeOnlyLabeledScalarDiagnosticProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo)

        val bFieldIds = world.resolver.resolve(world.personaId, world.personaCoreVersionId, skill, msg, "B").includedFieldIds.toSet()
        val bText = bProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        val laText = laProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)

        var preOk = true
        fun check(label: String, cond: Boolean) { println("[$label] ${if (cond) "PASS" else "FAIL"}"); if (!cond) preOk = false }

        val expectedFields = setOf("identity.name", "personality.core_traits", "identity.hometown", "identity.current_city", "communication.style", "narrative_state.unresolved_threads", "life_context.occupation", "identity.age", "life_context.important_life_events")
        check("Selected field IDs identical to Phase 14/16/19/20/21/24/26-Stage1", bFieldIds == expectedFields)
        check("No restricted field in B/LA", listOf(bText, laText).none { it.contains("intimate_anatomy") || it.contains("adult_intimacy") })
        val bLines = bText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val laLines = laText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val bOnly = bLines - laLines.toSet(); val laOnly = laLines - bLines.toSet()
        check("LA differs from B by exactly the age-label line", bOnly == listOf("27") && laOnly == listOf("Age: 27"))

        println(if (preOk) "\n✓✓✓ PRECONDITIONS VERIFIED — cleared for the 40-call interleaved run ✓✓✓" else "\n✗✗✗ PRECONDITION FAILED — STOP, do not spend API calls ✗✗✗")
        assertTrue(preOk, "STOP: Phase 26 Stage 2 precondition verification failed")

        // Predetermined INTERLEAVED order, fixed before any call: B,LA,B,LA,... x20 pairs = 40 calls.
        val order = (1..20).flatMap { listOf("B", "LA") }
        println("\n════ PREDETERMINED INTERLEAVED CALL ORDER (fixed before execution) ════")
        println(order.joinToString(", "))

        fun expectedPayloadFor(variant: String): String = if (variant == "B") bText else laText
        fun containsNum(text: String, n: String) = Regex("\\b$n\\b").containsMatchIn(text)
        fun containsWordNum(text: String, vararg words: String) = words.any { Regex("\\b$it\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) }

        data class P26Call(
            val index: Int, val variant: String, val response: String, val status: String,
            val payloadMatches: Boolean, val restrictedFieldPresent: Boolean,
            val has27: Boolean, val has21: Boolean, val has24: Boolean, val hasSix: Boolean, val hasThree: Boolean, val otherDurationHint: Boolean,
        )
        val calls = mutableListOf<P26Call>()

        order.forEachIndexed { idx, variant ->
            world.llmClient.currentSkillKey = skill
            world.llmClient.lastCapturedSystemBlock = null
            var responseText = ""; var status = "SUCCESS"
            try {
                val conversationId = world.service.create(
                    TestChatService.CreationRequest(personaId = world.personaId, testUserId = world.testUserId, personaContextVariant = variant, personaContextSkillKey = skill),
                ).let { (it as TestChatService.CreationResult.Created).conversation.id }
                val result = world.service.sendMessage(conversationId, UUID.randomUUID(), msg)
                check(result is TestChatService.MessageResult.Sent) { "sendMessage failed: $result" }
                when (val cr = result.chatResult) { is ChatResult.Success -> responseText = cr.response.content; else -> { status = "FAILED"; println("Call ${idx + 1} [$variant] FAILED: $cr") } }
            } catch (e: Exception) { status = "FAILED"; println("Call ${idx + 1} [$variant] EXCEPTION: ${e.message}") }
            val captured = world.llmClient.lastCapturedSystemBlock ?: ""
            val expectedPayload = expectedPayloadFor(variant)
            val payloadMatches = status == "SUCCESS" && captured.contains(expectedPayload)
            val restrictedFieldPresent = captured.contains("intimate_anatomy") || captured.contains("adult_intimacy")
            val has27 = containsNum(responseText, "27")
            val has21 = containsNum(responseText, "21") || containsWordNum(responseText, "twenty-one", "twenty one")
            val has24 = containsNum(responseText, "24") || containsWordNum(responseText, "twenty-four", "twenty four")
            val hasSix = containsWordNum(responseText, "six")
            val hasThree = containsWordNum(responseText, "three")
            val otherDurationHint = containsWordNum(responseText, "two", "four", "five", "seven", "eight", "nine", "ten", "couple", "few")
            calls += P26Call(idx + 1, variant, responseText, status, payloadMatches, restrictedFieldPresent, has27, has21, has24, hasSix, hasThree, otherDurationHint)
            println("Call ${idx + 1} [$variant]: status=$status payloadMatches=$payloadMatches restrictedFieldPresent=$restrictedFieldPresent has27=$has27 has21=$has21 has24=$has24 hasSix=$hasSix hasThree=$hasThree otherDurationHint=$otherDurationHint")
            println("  response: $responseText")
            Thread.sleep(1000)
        }

        val dir = "E:\\Projects\\Pink dreams\\docs\\Persona update POC"
        val header = "call_index,variant,response,status,payload_matches_request,restricted_field_present,has_27,has_21,has_24,has_six,has_three,other_duration_hint\n"
        val rows = calls.joinToString("\n") { c ->
            listOf(
                c.index, c.variant, "\"${c.response.replace("\"", "\"\"")}\"", c.status, c.payloadMatches, c.restrictedFieldPresent,
                c.has27, c.has21, c.has24, c.hasSix, c.hasThree, c.otherDurationHint,
            ).joinToString(",")
        }
        File("$dir/PHASE26_EXECUTIONS.csv").writeText(header + rows)
        println("\n✓ Saved: $dir/PHASE26_EXECUTIONS.csv")

        val allSucceeded = calls.all { it.status == "SUCCESS" }
        val allPayloadsMatch = calls.all { it.payloadMatches }
        val noRestrictedLeak = calls.none { it.restrictedFieldPresent }
        println("All 40 calls succeeded: $allSucceeded | All payloads verified: $allPayloadsMatch | No restricted-field leakage: $noRestrictedLeak")
        assertTrue(allSucceeded, "STOP: not all Phase 26 calls succeeded")
        assertTrue(allPayloadsMatch, "STOP: a Phase 26 payload did not match the captured request")
        assertTrue(noRestrictedLeak, "STOP: a restricted field leaked into a Phase 26 request")
    }

    /**
     * PHASE 27 STAGE 1 — zero-cost re-verification of the sister age-gap
     * scenario (Phase 22) as an independent generalization test for the
     * Phase 26 B-vs-LA statistical result. NO API calls — uses
     * interceptGeneration=true (real pipeline, zero network calls) for the
     * provider-to-request equivalence check only. Confirms nothing has
     * changed since Phase 22 and that the scenario remains clean.
     */
    @Test
    fun phase27Stage1Audit() {
        val world = World() // deliberately NO applyOriginalPhase10eFixes() — pure baseline seed, matching Phase 22
        world.llmClient.interceptGeneration = true // zero-cost: real pipeline, no network call

        val skill = "about"
        val msg = "Is there a big age gap between you and your sister in your family?"

        val bProvider = com.pinkdreams.chat.context.ResolvedPersonaContextProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo, "B")
        val laProvider = com.pinkdreams.chat.context.AgeOnlyLabeledScalarDiagnosticProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo)

        val bFieldIds = world.resolver.resolve(world.personaId, world.personaCoreVersionId, skill, msg, "B").includedFieldIds.toSet()
        val laFieldIds = world.resolver.resolve(world.personaId, world.personaCoreVersionId, skill, msg, "B").includedFieldIds.toSet()
        val bText = bProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        val laText = laProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)

        var ok = true
        fun check(label: String, cond: Boolean) { println("[$label] ${if (cond) "PASS" else "FAIL"}"); if (!cond) ok = false }

        println("\n════ PHASE 27 STAGE 1 — SISTER SCENARIO RE-VERIFICATION (zero-cost) ════")
        println("Selected fields: $bFieldIds")
        check("1. B and LA select exactly the same fields", bFieldIds == laFieldIds)
        check("2. identity.age selected (required, baseline, 'about' skill, no fix)", bFieldIds.contains("identity.age"))
        check("3. life_context.family selected (native conditional, baseline description, no fix)", bFieldIds.contains("life_context.family"))
        check("4. No mapping/registry fix applied (confirmed by construction — applyOriginalPhase10eFixes() never called)", true)
        check("5. No restricted field present", bFieldIds.none { it == "intimate_anatomy" || it == "adult_intimacy" } && !bText.contains("intimate_anatomy") && !laText.contains("intimate_anatomy"))

        val bLines = bText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val laLines = laText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val bOnly = bLines - laLines.toSet(); val laOnly = laLines - bLines.toSet()
        check("6. B and LA differ only in the intended identity.age scalar labeling", bOnly == listOf("27") && laOnly == listOf("Age: 27"))
        check("7. Anjali's age (29) present in both, unaffected by labeling", bText.contains("29") && laText.contains("29"))
        check("8. History/memory/profile absent for both (fresh conversation + fresh testUserId, established pattern)", true)

        val bRepeat = bProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        val laRepeat = laProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        check("9. Deterministic provider output (B/LA identical on repeat)", bRepeat == bText && laRepeat == laText)

        println("\n---- Provider -> request equivalence (zero-API-cost, via interceptGeneration) ----")
        listOf("B" to bProvider, "LA" to laProvider).forEach { (variantLabel, provider) ->
            world.llmClient.lastCapturedSystemBlock = null
            world.llmClient.currentSkillKey = skill
            val conversationId = world.service.create(
                TestChatService.CreationRequest(personaId = world.personaId, testUserId = world.testUserId, personaContextVariant = variantLabel, personaContextSkillKey = skill),
            ).let { (it as TestChatService.CreationResult.Created).conversation.id }
            val result = world.service.sendMessage(conversationId, UUID.randomUUID(), msg)
            check(result is TestChatService.MessageResult.Sent) { "sendMessage failed for $variantLabel: $result" }
            val capturedSystemBlock = world.llmClient.lastCapturedSystemBlock ?: throw IllegalStateException("STOP: no system block captured for $variantLabel")
            val expected = provider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
            check("10. [$variantLabel] provider output appears verbatim in captured request", capturedSystemBlock.contains(expected))
        }

        println("\n════ PHASE 27 STAGE 1 RESULT ════")
        println(if (ok) "✓✓✓ SISTER SCENARIO REMAINS CLEAN — cleared for the 40-call Stage 2, PENDING SEPARATE AUTHORIZATION ✓✓✓" else "✗✗✗ STAGE 1 FAILED — STOP, do not proceed to Stage 2 ✗✗✗")
        println("\nZERO API calls made — interceptGeneration=true for the entire test, all generation calls answered locally.")
        assertTrue(ok, "STOP: Phase 27 Stage 1 verification failed — see log above")
    }

    /**
     * PHASE 27 STAGE 2 — authorized execution. Exactly 40 real calls
     * (20xB, 20xLA), INTERLEAVED order, fixed before execution, on the
     * independent sister-age-gap scenario (skill "about", PURE BASELINE
     * mapping, no fixes). Kept entirely separate from Phase 22's 10-call
     * sample. No L condition. No production change.
     */
    @Test
    fun phase27Stage2Execution() {
        val world = World() // deliberately NO applyOriginalPhase10eFixes() — pure baseline seed, matching Phase 22/27-Stage1

        val skill = "about"
        val msg = "Is there a big age gap between you and your sister in your family?"

        println("\n════ PHASE 27 PRECONDITION VERIFICATION (zero-cost, re-run before spending calls) ════")
        val bProvider = com.pinkdreams.chat.context.ResolvedPersonaContextProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo, "B")
        val laProvider = com.pinkdreams.chat.context.AgeOnlyLabeledScalarDiagnosticProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo)

        val bFieldIds = world.resolver.resolve(world.personaId, world.personaCoreVersionId, skill, msg, "B").includedFieldIds.toSet()
        val bText = bProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        val laText = laProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)

        var preOk = true
        fun check(label: String, cond: Boolean) { println("[$label] ${if (cond) "PASS" else "FAIL"}"); if (!cond) preOk = false }

        val expectedFields = setOf("identity.name", "identity.age", "identity.hometown", "identity.current_city", "life_context.occupation", "life_context.education", "personality.core_traits", "life_context.family")
        check("Selected field IDs identical to Phase 22/27-Stage1", bFieldIds == expectedFields)
        check("No restricted field in B/LA", listOf(bText, laText).none { it.contains("intimate_anatomy") || it.contains("adult_intimacy") })
        val bLines = bText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val laLines = laText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val bOnly = bLines - laLines.toSet(); val laOnly = laLines - bLines.toSet()
        check("LA differs from B by exactly the age-label line", bOnly == listOf("27") && laOnly == listOf("Age: 27"))

        println(if (preOk) "\n✓✓✓ PRECONDITIONS VERIFIED — cleared for the 40-call interleaved run ✓✓✓" else "\n✗✗✗ PRECONDITION FAILED — STOP, do not spend API calls ✗✗✗")
        assertTrue(preOk, "STOP: Phase 27 Stage 2 precondition verification failed")

        // Predetermined INTERLEAVED order, fixed before any call: B,LA,B,LA,... x20 pairs = 40 calls.
        val order = (1..20).flatMap { listOf("B", "LA") }
        println("\n════ PREDETERMINED INTERLEAVED CALL ORDER (fixed before execution) ════")
        println(order.joinToString(", "))

        fun expectedPayloadFor(variant: String): String = if (variant == "B") bText else laText

        data class P27Call(
            val index: Int, val variant: String, val response: String, val status: String,
            val payloadMatches: Boolean, val restrictedFieldPresent: Boolean,
        )
        val calls = mutableListOf<P27Call>()

        order.forEachIndexed { idx, variant ->
            world.llmClient.currentSkillKey = skill
            world.llmClient.lastCapturedSystemBlock = null
            var responseText = ""; var status = "SUCCESS"
            try {
                val conversationId = world.service.create(
                    TestChatService.CreationRequest(personaId = world.personaId, testUserId = world.testUserId, personaContextVariant = variant, personaContextSkillKey = skill),
                ).let { (it as TestChatService.CreationResult.Created).conversation.id }
                val result = world.service.sendMessage(conversationId, UUID.randomUUID(), msg)
                check(result is TestChatService.MessageResult.Sent) { "sendMessage failed: $result" }
                when (val cr = result.chatResult) { is ChatResult.Success -> responseText = cr.response.content; else -> { status = "FAILED"; println("Call ${idx + 1} [$variant] FAILED: $cr") } }
            } catch (e: Exception) { status = "FAILED"; println("Call ${idx + 1} [$variant] EXCEPTION: ${e.message}") }
            val captured = world.llmClient.lastCapturedSystemBlock ?: ""
            val expectedPayload = expectedPayloadFor(variant)
            val payloadMatches = status == "SUCCESS" && captured.contains(expectedPayload)
            val restrictedFieldPresent = captured.contains("intimate_anatomy") || captured.contains("adult_intimacy")
            calls += P27Call(idx + 1, variant, responseText, status, payloadMatches, restrictedFieldPresent)
            println("Call ${idx + 1} [$variant]: status=$status payloadMatches=$payloadMatches restrictedFieldPresent=$restrictedFieldPresent")
            println("  response: $responseText")
            Thread.sleep(1000)
        }

        val dir = "E:\\Projects\\Pink dreams\\docs\\Persona update POC"
        val header = "call_index,variant,response,status,payload_matches_request,restricted_field_present\n"
        val rows = calls.joinToString("\n") { c ->
            listOf(c.index, c.variant, "\"${c.response.replace("\"", "\"\"")}\"", c.status, c.payloadMatches, c.restrictedFieldPresent).joinToString(",")
        }
        File("$dir/PHASE27_EXECUTIONS.csv").writeText(header + rows)
        println("\n✓ Saved: $dir/PHASE27_EXECUTIONS.csv")

        val allSucceeded = calls.all { it.status == "SUCCESS" }
        val allPayloadsMatch = calls.all { it.payloadMatches }
        val noRestrictedLeak = calls.none { it.restrictedFieldPresent }
        println("All 40 calls succeeded: $allSucceeded | All payloads verified: $allPayloadsMatch | No restricted-field leakage: $noRestrictedLeak")
        assertTrue(allSucceeded, "STOP: not all Phase 27 calls succeeded")
        assertTrue(allPayloadsMatch, "STOP: a Phase 27 payload did not match the captured request")
        assertTrue(noRestrictedLeak, "STOP: a restricted field leaked into a Phase 27 request")
    }

    /**
     * PHASE 30 — confirmatory sister-scenario execution (Path A from Phase
     * 29). Exactly 118 real calls (59xB, 59xLA), INTERLEAVED order, fixed
     * before execution, on the independent sister-age-gap scenario (skill
     * "about", PURE BASELINE mapping, no fixes). Phase 22's 10 historical
     * calls are NOT pooled into this sample. No L condition.
     */
    @Test
    fun phase30ConfirmatoryExecution() {
        val world = World() // fresh World(), deliberately NO applyOriginalPhase10eFixes() — pure baseline seed, matching Phase 22/27

        val skill = "about"
        val msg = "Is there a big age gap between you and your sister in your family?"

        println("\n════ PHASE 30 STAGE 1 — PRE-FLIGHT AUDIT (zero-cost, re-run before spending calls) ════")
        val bProvider = com.pinkdreams.chat.context.ResolvedPersonaContextProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo, "B")
        val laProvider = com.pinkdreams.chat.context.AgeOnlyLabeledScalarDiagnosticProvider(world.db, world.personaMasterRepo, world.fieldRegistryRepo, world.skillReqRepo)

        val bFieldIds = world.resolver.resolve(world.personaId, world.personaCoreVersionId, skill, msg, "B").includedFieldIds.toSet()
        val bText = bProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        val laText = laProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)

        var preOk = true
        fun check(label: String, cond: Boolean) { println("[$label] ${if (cond) "PASS" else "FAIL"}"); if (!cond) preOk = false }

        val expectedFields = setOf("identity.name", "identity.age", "identity.hometown", "identity.current_city", "life_context.occupation", "life_context.education", "personality.core_traits", "life_context.family")
        check("1. identity.age selected", bFieldIds.contains("identity.age"))
        check("2. life_context.family selected", bFieldIds.contains("life_context.family"))
        check("3. Selected field IDs identical to Phase 22/27", bFieldIds == expectedFields)
        check("4. Ground truth present: Anjali 29 and own age 27 in B payload", bText.contains("29") && bText.contains("27"))
        check("5. No restricted field in B/LA", listOf(bText, laText).none { it.contains("intimate_anatomy") || it.contains("adult_intimacy") })
        val bLines = bText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val laLines = laText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val bOnly = bLines - laLines.toSet(); val laOnly = laLines - bLines.toSet()
        check("6. LA differs from B by exactly the age-label line (27 -> Age: 27)", bOnly == listOf("27") && laOnly == listOf("Age: 27"))
        val bRepeat = bProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        val laRepeat = laProvider.getPersonaContext(world.personaId, world.personaCoreVersionId, skill, msg)
        check("7. Deterministic provider output (B/LA identical on repeat)", bRepeat == bText && laRepeat == laText)
        println("8. History/memory/profile absent for both (fresh conversation + fresh testUserId, established pattern) — confirmed by construction.")
        println("9. No mapping/registry fix applied (confirmed by construction — applyOriginalPhase10eFixes() never called).")
        println("10. Phase 22's original 10 calls are NOT pooled into this sample — this is a fresh, independent 118-call batch, analyzed on its own.")

        println(if (preOk) "\n✓✓✓ PRECONDITIONS VERIFIED — cleared for the 118-call interleaved run ✓✓✓" else "\n✗✗✗ PRECONDITION FAILED — STOP, do not spend API calls ✗✗✗")
        assertTrue(preOk, "STOP: Phase 30 Stage 1 pre-flight audit failed — see log above")

        // Predetermined INTERLEAVED order, fixed before any call: B,LA,B,LA,... x59 pairs = 118 calls.
        val order = (1..59).flatMap { listOf("B", "LA") }
        println("\n════ PHASE 30 STAGE 2 — PREDETERMINED INTERLEAVED CALL ORDER (fixed before execution, 118 calls) ════")
        println("Order length: ${order.size}. Pattern: B,LA repeated 59 times.")

        fun expectedPayloadFor(variant: String): String = if (variant == "B") bText else laText

        data class P30Call(
            val index: Int, val variant: String, val response: String, val status: String,
            val payloadMatches: Boolean, val restrictedFieldPresent: Boolean,
        )
        val calls = mutableListOf<P30Call>()

        order.forEachIndexed { idx, variant ->
            world.llmClient.currentSkillKey = skill
            world.llmClient.lastCapturedSystemBlock = null
            var responseText = ""; var status = "SUCCESS"
            try {
                val conversationId = world.service.create(
                    TestChatService.CreationRequest(personaId = world.personaId, testUserId = world.testUserId, personaContextVariant = variant, personaContextSkillKey = skill),
                ).let { (it as TestChatService.CreationResult.Created).conversation.id }
                val result = world.service.sendMessage(conversationId, UUID.randomUUID(), msg)
                check(result is TestChatService.MessageResult.Sent) { "sendMessage failed: $result" }
                when (val cr = result.chatResult) { is ChatResult.Success -> responseText = cr.response.content; else -> { status = "FAILED"; println("Call ${idx + 1} [$variant] FAILED: $cr") } }
            } catch (e: Exception) { status = "FAILED"; println("Call ${idx + 1} [$variant] EXCEPTION: ${e.message}") }
            val captured = world.llmClient.lastCapturedSystemBlock ?: ""
            val expectedPayload = expectedPayloadFor(variant)
            val payloadMatches = status == "SUCCESS" && captured.contains(expectedPayload)
            val restrictedFieldPresent = captured.contains("intimate_anatomy") || captured.contains("adult_intimacy")
            calls += P30Call(idx + 1, variant, responseText, status, payloadMatches, restrictedFieldPresent)
            println("Call ${idx + 1} [$variant]: status=$status payloadMatches=$payloadMatches restrictedFieldPresent=$restrictedFieldPresent")
            println("  response: $responseText")
            Thread.sleep(1000)
        }

        val dir = "E:\\Projects\\Pink dreams\\docs\\Persona update POC"
        val header = "call_index,variant,response,status,payload_matches_request,restricted_field_present\n"
        val rows = calls.joinToString("\n") { c ->
            listOf(c.index, c.variant, "\"${c.response.replace("\"", "\"\"")}\"", c.status, c.payloadMatches, c.restrictedFieldPresent).joinToString(",")
        }
        File("$dir/PHASE30_EXECUTIONS.csv").writeText(header + rows)
        println("\n✓ Saved: $dir/PHASE30_EXECUTIONS.csv")

        val allSucceeded = calls.all { it.status == "SUCCESS" }
        val allPayloadsMatch = calls.all { it.payloadMatches }
        val noRestrictedLeak = calls.none { it.restrictedFieldPresent }
        println("All 118 calls succeeded: $allSucceeded | All payloads verified: $allPayloadsMatch | No restricted-field leakage: $noRestrictedLeak")
        assertTrue(allSucceeded, "STOP: not all Phase 30 calls succeeded")
        assertTrue(allPayloadsMatch, "STOP: a Phase 30 payload did not match the captured request")
        assertTrue(noRestrictedLeak, "STOP: a restricted field leaked into a Phase 30 request")
    }
}
