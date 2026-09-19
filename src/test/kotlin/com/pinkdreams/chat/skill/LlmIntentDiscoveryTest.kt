package com.pinkdreams.chat.skill

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.llm.FakeLlmClient
import com.pinkdreams.llm.GenerationRequest
import com.pinkdreams.llm.LlmClient
import com.pinkdreams.llm.LlmResponse
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.SkillRepository
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmIntentDiscoveryTest {

    private fun activeSkillRepo(vararg keys: String): SkillRepository {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val repo = SkillRepository(db)
        keys.forEach { key -> repo.activate(repo.publish(repo.createNextVersion(key, "content for $key").id).id) }
        return repo
    }

    private fun requestAndContext(content: String): Pair<ChatRequest, ChatContext> {
        val request = ChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), content)
        val context = ChatContext(
            blocks = listOf(
                ContextBlock("system", "engine+persona"),
                ContextBlock("user", "hey"),
                ContextBlock("assistant", "hi there"),
                ContextBlock("user", content),
            ),
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID(),
        )
        return request to context
    }

    @Test
    fun `clearly flirtatious input with model returning an active key yields Selected`() {
        val repo = activeSkillRepo("flirting", "friendship")
        val client = FakeLlmClient(response = LlmResponse(content = """{"skillKey": "flirting"}""", provider = "test"))
        val discovery = LlmIntentDiscovery(client, repo)

        val (request, context) = requestAndContext("you look really cute today")
        assertEquals(SkillSelection.Selected("flirting"), discovery.selectSkill(request, context))
    }

    @Test
    fun `model returning none yields None`() {
        val repo = activeSkillRepo("flirting", "emotional_support")
        val client = FakeLlmClient(response = LlmResponse(content = """{"skillKey": "none"}""", provider = "test"))
        val discovery = LlmIntentDiscovery(client, repo)

        val (request, context) = requestAndContext("what time is it")
        assertEquals(SkillSelection.None, discovery.selectSkill(request, context))
    }

    @Test
    fun `model returning a key that is not currently active is rejected as None`() {
        val repo = activeSkillRepo("flirting")
        val client = FakeLlmClient(response = LlmResponse(content = """{"skillKey": "seduction"}""", provider = "test"))
        val discovery = LlmIntentDiscovery(client, repo)

        val (request, context) = requestAndContext("anything")
        assertEquals(SkillSelection.None, discovery.selectSkill(request, context), "Unknown/inactive skill keys must never be selected")
    }

    @Test
    fun `no active skills at all skips the LLM call entirely and returns None`() {
        val repo = activeSkillRepo() // none activated
        val client = FakeLlmClient(response = LlmResponse(content = """{"skillKey": "flirting"}""", provider = "test"))
        val discovery = LlmIntentDiscovery(client, repo)

        val (request, context) = requestAndContext("you look cute")
        assertEquals(SkillSelection.None, discovery.selectSkill(request, context))
        assertEquals(null, client.lastRequest, "No LLM call should be made when there are no candidate skills")
    }

    @Test
    fun `llm throwing an exception degrades to None rather than propagating`() {
        val repo = activeSkillRepo("flirting")
        val failingClient = FakeLlmClient(failure = RuntimeException("simulated timeout"))
        val discovery = LlmIntentDiscovery(failingClient, repo)

        val (request, context) = requestAndContext("you look cute")
        assertEquals(SkillSelection.None, discovery.selectSkill(request, context))
    }

    @Test
    fun `malformed json response degrades to None`() {
        val repo = activeSkillRepo("flirting")
        val client = FakeLlmClient(response = LlmResponse(content = "not json at all", provider = "test"))
        val discovery = LlmIntentDiscovery(client, repo)

        val (request, context) = requestAndContext("you look cute")
        assertEquals(SkillSelection.None, discovery.selectSkill(request, context))
    }

    @Test
    fun `repository failure degrades to None`() {
        val throwingRepo = object : SkillRepository(DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }) {
            override fun findAllActiveKeys(): List<String> = throw IllegalStateException("simulated repository failure")
        }
        val client = FakeLlmClient(response = LlmResponse(content = """{"skillKey": "flirting"}""", provider = "test"))
        val discovery = LlmIntentDiscovery(client, throwingRepo)

        val (request, context) = requestAndContext("you look cute")
        assertEquals(SkillSelection.None, discovery.selectSkill(request, context))
    }

    @Test
    fun `candidate keys are sourced from the active skill repository not a hardcoded list`() {
        val repo = activeSkillRepo("dating", "playful_teasing")
        var promptSeen: String? = null
        val client = LlmClient { req ->
            promptSeen = req.context.blocks.first().content
            LlmResponse(content = """{"skillKey": "dating"}""", provider = "test")
        }
        val discovery = LlmIntentDiscovery(client, repo)

        val (request, context) = requestAndContext("want to grab dinner sometime?")
        val result = discovery.selectSkill(request, context)

        assertEquals(SkillSelection.Selected("dating"), result)
        // The candidate list is the authoritative offer. The canonical prompt also
        // contains a static glossary of every skill name, which is explanatory text
        // and not a list of selectable keys — so assert on the candidate line itself.
        val candidateLine = promptSeen!!.lineSequence()
            .dropWhile { !it.contains("ACTIVE SKILL KEYS") }
            .drop(1)
            .first { it.isNotBlank() }
        val offered = candidateLine.split(",").map { it.trim() }.toSet()
        assertEquals(setOf("dating", "playful_teasing"), offered, "Only currently-active keys should appear as candidates")
    }

    @Test
    fun `discovery request does not include the full conversation only recent history plus current message`() {
        val repo = activeSkillRepo("flirting")
        val client = FakeLlmClient(response = LlmResponse(content = """{"skillKey": "none"}""", provider = "test"))
        val discovery = LlmIntentDiscovery(client, repo, recentHistoryLimit = 2)

        val request = ChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "current message")
        val context = ChatContext(
            blocks = listOf(ContextBlock("system", "engine+persona")) +
                (1..10).map { ContextBlock(if (it % 2 == 0) "assistant" else "user", "HIST_$it") } +
                listOf(ContextBlock("user", "current message")),
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID(),
        )

        discovery.selectSkill(request, context)

        val sentRequest = client.lastRequest as GenerationRequest
        val blocks = sentRequest.context.blocks
        // system instructions + 2 recent history blocks + 1 current message block.
        assertEquals(4, blocks.size)
        assertEquals("HIST_9", blocks[1].content)
        assertEquals("HIST_10", blocks[2].content)
        assertEquals("current message", blocks[3].content)
    }
}
