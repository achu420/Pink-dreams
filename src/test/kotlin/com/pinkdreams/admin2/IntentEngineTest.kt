package com.pinkdreams.admin2

import com.pinkdreams.baseline.BaselineConfiguration
import com.pinkdreams.baseline.BaselineSeeder
import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ChatRequest
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.skill.IntentEngineDefaultContent
import com.pinkdreams.chat.skill.LlmIntentDiscovery
import com.pinkdreams.chat.skill.SkillSelection
import com.pinkdreams.llm.GenerationRequest
import com.pinkdreams.llm.LlmClient
import com.pinkdreams.llm.LlmResponse
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.IntentEngineRepository
import com.pinkdreams.persistence.repositories.MemoryEngineRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase ADMIN-2 sections 13-18, 31, 34: the Intent Engine as its own versioned
 * configuration, and intent discovery reading its rules from the active
 * version rather than from Kotlin.
 */
class IntentEngineTest {

    private class Fixture {
        val db: Database = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }
        val intentEngines = IntentEngineRepository(db)
        val skills = SkillRepository(db)

        fun seedAndActivateSkills() {
            BaselineSeeder(
                ConversationEngineRepository(db), PersonaRepository(db), PersonaCoreVersionRepository(db),
                skills, MemoryEngineRepository(db), intentEngines,
            ).seedIfMissing()
            skills.findAll().forEach { skills.activate(skills.publish(it.id).id) }
        }
    }

    private class CapturingClient(private val body: String) : LlmClient {
        var promptSeen: String? = null
        override fun generate(request: GenerationRequest): LlmResponse {
            promptSeen = request.context.blocks.first().content
            return LlmResponse(content = body, provider = "test")
        }
    }

    private fun requestAndContext(message: String): Pair<ChatRequest, ChatContext> {
        val request = ChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), message)
        val context = ChatContext(
            blocks = listOf(ContextBlock("system", "engine"), ContextBlock("user", message)),
            engineVersionId = UUID.randomUUID(),
            personaCoreVersionId = UUID.randomUUID(),
        )
        return request to context
    }

    // ---------- section 31: lifecycle ----------

    @Test
    fun `create publish activate archive follows the same lifecycle as the other engines`() {
        val f = Fixture()

        val draft = f.intentEngines.createNextVersion("rules v1", changelogNote = "first", createdBy = "admin")
        assertEquals(1, draft.version)
        assertEquals("draft", draft.status)
        assertFalse(draft.isActive)
        assertNull(f.intentEngines.getActiveEngine(), "A draft must never be active")

        val published = f.intentEngines.publish(draft.id)
        assertEquals("published", published.status)
        assertFalse(published.isActive)

        val active = f.intentEngines.activate(published.id)
        assertTrue(active.isActive)
        assertEquals(active.id, f.intentEngines.getActiveEngine()?.id)

        assertFailsWith<IllegalStateException>("An active version must not be archivable") {
            f.intentEngines.archive(active.id)
        }
    }

    @Test
    fun `activating a new version deactivates the previous one`() {
        val f = Fixture()
        val v1 = f.intentEngines.activate(f.intentEngines.publish(f.intentEngines.createNextVersion("v1").id).id)
        val v2 = f.intentEngines.activate(f.intentEngines.publish(f.intentEngines.createNextVersion("v2").id).id)

        assertEquals(v2.id, f.intentEngines.getActiveEngine()?.id)
        assertFalse(f.intentEngines.findById(v1.id)!!.isActive, "At most one version may be active")
        assertEquals(1, f.intentEngines.findAll().count { it.isActive })
    }

    @Test
    fun `version numbers are server computed and sequential`() {
        val f = Fixture()
        val versions = (1..4).map { f.intentEngines.createNextVersion("content $it").version }
        assertEquals(listOf(1, 2, 3, 4), versions)
    }

    @Test
    fun `only draft can be published and only published can be activated`() {
        val f = Fixture()
        val draft = f.intentEngines.createNextVersion("v1")
        assertFailsWith<IllegalArgumentException> { f.intentEngines.activate(draft.id) }
        val published = f.intentEngines.publish(draft.id)
        assertFailsWith<IllegalArgumentException> { f.intentEngines.publish(published.id) }
    }

    // ---------- sections 13-15: runtime loads the ACTIVE version ----------

    @Test
    fun `intent discovery uses the active intent engine content not a kotlin string`() {
        val f = Fixture()
        f.seedAndActivateSkills()
        val custom = """
            CUSTOM ADMIN AUTHORED RULES
            ${IntentEngineDefaultContent.ACTIVE_SKILL_KEYS_PLACEHOLDER}
            Return {"skillKey": null} when unsure.
        """.trimIndent()
        f.intentEngines.activate(f.intentEngines.publish(f.intentEngines.createNextVersion(custom).id).id)

        val client = CapturingClient("""{"skillKey": "general_chat"}""")
        val discovery = LlmIntentDiscovery(client, f.skills, intentEngineRepository = f.intentEngines)
        val (request, context) = requestAndContext("hello")

        assertEquals(SkillSelection.Selected("general_chat"), discovery.selectSkill(request, context))
        assertTrue(client.promptSeen!!.contains("CUSTOM ADMIN AUTHORED RULES"), "The stored version must be what is sent")
    }

    @Test
    fun `activating a new intent engine version changes the prompt on the next turn`() {
        val f = Fixture()
        f.seedAndActivateSkills()
        f.intentEngines.activate(f.intentEngines.publish(f.intentEngines.createNextVersion("RULES ALPHA").id).id)

        val client = CapturingClient("""{"skillKey": "general_chat"}""")
        val discovery = LlmIntentDiscovery(client, f.skills, intentEngineRepository = f.intentEngines)
        val (request, context) = requestAndContext("hello")

        discovery.selectSkill(request, context)
        assertTrue(client.promptSeen!!.contains("RULES ALPHA"))

        // No restart, no re-construction of the discovery component.
        f.intentEngines.activate(f.intentEngines.publish(f.intentEngines.createNextVersion("RULES BETA").id).id)
        discovery.selectSkill(request, context)

        assertTrue(client.promptSeen!!.contains("RULES BETA"), "A newly activated version must apply immediately")
        assertFalse(client.promptSeen!!.contains("RULES ALPHA"))
    }

    @Test
    fun `candidate keys are supplied by the runtime and never authored into the prompt`() {
        val f = Fixture()
        f.seedAndActivateSkills()
        f.intentEngines.activate(f.intentEngines.publish(f.intentEngines.createNextVersion(IntentEngineDefaultContent.CONTENT).id).id)

        val client = CapturingClient("""{"skillKey": "flirting"}""")
        LlmIntentDiscovery(client, f.skills, intentEngineRepository = f.intentEngines)
            .selectSkill(requestAndContext("hi").first, requestAndContext("hi").second)

        val prompt = client.promptSeen!!
        assertFalse(
            prompt.contains(IntentEngineDefaultContent.ACTIVE_SKILL_KEYS_PLACEHOLDER),
            "The placeholder must be substituted, never sent to the model",
        )
        val candidateLine = prompt.lineSequence()
            .dropWhile { !it.startsWith("ACTIVE SKILL KEYS") }
            .drop(1).first { it.isNotBlank() }
        assertEquals(
            BaselineConfiguration.SKILLS.map { it.key }.toSet(),
            candidateLine.split(",").map { it.trim() }.toSet(),
        )
    }

    @Test
    fun `an edited prompt without the placeholder still receives the candidate keys`() {
        val f = Fixture()
        f.seedAndActivateSkills()
        // An admin has rewritten the prompt and dropped the placeholder.
        f.intentEngines.activate(f.intentEngines.publish(f.intentEngines.createNextVersion("Pick one skill.").id).id)

        val client = CapturingClient("""{"skillKey": "dating"}""")
        val discovery = LlmIntentDiscovery(client, f.skills, intentEngineRepository = f.intentEngines)
        val (request, context) = requestAndContext("dinner sometime?")

        assertEquals(SkillSelection.Selected("dating"), discovery.selectSkill(request, context))
        assertTrue(
            client.promptSeen!!.contains("ACTIVE SKILL KEYS"),
            "The model must never be left without the list of keys it may return",
        )
        assertTrue(client.promptSeen!!.contains("dating"))
    }

    @Test
    fun `no active intent engine disables selection without calling the llm`() {
        // Skills active, but no Intent Engine version has been activated.
        val f = Fixture()
        val draft = f.skills.createNextVersion("general_chat", "SKILL: GENERAL_CHAT")
        f.skills.activate(f.skills.publish(draft.id).id)
        assertNull(f.intentEngines.getActiveEngine())

        val client = CapturingClient("""{"skillKey": "general_chat"}""")
        val discovery = LlmIntentDiscovery(client, f.skills, intentEngineRepository = f.intentEngines)

        assertEquals(
            SkillSelection.None,
            discovery.selectSkill(requestAndContext("hi").first, requestAndContext("hi").second),
            "With no active Intent Engine, discovery degrades to None rather than guessing",
        )
        assertNull(client.promptSeen, "No LLM call may be made when there is no active Intent Engine")
    }

    @Test
    fun `seeding bootstraps and activates an intent engine so skill selection keeps working`() {
        // Section 38 / production safety: intent_engines is a new table, so every
        // database starts empty and the seed must activate, not merely draft —
        // otherwise deploying this phase would silently disable all skills.
        val f = Fixture()
        f.seedAndActivateSkills()

        val active = f.intentEngines.getActiveEngine()
        assertNotNull(active, "Seeding an empty intent_engines table must yield an ACTIVE version")
        assertEquals(IntentEngineDefaultContent.CONTENT, active.content)
    }

    @Test
    fun `reseeding never creates a second intent engine version`() {
        val f = Fixture()
        val seeder = BaselineSeeder(
            ConversationEngineRepository(f.db), PersonaRepository(f.db), PersonaCoreVersionRepository(f.db),
            f.skills, MemoryEngineRepository(f.db), f.intentEngines,
        )
        repeat(4) { seeder.seedIfMissing() }

        assertEquals(1, f.intentEngines.findAll().size)
        assertEquals(1, f.intentEngines.findAll().count { it.isActive })
    }

    @Test
    fun `seeding never overwrites a hand-edited active intent engine`() {
        val f = Fixture()
        val handEdited = f.intentEngines.createNextVersion("HAND EDITED INTENT RULES")
        f.intentEngines.activate(f.intentEngines.publish(handEdited.id).id)

        BaselineSeeder(
            ConversationEngineRepository(f.db), PersonaRepository(f.db), PersonaCoreVersionRepository(f.db),
            f.skills, MemoryEngineRepository(f.db), f.intentEngines,
        ).seedIfMissing()

        assertEquals("HAND EDITED INTENT RULES", f.intentEngines.getActiveEngine()!!.content)
        assertTrue(
            f.intentEngines.findAll().any { it.content == IntentEngineDefaultContent.CONTENT && it.status == "draft" },
            "The canonical version should still be offered as a draft for review",
        )
    }

    // ---------- sections 16, 34: output contract ----------

    @Test
    fun `an unknown or invented skill key is rejected`() {
        val f = Fixture()
        f.seedAndActivateSkills()
        f.intentEngines.activate(f.intentEngines.publish(f.intentEngines.createNextVersion(IntentEngineDefaultContent.CONTENT).id).id)

        listOf("""{"skillKey": "therapy_session"}""", """{"skillKey": "COMPLETELY_MADE_UP"}""").forEach { body ->
            val discovery = LlmIntentDiscovery(CapturingClient(body), f.skills, intentEngineRepository = f.intentEngines)
            assertEquals(
                SkillSelection.None, discovery.selectSkill(requestAndContext("x").first, requestAndContext("x").second),
                "An key outside the active candidates must never be selected",
            )
        }
    }

    @Test
    fun `null and malformed responses both resolve to None`() {
        val f = Fixture()
        f.seedAndActivateSkills()
        f.intentEngines.activate(f.intentEngines.publish(f.intentEngines.createNextVersion(IntentEngineDefaultContent.CONTENT).id).id)

        listOf("""{"skillKey": null}""", "not json at all", "", """{"wrong": "shape"}""").forEach { body ->
            val discovery = LlmIntentDiscovery(CapturingClient(body), f.skills, intentEngineRepository = f.intentEngines)
            assertEquals(
                SkillSelection.None, discovery.selectSkill(requestAndContext("x").first, requestAndContext("x").second),
                "Response '$body' should resolve to None",
            )
        }
    }

    @Test
    fun `each baseline skill key is selectable when the engine returns it`() {
        val f = Fixture()
        f.seedAndActivateSkills()
        f.intentEngines.activate(f.intentEngines.publish(f.intentEngines.createNextVersion(IntentEngineDefaultContent.CONTENT).id).id)

        BaselineConfiguration.SKILLS.forEach { seed ->
            val discovery = LlmIntentDiscovery(
                CapturingClient("""{"skillKey": "${seed.key}"}"""), f.skills, intentEngineRepository = f.intentEngines,
            )
            assertEquals(
                SkillSelection.Selected(seed.key),
                discovery.selectSkill(requestAndContext("x").first, requestAndContext("x").second),
                "${seed.key} must be selectable",
            )
        }
    }

    // ---------- section 40: stored prompt and parser must agree ----------

    @Test
    fun `the seeded prompt declares exactly the output shape the parser accepts`() {
        val content = IntentEngineDefaultContent.CONTENT
        assertTrue(content.contains("""{"skillKey": "..."}"""), "Prompt must declare the selected-key shape")
        assertTrue(content.contains("""{"skillKey": null}"""), "Prompt must declare the null shape")
        assertTrue(content.contains(IntentEngineDefaultContent.ACTIVE_SKILL_KEYS_PLACEHOLDER))
        // Section 17 — overlapping intents must be addressed explicitly.
        assertTrue(content.contains("DOMINANT INTENT WHEN SKILLS OVERLAP"))
        // Section 15 — it must not claim to generate a response or pick memories.
        assertTrue(content.contains("You are NOT generating the assistant's response"))
        assertTrue(content.contains("You are NOT selecting memories"))
    }

    @Test
    fun `the intent engine prompt is never injected into response generation`() {
        // Control-plane prompts (section 28) must not leak into the generation
        // context. The skill enricher and memory enricher are the only things
        // that add blocks after assembly, and neither knows about this text.
        val f = Fixture()
        f.seedAndActivateSkills()
        val generationContext = requestAndContext("hello").second

        assertTrue(
            generationContext.blocks.none { it.content.contains("Intent Discovery component") },
            "The Intent Engine prompt must never appear in the generation context",
        )
    }
}
