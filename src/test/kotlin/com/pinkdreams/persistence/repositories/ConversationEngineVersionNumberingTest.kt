package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.DatabaseFactory
import org.junit.jupiter.api.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Server-computed conversation-engine-version numbering (Phase 3B), mirroring
 * PersonaCoreVersionNumberingTest's pattern.
 *
 * Unlike Persona Core Versions, there is no separate "engine identity" column:
 * each row is itself a complete versioned engine, so the next version is a
 * single GLOBAL maximum across all rows, not scoped per parent id.
 */
class ConversationEngineVersionNumberingTest {

    private fun fixture(): ConversationEngineRepository {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        return ConversationEngineRepository(db)
    }

    @Test
    fun `empty engine table receives version 1`() {
        val repo = fixture()

        val created = repo.createNextVersion("first content")

        assertEquals(1, created.version)
        assertEquals("draft", created.status)
    }

    @Test
    fun `sequential creation yields sequential version numbers`() {
        val repo = fixture()

        val v1 = repo.createNextVersion("content 1")
        val v2 = repo.createNextVersion("content 2")
        val v3 = repo.createNextVersion("content 3")

        assertEquals(1, v1.version)
        assertEquals(2, v2.version)
        assertEquals(3, v3.version)
    }

    @Test
    fun `client cannot supply a version number through the repository contract`() {
        // createNextVersion has no version parameter at all — this is a
        // compile-time guarantee, not merely a runtime check. The legacy
        // create() method (used by pre-existing tests/fixtures) still accepts
        // an explicit version and is unchanged, preserving backward compatibility.
        val repo = fixture()

        val created = repo.createNextVersion(content = "content", changelogNote = "note")
        assertEquals(1, created.version)
    }

    @Test
    fun `next version uses the maximum existing version not row count or most recent insert`() {
        val repo = fixture()

        // Deliberately non-sequential and out-of-order-by-insertion, using the
        // legacy explicit-version create() to simulate pre-existing data whose
        // highest version number is not its most recently created row.
        repo.create(version = 1, content = "v1", status = "draft")
        repo.create(version = 5, content = "v5", status = "draft")
        repo.create(version = 3, content = "v3", status = "draft")

        val next = repo.createNextVersion("next content")

        assertEquals(6, next.version, "Must be max(1,5,3) + 1, not count()+1 (=4) or most-recent-row+1")
    }

    @Test
    fun `changelog note and content persist correctly through server computed creation`() {
        val repo = fixture()

        val created = repo.createNextVersion(
            content = "the actual content",
            changelogNote = "adjusted tone",
            createdBy = "admin-1",
        )

        val reloaded = repo.findById(created.id)
        assertEquals("the actual content", reloaded?.content)
        assertEquals("adjusted tone", reloaded?.changelogNote)
        assertEquals("admin-1", reloaded?.createdBy)
        assertEquals("draft", reloaded?.status)
    }

    // --- Concurrency: two real threads racing for the next version, synchronized
    // with a CyclicBarrier so both attempt simultaneously rather than relying on
    // sleep-based timing (which would be flaky). ---
    @Test
    fun `concurrent creation attempts never produce duplicate engine versions`() {
        val repo = fixture()

        val barrier = CyclicBarrier(2)
        val resultA = AtomicReference<ConversationEngineRepository.ConversationEngine>()
        val resultB = AtomicReference<ConversationEngineRepository.ConversationEngine>()
        val errorA = AtomicReference<Throwable>()
        val errorB = AtomicReference<Throwable>()

        val threadA = Thread {
            try {
                barrier.await() // both threads release at (as close as the JVM allows to) the same instant
                resultA.set(repo.createNextVersion("content-A"))
            } catch (t: Throwable) {
                errorA.set(t)
            }
        }
        val threadB = Thread {
            try {
                barrier.await()
                resultB.set(repo.createNextVersion("content-B"))
            } catch (t: Throwable) {
                errorB.set(t)
            }
        }

        threadA.start()
        threadB.start()
        threadA.join(30_000)
        threadB.join(30_000)

        assertNull(errorA.get(), "Thread A must not fail: ${errorA.get()}")
        assertNull(errorB.get(), "Thread B must not fail: ${errorB.get()}")

        val versionA = resultA.get()
        val versionB = resultB.get()
        assertTrue(versionA != null && versionB != null, "Both concurrent attempts must succeed via retry")
        assertTrue(versionA.version != versionB.version, "Concurrent attempts must never be assigned the same version number")
        assertEquals(setOf(1, 2), setOf(versionA.version, versionB.version))

        // Final persisted state must remain uniquely numbered — the actual
        // invariant the single-column unique index on `version` guarantees.
        val allEngines = repo.findAll()
        assertEquals(2, allEngines.size)
        assertEquals(allEngines.size, allEngines.map { it.version }.toSet().size, "No duplicate version numbers may exist")
    }
}
