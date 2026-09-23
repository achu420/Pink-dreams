package com.pinkdreams.visual.identity

import com.pinkdreams.imaging.orchestration.GeneratedCandidateRepository
import com.pinkdreams.imaging.orchestration.ImageGenerationOrchestrator
import com.pinkdreams.imaging.orchestration.ImageGenerationService
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ImageJobRepository
import com.pinkdreams.persistence.repositories.PersonaCoreVersionRepository
import com.pinkdreams.persistence.repositories.PersonaIdentityRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import com.pinkdreams.persistence.repositories.PersonalGuideRepository
import com.pinkdreams.persistence.repositories.ReferenceImageRepository
import com.pinkdreams.persistence.repositories.WardrobeRepository
import com.pinkdreams.storage.InMemoryObjectStorage
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourcePersonaPackageSeederTest {
    @Test
    fun `seed anaya zoya pihu is idempotent and links visual identity images`() {
        val root = Path.of("docs/personas")
        if (!root.resolve("Aanya").toFile().isDirectory) return

        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val storage = InMemoryObjectStorage()
        val personaRepo = PersonaRepository(db)
        val visualAdmin = PersonaVisualAdminService(
            personaRepository = personaRepo,
            personaIdentityRepository = PersonaIdentityRepository(db),
            visualVersionRepository = PersonaVisualVersionRepository(db),
            personalGuideRepository = PersonalGuideRepository(db),
            referenceImageRepository = ReferenceImageRepository(db, storage),
        )
        val seeder = SourcePersonaPackageSeeder(
            personaRepository = personaRepo,
            coreVersionRepository = PersonaCoreVersionRepository(db),
            visualAdminService = visualAdmin,
            referenceImageRepository = ReferenceImageRepository(db, storage),
            docsRoot = root,
        )
        val first = seeder.seedAll()
        assertEquals(3, first.size)
        assertTrue(first.any { it.slug == "anaya" && it.imagesUsable >= 1 })
        assertTrue(first.any { it.slug == "zoya" && it.imagesUsable >= 1 })
        assertTrue(first.any { it.slug == "pihu" && it.imagesUsable >= 1 })
        first.forEach {
            assertTrue(it.visualVersionId != null)
            assertTrue(personaRepo.findPersonaIdentityId(java.util.UUID.fromString(it.personaId)) != null)
        }
        val beforeCount = personaRepo.findAll().count { it.slug in setOf("anaya", "zoya", "pihu") }
        val second = seeder.seedAll()
        assertTrue(second.all { !it.created })
        assertEquals(beforeCount, personaRepo.findAll().count { it.slug in setOf("anaya", "zoya", "pihu") })

        val anaya = personaRepo.findAll().first { it.slug == "anaya" }
        val jobRepo = ImageJobRepository(db)
        val generation = ImageGenerationService(
            personaRepository = personaRepo,
            visualVersionRepository = PersonaVisualVersionRepository(db),
            wardrobeRepository = WardrobeRepository(db),
            referenceImageRepository = ReferenceImageRepository(db, storage),
            orchestrator = ImageGenerationOrchestrator(
                compiler = com.pinkdreams.imaging.compiler.PromptCompiler(),
                jobRepository = jobRepo,
            ),
            jobRepository = jobRepo,
            candidateRepository = GeneratedCandidateRepository(db),
        )
        val created = generation.create(
            ImageGenerationService.CreateCommand(
                personaId = anaya.id,
                idempotencyKey = "task31-anaya-smoke",
                seedPrompt = "A photorealistic lifestyle photograph of the adult Persona sitting at a stylish cafe in natural evening light, looking naturally toward the camera. Realistic skin texture, natural hair, realistic eyes, natural body proportions, authentic environment and lighting, candid professional photography.",
                candidateCount = 1,
                widthPx = 1024,
                heightPx = 1024,
            )
        )
        assertTrue(created.job.id.toString().isNotBlank())
        assertEquals(com.pinkdreams.imaging.job.ImageJobStatus.QUEUED, created.job.status)
    }
}
