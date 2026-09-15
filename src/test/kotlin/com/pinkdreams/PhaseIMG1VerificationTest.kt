package com.pinkdreams

import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.PersonaIdentityRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import kotlin.test.Test
import kotlin.test.assertTrue

class PhaseIMG1VerificationTest {

    @Test
    fun `Verify activation semantics - previous version stays published`() {
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

        val v1Retrieved = versionRepo.findById(v1Published.id)
        assertTrue(v1Retrieved?.status == "published", "Architectural requirement: Previous active version remains published (not auto-archived). Current implementation satisfies this.")

        val currentActive = versionRepo.findActiveForPersonaIdentity(identity.id)
        assertTrue(currentActive?.id == v2Published.id, "New version becomes active")
    }

    @Test
    fun `Verify version numbering is application-managed (non-sequential allowed)`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)

        val identity = identityRepo.create()

        val v1 = versionRepo.create(personaIdentityId = identity.id, version = 1)
        val v5 = versionRepo.create(personaIdentityId = identity.id, version = 5)
        val v3 = versionRepo.create(personaIdentityId = identity.id, version = 3)

        assertTrue(v1.version == 1)
        assertTrue(v5.version == 5)
        assertTrue(v3.version == 3)

        val all = versionRepo.findForPersonaIdentity(identity.id)
        assertTrue(all.size == 3, "Non-sequential version numbers allowed: schema has UNIQUE(persona_identity_id, version) but not auto-increment. Caller manages version numbers.")
    }
}
