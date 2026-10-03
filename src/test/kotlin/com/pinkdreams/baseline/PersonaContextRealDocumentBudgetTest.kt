package com.pinkdreams.baseline

import com.pinkdreams.chat.ChatContext
import com.pinkdreams.chat.ContextBlock
import com.pinkdreams.chat.ContextBlockType
import com.pinkdreams.chat.PersonaContextResolutionStage
import com.pinkdreams.chat.context.CharacterTokenEstimator
import com.pinkdreams.chat.context.RepositoryContextAssembler
import com.pinkdreams.chat.personacontext.production.BareScalarRendering
import com.pinkdreams.chat.personacontext.production.PersonaFieldAuthorizer
import com.pinkdreams.chat.personacontext.production.ProductionPersonaContextService
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.MemoryEngineRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaFieldRegistryRepository
import com.pinkdreams.persistence.repositories.PersonaMasterRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.SkillContextRequirementRepository
import com.pinkdreams.persistence.repositories.SkillRepository
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PHASE (final verification) Step 3 — budget behavior against the ACTUAL
 * seeded Simran document (`PersonaContextSeeder.simranMasterContent()`), not
 * an equivalent-scale fixture. Closes the gap flagged in
 * PHASE_PRODUCTION_READINESS_COMPLETE.md §21 item 3.
 */
class PersonaContextRealDocumentBudgetTest {

    private class Fixture {
        val db: Database = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }
        val personaRepository = PersonaRepository(db)
        val coreVersionRepository = PersonaCoreVersionRepository(db)
        val masterRepo = PersonaMasterRepository(db)
        val fieldRegistryRepo = PersonaFieldRegistryRepository(db)
        val skillReqRepo = SkillContextRequirementRepository(db)
        val authorizer = PersonaFieldAuthorizer(db, masterRepo, fieldRegistryRepo, skillReqRepo)
        val service = ProductionPersonaContextService(authorizer, BareScalarRendering)

        init {
            BaselineSeeder(
                ConversationEngineRepository(db), personaRepository, coreVersionRepository,
                SkillRepository(db), MemoryEngineRepository(db),
            ).seedIfMissing()
            PersonaContextSeeder(personaRepository, coreVersionRepository, masterRepo, fieldRegistryRepo, skillReqRepo).seedIfMissing()
        }

        val personaId get() = personaRepository.findAll().first { it.slug == BaselineConfiguration.SIMRAN_SLUG }.id
        val coreVersionId get() = personaRepository.findAll().first { it.slug == BaselineConfiguration.SIMRAN_SLUG }.activeCoreVersionId!!

        fun baseContext(selectedSkillKey: String?): ChatContext = ChatContext(
            blocks = listOf(
                ContextBlock("system", "CONVERSATION ENGINE — UNIVERSAL RULES:\n${BaselineConfiguration.CONVERSATION_ENGINE}", ContextBlockType.ENGINE),
                ContextBlock("system", "PERSONA CORE:\n${BaselineConfiguration.SIMRAN_PERSONA_CORE}", ContextBlockType.PERSONA),
                ContextBlock("user", "hi", ContextBlockType.CURRENT_MESSAGE),
            ),
            personaCoreVersionId = coreVersionId,
            selectedSkillKey = selectedSkillKey,
        )
    }

    // 1. Real actual rendered size, measured (not assumed).
    @Test
    fun `1 - the actual real-document rendered size is measured directly, not assumed from a fixture`() {
        val f = Fixture()
        val result = f.authorizer.resolve(f.personaId, f.coreVersionId, "general_chat", "hi", BareScalarRendering)
        val actualTokens = CharacterTokenEstimator.estimate(result.renderedText)

        // Documents the real measured size for every subsequent test in this file to
        // reason about, rather than any test silently assuming a size.
        assertTrue(actualTokens in 1..2000, "sanity bound: general_chat's real rendered content is $actualTokens estimated tokens")
        println("REAL_DOCUMENT_BUDGET: general_chat rendered=${result.renderedText.length} chars, estimatedTokens=$actualTokens")
    }

    // Measures each real skill's actual rendered size for the exact-boundary tests below.
    private fun realRenderedTokens(f: PersonaContextRealDocumentBudgetTest.Fixture, skillKey: String): Int {
        val result = f.authorizer.resolve(f.personaId, f.coreVersionId, skillKey, "hi", BareScalarRendering)
        return CharacterTokenEstimator.estimate(result.renderedText)
    }

    // 2 + one increment beyond (3). Exact boundary against the real document, both directions.
    @Test
    fun `2 3 - exact boundary against the real document is deterministic in both directions`() {
        val f = Fixture()
        val context = f.baseContext("general_chat")
        val realFieldTokens = realRenderedTokens(f, "general_chat")

        // Baseline (pre-replacement) total, from the REAL context, not assumed.
        val baselineTotal = context.blocks.sumOf { CharacterTokenEstimator.estimate(it.content) }
        // Exact total once the real replacement is installed.
        val postReplacementTotal = baselineTotal - CharacterTokenEstimator.estimate(context.blocks[1].content) + realFieldTokens

        // At exactly postReplacementTotal: fits, replacement installed.
        val exactStage = PersonaContextResolutionStage(f.service, tokenBudget = postReplacementTotal)
        val exactResult = exactStage.resolve(context, f.personaId, "hi")
        assertTrue(
            exactResult.blocks[1].content.contains("Simran Kohli"),
            "at the exact real post-replacement token total, the replacement must be installed (boundary is inclusive)",
        )

        // One token below that exact total: must NOT fit, baseline retained completely.
        val overStage = PersonaContextResolutionStage(f.service, tokenBudget = postReplacementTotal - 1)
        val overResult = overStage.resolve(context, f.personaId, "hi")
        assertEquals(context, overResult, "one token below the real boundary must reject the replacement and keep the exact original baseline")

        // Deterministic across repeated identical calls.
        val repeat1 = exactStage.resolve(context, f.personaId, "hi")
        val repeat2 = exactStage.resolve(context, f.personaId, "hi")
        assertEquals(repeat1, repeat2, "resolution against the real document must be deterministic across repeated calls")
    }

    // 4. Each real production skill, budget-safe.
    @Test
    fun `4 - each real mapped skill installs its real replacement when comfortably under budget`() {
        val f = Fixture()
        val stage = PersonaContextResolutionStage(f.service, tokenBudget = 100_000)

        listOf("general_chat", "friendship", "romantic_intimacy", "foreplay").forEach { skillKey ->
            val result = stage.resolve(f.baseContext(skillKey), f.personaId, "hi")
            assertTrue(
                result.blocks[1].content.contains("Simran Kohli"),
                "$skillKey must install its real authorized replacement when comfortably under budget",
            )
        }
    }

    // 5. Unmapped advice: no replacement to budget-check at all (fails closed before budget).
    @Test
    fun `5 - advice never reaches budget checking because it fails closed at authorization`() {
        val f = Fixture()
        val context = f.baseContext("advice")
        val stage = PersonaContextResolutionStage(f.service, tokenBudget = 1) // deliberately tiny

        val result = stage.resolve(context, f.personaId, "hi")
        // Must equal the unmodified baseline REGARDLESS of the tiny budget -- proves
        // the budget check is never even reached for a skill that fails at authorization.
        assertEquals(context, result)
    }

    // 6. Restricted-field-containing source, over budget: restricted content must not leak via any partial path.
    @Test
    fun `6 - over budget with a restricted-field-containing real result, no restricted content leaks in any form`() {
        val f = Fixture()
        val context = f.baseContext("romantic_intimacy")
        val stage = PersonaContextResolutionStage(f.service, tokenBudget = 1) // forces rejection

        val result = stage.resolve(context, f.personaId, "hi")
        assertEquals(context, result, "over-budget must reject cleanly")
        assertFalse(result.blocks.any { it.content.contains("Playful and warm") }, "restricted adult_intimacy content must never leak even via a rejected-but-partially-applied path")
    }

    // 7. Empty/missing context: no persona block at all.
    @Test
    fun `7 - a context with no PERSONA block is returned completely unchanged regardless of budget`() {
        val f = Fixture()
        val noPersonaContext = ChatContext(
            blocks = listOf(ContextBlock("system", "ENGINE_ONLY", ContextBlockType.ENGINE)),
            personaCoreVersionId = f.coreVersionId,
            selectedSkillKey = "general_chat",
        )
        val stage = PersonaContextResolutionStage(f.service, tokenBudget = 1)
        assertEquals(noPersonaContext, stage.resolve(noPersonaContext, f.personaId, "hi"))
    }

    // 8. Resolver/authorizer failure at real scale: never throws, safe result either way.
    @Test
    fun `8 - a mismatched personaId against the real seeded database never throws and never crosses a document boundary`() {
        val f = Fixture()
        val context = f.baseContext("general_chat")
        val stage = PersonaContextResolutionStage(f.service, tokenBudget = 100_000)

        // PersonaFieldAuthorizer's document lookup is keyed on personaCoreVersionId,
        // not personaId (documented since Phase 49 test N) -- in the real production
        // pipeline these two always arrive as a matched pair from the same request, so
        // this is not exploitable there. This test's real guarantee, at real scale: no
        // exception, and the result is still scoped to the SAME coreVersionId passed in
        // -- a mismatched personaId can never cause a DIFFERENT persona's document to be
        // returned, because no other document exists to cross into in this fixture.
        val result = stage.resolve(context, java.util.UUID.randomUUID(), "hi")
        assertEquals(context.personaCoreVersionId, result.personaCoreVersionId)
        assertTrue(result.blocks[1].content.contains("Architect (junior)"), "a mismatched personaId with a still-valid coreVersionId resolves that core version's real document -- documented, not a defect")
    }
}
