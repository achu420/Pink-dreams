package com.pinkdreams.chat.personacontext.production

import com.pinkdreams.baseline.BaselineConfiguration
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ChatResult
import com.pinkdreams.chat.ContextBlockType
import com.pinkdreams.chat.EntitlementDecision
import com.pinkdreams.chat.ModerationDecision
import com.pinkdreams.chat.PersonaContextResolutionStage
import com.pinkdreams.chat.PipelineChatEngine
import com.pinkdreams.chat.StageResult
import com.pinkdreams.chat.ValidationDecision
import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.chat.skill.IntentDiscovery
import com.pinkdreams.chat.skill.SkillSelection
import com.pinkdreams.llm.GenerationConfig
import com.pinkdreams.llm.GenerationRequest
import com.pinkdreams.llm.LlmClient
import com.pinkdreams.llm.LlmGenerator
import com.pinkdreams.llm.LlmResponse
import com.pinkdreams.persistence.RepositoryChatExecutionCoordinator
import com.pinkdreams.persistence.RepositoryChatPersistence
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ChatRequestExecutionRepository
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaFieldRegistryEntry
import com.pinkdreams.persistence.repositories.PersonaFieldRegistryRepository
import com.pinkdreams.persistence.repositories.PersonaMasterRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillContextRequirement
import com.pinkdreams.persistence.repositories.SkillContextRequirementRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * PHASE 59 Step 5 — regression test reproducing the Phase 57 failure
 * sequence: an earlier turn produces an assistant reply with no authorized
 * persona data available for the disputed fact (so the model improvised),
 * followed by a later turn where the correct authorized Persona Core field
 * IS available and the user asks the same biographical question again.
 *
 * The pipeline cannot deterministically prove what a real model does with
 * conflicting information (that is Phase 59 Step 6's job, against the real
 * LLM). What this test proves deterministically is the CONTRACT the model is
 * actually given at generation time on that later turn: the authorized,
 * current Persona Core fact is present in the PERSONA block, the
 * self-contradicting prior assistant turn is still present verbatim in
 * history (unfiltered, as established in Phase 58), and the ENGINE block
 * contains the Fix B precedence instruction telling the model the Persona
 * Core -- not its own earlier turn -- is authoritative on conflict. If any
 * of these three preconditions were missing, the model would have no
 * contractual basis to resolve the conflict correctly, regardless of prompt
 * wording.
 */
class Phase59ContradictionContractRegressionTest {

    private class RecordingLlmClient(private val reply: String) : LlmClient {
        var lastRequest: GenerationRequest? = null
        override fun generate(request: GenerationRequest): LlmResponse {
            lastRequest = request
            return LlmResponse(content = reply, provider = "test", model = "test-model")
        }
    }

    @Test
    fun `later turn's generation request carries the authorized fact, the conflicting prior turn, and the precedence instruction`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userId = UUID.randomUUID()
        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("p59-persona-${UUID.randomUUID()}", "Phase59 Persona", "female", "straight", 27, emptyMap())
        val coreRepository = PersonaCoreVersionRepository(db)
        val core = coreRepository.create(persona.id, 1, "FALLBACK_BASELINE_CORE_CONTENT", "draft")
        val publishedCore = coreRepository.publishCoreVersion(core.id)
        personaRepository.activateCoreVersion(persona.id, publishedCore.id)

        // Real production Conversation Engine content -- the Fix B edit lives here.
        val engineRepository = ConversationEngineRepository(db)
        val engineRow = engineRepository.create(1, BaselineConfiguration.CONVERSATION_ENGINE, "draft")
        engineRepository.activateEngine(engineRepository.publishEngine(engineRow.id).id)

        // The authoritative, real fact: where the persona grew up.
        val masterRepo = PersonaMasterRepository(db)
        masterRepo.create(
            publishedCore.id,
            buildJsonObject { put("hometown", "AUTHORITATIVE_HOMETOWN_PUNE") },
        )
        val fieldRegistryRepo = PersonaFieldRegistryRepository(db)
        fieldRegistryRepo.create(
            PersonaFieldRegistryEntry(
                fieldId = "hometown", path = "hometown", semanticType = null, classification = "identity",
                sensitivity = "normal", stability = "stable", behavioralCore = false, defaultLoad = false,
                allowedContexts = null, prohibitedContexts = null, description = "hometown",
            ),
        )
        val skillReqRepo = SkillContextRequirementRepository(db)
        skillReqRepo.create(
            SkillContextRequirement(
                skillKey = "general_chat", requiredFields = listOf("hometown"),
                recommendedFields = null, conditionalFields = null, restrictedFields = null,
                purpose = null, whenToUse = null, whenNotToUse = null,
            ),
        )

        val conversationRepository = ConversationRepository(db)
        val conversation = conversationRepository.create(userId, persona.id)
        val messageRepository = MessageRepository(db)
        val executions = ChatRequestExecutionRepository(db)
        val memoryService = MemoryService(MemoryFactRepository(db))
        val persistence = RepositoryChatPersistence(db, executions)
        val coordinator = RepositoryChatExecutionCoordinator(executions, conversationRepository, messageRepository)

        val contextAssembler = RepositoryContextAssembler(
            conversationRepository, messageRepository, UserProfileRepository(db),
            memoryService, engineRepository, personaRepository,
        )
        val intentDiscovery = IntentDiscovery { _, _ -> SkillSelection.Selected("general_chat") }
        val authorizer = PersonaFieldAuthorizer(db, masterRepo, fieldRegistryRepo, skillReqRepo)
        val personaContextResolution = PersonaContextResolutionStage(ProductionPersonaContextService(authorizer, BareScalarRendering))

        fun newEngine(client: RecordingLlmClient) = PipelineChatEngine(
            entitlementChecker = { EntitlementDecision.Allowed },
            inputModerator = { ModerationDecision.Allowed },
            contextAssembler = contextAssembler,
            generator = LlmGenerator(client, GenerationConfig(model = "test-model")),
            outputValidator = { _, _ -> ValidationDecision.Accepted },
            persistence = persistence,
            delivery = { _, _ -> StageResult.Succeeded(Unit) },
            executionCoordinator = coordinator,
            intentDiscovery = intentDiscovery,
            personaContextResolution = personaContextResolution,
        )

        // Turn 1: reproduces the Phase 57 sequence's earlier turn -- the model
        // (here, the recording fake) improvises an incorrect hometown. This
        // reply is persisted to real conversation history exactly as production does.
        val turn1Client = RecordingLlmClient("I actually grew up in Mumbai, in a small apartment near the sea.")
        val turn1Result = newEngine(turn1Client).process(
            ChatRequest(UUID.randomUUID(), userId, conversation.id, persona.id, UUID.randomUUID(), "Where did you grow up?"),
        )
        assertIs<ChatResult.Success>(turn1Result)

        // Turn 2: same biographical question again. Authorized data is now
        // available (it always was, in this test -- Phase 57's real gap was
        // Failure A preventing skill selection on turn 1; here we isolate
        // Failure B by holding skill selection constant and inspecting only
        // what the SECOND turn's generation request actually contains).
        val turn2Client = RecordingLlmClient("final reply")
        val turn2Result = newEngine(turn2Client).process(
            ChatRequest(UUID.randomUUID(), userId, conversation.id, persona.id, UUID.randomUUID(), "Wait, where did you really grow up?"),
        )
        assertIs<ChatResult.Success>(turn2Result)

        val captured = turn2Client.lastRequest ?: error("Turn 2 did not reach the LlmClient")
        val blocks = captured.context.blocks

        // 1. The authoritative fact is present in the PERSONA block this turn.
        val personaBlock = blocks.first { it.blockType == ContextBlockType.PERSONA }
        assertTrue(personaBlock.content.contains("AUTHORITATIVE_HOMETOWN_PUNE"), "authorized fact must be present on the turn where it's available")

        // 2. The conflicting prior assistant turn is still present, verbatim, in
        // history -- confirming Phase 58's finding that history is unfiltered
        // is still true after Fix A/B (Option A was chosen specifically because
        // it does not require history filtering).
        val historyText = blocks.joinToString("\n") { it.content }
        assertTrue(historyText.contains("I actually grew up in Mumbai"), "prior conflicting turn must remain in history, unfiltered")

        // 3. The ENGINE block carries the Fix B precedence instruction telling
        // the model to prefer current Persona Core data over its own earlier turn.
        val engineBlock = blocks.first { it.blockType == ContextBlockType.ENGINE }
        // The prompt hard-wraps prose across lines, so a phrase spanning a wrap
        // point would never match a literal substring check -- collapse
        // whitespace runs first, matching how the text actually reads once sent.
        val normalizedEngineContent = engineBlock.content.replace(Regex("\\s+"), " ")
        assertTrue(
            normalizedEngineContent.contains("the Persona Core is authoritative"),
            "engine content must instruct the model to prefer current Persona Core data over an earlier self-contradicting turn",
        )
        assertTrue(
            normalizedEngineContent.contains("Treat the earlier statement as the assistant's own past error"),
            "engine content must explicitly frame the earlier conflicting turn as the assistant's own error, not a fact to defend",
        )
    }
}
