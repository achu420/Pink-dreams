package com.pinkdreams.chat

import com.pinkdreams.baseline.BaselineConfiguration
import com.pinkdreams.baseline.BaselineSeeder
import com.pinkdreams.baseline.PersonaContextPocSeed
import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.chat.personacontext.production.BareScalarRendering
import com.pinkdreams.chat.personacontext.production.PersonaFieldAuthorizer
import com.pinkdreams.chat.personacontext.production.ProductionPersonaContextService
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
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PHASE 54 — Controlled Conversation-Engine ablation.
 *
 * Tests H1: removing the current Conversation Engine's grounding/
 * anti-fabrication directives changes B's behavioral error rate on the two
 * established scenarios (Pune-timeline, sister age-gap), holding the persona
 * payload exactly fixed (proven byte-identical in Phase 53).
 *
 * ENGINE-ABLATED is NOT a fictional "historical engine" -- it is the exact
 * current [BaselineConfiguration.CONVERSATION_ENGINE] text with ONLY the
 * specific grounding/anti-fabrication sentences removed (Part 1/2 of the
 * phase instruction). Every other line is byte-identical, mechanically
 * verified below.
 *
 * Test-only. Does not modify BaselineConfiguration.CONVERSATION_ENGINE or
 * any other production file.
 */
class Phase54EngineAblationTest {

    companion object {
        /**
         * The exact current engine text with ONLY these six sentences removed
         * (Part 1 classification: all are grounding [A] or anti-fabrication
         * [B] directives; nothing else was touched):
         *
         * 1. "Do not assume that information exists when it is not present in
         *    the supplied context." [A]
         * 2. "Do not manufacture familiarity, memories, experiences, or
         *    events." [B]
         * 3. "Do not invent memories." [B]
         * 4. "Do not treat recent conversation as long-term memory merely
         *    because it is visible." [C]
         * 5. "Do not claim to remember information that is not supplied by
         *    memory or recent conversation." [A]
         * 6. "Do not contradict it merely to satisfy a conversational
         *    direction." [A]
         * 7. "Do not invent persona biography outside the supplied Persona
         *    Core." [B -- the single most directly relevant line, per Phase
         *    53's own citation]
         *
         * Correction to Phase 53 §5/§13/§16: that report also cited "Do not
         * present inference or fiction as established fact" as a candidate
         * directive. Re-verified this phase by exact line-range lookup: that
         * sentence belongs to [BaselineConfiguration.MEMORY_ENGINE] (a
         * separate side-channel prompt for memory extraction), NOT
         * [BaselineConfiguration.CONVERSATION_ENGINE] -- it never reaches the
         * model during the Pune/sister chat-generation calls this experiment
         * concerns, and is therefore correctly excluded from this ablation.
         */
        private val REMOVED_SENTENCES = listOf(
            "Do not assume that information exists when it is not present in the supplied\ncontext.",
            "- Do not manufacture familiarity, memories, experiences, or events.",
            "Do not invent memories.",
            "Do not treat recent conversation as long-term memory merely because it is\nvisible.",
            "Do not claim to remember information that is not supplied by memory or recent\nconversation.",
            "Do not contradict it merely to satisfy a conversational direction.",
            "Do not invent persona biography outside the supplied Persona Core.",
        )

        /**
         * Line-based removal (robust to trimIndent()'s runtime-computed
         * indentation, unlike a literal whitespace-prefixed string replace):
         * splits into lines, drops any line whose trimmed content matches one
         * of the (possibly multi-line, so also line-split) removed sentences,
         * then collapses any resulting doubled blank line back to one.
         */
        val ENGINE_ABLATED: String = run {
            val removedLineSet = REMOVED_SENTENCES.flatMap { it.lines() }.map { it.trim() }.toSet()
            val kept = BaselineConfiguration.CONVERSATION_ENGINE.lines().filter { it.trim() !in removedLineSet }
            val collapsed = mutableListOf<String>()
            for (line in kept) {
                if (line.isBlank() && collapsed.lastOrNull()?.isBlank() == true) continue
                collapsed += line
            }
            collapsed.joinToString("\n")
        }
    }

    // Part 1/2/5 (of the byte-diff requirement) -- zero-cost, run first.
    @Test
    fun `ablated engine differs from current engine ONLY in the seven identified directive sentences`() {
        val currentLines = BaselineConfiguration.CONVERSATION_ENGINE.lines()
        val ablatedLines = ENGINE_ABLATED.lines()
        val removedLineSet = REMOVED_SENTENCES.flatMap { it.lines() }.map { it.trim() }.toSet()

        removedLineSet.forEach { target ->
            assertTrue(currentLines.any { it.trim() == target }, "sanity: '$target' must exist verbatim in the current engine")
            assertTrue(ablatedLines.none { it.trim() == target }, "'$target' must be fully absent from the ablated engine")
        }

        // Every non-blank line NOT in the removed set must appear, in the
        // same relative order, in both engines -- proving nothing else changed.
        val currentUnrelated = currentLines.map { it.trim() }.filter { it.isNotEmpty() && it !in removedLineSet }
        val ablatedUnrelated = ablatedLines.map { it.trim() }.filter { it.isNotEmpty() && it !in removedLineSet }
        assertEquals(currentUnrelated, ablatedUnrelated, "every unrelated line, in order, must be byte-identical between current and ablated")

        println("Current engine: ${BaselineConfiguration.CONVERSATION_ENGINE.length} chars, ${currentLines.size} lines")
        println("Ablated engine: ${ENGINE_ABLATED.length} chars, ${ablatedLines.size} lines")
        println("Chars removed: ${BaselineConfiguration.CONVERSATION_ENGINE.length - ENGINE_ABLATED.length}")
    }

    private class RoutingLlmClient(private val real: LlmClient, private val skillKey: String) : LlmClient {
        var lastCapturedContext: ChatContext? = null
        override fun generate(request: GenerationRequest): LlmResponse {
            val firstBlock = request.context.blocks.firstOrNull()?.content ?: ""
            return when {
                firstBlock.contains("Intent Discovery component") -> LlmResponse(content = """{"skillKey": "$skillKey"}""", provider = "test")
                firstBlock.contains("memory-extraction system") -> LlmResponse(content = """{"facts": []}""", provider = "test")
                firstBlock.contains("userMemoryChanges") -> LlmResponse(content = """{"userMemoryChanges": [], "personaMemoryChanges": []}""", provider = "test")
                else -> {
                    lastCapturedContext = request.context
                    real.generate(request)
                }
            }
        }
    }

    private class Harness(private val skillKey: String, private val engineText: String) {
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
            require(apiKey.isNotEmpty()) { "OPENROUTER_API_KEY must be set" }
            BaselineSeeder(engines, personas, cores, skills, MemoryEngineRepository(db)).seedIfMissing()
            skills.findAll().forEach { skills.activate(skills.publish(it.id).id) }
            personaId = personas.findAll().first { it.slug == BaselineConfiguration.SIMRAN_SLUG }.id
            personaCoreVersionId = requireNotNull(personas.findById(personaId)?.activeCoreVersionId)

            if (skillKey !in listOf("general_chat") && skills.findByKey(skillKey).isEmpty()) {
                val v = skills.createNextVersion(skillKey, "SKILL: ${skillKey.uppercase()}\n\nPurpose:\nPersona self-introduction and background overview.")
                skills.activate(skills.publish(v.id).id)
            }

            val seed = PersonaContextPocSeed(db)
            seed.seedPersonaFieldRegistry()
            seed.seedSkillContextRequirements()
            seed.seedSimranMasterDocument(personaCoreVersionId)

            // THE ONLY EXPERIMENTAL MANIPULATION: this test-scoped database's
            // active engine content is set to either the current or ablated
            // text. BaselineConfiguration.CONVERSATION_ENGINE itself
            // (production source) is never modified.
            val draft = engines.createNextVersion(content = engineText, changelogNote = "Phase 54 experiment engine", createdBy = "phase54")
            engines.activateEngine(engines.publishEngine(draft.id).id)
        }

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

        fun buildEngineChat(client: LlmClient): PipelineChatEngine {
            val contextAssembler = RepositoryContextAssembler(conversations, messages, UserProfileRepository(db), memoryService, engines, personas)
            val intentDiscovery = IntentDiscovery { _, _ -> SkillSelection.Selected(skillKey) }
            val personaContextResolution = PersonaContextResolutionStage(ProductionPersonaContextService(authorizer(), BareScalarRendering))
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

    data class CallRecord(val index: Int, val condition: String, val response: String, val status: String)

    private fun runScenario(scenarioName: String, skillKey: String, userMessage: String, n: Int, applyPuneFix: Boolean, csvName: String) {
        val currentHarness = Harness(skillKey, BaselineConfiguration.CONVERSATION_ENGINE)
        if (applyPuneFix) currentHarness.applyPuneFix()
        val ablatedHarness = Harness(skillKey, ENGINE_ABLATED)
        if (applyPuneFix) ablatedHarness.applyPuneFix()

        // Zero-cost precondition: PERSONA block must be byte-identical between the two harnesses.
        val currentPersona = currentHarness.authorizer().resolve(currentHarness.personaId, currentHarness.personaCoreVersionId, skillKey, userMessage, BareScalarRendering)
        val ablatedPersona = ablatedHarness.authorizer().resolve(ablatedHarness.personaId, ablatedHarness.personaCoreVersionId, skillKey, userMessage, BareScalarRendering)
        println("\n════ $scenarioName PRECONDITION (zero-cost) ════")
        println("current fields=${currentPersona.includedFieldIds}")
        println("ablated fields=${ablatedPersona.includedFieldIds}")
        assertEquals(currentPersona.renderedText, ablatedPersona.renderedText, "STOP: PERSONA payload must be byte-identical between current and ablated engine conditions")

        val currentClient = RoutingLlmClient(OpenRouterLlmClient(apiKey = currentHarness.apiKey, model = "deepseek/deepseek-v4-flash-0731", maxOutputTokens = 1024, timeoutSeconds = 300), skillKey)
        val ablatedClient = RoutingLlmClient(OpenRouterLlmClient(apiKey = ablatedHarness.apiKey, model = "deepseek/deepseek-v4-flash-0731", maxOutputTokens = 1024, timeoutSeconds = 300), skillKey)
        val currentEngine = currentHarness.buildEngineChat(currentClient)
        val ablatedEngine = ablatedHarness.buildEngineChat(ablatedClient)

        val order = (1..n).flatMap { listOf("CURRENT", "ABLATED") }
        println("\n════ $scenarioName PREDETERMINED ORDER (n=$n per group, ${order.size} total) ════")

        val records = mutableListOf<CallRecord>()
        var invariantViolation = false
        order.forEachIndexed { idx, condition ->
            val (harness, engine, client) = if (condition == "CURRENT") Triple(currentHarness, currentEngine, currentClient) else Triple(ablatedHarness, ablatedEngine, ablatedClient)
            var responseText = ""; var status = "SUCCESS"
            try {
                val result = harness.sendOnce(engine, userMessage)
                when (result) {
                    is ChatResult.Success -> responseText = result.response.content
                    else -> { status = "FAILED"; println("Call ${idx + 1} [$condition] FAILED: $result") }
                }
            } catch (e: Exception) { status = "FAILED"; println("Call ${idx + 1} [$condition] EXCEPTION: ${e.message}") }

            val capturedCtx = client.lastCapturedContext
            if (capturedCtx != null) {
                val personaBlock = capturedCtx.blocks.firstOrNull { it.blockType == ContextBlockType.PERSONA }?.content ?: ""
                val engineBlock = capturedCtx.blocks.firstOrNull { it.blockType == ContextBlockType.ENGINE }?.content ?: ""
                val expectedPersona = if (condition == "CURRENT") currentPersona.renderedText else ablatedPersona.renderedText
                if (!personaBlock.endsWith(expectedPersona)) { invariantViolation = true; println("STOP-CANDIDATE: persona block mismatch on call ${idx + 1}") }
                val expectedEngineFragment = if (condition == "CURRENT") "Do not invent persona biography" else null
                if (expectedEngineFragment != null && !engineBlock.contains(expectedEngineFragment)) { invariantViolation = true; println("STOP-CANDIDATE: current engine missing expected directive on call ${idx + 1}") }
                if (condition == "ABLATED" && engineBlock.contains("Do not invent persona biography outside the supplied Persona Core")) { invariantViolation = true; println("STOP-CANDIDATE: ablated engine still contains removed directive on call ${idx + 1}") }
            }

            records += CallRecord(idx + 1, condition, responseText, status)
            println("Call ${idx + 1} [$condition]: status=$status")
            println("  response: $responseText")
            Thread.sleep(1000)
        }

        val dir = "E:\\Projects\\Pink dreams\\docs\\Persona update POC"
        val header = "call_index,condition,response,status\n"
        val rows = records.joinToString("\n") { r -> listOf(r.index, r.condition, "\"${r.response.replace("\"", "\"\"")}\"", r.status).joinToString(",") }
        File("$dir/$csvName").writeText(header + rows)
        println("Saved: $dir/$csvName")
        println("Pipeline invariant violations detected: $invariantViolation")
        assertTrue(!invariantViolation, "STOP: a pipeline invariant was violated -- see log above")
    }

    @Test
    fun `Pune timeline - current vs ablated engine, n=24 per group interleaved`() {
        runScenario("PUNE", "general_chat", "Been in this city long?", n = 24, applyPuneFix = true, csvName = "PHASE54_PUNE_EXECUTIONS.csv")
    }

    @Test
    fun `Sister age-gap - current vs ablated engine, n=24 per group interleaved`() {
        runScenario("SISTER", "about", "Is there a big age gap between you and your sister in your family?", n = 24, applyPuneFix = false, csvName = "PHASE54_SISTER_EXECUTIONS.csv")
    }
}
