package com.pinkdreams.observability

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatPersistence
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ContextAssembler
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.EntitlementDecision
import com.pinkdreams.chat.GenerationResponse
import com.pinkdreams.chat.Generator
import com.pinkdreams.chat.ModerationDecision
import com.pinkdreams.chat.PersistedResponse
import com.pinkdreams.chat.PipelineChatEngine
import com.pinkdreams.chat.StageResult
import com.pinkdreams.chat.ValidationDecision
import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.chat.memory.MemoryCandidate
import com.pinkdreams.chat.memory.MemoryContextSelector
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.chat.memory.SkillAwareMemoryEnricher
import com.pinkdreams.chat.skill.IntentDiscovery
import com.pinkdreams.chat.skill.SkillContextEnricher
import com.pinkdreams.chat.skill.SkillSelection
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.llm.GenerationConfig
import com.pinkdreams.llm.LlmResponse
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.FailureIsolatedTurnAttributionRecorder
import com.pinkdreams.persistence.repositories.LlmExchangeRepository
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import com.pinkdreams.persistence.repositories.TurnAttributionRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository
import org.jetbrains.exposed.sql.Database
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 24 — Complete Pipeline Attribution.
 *
 * One test per attribution BOUNDARY, in the order Part 20 lists them. Each
 * asserts both the positive case (the value is correctly attributed) and the
 * negative case (a value the system cannot prove stays null rather than being
 * invented — Part 16).
 */
class TurnAttributionTest {

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private class CapturingRecorder : TurnAttributionRecorder {
        val records = mutableListOf<TurnAttributionRecord>()
        override fun record(record: TurnAttributionRecord) { records.add(record) }
    }

    private fun request(content: String = "hello") = ChatRequest(
        requestId = UUID.randomUUID(),
        userId = UUID.randomUUID(),
        conversationId = UUID.randomUUID(),
        personaId = UUID.randomUUID(),
        clientMessageId = UUID.randomUUID(),
        content = content,
    )

    private fun engine(
        recorder: TurnAttributionRecorder?,
        context: ChatContext = ChatContext(emptyList()),
        contextAssembler: ContextAssembler = ContextAssembler { StageResult.Succeeded(context) },
        intentDiscovery: IntentDiscovery = IntentDiscovery { _, _ -> SkillSelection.None },
        generator: Generator = Generator { _, _ -> StageResult.Succeeded(GenerationResponse("reply")) },
        validator: com.pinkdreams.chat.OutputValidator = com.pinkdreams.chat.OutputValidator { _, _ -> ValidationDecision.Accepted },
        persistence: ChatPersistence = ChatPersistence { _, r -> StageResult.Succeeded(PersistedResponse(UUID.randomUUID(), r.content)) },
        skillContextEnricher: SkillContextEnricher? = null,
        memoryContextEnricher: SkillAwareMemoryEnricher? = null,
        isTestChat: Boolean = false,
    ) = PipelineChatEngine(
        entitlementChecker = { EntitlementDecision.Allowed },
        inputModerator = { ModerationDecision.Allowed },
        contextAssembler = contextAssembler,
        generator = generator,
        outputValidator = validator,
        persistence = persistence,
        delivery = { _, _ -> StageResult.Succeeded(Unit) },
        intentDiscovery = intentDiscovery,
        skillContextEnricher = skillContextEnricher,
        memoryContextEnricher = memoryContextEnricher,
        turnAttributionRecorder = recorder,
        isTestChat = isTestChat,
    )

    // ------------------------------------------------------------------
    // Persona
    // ------------------------------------------------------------------

    @Test
    fun `persona id and active core version are attributed from the runtime context`() {
        val recorder = CapturingRecorder()
        val personaVersionId = UUID.randomUUID()
        val req = request()
        engine(
            recorder,
            context = ChatContext(emptyList(), personaCoreVersionId = personaVersionId, personaCoreVersion = 7),
        ).process(req)

        val record = recorder.records.single()
        assertEquals(req.personaId, record.personaId)
        assertEquals(personaVersionId, record.personaVersionId)
        assertEquals(7, record.personaVersion)
    }

    @Test
    fun `a context with no persona version leaves persona version null rather than inventing one`() {
        val recorder = CapturingRecorder()
        engine(recorder).process(request())

        val record = recorder.records.single()
        assertNull(record.personaVersionId)
        assertNull(record.personaVersion)
    }

    // ------------------------------------------------------------------
    // Conversation Engine
    // ------------------------------------------------------------------

    @Test
    fun `conversation engine id and version are attributed from the runtime context`() {
        val recorder = CapturingRecorder()
        val engineId = UUID.randomUUID()
        engine(recorder, context = ChatContext(emptyList(), engineVersionId = engineId, engineVersion = 3)).process(request())

        val record = recorder.records.single()
        assertEquals(engineId, record.conversationEngineId)
        assertEquals(3, record.conversationEngineVersion)
    }

    @Test
    fun `a missing engine version stays explicitly unavailable`() {
        val recorder = CapturingRecorder()
        engine(recorder, context = ChatContext(emptyList(), engineVersionId = UUID.randomUUID())).process(request())

        assertNull(recorder.records.single().conversationEngineVersion)
    }

    // ------------------------------------------------------------------
    // Intent — including the historical-configuration requirement (Part 5)
    // ------------------------------------------------------------------

    @Test
    fun `intent configuration is captured as used and a later admin change does not rewrite history`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val repository = TurnAttributionRepository(db)
        val recorder = FailureIsolatedTurnAttributionRecorder(repository)
        val skills = SkillRepository(db)
        skills.activate(skills.publish(skills.create("flirting", 1, "flirt content").id).id)

        // The admin-configurable intent model, as the running system resolves it.
        var adminIntentModel = "model-A"
        val llm = RecordingLlmClient(LlmResponse(content = "{\"skillKey\": \"flirting\"}"))
        val discovery = com.pinkdreams.chat.skill.LlmIntentDiscovery(
            client = llm,
            skillRepository = skills,
            turnAwareConfigProvider = { turnRequestId ->
                val config = GenerationConfig(model = adminIntentModel, maxOutputTokens = 600, workload = "intent_discovery")
                TurnAttributionScope.update(turnRequestId) { it.intentModelSource = "DATABASE" }
                config
            },
        )
        val context = ChatContext(
            listOf(ContextBlock("system", "rules"), ContextBlock("user", "hi")),
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID(),
        )

        val firstTurn = request()
        engine(recorder, context = context, intentDiscovery = discovery).process(firstTurn)

        // 09:05 — an admin changes the intent model.
        adminIntentModel = "model-B"
        val secondTurn = request()
        engine(recorder, context = context, intentDiscovery = discovery).process(secondTurn)

        // 09:10 — inspecting both turns.
        val first = assertNotNull(repository.findByTurnRequestId(firstTurn.requestId))
        val second = assertNotNull(repository.findByTurnRequestId(secondTurn.requestId))
        assertEquals("model-A", first.intentModel, "The historical turn must still report the model it actually ran with")
        assertEquals("model-B", second.intentModel, "The new turn must report the new configuration")
        assertEquals("DATABASE", first.intentModelSource)
        assertEquals(600, first.intentMaxOutputTokens)
    }

    @Test
    fun `intent outcome and selected skill are the values the implementation actually produced`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val recorder = CapturingRecorder()
        val skills = SkillRepository(db)
        skills.activate(skills.publish(skills.create("flirting", 1, "flirt content").id).id)
        val discovery = com.pinkdreams.chat.skill.LlmIntentDiscovery(
            client = RecordingLlmClient(LlmResponse(content = "{\"skillKey\": \"flirting\"}")),
            skillRepository = skills,
            config = GenerationConfig(model = "m", maxOutputTokens = 600, workload = "intent_discovery"),
        )
        engine(
            recorder,
            context = ChatContext(
                listOf(ContextBlock("system", "rules"), ContextBlock("user", "hi")),
                engineVersionId = UUID.randomUUID(),
                personaCoreVersionId = UUID.randomUUID(),
            ),
            intentDiscovery = discovery,
        ).process(request())

        val record = recorder.records.single()
        assertEquals("SKILL_SELECTED", record.intentOutcome)
        assertEquals("flirting", record.intentResultSkillKey)
    }

    @Test
    fun `no active skills is recorded as a skipped intent stage not as a failure or a guess`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val recorder = CapturingRecorder()
        val discovery = com.pinkdreams.chat.skill.LlmIntentDiscovery(
            client = RecordingLlmClient(LlmResponse(content = "{}")),
            skillRepository = SkillRepository(db),
            config = GenerationConfig(model = "m", maxOutputTokens = 600, workload = "intent_discovery"),
        )
        engine(recorder, intentDiscovery = discovery).process(request())

        val record = recorder.records.single()
        assertEquals("SKIPPED_NO_ACTIVE_SKILLS", record.intentOutcome)
        assertNull(record.selectedSkillKey)
    }

    // ------------------------------------------------------------------
    // Skill
    // ------------------------------------------------------------------

    @Test
    fun `the selected skill is attributed and skill context injection is reported truthfully`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val skills = SkillRepository(db)
        skills.activate(skills.publish(skills.create("confidence_building", 1, "content").id).id)
        val recorder = CapturingRecorder()

        engine(
            recorder,
            intentDiscovery = { _, _ -> SkillSelection.Selected("confidence_building") },
            skillContextEnricher = SkillContextEnricher(skills),
        ).process(request())

        val record = recorder.records.single()
        assertEquals("confidence_building", record.selectedSkillKey)
        assertEquals(true, record.skillContextInjected)
    }

    @Test
    fun `no skill selected leaves the skill attribution null and injection false`() {
        val recorder = CapturingRecorder()
        engine(recorder).process(request())

        val record = recorder.records.single()
        assertNull(record.selectedSkillKey)
        assertEquals(false, record.skillContextInjected)
    }

    // ------------------------------------------------------------------
    // Memory
    // ------------------------------------------------------------------

    @Test
    fun `memory actually injected into generation is attributed by id from the real assembler`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val f = assemblerFixture(db)
        val recorder = CapturingRecorder()

        engine(recorder, contextAssembler = f.assembler).process(f.request)

        val record = recorder.records.single()
        val ids = assertNotNull(record.memoryIdsUsed, "Memory IDs must be attributed when memory was injected")
        assertEquals(3, ids.size)
        assertEquals(3, record.memoryCountUsed)
        assertEquals("CONTEXT_ASSEMBLER", record.memorySelectionSource)
        assertEquals(f.memoryIds.toSet(), ids.toSet())
    }

    @Test
    fun `the skill-aware selector overrides the assembler's attribution with the set it actually injected`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val f = assemblerFixture(db)
        val recorder = CapturingRecorder()
        // Selector that keeps exactly one candidate — the authoritative
        // "actually injected" set is therefore strictly smaller than retrieved.
        val selector = MemoryContextSelector { _, _, candidates -> candidates.take(1) }
        val enricher = SkillAwareMemoryEnricher(MemoryService(MemoryFactRepository(db)), selector)

        engine(recorder, contextAssembler = f.assembler, memoryContextEnricher = enricher).process(f.request)

        val record = recorder.records.single()
        assertEquals(1, record.memoryCountUsed)
        assertEquals("SKILL_AWARE_SELECTOR", record.memorySelectionSource)
        assertEquals(3, record.memoryCandidateCount, "The candidate pool must stay distinct from the injected set")
    }

    @Test
    fun `a turn with no memory attributes an empty set and does not crash`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val f = assemblerFixture(db, memoryCount = 0)
        val recorder = CapturingRecorder()

        engine(recorder, contextAssembler = f.assembler).process(f.request)

        val record = recorder.records.single()
        assertEquals(emptyList(), record.memoryIdsUsed)
        assertEquals(0, record.memoryCountUsed)
    }

    @Test
    fun `memory attribution never copies memory text`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val f = assemblerFixture(db)
        val recorder = CapturingRecorder()

        engine(recorder, contextAssembler = f.assembler).process(f.request)

        val serialized = recorder.records.single().toString()
        assertFalse(serialized.contains("memory-secret"), "Attribution must carry memory identifiers, never memory text")
    }

    // ------------------------------------------------------------------
    // User profile
    // ------------------------------------------------------------------

    @Test
    fun `user and profile presence are attributed without inventing profile versioning`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val f = assemblerFixture(db)
        val recorder = CapturingRecorder()

        engine(recorder, contextAssembler = f.assembler).process(f.request)

        val record = recorder.records.single()
        assertEquals(f.request.userId, record.userId)
        assertEquals(true, record.userProfilePresent)
        assertNotNull(record.userProfileUpdatedAt)
    }

    // ------------------------------------------------------------------
    // Generation configuration
    // ------------------------------------------------------------------

    @Test
    fun `effective generation configuration and its resolution sources are captured per turn`() {
        val recorder = CapturingRecorder()
        val generator = com.pinkdreams.llm.LlmGenerator(
            client = RecordingLlmClient(LlmResponse(content = "reply", provider = "deepinfra", model = "m1")),
            turnAwareConfigProvider = { turnRequestId ->
                val config = GenerationConfig(
                    model = "m1", temperature = 0.7, maxOutputTokens = 900,
                    reasoningEnabled = false, providerSort = "latency", workload = "primary_generation",
                )
                TurnAttributionScope.update(turnRequestId) {
                    it.generationModel = config.model
                    it.generationModelSource = "DATABASE"
                    it.generationTemperature = config.temperature
                    it.generationTemperatureSource = "DATABASE"
                    it.generationMaxOutputTokens = config.maxOutputTokens
                    it.generationMaxOutputTokensSource = "ENVIRONMENT_OR_DEFAULT"
                    it.generationReasoning = config.reasoningEnabled
                    it.generationProviderSort = config.providerSort
                    it.generationProviderSortSource = "CODE_DEFAULT"
                }
                config
            },
        )
        engine(
            recorder,
            context = ChatContext(emptyList(), engineVersionId = UUID.randomUUID(), personaCoreVersionId = UUID.randomUUID()),
            generator = generator,
        ).process(request())

        val record = recorder.records.single()
        assertEquals("m1", record.generationModel)
        assertEquals("DATABASE", record.generationModelSource)
        assertEquals(0.7, record.generationTemperature)
        assertEquals(900, record.generationMaxOutputTokens)
        assertEquals("ENVIRONMENT_OR_DEFAULT", record.generationMaxOutputTokensSource)
        assertEquals(false, record.generationReasoning)
        assertEquals("latency", record.generationProviderSort)
        assertEquals("CODE_DEFAULT", record.generationProviderSortSource)
    }

    @Test
    fun `AiRuntimeSettings generationConfigResolved returns exactly what generationConfig returns`() {
        val settings = com.pinkdreams.config.AiRuntimeSettings(
            llmConfig = com.pinkdreams.config.LlmConfig(apiKey = "k", model = "env-model", maxOutputTokens = 1234),
        )
        val (config, resolved) = settings.generationConfigResolved()
        assertEquals(settings.generationConfig(), config, "The additive accessor must not change resolution behavior")
        assertEquals(config.model, resolved.model)
    }

    // ------------------------------------------------------------------
    // Regeneration
    // ------------------------------------------------------------------

    @Test
    fun `a single accepted attempt records no regeneration`() {
        val recorder = CapturingRecorder()
        engine(recorder).process(request())

        val record = recorder.records.single()
        assertFalse(record.regenerationOccurred)
        assertEquals(0, record.regenerationCount)
    }

    @Test
    fun `a rejected first attempt records exactly one regeneration`() {
        val recorder = CapturingRecorder()
        var validations = 0
        engine(
            recorder,
            validator = { _, _ -> if (validations++ == 0) ValidationDecision.Rejected("too short") else ValidationDecision.Accepted },
        ).process(request())

        val record = recorder.records.single()
        assertTrue(record.regenerationOccurred)
        assertEquals(1, record.regenerationCount)
        assertEquals("SUCCESS", record.outcome)
    }

    @Test
    fun `a regeneration that is itself rejected is still counted and the failure is attributed`() {
        val recorder = CapturingRecorder()
        engine(recorder, validator = { _, _ -> ValidationDecision.Rejected("never good") }).process(request())

        val record = recorder.records.single()
        assertEquals(1, record.regenerationCount)
        assertEquals("FAILED_VALIDATION_FAILED", record.outcome)
        assertEquals("OUTPUT_VALIDATION", record.failedStage)
    }

    // ------------------------------------------------------------------
    // Async work and on-path latency separation (Part 13)
    // ------------------------------------------------------------------

    @Test
    fun `async workload exchanges stay linked to the turn but out of on-path latency`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val exchanges = LlmExchangeRepository(db)
        val turnId = UUID.randomUUID()
        val conversationId = UUID.randomUUID()
        fun record(workload: String, latency: Long) = exchanges.record(
            LlmExchangeRepository.RecordInput(
                turnRequestId = turnId, conversationId = conversationId, workload = workload,
                isTestChat = false, model = "m", provider = "p", latencyMs = latency,
                outcome = LlmExchangeRepository.Outcome.SUCCESS,
            ),
        )
        record("intent_discovery", 1_000)
        record("primary_generation", 2_000)
        record("memory_extraction", 9_000)
        record("continuity_summarization", 8_000)
        record("memory_engine_maintenance", 7_000)

        val breakdown = assertNotNull(
            com.pinkdreams.persistence.repositories.PerformanceMetricsRepository(db).turnBreakdown(turnId),
        )
        assertEquals(3_000, breakdown.onPathLatencyMs(), "Async latency must never enter the user-facing figure")
        assertEquals(5, breakdown.exchanges.size, "Every async workload stays independently attributable to this turn")
    }

    // ------------------------------------------------------------------
    // Failure isolation (Parts 18/20)
    // ------------------------------------------------------------------

    @Test
    fun `a failed generation still produces an attribution record`() {
        val recorder = CapturingRecorder()
        engine(recorder, generator = { _, _ -> StageResult.Failed(ErrorCode.GENERATION_FAILED) }).process(request())

        val record = recorder.records.single()
        assertEquals("FAILED_GENERATION_FAILED", record.outcome)
        assertEquals("GENERATION", record.failedStage)
    }

    @Test
    fun `a failed context assembly still produces an attribution record`() {
        val recorder = CapturingRecorder()
        engine(recorder, contextAssembler = { StageResult.Failed(ErrorCode.VALIDATION_FAILED) }).process(request())

        assertEquals("CONTEXT_ASSEMBLY", recorder.records.single().failedStage)
    }

    @Test
    fun `attribution persistence failing does not fail the chat turn`() {
        val throwing = TurnAttributionRecorder { throw IllegalStateException("db is down") }
        val result = engine(throwing).process(request())

        assertTrue(result is com.pinkdreams.chat.ChatResult.Success, "The user response must survive an attribution failure")
    }

    @Test
    fun `the failure-isolated recorder swallows repository errors`() {
        val recorder = FailureIsolatedTurnAttributionRecorder(TurnAttributionRepository(DatabaseFactory.connectInMemory()))
        // No schema created: every insert throws. Must not propagate.
        recorder.record(minimalRecord())
    }

    @Test
    fun `the per-turn collector scope is always released`() {
        val before = TurnAttributionScope.activeCount()
        engine(CapturingRecorder()).process(request())
        engine(CapturingRecorder(), generator = { _, _ -> StageResult.Failed(ErrorCode.GENERATION_FAILED) }).process(request())
        assertEquals(before, TurnAttributionScope.activeCount(), "A finished turn must never leave a collector behind")
    }

    @Test
    fun `an engine with no recorder wired writes nothing and behaves exactly as before`() {
        val result = engine(null).process(request())
        assertTrue(result is com.pinkdreams.chat.ChatResult.Success)
    }

    // ------------------------------------------------------------------
    // Persistence round trip
    // ------------------------------------------------------------------

    @Test
    fun `an attribution record survives a database round trip intact`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val repository = TurnAttributionRepository(db)
        val memoryIds = listOf(UUID.randomUUID(), UUID.randomUUID())
        val original = minimalRecord().copy(
            personaVersion = 4,
            conversationEngineVersion = 9,
            intentModel = "intent-model",
            intentModelSource = "DATABASE",
            intentJsonMode = true,
            intentMaxOutputTokens = 600,
            intentOutcome = "SKILL_SELECTED",
            intentResultSkillKey = "flirting",
            selectedSkillKey = "flirting",
            skillContextInjected = true,
            memoryIdsUsed = memoryIds,
            memoryCountUsed = memoryIds.size,
            memoryCandidateCount = 20,
            memorySelectionSource = "SKILL_AWARE_SELECTOR",
            generationModel = "gen-model",
            generationTemperature = 0.8,
            generationMaxOutputTokens = 1500,
            regenerationOccurred = true,
            regenerationCount = 1,
            outcome = "SUCCESS",
        )
        repository.record(original)

        val loaded = assertNotNull(repository.findByTurnRequestId(original.turnRequestId))
        assertEquals(original, loaded)
        assertEquals(memoryIds, loaded.memoryIdsUsed)
    }

    @Test
    fun `an unknown turn returns no attribution rather than an empty fabricated one`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        assertNull(TurnAttributionRepository(db).findByTurnRequestId(UUID.randomUUID()))
    }

    @Test
    fun `test chat and production turns stay distinguishable`() {
        val production = CapturingRecorder()
        val test = CapturingRecorder()
        engine(production, isTestChat = false).process(request())
        engine(test, isTestChat = true).process(request())

        assertFalse(production.records.single().isTestChat)
        assertTrue(test.records.single().isTestChat)
    }

    // ------------------------------------------------------------------
    // Security (Part 19)
    // ------------------------------------------------------------------

    @Test
    fun `the attribution record carries no credential-shaped field`() {
        val fields = TurnAttributionRecord::class.java.declaredFields.map { it.name.lowercase() }
        // "token" alone is excluded deliberately: maxOutputTokens is a token
        // BUDGET, not a credential.
        listOf("apikey", "api_key", "authorization", "bearer", "password", "secret", "credential").forEach { forbidden ->
            assertFalse(fields.any { it.contains(forbidden) }, "Attribution must never carry a '$forbidden' field")
        }
        // The record also carries no free-text prompt or memory body — only
        // identifiers, enums, numbers and configuration values.
        assertFalse(fields.any { it.contains("prompt") || it.contains("requestbody") || it.contains("responsebody") })
    }

    @Test
    fun `the admin UI renders every attribution value through escapeHtml`() {
        val ui = java.io.File("src/main/resources/admin-ui.html").readText()
        assertTrue(ui.contains("function attrValue(v)"), "The attribution renderer must exist")
        // attrValue/attrConfig/attrRow are the only paths attribution values
        // reach the DOM through, and each must escape.
        val renderer = ui.substringAfter("function attrValue(v)").substringBefore("function turnAttributionBlocks")
        assertTrue(renderer.contains("escapeHtml(String(v))"))
        assertTrue(renderer.contains("escapeHtml(String(av.value))"))
        assertTrue(renderer.contains("escapeHtml(av.source)"))
        assertTrue(renderer.contains("escapeHtml(k)"))
        assertTrue(ui.contains("NOT CURRENTLY ATTRIBUTED"), "A missing link must be shown explicitly, never as blank")
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun minimalRecord() = TurnAttributionRecord(
        turnRequestId = UUID.randomUUID(), conversationId = UUID.randomUUID(), userId = UUID.randomUUID(),
        isTestChat = false, personaId = UUID.randomUUID(), personaVersionId = UUID.randomUUID(), personaVersion = null,
        conversationEngineId = UUID.randomUUID(), conversationEngineVersion = null,
        intentEngineId = null, intentEngineVersion = null, intentModel = null, intentModelSource = null,
        intentJsonMode = null, intentJsonModeSource = null, intentMaxOutputTokens = null,
        intentMaxOutputTokensSource = null, intentOutcome = null, intentResultSkillKey = null,
        selectedSkillKey = null, skillContextInjected = null, memoryIdsUsed = null, memoryCountUsed = null,
        memoryCandidateCount = null, memorySelectionSource = null, userProfilePresent = null,
        userProfileUpdatedAt = null, generationModel = null, generationModelSource = null,
        generationTemperature = null, generationTemperatureSource = null, generationMaxOutputTokens = null,
        generationMaxOutputTokensSource = null, generationReasoning = null, generationJsonMode = null,
        generationProviderSort = null, generationProviderSortSource = null, regenerationOccurred = false,
        regenerationCount = 0, clientMessageId = UUID.randomUUID(), assistantMessageId = null,
        outcome = "SUCCESS", failedStage = null,
    )

    private class RecordingLlmClient(private val response: LlmResponse) : com.pinkdreams.llm.LlmClient {
        override fun generate(request: com.pinkdreams.llm.GenerationRequest): LlmResponse = response
    }

    private class AssemblerFixture(
        val assembler: RepositoryContextAssembler,
        val request: ChatRequest,
        val memoryIds: List<UUID>,
    )

    private fun assemblerFixture(db: Database, memoryCount: Int = 3): AssemblerFixture {
        val user = UUID.randomUUID()
        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("attr-${UUID.randomUUID()}", "Attr", "female", "straight", 30, emptyMap())
        val coreRepository = PersonaCoreVersionRepository(db)
        val core = coreRepository.publishCoreVersion(coreRepository.create(persona.id, 1, "core", "draft").id)
        personaRepository.activateCoreVersion(persona.id, core.id)
        val engineRepository = ConversationEngineRepository(db)
        val conversationEngine = engineRepository.publishEngine(engineRepository.create(1, "engine", "draft").id)
        engineRepository.activateEngine(conversationEngine.id)
        val conversation = ConversationRepository(db).create(user, persona.id)
        UserProfileRepository(db).create(user, "Asha", "en", "warm")
        val memoryService = MemoryService(MemoryFactRepository(db))
        val memoryIds = (0 until memoryCount).map {
            memoryService.record(user, persona.id, listOf(MemoryCandidate("memory-secret-$it", "habit", "low"))).single().id
        }
        return AssemblerFixture(
            RepositoryContextAssembler(
                ConversationRepository(db), MessageRepository(db), UserProfileRepository(db),
                memoryService, engineRepository, personaRepository,
            ),
            ChatRequest(UUID.randomUUID(), user, conversation.id, persona.id, UUID.randomUUID(), "hello"),
            memoryIds,
        )
    }
}
