package com.pinkdreams.imaging

import com.pinkdreams.imaging.job.ImageJobType
import com.pinkdreams.imaging.observability.ImageGenerationEventRepository
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ImageJobRepository
import com.pinkdreams.persistence.repositories.PersonaIdentityRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import com.pinkdreams.storage.LocalFileObjectStorage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhaseIMGSecurityAndRetentionTest {

    @Test
    fun `redactSecrets strips api keys and pats`() {
        val raw = "failed Authorization: Bearer sk-abcdefghijklmnop github_pat_11ADT4KRQ0fake"
        val redacted = ImageGenerationEventRepository.redactSecrets(raw)!!
        assertFalse(redacted.contains("sk-abcdefghijklmnop"))
        assertFalse(redacted.contains("github_pat_11ADT4KRQ0fake"))
        assertTrue(redacted.contains("[REDACTED") || redacted.contains("REDACTED"))
    }

    @Test
    fun `redactSecrets null safe`() {
        assertNull(ImageGenerationEventRepository.redactSecrets(null))
    }

    @Test
    fun `local storage rejects oversized and bad mime`() {
        val dir = Files.createTempDirectory("img-sec")
        val store = LocalFileObjectStorage(dir, maxBytes = 10)
        try {
            var oversized = false
            try {
                store.store("jobs/a.png", ByteArray(20), "image/png")
            } catch (_: IllegalArgumentException) {
                oversized = true
            }
            assertTrue(oversized)

            var badMime = false
            try {
                store.store("jobs/b.bin", byteArrayOf(1, 2, 3), "text/html")
            } catch (_: IllegalArgumentException) {
                badMime = true
            }
            assertTrue(badMime)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `completeJobFailure stores redacted lastError`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val identity = PersonaIdentityRepository(db).create()
        val visual = PersonaVisualVersionRepository(db)
        val version = visual.create(identity.id, 1, "{}", author = "t")
        visual.publishVisualVersion(version.id)
        visual.activateVisualVersion(identity.id, version.id)

        val repo = ImageJobRepository(db)
        val job = repo.createJob(
            personaVisualVersionId = version.id,
            jobType = ImageJobType.IMAGE_GENERATION,
            idempotencyKey = "sec-err-1",
            requestPayload = "{}",
        )
        repo.claimJob(job.id, "w")
        val failed = repo.completeJobFailure(
            job.id,
            "provider rejected Authorization: Bearer sk-abcdefghijklmnop",
            shouldRetry = false,
        )
        assertFalse(failed.lastError!!.contains("sk-abcdefghijklmnop"))
        assertTrue(failed.lastError!!.contains("REDACTED") || failed.lastError!!.contains("[REDACTED"))
    }
}
