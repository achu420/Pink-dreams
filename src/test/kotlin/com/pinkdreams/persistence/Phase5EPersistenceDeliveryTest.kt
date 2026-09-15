package com.pinkdreams.persistence

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ChatResult
import com.pinkdreams.chat.ChatDelivery
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.EntitlementDecision
import com.pinkdreams.chat.GenerationResponse
import com.pinkdreams.chat.Generator
import com.pinkdreams.chat.InputModerator
import com.pinkdreams.chat.OutputValidator
import com.pinkdreams.chat.PipelineChatEngine
import com.pinkdreams.chat.StageResult
import com.pinkdreams.chat.ValidationDecision
import com.pinkdreams.common.errors.ErrorCode
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ChatRequestExecutionRepository
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class Phase5EPersistenceDeliveryTest {
    @Test
    fun `successful pipeline persists final exchange provenance and completes execution`() {
        val fixture = fixture()
        var delivered: String? = null
        val engine = fixture.engine(
            delivery = ChatDelivery { _, response ->
                delivered = response.content
                StageResult.Succeeded(Unit)
            },
        )

        val result = assertIs<ChatResult.Success>(engine.process(fixture.request))
        val messages = fixture.messages.findForConversation(fixture.request.conversationId)
        val userMessage = messages.single { it.role == "user" }
        val assistantMessage = messages.single { it.role == "assistant" }
        val execution = fixture.executions.find(fixture.request.conversationId, fixture.request.clientMessageId)

        assertEquals("final", result.response.content)
        assertEquals("final", delivered)
        assertEquals(fixture.request.content, userMessage.content)
        assertEquals(fixture.request.clientMessageId, userMessage.clientMessageId)
        assertEquals(fixture.request.requestId, userMessage.requestId)
        assertEquals(fixture.request.requestId, assistantMessage.requestId)
        assertEquals(fixture.engineId, assistantMessage.engineVersionId)
        assertEquals(fixture.coreId, assistantMessage.personaCoreVersionId)
        assertEquals("completed", execution?.status)
        assertEquals(assistantMessage.id, execution?.assistantMessageId)
    }

    @Test
    fun `completed duplicate reuses persisted result after delivery failure without regenerating`() {
        val fixture = fixture()
        var generationCalls = 0
        var deliveryCalls = 0
        val engine = fixture.engine(
            generator = Generator { _, _ ->
                generationCalls++
                StageResult.Succeeded(fixture.response)
            },
            delivery = ChatDelivery { _, _ ->
                deliveryCalls++
                if (deliveryCalls == 1) StageResult.Failed(ErrorCode.DELIVERY_FAILED) else StageResult.Succeeded(Unit)
            },
        )

        val first = assertIs<ChatResult.Failure>(engine.process(fixture.request))
        val second = assertIs<ChatResult.Success>(engine.process(fixture.request))

        assertEquals(ErrorCode.DELIVERY_FAILED, first.code)
        assertEquals("final", second.response.content)
        assertEquals(1, generationCalls)
        assertEquals(2, deliveryCalls)
        assertEquals(2, fixture.messages.findForConversation(fixture.request.conversationId).size)
        assertEquals(1L, fixture.executions.count())
    }

    @Test
    fun `persistence failure does not create messages or complete execution`() {
        val fixture = fixture()
        val engine = fixture.engine(
            generator = Generator { _, _ ->
                StageResult.Succeeded(fixture.response.copy(engineVersionId = null))
            },
        )

        val result = assertIs<ChatResult.Failure>(engine.process(fixture.request))
        val execution = fixture.executions.find(fixture.request.conversationId, fixture.request.clientMessageId)

        assertEquals(ErrorCode.PERSIST_FAILED, result.code)
        assertEquals(0, fixture.messages.findForConversation(fixture.request.conversationId).size)
        assertEquals("failed", execution?.status)
        assertFalse(execution?.assistantMessageId != null)
    }

    @Test
    fun `conversation ownership prevents persistence for another user`() {
        val fixture = fixture()
        val unauthorized = fixture.request.copy(userId = UUID.randomUUID())
        val engine = fixture.engine()

        val result = assertIs<ChatResult.Failure>(engine.process(unauthorized))

        assertEquals(ErrorCode.PERSIST_FAILED, result.code)
        assertEquals(0, fixture.messages.findForConversation(fixture.request.conversationId).size)
        assertEquals(null, fixture.executions.find(unauthorized.conversationId, unauthorized.clientMessageId))
    }

    private data class Fixture(
        val db: org.jetbrains.exposed.sql.Database,
        val request: ChatRequest,
        val response: GenerationResponse,
        val engineId: UUID,
        val coreId: UUID,
        val messages: MessageRepository,
        val executions: ChatRequestExecutionRepository,
        val coordinator: RepositoryChatExecutionCoordinator,
        val persistence: RepositoryChatPersistence,
    ) {
        fun engine(
            generator: Generator = Generator { _, _ -> StageResult.Succeeded(response) },
            delivery: ChatDelivery = ChatDelivery { _, _ -> StageResult.Succeeded(Unit) },
        ) = PipelineChatEngine(
            entitlementChecker = { EntitlementDecision.Allowed },
            inputModerator = InputModerator { com.pinkdreams.chat.ModerationDecision.Allowed },
            contextAssembler = { StageResult.Succeeded(ChatContext(listOf(ContextBlock("system", "context")), engineId, coreId)) },
            generator = generator,
            outputValidator = OutputValidator { _, _ -> ValidationDecision.Accepted },
            persistence = persistence,
            delivery = delivery,
            executionCoordinator = coordinator,
        )
    }

    private fun fixture(): Fixture {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val conversation = ConversationRepository(db).create(userId, personaId)
        val executions = ChatRequestExecutionRepository(db)
        val messages = MessageRepository(db)
        val coordinator = RepositoryChatExecutionCoordinator(executions, ConversationRepository(db), messages)
        val request = ChatRequest(UUID.randomUUID(), userId, conversation.id, personaId, UUID.randomUUID(), "hello")
        val engineId = UUID.randomUUID()
        val coreId = UUID.randomUUID()
        val response = GenerationResponse("final", engineId, coreId)
        return Fixture(
            db = db,
            request = request,
            response = response,
            engineId = engineId,
            coreId = coreId,
            messages = messages,
            executions = executions,
            coordinator = coordinator,
            persistence = RepositoryChatPersistence(db, executions),
        )
    }
}