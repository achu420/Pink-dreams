package com.pinkdreams.chat.continuity

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.PersistedResponse
import com.pinkdreams.chat.memory.CompletedTurn
import com.pinkdreams.llm.FakeLlmClient
import com.pinkdreams.llm.GenerationRequest
import com.pinkdreams.llm.LlmResponse
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmContinuitySummarizerTest {

    private fun completedTurn(): CompletedTurn {
        val request = ChatRequest(
            requestId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            conversationId = UUID.randomUUID(),
            personaId = UUID.randomUUID(),
            clientMessageId = UUID.randomUUID(),
            content = "current message",
        )
        val context = ChatContext(
            blocks = listOf(ContextBlock("system", "persona rules")),
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID(),
        )
        return CompletedTurn(request, context, PersistedResponse(UUID.randomUUID(), "assistant reply"))
    }

    @Test
    fun `request carries dedicated instructions previous summary and native older message blocks in order`() {
        val client = FakeLlmClient(response = LlmResponse(content = "user lives in Delhi and works as a designer.", provider = "test"))
        val summarizer = LlmContinuitySummarizer(client)

        val olderMessages = listOf(
            ContextBlock("user", "I live in Delhi."),
            ContextBlock("assistant", "That's great!"),
        )
        val result = summarizer.summarize(completedTurn(), "user lives in Delhi.", olderMessages)

        assertEquals("user lives in Delhi and works as a designer.", result)

        val sentRequest = client.lastRequest as GenerationRequest
        val blocks = sentRequest.context.blocks
        assertEquals(4, blocks.size, "instructions + previous-summary + 2 native older messages")
        assertEquals("system", blocks[0].role)
        assertTrue(blocks[0].content.contains("CONTINUITY SUMMARY"), "Must carry the dedicated continuity prompt, not the persona prompt")
        assertEquals("system", blocks[1].role)
        assertTrue(blocks[1].content.contains("PREVIOUS SUMMARY:"))
        assertTrue(blocks[1].content.contains("user lives in Delhi."))
        assertEquals("user", blocks[2].role)
        assertEquals("I live in Delhi.", blocks[2].content)
        assertEquals("assistant", blocks[3].role)
        assertEquals("That's great!", blocks[3].content)
    }

    @Test
    fun `blank model response yields null so the previous summary is preserved by the caller`() {
        val client = FakeLlmClient(response = LlmResponse(content = "   ", provider = "test"))
        val summarizer = LlmContinuitySummarizer(client)

        val result = summarizer.summarize(completedTurn(), "existing summary", listOf(ContextBlock("user", "hi")))

        assertNull(result)
    }

    @Test
    fun `no previous summary is represented explicitly rather than left blank`() {
        val client = FakeLlmClient(response = LlmResponse(content = "new summary text", provider = "test"))
        val summarizer = LlmContinuitySummarizer(client)

        summarizer.summarize(completedTurn(), null, listOf(ContextBlock("user", "hi")))

        val sentRequest = client.lastRequest as GenerationRequest
        assertTrue(sentRequest.context.blocks[1].content.contains("(none yet)"))
    }
}
