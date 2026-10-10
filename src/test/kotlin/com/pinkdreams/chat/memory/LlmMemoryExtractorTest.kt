package com.pinkdreams.chat.memory

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.PersistedResponse
import com.pinkdreams.llm.FakeLlmClient
import com.pinkdreams.llm.GenerationRequest
import com.pinkdreams.llm.LlmResponse
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit-level tests for LlmMemoryExtractor's request construction and response
 * parsing/validation, using FakeLlmClient (no real model). These prove the
 * extractor builds a correctly-shaped, role-provenance-preserving request and
 * robustly parses/validates whatever the model returns — not that a real LLM
 * makes good judgment calls (that depends on the prompt and the model itself).
 */
class LlmMemoryExtractorTest {

    private fun completedTurn(userContent: String, assistantContent: String): CompletedTurn {
        val engineVersionId = UUID.randomUUID()
        val personaCoreVersionId = UUID.randomUUID()
        val request = ChatRequest(
            requestId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            conversationId = UUID.randomUUID(),
            personaId = UUID.randomUUID(),
            clientMessageId = UUID.randomUUID(),
            content = userContent,
        )
        val context = ChatContext(
            blocks = listOf(ContextBlock("system", "persona rules"), ContextBlock("user", userContent)),
            engineVersionId = engineVersionId,
            personaCoreVersionId = personaCoreVersionId,
        )
        val response = PersistedResponse(assistantMessageId = UUID.randomUUID(), content = assistantContent)
        return CompletedTurn(request, context, response)
    }

    // --- A. No memory ---
    @Test
    fun `scenario A model returns no facts yields empty extraction result`() {
        val client = FakeLlmClient(response = LlmResponse(content = """{"facts": []}""", provider = "test"))
        val extractor = LlmMemoryExtractor(client)

        val result = extractor.extract(completedTurn("What's the weather today?", "It's sunny."))

        assertTrue(result.newFacts.isEmpty())
    }

    // --- B. Durable user fact ---
    @Test
    fun `scenario B model returns one fact yields one valid MemoryCandidate`() {
        val client = FakeLlmClient(
            response = LlmResponse(
                content = """{"facts": [{"fact": "user lives in Delhi", "factType": "interest", "criticality": "medium", "subject": "user", "evidenceQuote": "I live in Delhi"}]}""",
                provider = "test",
            ),
        )
        val extractor = LlmMemoryExtractor(client)

        val result = extractor.extract(completedTurn("I live in Delhi.", "That's a great city!"))

        assertEquals(1, result.newFacts.size)
        assertEquals("user lives in Delhi", result.newFacts.single().fact)
        assertEquals("interest", result.newFacts.single().factType)
        assertEquals("medium", result.newFacts.single().criticality)
    }

    // --- C. Role provenance: assistant content must never be presented as the fact source ---
    @Test
    fun `scenario C extraction request preserves native user assistant role provenance and never flattens the turn`() {
        val client = FakeLlmClient(response = LlmResponse(content = """{"facts": []}""", provider = "test"))
        val extractor = LlmMemoryExtractor(client)

        extractor.extract(completedTurn("Tell me something interesting.", "You're probably someone who loves travel."))

        val sentRequest = client.lastRequest as GenerationRequest
        val blocks = sentRequest.context.blocks
        assertEquals(3, blocks.size, "system instructions + native user block + native assistant block")
        assertEquals("system", blocks[0].role)
        assertTrue(blocks[0].content.contains("memory-extraction system"), "System block must carry the dedicated extraction prompt, not the persona prompt")
        assertTrue(blocks[0].content.contains("NEVER create"), "Prompt must explicitly forbid deriving memory from assistant content")

        assertEquals("user", blocks[1].role)
        assertEquals("Tell me something interesting.", blocks[1].content, "User content must be exact, not merged with assistant content")

        assertEquals("assistant", blocks[2].role)
        assertEquals("You're probably someone who loves travel.", blocks[2].content, "Assistant content must be exact and separately role-tagged")
    }

    // --- D. Temporary statement (model correctly declines) ---
    @Test
    fun `scenario D temporary statement with empty model response yields no memory`() {
        val client = FakeLlmClient(response = LlmResponse(content = """{"facts": []}""", provider = "test"))
        val extractor = LlmMemoryExtractor(client)

        val result = extractor.extract(completedTurn("I'm hungry right now.", "Maybe grab a snack!"))

        assertTrue(result.newFacts.isEmpty())
    }

    // --- F. Multiple memories ---
    @Test
    fun `scenario F model returns multiple facts yields multiple valid candidates without inventing extras`() {
        val client = FakeLlmClient(
            response = LlmResponse(
                content = """{"facts": [
                    {"fact": "user's name is Rahul", "factType": "interest", "criticality": "medium", "subject": "user", "evidenceQuote": "My name is Rahul"},
                    {"fact": "user lives in Delhi", "factType": "interest", "criticality": "medium", "subject": "user", "evidenceQuote": "I live in Delhi"}
                ]}""",
                provider = "test",
            ),
        )
        val extractor = LlmMemoryExtractor(client)

        val result = extractor.extract(completedTurn("My name is Rahul and I live in Delhi.", "Nice to meet you, Rahul!"))

        assertEquals(2, result.newFacts.size)
        assertTrue(result.newFacts.any { it.fact == "user's name is Rahul" })
        assertTrue(result.newFacts.any { it.fact == "user lives in Delhi" })
    }

    @Test
    fun `malformed json response is treated as no memory rather than crashing`() {
        val client = FakeLlmClient(response = LlmResponse(content = "not json at all", provider = "test"))
        val extractor = LlmMemoryExtractor(client)

        val result = extractor.extract(completedTurn("I live in Delhi.", "Noted."))

        assertTrue(result.newFacts.isEmpty())
    }

    @Test
    fun `markdown fenced json response is still parsed correctly`() {
        val client = FakeLlmClient(
            response = LlmResponse(
                content = "```json\n{\"facts\": [{\"fact\": \"user lives in Delhi\", \"factType\": \"interest\", \"criticality\": \"medium\", \"subject\": \"user\", \"evidenceQuote\": \"I live in Delhi\"}]}\n```",
                provider = "test",
            ),
        )
        val extractor = LlmMemoryExtractor(client)

        val result = extractor.extract(completedTurn("I live in Delhi.", "Noted."))

        assertEquals(1, result.newFacts.size)
        assertEquals("user lives in Delhi", result.newFacts.single().fact)
    }

    @Test
    fun `invalid factType or criticality is filtered out rather than passed through or crashing the batch`() {
        val client = FakeLlmClient(
            response = LlmResponse(
                content = """{"facts": [
                    {"fact": "user lives in Delhi", "factType": "not_a_real_type", "criticality": "medium", "subject": "user", "evidenceQuote": "I live in Delhi"},
                    {"fact": "user's favorite color is blue", "factType": "interest", "criticality": "extreme", "subject": "user", "evidenceQuote": "my favorite color is blue"},
                    {"fact": "user likes tea", "factType": "interest", "criticality": "low", "subject": "user", "evidenceQuote": "I like tea"}
                ]}""",
                provider = "test",
            ),
        )
        val extractor = LlmMemoryExtractor(client)

        val result = extractor.extract(completedTurn("I live in Delhi, my favorite color is blue, and I like tea.", "Noted."))

        assertEquals(1, result.newFacts.size, "Only the fully valid candidate should survive")
        assertEquals("user likes tea", result.newFacts.single().fact)
    }

    @Test
    fun `overlong fact is filtered out`() {
        val overlong = "x".repeat(MemoryService.MAX_FACT_LENGTH + 1)
        val client = FakeLlmClient(
            response = LlmResponse(
                content = """{"facts": [{"fact": "$overlong", "factType": "interest", "criticality": "low", "subject": "user", "evidenceQuote": "some long story"}]}""",
                provider = "test",
            ),
        )
        val extractor = LlmMemoryExtractor(client)

        val result = extractor.extract(completedTurn("some long story", "ok"))

        assertTrue(result.newFacts.isEmpty())
    }

    // --- Speaker-attribution safeguard (Phase 3) ---
    // Regression coverage for: a persona's own self-introduction being
    // misattributed to the user as a permanent memory fact.

    @Test
    fun `persona self-introduction is not stored as a user fact even when subject is mislabeled`() {
        // Simulates a non-compliant model: it labels subject="user" (the ONE
        // thing the prompt most directly asks it to get right) but the
        // evidenceQuote it offers can only come from the persona's own
        // message ("I'm Simran"), never the user's — so the grounding check
        // must catch this independently of the subject label.
        val client = FakeLlmClient(
            response = LlmResponse(
                content = """{"facts": [{"fact": "user's name is Simran", "factType": "interest", "criticality": "medium", "subject": "user", "evidenceQuote": "I'm Simran"}]}""",
                provider = "test",
            ),
        )
        val extractor = LlmMemoryExtractor(client, personaNameLookup = { "Simran" })

        val result = extractor.extract(
            completedTurn(
                userContent = "Hi! What is your name and what do you like to talk about?",
                assistantContent = "Oh, hi! I'm Simran. I love a good story or a heated debate.",
            ),
        )

        assertTrue(result.newFacts.isEmpty(), "A persona's self-introduction must never become a user fact")
    }

    @Test
    fun `persona self-introduction is rejected even when evidence is fabricated from the user message`() {
        // A second, independent guard: even if the model somehow fabricates a
        // quote that happens to be grounded, a fact that names the persona
        // itself is rejected outright.
        val client = FakeLlmClient(
            response = LlmResponse(
                content = """{"facts": [{"fact": "user's name is Simran", "factType": "interest", "criticality": "medium", "subject": "user", "evidenceQuote": "What is your name"}]}""",
                provider = "test",
            ),
        )
        val extractor = LlmMemoryExtractor(client, personaNameLookup = { "Simran" })

        val result = extractor.extract(
            completedTurn(
                userContent = "What is your name and what do you like to talk about?",
                assistantContent = "Oh, hi! I'm Simran.",
            ),
        )

        assertTrue(result.newFacts.isEmpty(), "A fact naming the persona itself must be rejected regardless of grounding")
    }

    @Test
    fun `user self-introduction is correctly stored`() {
        val client = FakeLlmClient(
            response = LlmResponse(
                content = """{"facts": [{"fact": "user's name is Rahul", "factType": "interest", "criticality": "medium", "subject": "user", "evidenceQuote": "My name is Rahul"}]}""",
                provider = "test",
            ),
        )
        val extractor = LlmMemoryExtractor(client, personaNameLookup = { "Simran" })

        val result = extractor.extract(
            completedTurn(
                userContent = "My name is Rahul, and I live in Delhi.",
                assistantContent = "Nice to meet you, Rahul!",
            ),
        )

        assertEquals(1, result.newFacts.size)
        assertEquals("user's name is Rahul", result.newFacts.single().fact)
    }

    @Test
    fun `third party mentioned by the user is not attributed to the user`() {
        // The user mentions someone else by name; a candidate that labels
        // itself subject="other" (per the prompt's explicit instruction) must
        // be rejected even though the text is genuinely present in the user's
        // own message.
        val client = FakeLlmClient(
            response = LlmResponse(
                content = """{"facts": [{"fact": "Simran lives in Pune", "factType": "interest", "criticality": "medium", "subject": "other", "evidenceQuote": "My colleague Simran lives in Pune"}]}""",
                provider = "test",
            ),
        )
        val extractor = LlmMemoryExtractor(client)

        val result = extractor.extract(
            completedTurn(
                userContent = "My colleague Simran lives in Pune.",
                assistantContent = "That's nice!",
            ),
        )

        assertTrue(result.newFacts.isEmpty(), "A fact about a third party must never be attributed to the user")
    }

    @Test
    fun `ambiguous or missing subject is rejected conservatively`() {
        val client = FakeLlmClient(
            response = LlmResponse(
                content = """{"facts": [{"fact": "user likes jazz", "factType": "interest", "criticality": "low", "evidenceQuote": "I like jazz"}]}""",
                provider = "test",
            ),
        )
        val extractor = LlmMemoryExtractor(client)

        val result = extractor.extract(completedTurn("I like jazz.", "Nice taste!"))

        assertTrue(result.newFacts.isEmpty(), "A candidate with no declared subject must be rejected, not assumed to be about the user")
    }

    @Test
    fun `mixed turn with both a user fact and a persona self-introduction keeps only the user fact`() {
        val client = FakeLlmClient(
            response = LlmResponse(
                content = """{"facts": [
                    {"fact": "user's name is Rahul", "factType": "interest", "criticality": "medium", "subject": "user", "evidenceQuote": "My name is Rahul"},
                    {"fact": "assistant's name is Simran", "factType": "interest", "criticality": "medium", "subject": "assistant", "evidenceQuote": "I'm Simran"}
                ]}""",
                provider = "test",
            ),
        )
        val extractor = LlmMemoryExtractor(client, personaNameLookup = { "Simran" })

        val result = extractor.extract(
            completedTurn(
                userContent = "My name is Rahul, nice to meet you.",
                assistantContent = "I'm Simran, nice to meet you too!",
            ),
        )

        assertEquals(1, result.newFacts.size)
        assertEquals("user's name is Rahul", result.newFacts.single().fact)
    }

    @Test
    fun `extraction prompt names the persona when a lookup is supplied`() {
        val client = FakeLlmClient(response = LlmResponse(content = """{"facts": []}""", provider = "test"))
        val extractor = LlmMemoryExtractor(client, personaNameLookup = { "Simran" })

        extractor.extract(completedTurn("Hi!", "Hi, I'm Simran!"))

        val sentRequest = client.lastRequest as GenerationRequest
        assertTrue(
            sentRequest.context.blocks[0].content.contains("Simran"),
            "The extraction prompt must name the persona when a lookup is available",
        )
    }
}
