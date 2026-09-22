package com.pinkdreams.visual.identity

import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.PersonaIdentityRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import com.pinkdreams.persistence.repositories.PersonalGuideRepository
import com.pinkdreams.persistence.repositories.ReferenceImageRepository
import com.pinkdreams.storage.InMemoryObjectStorage
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class ImagePipelineTestPersonaSeederTest {
    @Test
    fun `seed ananya and richa from docs fixtures when pics exist`() {
        val picsRoot = Path.of("docs/23 sept/persona testing data")
        val ananyaDir = picsRoot.resolve("anayna pics").toFile()
        if (!ananyaDir.isDirectory || ananyaDir.listFiles().isNullOrEmpty()) {
            // Workspace without fixture images — skip rather than fail CI
            return
        }

        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val storage = InMemoryObjectStorage()
        val personaRepo = PersonaRepository(db)
        val service = PersonaVisualAdminService(
            personaRepository = personaRepo,
            personaIdentityRepository = PersonaIdentityRepository(db),
            visualVersionRepository = PersonaVisualVersionRepository(db),
            personalGuideRepository = PersonalGuideRepository(db),
            referenceImageRepository = ReferenceImageRepository(db, storage),
        )
        val seeder = ImagePipelineTestPersonaSeeder(personaRepo, service, picsRoot)
        val results = seeder.seedAll()
        assertTrue(results.size == 2)
        assertTrue(results.any { it.slug == "ananya_rajput" && it.referencesUploaded > 0 })
        assertTrue(results.any { it.slug == "richa_mehta" && it.referencesUploaded > 0 })
        // Idempotent second run
        val again = seeder.seedAll()
        assertTrue(again.all { !it.created })
    }
}
