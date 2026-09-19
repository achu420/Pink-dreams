package com.pinkdreams.baseline

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.PersistedResponse
import com.pinkdreams.chat.memory.CompletedTurn
import com.pinkdreams.chat.memory.DeterministicMemoryContextSelector
import com.pinkdreams.chat.memory.LlmMemoryExtractor
import com.pinkdreams.chat.memory.MemoryCandidate
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.chat.memory.SkillAwareMemoryEnricher
import com.pinkdreams.chat.skill.LlmIntentDiscovery
import com.pinkdreams.chat.skill.SkillContextEnricher
import com.pinkdreams.chat.skill.SkillSelection
import com.pinkdreams.llm.GenerationRequest
import com.pinkdreams.llm.LlmClient
import com.pinkdreams.llm.LlmResponse
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.MemoryEngineRepository
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Parts R, S, T, U, V — behavior of the assembled baseline: skill switching
 * across a conversation, memory extraction provenance, the critical
 * persona/memory separation rule, memory-list sizing, and skill-aware memory
 * selection.
 *
 * The LLM is scripted throughout. What is under test is the wiring and the
 * rules the code enforces, not the model's judgment; a live-model run is a
 * separate verification step and cannot be an automated assertion.
 */
class BaselineBehaviorTest {

    private class Fixture {
        val db: Database = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }
        val skills = SkillRepository(db)
        val memoryFacts = MemoryFactRepository(db)
        val memoryService = MemoryService(memoryFacts)
        val userId: UUID = UUID.randomUUID()
        val personaId: UUID = UUID.randomUUID()

        init {
            BaselineSeeder(
                ConversationEngineRepository(db), PersonaRepository(db), PersonaCoreVersionRepository(db),
                skills, MemoryEngineRepository(db),
            ).seedIfMissing()
            // Explicit activation, exactly as an admin would do it.
            skills.findAll().forEach { skills.activate(skills.publish(it.id).id) }
        }

        fun request(content: String) = ChatRequest(UUID.randomUUID(), userId, UUID.randomUUID(), personaId, UUID.randomUUID(), content)

        fun context(current: String, history: List<ContextBlock> = emptyList(), memoryBlock: String? = null) = ChatContext(
            blocks = listOfNotNull(
                ContextBlock("system", "CONVERSATION ENGINE + PERSONA CORE"),
                memoryBlock?.let { ContextBlock("system", it) },
            ) + history + listOf(ContextBlock("user", current)),
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID(),
        )
    }

    /** Returns a queued reply per call, so a multi-turn sequence can be scripted. */
    private class QueuedClient(replies: List<String>) : LlmClient {
        private val queue = ArrayDeque(replies)
        val prompts = mutableListOf<String>()
        override fun generate(request: GenerationRequest): LlmResponse {
            prompts += request.context.blocks.first().content
            return LlmResponse(content = queue.removeFirst(), provider = "test")
        }
    }

    // ---------- Part R: skill switching across a conversation ----------

    @Test
    fun `the selected skill changes turn by turn as the conversation changes`() {
        val f = Fixture()
        val client = QueuedClient(
            listOf(
                """{"skillKey": "general_chat"}""",
                """{"skillKey": "emotional_support"}""",
                """{"skillKey": "playful_teasing"}""",
                """{"skillKey": null}""",
            ),
        )
        val discovery = LlmIntentDiscovery(client, f.skills)

        val turns = listOf(
            "So what did you get up to today?",
            "Honestly I've been struggling a lot this week.",
            "Okay okay, you got me there.",
            "mm",
        )
        val selections = turns.map { discovery.selectSkill(f.request(it), f.context(it)) }

        assertEquals(
            listOf(
                SkillSelection.Selected("general_chat"),
                SkillSelection.Selected("emotional_support"),
                SkillSelection.Selected("playful_teasing"),
                SkillSelection.None,
            ),
            selections,
            "Skill selection must be re-evaluated every turn, including back to None",
        )
    }

    @Test
    fun `each turn injects only its own selected skill and never a previous turn's`() {
        val f = Fixture()
        val enricher = SkillContextEnricher(f.skills)
        // Context is rebuilt per turn by the assembler, so each turn's enrichment
        // starts from a skill-free context — this asserts that property holds.
        val turnOne = enricher.enrich(f.context("I've had a rough week"), SkillSelection.Selected("emotional_support"))
        val turnTwo = enricher.enrich(f.context("You're impossible"), SkillSelection.Selected("playful_teasing"))

        listOf(turnOne, turnTwo).forEach { ctx ->
            assertEquals(
                1, ctx.blocks.count { it.content.startsWith("SELECTED SKILL (") },
                "Exactly one skill block may be present per turn",
            )
        }
        val second = turnTwo.blocks.single { it.content.startsWith("SELECTED SKILL (") }.content
        assertTrue(second.startsWith("SELECTED SKILL (playful_teasing):"))
        assertFalse(second.contains("emotional_support"), "The previous turn's skill must not leak into this turn")
        assertTrue(second.contains("SKILL: PLAYFUL_TEASING"), "The injected block must carry the canonical skill body")
    }

    @Test
    fun `no skill block is injected when intent discovery selects nothing`() {
        val f = Fixture()
        val context = f.context("mm")

        val enriched = SkillContextEnricher(f.skills).enrich(context, SkillSelection.None)

        assertEquals(context.blocks, enriched.blocks, "None must leave the context untouched — chat proceeds on engine + persona alone")
    }

    // ---------- Part T: persona statements must never become user memory ----------

    @Test
    fun `a preference stated by the persona never becomes a user memory`() {
        val f = Fixture()
        // The extractor is handed a turn where the USER said nothing about travelling
        // and the PERSONA said "I love travelling." A correct extractor returns {}.
        // If a future change starts sourcing facts from assistant content, the
        // scripted reply below is what the prompt's own rule forbids — so the
        // assertion is on the PROMPT contract that makes that impossible.
        val client = QueuedClient(listOf("""{"facts": []}"""))
        val extractor = LlmMemoryExtractor(client)

        val request = f.request("What are you up to?")
        val turn = CompletedTurn(
            request = request,
            context = f.context(request.content),
            response = PersistedResponse(
                assistantMessageId = UUID.randomUUID(),
                content = "I love travelling. I'd go somewhere new every month if I could.",
            ),
        )

        val result = extractor.extract(turn)

        assertTrue(result.newFacts.isEmpty(), "Nothing the persona said may become a user memory")

        val prompt = client.prompts.single()
        assertTrue(
            prompt.contains("Use ONLY the message with role \"user\" as the source of facts about the user"),
            "The extraction prompt must restrict fact provenance to the user message",
        )
        val collapsed = prompt.replace(Regex("\\s+"), " ")
        assertTrue(
            collapsed.contains("NEVER create a memory based on something the assistant said"),
            "The extraction prompt must explicitly forbid deriving memory from assistant content",
        )
    }

    @Test
    fun `user and persona memory are stored under distinct owners and never mixed`() {
        val f = Fixture()
        f.memoryFacts.create(f.userId, f.personaId, "user likes travelling", "interest", "medium", owner = "USER")
        f.memoryFacts.create(f.userId, f.personaId, "Simran said she loves travelling", "interaction_context", "low", owner = "PERSONA")

        val userSet = f.memoryService.selectWorkingSet(f.userId, f.personaId, "USER", 20)
        val personaSet = f.memoryService.selectWorkingSet(f.userId, f.personaId, "PERSONA", 20)

        assertEquals(listOf("user likes travelling"), userSet.map { it.fact })
        assertEquals(listOf("Simran said she loves travelling"), personaSet.map { it.fact })
        assertTrue(userSet.all { it.owner == "USER" } && personaSet.all { it.owner == "PERSONA" })
    }

    // ---------- Part U: the relevant-memory target is a target, not a quota ----------

    @Test
    fun `a target of twenty does not force twenty memories to exist`() {
        val f = Fixture()
        f.memoryService.record(
            f.userId, f.personaId,
            listOf(
                MemoryCandidate("user lives in Pune", "interest", "medium"),
                MemoryCandidate("user works night shifts", "habit", "low"),
            ),
        )

        val selected = f.memoryService.selectForContext(f.userId, f.personaId, BaselineConfiguration.RELEVANT_MEMORY_TARGET)

        assertEquals(2, selected.size, "The target is an upper bound — it must never pad, invent, or duplicate memories")
    }

    @Test
    fun `selection is capped at the configured target when more memories exist`() {
        val f = Fixture()
        repeat(30) { i ->
            f.memoryFacts.create(f.userId, f.personaId, "durable detail number $i", "interest", "low")
        }

        val selected = f.memoryService.selectForContext(f.userId, f.personaId, BaselineConfiguration.RELEVANT_MEMORY_TARGET)

        assertTrue(selected.size <= BaselineConfiguration.RELEVANT_MEMORY_TARGET)
        assertEquals(selected.size, selected.map { it.id }.distinct().size, "No memory may appear twice in one context")
    }

    // ---------- Part V: memory selection is skill-aware ----------

    @Test
    fun `the selected skill influences which memories reach the context`() {
        val f = Fixture()
        val emotional = f.memoryFacts.create(
            f.userId, f.personaId, "wants warmth and patience when expressing difficult emotions", "mindset", "low",
            learnedAt = LocalDateTime.now().minusHours(2),
        )
        val trivia = f.memoryFacts.create(
            f.userId, f.personaId, "prefers filter coffee to instant", "interest", "low",
            learnedAt = LocalDateTime.now(),
        )

        val selector = DeterministicMemoryContextSelector(f.skills, contextLimit = 1)
        val candidates = listOf(trivia, emotional)
        val message = "I can't cope today"

        // With no skill selected, skill relevance is zero for both and the newer
        // memory wins on recency — the baseline the skill signal has to overcome.
        assertEquals(trivia.id, selector.select(message, SkillSelection.None, candidates).single().id)

        // With emotional_support selected, the skill's own text lifts the
        // emotionally relevant memory above the newer but unrelated one.
        assertEquals(
            emotional.id,
            selector.select(message, SkillSelection.Selected("emotional_support"), candidates).single().id,
            "The selected skill's content must be an actual relevance signal, not a no-op",
        )
    }

    @Test
    fun `skill aware enrichment replaces the memory block without writing to the store`() {
        val f = Fixture()
        f.memoryFacts.create(f.userId, f.personaId, "user is training for a marathon", "aspiration", "high")
        val before = f.memoryFacts.findForRelationship(f.userId, f.personaId).map { it.id to it.fact }.toSet()

        val enricher = SkillAwareMemoryEnricher(f.memoryService, DeterministicMemoryContextSelector(f.skills))
        val request = f.request("how's the running going?")
        val context = f.context(request.content, memoryBlock = "RETRIEVED MEMORY:\n- placeholder")

        val enriched = enricher.enrich(context, request, SkillSelection.Selected("general_chat"))

        val memoryBlocks = enriched.blocks.filter { it.content.startsWith("RETRIEVED MEMORY:") }
        assertEquals(1, memoryBlocks.size, "Enrichment must replace the memory block, never add a second one")
        assertTrue(memoryBlocks.single().content.contains("marathon"))
        assertEquals(
            before, f.memoryFacts.findForRelationship(f.userId, f.personaId).map { it.id to it.fact }.toSet(),
            "Context selection must never mutate canonical memory",
        )
    }

    @Test
    fun `recent history stays as native role messages through skill and memory stages`() {
        val f = Fixture()
        val history = listOf(
            ContextBlock("user", "I've been learning the guitar."),
            ContextBlock("assistant", "That's lovely — how far along are you?"),
        )
        val request = f.request("Still only know three chords.")
        val context = f.context(request.content, history = history, memoryBlock = "RETRIEVED MEMORY:\n- placeholder")

        val afterMemory = SkillAwareMemoryEnricher(f.memoryService, DeterministicMemoryContextSelector(f.skills))
            .enrich(context, request, SkillSelection.Selected("general_chat"))
        val afterSkill = SkillContextEnricher(f.skills).enrich(afterMemory, SkillSelection.Selected("general_chat"))

        assertEquals(
            listOf("I've been learning the guitar.", "That's lovely — how far along are you?", "Still only know three chords."),
            afterSkill.blocks.filter { it.role != "system" }.map { it.content },
            "History must survive as separate native role messages, never flattened into a system block",
        )
        assertEquals(
            listOf("user", "assistant", "user"),
            afterSkill.blocks.filter { it.role != "system" }.map { it.role },
        )
    }

    // ---------- Part S: memory referenced later in the conversation ----------

    @Test
    fun `a durable preference recorded on one turn is selectable on a later unrelated turn`() {
        val f = Fixture()
        val extractorClient = QueuedClient(
            listOf("""{"facts": [{"fact": "user is allergic to peanuts", "factType": "interest", "criticality": "high"}]}"""),
        )
        val extractor = LlmMemoryExtractor(extractorClient)

        val firstRequest = f.request("Just so you know, I'm allergic to peanuts.")
        val firstTurn = CompletedTurn(
            firstRequest,
            f.context(firstRequest.content),
            PersistedResponse(UUID.randomUUID(), "Noted — I'll keep that in mind."),
        )
        f.memoryService.record(f.userId, f.personaId, extractor.extract(firstTurn).newFacts)

        // Several turns later, on an unrelated topic, the fact is still in the pool.
        val later = f.memoryService.selectForContext(f.userId, f.personaId, BaselineConfiguration.RELEVANT_MEMORY_TARGET)
        assertTrue(later.any { it.fact == "user is allergic to peanuts" }, "A durable high-criticality fact must remain available")

        // And re-extracting it does not create a second copy.
        val duplicateClient = QueuedClient(
            listOf("""{"facts": [{"fact": "user is allergic to peanuts", "factType": "interest", "criticality": "high"}]}"""),
        )
        f.memoryService.record(f.userId, f.personaId, LlmMemoryExtractor(duplicateClient).extract(firstTurn).newFacts)
        assertEquals(
            1, f.memoryFacts.findForRelationship(f.userId, f.personaId).count { it.fact == "user is allergic to peanuts" },
            "Re-stating a known fact must not duplicate it",
        )
    }

    @Test
    fun `memory is scoped to one user persona pair and never leaks`() {
        val f = Fixture()
        val otherUser = UUID.randomUUID()
        val otherPersona = UUID.randomUUID()
        f.memoryFacts.create(f.userId, f.personaId, "user drives a hatchback", "interest", "low")

        assertTrue(f.memoryService.selectForContext(otherUser, f.personaId, 20).isEmpty())
        assertTrue(f.memoryService.selectForContext(f.userId, otherPersona, 20).isEmpty())
        assertEquals(1, f.memoryService.selectForContext(f.userId, f.personaId, 20).size)
    }

    @Test
    fun `markReferenced updates recency without creating a new fact`() {
        val f = Fixture()
        val fact = f.memoryFacts.create(f.userId, f.personaId, "user's sister is getting married", "future_event", "high")

        f.memoryService.markReferenced(f.userId, f.personaId, listOf(fact.id), LocalDateTime.now())

        val all = f.memoryFacts.findForRelationship(f.userId, f.personaId)
        assertEquals(1, all.size)
        assertTrue(all.single().lastReferencedAt != null, "Referencing a memory must record that it was used")
    }
}
