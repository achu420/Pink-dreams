package com.pinkdreams.chat

import com.pinkdreams.baseline.BaselineConfiguration
import com.pinkdreams.baseline.BaselineSeeder
import com.pinkdreams.baseline.PersonaContextPocSeed
import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.chat.personacontext.production.BareScalarRendering
import com.pinkdreams.chat.personacontext.production.AgeOnlyLabeledScalarRendering
import com.pinkdreams.chat.personacontext.production.PersonaFieldAuthorizer
import com.pinkdreams.chat.personacontext.production.ProductionPersonaContextService
import com.pinkdreams.chat.personacontext.production.isUsable
import com.pinkdreams.chat.skill.IntentDiscovery
import com.pinkdreams.chat.skill.SkillSelection
import com.pinkdreams.llm.GenerationConfig
import com.pinkdreams.llm.GenerationRequest
import com.pinkdreams.llm.LlmClient
import com.pinkdreams.llm.LlmGenerator
import com.pinkdreams.llm.LlmResponse
import com.pinkdreams.llm.OpenRouterLlmClient
import com.pinkdreams.persistence.RepositoryChatExecutionCoordinator
import com.pinkdreams.persistence.RepositoryChatPersistence
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ChatRequestExecutionRepository
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaFieldRegistryRepository
import com.pinkdreams.persistence.repositories.PersonaMasterRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillContextRequirementRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PHASE 52 — Controlled Production Behavioral Validation.
 *
 * Tests whether the B-vs-LA effect established through the research/diagnostic
 * harness (Phase 26: Pune-timeline, p=0.00036; Phase 30: sister age-gap,
 * p=0.0336) survives when B/LA are delivered through the REAL production
 * Strategy-D pipeline (PersonaFieldAuthorizer -> ProductionPersonaContextService
 * -> PersonaContextResolutionStage), instead of the old research-only
 * CONTEXT_ASSEMBLY-time provider hook those phases used.
 *
 * Step 1 finding (Phase52PipelineControlVerificationTest, zero-cost, run
 * first): the old provider hook's output is silently overwritten by the
 * Phase 49 Strategy-D stage once a real skill key exists, because both hooks
 * write to the same ContextBlockType.PERSONA block and Strategy-D always runs
 * second. This means `TestChatService.personaContextVariant` can NOT control
 * what the model actually receives, on the current codebase. Per this phase's
 * own Step 1 instruction ("If B and LA cannot currently be selected cleanly
 * through production code, implement the smallest experiment-only
 * configuration mechanism necessary. Do NOT create a second production
 * architecture."), this harness instead controls B vs LA at the one point
 * that genuinely determines final output today: which ScalarRenderingPolicy
 * is passed into ProductionPersonaContextService, which the real
 * PersonaContextResolutionStage then uses. Every other component
 * (PersonaFieldAuthorizer, PersonaContextResolutionStage, PipelineChatEngine,
 * RepositoryContextAssembler) is the real, unmodified production class.
 *
 * Experiment-scoped data (field registry, skill mappings, master document)
 * is the exact historical PersonaContextPocSeed data plus the exact Phase 26
 * inline mapping fix, isolated in a fresh in-memory database -- never the
 * real committed PersonaContextSeeder data, and never written to any
 * persistent database.
 */
class Phase52ProductionBehavioralValidationTest {

    /** Routes intent-discovery/memory side-channel calls deterministically; captures only the real chat-generation call. */
    private class RoutingLlmClient(private val real: LlmClient, private val skillKey: String) : LlmClient {
        var lastCapturedSystemBlock: String? = null
        override fun generate(request: GenerationRequest): LlmResponse {
            val firstBlock = request.context.blocks.firstOrNull()?.content ?: ""
            return when {
                firstBlock.contains("Intent Discovery component") -> LlmResponse(content = """{"skillKey": "$skillKey"}""", provider = "test")
                firstBlock.contains("memory-extraction system") -> LlmResponse(content = """{"facts": []}""", provider = "test")
                firstBlock.contains("userMemoryChanges") -> LlmResponse(content = """{"userMemoryChanges": [], "personaMemoryChanges": []}""", provider = "test")
                else -> {
                    lastCapturedSystemBlock = request.context.blocks.firstOrNull { it.blockType == ContextBlockType.PERSONA }?.content
                    real.generate(request)
                }
            }
        }
    }

    private class Harness(private val skillKey: String) {
        val db: Database = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }
        val engines = ConversationEngineRepository(db)
        val personas = PersonaRepository(db)
        val cores = PersonaCoreVersionRepository(db)
        val skills = SkillRepository(db)
        val fieldRegistryRepo = PersonaFieldRegistryRepository(db)
        val skillReqRepo = SkillContextRequirementRepository(db)
        val personaMasterRepo = PersonaMasterRepository(db)
        val conversations = ConversationRepository(db)
        val messages = MessageRepository(db)
        val executions = ChatRequestExecutionRepository(db)
        val memoryService = MemoryService(MemoryFactRepository(db))

        val apiKey: String = System.getenv("OPENROUTER_API_KEY") ?: ""
        val personaId: UUID
        val personaCoreVersionId: UUID
        val userId: UUID = UUID.randomUUID()

        init {
            require(apiKey.isNotEmpty()) { "OPENROUTER_API_KEY must be set (source run-local.secrets.ps1 first)" }
            BaselineSeeder(engines, personas, cores, skills, com.pinkdreams.persistence.repositories.MemoryEngineRepository(db)).seedIfMissing()
            skills.findAll().forEach { skills.activate(skills.publish(it.id).id) }
            personaId = personas.findAll().first { it.slug == BaselineConfiguration.SIMRAN_SLUG }.id
            personaCoreVersionId = requireNotNull(personas.findById(personaId)?.activeCoreVersionId)

            // Experiment-scoped skill definitions for the two historical, non-production skill keys.
            if (skillKey !in listOf("general_chat")) {
                if (skills.findByKey(skillKey).isEmpty()) {
                    val v = skills.createNextVersion(skillKey, "SKILL: ${skillKey.uppercase()}\n\nPurpose:\nPersona self-introduction and background overview.\n\nBehavior:\nAnswer questions about who the persona is, their background, values, and current life -- grounded in the Persona Core.\n\nDo not:\nInvent facts that contradict the Persona Core.")
                    skills.activate(skills.publish(v.id).id)
                }
            }

            val seed = PersonaContextPocSeed(db)
            seed.seedPersonaFieldRegistry()
            seed.seedSkillContextRequirements()
            seed.seedSimranMasterDocument(personaCoreVersionId)
        }

        /**
         * Exact historical Pune-timeline mapping fixes -- BOTH the Phase 10e
         * fix (`applyOriginalPhase10eFixes`, adds identity.hometown/
         * current_city as required + life_context.family/friends as
         * conditional to general_chat) AND the Phase 26-specific inline fix
         * (adds identity.age + life_context.important_life_events as
         * conditional). The first run of this harness omitted the Phase 10e
         * fix by mistake (a harness construction error, discovered only
         * after 40 real calls had already been made with an incomplete field
         * set) -- see PHASE52_PRODUCTION_BEHAVIORAL_VALIDATION.md for full
         * disclosure of that invalid run's data, kept but excluded from the
         * primary analysis.
         */
        fun applyPuneFix() {
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

        fun authorizer() = PersonaFieldAuthorizer(db, personaMasterRepo, fieldRegistryRepo, skillReqRepo)

        fun buildEngine(policy: com.pinkdreams.chat.personacontext.production.ScalarRenderingPolicy, client: LlmClient): PipelineChatEngine {
            val contextAssembler = RepositoryContextAssembler(conversations, messages, UserProfileRepository(db), memoryService, engines, personas)
            val intentDiscovery = IntentDiscovery { _, _ -> SkillSelection.Selected(skillKey) }
            val personaContextResolution = PersonaContextResolutionStage(ProductionPersonaContextService(authorizer(), policy))
            val persistence = RepositoryChatPersistence(db, executions)
            val coordinator = RepositoryChatExecutionCoordinator(executions, conversations, messages)
            val generator = LlmGenerator(client, GenerationConfig(model = "deepseek/deepseek-v4-flash-0731", maxOutputTokens = 1024))
            return PipelineChatEngine(
                entitlementChecker = { EntitlementDecision.Allowed },
                inputModerator = { ModerationDecision.Allowed },
                contextAssembler = contextAssembler,
                generator = generator,
                outputValidator = { _, _ -> ValidationDecision.Accepted },
                persistence = persistence,
                delivery = { _, _ -> StageResult.Succeeded(Unit) },
                executionCoordinator = coordinator,
                intentDiscovery = intentDiscovery,
                personaContextResolution = personaContextResolution,
            )
        }

        fun sendOnce(engine: PipelineChatEngine, userMessage: String): ChatResult {
            val conversation = conversations.create(userId, personaId)
            val request = ChatRequest(UUID.randomUUID(), userId, conversation.id, personaId, UUID.randomUUID(), userMessage)
            return engine.process(request)
        }
    }

    data class CallRecord(
        val index: Int, val condition: String, val response: String, val status: String,
        val payloadContainsLabel: Boolean, val restrictedFieldPresent: Boolean,
    )

    private fun runScenario(
        scenarioName: String, skillKey: String, userMessage: String, n: Int, applyPuneFix: Boolean, csvName: String,
    ): List<CallRecord> {
        val harness = Harness(skillKey)
        if (applyPuneFix) harness.applyPuneFix()

        // Zero-cost precondition verification, before any real call.
        val bResult = harness.authorizer().resolve(harness.personaId, harness.personaCoreVersionId, skillKey, userMessage, BareScalarRendering)
        val laResult = harness.authorizer().resolve(harness.personaId, harness.personaCoreVersionId, skillKey, userMessage, AgeOnlyLabeledScalarRendering)
        println("\n════ $scenarioName PRECONDITION VERIFICATION (zero-cost) ════")
        println("B status=${bResult.status} fields=${bResult.includedFieldIds}")
        println("LA status=${laResult.status} fields=${laResult.includedFieldIds}")
        assertTrue(bResult.isUsable() && laResult.isUsable(), "STOP: B/LA must both be USABLE before any real call")
        assertEquals(bResult.includedFieldIds.toSet(), laResult.includedFieldIds.toSet(), "STOP: B and LA must select identical fields")
        if (applyPuneFix) {
            val expectedHistoricalFields = setOf(
                "identity.name", "personality.core_traits", "identity.hometown", "identity.current_city",
                "communication.style", "narrative_state.unresolved_threads", "life_context.occupation",
                "identity.age", "life_context.important_life_events",
            )
            assertEquals(expectedHistoricalFields, bResult.includedFieldIds.toSet(), "STOP: selected fields must exactly match the historical Phase 26 field set")
        }
        assertTrue(laResult.renderedText.contains("Age:"), "STOP: LA must render the age label")
        assertTrue(!bResult.renderedText.contains("Age:"), "STOP: B must not render the age label")
        val noRestricted = !bResult.renderedText.contains("adult_intimacy") && !bResult.renderedText.contains("intimate_anatomy") &&
            !laResult.renderedText.contains("adult_intimacy") && !laResult.renderedText.contains("intimate_anatomy")
        assertTrue(noRestricted, "STOP: restricted field present in B or LA payload")

        val bClientReal = OpenRouterLlmClient(apiKey = harness.apiKey, model = "deepseek/deepseek-v4-flash-0731", maxOutputTokens = 1024, timeoutSeconds = 300)
        val bRoutingClient = RoutingLlmClient(bClientReal, skillKey)
        val laClientReal = OpenRouterLlmClient(apiKey = harness.apiKey, model = "deepseek/deepseek-v4-flash-0731", maxOutputTokens = 1024, timeoutSeconds = 300)
        val laRoutingClient = RoutingLlmClient(laClientReal, skillKey)
        val bEngine = harness.buildEngine(BareScalarRendering, bRoutingClient)
        val laEngine = harness.buildEngine(AgeOnlyLabeledScalarRendering, laRoutingClient)

        // Predetermined INTERLEAVED order, fixed before any call, matching Phase 26/30 convention.
        val order = (1..n).flatMap { listOf("B", "LA") }
        println("\n════ $scenarioName PREDETERMINED ORDER (n=$n per group, ${order.size} total) ════")

        val records = mutableListOf<CallRecord>()
        order.forEachIndexed { idx, condition ->
            val (engine, routingClient) = if (condition == "B") bEngine to bRoutingClient else laEngine to laRoutingClient
            var responseText = ""
            var status = "SUCCESS"
            try {
                val result = harness.sendOnce(engine, userMessage)
                when (result) {
                    is ChatResult.Success -> responseText = result.response.content
                    else -> { status = "FAILED"; println("Call ${idx + 1} [$condition] FAILED: $result") }
                }
            } catch (e: Exception) {
                status = "FAILED"; println("Call ${idx + 1} [$condition] EXCEPTION: ${e.message}")
            }
            val capturedBlock = routingClient.lastCapturedSystemBlock ?: ""
            val payloadContainsLabel = capturedBlock.contains("Age:")
            val restrictedPresent = capturedBlock.contains("adult_intimacy") || capturedBlock.contains("intimate_anatomy")
            records += CallRecord(idx + 1, condition, responseText, status, payloadContainsLabel, restrictedPresent)
            println("Call ${idx + 1} [$condition]: status=$status payloadHasLabel=$payloadContainsLabel restricted=$restrictedPresent")
            println("  response: $responseText")
            Thread.sleep(1000)
        }

        val dir = "E:\\Projects\\Pink dreams\\docs\\Persona update POC"
        val header = "call_index,condition,response,status,payload_contains_label,restricted_field_present\n"
        val rows = records.joinToString("\n") { r ->
            listOf(r.index, r.condition, "\"${r.response.replace("\"", "\"\"")}\"", r.status, r.payloadContainsLabel, r.restrictedFieldPresent).joinToString(",")
        }
        File("$dir/$csvName").writeText(header + rows)
        println("Saved: $dir/$csvName")

        // Pipeline invariant checks (Step 8) -- payload label correctness per condition.
        val labelInvariantViolations = records.count { (it.condition == "B" && it.payloadContainsLabel) || (it.condition == "LA" && !it.payloadContainsLabel && it.status == "SUCCESS") }
        println("Label-invariant violations: $labelInvariantViolations")
        val restrictedLeaks = records.count { it.restrictedFieldPresent }
        println("Restricted-field leaks: $restrictedLeaks")

        return records
    }

    @Test
    fun `Pune timeline scenario - n=20 per group interleaved`() {
        runScenario(
            scenarioName = "PUNE TIMELINE",
            skillKey = "general_chat",
            userMessage = "Been in this city long?",
            n = 20,
            applyPuneFix = true,
            csvName = "PHASE52_PUNE_EXECUTIONS.csv",
        )
    }

    @Test
    fun `Sister age-gap scenario - n=59 per group interleaved`() {
        runScenario(
            scenarioName = "SISTER AGE-GAP",
            skillKey = "about",
            userMessage = "Is there a big age gap between you and your sister in your family?",
            n = 59,
            applyPuneFix = false,
            csvName = "PHASE52_SISTER_EXECUTIONS.csv",
        )
    }
}
