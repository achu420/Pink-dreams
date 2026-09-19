package com.pinkdreams.chat.continuity

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.PersistedResponse
import com.pinkdreams.chat.memory.CompletedTurn
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BestEffortContinuitySummarizationTest {

    private class Fixture(
        val conversationRepository: ConversationRepository,
        val messageRepository: MessageRepository,
        val conversationId: UUID,
        val userId: UUID,
        val personaId: UUID,
    )

    private fun fixture(): Fixture {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val userId = UUID.randomUUID()
        val personaRepository = PersonaRepository(db)
        val persona = personaRepository.create("continuity-persona-${UUID.randomUUID()}", "Test", "female", "straight", 30, emptyMap())
        val coreRepository = PersonaCoreVersionRepository(db)
        val core = coreRepository.create(persona.id, 1, "core", "draft")
        val publishedCore = coreRepository.publishCoreVersion(core.id)
        personaRepository.activateCoreVersion(persona.id, publishedCore.id)
        val conversationRepository = ConversationRepository(db)
        val conversation = conversationRepository.create(userId, persona.id)
        return Fixture(conversationRepository, MessageRepository(db), conversation.id, userId, persona.id)
    }

    private fun completedTurn(fixture: Fixture, content: String = "current"): CompletedTurn {
        val request = ChatRequest(
            requestId = UUID.randomUUID(),
            userId = fixture.userId,
            conversationId = fixture.conversationId,
            personaId = fixture.personaId,
            clientMessageId = UUID.randomUUID(),
            content = content,
        )
        val context = ChatContext(
            blocks = listOf(ContextBlock("system", "rules")),
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID(),
        )
        return CompletedTurn(request, context, PersistedResponse(UUID.randomUUID(), "reply"))
    }

    private fun seedMessages(fixture: Fixture, count: Int) {
        val base = LocalDateTime.of(2026, 1, 1, 0, 0)
        repeat(count) { i ->
            fixture.messageRepository.createUserMessage(
                fixture.conversationId, "message-$i", UUID.randomUUID(), UUID.randomUUID(),
                createdAt = base.plusMinutes(i.toLong()),
            )
        }
    }

    // --- A. Short conversation: no work done at all ---
    @Test
    fun `scenario A conversation within the active window triggers no summarization work`() {
        val fixture = fixture()
        seedMessages(fixture, 6) // window size 10, well under
        var summarizerCalls = 0
        val summarization = BestEffortContinuitySummarization(
            summarizer = { _, _, _ -> summarizerCalls++; "should not be used" },
            conversationRepository = fixture.conversationRepository,
            messageRepository = fixture.messageRepository,
            activeWindowSize = 10,
            executor = Executor { it.run() },
        )

        summarization.dispatch(completedTurn(fixture))

        assertEquals(0, summarizerCalls, "Summarizer must never be invoked while the conversation fits inside the active window")
        val conversation = fixture.conversationRepository.findById(fixture.conversationId)!!
        assertNull(conversation.continuitySummary)
        assertEquals(0, conversation.continuitySummaryCoveredCount)
    }

    // --- B/C. Long conversation folds in exactly the newly out-of-window messages ---
    @Test
    fun `scenario B and C messages beyond the active window are folded into a stored continuity summary`() {
        val fixture = fixture()
        seedMessages(fixture, 12) // window size 10 -> 2 messages (message-0, message-1) are out of window
        var receivedOlderMessages: List<ContextBlock>? = null
        val summarization = BestEffortContinuitySummarization(
            summarizer = { _, _, older -> receivedOlderMessages = older; "user mentioned message-0 and message-1 earlier." },
            conversationRepository = fixture.conversationRepository,
            messageRepository = fixture.messageRepository,
            activeWindowSize = 10,
            executor = Executor { it.run() },
        )

        summarization.dispatch(completedTurn(fixture))

        assertEquals(2, receivedOlderMessages?.size)
        assertEquals("message-0", receivedOlderMessages?.get(0)?.content)
        assertEquals("message-1", receivedOlderMessages?.get(1)?.content)

        val conversation = fixture.conversationRepository.findById(fixture.conversationId)!!
        assertEquals("user mentioned message-0 and message-1 earlier.", conversation.continuitySummary)
        assertEquals(2, conversation.continuitySummaryCoveredCount)
    }

    // --- D. Newer information replaces older information (full overwrite, never appended) ---
    @Test
    fun `updated summary fully replaces the previous one rather than appending`() {
        val fixture = fixture()
        fixture.conversationRepository.updateContinuitySummary(fixture.conversationId, "user works at Company A", 2)
        seedMessages(fixture, 14) // 4 more messages beyond the 2 already covered, up to activeWindowStart=4

        val summarization = BestEffortContinuitySummarization(
            summarizer = { _, previous, _ ->
                assertEquals("user works at Company A", previous, "Summarizer must see the previous summary to decide what to replace")
                "user now works at Company B"
            },
            conversationRepository = fixture.conversationRepository,
            messageRepository = fixture.messageRepository,
            activeWindowSize = 10,
            executor = Executor { it.run() },
        )

        summarization.dispatch(completedTurn(fixture))

        val conversation = fixture.conversationRepository.findById(fixture.conversationId)!!
        assertEquals("user now works at Company B", conversation.continuitySummary)
        assertFalse(conversation.continuitySummary!!.contains("Company A"), "Stale information must not survive an update")
    }

    // --- E. Failure isolation ---
    @Test
    fun `scenario E summarizer failure is isolated and leaves prior state unchanged`() {
        val fixture = fixture()
        seedMessages(fixture, 12)
        val summarization = BestEffortContinuitySummarization(
            summarizer = { _, _, _ -> throw IllegalStateException("simulated failure") },
            conversationRepository = fixture.conversationRepository,
            messageRepository = fixture.messageRepository,
            activeWindowSize = 10,
            executor = Executor { it.run() },
        )

        summarization.dispatch(completedTurn(fixture)) // must not throw

        val conversation = fixture.conversationRepository.findById(fixture.conversationId)!!
        assertNull(conversation.continuitySummary)
        assertEquals(0, conversation.continuitySummaryCoveredCount)
    }

    // --- F. Idempotency / repeated dispatch ---
    @Test
    fun `scenario F repeated dispatch for the same state does not reprocess or duplicate content`() {
        val fixture = fixture()
        seedMessages(fixture, 12)
        var summarizerCalls = 0
        val summarization = BestEffortContinuitySummarization(
            summarizer = { _, _, _ -> summarizerCalls++; "folded summary" },
            conversationRepository = fixture.conversationRepository,
            messageRepository = fixture.messageRepository,
            activeWindowSize = 10,
            executor = Executor { it.run() },
        )

        summarization.dispatch(completedTurn(fixture))
        summarization.dispatch(completedTurn(fixture)) // retry / repeated post-delivery processing

        assertEquals(1, summarizerCalls, "Already-covered messages must not be resent to the summarizer")
        val conversation = fixture.conversationRepository.findById(fixture.conversationId)!!
        assertEquals("folded summary", conversation.continuitySummary)
        assertEquals(2, conversation.continuitySummaryCoveredCount)
    }

    // --- Bounding ---
    @Test
    fun `overlong summarizer output is bounded to the configured maximum length`() {
        val fixture = fixture()
        seedMessages(fixture, 12)
        val overlong = "x".repeat(2000)
        val summarization = BestEffortContinuitySummarization(
            summarizer = { _, _, _ -> overlong },
            conversationRepository = fixture.conversationRepository,
            messageRepository = fixture.messageRepository,
            activeWindowSize = 10,
            maxSummaryLength = 100,
            executor = Executor { it.run() },
        )

        summarization.dispatch(completedTurn(fixture))

        val conversation = fixture.conversationRepository.findById(fixture.conversationId)!!
        assertEquals(100, conversation.continuitySummary?.length)
    }

    // --- Blank summarizer output still advances the covered pointer ---
    @Test
    fun `blank summarizer output preserves the previous summary but still advances the covered pointer`() {
        val fixture = fixture()
        fixture.conversationRepository.updateContinuitySummary(fixture.conversationId, "existing summary", 0)
        seedMessages(fixture, 12)
        var summarizerCalls = 0
        val summarization = BestEffortContinuitySummarization(
            summarizer = { _, _, _ -> summarizerCalls++; null },
            conversationRepository = fixture.conversationRepository,
            messageRepository = fixture.messageRepository,
            activeWindowSize = 10,
            executor = Executor { it.run() },
        )

        summarization.dispatch(completedTurn(fixture))
        summarization.dispatch(completedTurn(fixture))

        assertEquals(1, summarizerCalls, "Pointer must advance even on a blank result, so the same messages aren't retried forever")
        val conversation = fixture.conversationRepository.findById(fixture.conversationId)!!
        assertEquals("existing summary", conversation.continuitySummary)
        assertEquals(2, conversation.continuitySummaryCoveredCount)
    }
}
