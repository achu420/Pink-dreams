package com.pinkdreams.persistence

import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ConversationEngineRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Phase3PersonaEngineLifecycleTest {
    @Test
    fun `draft persona core stays draft and cannot be activated directly`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val personaRepo = PersonaRepository(db)
        val versionRepo = PersonaCoreVersionRepository(db)

        val persona = personaRepo.create(
            slug = "lina",
            displayName = "Lina",
            gender = "female",
            orientation = "straight",
            apparentAge = 30,
            languageProfile = mapOf("primary" to "en"),
        )

        val draft = versionRepo.create(
            personaId = persona.id,
            version = 1,
            content = "You are warm.",
            status = "draft",
            author = "admin",
        )

        assertEquals("draft", draft.status)
        assertFailsWith<IllegalStateException> {
            personaRepo.activateCoreVersion(persona.id, draft.id)
        }
    }

    @Test
    fun `published persona core can be activated and active versions resist archive`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val personaRepo = PersonaRepository(db)
        val versionRepo = PersonaCoreVersionRepository(db)

        val persona = personaRepo.create(
            slug = "meera",
            displayName = "Meera",
            gender = "female",
            orientation = "straight",
            apparentAge = 31,
            languageProfile = mapOf("primary" to "en"),
        )

        val v1 = versionRepo.create(
            personaId = persona.id,
            version = 1,
            content = "You are kind.",
            status = "draft",
            author = "admin",
        )

        val published = versionRepo.publishCoreVersion(v1.id)
        assertEquals("published", published.status)

        personaRepo.activateCoreVersion(persona.id, published.id)
        val active = personaRepo.getActiveCoreVersion(persona.id)
        assertNotNull(active)
        assertEquals(published.id, active.id)

        assertFailsWith<IllegalStateException> {
            versionRepo.archiveCoreVersion(published.id)
        }
    }

    @Test
    fun `draft persona core can be published and already-published versions reject republish`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val personaRepo = PersonaRepository(db)
        val versionRepo = PersonaCoreVersionRepository(db)

        val persona = personaRepo.create(
            slug = "sana",
            displayName = "Sana",
            gender = "female",
            orientation = "straight",
            apparentAge = 27,
            languageProfile = mapOf("primary" to "en"),
        )

        val draft = versionRepo.create(
            personaId = persona.id,
            version = 1,
            content = "You are thoughtful.",
            status = "draft",
            author = "admin",
        )

        val published = versionRepo.publishCoreVersion(draft.id)
        assertEquals("published", published.status)

        assertFailsWith<IllegalArgumentException> {
            versionRepo.publishCoreVersion(published.id)
        }
    }

    @Test
    fun `superseding version updates active pointer and preserves history`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val personaRepo = PersonaRepository(db)
        val versionRepo = PersonaCoreVersionRepository(db)

        val persona = personaRepo.create(
            slug = "nora",
            displayName = "Nora",
            gender = "female",
            orientation = "straight",
            apparentAge = 29,
            languageProfile = mapOf("primary" to "en"),
        )

        val v1 = versionRepo.create(persona.id, 1, "v1", "draft")
        val v2 = versionRepo.create(persona.id, 2, "v2", "draft")

        val publishedV1 = versionRepo.publishCoreVersion(v1.id)
        val publishedV2 = versionRepo.publishCoreVersion(v2.id)

        personaRepo.activateCoreVersion(persona.id, publishedV1.id)
        personaRepo.activateCoreVersion(persona.id, publishedV2.id)

        val active = personaRepo.getActiveCoreVersion(persona.id)
        assertNotNull(active)
        assertEquals(publishedV2.id, active.id)

        val historical = versionRepo.findById(publishedV1.id)
        assertNotNull(historical)
        assertEquals("published", historical.status)
    }

    @Test
    fun `published and active core content is immutable`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val personaRepo = PersonaRepository(db)
        val versionRepo = PersonaCoreVersionRepository(db)

        val persona = personaRepo.create(
            slug = "jules",
            displayName = "Jules",
            gender = "nonbinary",
            orientation = "queer",
            apparentAge = 32,
            languageProfile = mapOf("primary" to "en"),
        )

        val v1 = versionRepo.create(persona.id, 1, "alpha", "draft")
        val published = versionRepo.publishCoreVersion(v1.id)

        assertFailsWith<IllegalArgumentException> {
            versionRepo.updateContent(published.id, "edited")
        }

        personaRepo.activateCoreVersion(persona.id, published.id)

        assertFailsWith<IllegalArgumentException> {
            versionRepo.updateContent(published.id, "edited-again")
        }
    }

    @Test
    fun `unpublished core cannot become active and active engine cannot be overridden without valid replacement`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val engineRepo = ConversationEngineRepository(db)

        val v1 = engineRepo.create(version = 1, content = "v1", status = "published")
        val v1Active = engineRepo.activateEngine(v1.id)
        assertTrue(v1Active.isActive)

        val draft = engineRepo.create(version = 2, content = "v2", status = "draft")
        assertFailsWith<IllegalArgumentException> {
            engineRepo.activateEngine(draft.id)
        }

        val publishedSecond = engineRepo.publishEngine(draft.id)
        assertEquals("published", publishedSecond.status)

        val replaced = engineRepo.activateEngine(publishedSecond.id)
        assertTrue(replaced.isActive)
        val activeEngine = engineRepo.getActiveEngine()
        assertNotNull(activeEngine)
        assertEquals(publishedSecond.id, activeEngine.id)
    }

    @Test
    fun `retired persona preserves core-version history`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val personaRepo = PersonaRepository(db)
        val versionRepo = PersonaCoreVersionRepository(db)

        val persona = personaRepo.create(
            slug = "rhea",
            displayName = "Rhea",
            gender = "female",
            orientation = "straight",
            apparentAge = 34,
            languageProfile = mapOf("primary" to "en"),
        )

        val v1 = versionRepo.create(persona.id, 1, "old", "draft")
        val published = versionRepo.publishCoreVersion(v1.id)
        personaRepo.activateCoreVersion(persona.id, published.id)
        personaRepo.retirePersona(persona.id)

        val stored = personaRepo.findById(persona.id)
        assertNotNull(stored)
        assertEquals("retired", stored.status)

        val history = versionRepo.findById(published.id)
        assertNotNull(history)
        assertEquals(published.id, history.id)
        assertEquals("published", history.status)
    }
}
