package com.pinkdreams.api.admin

import com.pinkdreams.module
import com.pinkdreams.config.DatabaseConfig
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Phase6CComprehensiveTest {

    @Test
    fun `engine lifecycle - draft to publish to activate`() {
        val db = com.pinkdreams.persistence.database.DatabaseFactory.connectInMemory()
        com.pinkdreams.persistence.database.DatabaseFactory.initializeSchema(db)
        val engineRepo = ConversationEngineRepository(db)

        // Create draft
        val engine1 = engineRepo.create(
            version = 1,
            content = "test",
            createdBy = "admin-user",
        )
        assertEquals("draft", engine1.status)
        assertEquals(false, engine1.isActive)

        // Publish
        val engine2 = engineRepo.publishEngine(engine1.id)
        assertEquals("published", engine2.status)

        // Activate
        val engine3 = engineRepo.activateEngine(engine2.id)
        assertEquals("published", engine3.status)
        assertEquals(true, engine3.isActive)

        // Verify only one active
        val activeEngine = engineRepo.getActiveEngine()
        assertEquals(engine3.id, activeEngine?.id)
    }

    @Test
    fun `active core cannot be archived`() {
        val db = com.pinkdreams.persistence.database.DatabaseFactory.connectInMemory()
        com.pinkdreams.persistence.database.DatabaseFactory.initializeSchema(db)
        val personaRepo = PersonaRepository(db)
        val coreRepo = PersonaCoreVersionRepository(db)

        val persona = personaRepo.create(
            slug = "test-persona",
            displayName = "Test Persona",
            gender = "neutral",
            orientation = "any",
            apparentAge = 30,
            languageProfile = emptyMap(),
        )

        val core = coreRepo.create(
            personaId = persona.id,
            version = 1,
            content = "test core",
        )

        val published = coreRepo.publishCoreVersion(core.id)
        personaRepo.activateCoreVersion(persona.id, published.id)

        // Try to archive active core - should fail
        try {
            coreRepo.archiveCoreVersion(published.id)
            throw AssertionError("Should have thrown IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Active version cannot be archived", e.message)
        }
    }

    @Test
    fun `persona retirement preserves persona record`() {
        val db = com.pinkdreams.persistence.database.DatabaseFactory.connectInMemory()
        com.pinkdreams.persistence.database.DatabaseFactory.initializeSchema(db)
        val personaRepo = PersonaRepository(db)

        val persona = personaRepo.create(
            slug = "test-persona",
            displayName = "Test Persona",
            gender = "neutral",
            orientation = "any",
            apparentAge = 30,
            languageProfile = emptyMap(),
        )
        val personaId = persona.id

        // Persona must be active before it can be retired
        personaRepo.activatePersona(personaId)

        // Retire the persona
        val retired = personaRepo.retirePersona(personaId)
        assertEquals("retired", retired.status)

        // Verify we can still find it
        val stillFound = personaRepo.findById(personaId)
        assertEquals("retired", stillFound?.status)
    }

    @Test
    fun `cannot activate another persona's core version`() {
        val db = com.pinkdreams.persistence.database.DatabaseFactory.connectInMemory()
        com.pinkdreams.persistence.database.DatabaseFactory.initializeSchema(db)
        val personaRepo = PersonaRepository(db)
        val coreRepo = PersonaCoreVersionRepository(db)

        val persona1 = personaRepo.create(
            slug = "persona-1",
            displayName = "Persona 1",
            gender = "neutral",
            orientation = "any",
            apparentAge = 30,
            languageProfile = emptyMap(),
        )

        val persona2 = personaRepo.create(
            slug = "persona-2",
            displayName = "Persona 2",
            gender = "neutral",
            orientation = "any",
            apparentAge = 25,
            languageProfile = emptyMap(),
        )

        val core = coreRepo.create(
            personaId = persona1.id,
            version = 1,
            content = "core for persona 1",
        )

        val published = coreRepo.publishCoreVersion(core.id)

        // Try to activate persona1's core for persona2
        try {
            personaRepo.activateCoreVersion(persona2.id, published.id)
            throw AssertionError("Should have thrown IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("does not belong") == true)
        }
    }

    @Test
    fun `cannot activate draft core`() {
        val db = com.pinkdreams.persistence.database.DatabaseFactory.connectInMemory()
        com.pinkdreams.persistence.database.DatabaseFactory.initializeSchema(db)
        val personaRepo = PersonaRepository(db)
        val coreRepo = PersonaCoreVersionRepository(db)

        val persona = personaRepo.create(
            slug = "test-persona",
            displayName = "Test Persona",
            gender = "neutral",
            orientation = "any",
            apparentAge = 30,
            languageProfile = emptyMap(),
        )

        val core = coreRepo.create(
            personaId = persona.id,
            version = 1,
            content = "draft core",
        )

        // Try to activate draft - should fail
        try {
            personaRepo.activateCoreVersion(persona.id, core.id)
            throw AssertionError("Should have thrown IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Only published versions can be activated", e.message)
        }
    }

    @Test
    fun `cannot archive active engine`() {
        val db = com.pinkdreams.persistence.database.DatabaseFactory.connectInMemory()
        com.pinkdreams.persistence.database.DatabaseFactory.initializeSchema(db)
        val engineRepo = ConversationEngineRepository(db)

        val engine = engineRepo.create(
            version = 1,
            content = "test",
            createdBy = "admin-user",
        )
        val published = engineRepo.publishEngine(engine.id)
        val active = engineRepo.activateEngine(published.id)

        // Try to archive active - should fail
        try {
            engineRepo.archiveEngine(active.id)
            throw AssertionError("Should have thrown IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Active engine cannot be archived", e.message)
        }
    }

    @Test
    fun `can archive published engine that is not active`() {
        val db = com.pinkdreams.persistence.database.DatabaseFactory.connectInMemory()
        com.pinkdreams.persistence.database.DatabaseFactory.initializeSchema(db)
        val engineRepo = ConversationEngineRepository(db)

        val engine = engineRepo.create(
            version = 1,
            content = "test",
            createdBy = "admin-user",
        )
        val published = engineRepo.publishEngine(engine.id)

        // Archive should succeed - it's published but not active
        val archived = engineRepo.archiveEngine(published.id)
        assertEquals("archived", archived.status)
    }

    @Test
    fun `activating new engine deactivates old engine`() {
        val db = com.pinkdreams.persistence.database.DatabaseFactory.connectInMemory()
        com.pinkdreams.persistence.database.DatabaseFactory.initializeSchema(db)
        val engineRepo = ConversationEngineRepository(db)

        // Create and activate engine 1
        val engine1 = engineRepo.create(
            version = 1,
            content = "engine 1",
            createdBy = "admin",
        )
        val pub1 = engineRepo.publishEngine(engine1.id)
        val active1 = engineRepo.activateEngine(pub1.id)
        assertEquals(true, active1.isActive)

        // Create and activate engine 2
        val engine2 = engineRepo.create(
            version = 2,
            content = "engine 2",
            createdBy = "admin",
        )
        val pub2 = engineRepo.publishEngine(engine2.id)
        val active2 = engineRepo.activateEngine(pub2.id)
        assertEquals(true, active2.isActive)

        // Engine 1 should no longer be active
        val reloadedEngine1 = engineRepo.findById(engine1.id)
        assertEquals(false, reloadedEngine1?.isActive)

        // Only one active engine total
        val getActive = engineRepo.getActiveEngine()
        assertEquals(engine2.id, getActive?.id)
    }
}
