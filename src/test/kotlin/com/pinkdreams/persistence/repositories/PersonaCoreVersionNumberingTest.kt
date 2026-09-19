package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.DatabaseFactory
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Server-computed persona-core-version numbering (Phase 3A Part 2/3).
 *
 * Covers: empty persona, sequential creation, a persona whose highest version
 * is not its most-recently-inserted row (proving createNextVersion uses
 * MAX(version), never row-creation-order or row count), and genuine
 * concurrent creation using real threads synchronized on a CyclicBarrier —
 * not a timing-based sleep — so the race is forced deterministically rather
 * than hoped for.
 */
class PersonaCoreVersionNumberingTest {

    private fun fixture(): Pair<PersonaRepository, PersonaCoreVersionRepository> {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        return PersonaRepository(db) to PersonaCoreVersionRepository(db)
    }

    private fun createPersona(personaRepository: PersonaRepository) = personaRepository.create(
        slug = "numbering-${UUID.randomUUID()}",
        displayName = "Test",
        gender = "female",
        orientation = "straight",
        apparentAge = 27,
        languageProfile = emptyMap(),
    )

    @Test
    fun `empty persona receives version 1`() {
        val (personaRepository, versionRepository) = fixture()
        val persona = createPersona(personaRepository)

        val created = versionRepository.createNextVersion(persona.id, "first content")

        assertEquals(1, created.version)
        assertEquals(persona.id, created.personaId)
        assertEquals("draft", created.status)
    }

    @Test
    fun `sequential creation yields sequential version numbers`() {
        val (personaRepository, versionRepository) = fixture()
        val persona = createPersona(personaRepository)

        val v1 = versionRepository.createNextVersion(persona.id, "content 1")
        val v2 = versionRepository.createNextVersion(persona.id, "content 2")
        val v3 = versionRepository.createNextVersion(persona.id, "content 3")

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
        val (personaRepository, versionRepository) = fixture()
        val persona = createPersona(personaRepository)

        val created = versionRepository.createNextVersion(
            personaId = persona.id,
            content = "content",
            changelogNote = "note",
        )
        assertEquals(1, created.version)
    }

    @Test
    fun `next version uses the maximum existing version not row count or most recent insert`() {
        val (personaRepository, versionRepository) = fixture()
        val persona = createPersona(personaRepository)

        // Deliberately non-sequential and out-of-order-by-insertion, using the
        // legacy explicit-version create() to simulate pre-existing data whose
        // highest version number is not its most recently created row.
        versionRepository.create(persona.id, 1, "v1", "draft")
        versionRepository.create(persona.id, 5, "v5", "draft")
        versionRepository.create(persona.id, 3, "v3", "draft")

        val next = versionRepository.createNextVersion(persona.id, "next content")

        assertEquals(6, next.version, "Must be max(1,5,3) + 1, not count()+1 (=4) or most-recent-row+1")
    }

    @Test
    fun `changelog note and content persist correctly through server computed creation`() {
        val (personaRepository, versionRepository) = fixture()
        val persona = createPersona(personaRepository)

        val created = versionRepository.createNextVersion(
            personaId = persona.id,
            content = "the actual content",
            changelogNote = "adjusted tone",
            author = "admin-1",
        )

        val reloaded = versionRepository.findById(created.id)
        assertEquals("the actual content", reloaded?.content)
        assertEquals("adjusted tone", reloaded?.changelogNote)
        assertEquals("admin-1", reloaded?.author)
        assertEquals(persona.id, reloaded?.personaId)
        assertEquals("draft", reloaded?.status)
    }

    @Test
    fun `different personas number their versions independently`() {
        val (personaRepository, versionRepository) = fixture()
        val personaA = createPersona(personaRepository)
        val personaB = createPersona(personaRepository)

        versionRepository.createNextVersion(personaA.id, "a1")
        versionRepository.createNextVersion(personaA.id, "a2")
        val bFirst = versionRepository.createNextVersion(personaB.id, "b1")

        assertEquals(1, bFirst.version, "Persona B's numbering must not be affected by persona A's version count")
    }

    // --- Concurrency: two real threads racing for the next version, synchronized
    // with a CyclicBarrier so both attempt simultaneously rather than relying on
    // sleep-based timing (which would be flaky). ---
    @Test
    fun `concurrent creation attempts never produce duplicate persona and version pairs`() {
        val (personaRepository, versionRepository) = fixture()
        val persona = createPersona(personaRepository)

        val barrier = CyclicBarrier(2)
        val resultA = AtomicReference<PersonaCoreVersionRepository.PersonaCoreVersion>()
        val resultB = AtomicReference<PersonaCoreVersionRepository.PersonaCoreVersion>()
        val errorA = AtomicReference<Throwable>()
        val errorB = AtomicReference<Throwable>()

        val threadA = Thread {
            try {
                barrier.await() // both threads release at (as close as the JVM allows to) the same instant
                resultA.set(versionRepository.createNextVersion(persona.id, "content-A"))
            } catch (t: Throwable) {
                errorA.set(t)
            }
        }
        val threadB = Thread {
            try {
                barrier.await()
                resultB.set(versionRepository.createNextVersion(persona.id, "content-B"))
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

        // Final persisted state must remain uniquely numbered per persona — the
        // actual invariant the (persona_id, version) unique index guarantees.
        val allVersions = versionRepository.findForPersona(persona.id)
        assertEquals(2, allVersions.size)
        assertEquals(allVersions.size, allVersions.map { it.version }.toSet().size, "No duplicate version numbers may exist")
    }
}
