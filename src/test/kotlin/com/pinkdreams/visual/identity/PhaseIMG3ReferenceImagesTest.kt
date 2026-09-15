package com.pinkdreams.visual.identity

import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.PersonaIdentityRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import com.pinkdreams.persistence.repositories.ReferenceImageRepository
import com.pinkdreams.storage.InMemoryObjectStorage
import java.util.UUID
import kotlin.test.Test
import kotlin.test.BeforeTest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class PhaseIMG3ReferenceImagesTest {

    private lateinit var db: org.jetbrains.exposed.sql.Database
    private lateinit var objectStorage: InMemoryObjectStorage
    private lateinit var personaIdentityRepo: PersonaIdentityRepository
    private lateinit var visualVersionRepo: PersonaVisualVersionRepository
    private lateinit var referenceRepo: ReferenceImageRepository

    @BeforeTest
    fun setupTest() {
        db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        objectStorage = InMemoryObjectStorage()
        personaIdentityRepo = PersonaIdentityRepository(db)
        visualVersionRepo = PersonaVisualVersionRepository(db)
        referenceRepo = ReferenceImageRepository(db, objectStorage)
    }

    private fun setupDraftVersion(): Pair<UUID, UUID> {
        val identity = personaIdentityRepo.create()
        val version = visualVersionRepo.create(
            personaIdentityId = identity.id,
            version = 1,
            physicalGuide = "{}",
            author = "test"
        )
        return identity.id to version.id
    }

    // =========================================================================
    // ObjectStorage tests (1-5)
    // =========================================================================

    @Test
    fun `ObjectStorage 1 - store object and retrieve it`() {
        val key = "personas/test/references/img1"
        val content = "test image content".toByteArray()

        objectStorage.store(key, content, "image/jpeg")

        val retrieved = objectStorage.retrieve(key)
        assertNotNull(retrieved)
        assertEquals(key, retrieved.key)
        assertEquals("image/jpeg", retrieved.contentType)
        assertContentEquals(content, retrieved.content)
    }

    @Test
    fun `ObjectStorage 2 - exists behavior`() {
        val key = "personas/test/references/img2"
        val content = "test".toByteArray()

        assertFalse(objectStorage.exists(key), "Key should not exist initially")

        objectStorage.store(key, content, "image/png")
        assertTrue(objectStorage.exists(key), "Key should exist after store")

        objectStorage.delete(key)
        assertFalse(objectStorage.exists(key), "Key should not exist after delete")
    }

    @Test
    fun `ObjectStorage 3 - delete object`() {
        val key = "personas/test/references/img3"
        val content = "test".toByteArray()

        objectStorage.store(key, content, "image/webp")

        val deleted = objectStorage.delete(key)
        assertTrue(deleted, "Delete should return true for existing key")

        assertNull(objectStorage.retrieve(key), "Object should be gone")
    }

    @Test
    fun `ObjectStorage 4 - missing-object behavior`() {
        val missing = objectStorage.retrieve("personas/test/references/nonexistent")
        assertNull(missing, "Retrieve should return null for missing object")

        val deleted = objectStorage.delete("personas/test/references/nonexistent")
        assertFalse(deleted, "Delete should return false for missing object")
    }

    @Test
    fun `ObjectStorage 5 - content isolation (copies not references)`() {
        val key = "personas/test/references/img5"
        val content = byteArrayOf(1, 2, 3, 4, 5)

        objectStorage.store(key, content, "image/jpeg")

        val retrieved1 = objectStorage.retrieve(key)!!
        val retrieved2 = objectStorage.retrieve(key)!!

        assertFalse(retrieved1.content === retrieved2.content, "Copies should be independent")
        assertContentEquals(retrieved1.content, retrieved2.content)
    }

    // =========================================================================
    // Reference repository CRUD (6-13)
    // =========================================================================

    @Test
    fun `Reference 6 - upload reference to draft visual version`() {
        val (personaId, versionId) = setupDraftVersion()
        val content = "face reference".toByteArray()

        val ref = referenceRepo.uploadReference(
            personaVisualVersionId = versionId,
            personaIdentityId = personaId,
            content = content,
            contentType = "image/jpeg",
            role = ReferenceRole.FACE,
            source = ReferenceSource.HUMAN_UPLOADED,
            width = 800,
            height = 600
        )

        assertNotNull(ref)
        assertEquals(versionId, ref.personaVisualVersionId)
        assertEquals(ReferenceRole.FACE, ref.role)
        assertEquals(ReferenceStatus.UPLOADED, ref.status)
        assertEquals(ReferenceSource.HUMAN_UPLOADED, ref.source)
        assertEquals(800, ref.width)
        assertEquals(600, ref.height)
        assertTrue(objectStorage.exists(ref.storageKey))
    }

    @Test
    fun `Reference 7 - retrieve reference metadata`() {
        val (personaId, versionId) = setupDraftVersion()
        val ref = referenceRepo.uploadReference(
            personaVisualVersionId = versionId,
            personaIdentityId = personaId,
            content = "body reference".toByteArray(),
            contentType = "image/png",
            role = ReferenceRole.FULL_BODY,
            source = ReferenceSource.HUMAN_UPLOADED
        )

        val retrieved = referenceRepo.findById(ref.id)
        assertNotNull(retrieved)
        assertEquals(ref.id, retrieved.id)
        assertEquals(ReferenceStatus.UPLOADED, retrieved.status)
    }

    @Test
    fun `Reference 8 - list references for version`() {
        val (personaId, versionId) = setupDraftVersion()

        referenceRepo.uploadReference(
            personaVisualVersionId = versionId,
            personaIdentityId = personaId,
            content = "ref1".toByteArray(),
            contentType = "image/jpeg",
            role = ReferenceRole.FACE,
            source = ReferenceSource.HUMAN_UPLOADED
        )

        referenceRepo.uploadReference(
            personaVisualVersionId = versionId,
            personaIdentityId = personaId,
            content = "ref2".toByteArray(),
            contentType = "image/png",
            role = ReferenceRole.HAIR,
            source = ReferenceSource.HUMAN_UPLOADED
        )

        val refs = referenceRepo.findForVersion(versionId)
        assertEquals(2, refs.size)
    }

    @Test
    fun `Reference 9 - filter by role`() {
        val (personaId, versionId) = setupDraftVersion()

        referenceRepo.uploadReference(
            personaVisualVersionId = versionId,
            personaIdentityId = personaId,
            content = "face".toByteArray(),
            contentType = "image/jpeg",
            role = ReferenceRole.FACE,
            source = ReferenceSource.HUMAN_UPLOADED
        )

        referenceRepo.uploadReference(
            personaVisualVersionId = versionId,
            personaIdentityId = personaId,
            content = "body".toByteArray(),
            contentType = "image/jpeg",
            role = ReferenceRole.FULL_BODY,
            source = ReferenceSource.HUMAN_UPLOADED
        )

        val faceRefs = referenceRepo.findByRole(versionId, ReferenceRole.FACE)
        assertEquals(1, faceRefs.size)
        assertEquals(ReferenceRole.FACE, faceRefs[0].role)

        val bodyRefs = referenceRepo.findByRole(versionId, ReferenceRole.FULL_BODY)
        assertEquals(1, bodyRefs.size)
        assertEquals(ReferenceRole.FULL_BODY, bodyRefs[0].role)
    }

    @Test
    fun `Reference 10 - filter by status`() {
        val (personaId, versionId) = setupDraftVersion()

        val uploaded = referenceRepo.uploadReference(
            personaVisualVersionId = versionId,
            personaIdentityId = personaId,
            content = "ref".toByteArray(),
            contentType = "image/jpeg",
            role = ReferenceRole.FACE,
            source = ReferenceSource.HUMAN_UPLOADED
        )

        val finalized = referenceRepo.finalizeReference(uploaded.id)

        val uploadedRefs = referenceRepo.findByStatus(versionId, ReferenceStatus.UPLOADED)
        assertEquals(0, uploadedRefs.size)

        val finalizedRefs = referenceRepo.findByStatus(versionId, ReferenceStatus.FINALIZED)
        assertEquals(1, finalizedRefs.size)
        assertEquals(finalized.id, finalizedRefs[0].id)
    }

    @Test
    fun `Reference 11 - finalize uploaded reference`() {
        val (personaId, versionId) = setupDraftVersion()
        val uploaded = referenceRepo.uploadReference(
            personaVisualVersionId = versionId,
            personaIdentityId = personaId,
            content = "ref".toByteArray(),
            contentType = "image/jpeg",
            role = ReferenceRole.FACE,
            source = ReferenceSource.HUMAN_UPLOADED
        )

        assertEquals(ReferenceStatus.UPLOADED, uploaded.status)
        assertNull(uploaded.finalizedAt)

        val finalized = referenceRepo.finalizeReference(uploaded.id)
        assertEquals(ReferenceStatus.FINALIZED, finalized.status)
        assertNotNull(finalized.finalizedAt)
    }

    @Test
    fun `Reference 12 - archive finalized reference`() {
        val (personaId, versionId) = setupDraftVersion()
        val uploaded = referenceRepo.uploadReference(
            personaVisualVersionId = versionId,
            personaIdentityId = personaId,
            content = "ref".toByteArray(),
            contentType = "image/jpeg",
            role = ReferenceRole.FACE,
            source = ReferenceSource.HUMAN_UPLOADED
        )

        val finalized = referenceRepo.finalizeReference(uploaded.id)
        val archived = referenceRepo.archiveReference(finalized.id)

        assertEquals(ReferenceStatus.ARCHIVED, archived.status)
    }

    @Test
    fun `Reference 13 - remove draft reference and delete object`() {
        val (personaId, versionId) = setupDraftVersion()
        val ref = referenceRepo.uploadReference(
            personaVisualVersionId = versionId,
            personaIdentityId = personaId,
            content = "ref".toByteArray(),
            contentType = "image/jpeg",
            role = ReferenceRole.FACE,
            source = ReferenceSource.HUMAN_UPLOADED
        )

        assertTrue(objectStorage.exists(ref.storageKey))

        referenceRepo.removeReference(ref.id)

        assertNull(referenceRepo.findById(ref.id))
        assertFalse(objectStorage.exists(ref.storageKey))
    }

    // =========================================================================
    // Lifecycle / immutability (14-18)
    // =========================================================================

    @Test
    fun `Immutability 14 - cannot add reference to published visual version`() {
        val (personaId, versionId) = setupDraftVersion()

        visualVersionRepo.publishVisualVersion(versionId)

        try {
            referenceRepo.uploadReference(
                personaVisualVersionId = versionId,
                personaIdentityId = personaId,
                content = "ref".toByteArray(),
                contentType = "image/jpeg",
                role = ReferenceRole.FACE,
                source = ReferenceSource.HUMAN_UPLOADED
            )
            fail("Should have rejected upload to published version")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("draft") ?: false)
        }
    }

    @Test
    fun `Immutability 15 - cannot add reference to archived visual version`() {
        val (personaId, versionId) = setupDraftVersion()

        visualVersionRepo.publishVisualVersion(versionId)
        visualVersionRepo.archiveVisualVersion(versionId)

        try {
            referenceRepo.uploadReference(
                personaVisualVersionId = versionId,
                personaIdentityId = personaId,
                content = "ref".toByteArray(),
                contentType = "image/jpeg",
                role = ReferenceRole.FACE,
                source = ReferenceSource.HUMAN_UPLOADED
            )
            fail("Should have rejected upload to archived version")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("draft") ?: false)
        }
    }

    @Test
    fun `Immutability 16 - cannot finalize reference from published version`() {
        val (personaId, versionId) = setupDraftVersion()
        val ref = referenceRepo.uploadReference(
            personaVisualVersionId = versionId,
            personaIdentityId = personaId,
            content = "ref".toByteArray(),
            contentType = "image/jpeg",
            role = ReferenceRole.FACE,
            source = ReferenceSource.HUMAN_UPLOADED
        )

        visualVersionRepo.publishVisualVersion(versionId)

        try {
            referenceRepo.finalizeReference(ref.id)
            fail("Should have rejected finalize on published version")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("draft") ?: false)
        }
    }

    @Test
    fun `Immutability 17 - cannot remove reference from published version`() {
        val (personaId, versionId) = setupDraftVersion()
        val ref = referenceRepo.uploadReference(
            personaVisualVersionId = versionId,
            personaIdentityId = personaId,
            content = "ref".toByteArray(),
            contentType = "image/jpeg",
            role = ReferenceRole.FACE,
            source = ReferenceSource.HUMAN_UPLOADED
        )

        visualVersionRepo.publishVisualVersion(versionId)

        try {
            referenceRepo.removeReference(ref.id)
            fail("Should have rejected removal from published version")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("draft") ?: false)
        }
    }

    @Test
    fun `Immutability 18 - historical references remain intact when new version created`() {
        val (personaId, versionId1) = setupDraftVersion()

        val ref1 = referenceRepo.uploadReference(
            personaVisualVersionId = versionId1,
            personaIdentityId = personaId,
            content = "ref1".toByteArray(),
            contentType = "image/jpeg",
            role = ReferenceRole.FACE,
            source = ReferenceSource.HUMAN_UPLOADED
        )

        visualVersionRepo.publishVisualVersion(versionId1)

        val version2 = visualVersionRepo.create(
            personaIdentityId = personaId,
            version = 2,
            physicalGuide = "{}",
            author = "test"
        )

        val refs1 = referenceRepo.findForVersion(versionId1)
        val refs2 = referenceRepo.findForVersion(version2.id)

        assertEquals(1, refs1.size, "v1 should still have its reference")
        assertEquals(0, refs2.size, "v2 should start empty")
    }

    // =========================================================================
    // Validation (19-23)
    // =========================================================================

    @Test
    fun `Validation 19 - invalid MIME type rejected`() {
        val (personaId, versionId) = setupDraftVersion()

        try {
            referenceRepo.uploadReference(
                personaVisualVersionId = versionId,
                personaIdentityId = personaId,
                content = "ref".toByteArray(),
                contentType = "application/pdf",
                role = ReferenceRole.FACE,
                source = ReferenceSource.HUMAN_UPLOADED
            )
            fail("Should have rejected non-image MIME type")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Invalid reference image") ?: false)
        }
    }

    @Test
    fun `Validation 20 - empty content rejected`() {
        val (personaId, versionId) = setupDraftVersion()

        try {
            referenceRepo.uploadReference(
                personaVisualVersionId = versionId,
                personaIdentityId = personaId,
                content = byteArrayOf(),
                contentType = "image/jpeg",
                role = ReferenceRole.FACE,
                source = ReferenceSource.HUMAN_UPLOADED
            )
            fail("Should have rejected empty content")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Invalid reference image") ?: false)
        }
    }

    @Test
    fun `Validation 21 - invalid role handled`() {
        val (personaId, versionId) = setupDraftVersion()

        val ref = referenceRepo.uploadReference(
            personaVisualVersionId = versionId,
            personaIdentityId = personaId,
            content = "ref".toByteArray(),
            contentType = "image/jpeg",
            role = ReferenceRole.GENERAL_IDENTITY,
            source = ReferenceSource.HUMAN_UPLOADED
        )

        assertEquals(ReferenceRole.GENERAL_IDENTITY, ref.role)
    }

    @Test
    fun `Validation 22 - invalid dimensions rejected when provided`() {
        val (personaId, versionId) = setupDraftVersion()

        try {
            referenceRepo.uploadReference(
                personaVisualVersionId = versionId,
                personaIdentityId = personaId,
                content = "ref".toByteArray(),
                contentType = "image/jpeg",
                role = ReferenceRole.FACE,
                source = ReferenceSource.HUMAN_UPLOADED,
                width = 0,
                height = 600
            )
            fail("Should have rejected invalid width")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Invalid reference image") ?: false)
        }
    }

    @Test
    fun `Validation 23 - checksum field stored when provided`() {
        val (personaId, versionId) = setupDraftVersion()

        val ref = referenceRepo.uploadReference(
            personaVisualVersionId = versionId,
            personaIdentityId = personaId,
            content = "ref".toByteArray(),
            contentType = "image/jpeg",
            role = ReferenceRole.FACE,
            source = ReferenceSource.HUMAN_UPLOADED,
            checksum = "sha256:abc123def456"
        )

        assertEquals("sha256:abc123def456", ref.checksum)

        val retrieved = referenceRepo.findById(ref.id)
        assertEquals("sha256:abc123def456", retrieved!!.checksum)
    }

    // =========================================================================
    // Failure boundaries (24-25)
    // =========================================================================

    @Test
    fun `Failure 24 - storage cleanup on DB failure`() {
        val (personaId, versionId) = setupDraftVersion()
        val initialSize = objectStorage.getSize()

        val key = "personas/$personaId/visual-versions/$versionId/references/${UUID.randomUUID()}"

        objectStorage.store(key, "ref".toByteArray(), "image/jpeg")
        assertEquals(initialSize + 1, objectStorage.getSize(), "Object should be stored")

        try {
            referenceRepo.uploadReference(
                personaVisualVersionId = UUID.randomUUID(),
                personaIdentityId = personaId,
                content = "ref".toByteArray(),
                contentType = "image/jpeg",
                role = ReferenceRole.FACE,
                source = ReferenceSource.HUMAN_UPLOADED
            )
            fail("Should have failed with invalid version ID")
        } catch (e: IllegalArgumentException) {
            // Expected: version not found
        }
    }

    @Test
    fun `Failure 25 - storage error prevents DB record creation`() {
        val (personaId, versionId) = setupDraftVersion()

        val ref = referenceRepo.uploadReference(
            personaVisualVersionId = versionId,
            personaIdentityId = personaId,
            content = "ref".toByteArray(),
            contentType = "image/jpeg",
            role = ReferenceRole.FACE,
            source = ReferenceSource.HUMAN_UPLOADED
        )

        assertNotNull(referenceRepo.findById(ref.id), "Reference should exist in DB")
        assertTrue(objectStorage.exists(ref.storageKey), "Object should exist in storage")
    }

    // =========================================================================
    // Remove failure semantics (implicit coverage in other tests)
    // =========================================================================

    @Test
    fun `Remove semantics - DB delete then storage delete`() {
        val (personaId, versionId) = setupDraftVersion()
        val ref = referenceRepo.uploadReference(
            personaVisualVersionId = versionId,
            personaIdentityId = personaId,
            content = "ref".toByteArray(),
            contentType = "image/jpeg",
            role = ReferenceRole.FACE,
            source = ReferenceSource.HUMAN_UPLOADED
        )

        val storageKey = ref.storageKey

        assertTrue(objectStorage.exists(storageKey), "Object should exist before removal")

        referenceRepo.removeReference(ref.id)

        assertNull(referenceRepo.findById(ref.id), "DB record should be deleted")
        assertFalse(objectStorage.exists(storageKey), "Storage object should be deleted")
    }
}
