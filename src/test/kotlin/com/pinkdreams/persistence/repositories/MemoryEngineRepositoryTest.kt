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

class MemoryEngineRepositoryTest {

    private fun fixture(): MemoryEngineRepository = MemoryEngineRepository(DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) })

    @Test
    fun `first version is 1 and sequential creation increments`() {
        val repo = fixture()
        val v1 = repo.createNextVersion("content v1")
        val v2 = repo.createNextVersion("content v2")
        assertEquals(1, v1.version)
        assertEquals(2, v2.version)
        assertEquals("draft", v1.status)
    }

    @Test
    fun `client cannot supply a version through createNextVersion`() {
        val repo = fixture()
        val created = repo.createNextVersion(content = "content", changelogNote = "note")
        assertEquals(1, created.version)
    }

    @Test
    fun `next version uses maximum existing version not row count`() {
        val repo = fixture()
        repo.create(1, "v1")
        repo.create(5, "v5")
        repo.create(3, "v3")
        assertEquals(6, repo.createNextVersion("next").version)
    }

    @Test
    fun `full lifecycle draft publish activate archive`() {
        val repo = fixture()
        val draft = repo.createNextVersion("content")
        val published = repo.publish(draft.id)
        assertEquals("published", published.status)
        val activated = repo.activate(published.id)
        assertTrue(activated.isActive)
        assertEquals(activated.id, repo.getActiveEngine()?.id)

        assertFailsWith<IllegalStateException> { repo.archive(activated.id) }

        val v2 = repo.activate(repo.publish(repo.createNextVersion("content v2").id).id)
        assertTrue(!repo.findById(activated.id)!!.isActive, "Activating v2 must deactivate v1")
        val archived = repo.archive(activated.id)
        assertEquals("archived", archived.status)
        assertTrue(v2.isActive)
    }

    @Test
    fun `only one memory engine can be active at a time`() {
        val repo = fixture()
        val v1 = repo.activate(repo.publish(repo.createNextVersion("v1").id).id)
        val v2 = repo.activate(repo.publish(repo.createNextVersion("v2").id).id)

        assertTrue(v2.isActive)
        assertTrue(!repo.findById(v1.id)!!.isActive)
        assertEquals(v2.id, repo.getActiveEngine()?.id)
    }

    @Test
    fun `only draft can publish only published can activate or archive`() {
        val repo = fixture()
        val draft = repo.createNextVersion("content")
        assertFailsWith<IllegalArgumentException> { repo.activate(draft.id) }
        val published = repo.publish(draft.id)
        assertFailsWith<IllegalArgumentException> { repo.publish(published.id) }
    }

    @Test
    fun `no active engine returns null`() {
        val repo = fixture()
        repo.createNextVersion("draft content")
        assertNull(repo.getActiveEngine())
    }

    // --- Concurrency: real threads racing for the next version ---
    @Test
    fun `concurrent creation never produces duplicate versions`() {
        val repo = fixture()
        val barrier = CyclicBarrier(2)
        val resultA = AtomicReference<MemoryEngineRepository.MemoryEngine>()
        val resultB = AtomicReference<MemoryEngineRepository.MemoryEngine>()
        val errorA = AtomicReference<Throwable>()
        val errorB = AtomicReference<Throwable>()

        val threadA = Thread {
            try {
                barrier.await()
                resultA.set(repo.createNextVersion("content-A"))
            } catch (t: Throwable) { errorA.set(t) }
        }
        val threadB = Thread {
            try {
                barrier.await()
                resultB.set(repo.createNextVersion("content-B"))
            } catch (t: Throwable) { errorB.set(t) }
        }
        threadA.start(); threadB.start()
        threadA.join(30_000); threadB.join(30_000)

        assertNull(errorA.get()); assertNull(errorB.get())
        assertNotNull(resultA.get()); assertNotNull(resultB.get())
        assertTrue(resultA.get().version != resultB.get().version)
        assertEquals(setOf(1, 2), setOf(resultA.get().version, resultB.get().version))
        val all = repo.findAll()
        assertEquals(2, all.size)
        assertEquals(all.size, all.map { it.version }.toSet().size)
    }
}
