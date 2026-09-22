package com.pinkdreams.visual.identity

import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.PersonaIdentityRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import com.pinkdreams.persistence.repositories.PersonalGuideRepository
import com.pinkdreams.persistence.repositories.ReferenceImageRepository
import com.pinkdreams.storage.InMemoryObjectStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonaVisualAdminServiceTest {
    @Test
    fun `generation context excludes private references by default and uses updated hair`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val storage = InMemoryObjectStorage()
        val personaRepo = PersonaRepository(db)
        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)
        val guideRepo = PersonalGuideRepository(db)
        val refRepo = ReferenceImageRepository(db, storage)
        val service = PersonaVisualAdminService(personaRepo, identityRepo, versionRepo, guideRepo, refRepo)

        val persona = personaRepo.create(
            slug = "svc-${java.util.UUID.randomUUID()}",
            displayName = "Svc",
            gender = "female",
            orientation = "straight",
            apparentAge = 25,
            languageProfile = emptyMap(),
        )
        service.ensureDraft(persona.id)
        service.updatePhysicalGuide(
            persona.id,
            PhysicalGuide(
                agePresentation = AgePresentation(25, true),
                hair = Hair(color = "black", length = "long"),
            ),
        )
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53,
            0xDE.toByte(), 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
            0x54, 0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(), 0xCF.toByte(), 0xC0.toByte(), 0x00,
            0x00, 0x00, 0x03, 0x00, 0x01, 0x00, 0x05, 0xFE.toByte(),
            0xD4.toByte(), 0xEF.toByte(), 0x00, 0x00, 0x00, 0x00, 0x49, 0x45,
            0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
        service.uploadReference(persona.id, ReferenceRole.FRONT, png, "image/png")
        service.uploadReference(persona.id, ReferenceRole.PRIVATE, png, "image/png")
        service.publishAndActivateDraft(persona.id)

        val ctx = service.resolveForGeneration(persona.id, includePrivateReferences = false)
        assertTrue(ctx.referenceImages.any { it.role == ReferenceRole.FRONT })
        assertFalse(ctx.referenceImages.any { it.role == ReferenceRole.PRIVATE })
        assertTrue(ctx.visualVersion.physicalGuide.contains("black"))

        // New draft with new hair; until published, generation still uses active published
        service.ensureDraft(persona.id)
        service.updatePhysicalGuide(
            persona.id,
            PhysicalGuide(
                agePresentation = AgePresentation(25, true),
                hair = Hair(color = "auburn", length = "long"),
            ),
        )
        val stillOld = service.resolveForGeneration(persona.id)
        assertTrue(stillOld.visualVersion.physicalGuide.contains("black"))
        assertEquals("published", stillOld.visualVersion.status)

        service.publishAndActivateDraft(persona.id)
        val updated = service.resolveForGeneration(persona.id)
        assertTrue(updated.visualVersion.physicalGuide.contains("auburn"))
    }
}
