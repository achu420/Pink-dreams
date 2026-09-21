package com.pinkdreams.api.admin

import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.module
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationRepository
import com.pinkdreams.persistence.repositories.MemoryFactRepository
import com.pinkdreams.persistence.repositories.MessageRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.UserProfileRepository
import com.pinkdreams.persistence.repositories.UserRepository
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Admin console exports: Users CSV, per-user Memory-facts CSV, and a single
 * conversation's transcript as JSON.
 *
 * Every one of these endpoints must (a) refuse an unauthenticated caller,
 * (b) refuse an authenticated NON-admin with 403 — the check is on the
 * resolved principal's identity, never on mere header presence — and (c) for
 * the CSV exports, survive free text containing a comma, a double quote and a
 * newline without the file's column structure being corrupted.
 */
class AdminExportRoutesTest {
    private val testAdminId = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
    private val testNonAdminId = UUID.fromString("00000000-0000-0000-0000-0000000000e2")

    /** Text that breaks every naive comma-join: a delimiter, a quote and a line break. */
    private val nastyText = "Lives in Pune, India \"the best\" city\nand loves trekking"

    private class TestAdminAuthProvider(private val adminId: UUID) : com.pinkdreams.auth.AdminAuthorizationProvider() {
        override fun isAdmin(userId: String): Boolean = userId == adminId.toString()
    }

    private fun ApplicationTestBuilder.setupAdminApp(): Database {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val conversationRepo = ConversationRepository(db)
        application {
            module(
                databaseConfig = DatabaseConfig(jdbcUrl = "jdbc:h2:mem:test", username = "sa", password = ""),
                llmConfig = LlmConfig(apiKey = "test-key"),
                chatEngine = null,
                conversationRepository = conversationRepo,
                adminAuthProvider = TestAdminAuthProvider(testAdminId),
                database = db,
            )
        }
        return db
    }

    // ------------------------------------------------------------------
    // A minimal RFC-4180 reader, so the assertions below verify the CSV's
    // real STRUCTURE (row/column boundaries) rather than just substring
    // presence — a naive `split(",")` would happily pass a corrupted file.
    // ------------------------------------------------------------------
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ',' -> { row.add(field.toString()); field.clear() }
                !inQuotes && c == '\n' -> { row.add(field.toString()); field.clear(); rows.add(row); row = mutableListOf() }
                else -> field.append(c)
            }
            i++
        }
        row.add(field.toString())
        if (row.size > 1 || row[0].isNotEmpty()) rows.add(row)
        return rows
    }

    private fun seedUser(db: Database, displayName: String, city: String? = "Pune", age: Int? = 30): UUID {
        val userId = UUID.randomUUID()
        UserRepository(db).create(userId)
        UserProfileRepository(db).create(
            userId = userId,
            displayName = displayName,
            gender = "male",
            interest = "female",
            city = city,
            age = age,
        )
        return userId
    }

    private fun seedPersona(db: Database, slug: String) = PersonaRepository(db).create(
        slug = slug,
        displayName = "Export Tester",
        gender = "female",
        orientation = "straight",
        apparentAge = 25,
        languageProfile = emptyMap(),
    )

    // ---------------------------- Users CSV ----------------------------

    @Test
    fun `users csv export rejects unauthenticated caller`() = testApplication {
        setupAdminApp()
        val response = client.get("/v1/admin/users/export.csv")
        assertTrue(
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden,
            "expected the users export to refuse an unauthenticated caller, got ${response.status}",
        )
    }

    @Test
    fun `users csv export forbids authenticated non-admin`() = testApplication {
        setupAdminApp()
        val response = client.get("/v1/admin/users/export.csv") {
            basicAuth(testNonAdminId.toString(), "password")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `users csv export downloads real rows with activity columns`() = testApplication {
        val db = setupAdminApp()
        val userId = seedUser(db, "Alice Export")
        val persona = seedPersona(db, "users-csv-persona")
        ConversationRepository(db).create(userId = userId, personaId = persona.id)

        val response = client.get("/v1/admin/users/export.csv") {
            basicAuth(testAdminId.toString(), "password")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(
            response.headers[HttpHeaders.ContentDisposition]?.contains("attachment") == true,
            "export must be sent as a downloadable attachment",
        )
        assertTrue(response.headers[HttpHeaders.ContentType]?.contains("csv") == true)

        val rows = parseCsv(response.bodyAsText())
        assertEquals(
            listOf("userId", "displayName", "gender", "interest", "city", "age", "conversations", "lastActiveAt"),
            rows.first(),
        )
        val row = rows.drop(1).single { it[0] == userId.toString() }
        assertEquals("Alice Export", row[1])
        assertEquals("Pune", row[4])
        assertEquals("30", row[5])
        // Real grouped-count KPI, not a constant.
        assertEquals("1", row[6])
    }

    @Test
    fun `users csv export escapes commas quotes and newlines without corrupting structure`() = testApplication {
        val db = setupAdminApp()
        val userId = seedUser(db, nastyText, city = "Pune, MH")

        val response = client.get("/v1/admin/users/export.csv") {
            basicAuth(testAdminId.toString(), "password")
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val rows = parseCsv(response.bodyAsText())
        val header = rows.first()
        // Structure survived: exactly one data row, with exactly as many
        // columns as the header — the embedded newline did NOT split the row
        // and the embedded comma did NOT split a column.
        assertEquals(2, rows.size, "the embedded newline must not create an extra CSV row")
        val row = rows[1]
        assertEquals(header.size, row.size, "the embedded comma must not create an extra CSV column")
        // And the values round-trip byte-for-byte.
        assertEquals(userId.toString(), row[0])
        assertEquals(nastyText, row[1])
        assertEquals("Pune, MH", row[4])
    }

    @Test
    fun `users csv export honours the same filters as the users list UI`() = testApplication {
        val db = setupAdminApp()
        seedUser(db, "Filtered In", city = "Pune", age = 30)
        seedUser(db, "Filtered Out", city = "Delhi", age = 30)

        val filtered = client.get("/v1/admin/users/export.csv?city=Pune") {
            basicAuth(testAdminId.toString(), "password")
        }
        assertEquals(HttpStatusCode.OK, filtered.status)
        val rows = parseCsv(filtered.bodyAsText()).drop(1)
        assertEquals(1, rows.size)
        assertEquals("Filtered In", rows[0][1])

        // Age bounds apply too, and an unfiltered export returns everyone.
        val byAge = parseCsv(
            client.get("/v1/admin/users/export.csv?minAge=40") {
                basicAuth(testAdminId.toString(), "password")
            }.bodyAsText(),
        ).drop(1)
        assertEquals(0, byAge.size)

        val all = parseCsv(
            client.get("/v1/admin/users/export.csv") {
                basicAuth(testAdminId.toString(), "password")
            }.bodyAsText(),
        ).drop(1)
        assertEquals(2, all.size)
    }

    // --------------------------- Memory CSV ----------------------------

    @Test
    fun `memory csv export rejects unauthenticated and non-admin callers`() = testApplication {
        val db = setupAdminApp()
        val userId = seedUser(db, "Memory Owner")

        val anon = client.get("/v1/admin/users/$userId/memory/export.csv")
        assertTrue(anon.status == HttpStatusCode.Unauthorized || anon.status == HttpStatusCode.Forbidden)

        val nonAdmin = client.get("/v1/admin/users/$userId/memory/export.csv") {
            basicAuth(testNonAdminId.toString(), "password")
        }
        assertEquals(HttpStatusCode.Forbidden, nonAdmin.status)
    }

    @Test
    fun `memory csv export round-trips a fact containing a comma quote and newline`() = testApplication {
        val db = setupAdminApp()
        val userId = seedUser(db, "Memory Owner")
        val persona = seedPersona(db, "memory-csv-persona")
        MemoryFactRepository(db).create(
            userId = userId,
            personaId = persona.id,
            fact = nastyText,
            factType = "preference",
            criticality = "high",
        )

        val response = client.get("/v1/admin/users/$userId/memory/export.csv") {
            basicAuth(testAdminId.toString(), "password")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.ContentDisposition]?.contains("attachment") == true)

        val rows = parseCsv(response.bodyAsText())
        assertEquals(2, rows.size, "one header + exactly one fact row, despite the embedded newline")
        assertEquals(rows[0].size, rows[1].size, "the embedded comma must not add a column")
        val factIndex = rows[0].indexOf("fact")
        assertEquals(nastyText, rows[1][factIndex])
        assertEquals("Export Tester", rows[1][rows[0].indexOf("personaDisplayName")])
        assertEquals("high", rows[1][rows[0].indexOf("criticality")])
    }

    @Test
    fun `memory csv export rejects a malformed user id`() = testApplication {
        setupAdminApp()
        val response = client.get("/v1/admin/users/not-a-uuid/memory/export.csv") {
            basicAuth(testAdminId.toString(), "password")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ------------------------ Transcript export ------------------------

    @Test
    fun `transcript export rejects unauthenticated and non-admin callers`() = testApplication {
        val db = setupAdminApp()
        val userId = seedUser(db, "Transcript Owner")
        val persona = seedPersona(db, "transcript-auth-persona")
        val conversation = ConversationRepository(db).create(userId = userId, personaId = persona.id)

        val anon = client.get("/v1/admin/conversations/${conversation.id}/export.json")
        assertTrue(anon.status == HttpStatusCode.Unauthorized || anon.status == HttpStatusCode.Forbidden)

        val nonAdmin = client.get("/v1/admin/conversations/${conversation.id}/export.json") {
            basicAuth(testNonAdminId.toString(), "password")
        }
        assertEquals(HttpStatusCode.Forbidden, nonAdmin.status)
    }

    @Test
    fun `transcript export returns the real messages as a downloadable json document`() = testApplication {
        val db = setupAdminApp()
        val userId = seedUser(db, "Transcript Owner")
        val persona = seedPersona(db, "transcript-persona")
        val conversation = ConversationRepository(db).create(userId = userId, personaId = persona.id)
        MessageRepository(db).createUserMessage(
            conversationId = conversation.id,
            content = nastyText,
            clientMessageId = UUID.randomUUID(),
            requestId = UUID.randomUUID(),
        )

        val response = client.get("/v1/admin/conversations/${conversation.id}/export.json") {
            basicAuth(testAdminId.toString(), "password")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(
            response.headers[HttpHeaders.ContentDisposition]?.contains("attachment") == true,
            "transcript export must be sent as a downloadable attachment",
        )
        assertTrue(response.headers[HttpHeaders.ContentType]?.contains("json") == true)

        val doc = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(doc["exportedAt"])
        assertEquals(conversation.id.toString(), doc["conversation"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        val messages = doc["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        // JSON carries free text verbatim — no escaping hazard, unlike CSV.
        assertEquals(nastyText, messages[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `transcript export 404s for an unknown conversation and 400s for a malformed id`() = testApplication {
        setupAdminApp()
        val unknown = client.get("/v1/admin/conversations/${UUID.randomUUID()}/export.json") {
            basicAuth(testAdminId.toString(), "password")
        }
        assertEquals(HttpStatusCode.NotFound, unknown.status)

        val malformed = client.get("/v1/admin/conversations/not-a-uuid/export.json") {
            basicAuth(testAdminId.toString(), "password")
        }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
    }
}
