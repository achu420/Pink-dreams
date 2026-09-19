package com.pinkdreams.chat

import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.chat.memory.MemoryService
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
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Proves role/provenance survives the COMPLETE round trip across multiple real
 * chat turns using the actual production pipeline — not a hand-built ChatContext:
 *
 *   LLM response -> RepositoryChatPersistence -> MessageRepository ->
 *   RepositoryContextAssembler (next turn) -> LlmGenerator -> LlmClient
 *
 * Prior tests (ChatContextContractRegressionTest, LlmClientBoundaryAuditTest)
 * proved role handling given manually-inserted history rows. This test proves
 * the rows themselves are written correctly by a real assistant turn, and that
 * an assistant's own words never resurface as a "user" turn in a later request.
 */
class ConversationProvenanceRoundTripTest {

    private class SequencedRecordingLlmClient(
        private val responses: MutableList<LlmResponse>,
    ) : LlmClient {
        val capturedRequests = mutableListOf<GenerationRequest>()
        override fun generate(request: GenerationRequest): LlmResponse {
            capturedRequests += request
            return responses.removeAt(0)
        }
    }

    private class Fixture(
        val db: org.jetbrains.exposed.sql.Database,
        val userId: UUID,
        val personaId: UUID,
        val conversationId: UUID,
        val messages: MessageRepository,
        val engine: PipelineChatEngine,
        val llmClient: SequencedRecordingLlmClient,
    )

    private fun fixture(vararg assistantReplies: String): Fixture {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userId = UUID.randomUUID()
        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("provenance-persona-${UUID.randomUUID()}", "Test", "female", "straight", 30, emptyMap())
        val coreRepository = PersonaCoreVersionRepository(db)
        val core = coreRepository.create(persona.id, 1, "core content", "draft")
        val publishedCore = coreRepository.publishCoreVersion(core.id)
        personaRepository.activateCoreVersion(persona.id, publishedCore.id)

        val engineRepository = ConversationEngineRepository(db)
        val engineRow = engineRepository.create(1, "engine rules", "draft")
        val publishedEngine = engineRepository.publishEngine(engineRow.id)
        engineRepository.activateEngine(publishedEngine.id)

        val conversationRepository = ConversationRepository(db)
        val conversation = conversationRepository.create(userId, persona.id)

        val messageRepository = MessageRepository(db)
        val executions = ChatRequestExecutionRepository(db)
        val memoryService = MemoryService(MemoryFactRepository(db))
        val persistence = RepositoryChatPersistence(db, executions)
        val coordinator = RepositoryChatExecutionCoordinator(executions, conversationRepository, messageRepository)

        val llmClient = SequencedRecordingLlmClient(
            assistantReplies.map { LlmResponse(content = it, provider = "test", model = "test-model") }.toMutableList(),
        )
        val generator = LlmGenerator(llmClient, GenerationConfig(model = "test-model"))
        val contextAssembler = RepositoryContextAssembler(
            conversationRepository,
            messageRepository,
            UserProfileRepository(db),
            memoryService,
            engineRepository,
            personaRepository,
        )

        val engine = PipelineChatEngine(
            entitlementChecker = { EntitlementDecision.Allowed },
            inputModerator = { ModerationDecision.Allowed },
            contextAssembler = contextAssembler,
            generator = generator,
            outputValidator = { _, _ -> ValidationDecision.Accepted },
            persistence = persistence,
            delivery = { _, _ -> StageResult.Succeeded(Unit) },
            executionCoordinator = coordinator,
        )

        return Fixture(db, userId, persona.id, conversation.id, messageRepository, engine, llmClient)
    }

    private fun sendTurn(fixture: Fixture, content: String): ChatResult {
        val request = ChatRequest(
            requestId = UUID.randomUUID(),
            userId = fixture.userId,
            conversationId = fixture.conversationId,
            personaId = fixture.personaId,
            clientMessageId = UUID.randomUUID(),
            content = content,
        )
        return fixture.engine.process(request)
    }

    @Test
    fun `assistant reply persisted with role assistant survives round trip and never becomes user in next turn`() {
        val fixture = fixture(
            "I'll remember that blue is your favorite.",
            "Understood.",
        )

        // Turn 1: user states a fact; assistant replies.
        val turn1Result = assertIs<ChatResult.Success>(sendTurn(fixture, "My favorite color is blue."))
        assertEquals("I'll remember that blue is your favorite.", turn1Result.response.content)

        // Round-trip proof #1: what was ACTUALLY persisted after a real assistant turn.
        val afterTurn1 = fixture.messages.findForConversation(fixture.conversationId)
        assertEquals(2, afterTurn1.size)
        val persistedUserMsg = afterTurn1.single { it.content == "My favorite color is blue." }
        val persistedAssistantMsg = afterTurn1.single { it.content == "I'll remember that blue is your favorite." }
        assertEquals("user", persistedUserMsg.role, "The original request must be persisted with role=user")
        assertEquals("assistant", persistedAssistantMsg.role, "The LLM's reply must be persisted with role=assistant")

        // Turn 2: user asks a follow-up; this triggers context assembly that must
        // retrieve turn 1's rows and preserve their roles into the next LLM request.
        val turn3Result = assertIs<ChatResult.Success>(sendTurn(fixture, "What did you just say?"))
        assertEquals("Understood.", turn3Result.response.content)

        // Inspect the ACTUAL request handed to the LlmClient for turn 2 (index 1).
        // History must arrive as NATIVE per-message user/assistant blocks, never a
        // flattened "[role] content" transcript wrapped in a single system block.
        val turn2Request = fixture.llmClient.capturedRequests[1]
        val blocks = turn2Request.context.blocks
        assertEquals(6, blocks.size, "3 system blocks + 2 native history messages + 1 current message")
        assertEquals(listOf("system", "system", "system", "user", "assistant", "user"), blocks.map { it.role })

        val turn1UserBlock = blocks[3]
        val turn1AssistantBlock = blocks[4]
        val currentBlock = blocks[5]

        assertEquals("My favorite color is blue.", turn1UserBlock.content, "Turn 1 must appear in history as a native user message")
        assertEquals("I'll remember that blue is your favorite.", turn1AssistantBlock.content, "The assistant's own words must appear in history as a native assistant message")
        assertEquals("assistant", turn1AssistantBlock.role, "The assistant's own prior statement must NEVER be re-tagged as user")

        assertFalse(
            blocks.subList(0, 5).any { it.content == "What did you just say?" },
            "Current message must not appear among history blocks",
        )
        assertEquals("user", currentBlock.role)
        assertEquals("What did you just say?", currentBlock.content)
    }

    @Test
    fun `assistant recommendation never resurfaces as user statement in later history`() {
        val fixture = fixture(
            "You might enjoy Paris in the spring.",
            "I recommended visiting Paris in the spring.",
        )

        assertIs<ChatResult.Success>(sendTurn(fixture, "I have never visited Paris."))
        assertIs<ChatResult.Success>(sendTurn(fixture, "What did you recommend?"))

        val turn2Request = fixture.llmClient.capturedRequests[1]
        val blocks = turn2Request.context.blocks
        assertEquals(6, blocks.size, "3 system blocks + 2 native history messages + 1 current message")
        assertEquals(listOf("system", "system", "system", "user", "assistant", "user"), blocks.map { it.role })

        val userStatementBlock = blocks[3]
        val assistantReplyBlock = blocks[4]
        val currentBlock = blocks[5]

        // Exact expected chronological content, roles preserved verbatim from persistence,
        // as native per-message blocks — never a flattened "[role] content" transcript.
        assertEquals("I have never visited Paris.", userStatementBlock.content)
        assertEquals("user", userStatementBlock.role)
        assertEquals("You might enjoy Paris in the spring.", assistantReplyBlock.content)
        assertEquals("assistant", assistantReplyBlock.role, "The assistant's recommendation must never appear attributed to the user")

        // Chronological order preserved: user statement's block index precedes the assistant reply's.
        assertTrue(blocks.indexOf(userStatementBlock) < blocks.indexOf(assistantReplyBlock), "User statement must precede the assistant's reply chronologically")

        assertEquals("user", currentBlock.role)
        assertEquals("What did you recommend?", currentBlock.content)

        // Final round-trip check: the persisted rows themselves, independent of ChatContext.
        val allMessages = fixture.messages.findForConversation(fixture.conversationId)
        val recommendation = allMessages.single { it.content == "You might enjoy Paris in the spring." }
        assertEquals("assistant", recommendation.role, "Persisted role for the assistant's own generated content must remain assistant")
    }

    @Test
    fun `four turn conversation preserves exact role sequence end to end`() {
        val fixture = fixture(
            "I'll remember that blue is your favorite.",
            "You said your favorite color is blue.",
        )

        assertIs<ChatResult.Success>(sendTurn(fixture, "My favorite color is blue."))
        assertIs<ChatResult.Success>(sendTurn(fixture, "What did you just say?"))

        val allMessages = fixture.messages.findForConversation(fixture.conversationId)
        assertEquals(4, allMessages.size)

        val expectedSequence = listOf(
            "user" to "My favorite color is blue.",
            "assistant" to "I'll remember that blue is your favorite.",
            "user" to "What did you just say?",
            "assistant" to "You said your favorite color is blue.",
        )
        expectedSequence.forEachIndexed { index, (expectedRole, expectedContent) ->
            assertEquals(expectedRole, allMessages[index].role, "Turn ${index + 1} role mismatch")
            assertEquals(expectedContent, allMessages[index].content, "Turn ${index + 1} content mismatch")
        }
    }

    @Test
    fun `many sequential turns always retrieve user before its own assistant reply`() {
        // Regression guard for the shared-timestamp ordering bug: user and assistant
        // messages within one turn previously received an identical createdAt, so
        // findForConversation's (createdAt, id) tiebreak on a random UUID could sort
        // the assistant's reply before the user message that prompted it. Repeating
        // across many turns makes a reintroduced bug fail near-certainly rather than
        // ~50% of the time.
        val turnCount = 15
        val replies = (1..turnCount).map { "reply-$it" }
        val fixture = fixture(*replies.toTypedArray())

        (1..turnCount).forEach { i ->
            assertIs<ChatResult.Success>(sendTurn(fixture, "message-$i"))
        }

        val allMessages = fixture.messages.findForConversation(fixture.conversationId)
        assertEquals(turnCount * 2, allMessages.size)

        (0 until turnCount).forEach { i ->
            val userMsg = allMessages[i * 2]
            val assistantMsg = allMessages[i * 2 + 1]
            assertEquals("user", userMsg.role, "Position ${i * 2} must be the user turn")
            assertEquals("message-${i + 1}", userMsg.content)
            assertEquals("assistant", assistantMsg.role, "Position ${i * 2 + 1} must be the assistant turn")
            assertEquals("reply-${i + 1}", assistantMsg.content)
            assertTrue(
                userMsg.createdAt <= assistantMsg.createdAt,
                "User message must not be timestamped after its own assistant reply",
            )
        }
    }
}
