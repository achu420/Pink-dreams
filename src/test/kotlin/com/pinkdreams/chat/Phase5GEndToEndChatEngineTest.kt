package com.pinkdreams.chat

import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.chat.memory.BestEffortMemoryExtraction
import com.pinkdreams.chat.memory.CompletedTurn
import com.pinkdreams.chat.memory.ExtractionResult
import com.pinkdreams.chat.memory.MemoryCandidate
import com.pinkdreams.chat.memory.MemoryExtractor
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.chat.memory.PostDeliveryMemoryExtraction
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
import com.pinkdreams.common.errors.ErrorCode
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Phase5GEndToEndChatEngineTest {
    @Test
    fun `happy path proves claim order final persistence delivery extraction and provenance`() {
        val fixture = fixture()
        val events = mutableListOf<String>()
        var extracted: CompletedTurn? = null
        val engine = fixture.engine(
            events = events,
            extraction = BestEffortMemoryExtraction(
                extractor = MemoryExtractor { turn ->
                    events += "extract"
                    extracted = turn
                    ExtractionResult(listOf(MemoryCandidate("likes tea", "interest", "high")))
                },
                memoryService = fixture.memoryService,
                executor = Executor { it.run() },
            ),
        )

        val result = assertIs<ChatResult.Success>(engine.process(fixture.request))
        val messages = fixture.messages.findForConversation(fixture.request.conversationId)
        val assistant = messages.single { it.role == "assistant" }
        val facts = fixture.memoryService.findForRelationship(fixture.userId, fixture.personaId)

        assertEquals(
            listOf("claim", "entitlement", "moderation", "context", "generation", "validation", "persist", "deliver", "extract"),
            events,
        )
        assertEquals("final", result.response.content)
        assertEquals(fixture.engineId, assistant.engineVersionId)
        assertEquals(fixture.coreId, assistant.personaCoreVersionId)
        assertEquals("final", extracted?.response?.content)
        assertEquals("llm_extracted", facts.single().source)
        assertEquals(fixture.personaId, facts.single().personaId)
    }

    @Test
    fun `same request generates and extracts once while completed result is reused`() {
        val fixture = fixture()
        var generationCalls = 0
        var validationCalls = 0
        var persistenceCalls = 0
        var deliveryCalls = 0
        var extractionCalls = 0
        val engine = fixture.engine(
            generator = Generator { _, context ->
                generationCalls++
                StageResult.Succeeded(GenerationResponse("final", context.engineVersionId, context.personaCoreVersionId))
            },
            validator = OutputValidator { _, _ -> validationCalls++; ValidationDecision.Accepted },
            persistence = ChatPersistence { request, response ->
                persistenceCalls++
                fixture.persistence.persist(request, response)
            },
            delivery = ChatDelivery { request, response ->
                deliveryCalls++
                fixture.delivery.deliver(request, response)
            },
            extraction = PostDeliveryMemoryExtraction { extractionCalls++ },
        )

        assertIs<ChatResult.Success>(engine.process(fixture.request))
        val duplicate = assertIs<ChatResult.Success>(engine.process(fixture.request))

        assertEquals("final", duplicate.response.content)
        assertEquals(1, generationCalls)
        assertEquals(1, validationCalls)
        assertEquals(1, persistenceCalls)
        assertEquals(2, deliveryCalls)
        assertEquals(1, extractionCalls)
        assertEquals(2, fixture.messages.findForConversation(fixture.request.conversationId).size)
    }

    @Test
    fun `processing duplicate stops before generation validation persistence delivery and extraction`() {
        val fixture = fixture()
        fixture.executions.claim(fixture.request.conversationId, fixture.request.clientMessageId, fixture.request.requestId)
        val events = mutableListOf<String>()
        val engine = fixture.engine(
            events = events,
            generator = Generator { _, _ ->
                events += "generation"
                StageResult.Succeeded(GenerationResponse("final", fixture.engineId, fixture.coreId))
            },
            validator = OutputValidator { _, _ -> events += "validation"; ValidationDecision.Accepted },
            extraction = PostDeliveryMemoryExtraction { events += "extract" },
        )

        val result = assertIs<ChatResult.InProgress>(engine.process(fixture.request))

        assertEquals(listOf("claim"), events)
        assertEquals(PipelineStage.RECEIVED, result.state.currentStage)
        assertEquals(0, fixture.messages.findForConversation(fixture.request.conversationId).size)
    }

    @Test
    fun `failed request can retry and complete one exchange`() {
        val fixture = fixture()
        var attempts = 0
        val engine = fixture.engine(
            generator = Generator { _, context ->
                attempts++
                if (attempts == 1) StageResult.Failed(ErrorCode.GENERATION_FAILED)
                else StageResult.Succeeded(GenerationResponse("retry-final", context.engineVersionId, context.personaCoreVersionId))
            },
        )

        assertEquals(ErrorCode.GENERATION_FAILED, assertIs<ChatResult.Failure>(engine.process(fixture.request)).code)
        val result = assertIs<ChatResult.Success>(engine.process(fixture.request))

        assertEquals("retry-final", result.response.content)
        assertEquals(2, attempts)
        assertEquals(2, fixture.messages.findForConversation(fixture.request.conversationId).size)
    }

    @Test
    fun `invalid then valid generation persists delivers and extracts only final output`() {
        val fixture = fixture()
        val seen = mutableListOf<String>()
        var extractionContent: String? = null
        var attempts = 0
        val engine = fixture.engine(
            events = seen,
            generator = Generator { _, context ->
                attempts++
                val content = if (attempts == 1) "invalid" else "final"
                StageResult.Succeeded(GenerationResponse(content, context.engineVersionId, context.personaCoreVersionId))
            },
            validator = OutputValidator { _, response ->
                seen += "validation:$${response.content}"
                if (response.content == "invalid") ValidationDecision.Rejected("test") else ValidationDecision.Accepted
            },
            extraction = PostDeliveryMemoryExtraction { turn ->
                seen += "extract"
                extractionContent = turn.response.content
            },
        )

        val result = assertIs<ChatResult.Success>(engine.process(fixture.request))
        val messages = fixture.messages.findForConversation(fixture.request.conversationId)

        assertEquals("final", result.response.content)
        assertEquals(2, attempts)
        assertEquals("final", messages.single { it.role == "assistant" }.content)
        assertEquals("final", extractionContent)
        assertFalse(seen.contains("persist:invalid"))
        assertFalse(seen.contains("deliver:invalid"))
    }

    @Test
    fun `validation failure twice and generation failure do not persist deliver or extract`() {
        val validationFixture = fixture()
        var validationGenerations = 0
        var validationExtraction = false
        val invalidEngine = validationFixture.engine(
            generator = Generator { _, context ->
                validationGenerations++
                StageResult.Succeeded(GenerationResponse("invalid", context.engineVersionId, context.personaCoreVersionId))
            },
            validator = OutputValidator { _, _ -> ValidationDecision.Rejected("always") },
            extraction = PostDeliveryMemoryExtraction { validationExtraction = true },
        )
        val invalidResult = assertIs<ChatResult.Failure>(invalidEngine.process(validationFixture.request))

        assertEquals(ErrorCode.VALIDATION_FAILED, invalidResult.code)
        assertEquals(2, validationGenerations)
        assertFalse(validationExtraction)
        assertEquals(0, validationFixture.messages.findForConversation(validationFixture.request.conversationId).size)

        val generationFixture = fixture()
        var generationExtraction = false
        val generationEngine = generationFixture.engine(
            generator = Generator { _, _ -> StageResult.Failed(ErrorCode.GENERATION_FAILED) },
            extraction = PostDeliveryMemoryExtraction { generationExtraction = true },
        )
        val generationResult = assertIs<ChatResult.Failure>(generationEngine.process(generationFixture.request))

        assertEquals(ErrorCode.GENERATION_FAILED, generationResult.code)
        assertFalse(generationExtraction)
        assertEquals(0, generationFixture.messages.findForConversation(generationFixture.request.conversationId).size)
    }

    @Test
    fun `persistence failure prevents delivery and extraction`() {
        val fixture = fixture()
        var deliveryCalled = false
        var extractionCalled = false
        val engine = fixture.engine(
            persistence = ChatPersistence { _, _ -> StageResult.Failed(ErrorCode.PERSIST_FAILED) },
            delivery = ChatDelivery { _, _ ->
                deliveryCalled = true
                StageResult.Succeeded(Unit)
            },
            extraction = PostDeliveryMemoryExtraction { extractionCalled = true },
        )

        val result = assertIs<ChatResult.Failure>(engine.process(fixture.request))

        assertEquals(ErrorCode.PERSIST_FAILED, result.code)
        assertFalse(deliveryCalled)
        assertFalse(extractionCalled)
        assertEquals(0, fixture.messages.findForConversation(fixture.request.conversationId).size)
    }

    @Test
    fun `delivery failure leaves persisted exchange and prevents extraction`() {
        val fixture = fixture()
        var extractionCalled = false
        val engine = fixture.engine(
            delivery = ChatDelivery { _, _ -> StageResult.Failed(ErrorCode.DELIVERY_FAILED) },
            extraction = PostDeliveryMemoryExtraction { extractionCalled = true },
        )

        val result = assertIs<ChatResult.Failure>(engine.process(fixture.request))

        assertEquals(ErrorCode.DELIVERY_FAILED, result.code)
        assertFalse(extractionCalled)
        assertEquals(2, fixture.messages.findForConversation(fixture.request.conversationId).size)
        assertEquals("completed", fixture.executions.find(fixture.request.conversationId, fixture.request.clientMessageId)?.status)
    }

    @Test
    fun `extraction failure leaves successful result and persisted exchange unchanged`() {
        val fixture = fixture()
        val extraction = BestEffortMemoryExtraction(
            extractor = MemoryExtractor { throw IllegalStateException("extraction failed") },
            memoryService = fixture.memoryService,
            executor = Executor { it.run() },
        )
        val result = assertIs<ChatResult.Success>(fixture.engine(extraction = extraction).process(fixture.request))

        assertEquals("final", result.response.content)
        assertEquals(2, fixture.messages.findForConversation(fixture.request.conversationId).size)
        assertEquals("completed", fixture.executions.find(fixture.request.conversationId, fixture.request.clientMessageId)?.status)
    }

    @Test
    fun `entitlement and moderation failures stop before context and generation`() {
        val entitlementFixture = fixture()
        val entitlementEvents = mutableListOf<String>()
        val entitlementEngine = entitlementFixture.engine(
            events = entitlementEvents,
            entitlement = { entitlementEvents += "entitlement"; EntitlementDecision.Denied },
        )
        assertEquals(ErrorCode.ENTITLEMENT_DENIED, assertIs<ChatResult.Failure>(entitlementEngine.process(entitlementFixture.request)).code)
        assertEquals(listOf("claim", "entitlement"), entitlementEvents)

        val moderationFixture = fixture()
        val moderationEvents = mutableListOf<String>()
        val moderationEngine = moderationFixture.engine(
            events = moderationEvents,
            moderation = { moderationEvents += "moderation"; ModerationDecision.Blocked },
        )
        assertEquals(ErrorCode.MODERATION_BLOCKED, assertIs<ChatResult.Failure>(moderationEngine.process(moderationFixture.request)).code)
        assertEquals(listOf("claim", "entitlement", "moderation"), moderationEvents)
    }

    @Test
    fun `same universal engine supports two persona IDs with exact core provenance`() {
        val first = fixture()
        val second = fixture()
        val firstResult = assertIs<ChatResult.Success>(first.engine().process(first.request))
        val secondResult = assertIs<ChatResult.Success>(second.engine().process(second.request))
        val firstMessage = first.messages.findForConversation(first.request.conversationId).single { it.role == "assistant" }
        val secondMessage = second.messages.findForConversation(second.request.conversationId).single { it.role == "assistant" }

        assertTrue(first.personaId != second.personaId)
        assertEquals(first.engineId, firstMessage.engineVersionId)
        assertEquals(second.engineId, secondMessage.engineVersionId)
        assertEquals(first.coreId, firstMessage.personaCoreVersionId)
        assertEquals(second.coreId, secondMessage.personaCoreVersionId)
        assertEquals("final", firstResult.response.content)
        assertEquals("final", secondResult.response.content)
    }

    private data class Fixture(
        val db: org.jetbrains.exposed.sql.Database,
        val userId: UUID,
        val personaId: UUID,
        val request: ChatRequest,
        val engineId: UUID,
        val coreId: UUID,
        val messages: MessageRepository,
        val executions: ChatRequestExecutionRepository,
        val memoryService: MemoryService,
        val persistence: RepositoryChatPersistence,
        val coordinator: RepositoryChatExecutionCoordinator,
        val delivery: ChatDelivery,
    ) {
        fun engine(
            events: MutableList<String> = mutableListOf(),
            entitlement: (ChatRequest) -> EntitlementDecision = { events += "entitlement"; EntitlementDecision.Allowed },
            moderation: (ChatRequest) -> ModerationDecision = { events += "moderation"; ModerationDecision.Allowed },
            generator: Generator = Generator { _, context ->
                events += "generation"
                StageResult.Succeeded(GenerationResponse("final", context.engineVersionId, context.personaCoreVersionId))
            },
            validator: OutputValidator = OutputValidator { _, _ -> events += "validation"; ValidationDecision.Accepted },
            persistence: ChatPersistence = ChatPersistence { request, response ->
                events += "persist"
                this.persistence.persist(request, response)
            },
            delivery: ChatDelivery = ChatDelivery { request, response ->
                events += "deliver"
                this.delivery.deliver(request, response)
            },
            extraction: PostDeliveryMemoryExtraction = PostDeliveryMemoryExtraction { events += "extract" },
        ) = PipelineChatEngine(
            entitlementChecker = entitlement,
            inputModerator = moderation,
            contextAssembler = { events += "context"; RepositoryContextAssembler(
                ConversationRepository(db),
                messages,
                UserProfileRepository(db),
                memoryService,
                activeEngineRepository,
                PersonaRepository(db),
            ).assemble(it) },
            generator = generator,
            outputValidator = validator,
            persistence = persistence,
            delivery = delivery,
            executionCoordinator = RecordingCoordinator(coordinator, events),
            postDeliveryMemoryExtraction = extraction,
        )

        private val activeEngineRepository: ConversationEngineRepository
            get() = engineRepository

        lateinit var engineRepository: ConversationEngineRepository
    }

    private class RecordingCoordinator(
        private val delegate: RepositoryChatExecutionCoordinator,
        private val events: MutableList<String>,
    ) : ChatExecutionCoordinator {
        override fun claim(request: ChatRequest): StageResult<ExecutionClaim> {
            events += "claim"
            return delegate.claim(request)
        }

        override fun fail(request: ChatRequest, code: ErrorCode) = delegate.fail(request, code)
    }

    private fun fixture(): Fixture {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val userId = UUID.randomUUID()
        val personaId = UUID.randomUUID()
        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("persona-${UUID.randomUUID()}", "Fixture", "female", "straight", 30, emptyMap())
        val coreRepository = PersonaCoreVersionRepository(db)
        val core = coreRepository.create(persona.id, 1, "core", "draft")
        val publishedCore = coreRepository.publishCoreVersion(core.id)
        personaRepository.activateCoreVersion(persona.id, publishedCore.id)
        val engineRepository = ConversationEngineRepository(db)
        val engine = engineRepository.create(1, "engine", "draft")
        val publishedEngine = engineRepository.publishEngine(engine.id)
        engineRepository.activateEngine(publishedEngine.id)
        val conversation = ConversationRepository(db).create(userId, persona.id)
        UserProfileRepository(db).create(userId, "User", "en", "warm")
        val messages = MessageRepository(db)
        val executions = ChatRequestExecutionRepository(db)
        val memoryService = MemoryService(MemoryFactRepository(db))
        val persistence = RepositoryChatPersistence(db, executions)
        val coordinator = RepositoryChatExecutionCoordinator(executions, ConversationRepository(db), messages)
        val request = ChatRequest(UUID.randomUUID(), userId, conversation.id, persona.id, UUID.randomUUID(), "hello")
        return Fixture(
            db, userId, persona.id, request, publishedEngine.id, publishedCore.id,
            messages, executions, memoryService, persistence, coordinator,
            ChatDelivery { _, _ -> StageResult.Succeeded(Unit) },
        ).also { it.engineRepository = engineRepository }
    }
}