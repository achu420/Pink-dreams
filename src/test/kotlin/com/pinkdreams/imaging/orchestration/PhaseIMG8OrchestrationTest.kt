package com.pinkdreams.imaging.orchestration

import com.pinkdreams.imaging.job.ImageJobResult
import com.pinkdreams.imaging.provider.GeneratedCandidate
import com.pinkdreams.imaging.provider.GenerationResult
import com.pinkdreams.imaging.provider.GenerationStatus
import com.pinkdreams.imaging.provider.ProviderJobHandle
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.PersonaIdentityRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import com.pinkdreams.persistence.repositories.ReferenceImageRepository
import com.pinkdreams.storage.ObjectStorage
import com.pinkdreams.storage.StorageMetadata
import com.pinkdreams.storage.StorageObject
import com.pinkdreams.visual.identity.ReferenceRole
import com.pinkdreams.visual.identity.ReferenceSource
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhaseIMG8OrchestrationTest {
    private lateinit var db: org.jetbrains.exposed.sql.Database
    private lateinit var candidateRepo: GeneratedCandidateRepository
    private lateinit var handler: ImageGenerationHandler
    private lateinit var storage: TestObjectStorage

    @BeforeTest
    fun setup() {
        db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        candidateRepo = GeneratedCandidateRepository(db)
        storage = TestObjectStorage()

        val refImageRepo = ReferenceImageRepository(db, storage)

        handler = ImageGenerationHandler(
            imageProvider = TestImageProvider(),
            referenceImageRepository = refImageRepo,
            objectStorage = storage,
            generatedCandidateRepository = candidateRepo
        )
    }

    /**
     * TEST 1: Candidate Persistence Test
     * Verifies that persistCandidates() stores candidates with correct metadata
     * and SHA-256 checksums matching actual image bytes.
     */
    @Test
    fun `candidate persistence test - stores candidates with correct metadata and checksums`() {
        val jobId = UUID.randomUUID()
        val imageBytes = ByteArray(100) { it.toByte() }
        val expectedChecksum = calculateChecksum(imageBytes)

        val result = GenerationResult(
            status = GenerationStatus.COMPLETED,
            jobHandle = ProviderJobHandle(externalJobId = "test", providerIdentifier = "test"),
            candidates = listOf(
                GeneratedCandidate(id = UUID.randomUUID(), imageData = imageBytes, widthPx = 512, heightPx = 512)
            )
        )

        // Persist candidates
        val jobResult = handler.persistCandidates(jobId, result)

        // Verify success
        assertTrue(jobResult is ImageJobResult.Success)

        // Verify candidate persisted with correct metadata
        val persisted = candidateRepo.findByImageJob(jobId)
        assertEquals(1, persisted.size)

        val candidate = persisted[0]
        assertEquals(jobId, candidate.imageJobId)
        assertEquals("jobs/$jobId/candidates/0", candidate.storageKey)
        assertEquals("image/png", candidate.contentType)
        assertEquals(imageBytes.size.toLong(), candidate.fileSize)
        assertEquals(512, candidate.widthPx)
        assertEquals(512, candidate.heightPx)
        assertEquals(expectedChecksum, candidate.checksum)
        assertEquals(0, candidate.candidateIndex)
        assertNotNull(candidate.createdAt)

        // Verify bytes were actually stored
        val stored = storage.retrieve("jobs/$jobId/candidates/0")
        assertNotNull(stored)
        assertEquals(imageBytes.contentToString(), stored.content.contentToString())
    }

    /**
     * TEST 2: Multiple Candidate Test
     * Verifies that 3+ candidates with different bytes are all preserved
     * with correct indexes and distinct storage keys.
     */
    @Test
    fun `multiple candidate test - persists 3 candidates with different bytes correctly`() {
        val jobId = UUID.randomUUID()

        // Create 3 candidates with DELIBERATELY DIFFERENT bytes
        val bytes1 = ByteArray(100) { it.toByte() }
        val bytes2 = ByteArray(100) { (it + 1).toByte() }
        val bytes3 = ByteArray(100) { (it + 2).toByte() }

        val result = GenerationResult(
            status = GenerationStatus.COMPLETED,
            jobHandle = ProviderJobHandle(externalJobId = "test", providerIdentifier = "test"),
            candidates = listOf(
                GeneratedCandidate(id = UUID.randomUUID(), imageData = bytes1, widthPx = 512, heightPx = 512),
                GeneratedCandidate(id = UUID.randomUUID(), imageData = bytes2, widthPx = 512, heightPx = 512),
                GeneratedCandidate(id = UUID.randomUUID(), imageData = bytes3, widthPx = 512, heightPx = 512)
            )
        )

        // Persist all candidates
        val jobResult = handler.persistCandidates(jobId, result)
        assertTrue(jobResult is ImageJobResult.Success)

        // Verify all 3 candidates persisted
        val persisted = candidateRepo.findByImageJob(jobId)
        assertEquals(3, persisted.size)

        // Verify indexes are 0, 1, 2
        assertEquals(0, persisted[0].candidateIndex)
        assertEquals(1, persisted[1].candidateIndex)
        assertEquals(2, persisted[2].candidateIndex)

        // Verify storage keys are distinct
        val keys = persisted.map { it.storageKey }
        assertEquals(3, keys.distinct().size)

        // Verify each candidate has correct checksum and bytes
        assertEquals(calculateChecksum(bytes1), persisted[0].checksum)
        assertEquals(calculateChecksum(bytes2), persisted[1].checksum)
        assertEquals(calculateChecksum(bytes3), persisted[2].checksum)

        // Verify each candidate's bytes are correctly stored
        persisted.forEachIndexed { index, candidate ->
            val stored = storage.retrieve(candidate.storageKey)
            assertNotNull(stored)
            val expected = when(index) {
                0 -> bytes1
                1 -> bytes2
                2 -> bytes3
                else -> throw IllegalStateException()
            }
            assertEquals(expected.contentToString(), stored.content.contentToString())
        }
    }

    /**
     * TEST 3: Concurrent Candidates Test
     * Verifies that candidates from different image jobs don't interfere.
     */
    @Test
    fun `concurrent candidates test - different jobs don't interfere`() {
        val jobId1 = UUID.randomUUID()
        val jobId2 = UUID.randomUUID()
        val imageBytes = ByteArray(50) { it.toByte() }

        val result = GenerationResult(
            status = GenerationStatus.COMPLETED,
            jobHandle = ProviderJobHandle(externalJobId = "test", providerIdentifier = "test"),
            candidates = listOf(
                GeneratedCandidate(id = UUID.randomUUID(), imageData = imageBytes, widthPx = 512, heightPx = 512)
            )
        )

        // Persist candidates for two different jobs
        val result1 = handler.persistCandidates(jobId1, result)
        assertTrue(result1 is ImageJobResult.Success)

        val result2 = handler.persistCandidates(jobId2, result)
        assertTrue(result2 is ImageJobResult.Success)

        // Verify each job has its own candidate
        val candidates1 = candidateRepo.findByImageJob(jobId1)
        val candidates2 = candidateRepo.findByImageJob(jobId2)

        assertEquals(1, candidates1.size)
        assertEquals(1, candidates2.size)

        // Verify they have different storage keys (because jobId is part of the key)
        assertTrue(candidates1[0].storageKey != candidates2[0].storageKey)
        assertEquals(jobId1, candidates1[0].imageJobId)
        assertEquals(jobId2, candidates2[0].imageJobId)
    }

    /**
     * TEST 4: SHA-256 Checksum Test
     * Verifies that calculateChecksum() produces correct SHA-256 hashes.
     */
    @Test
    fun `checksum test - calculateChecksum produces correct SHA-256 hashes`() {
        val data1 = ByteArray(10) { it.toByte() }
        val data2 = ByteArray(10) { it.toByte() }
        val data3 = ByteArray(10) { (it + 1).toByte() }

        val hash1 = handler.calculateChecksum(data1)
        val hash2 = handler.calculateChecksum(data2)
        val hash3 = handler.calculateChecksum(data3)

        // Same data should produce same hash
        assertEquals(hash1, hash2)

        // Different data should produce different hash
        assertTrue(hash1 != hash3)

        // Hash should be hex string of 64 chars (256 bits = 32 bytes = 64 hex chars)
        assertEquals(64, hash1.length)
        assertTrue(hash1.all { it in '0'..'9' || it in 'a'..'f' })
    }

    /**
     * TEST 5: ObjectStorage Failure Test
     * Verifies that ObjectStorage failure during candidate persistence
     * is handled gracefully without creating orphaned records.
     */
    @Test
    fun `ObjectStorage failure test - handles storage failure gracefully`() {
        // Switch to failing storage
        val failingStorage = FailingObjectStorage()
        val refImageRepo = ReferenceImageRepository(db, failingStorage)
        val failingHandler = ImageGenerationHandler(
            imageProvider = TestImageProvider(),
            referenceImageRepository = refImageRepo,
            objectStorage = failingStorage,
            generatedCandidateRepository = candidateRepo
        )

        val jobId = UUID.randomUUID()
        val imageBytes = ByteArray(100) { it.toByte() }

        val result = GenerationResult(
            status = GenerationStatus.COMPLETED,
            jobHandle = ProviderJobHandle(externalJobId = "test", providerIdentifier = "test"),
            candidates = listOf(
                GeneratedCandidate(id = UUID.randomUUID(), imageData = imageBytes, widthPx = 512, heightPx = 512)
            )
        )

        // Attempt to persist with failing storage
        val jobResult = failingHandler.persistCandidates(jobId, result)

        // Verify failure is surfaced
        assertTrue(jobResult is ImageJobResult.Failure)
        val failure = jobResult as ImageJobResult.Failure
        assertTrue(failure.errorMessage.contains("Failed to persist"))

        // Verify no candidate was persisted (no orphaned record)
        val candidates = candidateRepo.findByImageJob(jobId)
        assertEquals(0, candidates.size, "Failed persistence should not create incomplete records")
    }

    @Test
    fun `retry persist replaces prior candidate rows - no duplicates`() {
        val jobId = UUID.randomUUID()
        val bytes1 = ByteArray(50) { 1 }
        val bytes2 = ByteArray(50) { 2 }

        val first = GenerationResult(
            status = GenerationStatus.COMPLETED,
            jobHandle = ProviderJobHandle(externalJobId = "t1", providerIdentifier = "test"),
            candidates = listOf(
                GeneratedCandidate(id = UUID.randomUUID(), imageData = bytes1, widthPx = 256, heightPx = 256)
            )
        )
        assertTrue(handler.persistCandidates(jobId, first) is ImageJobResult.Success)
        assertEquals(1, candidateRepo.findByImageJob(jobId).size)

        val second = GenerationResult(
            status = GenerationStatus.COMPLETED,
            jobHandle = ProviderJobHandle(externalJobId = "t2", providerIdentifier = "test"),
            candidates = listOf(
                GeneratedCandidate(id = UUID.randomUUID(), imageData = bytes2, widthPx = 256, heightPx = 256)
            )
        )
        assertTrue(handler.persistCandidates(jobId, second) is ImageJobResult.Success)
        val after = candidateRepo.findByImageJob(jobId)
        assertEquals(1, after.size, "retry must replace, not duplicate candidate rows")
        assertEquals(calculateChecksum(bytes2), after.single().checksum)
    }

    // ==================== Test Helpers ====================

    private class TestImageProvider : com.pinkdreams.imaging.provider.ImageProvider {
        override val providerId = "test"
        override val capabilities = com.pinkdreams.imaging.provider.ProviderCapabilities(
            maxCandidateCount = 4,
            supportsReferences = true,
            supportsMultipleReferences = true,
            supportedAspectRatios = listOf("1:1"),
            minWidthPx = 256,
            maxWidthPx = 2048,
            minHeightPx = 256,
            maxHeightPx = 2048,
            supportsCancellation = true,
            supportsIdempotency = true
        )
        override suspend fun submit(request: com.pinkdreams.imaging.provider.GenerationRequest) =
            GenerationResult(
                status = GenerationStatus.QUEUED,
                jobHandle = ProviderJobHandle(externalJobId = "test", providerIdentifier = "test")
            )
        override suspend fun getStatus(jobHandle: ProviderJobHandle) = TODO()
        override suspend fun getResult(jobHandle: ProviderJobHandle) = TODO()
        override suspend fun cancel(jobHandle: ProviderJobHandle) = true
    }

    private fun calculateChecksum(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }

    private class TestObjectStorage : ObjectStorage {
        private val storage = mutableMapOf<String, ByteArray>()

        override fun store(key: String, content: ByteArray, contentType: String): StorageMetadata {
            storage[key] = content
            return StorageMetadata(key = key, contentType = contentType, size = content.size.toLong())
        }

        override fun retrieve(key: String): StorageObject? =
            storage[key]?.let { content ->
                StorageObject(key = key, content = content, contentType = "image/png", size = content.size.toLong())
            }

        override fun delete(key: String): Boolean = storage.remove(key) != null

        override fun exists(key: String): Boolean = storage.containsKey(key)
    }

    private class FailingObjectStorage : ObjectStorage {
        override fun store(key: String, content: ByteArray, contentType: String): StorageMetadata {
            throw Exception("ObjectStorage.store() failed")
        }

        override fun retrieve(key: String): StorageObject? = throw Exception("ObjectStorage.retrieve() failed")

        override fun delete(key: String): Boolean = throw Exception("ObjectStorage.delete() failed")

        override fun exists(key: String): Boolean = throw Exception("ObjectStorage.exists() failed")
    }
}
