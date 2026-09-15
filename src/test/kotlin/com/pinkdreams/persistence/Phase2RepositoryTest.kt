package com.pinkdreams.persistence

import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Phase2RepositoryTest {
    @Test
    fun `conversation engine repository persists and retrieves a record`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val repo = ConversationEngineRepository(db)
        val engine = repo.create(
            version = 1,
            content = "You are a helpful assistant.",
            status = "draft",
            changelogNote = "Initial draft",
            createdBy = "admin",
        )

        val loaded = repo.findById(engine.id)
        assertNotNull(loaded)
        assertEquals(1, loaded.version)
        assertEquals("draft", loaded.status)
    }

    @Test
    fun `persona core version can be activated on a persona`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val personaRepo = PersonaRepository(db)
        val versionRepo = PersonaCoreVersionRepository(db)

        val persona = personaRepo.create(
            slug = "simran",
            displayName = "Simran",
            gender = "female",
            orientation = "straight",
            apparentAge = 28,
            languageProfile = mapOf("primary" to "en"),
        )

        val version = versionRepo.create(
            personaId = persona.id,
            version = 1,
            content = "You are warm and practical.",
            status = "published",
            author = "admin",
        )

        personaRepo.activateCoreVersion(persona.id, version.id)

        val updated = personaRepo.findById(persona.id)
        assertNotNull(updated)
        assertEquals(version.id, updated.activeCoreVersionId)
        assertTrue(updated.activeCoreVersionId != null)
    }

    @Test
    fun `conversation engine records are versioned by number`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val repo = ConversationEngineRepository(db)
        repo.create(version = 1, content = "v1", status = "published")
        repo.create(version = 2, content = "v2", status = "draft")

        val byVersion = repo.findByVersion(2)
        assertNotNull(byVersion)
        assertEquals(2, byVersion.version)
        assertEquals("v2", byVersion.content)
    }
}
