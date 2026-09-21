package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.DatabaseFactory
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

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

        assertEquals(2L, byWorkload.getValue("intent_discovery").count)
        assertEquals(2500.0, byWorkload.getValue("intent_discovery").avg)
        assertEquals(1L, byWorkload.getValue("primary_generation").count)
        assertEquals(9000L, byWorkload.getValue("primary_generation").p50)
    }

    @Test
    fun `stats by provider separates upstream hosts`() {
        val database = db()
        val exchangeRepo = LlmExchangeRepository(database)
        val metrics = PerformanceMetricsRepository(database)
        record(exchangeRepo, 1000, provider = "CoreWeave")
        record(exchangeRepo, 50000, provider = "Wafer")

        val byProvider = metrics.statsByProvider()

        assertEquals(1000L, byProvider.getValue("CoreWeave").max)
        assertEquals(50000L, byProvider.getValue("Wafer").max)
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
}
