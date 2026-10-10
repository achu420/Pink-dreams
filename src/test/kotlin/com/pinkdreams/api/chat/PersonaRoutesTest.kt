package com.pinkdreams.api.chat

import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.module
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.util.encodeBase64
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val testJson = Json { ignoreUnknownKeys = true }

class PersonaRoutesTest {

    // Goes through the real draft -> published core version -> activateCoreVersion
    // -> activatePersona chain, now that PersonaRepository.activatePersona exists —
    // this used to require reaching around the repository with raw SQL because no
    // production code path could reach "active" at all.
    private fun activate(db: org.jetbrains.exposed.sql.Database, personaRepo: PersonaRepository, personaId: UUID) {
        val versionRepo = PersonaCoreVersionRepository(db)
        val published = versionRepo.publishCoreVersion(versionRepo.create(personaId, 1, "core content", "draft").id)
        personaRepo.activateCoreVersion(personaId, published.id)
        personaRepo.activatePersona(personaId)
    }

    @Test
    fun `list personas with none active returns empty list`() = testApplication {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val personaRepo = PersonaRepository(db)
        personaRepo.create(
            slug = "draft-persona",
            displayName = "Draft Persona",
            gender = "female",
            orientation = "straight",
            apparentAge = 25,
            languageProfile = emptyMap(),
        )

        val testDatabaseConfig = DatabaseConfig(jdbcUrl = "jdbc:h2:mem:test", username = "sa", password = "")
        val testLlmConfig = LlmConfig(apiKey = "test-key")

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, database = db)
        }

        val authHeader = "Basic ${"${UUID.randomUUID()}:password".encodeBase64()}"
        val response = client.get("/v1/personas") {
            header("Authorization", authHeader)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = testJson.decodeFromString<PublicPersonaListResponse>(response.bodyAsText())
        assertTrue(body.personas.isEmpty())
    }

    @Test
    fun `list personas returns only active personas`() = testApplication {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val personaRepo = PersonaRepository(db)
        val activePersona = personaRepo.create(
            slug = "active-persona",
            displayName = "Active Persona",
            gender = "female",
            orientation = "straight",
            apparentAge = 28,
            languageProfile = emptyMap(),
        )
        activate(db, personaRepo, activePersona.id)

        personaRepo.create(
            slug = "draft-persona",
            displayName = "Draft Persona",
            gender = "female",
            orientation = "straight",
            apparentAge = 25,
            languageProfile = emptyMap(),
        )
        val retiredPersona = personaRepo.create(
            slug = "retired-persona",
            displayName = "Retired Persona",
            gender = "female",
            orientation = "straight",
            apparentAge = 30,
            languageProfile = emptyMap(),
        )
        personaRepo.retirePersona(retiredPersona.id)

        val testDatabaseConfig = DatabaseConfig(jdbcUrl = "jdbc:h2:mem:test", username = "sa", password = "")
        val testLlmConfig = LlmConfig(apiKey = "test-key")

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig, database = db)
        }

        val authHeader = "Basic ${"${UUID.randomUUID()}:password".encodeBase64()}"
        val response = client.get("/v1/personas") {
            header("Authorization", authHeader)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = testJson.decodeFromString<PublicPersonaListResponse>(response.bodyAsText())
        assertEquals(1, body.personas.size)
        val returned = body.personas.single()
        assertEquals(activePersona.id.toString(), returned.id)
        assertEquals(activePersona.slug, returned.slug)
        assertEquals(activePersona.displayName, returned.displayName)
        assertEquals(activePersona.gender, returned.gender)
        assertEquals(activePersona.orientation, returned.orientation)
        assertEquals(activePersona.apparentAge, returned.apparentAge)
    }

    @Test
    fun `list personas without authentication returns 401`() = testApplication {
        val testDatabaseConfig = DatabaseConfig(jdbcUrl = "jdbc:h2:mem:test", username = "sa", password = "")
        val testLlmConfig = LlmConfig(apiKey = "test-key")

        application {
            module(databaseConfig = testDatabaseConfig, llmConfig = testLlmConfig)
        }

        val response = client.get("/v1/personas")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
