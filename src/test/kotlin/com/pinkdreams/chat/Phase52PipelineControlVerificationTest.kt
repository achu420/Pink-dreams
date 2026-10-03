package com.pinkdreams.chat

import com.pinkdreams.baseline.BaselineConfiguration
import com.pinkdreams.baseline.BaselineSeeder
import com.pinkdreams.baseline.PersonaContextPocSeed
import com.pinkdreams.chat.context.AgeOnlyLabeledScalarDiagnosticProvider
import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.chat.context.ResolvedPersonaContextProvider
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
import com.pinkdreams.persistence.repositories.PersonaFieldRegistryRepository
import com.pinkdreams.persistence.repositories.PersonaMasterRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillContextRequirementRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository
import com.pinkdreams.persistence.repositories.UserRepository
import com.pinkdreams.testchat.TestChatService
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertTrue

/**
 * PHASE 52 Step 1 — ZERO-COST verification of whether the historical
 * Phase 26/30 B-vs-LA mechanism (`TestChatService.CreationRequest.personaContextVariant`
 * -> `RepositoryContextAssembler.personaContextProvider`, a CONTEXT_ASSEMBLY-time
 * hook) still produces its intended, distinct B/LA text once the request reaches
 * the model, now that `ChatEngineFactory.build()` unconditionally ALSO wires the
 * Phase 49 Strategy-D `PersonaContextResolutionStage` (a POST-skill-selection
 * hook that replaces the SAME `ContextBlockType.PERSONA` block, using
 * `BareScalarRendering` always, via a fully independent selection/rendering
 * implementation, `PersonaFieldAuthorizer`).
 *
 * `RepositoryContextAssembler.assemble()` (source, current) tags whatever
 * `personaContextProvider` returns as `ContextBlockType.PERSONA` and sets
 * `ChatContext.personaCoreVersionId` -- both of which are exactly what
 * `PersonaContextResolutionStage.resolve()` needs to find and REPLACE that
 * block later in the same pipeline run, if `PersonaFieldAuthorizer` returns a
 * USABLE result whose text differs from whatever is already in the block.
 *
 * This test answers, with zero API calls (`interceptGeneration`-equivalent: a
 * local fake `LlmClient` that never touches the network), whether that
 * replacement actually happens for the LA condition on the real Pune-timeline
 * scenario setup, using the exact research seed (`PersonaContextPocSeed`) the
 * historical experiments used.
 */
class Phase52PipelineControlVerificationTest {

    /**
     * Routes based on which dedicated system prompt was sent (same pattern
     * already used elsewhere in this codebase's own pipeline tests, e.g.
     * `TestChatServiceTest`'s `trackingClient`): answers the real
     * Intent Discovery call deterministically so a real skill key reaches
     * the pipeline, and captures only the actual chat-generation request
     * (the one whose context carries the PERSONA block) -- zero network calls.
     */
    private class CapturingLlmClient(private val skillKey: String) : LlmClient {
        var lastChatRequest: GenerationRequest? = null
        override fun generate(request: GenerationRequest): LlmResponse {
            val firstBlock = request.context.blocks.firstOrNull()?.content ?: ""
            return when {
                firstBlock.contains("Intent Discovery component") -> LlmResponse(content = """{"skillKey": "$skillKey"}""", provider = "test")
                firstBlock.contains("memory-extraction system") -> LlmResponse(content = """{"facts": []}""", provider = "test")
                firstBlock.contains("userMemoryChanges") -> LlmResponse(content = """{"userMemoryChanges": [], "personaMemoryChanges": []}""", provider = "test")
                else -> {
                    lastChatRequest = request
                    LlmResponse(content = "captured, no network call made", provider = "test")
                }
            }
        }
    }

    @Test
    fun `does the Phase 49 resolution stage overwrite the old-mechanism LA payload on the real Pune-timeline setup`() {
        val db: Database = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }
        val engines = ConversationEngineRepository(db)
        val personas = PersonaRepository(db)
        val cores = PersonaCoreVersionRepository(db)
        val skills = SkillRepository(db)
        val memoryEngines = MemoryEngineRepository(db)
        val intentEngines = IntentEngineRepository(db)
        val aiSettingsRepo = AiSettingsRepository(db)
        val memoryFacts = MemoryFactRepository(db)
        val memoryService = com.pinkdreams.chat.memory.MemoryService(memoryFacts)
        val messages = MessageRepository(db)
        val conversations = ConversationRepository(db)
        val userProfiles = UserProfileRepository(db)
        val users = UserRepository(db)
        val executions = ChatRequestExecutionRepository(db)
        val fieldRegistryRepo = PersonaFieldRegistryRepository(db)
        val skillReqRepo = SkillContextRequirementRepository(db)
        val personaMasterRepo = PersonaMasterRepository(db)

        val testUserId = UUID.randomUUID()
        users.create(testUserId)
        BaselineSeeder(engines, personas, cores, skills, memoryEngines, intentEngines).seedIfMissing()
        skills.findAll().forEach { skills.activate(skills.publish(it.id).id) }
        val personaId = personas.findAll().first { it.slug == BaselineConfiguration.SIMRAN_SLUG }.id
        val personaCoreVersionId = requireNotNull(personas.findById(personaId)?.activeCoreVersionId)

        // Exact historical research seed (PersonaContextPocSeed) -- same field
        // registry / skill mappings / master document Phase 26/30 used.
        val seed = PersonaContextPocSeed(db)
        seed.seedPersonaFieldRegistry()
        seed.seedSkillContextRequirements()
        seed.seedSimranMasterDocument(personaCoreVersionId)
        // The same inline mapping fix Phase 26's own test applies (identity.age +
        // life_context.important_life_events added to general_chat's conditional fields).
        val ileEntry = fieldRegistryRepo.findByFieldId("life_context.important_life_events")!!
        fieldRegistryRepo.update(ileEntry.copy(description = "life_context.important_life_events years long since moved arrived came time"))
        val gc = skillReqRepo.findBySkillKey("general_chat")!!
        skillReqRepo.update(gc.copy(conditionalFields = (gc.conditionalFields ?: emptyList()) + listOf("identity.age", "life_context.important_life_events")))
        val ageEntry = fieldRegistryRepo.findByFieldId("identity.age")!!
        fieldRegistryRepo.update(ageEntry.copy(description = "identity.age years long since moved arrived came time"))

        val skill = "general_chat"
        val msg = "Been in this city long?"

        val capturingClient = CapturingLlmClient(skillKey = skill)
        val llmConfig = LlmConfig(apiKey = "unused-intercepted", model = "unused-intercepted", maxOutputTokens = 1024)
        val aiRuntimeSettings = AiRuntimeSettings(llmConfig, aiSettingsRepo)

        val laProvider = AgeOnlyLabeledScalarDiagnosticProvider(db, personaMasterRepo, fieldRegistryRepo, skillReqRepo)
        val expectedLaText = laProvider.getPersonaContext(personaId, personaCoreVersionId, skill, msg)
        assertTrue(expectedLaText.contains("Age: 27"), "sanity: the old-mechanism LA provider itself must render the age-labeled line")

        val pinnedDeps = com.pinkdreams.chat.ChatEngineFactory.Dependencies(
            llmClient = capturingClient,
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
            isTestChat = true,
            personaContextProvider = laProvider,
            personaContextSkillKey = skill,
        )
        val engine = com.pinkdreams.chat.ChatEngineFactory.build(pinnedDeps)

        val conversation = conversations.create(testUserId, personaId)
        val request = ChatRequest(UUID.randomUUID(), testUserId, conversation.id, personaId, UUID.randomUUID(), msg)
        val result = engine.process(request)

        println("Pipeline result: $result")
        val captured = capturingClient.lastChatRequest
        println("Was a chat-generation request captured at all? ${captured != null}")
        if (captured != null) {
            val personaBlock = captured.context.blocks.first { it.blockType == ContextBlockType.PERSONA }
            println("\n════ CAPTURED FINAL PERSONA BLOCK ════")
            println(personaBlock.content)
            println("════ END ════\n")
            println("Contains 'Age: 27' (LA-labeled, as intended by the old mechanism): ${personaBlock.content.contains("Age: 27")}")
            println("Contains bare '27' without the label: ${personaBlock.content.contains(Regex("\\b27\\b")) && !personaBlock.content.contains("Age: 27")}")
            println("Selected skill key on final context: ${captured.skillKey}")
            println("Byte-identical to the old-mechanism LA provider's own output: ${personaBlock.content == expectedLaText || personaBlock.content.endsWith(expectedLaText)}")
        }
    }
}
