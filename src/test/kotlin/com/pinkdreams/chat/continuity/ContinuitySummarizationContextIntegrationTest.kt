package com.pinkdreams.chat.continuity

import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ChatResult
import com.pinkdreams.chat.CompositePostDeliveryHook
import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.EntitlementDecision
import com.pinkdreams.chat.ModerationDecision
import com.pinkdreams.chat.PipelineChatEngine
import com.pinkdreams.chat.StageResult
import com.pinkdreams.chat.ValidationDecision
import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.chat.memory.BestEffortMemoryExtraction
import com.pinkdreams.chat.memory.ExtractionResult
import com.pinkdreams.chat.memory.MemoryExtractor
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
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Full-pipeline integration: real PipelineChatEngine, real
 * RepositoryContextAssembler, real BestEffortMemoryExtraction +
 * BestEffortContinuitySummarization composed via CompositePostDeliveryHook.
 * Only the LlmClient is fake. Uses a synchronous Executor so post-delivery
 * work completes before assertions run.
 */
class ContinuitySummarizationContextIntegrationTest {

    /** Routes based on which dedicated prompt was sent: continuity, memory extraction, or primary chat. */
    private class RoutingFakeLlmClient(
        private val chatReplies: MutableList<String>,
    ) : LlmClient {
        var continuityCalls = 0
        override fun generate(request: GenerationRequest): LlmResponse {
            val firstBlock = request.context.blocks.firstOrNull()?.content ?: ""
            return when {
                firstBlock.contains("CONTINUITY SUMMARY") -> {
                    continuityCalls++
                    LlmResponse(content = "user mentioned an early detail that scrolled out of the active window.", provider = "test")
                }
                firstBlock.contains("memory-extraction system") -> LlmResponse(content = """{"facts": []}""", provider = "test")
                else -> LlmResponse(content = chatReplies.removeAt(0), provider = "test")
            }
        }
    }

    private class Fixture(
        val userId: UUID,
        val personaId: UUID,
        val conversationId: UUID,
        val conversationRepository: ConversationRepository,
        val contextAssembler: RepositoryContextAssembler,
        val engine: PipelineChatEngine,
        val llmClient: RoutingFakeLlmClient,
    )

    private fun fixture(chatReplies: List<String>, messageLimit: Int = 10): Fixture {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val userId = UUID.randomUUID()
        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("continuity-int-persona-${UUID.randomUUID()}", "Test", "female", "straight", 30, emptyMap())
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

        val llmClient = RoutingFakeLlmClient(chatReplies.toMutableList())
        val generator = LlmGenerator(llmClient, GenerationConfig(model = "test-model"))
        val contextAssembler = RepositoryContextAssembler(
            conversationRepository,
            messageRepository,
            UserProfileRepository(db),
            memoryService,
            engineRepository,
            personaRepository,
            messageLimit = messageLimit,
        )

        val noopMemoryExtractor = MemoryExtractor { ExtractionResult(newFacts = emptyList()) }
        val memoryHook = BestEffortMemoryExtraction(noopMemoryExtractor, memoryService, executor = Executor { it.run() })
        val continuityHook = BestEffortContinuitySummarization(
            summarizer = LlmContinuitySummarizer(llmClient),
            conversationRepository = conversationRepository,
            messageRepository = messageRepository,
            activeWindowSize = messageLimit,
            executor = Executor { it.run() },
        )
        val postDelivery = CompositePostDeliveryHook(listOf(memoryHook, continuityHook))

        val engine = PipelineChatEngine(
            entitlementChecker = { EntitlementDecision.Allowed },
            inputModerator = { ModerationDecision.Allowed },
            contextAssembler = contextAssembler,
            generator = generator,
            outputValidator = { _, _ -> ValidationDecision.Accepted },
            persistence = persistence,
            delivery = { _, _ -> StageResult.Succeeded(Unit) },
            executionCoordinator = coordinator,
            postDeliveryMemoryExtraction = postDelivery,
        )

        return Fixture(userId, persona.id, conversation.id, conversationRepository, contextAssembler, engine, llmClient)
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

    private fun assembleNextContext(fixture: Fixture, content: String): ChatContext {
        val request = ChatRequest(
            requestId = UUID.randomUUID(),
            userId = fixture.userId,
            conversationId = fixture.conversationId,
            personaId = fixture.personaId,
            clientMessageId = UUID.randomUUID(),
            content = content,
        )
        return assertIs<StageResult.Succeeded<ChatContext>>(fixture.contextAssembler.assemble(request)).value
    }

    // --- A. Short conversation: existing native-history behavior is unchanged ---
    @Test
    fun `scenario A conversation within the window has no continuity block at all`() {
        val fixture = fixture(chatReplies = listOf("reply1", "reply2"), messageLimit = 10)

        assertIs<ChatResult.Success>(sendTurn(fixture, "Hello"))
        assertIs<ChatResult.Success>(sendTurn(fixture, "How are you"))

        assertEquals(0, fixture.llmClient.continuityCalls, "Continuity summarization must never run for a conversation inside the window")

        val context = assembleNextContext(fixture, "third message")
        // 3 system blocks (engine/persona, profile, memory) + 4 native history + 1 current = 8, no continuity block.
        assertEquals(8, context.blocks.size)
        assertTrue(context.blocks.none { it.content.contains("EARLIER CONVERSATION CONTEXT") }, "No continuity block may appear for a short conversation")
    }

    // --- B/C/G. Long conversation: continuity block appears, ordering is correct, native history + current preserved ---
    @Test
    fun `scenario B C and G exceeding the window produces a correctly ordered continuity block alongside native recent history`() {
        val fixture = fixture(chatReplies = (1..7).map { "reply-$it" }, messageLimit = 10)

        // 6 turns = 12 persisted messages > window of 10, so 2 messages will have scrolled out
        // by the time we assemble context for a 7th turn.
        repeat(6) { i -> assertIs<ChatResult.Success>(sendTurn(fixture, "user-turn-$i")) }

        assertTrue(fixture.llmClient.continuityCalls > 0, "Continuity summarization must have run at least once")

        val context = assembleNextContext(fixture, "current turn")
        val roles = context.blocks.map { it.role }

        // Expected shape: system(engine/persona), system(profile), system(memory),
        // system(continuity), native history (role-preserved), user(current, final).
        assertEquals("system", roles[0])
        assertEquals("system", roles[1])
        assertEquals("system", roles[2])
        assertEquals("system", roles[3])
        assertTrue(context.blocks[3].content.contains("EARLIER CONVERSATION CONTEXT"), "4th block must be the continuity summary")
        assertTrue(context.blocks[3].content.contains("scrolled out of the active window"))

        val historyAndCurrentRoles = roles.subList(4, roles.size)
        assertTrue(historyAndCurrentRoles.dropLast(1).all { it == "user" || it == "assistant" }, "History blocks must remain native user/assistant roles")
        assertEquals("user", roles.last())
        assertEquals("current turn", context.blocks.last().content)

        // Native history window is bounded to the configured limit (10).
        assertEquals(10, historyAndCurrentRoles.size - 1, "Native history must still be capped at the configured window size")
    }

    // --- D. Recent context wins: ordering places continuity before native history, and native history carries the newer fact ---
    @Test
    fun `scenario D older continuity information is superseded in position and content by recent native messages`() {
        val fixture = fixture(chatReplies = (1..8).map { "reply-$it" }, messageLimit = 10)

        // Force an initial continuity summary asserting an outdated fact.
        fixture.conversationRepository.updateContinuitySummary(fixture.conversationId, "user works at Company A", 2)

        // Push enough turns so this stale summary remains (no new messages scroll out yet
        // beyond what's already covered) while a NEW, contradicting fact is stated recently.
        repeat(3) { i -> assertIs<ChatResult.Success>(sendTurn(fixture, "filler-$i")) }
        assertIs<ChatResult.Success>(sendTurn(fixture, "Actually, I now work at Company B."))

        val context = assembleNextContext(fixture, "what do you know about my job?")
        val continuityIndex = context.blocks.indexOfFirst { it.content.contains("EARLIER CONVERSATION CONTEXT") }
        val newerFactIndex = context.blocks.indexOfFirst { it.content.contains("Company B") }

        assertTrue(continuityIndex in 0 until newerFactIndex, "Continuity (older) must be positioned strictly before the newer native message that supersedes it")
        assertTrue(context.blocks[continuityIndex].content.contains("Company A"), "Continuity block still carries the old text verbatim (ordering, not content-rewriting, establishes authority)")
        assertTrue(
            context.blocks[continuityIndex].content.contains("authoritative if it conflicts"),
            "Continuity block must explicitly declare itself lower-authority than more recent messages",
        )
    }

    // --- Token budget: continuity is dropped before history when over budget ---
    @Test
    fun `continuity block is trimmed before native history when the token budget is exceeded`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val userId = UUID.randomUUID()
        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("budget-persona-${UUID.randomUUID()}", "Test", "female", "straight", 30, emptyMap())
        val coreRepository = PersonaCoreVersionRepository(db)
        val core = coreRepository.create(persona.id, 1, "core", "draft")
        val publishedCore = coreRepository.publishCoreVersion(core.id)
        personaRepository.activateCoreVersion(persona.id, publishedCore.id)
        val engineRepository = ConversationEngineRepository(db)
        val engineRow = engineRepository.create(1, "engine", "draft")
        val publishedEngine = engineRepository.publishEngine(engineRow.id)
        engineRepository.activateEngine(publishedEngine.id)
        val conversationRepository = ConversationRepository(db)
        val conversation = conversationRepository.create(userId, persona.id)
        val messageRepository = MessageRepository(db)

        conversationRepository.updateContinuitySummary(conversation.id, "x".repeat(400), 2)
        messageRepository.createUserMessage(conversation.id, "recent message", UUID.randomUUID(), UUID.randomUUID())

        // Budget comfortably fits engine+persona+profile+memory+one short history message,
        // but not the 400-char continuity block on top of it.
        val assembler = RepositoryContextAssembler(
            conversationRepository,
            messageRepository,
            UserProfileRepository(db),
            MemoryService(MemoryFactRepository(db)),
            engineRepository,
            personaRepository,
            tokenBudget = 100,
        )
        val request = ChatRequest(UUID.randomUUID(), userId, conversation.id, persona.id, UUID.randomUUID(), "current")
        val context = assertIs<StageResult.Succeeded<ChatContext>>(assembler.assemble(request)).value

        assertTrue(context.blocks.none { it.content.contains("EARLIER CONVERSATION CONTEXT") }, "Continuity must be dropped under budget pressure")
        assertTrue(context.blocks.any { it.content == "recent message" }, "Native history must survive when only continuity needs to be dropped to fit budget")
    }
}
