package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.database.Messages
import com.pinkdreams.persistence.database.defaultNow
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 3 — SLA / Performance Data Model.
 *
 * Proves the aggregation layer computes correct percentiles, SLA bucket
 * counts, error rates, and dimension breakdowns against known, hand-picked
 * latency values recorded through the exact same [LlmExchangeRepository]
 * write path Task 2 uses — no separate/duplicated write path being tested.
 */
class PerformanceMetricsRepositoryTest {

    private fun db(): Database = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }

    private fun record(
        exchangeRepo: LlmExchangeRepository,
        latencyMs: Long,
        workload: String = "primary_generation",
        model: String = "test-model",
        provider: String = "TestProvider",
        outcome: LlmExchangeRepository.Outcome = LlmExchangeRepository.Outcome.SUCCESS,
        conversationId: UUID = UUID.randomUUID(),
        turnRequestId: UUID = UUID.randomUUID(),
        skillKey: String? = null,
    ) {
        exchangeRepo.record(
            LlmExchangeRepository.RecordInput(
                turnRequestId = turnRequestId,
                conversationId = conversationId,
                workload = workload,
                isTestChat = false,
                model = model,
                provider = provider,
                latencyMs = latencyMs,
                outcome = outcome,
                skillKey = skillKey,
            ),
        )
    }

    @Test
    fun `percentiles match hand-computed nearest-rank values`() {
        val database = db()
        val exchangeRepo = LlmExchangeRepository(database)
        val metrics = PerformanceMetricsRepository(database)
        // 1..10 seconds in 1s steps -> sorted [1000..10000], n=10
        (1..10).forEach { record(exchangeRepo, it * 1000L) }

        val stats = metrics.latencyStats()

        assertEquals(10L, stats.count)
        // nearest-rank: p50 -> ceil(0.50*10)=5th value=5000; p95 -> ceil(9.5)=10th=10000
        assertEquals(5000L, stats.p50)
        assertEquals(8000L, stats.p75)
        assertEquals(10000L, stats.p95)
        assertEquals(10000L, stats.p99)
        assertEquals(10000L, stats.max)
        assertEquals(5500.0, stats.avg)
    }

    @Test
    fun `SLA buckets count correctly across the boundary values`() {
        val database = db()
        val exchangeRepo = LlmExchangeRepository(database)
        val metrics = PerformanceMetricsRepository(database)
        listOf(4000L, 5000L, 7000L, 8000L, 9500L, 10000L, 15000L, 25000L).forEach { record(exchangeRepo, it) }

        val buckets = metrics.slaBuckets()

        assertEquals(8L, buckets.total)
        assertEquals(2L, buckets.le5s) // 4000, 5000
        assertEquals(4L, buckets.le8s) // + 7000, 8000
        assertEquals(6L, buckets.le10s) // + 9500, 10000
        assertEquals(2L, buckets.gt10s) // 15000, 25000
        assertEquals(1L, buckets.gt20s) // 25000
        assertEquals(75.0, buckets.within10sRate)
    }

    @Test
    fun `error rate distinguishes outcome types`() {
        val database = db()
        val exchangeRepo = LlmExchangeRepository(database)
        val metrics = PerformanceMetricsRepository(database)
        record(exchangeRepo, 1000, outcome = LlmExchangeRepository.Outcome.SUCCESS)
        record(exchangeRepo, 1000, outcome = LlmExchangeRepository.Outcome.SUCCESS)
        record(exchangeRepo, 1000, outcome = LlmExchangeRepository.Outcome.BUDGET_EXHAUSTION)
        record(exchangeRepo, 1000, outcome = LlmExchangeRepository.Outcome.PROVIDER_ERROR)

        val rate = metrics.errorRate()

        assertEquals(4L, rate.total)
        assertEquals(2L, rate.success)
        assertEquals(1L, rate.budgetExhaustion)
        assertEquals(1L, rate.providerError)
        assertEquals(50.0, rate.failureRate)
    }

    @Test
    fun `stats by workload separates intent from generation`() {
        val database = db()
        val exchangeRepo = LlmExchangeRepository(database)
        val metrics = PerformanceMetricsRepository(database)
        record(exchangeRepo, 2000, workload = "intent_discovery")
        record(exchangeRepo, 3000, workload = "intent_discovery")
        record(exchangeRepo, 9000, workload = "primary_generation")

        val byWorkload = metrics.statsByWorkload()

        assertEquals(2L, byWorkload.getValue("intent_discovery").latency.count)
        assertEquals(2500.0, byWorkload.getValue("intent_discovery").latency.avg)
        assertEquals(1L, byWorkload.getValue("primary_generation").latency.count)
        assertEquals(9000L, byWorkload.getValue("primary_generation").latency.p50)
    }

    @Test
    fun `stats by provider separates upstream hosts`() {
        val database = db()
        val exchangeRepo = LlmExchangeRepository(database)
        val metrics = PerformanceMetricsRepository(database)
        record(exchangeRepo, 1000, provider = "CoreWeave")
        record(exchangeRepo, 50000, provider = "Wafer")

        val byProvider = metrics.statsByProvider()

        assertEquals(1000L, byProvider.getValue("CoreWeave").latency.max)
        assertEquals(50000L, byProvider.getValue("Wafer").latency.max)
    }

    @Test
    fun `filter narrows results to a single conversation`() {
        val database = db()
        val exchangeRepo = LlmExchangeRepository(database)
        val metrics = PerformanceMetricsRepository(database)
        val targetConversation = UUID.randomUUID()
        record(exchangeRepo, 1000, conversationId = targetConversation)
        record(exchangeRepo, 99000, conversationId = UUID.randomUUID())

        val stats = metrics.latencyStats(PerformanceMetricsRepository.MetricsFilter(conversationId = targetConversation))

        assertEquals(1L, stats.count)
        assertEquals(1000L, stats.max)
    }

    @Test
    fun `turn breakdown reconstructs every LLM call for one turn in order`() {
        val database = db()
        val exchangeRepo = LlmExchangeRepository(database)
        val metrics = PerformanceMetricsRepository(database)
        val turnId = UUID.randomUUID()
        val conversationId = UUID.randomUUID()
        record(exchangeRepo, 2000, workload = "intent_discovery", turnRequestId = turnId, conversationId = conversationId)
        record(exchangeRepo, 4000, workload = "primary_generation", turnRequestId = turnId, conversationId = conversationId)
        record(exchangeRepo, 3000, workload = "memory_extraction", turnRequestId = turnId, conversationId = conversationId)

        val breakdown = metrics.turnBreakdown(turnId)!!

        assertEquals(3, breakdown.exchanges.size)
        assertEquals(listOf("intent_discovery", "primary_generation", "memory_extraction"), breakdown.exchanges.map { it.workload })
        // on-path excludes the async memory_extraction side-channel by default
        assertEquals(6000L, breakdown.onPathLatencyMs())
    }

    @Test
    fun `empty result set returns null percentiles not a crash`() {
        val database = db()
        val metrics = PerformanceMetricsRepository(database)

        val stats = metrics.latencyStats()

        assertEquals(0L, stats.count)
        assertEquals(null, stats.p50)
        assertEquals(null, stats.avg)
    }

    // --- Task 8 Part 4 — skill attribution ---

    @Test
    fun `statsBySkill groups by the attributed skill and merges None with pre-attribution rows under 'none'`() {
        val database = db()
        val exchangeRepo = LlmExchangeRepository(database)
        val metrics = PerformanceMetricsRepository(database)
        record(exchangeRepo, 1000, skillKey = "flirting")
        record(exchangeRepo, 3000, skillKey = "flirting")
        record(exchangeRepo, 2000, skillKey = "emotional_support")
        record(exchangeRepo, 5000, skillKey = null) // SkillSelection.None or pre-Task-8

        val bySkill = metrics.statsBySkill()

        assertEquals(2, bySkill["flirting"]!!.latency.count)
        // nearest-rank over sorted [1000, 3000]: ceil(0.50*2)=1st value=1000
        assertEquals(1000L, bySkill["flirting"]!!.latency.p50)
        assertEquals(1, bySkill["emotional_support"]!!.latency.count)
        assertEquals(1, bySkill["none"]!!.latency.count)
    }

    @Test
    fun `filtering by skillKey narrows every aggregate to that skill only`() {
        val database = db()
        val exchangeRepo = LlmExchangeRepository(database)
        val metrics = PerformanceMetricsRepository(database)
        record(exchangeRepo, 1000, skillKey = "flirting")
        record(exchangeRepo, 9000, skillKey = "dating")

        val stats = metrics.latencyStats(PerformanceMetricsRepository.MetricsFilter(skillKey = "flirting"))

        assertEquals(1L, stats.count)
        assertEquals(1000L, stats.p50)
    }

    @Test
    fun `filtering by outcome narrows the result set to that outcome only`() {
        val database = db()
        val exchangeRepo = LlmExchangeRepository(database)
        val metrics = PerformanceMetricsRepository(database)
        record(exchangeRepo, 1000, outcome = LlmExchangeRepository.Outcome.SUCCESS)
        record(exchangeRepo, 2000, outcome = LlmExchangeRepository.Outcome.PROVIDER_ERROR)

        val stats = metrics.latencyStats(PerformanceMetricsRepository.MetricsFilter(outcome = "PROVIDER_ERROR"))

        assertEquals(1L, stats.count)
        assertEquals(2000L, stats.p50)
    }

    @Test
    fun `findExchanges preserves skillKey for export`() {
        val database = db()
        val exchangeRepo = LlmExchangeRepository(database)
        val metrics = PerformanceMetricsRepository(database)
        record(exchangeRepo, 1000, skillKey = "dating")

        val exchanges = metrics.findExchanges()

        assertEquals("dating", exchanges.single().skillKey)
    }

    // --- Task 8 Part 6 — stage timings, read from the existing messages.metadata blob ---

    @Test
    fun `stageTimingsForTurn reads the existing lvm_stage_timings metadata for the assistant message of that turn`() {
        val database = db()
        val metrics = PerformanceMetricsRepository(database)
        val turnId = UUID.randomUUID()
        insertAssistantMessage(
            database,
            requestId = turnId,
            metadataJson = """{"lvm_stage_timings":"{\"context_assembly\":\"12\",\"generation\":\"2048\"}"}""",
        )

        val stageTimings = metrics.stageTimingsForTurn(turnId)

        assertEquals(mapOf("context_assembly" to 12L, "generation" to 2048L), stageTimings)
    }

    @Test
    fun `stageTimingsForTurn returns null when no assistant message exists for the turn`() {
        val database = db()
        val metrics = PerformanceMetricsRepository(database)

        assertNull(metrics.stageTimingsForTurn(UUID.randomUUID()))
    }

    @Test
    fun `stageTimingsForTurn returns null rather than crashing on legacy metadata with no stage timings key`() {
        val database = db()
        val metrics = PerformanceMetricsRepository(database)
        val turnId = UUID.randomUUID()
        insertAssistantMessage(database, requestId = turnId, metadataJson = """{"lvm_config":"{}"}""")

        assertNull(metrics.stageTimingsForTurn(turnId))
    }

    private fun insertAssistantMessage(database: Database, requestId: UUID, metadataJson: String) {
        transaction(database) {
            Messages.insert {
                it[Messages.id] = UUID.randomUUID()
                it[Messages.conversationId] = UUID.randomUUID()
                it[Messages.role] = "assistant"
                it[Messages.content] = "reply"
                it[Messages.requestId] = requestId
                it[Messages.metadata] = metadataJson
                it[Messages.createdAt] = defaultNow()
            }
        }
    }

    // --- Task 10 — per-dimension SLA buckets / error rate ---

    @Test
    fun `per-dimension stats carry the same SLA buckets and error rate as the overall aggregation`() {
        val database = db()
        val exchangeRepo = LlmExchangeRepository(database)
        val metrics = PerformanceMetricsRepository(database)
        record(exchangeRepo, 4000, workload = "primary_generation")
        record(exchangeRepo, 12000, workload = "primary_generation")
        record(exchangeRepo, 25000, workload = "primary_generation", outcome = LlmExchangeRepository.Outcome.PROVIDER_ERROR)

        val byWorkload = metrics.statsByWorkload()
        val dim = byWorkload.getValue("primary_generation")

        assertEquals(3L, dim.latency.count)
        assertEquals(1L, dim.sla.le5s)
        assertEquals(2L, dim.sla.gt10s)
        assertEquals(1L, dim.sla.gt20s)
        assertEquals(3L, dim.sla.total)
        assertEquals(1L, dim.errorRate.providerError)
        // 1 failure out of 3 -> 33.33...%
        assertTrue(dim.errorRate.failureRate > 33.0 && dim.errorRate.failureRate < 34.0)
    }

    @Test
    fun `per-provider stats isolate SLA buckets to that provider only`() {
        val database = db()
        val exchangeRepo = LlmExchangeRepository(database)
        val metrics = PerformanceMetricsRepository(database)
        record(exchangeRepo, 15000, provider = "SlowHost")
        record(exchangeRepo, 1000, provider = "FastHost")

        val byProvider = metrics.statsByProvider()

        assertEquals(1L, byProvider.getValue("SlowHost").sla.gt10s)
        assertEquals(0L, byProvider.getValue("FastHost").sla.gt10s)
        assertEquals(1L, byProvider.getValue("FastHost").sla.le5s)
    }

    // --- Task 10 Step 11 — Slow Turn Explorer ---

    @Test
    fun `slowTurns returns only turns whose on-path latency exceeds the threshold`() {
        val database = db()
        val exchangeRepo = LlmExchangeRepository(database)
        val metrics = PerformanceMetricsRepository(database)
        val slowTurn = UUID.randomUUID()
        val fastTurn = UUID.randomUUID()
        record(exchangeRepo, 6000, workload = "intent_discovery", turnRequestId = slowTurn)
        record(exchangeRepo, 6000, workload = "primary_generation", turnRequestId = slowTurn) // on-path total 12000
        record(exchangeRepo, 1000, workload = "intent_discovery", turnRequestId = fastTurn)
        record(exchangeRepo, 1000, workload = "primary_generation", turnRequestId = fastTurn) // on-path total 2000

        val slow = metrics.slowTurns(minOnPathLatencyMs = 10_000)

        assertEquals(1, slow.size)
        assertEquals(slowTurn, slow.single().turnRequestId)
        assertEquals(12000L, slow.single().onPathLatencyMs)
    }

    @Test
    fun `slowTurns excludes async side-channel latency from the on-path total`() {
        val database = db()
        val exchangeRepo = LlmExchangeRepository(database)
        val metrics = PerformanceMetricsRepository(database)
        val turnId = UUID.randomUUID()
        record(exchangeRepo, 3000, workload = "intent_discovery", turnRequestId = turnId)
        record(exchangeRepo, 3000, workload = "primary_generation", turnRequestId = turnId) // on-path total 6000
        record(exchangeRepo, 30000, workload = "memory_extraction", turnRequestId = turnId) // async, must not count

        val slow = metrics.slowTurns(minOnPathLatencyMs = 5_000)

        assertEquals(1, slow.size, "The turn's on-path total (6000ms) exceeds the threshold even though the async call alone is much larger")
        assertEquals(6000L, slow.single().onPathLatencyMs, "Async memory_extraction latency must never be added to the on-path/SLA figure")
    }

    @Test
    fun `slowTurns carries skill and model-provider attribution for the generation exchange`() {
        val database = db()
        val exchangeRepo = LlmExchangeRepository(database)
        val metrics = PerformanceMetricsRepository(database)
        val turnId = UUID.randomUUID()
        exchangeRepo.record(
            LlmExchangeRepository.RecordInput(
                turnRequestId = turnId, conversationId = UUID.randomUUID(), workload = "intent_discovery",
                isTestChat = false, model = "openai/gpt-4o-mini", provider = "OpenAI", latencyMs = 6000,
                outcome = LlmExchangeRepository.Outcome.SUCCESS,
            ),
        )
        exchangeRepo.record(
            LlmExchangeRepository.RecordInput(
                turnRequestId = turnId, conversationId = UUID.randomUUID(), workload = "primary_generation",
                isTestChat = false, model = "deepseek/deepseek-v4-flash-0731", provider = "CoreWeave", latencyMs = 6000,
                outcome = LlmExchangeRepository.Outcome.SUCCESS, skillKey = "flirting",
            ),
        )

        val slow = metrics.slowTurns(minOnPathLatencyMs = 10_000).single()

        assertEquals("flirting", slow.skillKey)
        assertEquals("openai/gpt-4o-mini", slow.intentModel)
        assertEquals("deepseek/deepseek-v4-flash-0731", slow.generationModel)
        assertEquals("CoreWeave", slow.generationProvider)
    }

    @Test
    fun `slowTurns returns an empty list rather than crashing when nothing exceeds the threshold`() {
        val database = db()
        val exchangeRepo = LlmExchangeRepository(database)
        val metrics = PerformanceMetricsRepository(database)
        record(exchangeRepo, 1000)

        assertTrue(metrics.slowTurns(minOnPathLatencyMs = 10_000).isEmpty())
    }

    /**
     * Task 03 audit fix. The admin Observability filter bar labels these controls
     * "Model contains" / "Provider contains" and suggests a partial value
     * ("e.g. deepseek"), but they were matched with strict equality — so the
     * control's own documented example returned zero rows against real model ids
     * like "deepseek/deepseek-v4-flash-0731". These assertions pin the
     * substring semantics the UI advertises, and pin that an exact value still
     * matches itself (the change is a strict widening, never a redefinition).
     */
    @Test
    fun `model and provider filters match on substring as the admin UI advertises`() {
        val database = db()
        val exchangeRepo = LlmExchangeRepository(database)
        val metrics = PerformanceMetricsRepository(database)
        record(exchangeRepo, 1000, model = "deepseek/deepseek-v4-flash-0731", provider = "CoreWeave")
        record(exchangeRepo, 2000, model = "deepseek/deepseek-v4-flash-0731", provider = "DeepInfra")
        record(exchangeRepo, 3000, model = "openai/gpt-4o-mini", provider = "Azure")

        // The UI's own placeholder value must actually select rows.
        assertEquals(2, metrics.latencyStats(PerformanceMetricsRepository.MetricsFilter(model = "deepseek")).count)
        // Case-insensitive, as a free-text "contains" box implies.
        assertEquals(2, metrics.latencyStats(PerformanceMetricsRepository.MetricsFilter(model = "DEEPSEEK")).count)
        // A full, exact id still matches exactly itself — strict widening.
        assertEquals(
            1,
            metrics.latencyStats(PerformanceMetricsRepository.MetricsFilter(model = "openai/gpt-4o-mini")).count,
        )
        // Provider behaves identically.
        assertEquals(1, metrics.latencyStats(PerformanceMetricsRepository.MetricsFilter(provider = "core")).count)
        assertEquals(0, metrics.latencyStats(PerformanceMetricsRepository.MetricsFilter(provider = "nosuch")).count)
        // Combined with another dimension the filters still AND together.
        assertEquals(
            1,
            metrics.latencyStats(
                PerformanceMetricsRepository.MetricsFilter(model = "deepseek", provider = "DeepInfra"),
            ).count,
        )
    }

    /**
     * A LIKE wildcard typed into the box must be matched as a literal character,
     * never as a pattern — otherwise "%" would silently select every row and an
     * admin would read a filtered dashboard that was not filtered at all.
     */
    @Test
    fun `wildcard characters in a model filter are matched literally`() {
        val database = db()
        val exchangeRepo = LlmExchangeRepository(database)
        val metrics = PerformanceMetricsRepository(database)
        record(exchangeRepo, 1000, model = "deepseek/deepseek-v4-flash-0731")
        record(exchangeRepo, 2000, model = "openai/gpt-4o-mini")
        record(exchangeRepo, 3000, model = "literal%percent")

        assertEquals(1, metrics.latencyStats(PerformanceMetricsRepository.MetricsFilter(model = "%")).count)
        assertEquals(1, metrics.latencyStats(PerformanceMetricsRepository.MetricsFilter(model = "l%p")).count)
        assertEquals(0, metrics.latencyStats(PerformanceMetricsRepository.MetricsFilter(model = "_")).count)
    }
}
