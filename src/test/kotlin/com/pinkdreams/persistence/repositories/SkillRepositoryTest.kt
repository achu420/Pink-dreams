package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.DatabaseFactory
import org.junit.jupiter.api.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillRepositoryTest {

    private fun fixture(): SkillRepository = SkillRepository(DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) })

    // --- Persistence / versioning ---

    @Test
    fun `first version for a new key is 1`() {
        val repo = fixture()
        val created = repo.createNextVersion("flirting", "content v1")
        assertEquals(1, created.version)
        assertEquals("draft", created.status)
        assertEquals("flirting", created.key)
    }

    @Test
    fun `sequential creation for the same key yields sequential versions`() {
        val repo = fixture()
        val v1 = repo.createNextVersion("flirting", "v1")
        val v2 = repo.createNextVersion("flirting", "v2")
        val v3 = repo.createNextVersion("flirting", "v3")
        assertEquals(listOf(1, 2, 3), listOf(v1.version, v2.version, v3.version))
    }

    @Test
    fun `next version uses maximum existing version for the key not row count`() {
        val repo = fixture()
        repo.create("flirting", 1, "v1")
        repo.create("flirting", 5, "v5")
        repo.create("flirting", 3, "v3")
        val next = repo.createNextVersion("flirting", "next")
        assertEquals(6, next.version)
    }

    @Test
    fun `different keys version independently`() {
        val repo = fixture()
        repo.createNextVersion("flirting", "f1")
        repo.createNextVersion("flirting", "f2")
        val friendshipFirst = repo.createNextVersion("friendship", "fr1")
        assertEquals(1, friendshipFirst.version, "friendship's numbering must not be affected by flirting's version count")
    }

    @Test
    fun `client cannot supply a version through the createNextVersion contract`() {
        // createNextVersion has no version parameter at all — a compile-time
        // guarantee. The legacy explicit-version create() remains available for
        // existing fixtures/tests, unchanged.
        val repo = fixture()
        val created = repo.createNextVersion(key = "flirting", content = "content", changelogNote = "note")
        assertEquals(1, created.version)
    }

    // --- Lifecycle ---

    @Test
    fun `full lifecycle draft to published to active to archived`() {
        val repo = fixture()
        val draft = repo.createNextVersion("flirting", "content")
        assertEquals("draft", draft.status)

        val published = repo.publish(draft.id)
        assertEquals("published", published.status)
        assertTrue(!published.isActive)

        val activated = repo.activate(published.id)
        assertTrue(activated.isActive)
        assertEquals(activated.id, repo.getActiveForKey("flirting")?.id)

        assertFailsWith<IllegalStateException> { repo.archive(activated.id) }

        // deactivate by activating a newer version, then the old one can be archived
        val v2 = repo.publish(repo.createNextVersion("flirting", "content v2").id)
        repo.activate(v2.id)
        val archived = repo.archive(activated.id)
        assertEquals("archived", archived.status)
    }

    @Test
    fun `only draft skills can be published`() {
        val repo = fixture()
        val draft = repo.createNextVersion("flirting", "content")
        val published = repo.publish(draft.id)
        assertFailsWith<IllegalArgumentException> { repo.publish(published.id) }
    }

    @Test
    fun `only published skills can be activated`() {
        val repo = fixture()
        val draft = repo.createNextVersion("flirting", "content")
        assertFailsWith<IllegalArgumentException> { repo.activate(draft.id) }
    }

    // --- Multiple simultaneously-active skills across different keys ---

    @Test
    fun `multiple different keys can each have their own active version at the same time`() {
        val repo = fixture()
        val flirtingV1 = repo.activate(repo.publish(repo.createNextVersion("flirting", "flirting content").id).id)
        val friendshipV2 = run {
            repo.activate(repo.publish(repo.createNextVersion("friendship", "v1").id).id)
            repo.activate(repo.publish(repo.createNextVersion("friendship", "v2").id).id)
        }
        val datingV1 = repo.activate(repo.publish(repo.createNextVersion("dating", "dating content").id).id)

        assertTrue(flirtingV1.isActive)
        assertTrue(friendshipV2.isActive)
        assertTrue(datingV1.isActive)
        assertEquals(1, flirtingV1.version)
        assertEquals(2, friendshipV2.version)
        assertEquals(1, datingV1.version)

        assertEquals(setOf("flirting", "friendship", "dating"), repo.findAllActiveKeys().toSet())
    }

    @Test
    fun `activating a new version for a key deactivates the previous active version for that key only`() {
        val repo = fixture()
        val flirtingV1 = repo.activate(repo.publish(repo.createNextVersion("flirting", "v1").id).id)
        val friendshipV1 = repo.activate(repo.publish(repo.createNextVersion("friendship", "v1").id).id)

        val flirtingV2 = repo.activate(repo.publish(repo.createNextVersion("flirting", "v2").id).id)

        assertTrue(flirtingV2.isActive)
        assertTrue(!repo.findById(flirtingV1.id)!!.isActive, "Old flirting version must be deactivated")
        assertTrue(repo.findById(friendshipV1.id)!!.isActive, "Unrelated key's active version must be untouched")
    }

    @Test
    fun `getActiveForKey returns null when no version of that key is active`() {
        val repo = fixture()
        repo.createNextVersion("flirting", "draft content")
        assertNull(repo.getActiveForKey("flirting"))
        assertNull(repo.getActiveForKey("nonexistent_key"))
    }

    // --- Concurrency: real threads racing for the next version of the SAME key ---

    @Test
    fun `concurrent creation for the same key never produces duplicate versions`() {
        val repo = fixture()
        val barrier = CyclicBarrier(2)
        val resultA = AtomicReference<SkillRepository.Skill>()
        val resultB = AtomicReference<SkillRepository.Skill>()
        val errorA = AtomicReference<Throwable>()
        val errorB = AtomicReference<Throwable>()

        val threadA = Thread {
            try {
                barrier.await()
                resultA.set(repo.createNextVersion("flirting", "content-A"))
            } catch (t: Throwable) {
                errorA.set(t)
            }
        }
        val threadB = Thread {
            try {
                barrier.await()
                resultB.set(repo.createNextVersion("flirting", "content-B"))
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
        assertNotNull(resultA.get())
        assertNotNull(resultB.get())
        assertTrue(resultA.get().version != resultB.get().version)
        assertEquals(setOf(1, 2), setOf(resultA.get().version, resultB.get().version))

        val all = repo.findByKey("flirting")
        assertEquals(2, all.size)
        assertEquals(all.size, all.map { it.version }.toSet().size, "No duplicate version numbers may exist for the same key")
    }
}
