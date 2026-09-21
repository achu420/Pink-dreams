package com.pinkdreams.chat

import com.pinkdreams.chat.memory.BestEffortMemoryExtraction
import com.pinkdreams.chat.memory.CompletedTurn
import com.pinkdreams.chat.memory.ExtractionResult
import com.pinkdreams.chat.memory.MemoryCandidate
import com.pinkdreams.chat.memory.MemoryExtractor
import com.pinkdreams.chat.memory.MemoryService
import com.pinkdreams.chat.memory.PostDeliveryMemoryExtraction
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runtime Quality + Latency Verification phase.
 *
 * These tests exercise the [PipelineChatEngine] stage-timing instrumentation
 * itself (item 1) and prove, with an artificially slow extractor rather than
 * a real network call, that async memory extraction genuinely never sits on
 * the user-facing critical path (item 6). Live per-stage millisecond
 * measurements against real OpenRouter are reported separately — a scripted
 * test cannot measure real provider latency, only prove the instrumentation
 * and the async boundary are correct.
 */
class RuntimeLatencyInstrumentationTest {

    private fun request() = ChatRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "hello")

    private fun engine(
        context: ContextAssembler = ContextAssembler { StageResult.Succeeded(ChatContext(emptyList())) },
        intentDiscovery: com.pinkdreams.chat.skill.IntentDiscovery = com.pinkdreams.chat.skill.NoopIntentDiscovery,
        generation: Generator = Generator { _, _ -> StageResult.Succeeded(GenerationResponse("reply")) },
        persistence: ChatPersistence,
        postDelivery: PostDeliveryMemoryExtraction = com.pinkdreams.chat.memory.NoopPostDeliveryMemoryExtraction,
    ): PipelineChatEngine = PipelineChatEngine(
        entitlementChecker = { EntitlementDecision.Allowed },
        inputModerator = { ModerationDecision.Allowed },
        contextAssembler = context,
        generator = generation,
        outputValidator = { _, _ -> ValidationDecision.Accepted },
        persistence = persistence,
        delivery = { _, _ -> StageResult.Succeeded(Unit) },
        intentDiscovery = intentDiscovery,
        postDeliveryMemoryExtraction = postDelivery,
    )

    // ---------- item 1: stage timings are captured and persisted ----------

    @Test
    fun `every user-facing stage records a measured duration`() {
        var capturedDiagnostics: LlmExecutionDiagnostics? = null
        val persistence = ChatPersistence { _, response ->
            capturedDiagnostics = response.executionDiagnostics
            StageResult.Succeeded(PersistedResponse(UUID.randomUUID(), response.content))
        }
        val e = engine(persistence = persistence)

        e.process(request())

        val timings = assertNotNull(capturedDiagnostics?.stageTimingsMs, "Stage timings must be persisted with the message")
        listOf("context_assembly", "intent_discovery", "memory_context_selection", "skill_context_enrichment", "generation", "total_before_persist")
            .forEach { stage -> assertTrue(timings.containsKey(stage), "Missing timing for stage '$stage'") }
        timings.values.forEach { v -> assertTrue(v.toLong() >= 0, "Duration must be a non-negative measured value, got $v") }
    }

    @Test
    fun `a deliberately slow generation call is reflected in its own stage timing`() {
        var capturedDiagnostics: LlmExecutionDiagnostics? = null
        val slowGeneration = Generator { _, _ ->
            Thread.sleep(120)
            StageResult.Succeeded(GenerationResponse("reply"))
        }
        val persistence = ChatPersistence { _, response ->
            capturedDiagnostics = response.executionDiagnostics
            StageResult.Succeeded(PersistedResponse(UUID.randomUUID(), response.content))
        }
        val e = engine(generation = slowGeneration, persistence = persistence)

        e.process(request())

        val generationMs = capturedDiagnostics!!.stageTimingsMs!!["generation"]!!.toLong()
        assertTrue(generationMs >= 100, "The slow generation call's own duration must show up in its stage, not be hidden elsewhere (got ${generationMs}ms)")
    }

    @Test
    fun `a deliberately slow intent discovery call is attributed to intent_discovery not generation`() {
        var capturedDiagnostics: LlmExecutionDiagnostics? = null
        val slowIntent = com.pinkdreams.chat.skill.IntentDiscovery { _, _ ->
            Thread.sleep(120)
            com.pinkdreams.chat.skill.SkillSelection.None
        }
        val persistence = ChatPersistence { _, response ->
            capturedDiagnostics = response.executionDiagnostics
            StageResult.Succeeded(PersistedResponse(UUID.randomUUID(), response.content))
        }
        val e = engine(intentDiscovery = slowIntent, persistence = persistence)

        e.process(request())

        val timings = capturedDiagnostics!!.stageTimingsMs!!
        assertTrue(timings["intent_discovery"]!!.toLong() >= 100, "Slow intent discovery must be attributed to its own stage")
        assertTrue(timings["generation"]!!.toLong() < 100, "Intent discovery's latency must not leak into the generation stage's measurement")
    }

    // ---------- item 6: async memory extraction never sits on the critical path ----------

    @Test
    fun `the user-facing response returns before a slow memory extractor even starts`() {
        val extractionStarted = CountDownLatch(1)
        val releaseExtraction = CountDownLatch(1)
        val slowExtractor = MemoryExtractor { turn ->
            extractionStarted.countDown()
            // Block until the test explicitly releases it, proving the response
            // path above already returned without waiting for this.
            releaseExtraction.await(5, TimeUnit.SECONDS)
            ExtractionResult(newFacts = emptyList())
        }
        val db = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }
        val memoryService = MemoryService(MemoryFactRepository(db))
        val postDelivery = BestEffortMemoryExtraction(slowExtractor, memoryService)

        val persistence = ChatPersistence { _, response -> StageResult.Succeeded(PersistedResponse(UUID.randomUUID(), response.content)) }
        val e = engine(persistence = persistence, postDelivery = postDelivery)

        val start = System.nanoTime()
        val result = e.process(request())
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertTrue(result is ChatResult.Success, "The turn must succeed even though extraction has not run yet")
        assertTrue(elapsedMs < 1000, "process() must return long before the 5s-capable extractor is released (took ${elapsedMs}ms)")
        // Extraction genuinely did start (asynchronously) — this rules out the
        // hook silently never firing, as distinct from it firing without blocking.
        assertTrue(extractionStarted.await(2, TimeUnit.SECONDS), "Extraction must actually dispatch, just not block the response")
        releaseExtraction.countDown()
    }

    @Test
    fun `a memory extractor that throws never affects the returned chat result`() {
        val failingExtractor = MemoryExtractor { throw RuntimeException("boom") }
        val db = DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) }
        val memoryService = MemoryService(MemoryFactRepository(db))
        val postDelivery = BestEffortMemoryExtraction(failingExtractor, memoryService)
        val persistence = ChatPersistence { _, response -> StageResult.Succeeded(PersistedResponse(UUID.randomUUID(), response.content)) }
        val e = engine(persistence = persistence, postDelivery = postDelivery)

        val result = e.process(request())

        assertTrue(result is ChatResult.Success)
    }
}
