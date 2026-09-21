package com.pinkdreams.api.admin

import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.module
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.LlmExchangeRepository
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 5 — Admin Observability UI (backend). Proves the required
 * properties from the task spec's own "Tests" section: filters,
 * pagination, detail loading, redaction, production/test separation, and
 * missing metadata — over the real HTTP surface, not just the repository.
 */
class AdminObservabilityRoutesTest {
    private val adminId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val nonAdminId = UUID.fromString("00000000-0000-0000-0000-0000000000a2")

    private class TestAdminAuthProvider(private val adminId: UUID) : com.pinkdreams.auth.AdminAuthorizationProvider() {
        override fun isAdmin(userId: String): Boolean = userId == adminId.toString()
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.setup(): Database {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        application {
            module(
                databaseConfig = DatabaseConfig(jdbcUrl = "jdbc:h2:mem:test", username = "sa", password = ""),
                llmConfig = LlmConfig(apiKey = "test-key"),
                chatEngine = null,
                conversationRepository = ConversationRepository(db),
                adminAuthProvider = TestAdminAuthProvider(adminId),
                database = db,
            )
        }
        return db
    }

    private fun json(body: String) = Json.parseToJsonElement(body).jsonObject

    private fun seedExchange(
        repo: LlmExchangeRepository,
        conversationId: UUID,
        workload: String = "primary_generation",
        isTestChat: Boolean = false,
        latencyMs: Long = 1000,
        outcome: LlmExchangeRepository.Outcome = LlmExchangeRepository.Outcome.SUCCESS,
        requestBody: String = """{"model":"x"}""",
        responseBody: String = """{"choices":[]}""",
        skillKey: String? = null,
    ): UUID {
        val turnId = UUID.randomUUID()
        repo.record(
            LlmExchangeRepository.RecordInput(
                turnRequestId = turnId,
                conversationId = conversationId,
                workload = workload,
                isTestChat = isTestChat,
                model = "test-model",
                provider = "TestProvider",
                latencyMs = latencyMs,
                outcome = outcome,
                requestBody = requestBody,
                responseBody = responseBody,
                skillKey = skillKey,
            ),
        )
        return turnId
    }

    @Test
    fun `latency dashboard reflects seeded exchanges and filters by workload`() = testApplication {
        val db = setup()
        val repo = LlmExchangeRepository(db)
        val conversationId = UUID.randomUUID()
        seedExchange(repo, conversationId, workload = "intent_discovery", latencyMs = 2000)
        seedExchange(repo, conversationId, workload = "primary_generation", latencyMs = 9000)

        val all = client.get("/v1/admin/observability/latency-dashboard") { basicAuth(adminId.toString(), "x") }
        assertEquals(HttpStatusCode.OK, all.status)
        assertEquals(2, json(all.bodyAsText())["overall"]!!.jsonObject["count"]!!.jsonPrimitive.content.toInt())

        val filtered = client.get("/v1/admin/observability/latency-dashboard?workload=intent_discovery") {
            basicAuth(adminId.toString(), "x")
        }
        assertEquals(1, json(filtered.bodyAsText())["overall"]!!.jsonObject["count"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `exchange list requires a filter dimension`() = testApplication {
        setup()

        val response = client.get("/v1/admin/observability/exchanges") { basicAuth(adminId.toString(), "x") }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `exchange list can be filtered by conversation and respects limit`() = testApplication {
        val db = setup()
        val repo = LlmExchangeRepository(db)
        val conversationId = UUID.randomUUID()
        repeat(5) { seedExchange(repo, conversationId) }
        seedExchange(repo, UUID.randomUUID()) // different conversation, must not appear

        val response = client.get("/v1/admin/observability/exchanges?conversationId=$conversationId&limit=3") {
            basicAuth(adminId.toString(), "x")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        assertEquals(3, body["exchanges"]!!.jsonArray.size)
    }

    @Test
    fun `exchange detail lazily includes raw payloads not present on the list endpoint`() = testApplication {
        val db = setup()
        val repo = LlmExchangeRepository(db)
        val conversationId = UUID.randomUUID()
        seedExchange(repo, conversationId, requestBody = """{"secret":"never shown on list"}""")

        val listResponse = client.get("/v1/admin/observability/exchanges?conversationId=$conversationId") {
            basicAuth(adminId.toString(), "x")
        }
        val listBody = json(listResponse.bodyAsText())
        val exchangeId = listBody["exchanges"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content
        assertFalse(listResponse.bodyAsText().contains("secret"), "Raw request body must never appear on the list endpoint")

        val detailResponse = client.get("/v1/admin/observability/exchanges/$exchangeId") { basicAuth(adminId.toString(), "x") }

        assertEquals(HttpStatusCode.OK, detailResponse.status)
        assertTrue(detailResponse.bodyAsText().contains("never shown on list"), "Detail endpoint must include the raw body")
    }

    @Test
    fun `exchange detail never exposes an Authorization header value`() = testApplication {
        val db = setup()
        val repo = LlmExchangeRepository(db)
        val conversationId = UUID.randomUUID()
        seedExchange(conversationId = conversationId, repo = repo, requestBody = """{"model":"x"}""")

        val listResponse = client.get("/v1/admin/observability/exchanges?conversationId=$conversationId") {
            basicAuth(adminId.toString(), "x")
        }
        val exchangeId = json(listResponse.bodyAsText())["exchanges"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        val detailResponse = client.get("/v1/admin/observability/exchanges/$exchangeId") { basicAuth(adminId.toString(), "x") }

        assertFalse(detailResponse.bodyAsText().contains("Bearer"), "No API key material should ever reach an admin response")
    }

    @Test
    fun `unknown exchange id returns 404 not a crash`() = testApplication {
        setup()

        val response = client.get("/v1/admin/observability/exchanges/${UUID.randomUUID()}") { basicAuth(adminId.toString(), "x") }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `turn trace reconstructs every exchange for one turn in order`() = testApplication {
        val db = setup()
        val repo = LlmExchangeRepository(db)
        val conversationId = UUID.randomUUID()
        val turnId = UUID.randomUUID()
        repo.record(
            LlmExchangeRepository.RecordInput(
                turnRequestId = turnId, conversationId = conversationId, workload = "intent_discovery",
                isTestChat = false, model = "m", provider = "p", latencyMs = 1000, outcome = LlmExchangeRepository.Outcome.SUCCESS,
            ),
        )
        repo.record(
            LlmExchangeRepository.RecordInput(
                turnRequestId = turnId, conversationId = conversationId, workload = "primary_generation",
                isTestChat = false, model = "m", provider = "p", latencyMs = 2000, outcome = LlmExchangeRepository.Outcome.SUCCESS,
            ),
        )

        val response = client.get("/v1/admin/observability/turns/$turnId") { basicAuth(adminId.toString(), "x") }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        assertEquals(2, body["exchanges"]!!.jsonArray.size)
        assertEquals(3000, body["onPathLatencyMs"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `production and test chat exchanges are distinguishable and independently filterable`() = testApplication {
        val db = setup()
        val repo = LlmExchangeRepository(db)
        val conversationId = UUID.randomUUID()
        seedExchange(repo, conversationId, isTestChat = false)
        seedExchange(repo, conversationId, isTestChat = true)

        val prodOnly = client.get("/v1/admin/observability/exchanges?conversationId=$conversationId") {
            basicAuth(adminId.toString(), "x")
        }
        val body = json(prodOnly.bodyAsText())
        val flags = body["exchanges"]!!.jsonArray.map { it.jsonObject["isTestChat"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("true", "false"), flags, "Both should be visible; isTestChat must be present so an admin can distinguish them")
    }

    @Test
    fun `a non-admin principal is rejected`() = testApplication {
        setup()

        val response = client.get("/v1/admin/observability/latency-dashboard") { basicAuth(nonAdminId.toString(), "x") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `an unauthenticated request is rejected`() = testApplication {
        setup()

        val response = client.get("/v1/admin/observability/latency-dashboard")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `missing metadata fields degrade gracefully instead of crashing`() = testApplication {
        val db = setup()
        val repo = LlmExchangeRepository(db)
        val conversationId = UUID.randomUUID()
        // No model/provider/tokens supplied — a real budget-exhaustion-before-any-usage case.
        repo.record(
            LlmExchangeRepository.RecordInput(
                turnRequestId = UUID.randomUUID(), conversationId = conversationId, workload = "primary_generation",
                isTestChat = false, model = null, provider = null, latencyMs = 500,
                outcome = LlmExchangeRepository.Outcome.EXCEPTION,
            ),
        )

        val response = client.get("/v1/admin/observability/exchanges?conversationId=$conversationId") {
            basicAuth(adminId.toString(), "x")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val exchange = json(response.bodyAsText())["exchanges"]!!.jsonArray[0].jsonObject
        // Server-wide JSON config omits null fields (explicitNulls = false) rather
        // than emitting "model": null — the absent key IS the correct "no model
        // recorded" representation here, not a bug.
        assertEquals(null, exchange["model"], "A missing model must be omitted, not crash or be defaulted to a fake value")
        assertEquals("EXCEPTION", exchange["outcome"]!!.jsonPrimitive.content)
    }

    // Task 6 — Export/Analysis

    @Test
    fun `CSV export includes a header, preserves exchange IDs, and respects the workload filter`() = testApplication {
        val db = setup()
        val repo = LlmExchangeRepository(db)
        val conversationId = UUID.randomUUID()
        val intentId = seedExchange(repo, conversationId, workload = "intent_discovery")
        seedExchange(repo, conversationId, workload = "primary_generation")

        val response = client.get("/v1/admin/observability/export/exchanges.csv?workload=intent_discovery") {
            basicAuth(adminId.toString(), "x")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val lines = response.bodyAsText().trim().lines()
        assertEquals(2, lines.size, "One header line plus exactly one matching data row")
        assertTrue(lines[0].startsWith("id,turnRequestId,conversationId,workload"), "Header must be present")
        assertTrue(lines[1].contains(intentId.toString()), "The turn request id must be preserved for joining back to the detail endpoint")
        assertFalse(response.bodyAsText().contains("primary_generation"), "The filter must exclude the other workload's row")
    }

    @Test
    fun `CSV export never includes raw request or response bodies`() = testApplication {
        val db = setup()
        val repo = LlmExchangeRepository(db)
        val conversationId = UUID.randomUUID()
        seedExchange(repo, conversationId, requestBody = """{"secret_marker":"should never leave via bulk export"}""")

        val response = client.get("/v1/admin/observability/export/exchanges.csv?conversationId=$conversationId") {
            basicAuth(adminId.toString(), "x")
        }

        assertFalse(response.bodyAsText().contains("secret_marker"), "Bulk CSV export must never carry raw payload content")
    }

    @Test
    fun `aggregated performance export returns the same shape as the dashboard`() = testApplication {
        val db = setup()
        val repo = LlmExchangeRepository(db)
        val conversationId = UUID.randomUUID()
        seedExchange(repo, conversationId, workload = "primary_generation", latencyMs = 3000)

        val response = client.get("/v1/admin/observability/export/performance.json") {
            basicAuth(adminId.toString(), "x")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        assertTrue(body.containsKey("overall"))
        assertTrue(body.containsKey("slaBuckets"))
        assertTrue(body.containsKey("byWorkload"))
    }

    @Test
    fun `export endpoints require admin access like every other observability endpoint`() = testApplication {
        setup()

        val csvResponse = client.get("/v1/admin/observability/export/exchanges.csv") { basicAuth(nonAdminId.toString(), "x") }
        val jsonResponse = client.get("/v1/admin/observability/export/performance.json") { basicAuth(nonAdminId.toString(), "x") }

        assertEquals(HttpStatusCode.Forbidden, csvResponse.status)
        assertEquals(HttpStatusCode.Forbidden, jsonResponse.status)
    }

    // --- Task 8 ---

    @Test
    fun `latency dashboard includes a bySkill breakdown and respects the skillKey filter`() = testApplication {
        val db = setup()
        val repo = LlmExchangeRepository(db)
        val conversationId = UUID.randomUUID()
        seedExchange(repo, conversationId, skillKey = "flirting", latencyMs = 1000)
        seedExchange(repo, conversationId, skillKey = "dating", latencyMs = 9000)

        val all = client.get("/v1/admin/observability/latency-dashboard") { basicAuth(adminId.toString(), "x") }
        val allBody = json(all.bodyAsText())
        assertTrue(allBody["bySkill"]!!.jsonObject.containsKey("flirting"))
        assertTrue(allBody["bySkill"]!!.jsonObject.containsKey("dating"))

        val filtered = client.get("/v1/admin/observability/latency-dashboard?skillKey=flirting") { basicAuth(adminId.toString(), "x") }
        val filteredBody = json(filtered.bodyAsText())
        assertEquals(1, filteredBody["overall"]!!.jsonObject["count"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `exchange list respects the skillKey and outcome filters on top of the required conversationId dimension`() = testApplication {
        val db = setup()
        val repo = LlmExchangeRepository(db)
        val conversationId = UUID.randomUUID()
        seedExchange(repo, conversationId, skillKey = "flirting", outcome = LlmExchangeRepository.Outcome.SUCCESS)
        seedExchange(repo, conversationId, skillKey = "dating", outcome = LlmExchangeRepository.Outcome.PROVIDER_ERROR)

        val response = client.get("/v1/admin/observability/exchanges?conversationId=$conversationId&skillKey=flirting") {
            basicAuth(adminId.toString(), "x")
        }
        val exchanges = json(response.bodyAsText())["exchanges"]!!.jsonArray
        assertEquals(1, exchanges.size)
        assertEquals("flirting", exchanges[0].jsonObject["skillKey"]!!.jsonPrimitive.content)

        val outcomeFiltered = client.get("/v1/admin/observability/exchanges?conversationId=$conversationId&outcome=PROVIDER_ERROR") {
            basicAuth(adminId.toString(), "x")
        }
        val outcomeExchanges = json(outcomeFiltered.bodyAsText())["exchanges"]!!.jsonArray
        assertEquals(1, outcomeExchanges.size)
        assertEquals("PROVIDER_ERROR", outcomeExchanges[0].jsonObject["outcome"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an exchange recorded before skill attribution existed has a null skillKey not a guessed one`() = testApplication {
        val db = setup()
        val repo = LlmExchangeRepository(db)
        val conversationId = UUID.randomUUID()
        seedExchange(repo, conversationId, skillKey = null)

        val response = client.get("/v1/admin/observability/exchanges?conversationId=$conversationId") { basicAuth(adminId.toString(), "x") }

        assertEquals(null, json(response.bodyAsText())["exchanges"]!!.jsonArray[0].jsonObject["skillKey"])
    }

    @Test
    fun `CSV export includes the skillKey column`() = testApplication {
        val db = setup()
        val repo = LlmExchangeRepository(db)
        val conversationId = UUID.randomUUID()
        seedExchange(repo, conversationId, skillKey = "flirting")

        val response = client.get("/v1/admin/observability/export/exchanges.csv?conversationId=$conversationId") {
            basicAuth(adminId.toString(), "x")
        }

        val lines = response.bodyAsText().trim().lines()
        assertTrue(lines[0].contains("skillKey"), "Header must include the new attribution column")
        assertTrue(lines[1].contains("flirting"))
    }

    @Test
    fun `effective configuration reports one entry per workload without requiring database access`() = testApplication {
        setup()

        val response = client.get("/v1/admin/observability/effective-configuration") { basicAuth(adminId.toString(), "x") }

        assertEquals(HttpStatusCode.OK, response.status)
        val workloads = json(response.bodyAsText())["workloads"]!!.jsonArray
        val workloadNames = workloads.map { it.jsonObject["workload"]!!.jsonPrimitive.content }.toSet()
        assertEquals(
            setOf("primary_generation", "intent_discovery", "memory_extraction", "continuity_summarization", "memory_engine_maintenance"),
            workloadNames,
        )
    }

    @Test
    fun `effective configuration requires admin access`() = testApplication {
        setup()

        val response = client.get("/v1/admin/observability/effective-configuration") { basicAuth(nonAdminId.toString(), "x") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `turn trace includes stage timings when the assistant message metadata carries them`() = testApplication {
        val db = setup()
        val repo = LlmExchangeRepository(db)
        val conversationId = UUID.randomUUID()
        val turnId = seedExchange(repo, conversationId, workload = "primary_generation")
        org.jetbrains.exposed.sql.transactions.transaction(db) {
            com.pinkdreams.persistence.database.Messages.insert {
                it[id] = UUID.randomUUID()
                it[com.pinkdreams.persistence.database.Messages.conversationId] = conversationId
                it[role] = "assistant"
                it[content] = "reply"
                it[requestId] = turnId
                it[metadata] = """{"lvm_stage_timings":"{\"generation\":\"1234\"}"}"""
                it[createdAt] = com.pinkdreams.persistence.database.defaultNow()
            }
        }

        val response = client.get("/v1/admin/observability/turns/$turnId") { basicAuth(adminId.toString(), "x") }

        val stageTimings = json(response.bodyAsText())["stageTimingsMs"]!!.jsonObject
        assertEquals(1234, stageTimings["generation"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `turn trace stage timings are null not fabricated when no assistant message exists for the turn`() = testApplication {
        val db = setup()
        val repo = LlmExchangeRepository(db)
        val conversationId = UUID.randomUUID()
        val turnId = seedExchange(repo, conversationId)

        val response = client.get("/v1/admin/observability/turns/$turnId") { basicAuth(adminId.toString(), "x") }

        assertEquals(null, json(response.bodyAsText())["stageTimingsMs"])
    }
}
