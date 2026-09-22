package com.pinkdreams.imaging

import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.database.Personas
import com.pinkdreams.persistence.repositories.PersonaIdentityRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Seeds a clearly named test persona + visual identity against real Postgres.
 * Opt-in: IMAGE_LIVE_GATE=true plus DATABASE_URL / USER / PASSWORD.
 * Writes persona UUID to .tmp/live-gate-persona-id.txt (no secrets).
 */
class PhaseIMGLiveGateSeedTest {

    @Test
    fun `seed test persona with visual identity for live gate`() {
        assumeTrue(
            System.getenv("IMAGE_LIVE_GATE")?.equals("true", ignoreCase = true) == true,
            "Set IMAGE_LIVE_GATE=true to run",
        )
        val url = System.getenv("DATABASE_URL")
        val user = System.getenv("DATABASE_USER")
        val pass = System.getenv("DATABASE_PASSWORD")
        assumeTrue(!url.isNullOrBlank() && !user.isNullOrBlank() && !pass.isNullOrBlank(), "Postgres env required")

        val db = DatabaseFactory.connect(
            com.pinkdreams.config.DatabaseConfig(
                jdbcUrl = url!!,
                username = user!!,
                password = pass!!,
            )
        )

        val identityRepo = PersonaIdentityRepository(db)
        val visualRepo = PersonaVisualVersionRepository(db)
        val personaRepo = PersonaRepository(db)

        val identity = identityRepo.create()
        val version = visualRepo.create(
            personaIdentityId = identity.id,
            version = 1,
            physicalGuide = """{"face":{"shape":"oval"},"hair":{"color":"black","length":"long"},"note":"live-gate-seed"}""",
            author = "live-gate",
        )
        visualRepo.publishVisualVersion(version.id)
        visualRepo.activateVisualVersion(identity.id, version.id)

        val slug = "live-gate-${UUID.randomUUID().toString().take(8)}"
        val persona = personaRepo.create(
            slug = slug,
            displayName = "LiveGate Test",
            gender = "female",
            orientation = "straight",
            apparentAge = 25,
            languageProfile = mapOf("primary" to "en"),
        )
        transaction(db) {
            Personas.update({ Personas.id eq persona.id }) {
                it[Personas.personaIdentityId] = identity.id
                it[Personas.status] = "active"
            }
        }

        val out = Path.of(".tmp", "live-gate-persona-id.txt")
        Files.createDirectories(out.parent)
        Files.writeString(out, persona.id.toString())
        assertNotNull(personaRepo.findPersonaIdentityId(persona.id))
        println("LIVE_GATE_PERSONA_ID=${persona.id}")
    }
}
