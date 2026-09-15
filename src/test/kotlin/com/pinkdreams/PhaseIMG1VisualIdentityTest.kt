package com.pinkdreams

import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.PersonaIdentityRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class PhaseIMG1VisualIdentityTest {

    @Test
    fun `IMG-1 - Create persona identity`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val identity = identityRepo.create()

        assertNotNull(identity.id, "Identity should have an ID")
        assertNull(identity.activeVisualVersionId, "New identity should have no active visual version")
    }

    @Test
    fun `IMG-1 - Retrieve persona identity`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val created = identityRepo.create()
        val retrieved = identityRepo.findById(created.id)

        assertNotNull(retrieved, "Should retrieve created identity")
        assertEquals(created.id, retrieved.id, "Retrieved ID should match")
    }

    @Test
    fun `IMG-1 - Create draft visual version`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)

        val identity = identityRepo.create()
        val version = versionRepo.create(
            personaIdentityId = identity.id,
            version = 1,
            physicalGuide = """{"face":"oval","hair":"brown"}""",
            styleConstraints = """{"style":"elegant"}"""
        )

        assertEquals(identity.id, version.personaIdentityId, "Version should belong to identity")
        assertEquals(1, version.version, "Version number should be 1")
        assertEquals("draft", version.status, "New version should be draft")
    }

    @Test
    fun `IMG-1 - Retrieve draft visual version`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)

        val identity = identityRepo.create()
        val created = versionRepo.create(
            personaIdentityId = identity.id,
            version = 1,
            physicalGuide = """{"face":"oval"}"""
        )

        val retrieved = versionRepo.findById(created.id)
        assertNotNull(retrieved, "Should retrieve created version")
        assertEquals(created.id, retrieved.id, "Retrieved version ID should match")
        assertEquals("""{"face":"oval"}""", retrieved.physicalGuide, "Physical guide should match")
    }

    @Test
    fun `IMG-1 - Update draft visual version - physical guide`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)

        val identity = identityRepo.create()
        val version = versionRepo.create(
            personaIdentityId = identity.id,
            version = 1,
            physicalGuide = """{"face":"oval"}"""
        )

        val updated = versionRepo.updatePhysicalGuide(version.id, """{"face":"oval","hair":"black"}""")
        assertEquals("""{"face":"oval","hair":"black"}""", updated.physicalGuide, "Physical guide should be updated")
    }

    @Test
    fun `IMG-1 - Update draft visual version - style constraints`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)

        val identity = identityRepo.create()
        val version = versionRepo.create(
            personaIdentityId = identity.id,
            version = 1,
            styleConstraints = """{"style":"casual"}"""
        )

        val updated = versionRepo.updateStyleConstraints(version.id, """{"style":"elegant","color":"blue"}""")
        assertEquals("""{"style":"elegant","color":"blue"}""", updated.styleConstraints, "Style constraints should be updated")
    }

    @Test
    fun `IMG-1 - Publish visual version transitions draft to published`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)

        val identity = identityRepo.create()
        val draft = versionRepo.create(personaIdentityId = identity.id, version = 1)

        val published = versionRepo.publishVisualVersion(draft.id)
        assertEquals("published", published.status, "Version should be published")
    }

    @Test
    fun `IMG-1 - Archive visual version transitions published to archived`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)

        val identity = identityRepo.create()
        val draft = versionRepo.create(personaIdentityId = identity.id, version = 1)
        val published = versionRepo.publishVisualVersion(draft.id)

        val archived = versionRepo.archiveVisualVersion(published.id)
        assertEquals("archived", archived.status, "Version should be archived")
    }

    @Test
    fun `IMG-1 - Activate published visual version sets active reference`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)

        val identity = identityRepo.create()
        val draft = versionRepo.create(personaIdentityId = identity.id, version = 1)
        val published = versionRepo.publishVisualVersion(draft.id)

        versionRepo.activateVisualVersion(identity.id, published.id)

        val reloadedIdentity = identityRepo.findById(identity.id)
        assertNotNull(reloadedIdentity, "Identity should exist")
        assertEquals(published.id, reloadedIdentity.activeVisualVersionId, "Active version should be set")
    }

    @Test
    fun `IMG-1 - Find active visual version returns current active`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)

        val identity = identityRepo.create()
        val v1 = versionRepo.create(personaIdentityId = identity.id, version = 1)
        val v1Published = versionRepo.publishVisualVersion(v1.id)
        versionRepo.activateVisualVersion(identity.id, v1Published.id)

        val active = versionRepo.findActiveForPersonaIdentity(identity.id)
        assertNotNull(active, "Should find active version")
        assertEquals(v1Published.id, active.id, "Active version should match")
    }

    @Test
    fun `IMG-1 - Only one active visual version per identity`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)

        val identity = identityRepo.create()
        val v1 = versionRepo.create(personaIdentityId = identity.id, version = 1)
        val v1Published = versionRepo.publishVisualVersion(v1.id)
        versionRepo.activateVisualVersion(identity.id, v1Published.id)

        val v2 = versionRepo.create(personaIdentityId = identity.id, version = 2)
        val v2Published = versionRepo.publishVisualVersion(v2.id)
        versionRepo.activateVisualVersion(identity.id, v2Published.id)

        val active = versionRepo.findActiveForPersonaIdentity(identity.id)
        assertEquals(v2Published.id, active?.id, "Only v2 should be active")
    }

    @Test
    fun `IMG-1 - Retrieve all versions for identity history`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)

        val identity = identityRepo.create()
        versionRepo.create(personaIdentityId = identity.id, version = 1)
        versionRepo.create(personaIdentityId = identity.id, version = 2)
        versionRepo.create(personaIdentityId = identity.id, version = 3)

        val all = versionRepo.findForPersonaIdentity(identity.id)
        assertEquals(3, all.size, "Should retrieve all 3 versions")
    }

    @Test
    fun `IMG-1 - Application-level immutability - cannot update published version`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)

        val identity = identityRepo.create()
        val draft = versionRepo.create(personaIdentityId = identity.id, version = 1)
        val published = versionRepo.publishVisualVersion(draft.id)

        try {
            versionRepo.updatePhysicalGuide(published.id, """{"new":"content"}""")
            fail("Should not allow update of published version")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("immutable") ?: false, "Error should mention immutability")
        }
    }

    @Test
    fun `IMG-1 - Application-level immutability - cannot update archived version`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)

        val identity = identityRepo.create()
        val draft = versionRepo.create(personaIdentityId = identity.id, version = 1)
        val published = versionRepo.publishVisualVersion(draft.id)
        val archived = versionRepo.archiveVisualVersion(published.id)

        try {
            versionRepo.updateStyleConstraints(archived.id, """{"new":"style"}""")
            fail("Should not allow update of archived version")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("immutable") ?: false, "Error should mention immutability")
        }
    }

    @Test
    fun `IMG-1 - Cannot publish non-draft version`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)

        val identity = identityRepo.create()
        val draft = versionRepo.create(personaIdentityId = identity.id, version = 1)
        val published = versionRepo.publishVisualVersion(draft.id)

        try {
            versionRepo.publishVisualVersion(published.id)
            fail("Should not allow publishing already published version")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("draft") ?: false, "Error should mention draft requirement")
        }
    }

    @Test
    fun `IMG-1 - Cannot archive non-published version`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)

        val identity = identityRepo.create()
        val draft = versionRepo.create(personaIdentityId = identity.id, version = 1)

        try {
            versionRepo.archiveVisualVersion(draft.id)
            fail("Should not allow archiving draft version")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("published") ?: false, "Error should mention published requirement")
        }
    }

    @Test
    fun `IMG-1 - Cannot archive active version`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)

        val identity = identityRepo.create()
        val draft = versionRepo.create(personaIdentityId = identity.id, version = 1)
        val published = versionRepo.publishVisualVersion(draft.id)
        versionRepo.activateVisualVersion(identity.id, published.id)

        try {
            versionRepo.archiveVisualVersion(published.id)
            fail("Should not allow archiving active version")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.lowercase()?.contains("active") ?: false, "Error should mention active version")
        }
    }

    @Test
    fun `IMG-1 - Can only activate published versions`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)

        val identity = identityRepo.create()
        val draft = versionRepo.create(personaIdentityId = identity.id, version = 1)

        try {
            versionRepo.activateVisualVersion(identity.id, draft.id)
            fail("Should not allow activating draft version")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("published") ?: false, "Error should mention published requirement")
        }
    }

    @Test
    fun `IMG-1 - Version must belong to identity when activating`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)

        val identity1 = identityRepo.create()
        val identity2 = identityRepo.create()

        val v1 = versionRepo.create(personaIdentityId = identity1.id, version = 1)
        val v1Published = versionRepo.publishVisualVersion(v1.id)

        try {
            versionRepo.activateVisualVersion(identity2.id, v1Published.id)
            fail("Should not allow activating version from different identity")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("belong") ?: false, "Error should mention ownership")
        }
    }

    @Test
    fun `IMG-1 - Find draft for identity returns null when no draft exists`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)

        val identity = identityRepo.create()

        val draft = versionRepo.findDraftForPersonaIdentity(identity.id)
        assertNull(draft, "Should return null when no draft exists")
    }

    @Test
    fun `IMG-1 - Find active for identity returns null when no active version`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)

        val identity = identityRepo.create()

        val active = versionRepo.findActiveForPersonaIdentity(identity.id)
        assertNull(active, "Should return null when no active version")
    }
}
