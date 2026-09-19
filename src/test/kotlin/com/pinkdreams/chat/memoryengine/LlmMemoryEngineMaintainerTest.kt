package com.pinkdreams.chat.memoryengine

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.PersistedResponse
import com.pinkdreams.chat.memory.CompletedTurn
import com.pinkdreams.llm.FakeLlmClient
import com.pinkdreams.llm.GenerationRequest
import com.pinkdreams.llm.LlmResponse
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.MemoryEngineRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmMemoryEngineMaintainerTest {

    private fun activeEngine(content: String = "MEMORY ENGINE INSTRUCTIONS"): MemoryEngineRepository {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val repo = MemoryEngineRepository(db)
        repo.activate(repo.publish(repo.createNextVersion(content).id).id)
        return repo
    }

    private fun turn(): CompletedTurn {
        val request = ChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "hi")
        val context = ChatContext(listOf(ContextBlock("system", "x")), engineVersionId = UUID.randomUUID(), personaCoreVersionId = UUID.randomUUID())
        return CompletedTurn(request, context, PersistedResponse(UUID.randomUUID(), "reply"))
    }

    private fun message(role: String, content: String) = MessageRepository.Message(
        id = UUID.randomUUID(), conversationId = UUID.randomUUID(), role = role, content = content,
        engineVersionId = null, personaCoreVersionId = null, clientMessageId = null, requestId = null,
        metadata = "{}", createdAt = LocalDateTime.now(),
    )

    @Test
    fun `no active memory engine returns an empty result without calling the LLM`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val repo = MemoryEngineRepository(db)
        repo.createNextVersion("draft only, never activated") // draft, not active
        val client = FakeLlmClient(response = LlmResponse(content = """{"userMemoryChanges":[{"action":"ADD"}]}""", provider = "test"))
        val maintainer = LlmMemoryEngineMaintainer(client, repo)

        val result = maintainer.maintain(turn(), listOf(message("user", "hi")), emptyList(), emptyList())

        assertEquals(MemoryMaintenanceResult(), result)
        assertEquals(null, client.lastRequest)
    }

    @Test
    fun `empty batch returns an empty result without calling the LLM`() {
        val repo = activeEngine()
        val client = FakeLlmClient(response = LlmResponse(content = """{"userMemoryChanges":[]}""", provider = "test"))
        val maintainer = LlmMemoryEngineMaintainer(client, repo)

        maintainer.maintain(turn(), emptyList(), emptyList(), emptyList())

        assertEquals(null, client.lastRequest)
    }

    @Test
    fun `ADD action is parsed correctly`() {
        val repo = activeEngine()
        val client = FakeLlmClient(response = LlmResponse(
            content = """{"userMemoryChanges":[{"action":"ADD","memoryType":"interest","content":"user likes hiking","criticality":"medium"}]}""",
            provider = "test",
        ))
        val maintainer = LlmMemoryEngineMaintainer(client, repo)

        val result = maintainer.maintain(turn(), listOf(message("user", "I love hiking")), emptyList(), emptyList())

        assertEquals(1, result.userMemoryChanges.size)
        val change = result.userMemoryChanges.single()
        assertEquals(MemoryChangeAction.ADD, change.action)
        assertEquals("user likes hiking", change.content)
        assertEquals("interest", change.memoryType)
    }

    @Test
    fun `UPDATE SUPERSEDE REMOVE KEEP and IGNORE actions all parse correctly`() {
        val repo = activeEngine()
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        val id3 = UUID.randomUUID()
        val id4 = UUID.randomUUID()
        val client = FakeLlmClient(response = LlmResponse(
            content = """{"userMemoryChanges":[
                {"action":"UPDATE","memoryId":"$id1","content":"refined"},
                {"action":"SUPERSEDE","memoryId":"$id2","content":"new fact","memoryType":"interest"},
                {"action":"REMOVE","memoryId":"$id3"},
                {"action":"KEEP","memoryId":"$id4"},
                {"action":"IGNORE"}
            ]}""",
            provider = "test",
        ))
        val maintainer = LlmMemoryEngineMaintainer(client, repo)

        val result = maintainer.maintain(turn(), listOf(message("user", "hi")), emptyList(), emptyList())

        assertEquals(5, result.userMemoryChanges.size)
        assertEquals(listOf(MemoryChangeAction.UPDATE, MemoryChangeAction.SUPERSEDE, MemoryChangeAction.REMOVE, MemoryChangeAction.KEEP, MemoryChangeAction.IGNORE), result.userMemoryChanges.map { it.action })
    }

    @Test
    fun `persona memory changes are parsed into a separate list`() {
        val repo = activeEngine()
        val client = FakeLlmClient(response = LlmResponse(
            content = """{"userMemoryChanges":[],"personaMemoryChanges":[{"action":"ADD","memoryType":"commitment","content":"Simran promised to follow up"}]}""",
            provider = "test",
        ))
        val maintainer = LlmMemoryEngineMaintainer(client, repo)

        val result = maintainer.maintain(turn(), listOf(message("assistant", "I'll let you know")), emptyList(), emptyList())

        assertTrue(result.userMemoryChanges.isEmpty())
        assertEquals(1, result.personaMemoryChanges.size)
        assertEquals("commitment", result.personaMemoryChanges.single().memoryType)
    }

    @Test
    fun `unknown action is dropped rather than failing the whole batch`() {
        val repo = activeEngine()
        val client = FakeLlmClient(response = LlmResponse(
            content = """{"userMemoryChanges":[{"action":"DELETE_EVERYTHING","content":"x"},{"action":"KEEP","memoryId":"${UUID.randomUUID()}"}]}""",
            provider = "test",
        ))
        val maintainer = LlmMemoryEngineMaintainer(client, repo)

        val result = maintainer.maintain(turn(), listOf(message("user", "hi")), emptyList(), emptyList())

        assertEquals(1, result.userMemoryChanges.size, "The unknown action must be dropped, the valid one preserved")
        assertEquals(MemoryChangeAction.KEEP, result.userMemoryChanges.single().action)
    }

    @Test
    fun `malformed json yields an empty result rather than throwing`() {
        val repo = activeEngine()
        val client = FakeLlmClient(response = LlmResponse(content = "not json at all", provider = "test"))
        val maintainer = LlmMemoryEngineMaintainer(client, repo)

        val result = maintainer.maintain(turn(), listOf(message("user", "hi")), emptyList(), emptyList())

        assertEquals(MemoryMaintenanceResult(), result)
    }

    @Test
    fun `markdown fenced json is still parsed correctly`() {
        val repo = activeEngine()
        val client = FakeLlmClient(response = LlmResponse(
            content = "```json\n{\"userMemoryChanges\":[{\"action\":\"ADD\",\"memoryType\":\"interest\",\"content\":\"x\"}]}\n```",
            provider = "test",
        ))
        val maintainer = LlmMemoryEngineMaintainer(client, repo)

        val result = maintainer.maintain(turn(), listOf(message("user", "hi")), emptyList(), emptyList())

        assertEquals(1, result.userMemoryChanges.size)
    }

    @Test
    fun `request sends active memory engine content as system instructions and batch plus working memory as user payload`() {
        val repo = activeEngine("MEMORY_ENGINE_UNIQUE_INSTRUCTIONS")
        val client = FakeLlmClient(response = LlmResponse(content = """{"userMemoryChanges":[]}""", provider = "test"))
        val maintainer = LlmMemoryEngineMaintainer(client, repo)

        maintainer.maintain(turn(), listOf(message("user", "BATCH_MESSAGE_CONTENT")), emptyList(), emptyList())

        val sentRequest = client.lastRequest as GenerationRequest
        val blocks = sentRequest.context.blocks
        assertEquals(2, blocks.size)
        assertEquals("system", blocks[0].role)
        assertTrue(blocks[0].content.contains("MEMORY_ENGINE_UNIQUE_INSTRUCTIONS"))
        assertEquals("user", blocks[1].role)
        assertTrue(blocks[1].content.contains("BATCH_MESSAGE_CONTENT"))
        assertTrue(blocks[1].content.contains("RECENT CONVERSATION"))
        assertTrue(blocks[1].content.contains("CURRENT USER MEMORY"))
        assertTrue(blocks[1].content.contains("CURRENT PERSONA MEMORY"))
    }
}
